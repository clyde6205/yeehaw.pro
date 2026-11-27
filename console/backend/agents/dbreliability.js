'use strict';
// Database Reliability agent: real checks against the live Neon database. Read only.
//   schemaConsistency : the database must contain every table and column that schema.sql declares
//   integrity         : invalid indexes, orphaned rows, NOT VALID constraints, long idle-in-transaction sessions
//   queryPerformance  : times the queries the API actually runs; uses pg_stat_statements when present
//   failedOperations  : engine runs that failed recently
//   migrationSafety   : static review of schema.sql for non-idempotent or destructive statements (pure function)
const fs = require('node:fs');
const path = require('node:path');

const stripComments = (s) => s.replace(/\/\*[\s\S]*?\*\//g, '').replace(/--.*$/gm, '');

/** Tables and columns that schema.sql declares (CREATE TABLE bodies + ALTER TABLE ... ADD COLUMN). */
function expectedSchema(sql) {
  const out = {}; const s = stripComments(sql);
  for (const m of s.matchAll(/CREATE TABLE IF NOT EXISTS\s+(\w+)\s*\(([\s\S]*?)\n\);/g)) {
    const cols = [];
    for (const line of m[2].split('\n')) { const c = line.trim().match(/^([a-z_][a-z0-9_]*)\s+(?!KEY\b|CHECK\b|UNIQUE\b|PRIMARY\b|FOREIGN\b|CONSTRAINT\b)[a-zA-Z]/i); if (c) cols.push(c[1]); }
    out[m[1]] = cols;
  }
  for (const m of s.matchAll(/ALTER TABLE\s+(\w+)\s+ADD COLUMN IF NOT EXISTS\s+(\w+)/gi)) (out[m[1]] = out[m[1]] || []).push(m[2]);
  return out;
}

/** Static review of migration SQL. Returns [{rule, severity, statement}]. */
function migrationSafety(sql) {
  const issues = [];
  for (const raw of stripComments(sql).split(';')) {
    const st = raw.trim().replace(/\s+/g, ' '); if (!st) continue;
    const add = (rule, severity) => issues.push({ rule, severity, statement: st.slice(0, 140) });
    if (/^CREATE TABLE\b(?! IF NOT EXISTS)/i.test(st)) add('CREATE TABLE without IF NOT EXISTS: re-running the migration fails', 'medium');
    if (/^CREATE (UNIQUE )?INDEX\b(?! IF NOT EXISTS)(?! CONCURRENTLY IF NOT EXISTS)/i.test(st)) add('CREATE INDEX without IF NOT EXISTS', 'medium');
    if (/^DROP\s+(TABLE|COLUMN|SCHEMA|DATABASE)/i.test(st) || /\bDROP COLUMN\b/i.test(st)) add('destructive: drops data', 'high');
    if (/^TRUNCATE\b/i.test(st)) add('destructive: TRUNCATE', 'high');
    if (/^DELETE FROM\b(?!.*\bWHERE\b)/i.test(st)) add('DELETE without WHERE', 'high');
    if (/ADD COLUMN\b(?!.*IF NOT EXISTS)/i.test(st)) add('ADD COLUMN without IF NOT EXISTS', 'medium');
    if (/ADD COLUMN\b.*\bNOT NULL\b(?!.*\bDEFAULT\b)/i.test(st)) add('NOT NULL column without DEFAULT fails on a populated table', 'high');
    if (/ALTER COLUMN\s+\w+\s+(SET DATA )?TYPE\b/i.test(st)) add('column type change rewrites the table and can lose data', 'high');
    if (/^ALTER TABLE .* RENAME\b/i.test(st)) add('rename breaks running application code', 'medium');
  }
  return issues;
}

let cachedSql;
const readSchemaSql = () => cachedSql || (cachedSql = fs.readFileSync(path.join(__dirname, '..', 'schema.sql'), 'utf8'));

async function reliability({ pool }) {
  const out = []; const F = (fp, severity, title, evidence, root_cause, proposal, risk = 'low') => out.push({ fingerprint: 'dbr:' + fp, agent: 'db-optimizer', severity, title, evidence, root_cause, proposal, risk });
  const sql = readSchemaSql();
  // schema consistency
  const exp = expectedSchema(sql);
  const have = {};
  for (const r of (await pool.query(`SELECT table_name, column_name FROM information_schema.columns WHERE table_schema='public'`)).rows) (have[r.table_name] = have[r.table_name] || new Set()).add(r.column_name);
  const missing = [];
  for (const [t, cols] of Object.entries(exp)) { if (!have[t]) missing.push(`table ${t}`); else for (const c of cols) if (!have[t].has(c)) missing.push(`${t}.${c}`); }
  if (missing.length) F('schema-drift', 'high', `Database is missing ${missing.length} schema object(s) that the code expects`, { missing: missing.slice(0, 20) },
    'schema.sql was not applied after the last deploy, or a migration failed part way.', 'Run the idempotent migration (schema.sql is additive and safe to re-run) against the database.', 'medium');
  // integrity
  const badIdx = (await pool.query(`SELECT c.relname FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE NOT i.indisvalid`)).rows;
  if (badIdx.length) F('invalid-index', 'high', `${badIdx.length} invalid index(es)`, { indexes: badIdx.map((r) => r.relname) }, 'A failed CREATE INDEX CONCURRENTLY left an unusable index.', 'Drop and recreate the index.', 'medium');
  const notValid = (await pool.query(`SELECT conname, conrelid::regclass::text AS tbl FROM pg_constraint WHERE NOT convalidated`)).rows;
  if (notValid.length) F('constraint-not-validated', 'medium', `${notValid.length} constraint(s) not validated`, { constraints: notValid }, 'A constraint was added NOT VALID and never validated.', 'VALIDATE CONSTRAINT after checking the data.', 'low');
  const orphans = Number((await pool.query(`SELECT count(*) AS n FROM agent_actions a LEFT JOIN agent_findings f ON f.id = a.finding_id WHERE a.finding_id IS NOT NULL AND f.id IS NULL`)).rows[0].n);
  if (orphans) F('orphan-actions', 'medium', `${orphans} agent action(s) point at a finding that no longer exists`, { orphans }, 'Findings were deleted without their audit rows.', 'Investigate; the audit trail must stay complete.', 'low');
  const idle = (await pool.query(`SELECT count(*)::int AS n, COALESCE(max(EXTRACT(EPOCH FROM now() - xact_start)),0)::int AS max_s FROM pg_stat_activity WHERE state = 'idle in transaction' AND now() - xact_start > interval '5 minutes' AND pid <> pg_backend_pid()`)).rows[0];
  if (idle.n) F('idle-in-transaction', 'medium', `${idle.n} session(s) idle in a transaction for over 5 minutes (longest ${idle.max_s}s)`, idle, 'A request opened a transaction and never finished it; it holds locks and blocks vacuum.', 'Find the leaking code path; set idle_in_transaction_session_timeout.', 'medium');
  // query performance: the statements the API runs, timed a few times (these are reads only)
  const probes = { 'config lookup': `SELECT id FROM remote_configs WHERE is_active LIMIT 1`, 'device lookup': `SELECT device_id FROM devices LIMIT 1`, 'findings list': `SELECT id, status FROM agent_findings ORDER BY updated_at DESC LIMIT 50` };
  const timing = {};
  for (const [name, q] of Object.entries(probes)) { const ts = []; for (let i = 0; i < 5; i++) { const t = process.hrtime.bigint(); await pool.query(q); ts.push(Number(process.hrtime.bigint() - t) / 1e6); } ts.sort((a, b) => a - b); timing[name] = { median_ms: +ts[2].toFixed(1), max_ms: +ts[4].toFixed(1) }; }
  const slow = Object.entries(timing).filter(([, v]) => v.median_ms > 400);
  if (slow.length) F('slow-queries', 'medium', `Slow database round trips: ${slow.map(([n, v]) => `${n} ${v.median_ms} ms`).join(', ')}`, { timing }, 'Compute is waking from suspend, the region is far from the function, or the query is unindexed.', 'Compare the Neon region with the Vercel function region; EXPLAIN the slow statement.', 'none');
  try {
    const top = (await pool.query(`SELECT left(query,100) AS q, calls, round(mean_exec_time::numeric,1) AS mean_ms FROM pg_stat_statements WHERE mean_exec_time > 500 AND calls > 20 ORDER BY mean_exec_time DESC LIMIT 3`)).rows;
    if (top.length) F('slow-statements', 'medium', `${top.length} statement(s) average over 500 ms`, { top }, 'Expensive queries seen by pg_stat_statements.', 'EXPLAIN ANALYZE them and index the filter columns.', 'low');
  } catch { /* pg_stat_statements not installed: nothing to report, nothing is claimed */ }
  // failed operations
  const runs = (await pool.query(`SELECT count(*)::int AS n, count(*) FILTER (WHERE ok = false)::int AS bad FROM agent_runs WHERE started_at > now() - interval '24 hours'`)).rows[0];
  if (runs.n >= 5 && runs.bad / runs.n > 0.2) F('failed-engine-runs', 'medium', `${runs.bad} of ${runs.n} engine runs failed in 24 h`, runs, 'A specialist or the database is failing repeatedly.', 'Read the failing run summaries in the console.', 'none');
  const deadActions = Number((await pool.query(`SELECT count(*) AS n FROM agent_findings WHERE status = 'failed' AND updated_at > now() - interval '24 hours'`)).rows[0].n);
  if (deadActions >= 3) F('failed-repairs', 'medium', `${deadActions} repairs failed in 24 h`, { failed: deadActions }, 'Repeated failed repairs usually mean a wrong diagnosis.', 'Review the repair evidence before approving more.', 'none');
  // migration safety of the shipped schema file
  const risky = migrationSafety(sql).filter((i) => i.severity === 'high');
  if (risky.length) F('unsafe-migration', 'high', `schema.sql contains ${risky.length} unsafe statement(s)`, { issues: risky.slice(0, 5) }, 'The migration runs on every deploy; destructive or non-idempotent statements are dangerous there.', 'Move destructive changes to a reviewed one-off migration.', 'medium');
  return out;
}
module.exports = { reliability, expectedSchema, migrationSafety };
