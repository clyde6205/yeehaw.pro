'use strict';
const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { makePool, migrate } = require('../db');
const { reliability, expectedSchema, migrationSafety } = require('../agents/dbreliability');
const DB = process.env.TEST_DATABASE_URL || 'postgres://yh:yhpass@localhost:5432/yeehaw_test';
let pool;
before(async () => { pool = makePool(DB); await pool.query('DROP SCHEMA public CASCADE; CREATE SCHEMA public;'); await migrate(pool); });
after(async () => { await pool.end(); });

test('a freshly migrated database has no reliability findings', async () => {
  assert.deepEqual((await reliability({ pool })).map((f) => f.fingerprint), []);
});
test('expectedSchema reads the real schema.sql, including later ALTER TABLE additions', () => {
  const e = expectedSchema(fs.readFileSync(__dirname + '/../schema.sql', 'utf8'));
  assert.ok(e.engine_settings.includes('auto_repairs')); assert.ok(e.agent_actions.includes('evidence')); assert.ok(e.devices.includes('device_id'));
});
test('schema drift is detected: a dropped column and a dropped table are named', async () => {
  await pool.query('ALTER TABLE agent_actions DROP COLUMN evidence'); await pool.query('DROP TABLE engine_settings');
  const f = (await reliability({ pool })).find((x) => x.fingerprint === 'dbr:schema-drift');
  assert.ok(f && f.severity === 'high'); assert.ok(f.evidence.missing.includes('agent_actions.evidence') && f.evidence.missing.includes('table engine_settings'));
  await migrate(pool);
  assert.equal((await reliability({ pool })).find((x) => x.fingerprint === 'dbr:schema-drift'), undefined, 'the idempotent migration heals the drift');
});
test('integrity: a non-validated constraint is found (orphans cannot occur: a foreign key forbids them)', async () => {
  await pool.query(`ALTER TABLE agent_actions DROP CONSTRAINT IF EXISTS t_nv; ALTER TABLE agent_actions ADD CONSTRAINT t_nv CHECK (actor <> '') NOT VALID`);
  const fp = (await reliability({ pool })).map((f) => f.fingerprint);
  assert.ok(fp.includes('dbr:constraint-not-validated'));
  await pool.query(`ALTER TABLE agent_actions DROP CONSTRAINT t_nv`);
});
test('failed engine runs are reported only when there are enough samples and the rate is high', async () => {
  await pool.query('DELETE FROM agent_runs');
  for (let i = 0; i < 4; i++) await pool.query(`INSERT INTO agent_runs (ok) VALUES (false)`);
  assert.equal((await reliability({ pool })).some((f) => f.fingerprint === 'dbr:failed-engine-runs'), false, '4 runs is too few');
  await pool.query(`INSERT INTO agent_runs (ok) VALUES (false)`);
  assert.equal((await reliability({ pool })).some((f) => f.fingerprint === 'dbr:failed-engine-runs'), true);
  await pool.query('DELETE FROM agent_runs');
});
test('migrationSafety flags destructive and non-idempotent SQL and accepts the shipped schema', () => {
  const bad = migrationSafety(`CREATE TABLE t (id int); ALTER TABLE t ADD COLUMN x int NOT NULL; DROP TABLE y; DELETE FROM z; ALTER TABLE t ALTER COLUMN x TYPE text; CREATE INDEX i ON t (x);`).map((i) => i.rule);
  for (const r of ['CREATE TABLE without IF NOT EXISTS', 'ADD COLUMN without IF NOT EXISTS', 'NOT NULL column without DEFAULT', 'destructive: drops data', 'DELETE without WHERE', 'column type change', 'CREATE INDEX without IF NOT EXISTS']) assert.ok(bad.some((x) => x.startsWith(r)), r);
  assert.deepEqual(migrationSafety(fs.readFileSync(__dirname + '/../schema.sql', 'utf8')).filter((i) => i.severity === 'high'), []);
});

test('availability asks the always-on watcher for its heartbeat instead of reporting silence', async () => {
  const { availability } = require('../agents/specialists');
  await pool.query('DELETE FROM agent_reports');
  process.env.WATCH_STATUS_URL = 'https://watch.example/status';
  try {
    const alive = async () => ({ json: async () => ({ running: true, lastRunAt: new Date().toISOString(), status: 'ok', queued: 0, deliveryError: null }) });
    assert.deepEqual(await availability({ pool, fetchFn: alive }), [], 'a watcher that probed a moment ago is a live heartbeat');
    const stale = async () => ({ json: async () => ({ running: true, lastRunAt: new Date(Date.now() - 3600e3).toISOString(), status: 'ok' }) });
    assert.equal((await availability({ pool, fetchFn: stale }))[0].fingerprint, 'availability:scheduler-silent');
    const undeliverable = async () => ({ json: async () => ({ running: true, lastRunAt: new Date().toISOString(), status: 'ok', deliveryError: 'HTTP 401' }) });
    assert.equal((await availability({ pool, fetchFn: undeliverable }))[0].fingerprint, 'availability:watch-delivery');
    const down = async () => { throw new Error('unreachable'); };
    assert.equal((await availability({ pool, fetchFn: down }))[0].fingerprint, 'availability:scheduler-silent');
  } finally { delete process.env.WATCH_STATUS_URL; }
});

test('roster: all eight agents are listed; status is never-run until something really ran', async () => {
  const { buildRoster, ROSTER } = require('../agents/roster');
  assert.deepEqual(ROSTER.map((a) => a.id), ['monitoring', 'diagnostic', 'repair', 'performance', 'security', 'qa', 'database', 'android']);
  assert.ok(buildRoster({}).every((a) => a.status === 'never-run' && a.last_success_at === null));
  const runs = [{ started_at: '2026-11-27T10:00:00Z', finished_at: '2026-11-27T10:00:02Z', summary: { per_agent: { database: { state: 'ok', ms: 40 }, security: { state: 'error', error: 'boom', ms: 3 }, performance: { state: 'disabled' } } } }];
  const r = Object.fromEntries(buildRoster({ runs, disabled: ['performance'], latest: [{ agent: 'security-scan', status: 'ok', title: 't', created_at: '2026-11-27T11:00:00Z' }],
    findings: [{ agent: 'db-optimizer', status: 'open' }, { agent: 'db-optimizer', status: 'verified' }], actions: [{ actor: 'qa', created_at: '2026-11-27T12:00:00Z' }] }).map((a) => [a.id, a]));
  assert.equal(r.database.status, 'ok'); assert.equal(r.database.open_findings, 1); assert.equal(r.database.verified_findings, 1);
  assert.equal(r.security.status, 'error'); assert.equal(r.security.last_error, 'boom'); assert.equal(r.security.tool_last.agent, 'security-scan');
  assert.equal(r.performance.status, 'disabled'); assert.equal(r.qa.status, 'ok'); assert.equal(r.qa.last_success_at, '2026-11-27T12:00:00.000Z');
  assert.equal(r.repair.status, 'never-run'); assert.equal(r.android.status, 'never-run');
});
