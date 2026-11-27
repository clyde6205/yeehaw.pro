import { test } from 'node:test';
import assert from 'node:assert/strict';
import { runAgents } from './index.js';

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
    'POST https://api.example/v1/agent/tick': res(200, { ok: true }),
  });
  const r = await runAgents(env, f);
  assert.equal(r.ok, true);
  assert.equal(sent[0].agent, 'site-health'); assert.equal(sent[0].status, 'ok');
});

test('a failing page is reported with its URL, never as ok', async () => {
  const sent = [];
  const f = mk({
    'GET https://yeehaw.pro/': res(200), 'GET https://yeehaw.pro/privacy/': res(404), 'GET https://yeehaw.pro/support/': res(200),
    'GET https://api.example/healthz': res(200, { ok: true }),
    'POST https://api.example/v1/agent/report': (o) => { sent.push(JSON.parse(o.body)); return res(201); },
    'POST https://api.example/v1/agent/tick': res(200, { ok: true }),
  });
  const r = await runAgents(env, f);
  assert.equal(r.ok, false);
  assert.equal(sent[0].status, 'fail'); assert.match(sent[0].details.bad[0], /privacy.*404/);
});

test('missing secrets: says so, does not pretend', async () => {
  const r = await runAgents({}, async () => { throw new Error('must not call'); });
  assert.equal(r.ok, false); assert.match(r.error, /AGENT_TOKEN/);
});
