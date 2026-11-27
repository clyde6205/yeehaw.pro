'use strict';
// Controlled orchestration of the specialist agents.
//   detect  : every specialist reports findings (read only)
//   record  : findings are upserted by fingerprint; cleared ones are auto-resolved with a recorded verification
//   repair  : ONLY allowlisted, low-risk operational repairs run, and only if the finding is risk none/low (automatic)
//             or the owner approved it in the console. Each repair is verified by re-running the check that found it.
//   audit   : every step writes an agent_actions row. Nothing here edits code, touches main, merges, or reads secrets.
const S = require('./specialists');

const REPAIRS = {
  // key -> { run(pool, arg), verify(pool, arg) -> string|null (null = verified ok) }
  async prune_agent_reports(pool) {
    const r = await pool.query(`DELETE FROM agent_reports WHERE created_at < now() - interval '30 days'`);
    return `deleted ${r.rowCount} agent report rows older than 30 days (agents' own history only, no user data)`;
  },
  async analyze(pool, table) {
    if (!/^[a-z_][a-z0-9_]{0,62}$/.test(table)) throw new Error('bad table name');
    const ok = await pool.query(`SELECT 1 FROM pg_stat_user_tables WHERE relname = $1`, [table]);
    if (!ok.rowCount) throw new Error('unknown table');
    await pool.query(`ANALYZE "${table}"`);
    return `ANALYZE ${table} completed`;
  },
};
// Independent verification (QA agent): a check that does NOT reuse the detector that raised the finding. A repair is only
// "verified" when this check AND the original detector re-run both pass. Returns null when verified, else the failure reason.
const REPAIR_VERIFY = {
  async prune_agent_reports(pool) {
    const n = Number((await pool.query(`SELECT count(*) AS n FROM agent_reports WHERE created_at < now() - interval '30 days'`)).rows[0].n);
    return n === 0 ? null : `${n} agent report rows older than 30 days still exist`;
  },
  async analyze(pool, table) {
    const r = (await pool.query(`SELECT greatest(last_analyze, last_autoanalyze) AS t FROM pg_stat_user_tables WHERE relname = $1`, [table])).rows[0];
    return r && r.t && Date.now() - new Date(r.t).getTime() < 120_000 ? null : `${table} shows no ANALYZE in the last 2 minutes`;
  },
};
const DEFAULTS = { enabled: true, disabled_agents: [], interval_minutes: 5, auto_repairs: ['prune_agent_reports', 'analyze'] };
async function getSettings(pool) {
  const r = await pool.query('SELECT enabled, disabled_agents, interval_minutes, auto_repairs, updated_at FROM engine_settings WHERE id = 1');
  return r.rows[0] || { ...DEFAULTS };
}
const parseRepair = (key) => { const [name, arg] = String(key).split(':'); return REPAIRS[name] ? { fn: REPAIRS[name], arg, name } : null; };
const log = (pool, id, actor, action, result, verification = null) =>
  pool.query('INSERT INTO agent_actions (finding_id, actor, action, result, verification) VALUES ($1,$2,$3,$4,$5)', [id, actor, action, result, verification]);

// Dependencies between agents' findings: a downstream problem is not repaired while the upstream one that probably causes it is still open.
const DEPENDS = { 'perf:': ['availability:', 'db:', 'dbr:'], 'security:android': [], 'qa:': ['availability:', 'db:'] };
async function blockedBy(pool, f) {
  const key = Object.keys(DEPENDS).find((k) => f.fingerprint.startsWith(k));
  if (!key || !DEPENDS[key].length) return null;
  const rows = (await pool.query(`SELECT fingerprint FROM agent_findings WHERE id <> $1 AND status IN ('open','approved','applied','failed') AND severity IN ('critical','high')`, [f.id])).rows;
  const hit = rows.find((r) => DEPENDS[key].some((p) => r.fingerprint.startsWith(p)));
  return hit ? hit.fingerprint : null;
}
// Learning from earlier outcomes: an automatic repair that independent QA has rejected twice in 14 days stops running by itself.
async function failedBefore(pool, f) {
  const r = await pool.query(`SELECT count(*)::int AS n FROM agent_actions a JOIN agent_findings g ON g.id = a.finding_id WHERE a.action = 'independent verification' AND a.verification = 'failed' AND g.repair = $1 AND a.created_at > now() - interval '14 days'`, [f.repair]);
  return r.rows[0].n;
}
const logOnce = async (pool, id, actor, action, result) => { if (!(await pool.query('SELECT 1 FROM agent_actions WHERE finding_id = $1 AND action = $2 AND result = $3 LIMIT 1', [id, action, result])).rowCount) await log(pool, id, actor, action, result); };

async function detect(pool, specialists, disabled = [], perAgent = {}) {
  const found = [], errors = [];
  for (const [name, fn] of Object.entries(specialists)) {
    if (disabled.includes(name)) { perAgent[name] = { state: 'disabled' }; continue; }
    const t0 = Date.now();
    try { const got = await fn({ pool }); found.push(...got); perAgent[name] = { state: 'ok', found: got.length, ms: Date.now() - t0 }; }
    catch (e) { errors.push(`${name}: ${e.message}`); perAgent[name] = { state: 'error', error: e.message, ms: Date.now() - t0 }; }
  }
  return { found, errors };
}

/** Correlate findings so the console shows one probable root cause instead of several symptoms. */
function diagnose(found) {
  const has = (p) => found.some((f) => f.fingerprint.startsWith(p));
  if (has('availability:site-down') && (has('perf:db-latency') || has('db:connections')))
    for (const f of found) if (f.fingerprint.startsWith('availability:site-down')) f.root_cause = 'Probable cause: database slowness or connection exhaustion (see the database findings) rather than the website itself.';
  return found;
}

async function runOnce(pool, { specialists = S, now = Date.now, respectInterval = false } = {}) {
  const cfg = await getSettings(pool);
  if (!cfg.enabled) return { lease: 'skipped', reason: 'engine is switched off in the founder console' };
  if (respectInterval) {
    const recent = await pool.query(`SELECT 1 FROM agent_runs WHERE started_at > now() - ($1 || ' minutes')::interval LIMIT 1`, [String(cfg.interval_minutes)]);
    if (recent.rowCount) return { lease: 'skipped', reason: `ran within the configured ${cfg.interval_minutes} minute interval` };
  }
  const lease = await pool.query(`UPDATE agent_lease SET until = now() + interval '2 minutes' WHERE id = 1 AND until < now() RETURNING id`);
  if (!lease.rowCount) return { lease: 'skipped', reason: 'another run is in progress or ran within the last 2 minutes' };
  const run = (await pool.query('INSERT INTO agent_runs DEFAULT VALUES RETURNING id')).rows[0].id;
  const perAgent = {};
  const summary = { per_agent: perAgent, detected: 0, opened: 0, resolved: 0, repaired: 0, verified: 0, failed: 0, errors: [] };
  try {
    const { found, errors } = await detect(pool, specialists, cfg.disabled_agents, perAgent);
    summary.errors = errors; summary.detected = found.length;
    diagnose(found);
    const live = new Set();
    for (const f of found) {
      live.add(f.fingerprint);
      const ex = await pool.query(`SELECT id FROM agent_findings WHERE fingerprint = $1 AND status IN ('open','approved','applied','failed')`, [f.fingerprint]);
      if (ex.rowCount) { await pool.query(`UPDATE agent_findings SET evidence=$2, title=$3, severity=$4, root_cause=$5, updated_at=now() WHERE id=$1`, [ex.rows[0].id, f.evidence || {}, f.title, f.severity, f.root_cause || null]); continue; }
      const ins = await pool.query(`INSERT INTO agent_findings (fingerprint, agent, severity, title, evidence, root_cause, proposal, risk, repair)
        VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9) RETURNING id`, [f.fingerprint, f.agent, f.severity, f.title, f.evidence || {}, f.root_cause || null, f.proposal || null, f.risk || 'medium', f.repair || null]);
      await log(pool, ins.rows[0].id, f.agent, 'detected', f.title); summary.opened++;
    }
    // Findings whose condition has cleared: resolve, recording that the check no longer sees the problem (verification).
    const openRows = (await pool.query(`SELECT id, fingerprint FROM agent_findings WHERE status IN ('open','approved','applied','failed')`)).rows;
    const specialistFailed = errors.length > 0;
    for (const r of openRows) if (!live.has(r.fingerprint) && !specialistFailed) {
      await pool.query(`UPDATE agent_findings SET status = 'resolved', resolved_at = now(), updated_at = now() WHERE id = $1`, [r.id]);
      await log(pool, r.id, 'orchestrator', 'auto-resolved', 'condition no longer detected', 'the detecting check re-ran and no longer reports it'); summary.resolved++;
    }
    // Repairs: low-risk allowlisted ones automatically, plus anything the owner approved.
    const todo = (await pool.query(`SELECT * FROM agent_findings WHERE repair IS NOT NULL AND (status = 'approved' OR (status = 'open' AND risk IN ('none','low') )) ORDER BY id LIMIT 5`)).rows;
    for (const f of todo) {
      const rp = parseRepair(f.repair);
      if (!rp) { await log(pool, f.id, 'orchestrator', 'repair refused', `repair "${f.repair}" is not on the allowlist`); continue; }
      if (f.status === 'open' && !cfg.auto_repairs.includes(rp.name)) continue; // not permitted to run by itself; waits for the owner
      const dep = await blockedBy(pool, f);
      if (dep) { await logOnce(pool, f.id, 'orchestrator', 'deferred', `waiting on upstream finding ${dep}; it is the more likely cause`); continue; }
      if (f.status === 'open') { const n = await failedBefore(pool, f); if (n >= 2) { await logOnce(pool, f.id, 'orchestrator', 'repair skipped (learned)', `repair ${rp.name} was rejected by independent QA ${n} times in the last 14 days; it will not run automatically, the owner must approve it`); continue; } }
      try {
        const result = await rp.fn(pool, rp.arg); summary.repaired++;
        await pool.query(`UPDATE agent_findings SET status = 'applied', updated_at = now() WHERE id = $1`, [f.id]);
        await log(pool, f.id, 'repair', 'repair ' + rp.name, result);
        // verify independently: (1) a check that does not reuse the detector, (2) the detector itself re-run. Both must pass.
        const indep = REPAIR_VERIFY[rp.name] ? await REPAIR_VERIFY[rp.name](pool, rp.arg) : 'no independent verification is defined for this repair';
        const again = await detect(pool, specialists, cfg.disabled_agents);
        const still = again.found.some((x) => x.fingerprint === f.fingerprint);
        const failed = still || !!indep;
        await pool.query(`UPDATE agent_findings SET status = $2, updated_at = now(), resolved_at = CASE WHEN $2 = 'verified' THEN now() END WHERE id = $1`, [f.id, failed ? 'failed' : 'verified']);
        await log(pool, f.id, 'qa', 'independent verification', failed ? 'FAILED: ' + (indep || 'the problem is still detected after the repair') : 'verified: independent check passed and the problem is no longer detected', failed ? 'failed' : 'passed');
        failed ? summary.failed++ : summary.verified++;
      } catch (e) {
        await pool.query(`UPDATE agent_findings SET status = 'failed', updated_at = now() WHERE id = $1`, [f.id]);
        await log(pool, f.id, 'orchestrator', 'repair error', e.message); summary.failed++;
      }
    }
    await pool.query('UPDATE agent_runs SET finished_at = now(), ok = $2, summary = $3 WHERE id = $1', [run, errors.length === 0, summary]);
    return { lease: 'acquired', run, summary };
  } catch (e) {
    await pool.query('UPDATE agent_runs SET finished_at = now(), ok = false, summary = $2 WHERE id = $1', [run, { ...summary, fatal: e.message }]);
    throw e;
  } finally { await pool.query(`UPDATE agent_lease SET until = now() + interval '45 seconds' WHERE id = 1`); }
}

module.exports = { runOnce, detect, diagnose, REPAIRS, REPAIR_VERIFY, getSettings };
