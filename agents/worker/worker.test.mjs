import { test } from 'node:test';
import assert from 'node:assert/strict';
import { runAgents } from './index.js';
const W = { wait: 1 };

const env = { CONSOLE_URL: 'https://api.example', AGENT_TOKEN: 'x'.repeat(40) };
const mk = (map) => async (url, opts = {}) => {
  const key = (opts.method || 'GET') + ' ' + url;
  const h = map[key];
  if (!h) throw new Error('unexpected ' + key);
  return typeof h === 'function' ? h(opts) : h;
};
const res = (status, body = {}) => ({ ok: status < 400, status, json: async () => body });

test('all healthy: reports ok and runs the tick', async () => {
  const sent = [];
  const f = mk({
    'GET https://yeehaw.pro/': res(200), 'GET https://yeehaw.pro/privacy/': res(200), 'GET https://yeehaw.pro/support/': res(200),
    'GET https://api.example/healthz': res(200, { ok: true }),
    'POST https://api.example/v1/agent/report': (o) => { sent.push(JSON.parse(o.body)); return res(201); },
    'POST https://api.example/v1/agent/tick': res(200, { ok: true, fleet: 'ok', db: 'ok', db_ms: 3, open_findings: 0 }),
  });
  const r = await runAgents(env, f, W);
  assert.equal(r.ok, true);
  assert.equal(sent[0].agent, 'site-health'); assert.equal(sent[0].status, 'ok');
});

test('a failing page is reported with its URL, never as ok', async () => {
  const sent = [];
  const f = mk({
    'GET https://yeehaw.pro/': res(200), 'GET https://yeehaw.pro/privacy/': res(404), 'GET https://yeehaw.pro/support/': res(200),
    'GET https://api.example/healthz': res(200, { ok: true }),
    'POST https://api.example/v1/agent/report': (o) => { sent.push(JSON.parse(o.body)); return res(201); },
    'POST https://api.example/v1/agent/tick': res(200, { ok: true, fleet: 'ok', db: 'ok', db_ms: 3, open_findings: 0 }),
  });
  const r = await runAgents(env, f, W);
  assert.equal(r.ok, false);
  assert.equal(sent[0].status, 'fail'); assert.match(sent[0].details.bad[0], /privacy.*404/);
});

test('missing secrets: says so, does not pretend', async () => {
  const r = await runAgents({}, async () => { throw new Error('must not call'); });
  assert.equal(r.ok, false); assert.match(r.error, /AGENT_TOKEN/);
});

const base = (over = {}) => ({
  'GET https://yeehaw.pro/': res(200), 'GET https://yeehaw.pro/privacy/': res(200), 'GET https://yeehaw.pro/support/': res(200),
  'GET https://api.example/healthz': res(200, { ok: true }), ...over });

test('tick says database down: HTTP 200 is not accepted as healthy', async () => {
  const sent = [];
  const f = mk(base({ 'POST https://api.example/v1/agent/report': (o) => { sent.push(JSON.parse(o.body)); return res(201); },
    'POST https://api.example/v1/agent/tick': res(200, { ok: false, fleet: 'ok', db: 'fail' }) }));
  const r = await runAgents(env, f, W);
  assert.equal(r.ok, false);
  const s = sent.find((x) => x.agent === 'scheduler'); assert.equal(s.status, 'fail'); assert.match(s.title, /database fail/);
});

test('fleet fail is surfaced as scheduler fail', async () => {
  const sent = [];
  const f = mk(base({ 'POST https://api.example/v1/agent/report': (o) => { sent.push(JSON.parse(o.body)); return res(201); },
    'POST https://api.example/v1/agent/tick': res(200, { ok: true, fleet: 'fail', db: 'ok' }) }));
  assert.equal((await runAgents(env, f, W)).ok, false);
  assert.equal(sent.find((x) => x.agent === 'scheduler').status, 'fail');
});

test('a rejected report is detected, not ignored', async () => {
  const f = mk(base({ 'POST https://api.example/v1/agent/report': res(401),
    'POST https://api.example/v1/agent/tick': res(200, { ok: true, fleet: 'ok', db: 'ok' }) }));
  const r = await runAgents(env, f, W);
  assert.equal(r.ok, false); assert.ok(r.delivery.length >= 1); assert.match(r.delivery[0], /401/);
});

test('transient 503 is retried and then succeeds; duplicate-run 202 is not a failure', async () => {
  let n = 0;
  const f = mk(base({ 'POST https://api.example/v1/agent/report': () => (++n < 2 ? res(503) : res(201)),
    'POST https://api.example/v1/agent/tick': res(202, { skipped: true }) }));
  const r = await runAgents(env, f, W);
  assert.equal(r.delivery.length, 0); assert.equal(r.ok, true); assert.match(r.tick.status, /skipped/);
});

test('unhealthy API body is a failure even with HTTP 200', async () => {
  const f = mk(base({ 'GET https://api.example/healthz': res(200, { ok: false }),
    'POST https://api.example/v1/agent/report': res(201),
    'POST https://api.example/v1/agent/tick': res(200, { ok: true, fleet: 'ok', db: 'ok' }) }));
  const r = await runAgents(env, f, W);
  assert.equal(r.ok, false); assert.ok(r.bad.some((x) => /unhealthy body/.test(x)));
});

test('a 403 from the site is a warning (access protection, availability unverified), not a confirmed outage', async () => {
  const sent = [];
  const f = mk(base({ 'GET https://yeehaw.pro/': res(403),
    'POST https://api.example/v1/agent/report': (o) => { sent.push(JSON.parse(o.body)); return res(201); },
    'POST https://api.example/v1/agent/tick': res(200, { ok: true, fleet: 'ok', db: 'ok' }) }));
  const r = await runAgents(env, f, W);
  assert.deepEqual(r.bad, []);
  const s = sent.find((x) => x.agent === 'site-health');
  assert.equal(s.status, 'warn'); assert.match(s.details.warnings[0], /403.*protection/);
});

test('a 403 on the agent tick is reported as warn and never as ok', async () => {
  const sent = [];
  const f = mk(base({ 'POST https://api.example/v1/agent/report': (o) => { sent.push(JSON.parse(o.body)); return res(201); },
    'POST https://api.example/v1/agent/tick': res(403) }));
  const r = await runAgents(env, f, W);
  assert.equal(r.ok, false);
  assert.equal(sent.find((x) => x.agent === 'scheduler').status, 'warn');
});

test('network failure of the tick is reported as not ok', async () => {
  const f = mk(base({ 'POST https://api.example/v1/agent/report': res(201),
    'POST https://api.example/v1/agent/tick': () => { throw new Error('timeout'); } }));
  assert.equal((await runAgents(env, f, W)).ok, false);
});
