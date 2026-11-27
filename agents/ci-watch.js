'use strict';
// Agent "ci-watch": looks at the latest CI runs on the integration branch. If a job failed ONLY because Maven Central
// rate-limited the runner (HTTP 429), it re-runs the failed jobs, at most twice per commit. Everything else is reported
// for a human or a fixer agent: it never edits code, never touches main, never merges.
const { report, gh, GH_TOKEN } = require('./lib');
const BRANCH = process.env.WATCH_BRANCH || 'integration/reconcile-main';
(async () => {
  const rr = await gh(`/actions/runs?branch=${encodeURIComponent(BRANCH)}&per_page=30`);
  if (!rr.ok) return report('ci-watch', 'fail', `cannot read CI runs (HTTP ${rr.status})`);
  const runs = (await rr.json()).workflow_runs;
  if (!runs.length) return report('ci-watch', 'warn', `no CI runs found on ${BRANCH}`);
  const head = runs[0].head_sha;
  const mine = runs.filter((r) => r.head_sha === head);
  const pending = mine.filter((r) => r.status !== 'completed');
  const failed = mine.filter((r) => r.status === 'completed' && !['success', 'skipped', 'cancelled'].includes(r.conclusion));
  if (pending.length) return report('ci-watch', 'warn', `${pending.length} run(s) still in progress on ${head.slice(0, 7)}`, { run_ids: pending.map((r) => r.id) }, head.slice(0, 7));
  if (!failed.length) return report('ci-watch', 'ok', `all ${mine.length} runs green on ${head.slice(0, 7)}`, { runs: mine.map((r) => `${r.name}: ${r.conclusion}`) }, head.slice(0, 7));
  const real = [], rerun = [];
  for (const run of failed) {
    const jobs = (await (await gh(`/actions/runs/${run.id}/jobs`)).json()).jobs.filter((j) => j.conclusion === 'failure');
    let onlyInfra = jobs.length > 0;
    for (const j of jobs) {
      const log = await gh(`/actions/jobs/${j.id}/logs`, { redirect: 'follow' });
      const text = log.ok ? await log.text() : '';
      const infra = /Received status code 429 from server: Too Many Requests/.test(text) && !/Execute app\.yehaw\.browser\.\S+: FAILED/.test(text);
      if (!infra) { onlyInfra = false; real.push(`${run.name} / ${j.name} (job ${j.id})`); }
    }
    if (onlyInfra && run.run_attempt < 3 && GH_TOKEN) {
      const r = await gh(`/actions/runs/${run.id}/rerun-failed-jobs`, { method: 'POST' });
      if (r.status === 201) rerun.push(`${run.name} (attempt ${run.run_attempt} -> ${run.run_attempt + 1})`); else real.push(`${run.name}: rerun refused HTTP ${r.status}`);
    }
  }
  if (real.length) return report('ci-watch', 'fail', `${real.length} real failure(s) on ${head.slice(0, 7)}`, { real, rerun }, head.slice(0, 7));
  if (rerun.length) return report('ci-watch', 'fixed', `Maven rate-limit failures re-run on ${head.slice(0, 7)}`, { rerun }, head.slice(0, 7));
  return report('ci-watch', 'warn', `failed runs on ${head.slice(0, 7)} could not be classified`, { runs: failed.map((r) => r.name) }, head.slice(0, 7));
})();
