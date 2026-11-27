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
const parseRepair = (key) => { const [name, arg] = String(key).split(':'); return REPAIRS[name] ? { fn: REPAIRS[name], arg, name } : null; };
const log = (pool, id, actor, action, result, verification = null) =>
  pool.query('INSERT INTO agent_actions (finding_id, actor, action, result, verification) VALUES ($1,$2,$3,$4,$5)', [id, actor, action, result, verification]);

async function detect(pool, specialists) {
  const found = [], errors = [];
  for (const [name, fn] of Object.entries(specialists)) {
    try { found.push(...(await fn({ pool }))); } catch (e) { errors.push(`${name}: ${e.message}`); }
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

async function runOnce(pool, { specialists = S, now = Date.now } = {}) {
  const lease = await pool.query(`UPDATE agent_lease SET until = now() + interval '2 minutes' WHERE id = 1 AND until < now() RETURNING id`);
  if (!lease.rowCount) return { lease: 'skipped', reason: 'another run is in progress or ran within the last 2 minutes' };
  const run = (await pool.query('INSERT INTO agent_runs DEFAULT VALUES RETURNING id')).rows[0].id;
  const summary = { detected: 0, opened: 0, resolved: 0, repaired: 0, verified: 0, failed: 0, errors: [] };
  try {
    const { found, errors } = await detect(pool, specialists);
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
    const todo = (await pool.query(`SELECT * FROM agent_findings WHERE repair IS NOT NULL AND (status = 'approved' OR (status = 'open' AND risk IN ('none','low'))) ORDER BY id LIMIT 5`)).rows;
    for (const f of todo) {
      const rp = parseRepair(f.repair);
      if (!rp) { await log(pool, f.id, 'orchestrator', 'repair refused', `repair "${f.repair}" is not on the allowlist`); continue; }
      try {
        const result = await rp.fn(pool, rp.arg); summary.repaired++;
        await pool.query(`UPDATE agent_findings SET status = 'applied', updated_at = now() WHERE id = $1`, [f.id]);
        await log(pool, f.id, 'orchestrator', 'repair ' + rp.name, result);
        // verify: re-run the same specialist and see whether the fingerprint is gone
        const again = await detect(pool, specialists);
        const still = again.found.some((x) => x.fingerprint === f.fingerprint);
        await pool.query(`UPDATE agent_findings SET status = $2, updated_at = now(), resolved_at = CASE WHEN $2 = 'verified' THEN now() END WHERE id = $1`, [f.id, still ? 'failed' : 'verified']);
        await log(pool, f.id, 'orchestrator', 'verification', still ? 'FAILED: the problem is still detected after the repair' : 'verified: the problem is no longer detected', still ? 'failed' : 'passed');
        still ? summary.failed++ : summary.verified++;
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

module.exports = { runOnce, detect, diagnose, REPAIRS };
