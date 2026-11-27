// End-to-end proof run of the engine on a real HTTP console and a real Postgres (local; NOT the production deployment).
//   perf agent measures -> opens finding -> repair agent prepares a real code change in an isolated branch (tests run before/after)
//   -> QA agent verifies independently on a fresh checkout -> perf agent re-measures the verified commit -> console shows the history.
// Also runs: a bad fix (must not be reported as success), a protected operation (must need approval).
// Usage: node scripts/engine-e2e.mjs  (needs Postgres at E2E_DB_URL, default the local test cluster)
import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { randomBytes } from 'node:crypto';
import { runRepair, verifyRepair, consoleReporter } from '../agents/repair/index.mjs';
import { bench, compare } from '../agents/perf/bench.mjs';
const require = createRequire(import.meta.url);
const repo = resolve(new URL('..', import.meta.url).pathname), backend = join(repo, 'console/backend');
process.env.NODE_PATH = join(backend, 'node_modules'); require('node:module').Module._initPaths();
const ADMIN = process.env.E2E_DB_URL || 'postgres://yh:yhpass@localhost:5432/postgres';
const dbUrl = (name) => ADMIN.replace(/\/[^/]*$/, '/' + name);
const { Pool } = require('pg');
const admin = new Pool({ connectionString: ADMIN });
for (const n of ['e2e_console', 'e2e_bench_before', 'e2e_bench_after', 'e2e_repair', 'e2e_qa']) { await admin.query(`DROP DATABASE IF EXISTS ${n}`); await admin.query(`CREATE DATABASE ${n}`); }
const base = execFileSync('git', ['rev-parse', '--abbrev-ref', 'HEAD'], { cwd: repo, encoding: 'utf8' }).trim();
const evidence = { started: new Date().toISOString(), base, steps: [] };
const step = (name, data) => { evidence.steps.push({ name, ...data }); console.log('==', name, JSON.stringify(data).slice(0, 300)); };

// 1. perf agent measures the current code
const before = await bench({ backendDir: backend, dbUrl: dbUrl('e2e_bench_before'), n: 200 });
step('perf: baseline measured', { boot: before.endpoints['POST /v1/telemetry/boot (new device)'] });

// 2. a real console to report to
const { makePool, migrate } = require(backend + '/db.js'); const { createApp } = require(backend + '/server.js'); const { generateKeyPem } = require(backend + '/lib/sign.js');
const cpool = makePool(dbUrl('e2e_console')); await migrate(cpool);
const ADMIN_TOKEN = randomBytes(32).toString('base64url'), AGENT_TOKEN = randomBytes(32).toString('base64url');
const srv = createApp({ pool: cpool, adminToken: ADMIN_TOKEN, signingKeyPem: generateKeyPem(), rateLimits: false, agentToken: AGENT_TOKEN }).listen(0);
const url = 'http://127.0.0.1:' + srv.address().port;
const J = (p, o = {}, tok = AGENT_TOKEN) => fetch(url + p, { ...o, headers: { 'content-type': 'application/json', authorization: 'Bearer ' + tok, ...(o.headers || {}) } }).then(async (r) => ({ status: r.status, body: await r.json().catch(() => null) }));
const report = consoleReporter(url, AGENT_TOKEN);

// 3. perf agent opens a finding from its own measurement
const b = before.endpoints['POST /v1/telemetry/boot (new device)'];
const f1 = (await J('/v1/agent/finding', { method: 'POST', body: JSON.stringify({ agent: 'performance', fingerprint: 'perf:boot-round-trips', severity: 'medium', risk: 'medium',
  title: `Boot telemetry makes ${b.db_round_trips_per_request} database round trips per launch`, evidence: { measured: b, endpoint: 'POST /v1/telemetry/boot' },
  root_cause: 'The handler checks out a client and runs BEGIN, two INSERTs and COMMIT as separate statements; each is a network round trip to Neon from a Vercel function.',
  proposal: 'Run the device upsert and the session upsert as one atomic statement (data-modifying CTE).' }) })).body;
step('performance agent opened finding', { id: f1.id });

// 4. repair agent: real change to the real handler, isolated branch, tests before/after
const OLD_START = '    const client = await pool.connect();\n    try {\n      await client.query(\'BEGIN\');';
const fixer = (wt) => {
  const f = join(wt, 'console/backend/server.js'); let s = readFileSync(f, 'utf8');
  const a = s.indexOf(OLD_START), endMark = '    } catch (e) { await client.query(\'ROLLBACK\').catch(() => {}); throw e; } finally { client.release(); }\n    res.status(200).json({ ok: true });';
  const z = s.indexOf(endMark); if (a < 0 || z < 0) throw new Error('boot handler not found in the expected shape');
  const neu = `    // One statement, one round trip, still atomic: device upsert + daily session upsert (skipped when the device is blocked).
    const r = await pool.query(
      \`WITH d AS (
         INSERT INTO devices (device_id, app_version, android_os_ver, ram_mb, country, version_code)
         VALUES ($1,$2,$3,$4,$5,$6)
         ON CONFLICT (device_id) DO UPDATE SET last_seen = now(), boot_count = devices.boot_count + 1,
           app_version = EXCLUDED.app_version, android_os_ver = EXCLUDED.android_os_ver,
           ram_mb = EXCLUDED.ram_mb, country = COALESCE(EXCLUDED.country, devices.country),
           version_code = COALESCE(EXCLUDED.version_code, devices.version_code)
         RETURNING device_id, blocked),
       s AS (
         INSERT INTO sessions (device_id, day) SELECT device_id, (now() AT TIME ZONE 'UTC')::date FROM d WHERE NOT blocked
         ON CONFLICT (device_id, day) DO UPDATE SET session_count = sessions.session_count + 1, last_at = now()
         RETURNING 1)
       SELECT blocked FROM d\`,
      [p.device_id, p.app_version, p.android_os_ver, p.ram_mb, country, p.version_code]);
    if (r.rows[0].blocked) return res.status(200).json({ ok: true, blocked: true });
    res.status(200).json({ ok: true });`;
  s = s.slice(0, a) + neu + s.slice(z + endMark.length); writeFileSync(f, s);
};
const testCmd = (db) => `cd console/backend && TEST_DATABASE_URL=${dbUrl(db)} node --test test/api.test.js`;
const fix = await runRepair({ repoDir: repo, base, finding: { id: f1.id, fingerprint: 'perf:boot-round-trips', title: 'boot telemetry: one round trip instead of four' }, fixer, testCmd: testCmd('e2e_repair'), report });
step('repair agent', { ok: fix.ok, branch: fix.branch, commit: fix.commit, before_tests: { exit: fix.before?.exit, passed: fix.before?.passed, failed: fix.before?.failed }, after_tests: { exit: fix.after?.exit, passed: fix.after?.passed, failed: fix.after?.failed } });
if (!fix.ok) { console.error(JSON.stringify(fix, null, 1)); process.exit(1); }

// 5. QA verifies independently (fresh checkout, different run, different database)
const qa = await verifyRepair({ repoDir: repo, base, finding: { id: f1.id }, commit: fix.commit, testCmd: testCmd('e2e_qa'), report });
step('qa agent', { ok: qa.ok, reasons: qa.reasons, passed: qa.tests.passed, failed: qa.tests.failed });

// 6. perf agent re-measures the verified commit
const wt = mkdtempSync(join(tmpdir(), 'yh-after-'));
execFileSync('git', ['worktree', 'add', '-q', '--detach', wt, fix.commit], { cwd: repo });
const after = await bench({ backendDir: join(wt, 'console/backend'), dbUrl: dbUrl('e2e_bench_after'), n: 200 });
execFileSync('git', ['worktree', 'remove', '--force', wt], { cwd: repo });
const cmp = compare(before, after); step('perf: before/after', { comparison: cmp });
writeFileSync(join(repo, 'docs/perf/before.json'), JSON.stringify(before, null, 1)); writeFileSync(join(repo, 'docs/perf/after.json'), JSON.stringify(after, null, 1));
await report({ finding_id: f1.id, actor: 'performance', action: 'before/after measured', result: cmp.filter((c) => c.endpoint.includes('boot')).map((c) => `${c.endpoint}: round trips ${c.round_trips[0]} -> ${c.round_trips[1]}, p50 ${c.p50_ms[0]} -> ${c.p50_ms[1]} ms`).join('; '), evidence: { comparison: cmp.slice(0, 2).map((c) => ({ endpoint: c.endpoint, round_trips: c.round_trips, p50_ms: c.p50_ms, projected_ms_at_rtt: c.projected_ms_at_rtt })) } });

// 7. a bad fix must never be reported as success
const f2 = (await J('/v1/agent/finding', { method: 'POST', body: JSON.stringify({ agent: 'performance', fingerprint: 'perf:bad-fix-demo', severity: 'low', title: 'demo: a fix that breaks the API' }) })).body;
const bad = await runRepair({ repoDir: repo, base, finding: { id: f2.id, fingerprint: 'perf:bad-fix-demo', title: 'bad' }, testCmd: testCmd('e2e_repair'), report,
  fixer: (w) => { const f = join(w, 'console/backend/server.js'); writeFileSync(f, readFileSync(f, 'utf8').replace("res.status(200).json({ ok: true });\n  }));\n\n  // User-initiated erasure", "res.status(500).json({ ok: false });\n  }));\n\n  // User-initiated erasure")); } });
step('bad fix', { reported_ok: bad.ok, stage: bad.stage });

// 8. protected operations need approval
const f3 = (await J('/v1/agent/finding', { method: 'POST', body: JSON.stringify({ agent: 'infrastructure', fingerprint: 'infra:wrong-root-demo', severity: 'critical', title: 'API serving static site' }) })).body;
const approve = await J(`/v1/admin/findings/${f3.id}/approve`, { method: 'POST', body: '{}' }, ADMIN_TOKEN);
step('protected operation', { approve_status: approve.status, error: approve.body.error });

// 9. what the founder console shows
const view = (await J('/v1/admin/agents', {}, ADMIN_TOKEN)).body;
const byId = (id) => view.actions.filter((a) => Number(a.finding_id) === id).reverse().map((a) => ({ actor: a.actor, action: a.action, verification: a.verification, tests: a.evidence && a.evidence.tests ? Object.fromEntries(Object.entries(a.evidence.tests).map(([k, v]) => [k, v && v.exit !== undefined ? { exit: v.exit, passed: v.passed, failed: v.failed } : v])) : undefined }));
const out = { finding_perf: { id: f1.id, status: view.findings.find((x) => Number(x.id) === f1.id).status, history: byId(f1.id) }, finding_bad_fix: { id: f2.id, status: view.findings.find((x) => Number(x.id) === f2.id).status, history: byId(f2.id) }, finding_protected: { id: f3.id, status: view.findings.find((x) => Number(x.id) === f3.id).status } };
step('console view', out);
evidence.finished = new Date().toISOString(); evidence.console = out; evidence.comparison = cmp;
writeFileSync(join(repo, 'docs/perf/e2e-evidence.json'), JSON.stringify(evidence, null, 1));
srv.close(); await cpool.end(); await admin.end();
