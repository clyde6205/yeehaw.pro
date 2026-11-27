import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { analyzeVercel, analyzeCloudflare, analyzeNeon, publish } from './analyze.mjs';
const fx = JSON.parse(readFileSync(new URL('./fixtures/real-2026-11-27.json', import.meta.url)));
const expect = { alias: 'api.yeehaw.pro', headSha: fx.branchHeadSha };
const now = fx.serving.createdAt + 5 * 60_000;
const fps = (a) => a.map((f) => f.fingerprint);

test('REAL data (captured today): healthy serving deployment is not flagged as a wrong root; real failed deploy, churn and stale code are found', () => {
  const f = analyzeVercel({ serving: fx.serving, deployments: fx.deployments, now, expect });
  assert.ok(!fps(f).includes('infra:api-build-suspicious'), 'normal 11.5 s API build must not be flagged');
  assert.ok(fps(f).includes('infra:deploy-failed:84GWCFMc'));
  assert.ok(fps(f).includes('infra:deploy-churn:b4f2353'));
  assert.ok(fps(f).includes('infra:api-behind-branch'));
  assert.ok(f.every((x) => x.agent === 'infrastructure' && x.evidence));
});
test('REAL data: the 2026-11-27 incident deployment (4.2 s static build) is identified as an incorrect deployment root', () => {
  const bad = fx.badDeployment;
  const deployments = fx.deployments.map((d) => (d.id === bad.id ? d : d));
  const f = analyzeVercel({ serving: bad, deployments, now: bad.createdAt + 60_000, expect });
  const s = f.find((x) => x.fingerprint === 'infra:api-build-suspicious');
  assert.ok(s, 'flagged'); assert.equal(s.severity, 'critical'); assert.match(s.root_cause, /Root Directory/); assert.equal(s.evidence.build_ms, 4233);
});
test('domain not on the serving deployment is critical', () => {
  const f = analyzeVercel({ serving: { ...fx.serving, alias: ['x.vercel.app'] }, deployments: fx.deployments, now, expect });
  assert.equal(f.find((x) => x.fingerprint === 'infra:alias-missing').severity, 'critical');
});
test('cloudflare: bot fight mode and unexpected cron are caught; the real current state is clean', () => {
  assert.deepEqual(analyzeCloudflare(fx.cloudflare), []);
  const f = analyzeCloudflare({ fight_mode: true, schedules: { 'yeehaw-agents': [{ cron: '*/5 * * * *' }] } });
  assert.deepEqual(fps(f), ['infra:bot-fight-mode', 'infra:unexpected-cron:yeehaw-agents']);
});
test('neon: real consumption becomes measured cost lines with the free-plan limit; no invented money', () => {
  const { costs, findings } = analyzeNeon(fx.neon, Date.parse('2026-11-27T14:40:00Z'));
  const m = Object.fromEntries(costs.map((c) => [c.metric, c]));
  assert.equal(m.compute_cu_hours_month_to_date.value, 0.51); assert.equal(m.billed_amount.value, 0); assert.equal(m.compute_cu_hours_month_to_date.limit, 100);
  assert.deepEqual(findings, []);
  assert.equal(analyzeNeon({ ...fx.neon, compute_time_seconds: 1_200_000 }, Date.parse('2026-11-27T14:40:00Z')).findings[0].fingerprint, 'infra:neon-compute-budget');
});
test('publish posts findings and costs with the agent token', async () => {
  const calls = []; const f = async (u, o) => { calls.push([u, JSON.parse(o.body)]); return { status: 201 }; };
  const r = await publish({ base: 'https://x', token: 't', findings: [{ fingerprint: 'infra:a1', agent: 'infrastructure', severity: 'low', title: 't' }], costs: [{ provider: 'neon' }], fetchFn: f });
  assert.deepEqual(calls.map((c) => c[0]), ['https://x/v1/agent/finding', 'https://x/v1/agent/report']); assert.equal(r.costs, 201);
});
