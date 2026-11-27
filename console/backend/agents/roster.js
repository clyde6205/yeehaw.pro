'use strict';
// The eight named agents and what each is made of. Pure function: it only reads rows already loaded by the console endpoint.
// An agent shows "ok" only when something actually ran and succeeded; otherwise "never-run". No placeholder statuses.
const ROSTER = [
  { id: 'monitoring', name: 'Monitoring Intelligence', specialists: ['availability'], tools: ['watch', 'watch-selftest', 'site-health'], findingAgents: ['availability'], does: 'Website/API availability, the always-on watcher heartbeat, delivery health' },
  { id: 'diagnostic', name: 'Diagnostic', specialists: ['diagnostic'], tools: [], findingAgents: ['diagnostic'], does: 'Classifies watcher incidents (website vs API vs database vs wrong deployment) and names the probable cause' },
  { id: 'repair', name: 'Autonomous Repair', specialists: [], tools: [], actors: ['repair'], findingAgents: ['repair'], does: 'Prepares code fixes on isolated branches with tests; rolls back its own branches' },
  { id: 'performance', name: 'Performance Optimization', specialists: ['performance'], tools: ['perf-bench'], findingAgents: ['performance'], does: 'Measured before/after benchmarks; database latency; APK size' },
  { id: 'security', name: 'Security', specialists: ['security'], tools: ['security-scan'], findingAgents: ['security'], does: 'Active API scan, dependency audit, secret scan, Android attack surface, admin-activity anomalies' },
  { id: 'qa', name: 'Quality Assurance', specialists: ['qa'], tools: [], actors: ['qa'], findingAgents: ['qa'], does: 'Independent verification of repairs in a separate worktree/database; CI verdicts' },
  { id: 'database', name: 'Database Reliability', specialists: ['database'], tools: [], findingAgents: ['db-optimizer'], does: 'Schema drift, integrity, query timing, failed runs, migration safety' },
  { id: 'android', name: 'Android Compatibility', specialists: ['androidCompat'], tools: ['android-analysis'], findingAgents: ['android-compat', 'android'], does: 'Fleet crash segmentation, static analysis of the app, representative web-layer profile (no device claims)' },
];
const LIVE = new Set(['open', 'approved', 'applied', 'failed']);

function buildRoster({ runs = [], latest = [], findings = [], actions = [], disabled = [] }) {
  return ROSTER.map((a) => {
    const per = runs.map((r) => ({ at: r.finished_at || r.started_at, s: a.specialists.map((n) => r.summary && r.summary.per_agent && r.summary.per_agent[n]).filter(Boolean) })).filter((x) => x.s.length);
    const lastRun = per[0] || null;
    const lastOk = per.find((x) => x.s.every((s) => s.state === 'ok')) || null;
    const tool = latest.filter((l) => a.tools.includes(l.agent)).sort((x, y) => new Date(y.created_at) - new Date(x.created_at))[0] || null;
    const acts = a.actors ? actions.filter((x) => a.actors.includes(x.actor)) : [];
    const mine = findings.filter((f) => a.findingAgents.includes(f.agent));
    const isDisabled = a.specialists.length > 0 && a.specialists.every((n) => disabled.includes(n));
    const executedEver = !!(lastOk || tool || acts.length);
    let status = 'never-run';
    if (isDisabled) status = 'disabled';
    else if (lastRun && lastRun.s.some((s) => s.state === 'error')) status = 'error';
    else if (executedEver) status = 'ok';
    const ts = [lastOk && lastOk.at, tool && tool.created_at, acts[0] && acts[0].created_at].filter(Boolean).map((t) => new Date(t).getTime());
    return {
      id: a.id, name: a.name, does: a.does, status,
      last_success_at: ts.length ? new Date(Math.max(...ts)).toISOString() : null,
      last_run_ms: lastRun ? lastRun.s.reduce((t, s) => t + (s.ms || 0), 0) : null,
      last_error: lastRun ? (lastRun.s.find((s) => s.error) || {}).error || null : null,
      tool_last: tool ? { agent: tool.agent, status: tool.status, title: tool.title, at: tool.created_at } : null,
      open_findings: mine.filter((f) => LIVE.has(f.status)).length,
      verified_findings: mine.filter((f) => f.status === 'verified').length,
      failed_findings: mine.filter((f) => f.status === 'failed').length,
      actions_recorded: acts.length,
    };
  });
}
module.exports = { ROSTER, buildRoster };
