import { test } from 'node:test';
import assert from 'node:assert/strict';
import { collectAll, DEFAULT_CFG } from './collect.mjs';
const J = (o, s = 200) => ({ ok: s < 400, status: s, json: async () => o });
const NOW = Date.UTC(2026, 10, 28, 3);
const fake = (over = {}) => async (url) => {
  if (over.fail && url.includes(over.fail)) return J({}, 403);
  if (url.includes('api.vercel.com/v6/deployments')) return J({ deployments: [{ uid: 'dpl_abcdefghijkl', state: 'READY', target: 'production', created: NOW - 3600e3, buildingAt: NOW - 3600e3, ready: NOW - 3500e3, meta: { githubCommitSha: 'a'.repeat(40) } }] });
  if (url.includes('/aliases')) return J({ aliases: [{ alias: 'api.yeehaw.pro' }] });
  if (url.includes('workers/scripts')) return J({ result: { schedules: over.cron ? [{ cron: '* * * * *' }] : [] } });
  if (url.includes('neon.tech')) return J({ project: { compute_time_seconds: 7383, active_time_seconds: 20000, data_transfer_bytes: 5e6, consumption_period_start: '2026-11-01T00:00:00Z', subscription_type: 'free_v3' } });
  return J({}, 404);
};
test('no credentials: nothing runs, each provider names the exact permission it needs, nothing throws', async () => {
  const r = await collectAll({ env: {}, fetchFn: fake(), now: NOW, cfg: DEFAULT_CFG });
  assert.deepEqual(r.ran, []); assert.equal(r.skipped.length, 3); assert.match(r.skipped.find((s) => s.provider === 'cloudflare').how, /Workers Scripts: Read/);
});
test('all credentials: usage and health collected, costs measured, no findings when healthy', async () => {
  const r = await collectAll({ env: { VERCEL_TOKEN: 't', CLOUDFLARE_API_TOKEN: 't', NEON_API_KEY: 't' }, fetchFn: fake(), now: NOW, cfg: DEFAULT_CFG });
  assert.deepEqual(r.ran.sort(), ['cloudflare', 'neon', 'vercel']); assert.ok(r.costs.some((c) => c.metric === 'compute_cu_hours_month_to_date')); assert.equal(r.failed.length, 0);
});
test('one provider failing (HTTP 403) is isolated and reported; an unexpected cron is a finding', async () => {
  const r = await collectAll({ env: { VERCEL_TOKEN: 't', CLOUDFLARE_API_TOKEN: 't', NEON_API_KEY: 't' }, fetchFn: fake({ fail: 'neon.tech', cron: true }), now: NOW, cfg: DEFAULT_CFG });
  assert.deepEqual(r.failed.map((f) => f.provider), ['neon']); assert.ok(r.findings.some((f) => f.fingerprint.startsWith('infra:unexpected-cron')));
});
test('network-level failures (fetch rejects) are isolated per provider and never abort the collection', async () => {
  const f = async (url) => { if (url.includes('vercel.com')) throw new Error('ECONNRESET'); return fake()(url); };
  const r = await collectAll({ env: { VERCEL_TOKEN: 't', CLOUDFLARE_API_TOKEN: 't', NEON_API_KEY: 't' }, fetchFn: f, now: NOW, cfg: DEFAULT_CFG });
  assert.deepEqual(r.failed.map((x) => x.provider), ['vercel']); assert.deepEqual(r.ran.sort(), ['cloudflare', 'neon']); assert.ok(r.costs.length > 0);
});
