// Infrastructure & cost intelligence. Pure functions over data collected from the Vercel, Cloudflare and Neon APIs.
// Collection is separate (connectors today; read-only API tokens as Worker secrets would let it run unattended - owner decision).
// Every finding carries the evidence it came from. Nothing here changes any infrastructure.
const median = (a) => { const s = [...a].sort((x, y) => x - y); return s.length ? s[Math.floor(s.length / 2)] : null; };
const F = (fingerprint, severity, title, evidence, root_cause, proposal, risk = 'high') => ({ fingerprint, agent: 'infrastructure', severity, title, evidence, root_cause, proposal, risk });
const buildMs = (d) => (d.ready && d.buildingAt ? d.ready - d.buildingAt : null);

/** @param {{serving, deployments, now?, expect:{alias:string, headSha?:string, minBuildRatio?:number}}} i */
export function analyzeVercel({ serving, deployments, now = Date.now(), expect }) {
  const out = [];
  const prod = deployments.filter((d) => d.target === 'production');
  // 1. failed deployments in the last 7 days
  for (const d of prod.filter((x) => x.state === 'ERROR' && now - x.createdAt < 7 * 864e5))
    out.push(F('infra:deploy-failed:' + d.id.slice(4, 12), 'medium', `Production deployment ${d.id.slice(0, 12)} failed to build`, { deployment: d.id, state: d.state, created: new Date(d.createdAt).toISOString() },
      'The build errored; a failed production build does not replace the serving deployment but wastes a deploy and hides config problems.', 'Read the build log for this deployment (config parse, install or build step) and fix on the integration branch.', 'medium'));
  // 2. serving deployment must carry the API alias
  if (serving && !(serving.alias || []).includes(expect.alias))
    out.push(F('infra:alias-missing', 'critical', `Serving production deployment is not assigned to ${expect.alias}`, { serving: serving.id, aliases: serving.alias || [] },
      'The API domain points at a different deployment or none.', 'Assign the domain to the last good API deployment (owner approval).'));
  // 3. possible wrong root: the serving build was far faster than the normal API builds (a static-site build takes seconds)
  const ratio = expect.minBuildRatio ?? 0.6, base = median(prod.filter((d) => d.state === 'READY' && d.id !== serving?.id).map(buildMs).filter((x) => x));
  const sb = serving ? buildMs(serving) : null;
  if (sb && base && sb < base * ratio)
    out.push(F('infra:api-build-suspicious', 'critical', `Serving API build took ${(sb / 1000).toFixed(1)} s vs a normal ${(base / 1000).toFixed(1)} s`, { serving: serving.id, build_ms: sb, median_other_builds_ms: base },
      'A much shorter build usually means the wrong Root Directory was built (the static site instead of console/backend).', 'Confirm Root Directory = console/backend and that /livez returns JSON; redeploy with projectSettings.rootDirectory if not.'));
  // 4. deployment churn: the same commit deployed to production repeatedly within 2 hours
  const bySha = {};
  for (const d of prod) (bySha[d.meta?.githubCommitSha] ||= []).push(d);
  for (const [sha, list] of Object.entries(bySha)) {
    const recent = list.filter((d) => now - d.createdAt < 2 * 36e5);
    if (sha && recent.length >= 3) out.push(F('infra:deploy-churn:' + sha.slice(0, 7), 'low', `${recent.length} production deployments of ${sha.slice(0, 7)} within 2 hours`, { sha, deployments: recent.map((d) => d.id) },
      'Repeated redeploys of one commit: usually recovery attempts or a config problem being retried.', 'Verify real endpoints after each deploy; avoid redeploying without a changed input.', 'none'));
  }
  // 5. serving code behind the integration branch
  if (expect.headSha && serving?.meta?.githubCommitSha && serving.meta.githubCommitSha !== expect.headSha)
    out.push(F('infra:api-behind-branch', 'low', `Production API runs ${serving.meta.githubCommitSha.slice(0, 7)}, integration branch is at ${expect.headSha.slice(0, 7)}`, { serving_sha: serving.meta.githubCommitSha, head_sha: expect.headSha },
      'Fixes committed after the serving build are not live.', 'Review and approve a deployment (needs database migration approval if the schema changed).', 'medium'));
  return out;
}

export function analyzeCloudflare({ fight_mode, schedules = {}, expectNoCron = true }) {
  const out = [];
  if (fight_mode) out.push(F('infra:bot-fight-mode', 'high', 'Bot Fight Mode is on: non-browser clients (the app, monitors) can be challenged with 403', { fight_mode },
    'Free-plan Bot Fight Mode cannot be bypassed by custom rules.', 'Disable Bot Fight Mode (documented in RC_EVIDENCE) or move the API behind a different path.', 'medium'));
  for (const [name, list] of Object.entries(schedules))
    if (expectNoCron && Array.isArray(list) && list.length) out.push(F('infra:unexpected-cron:' + name, 'medium', `Worker ${name} has ${list.length} cron schedule(s)`, { worker: name, schedules: list },
      'Cron triggers were removed on purpose; scheduled runs consume resources and wake the database.', 'Remove the schedule unless it is justified.', 'medium'));
  return out;
}

/** Real consumption -> cost lines plus findings against the free plan. Never estimates money that was not measured. */
export function analyzeNeon(n, now = Date.now()) {
  const FREE_CU_HOURS = 100; // Neon free plan: 100 CU-hours per project per month
  const cuHours = (n.compute_time_seconds / 3600) * (n.autoscaling_limit_max_cu || 0.25);
  const start = new Date(n.consumption_period_start).getTime(), frac = Math.max((now - start) / (30 * 864e5), 0.01);
  const projected = cuHours / frac;
  const costs = [
    { provider: 'neon', metric: 'compute_cu_hours_month_to_date', value: Number(cuHours.toFixed(2)), unit: 'CU-hours', limit: FREE_CU_HOURS, plan: n.subscription_type },
    { provider: 'neon', metric: 'compute_cu_hours_projected_month', value: Number(projected.toFixed(2)), unit: 'CU-hours', limit: FREE_CU_HOURS, plan: n.subscription_type },
    { provider: 'neon', metric: 'active_hours_month_to_date', value: Number((n.active_time_seconds / 3600).toFixed(1)), unit: 'h' },
    { provider: 'neon', metric: 'data_transfer', value: Number((n.data_transfer_bytes / 1048576).toFixed(2)), unit: 'MiB' },
    { provider: 'neon', metric: 'billed_amount', value: 0, unit: 'USD', note: 'free plan; no paid service is in use' },
  ];
  const findings = [];
  if (projected > 0.7 * FREE_CU_HOURS) findings.push(F('infra:neon-compute-budget', projected > FREE_CU_HOURS ? 'high' : 'medium', `Neon compute projected at ${projected.toFixed(0)} of ${FREE_CU_HOURS} free CU-hours this month`, { cuHours, projected },
    'Constant database activity keeps compute awake.', 'Find the periodic caller (cron, watcher, health pings) and reduce it.', 'low'));
  return { costs, findings };
}

export async function publish({ base, token, findings, costs, fetchFn = fetch }) {
  const h = { 'content-type': 'application/json', authorization: 'Bearer ' + token };
  const res = { findings: [], costs: null };
  for (const f of findings) { const r = await fetchFn(base + '/v1/agent/finding', { method: 'POST', headers: h, body: JSON.stringify(f) }); res.findings.push({ fingerprint: f.fingerprint, http: r.status }); }
  if (costs) { const r = await fetchFn(base + '/v1/agent/report', { method: 'POST', headers: h, body: JSON.stringify({ agent: 'infra-cost', status: 'ok', title: 'infrastructure costs measured', details: { costs, note: 'measured from the provider APIs; no estimates' } }) }); res.costs = r.status; }
  return res;
}
