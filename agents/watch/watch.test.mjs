import { test } from 'node:test';
import assert from 'node:assert/strict';
import { runOnce, decide } from './index.js';
const mem = () => { const m = new Map(); return { get: async (k) => m.get(k), put: async (k, v) => { m.set(k, v); } }; };
const env = { CONSOLE_URL: 'https://api.example', AGENT_TOKEN: 'x'.repeat(40) };
const res = (status, body = {}, ct = 'application/json') => ({ ok: status < 400, status, headers: { get: () => ct }, json: async () => body });
const net = (over = {}, sent = []) => async (url, o = {}) => {
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
