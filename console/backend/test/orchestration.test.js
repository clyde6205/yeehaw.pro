'use strict';
// Orchestrator behaviours that only show up when several agents and the real API work together. LOCAL evidence (embedded Postgres,
// a throw-away git repository, the real HTTP API); the scripted `fixer` stands in for the AI code-writer, everything else is real.
const { test, before, after, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');
const { mkdtempSync, writeFileSync, mkdirSync } = require('node:fs');
const { tmpdir } = require('node:os');
const { join } = require('node:path');
const { makePool, migrate } = require('../db');
const { createApp } = require('../server');
const { generateKeyPem } = require('../lib/sign');
const { runOnce } = require('../agents/orchestrator');

const DB = process.env.TEST_DATABASE_URL || 'postgres://yh:yhpass@localhost:5432/yeehaw_test';
const ADMIN = crypto.randomBytes(32).toString('base64url'), AGENT = crypto.randomBytes(32).toString('base64url');
let pool, server, base;
const g = (cwd, ...a) => execFileSync('git', a, { cwd, encoding: 'utf8' }).trim();
const H = (t) => ({ 'content-type': 'application/json', authorization: 'Bearer ' + t });
const api = (p, body, t = AGENT, method = 'POST') => fetch(base + p, { method, headers: H(t), body: body === undefined ? undefined : JSON.stringify(body) });
const actions = async (id) => (await pool.query('SELECT actor, action, verification, evidence FROM agent_actions WHERE finding_id = $1 ORDER BY id', [id])).rows;

before(async () => {
  pool = makePool(DB); await pool.query('DROP SCHEMA public CASCADE; CREATE SCHEMA public;'); await migrate(pool);
  server = createApp({ pool, adminToken: ADMIN, signingKeyPem: generateKeyPem(), rateLimits: false, agentToken: AGENT }).listen(0);
  base = 'http://127.0.0.1:' + server.address().port;
});
after(async () => { server.close(); await pool.end(); });
beforeEach(async () => { await pool.query('TRUNCATE agent_actions, agent_findings, agent_runs, agent_reports CASCADE'); await pool.query("UPDATE agent_lease SET until = 'epoch'"); });
const release = () => pool.query("UPDATE agent_lease SET until = 'epoch'");
const fx = (fingerprint, extra = {}) => ({ fingerprint, agent: 'performance', severity: 'medium', title: fingerprint, risk: 'low', repair: 'prune_agent_reports', ...extra });

test('learning: an automatic repair rejected twice by independent QA stops running by itself and is escalated to the owner', async () => {
  for (const n of [1, 2]) {
    const id = (await pool.query(`INSERT INTO agent_findings (fingerprint, agent, severity, title, risk, repair, status) VALUES ($1,'performance','low','old','low','prune_agent_reports','failed') RETURNING id`, ['old:' + n])).rows[0].id;
    await pool.query(`INSERT INTO agent_actions (finding_id, actor, action, verification) VALUES ($1,'qa','independent verification','failed')`, [id]);
  }
  const r = await runOnce(pool, { specialists: { s: async () => [fx('perf:learn')] } });
  assert.equal(r.summary.repaired, 0, 'the repair did not run');
  const f = (await pool.query(`SELECT id, status FROM agent_findings WHERE fingerprint = 'perf:learn'`)).rows[0];
  assert.equal(f.status, 'open');
  const a = await actions(f.id); assert.ok(a.some((x) => x.action === 'repair skipped (learned)'));
  // the owner can still approve it explicitly
  assert.equal((await api(`/v1/admin/findings/${f.id}/approve`, {}, ADMIN)).status, 200);
  await release(); const r2 = await runOnce(pool, { specialists: { s: async () => [fx('perf:learn')] } });
  assert.equal(r2.summary.repaired, 1, 'owner approval overrides the learned block');
});

test('dependencies: a downstream finding waits while the upstream problem that likely causes it is open, then proceeds', async () => {
  const up = fx('availability:site-down', { agent: 'availability', severity: 'critical', repair: null, risk: 'medium' });
  const down = fx('perf:slow-boot');
  let r = await runOnce(pool, { specialists: { a: async () => [up], p: async () => [down] } });
  assert.equal(r.summary.repaired, 0);
  const d = (await pool.query(`SELECT id FROM agent_findings WHERE fingerprint = 'perf:slow-boot'`)).rows[0].id;
  assert.ok((await actions(d)).some((x) => x.action === 'deferred' && /availability:site-down/.test(JSON.stringify(x))) || (await pool.query(`SELECT 1 FROM agent_actions WHERE finding_id=$1 AND action='deferred'`, [d])).rowCount === 1);
  await release();
  r = await runOnce(pool, { specialists: { a: async () => [], p: async () => [down] } });
  assert.equal(r.summary.repaired, 1, 'upstream cleared, downstream repair ran');
  assert.ok((await actions(d)).some((x) => x.action === 'repair prune_agent_reports'), 'the repair is on record');
});

test('end to end: finding -> owner approval -> isolated code repair -> independent QA -> rejected bad repair -> executed, verified rollback', async () => {
  const { runRepair, verifyRepair, consoleReporter, processRollbacks } = await import('../../../agents/repair/index.mjs');
  const repo = mkdtempSync(join(tmpdir(), 'yh-e2e-')); g(repo, 'init', '-q', '-b', 'main'); g(repo, 'config', 'user.email', 't@t'); g(repo, 'config', 'user.name', 't');
  mkdirSync(join(repo, 'test')); writeFileSync(join(repo, 'add.js'), 'module.exports = (a, b) => a - b; // BUG\n');
  writeFileSync(join(repo, 'test', 'add.test.js'), "const t=require('node:test'),a=require('node:assert');t('adds',()=>a.equal(require('../add')(2,3),5));\n");
  g(repo, 'add', '-A'); g(repo, 'commit', '-q', '-m', 'init'); const mainSha = g(repo, 'rev-parse', 'main');
  const report = consoleReporter(base, AGENT);
  // 1 detect (QA agent files the finding through the real API)
  const fres = await (await api('/v1/agent/finding', { agent: 'qa', fingerprint: 'qa:add-regression', severity: 'high', title: 'add() returns a-b: regression test failing', risk: 'medium', evidence: { test: 'adds' }, root_cause: 'operator typo', proposal: 'use +' })).json();
  assert.equal(fres.created, true); const id = fres.id;
  // 2 the agent token cannot approve; the owner can
  assert.equal((await api(`/v1/admin/findings/${id}/approve`, {}, AGENT)).status, 401);
  assert.equal((await api(`/v1/admin/findings/${id}/approve`, {}, ADMIN)).status, 409, 'a finding with no allowlisted server repair cannot be approved as one');
  // 3 repair agent: isolated worktree + branch, tests before/after, committed, reported
  const out = await runRepair({ repoDir: repo, base: 'main', finding: { id, fingerprint: 'qa:add-regression', title: 'add() returns a-b' }, testCmd: 'node --test', report, fixer: (wt) => writeFileSync(join(wt, 'add.js'), 'module.exports = (a, b) => a + b;\n') });
  assert.equal(out.ok, true); assert.equal(g(repo, 'rev-parse', 'main'), mainSha, 'main untouched');
  // 4 a repair agent can NOT verify its own work
  assert.equal((await api('/v1/agent/action', { finding_id: id, actor: 'repair', action: 'self check', verification: 'passed', evidence: { commit: out.commit, run_id: 'x', tests: {} } })).status, 403);
  // 5 independent QA: different run id, fresh checkout of the exact commit
  const v = await verifyRepair({ repoDir: repo, base: 'main', finding: { id }, commit: out.commit, testCmd: 'node --test', report });
  assert.equal(v.ok, true);
  let a = await actions(id); const prep = a.find((x) => x.action === 'fix prepared'), ver = a.find((x) => x.action === 'independent verification');
  assert.notEqual(prep.evidence.run_id, ver.evidence.run_id); assert.equal(prep.evidence.commit, ver.evidence.commit); assert.equal(ver.verification, 'passed');
  assert.equal((await pool.query('SELECT status FROM agent_findings WHERE id = $1', [id])).rows[0].status, 'verified');
  // 6 a "fix" that only deletes the failing test is REJECTED by QA
  const f2 = await (await api('/v1/agent/finding', { agent: 'qa', fingerprint: 'qa:cheat', severity: 'high', title: 'cheating fix', risk: 'medium' })).json();
  const bad = await runRepair({ repoDir: repo, base: 'main', finding: { id: f2.id, fingerprint: 'qa:cheat', title: 'cheat' }, testCmd: 'node --test', report, fixer: (wt) => execFileSync('git', ['rm', '-q', 'test/add.test.js'], { cwd: wt }) });
  if (bad.ok) { const vb = await verifyRepair({ repoDir: repo, base: 'main', finding: { id: f2.id }, commit: bad.commit, testCmd: 'node --test', report }); assert.equal(vb.ok, false); assert.equal((await pool.query('SELECT status FROM agent_findings WHERE id = $1', [f2.id])).rows[0].status, 'failed'); }
  else assert.ok((await actions(f2.id)).some((x) => x.action === 'fix failed'), 'rejected before it was even proposed');
  // 7 owner rolls back from the console; the repair agent executes it; QA verifies it independently
  assert.equal((await api(`/v1/admin/findings/${id}/rollback`, {}, ADMIN)).status, 202);
  const done = await processRollbacks({ base, token: AGENT, repoDir: repo, baseBranch: 'main', testCmd: 'node --test' });
  assert.equal(done[0] && done[0].ok, true, JSON.stringify(done)); assert.equal(g(repo, 'rev-parse', 'main'), mainSha, 'main still untouched');
  a = await actions(id); assert.ok(a.some((x) => x.action === 'rollback requested')); assert.ok(a.some((x) => x.action === 'rollback done')); assert.ok(a.some((x) => /^rollback verified/.test(x.action) && x.verification === 'passed'));
  // 8 everything above is in the persistent audit trail
  assert.ok((await pool.query(`SELECT 1 FROM admin_audit WHERE action = 'agent.finding.rollback'`)).rowCount === 1);
});
