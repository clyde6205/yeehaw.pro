'use strict';
// Specialist agents. Each takes { pool } and returns findings: plain objects describing one detected problem.
// They only READ. Any repair is named by a key in REPAIRS and is executed (and then verified) by the orchestrator.
//   finding = { fingerprint, agent, severity, title, evidence, root_cause, proposal, risk, repair? }

const { reliability } = require('./dbreliability');
const MIN = 60_000;
const NEON_FREE_BYTES = 512 * 1024 * 1024; // Neon free plan storage; warn at 70%

/** 1. Monitoring and availability, including a dead-man switch on the monitoring itself. */
async function availability({ pool, fetchFn }) {
  const out = [];
  const { rows } = await pool.query(`SELECT agent, status, title, created_at FROM agent_reports
      WHERE agent IN ('site-health','scheduler') AND created_at > now() - interval '30 minutes' ORDER BY created_at DESC`);
  const site = rows.filter((r) => r.agent === 'site-health');
  let beat = rows.filter((r) => r.agent === 'scheduler' || r.agent === 'site-health');
  // The always-on watcher (Durable Object) only reports on CHANGE, so its silence in the database means nothing. Ask it directly:
  // its status endpoint says when it last probed. Used only when WATCH_STATUS_URL is configured.
  let watcher = null;
  if (!beat.length && process.env.WATCH_STATUS_URL) {
    try { const r = await (fetchFn || fetch)(process.env.WATCH_STATUS_URL, { signal: AbortSignal.timeout(4000) }); const j = await r.json();
      watcher = { running: !!j.running, lastRunAt: j.lastRunAt, status: j.status, queued: j.queued, deliveryError: j.deliveryError };
      if (j.running && j.lastRunAt && Date.now() - new Date(j.lastRunAt).getTime() < 5 * MIN) beat = [{ agent: 'watch' }];
      if (j.status === 'fail' && !site.length) out.push({ fingerprint: 'availability:watch-failing', agent: 'availability', severity: 'high', title: 'The always-on watcher reports failing probes', evidence: watcher, root_cause: 'Live probes are failing right now (see the incident findings for the classified cause).', proposal: 'Open the incident finding.', risk: 'none' });
      if (j.deliveryError) out.push({ fingerprint: 'availability:watch-delivery', agent: 'availability', severity: 'medium', title: 'The watcher cannot deliver reports to the console: ' + j.deliveryError, evidence: watcher, root_cause: 'AGENT_TOKEN mismatch or the API rejecting reports.', proposal: 'Check AGENT_TOKEN on both sides; run the Worker /selftest.', risk: 'none' });
    } catch (e) { watcher = { error: e.message }; }
  }
  if (!beat.length) out.push({ fingerprint: 'availability:scheduler-silent', agent: 'availability', severity: 'high',
    title: 'No heartbeat from the always-on scheduler in 30 minutes',
    evidence: { watcher }, root_cause: 'The Cloudflare Worker cron is not running or cannot reach the API (not deployed, token wrong, or API down).',
    proposal: 'Check the Worker deployment and its cron trigger; confirm AGENT_TOKEN matches on both sides.', risk: 'none' });
  if (site.length && site[0].status === 'fail') out.push({ fingerprint: 'availability:site-down', agent: 'availability', severity: 'high',
    title: 'Public endpoint check failing: ' + site[0].title, evidence: { latest: site[0] },
    root_cause: 'One or more public URLs returned an error or timed out from the monitor.', proposal: 'Open the failing URL; check Cloudflare/Vercel status and the last deployment.', risk: 'none' });
  return out;
}

/** 2. Neon database: size against the plan, dead rows, connection pressure, missing-index hints. */
async function database({ pool }) {
  const out = [...(await reliability({ pool }))];
  const size = Number((await pool.query('SELECT pg_database_size(current_database()) AS b')).rows[0].b);
  if (size > 0.7 * NEON_FREE_BYTES) out.push({ fingerprint: 'db:size', agent: 'db-optimizer', severity: size > 0.9 * NEON_FREE_BYTES ? 'high' : 'medium',
    title: `Database is ${(size / 1048576).toFixed(0)} MB of the 512 MB free allowance`, evidence: { bytes: size },
    root_cause: 'Growth of telemetry/report tables.', proposal: 'Prune old agent_reports (automatic, safe) and review telemetry retention; upgrade only if the owner approves paid storage.', risk: 'low', repair: 'prune_agent_reports' });
  const stale = (await pool.query(`SELECT relname, n_live_tup, n_dead_tup FROM pg_stat_user_tables
      WHERE n_dead_tup > 1000 AND n_dead_tup > 0.2 * GREATEST(n_live_tup,1) ORDER BY n_dead_tup DESC LIMIT 3`)).rows;
  for (const t of stale) out.push({ fingerprint: 'db:dead:' + t.relname, agent: 'db-optimizer', severity: 'low',
    title: `Table ${t.relname} has ${t.n_dead_tup} dead rows (${t.n_live_tup} live)`, evidence: t,
    root_cause: 'Many updates/deletes since the last autovacuum.', proposal: 'Refresh planner statistics (ANALYZE); autovacuum reclaims the space.', risk: 'none', repair: 'analyze:' + t.relname });
  const seq = (await pool.query(`SELECT relname, seq_scan, idx_scan, n_live_tup FROM pg_stat_user_tables
      WHERE n_live_tup > 10000 AND seq_scan > 100 AND seq_scan > 5 * COALESCE(idx_scan,0) ORDER BY seq_scan DESC LIMIT 2`)).rows;
  for (const t of seq) out.push({ fingerprint: 'db:seqscan:' + t.relname, agent: 'db-optimizer', severity: 'medium',
    title: `Table ${t.relname} is read by full scans (${t.seq_scan} scans, ${t.idx_scan || 0} index scans, ${t.n_live_tup} rows)`, evidence: t,
    root_cause: 'A frequent query on a large table has no usable index.', proposal: 'Identify the query and add an index. Needs the owner\'s approval: schema change.', risk: 'medium' });
  const conn = (await pool.query(`SELECT count(*)::int AS n, current_setting('max_connections')::int AS max FROM pg_stat_activity`)).rows[0];
  if (conn.n > 0.8 * conn.max) out.push({ fingerprint: 'db:connections', agent: 'db-optimizer', severity: 'medium',
    title: `${conn.n} of ${conn.max} database connections in use`, evidence: conn, root_cause: 'Pool size too large for the plan or leaked connections.', proposal: 'Lower PG_POOL_MAX or use the pooled connection string.', risk: 'medium' });
  return out;
}

/** 3. Android compatibility and crash analysis: is the crash rate concentrated in one Android version or RAM tier? */
async function androidCompat({ pool }) {
  const out = [];
  const totalBad = (await pool.query(`SELECT COALESCE(sum(value),0)::int AS bad FROM health_events WHERE kind IN ('crash','freeze') AND created_at > now() - interval '24 hours'`)).rows[0].bad;
  const seg = async (col, label) => (await pool.query(`SELECT ${col} AS seg, count(DISTINCT d.device_id)::int AS devices, COALESCE(sum(h.value),0)::int AS bad
      FROM devices d LEFT JOIN health_events h ON h.device_id = d.device_id AND h.kind IN ('crash','freeze') AND h.created_at > now() - interval '24 hours'
      GROUP BY 1 HAVING COALESCE(sum(h.value),0) >= 5 ORDER BY bad DESC LIMIT 3`)).rows;
  const allDev = Math.max(1, (await pool.query('SELECT count(*)::int AS n FROM devices')).rows[0].n);
  const overall = totalBad / allDev;
  for (const [col, label] of [['d.android_os_ver', 'Android version'], ['d.app_version', 'app version'], ["CASE WHEN d.ram_mb <= 1024 THEN '<=1 GB' WHEN d.ram_mb <= 2048 THEN '<=2 GB' ELSE '>2 GB' END", 'RAM tier']]) {
    for (const s of await seg(col, label)) {
      const rate = s.bad / Math.max(1, s.devices);
      if (rate > 3 * Math.max(overall, 0.01)) out.push({ fingerprint: `android:${label}:${s.seg}`, agent: 'android-compat', severity: s.bad >= 20 ? 'high' : 'medium',
        title: `Crashes/freezes concentrated in ${label} ${s.seg}: ${s.bad} events on ${s.devices} device(s)`, evidence: { segment: s, overall_per_device: Number(overall.toFixed(2)) },
        root_cause: `The failure rate for ${label} ${s.seg} is over 3x the fleet average, so the cause is likely specific to it (OS behaviour, WebView version or memory limit).`,
        proposal: 'Reproduce on that Android version/RAM tier in the emulator matrix; if confirmed, gate the affected feature through remote config until fixed.', risk: 'medium' });
    }
  }
  return out;
}

/** 4. Security and vulnerability analysis: reads dependency/CI reports from the CI agents and watches for abnormal admin activity. */
async function security({ pool }) {
  const out = [];
  const dep = (await pool.query(`SELECT status, title, created_at FROM agent_reports WHERE agent = 'deps-watch' ORDER BY created_at DESC LIMIT 1`)).rows[0];
  if (dep && dep.status === 'fail') out.push({ fingerprint: 'security:deps', agent: 'security', severity: 'high', title: 'Dependency audit: ' + dep.title, evidence: dep,
    root_cause: 'A dependency has a known high or critical vulnerability.', proposal: 'Update the dependency on the integration branch, run the full CI matrix, open a draft PR.', risk: 'medium' });
  const adm = (await pool.query(`SELECT count(*)::int AS n FROM admin_audit WHERE at > now() - interval '1 hour'`)).rows[0].n;
  if (adm > 30) out.push({ fingerprint: 'security:admin-burst', agent: 'security', severity: 'medium', title: `${adm} admin actions in the last hour`, evidence: { admin_actions_1h: adm },
    root_cause: 'Unusually many console changes: a script, a mistake, or a leaked admin token.', proposal: 'Review the audit log; rotate ADMIN_TOKEN if any action is not yours.', risk: 'none' });
  const blocked = (await pool.query(`SELECT count(*)::int AS n FROM devices WHERE blocked`)).rows[0].n;
  if (blocked > 50) out.push({ fingerprint: 'security:blocked', agent: 'security', severity: 'low', title: `${blocked} blocked devices`, evidence: { blocked },
    root_cause: 'Many devices blocked from the console.', proposal: 'Confirm that this is intended.', risk: 'none' });
  return out;
}

/** 5. Performance: database latency trend from the tick's own measurements. */
async function performance({ pool }) {
  const out = [];
  const r = (await pool.query(`SELECT percentile_cont(0.95) WITHIN GROUP (ORDER BY (details->>'ms')::numeric) AS p95, count(*)::int AS n
      FROM agent_reports WHERE agent = 'db-health' AND details ? 'ms' AND created_at > now() - interval '2 hours'`)).rows[0];
  if (r.n >= 6 && Number(r.p95) > 800) out.push({ fingerprint: 'perf:db-latency', agent: 'performance', severity: 'medium',
    title: `Database round trip p95 is ${Math.round(Number(r.p95))} ms over ${r.n} checks`, evidence: { p95_ms: Math.round(Number(r.p95)), samples: r.n },
    root_cause: 'The database is slow to answer from the API region (cold compute, distance, or load).', proposal: 'Check Neon region vs the Vercel function region; keep the pooled connection string.', risk: 'none' });
  const size = (await pool.query(`SELECT status, title FROM agent_reports WHERE agent = 'size-watch' ORDER BY created_at DESC LIMIT 1`)).rows[0];
  if (size && size.status !== 'ok') out.push({ fingerprint: 'perf:apk-size', agent: 'performance', severity: size.status === 'fail' ? 'high' : 'low',
    title: 'APK size: ' + size.title, evidence: size, root_cause: 'Release APK grew past its budget.', proposal: 'Find the new dependency or asset (R8 report, resource shrinking) and remove it.', risk: 'medium' });
  return out;
}

/** 6. Regression testing and QA: turns the CI agent's verdict into a finding with a concrete next step. */
async function qa({ pool }) {
  const out = [];
  const ci = (await pool.query(`SELECT status, title, details, ref FROM agent_reports WHERE agent = 'ci-watch' ORDER BY created_at DESC LIMIT 1`)).rows[0];
  if (ci && ci.status === 'fail') out.push({ fingerprint: 'qa:ci-failing', agent: 'qa', severity: 'high', title: 'CI: ' + ci.title, evidence: ci.details,
    root_cause: 'A real (non-infrastructure) job failure on the integration branch.', proposal: 'Read the failing job log, fix on the integration branch, run the full matrix, open a draft PR.', risk: 'medium' });
  return out;
}

/** 7. Diagnostic agent: turns the always-on watcher's classified incidents into investigated findings.
 *  It does not just relay "something failed": it keeps the watcher's probe evidence, the classified kind (website vs API vs
 *  database vs wrong deployment), the probable cause and the concrete next step, and routes it to the owning agent. */
const OWNER = { 'wrong-deployment': 'infrastructure', 'platform-outage': 'infrastructure', 'api-outage': 'availability', 'website-outage': 'availability', 'database-outage': 'db-optimizer' };
async function diagnostic({ pool }) {
  const r = (await pool.query(`SELECT status, title, details, created_at FROM agent_reports WHERE agent = 'watch' AND created_at > now() - interval '40 minutes' ORDER BY created_at DESC LIMIT 1`)).rows[0];
  if (!r || r.status !== 'fail') return [];
  const inc = r.details && r.details.incident;
  if (!inc || !OWNER[inc.kind]) return [{ fingerprint: 'incident:unclassified', agent: 'diagnostic', severity: 'high', title: 'Live probes failing: ' + r.title,
    evidence: { bad: (r.details && r.details.bad) || [] }, root_cause: 'The watcher saw failures it could not classify.', proposal: 'Inspect the listed probe failures.', risk: 'high' }];
  return [{ fingerprint: 'incident:' + inc.kind, agent: OWNER[inc.kind], severity: inc.severity === 'critical' ? 'critical' : 'high',
    title: `${inc.kind} (${inc.scope}): ${r.title}`, evidence: { probes: inc.evidence, observed_at: r.created_at, diagnosed_by: 'diagnostic' },
    root_cause: inc.cause, proposal: inc.next, risk: 'high' }];
}

module.exports = { availability, database, androidCompat, security, performance, qa, diagnostic };
