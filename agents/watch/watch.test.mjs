import { test } from 'node:test';
import assert from 'node:assert/strict';
import { runOnce, decide, probe } from './index.js';
const mem = () => { const m = new Map(); return { get: async (k) => m.get(k), put: async (k, v) => { m.set(k, v); } }; };
const env = { CONSOLE_URL: 'https://api.example', AGENT_TOKEN: 'x'.repeat(40) };
const res = (status, body = {}, ct = 'application/json') => ({ ok: status < 400, status, headers: { get: () => ct }, json: async () => body });
const net = (over = {}, sent = [], ticks = []) => async (url, o = {}) => {
  if (o.method === 'POST' && url.endsWith('/v1/agent/tick')) { ticks.push(o.headers.authorization ? 'auth' : 'noauth'); return over.tick ? over.tick() : res(200, { ok: true, orchestrator: { lease: 'acquired', run: '12' }, open_findings: {} }); }
  if (o.method === 'POST') { sent.push(JSON.parse(o.body)); return res(over.report ?? 201); }
  if (over[url]) return over[url];
  if (url.includes('/livez')) return res(200, { ok: true });
  if (url.includes('/healthz')) return res(200, { ok: true });
  return res(200, {}, 'text/html');
};

test('all fine: writes nothing to the console (database can sleep)', async () => {
  const sent = [], st = mem();
  const r = await runOnce(env, st, net({}, sent), 1_000_000);
  assert.equal(r.next.status, 'ok'); assert.equal(sent.length, 0);
});
test('first failure is reported once, then only every 15 minutes while it lasts', async () => {
  const sent = [], st = mem(), f = net({ 'https://yeehaw.pro/': res(404, {}, 'text/html') }, sent);
  await runOnce(env, st, f, 1_000_000); assert.equal(sent.length, 1); assert.equal(sent[0].status, 'fail');
  await runOnce(env, st, f, 1_030_000); assert.equal(sent.length, 1);
  await runOnce(env, st, f, 1_000_000 + 15 * 60_000); assert.equal(sent.length, 2);
});
test('recovery is reported', async () => {
  const sent = [], st = mem();
  await runOnce(env, st, net({ 'https://yeehaw.pro/': res(500) }, sent), 1_000_000);
  await runOnce(env, st, net({}, sent), 1_030_000);
  assert.deepEqual(sent.map((x) => x.status), ['fail', 'ok']);
});
test('a 403 is access protection, not an outage', async () => {
  const sent = [], st = mem();
  const r = await runOnce(env, st, net({ 'https://yeehaw.pro/': res(403, {}, 'text/html') }, sent), 1_000_000);
  assert.equal(r.next.status, 'ok'); assert.equal(sent.length, 0); assert.equal(r.next.warn.length, 1);
});
test('API without /livez but answering JSON 404 counts as alive; a network error counts as failing', async () => {
  const st = mem();
  const a = await runOnce(env, st, net({ 'https://api.example/livez': res(404, { error: 'not_found' }) }), 1_000_000);
  assert.equal(a.next.status, 'ok');
  const b = await runOnce(env, mem(), async () => { throw new Error('boom'); }, 1_000_000);
  assert.equal(b.next.status, 'fail');
});
test('database is probed only every 30 minutes', async () => {
  const urls = [], st = mem();
  const f = async (u, o = {}) => { urls.push(u); return o.method === 'POST' ? res(201) : u.includes('/livez') || u.includes('/healthz') ? res(200, { ok: true }) : res(200, {}, 'text/html'); };
  await runOnce(env, st, f, 1_000_000); await runOnce(env, st, f, 1_030_000); await runOnce(env, st, f, 1_000_000 + 30 * 60_000);
  assert.equal(urls.filter((u) => u.includes('/healthz')).length, 2);
});
test('delivery failure is kept and retried; bad token is refused without echoing it', async () => {
  const st = mem(), sent = [];
  const r1 = await runOnce(env, st, net({ 'https://yeehaw.pro/': res(500), report: 401 }, sent), 1_000_000);
  assert.equal(r1.next.deliveryError, 'HTTP 401'); assert.equal(r1.next.lastReportedAt, 0);
  const r2 = await runOnce({ ...env, AGENT_TOKEN: 'a\nb' }, mem(), net({ 'https://yeehaw.pro/': res(500) }, sent), 1_000_000);
  assert.match(r2.next.deliveryError, /missing or unsendable/);
});
test('decide: unchanged ok state produces no report', () => {
  assert.equal(decide({ status: 'ok' }, [{ ok: true }], 5).report, null);
});

// ---- diagnosis + queue ----
import { classify } from './index.js';
const R = (name, kind, ok, extra = {}) => ({ name, kind, ok, status: ok ? 200 : 500, html: false, note: ok ? undefined : `${name}: bad`, ...extra });

test('classify: API answering HTML = wrong deployment (the 2026-11-27 incident)', () => {
  const i = classify([R('site', 'page', true), R('api /livez', 'api-live', false, { status: 200, html: true, note: 'api /livez: HTTP 200 returned an HTML page where the API must answer JSON' })]);
  assert.equal(i.kind, 'wrong-deployment'); assert.equal(i.scope, 'api'); assert.match(i.next, /Root Directory/);
});
test('classify: site outage is distinguished from API outage', () => {
  assert.equal(classify([R('site', 'page', false), R('api', 'api-live', true)]).kind, 'website-outage');
  assert.equal(classify([R('site', 'page', true), R('api', 'api-live', false)]).kind, 'api-outage');
  assert.equal(classify([R('site', 'page', false), R('api', 'api-live', false)]).kind, 'platform-outage');
  assert.equal(classify([R('site', 'page', true), R('api', 'api-live', true), R('db', 'api-db', false)]).kind, 'database-outage');
  assert.equal(classify([R('site', 'page', true), R('api', 'api-live', true)]), null);
});
test('probe: HTML 200 from the API is NOT healthy', async () => {
  const f = async () => new Response('<html>site</html>', { status: 200, headers: { 'content-type': 'text/html' } });
  const r = await probe(f, 'api /livez', 'https://api.x/livez', 'api-live');
  assert.equal(r.ok, false); assert.equal(r.html, true);
});
test('queue: reports are held while the console refuses them, then delivered in order after recovery', async () => {
  const store = new Map(); const storage = { get: async (k) => store.get(k), put: async (k, v) => store.set(k, v) };
  const env = { CONSOLE_URL: 'https://api.x', AGENT_TOKEN: 'tok123' };
  const posts = []; let reportingUp = false; let apiUp = false;
  const f = async (url, o = {}) => {
    if (url.endsWith('/v1/agent/report')) { posts.push(JSON.parse(o.body)); return new Response('{}', { status: reportingUp ? 201 : 503 }); }
    if (url.startsWith('https://yeehaw.pro')) return new Response('ok', { status: 200 });
    if (!apiUp) return new Response('<html/>', { status: 200, headers: { 'content-type': 'text/html' } });
    return Response.json({ ok: true });
  };
  let t = 1_000_000;
  let o = await runOnce(env, storage, f, t);           // fail detected, delivery refused (503)
  assert.equal(o.next.queued, 1); assert.ok(o.next.deliveryError);
  apiUp = true; t += 30_000;
  o = await runOnce(env, storage, f, t);               // recovered, still refused -> 2 queued
  assert.equal(o.next.queued, 2);
  reportingUp = true; posts.length = 0; t += 30_000;
  o = await runOnce(env, storage, f, t + 20 * 60_000); // no new state change, but queue flushes
  assert.equal(o.next.queued, 0); assert.equal(o.next.deliveryError, undefined);
});

import { selfTest } from './index.js';
test('selfTest sends one report with the Worker token, is rate limited, and never returns the token', async () => {
  const store = new Map(); const storage = { get: async (k) => store.get(k), put: async (k, v) => { store.set(k, v); } };
  const calls = [];
  const f = async (u, o) => { calls.push({ u, o }); return { status: 201 }; };
  const env = { CONSOLE_URL: 'https://api.example/', AGENT_TOKEN: 'sekret-token-value' };
  const a = await selfTest(env, storage, f, 1_000_000);
  assert.equal(a.accepted, true); assert.equal(calls.length, 1);
  assert.equal(calls[0].u, 'https://api.example/v1/agent/report'); assert.equal(calls[0].o.headers.authorization, 'Bearer sekret-token-value');
  assert.ok(!JSON.stringify(a).includes('sekret'));
  const b = await selfTest(env, storage, f, 1_000_000 + 60_000);
  assert.equal(b.sent, false); assert.equal(calls.length, 1);
  assert.equal((await selfTest({ CONSOLE_URL: 'x' }, new Map().constructor === Map ? { get: async () => 0, put: async () => {} } : null, f, 9e9)).sent, false);
  const rej = await selfTest(env, { get: async () => 0, put: async () => {} }, async () => ({ status: 401 }), 9e9);
  assert.equal(rej.accepted, false); assert.equal(rej.httpStatus, 401);
});

test('scheduled orchestrator: first pass at once, then hourly while fine, immediately on a state change, every 5 minutes during an incident', async () => {
  const st = mem(), ticks = [], sent = []; const f = net({}, sent, ticks), bad = net({ 'https://yeehaw.pro/': res(500) }, sent, ticks);
  const T = 10_000_000;
  await runOnce(env, st, f, T); assert.equal(ticks.length, 1, 'first run triggers a pass');
  await runOnce(env, st, f, T + 30_000); await runOnce(env, st, f, T + 30 * 60_000); assert.equal(ticks.length, 1, 'no extra passes while fine');
  await runOnce(env, st, f, T + 60 * 60_000); assert.equal(ticks.length, 2, 'hourly pass');
  await runOnce(env, st, bad, T + 61 * 60_000); assert.equal(ticks.length, 3, 'state change triggers a pass at once');
  await runOnce(env, st, bad, T + 62 * 60_000); assert.equal(ticks.length, 3);
  await runOnce(env, st, bad, T + 67 * 60_000); assert.equal(ticks.length, 4, 'every 5 minutes while failing');
  const s = await st.get('state'); assert.equal(s.lastTick.http, 200); assert.equal(s.lastTick.run, '12');
});
test('scheduled orchestrator: a refused trigger is retried after 10 minutes, never in a tight loop; no token means no trigger', async () => {
  const st = mem(), ticks = []; const f = net({ tick: () => res(503) }, [], ticks); const T = 5_000_000;
  await runOnce(env, st, f, T); assert.equal(ticks.length, 1);
  await runOnce(env, st, f, T + 30_000); await runOnce(env, st, f, T + 5 * 60_000); assert.equal(ticks.length, 1);
  await runOnce(env, st, f, T + 10 * 60_000); assert.equal(ticks.length, 2); assert.equal((await st.get('state')).tickFailures, 2);
  const t2 = []; await runOnce({ ...env, AGENT_TOKEN: '' }, mem(), net({}, [], t2), T); assert.equal(t2.length, 0);
});
test('scheduled orchestrator: not triggered while reports are still queued (the diagnostic would see stale evidence)', async () => {
  const st = mem(), ticks = []; await runOnce(env, st, net({ 'https://yeehaw.pro/': res(500), report: 503 }, [], ticks), 10_000_000); assert.equal(ticks.length, 0);
});
