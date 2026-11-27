'use strict';
// Agent "size-watch": release APK size from the latest release-smoke CI log, against the 12 MiB gate and a growth budget.
const { report, gh } = require('./lib');
const BRANCH = process.env.WATCH_BRANCH || 'integration/reconcile-main';
const GATE = 12 * 1024 * 1024, BUDGET = 1.25 * 972583; // budget: +25% over the Quick Tools release candidate
(async () => {
  const rr = await (await gh(`/actions/runs?branch=${encodeURIComponent(BRANCH)}&per_page=30`)).json();
  const run = (rr.workflow_runs || []).find((r) => /release APK/i.test(r.name) && r.conclusion === 'success');
  if (!run) return report('size-watch', 'warn', 'no successful release-APK run found');
  const job = (await (await gh(`/actions/runs/${run.id}/jobs`)).json()).jobs.find((j) => /API 36/.test(j.name));
  const log = await gh(`/actions/jobs/${job.id}/logs`, { redirect: 'follow' });
  if (!log.ok) return report('size-watch', 'warn', `cannot read release log (HTTP ${log.status})`);
  const m = (await log.text()).match(/-rw-r--r-- \d+ \w+ \w+ (\d+) .*app-release\.apk/);
  if (!m) return report('size-watch', 'warn', 'APK size not found in the log');
  const size = Number(m[1]);
  const status = size > GATE ? 'fail' : size > BUDGET ? 'warn' : 'ok';
  await report('size-watch', status, `release APK ${size.toLocaleString('en')} bytes (gate ${GATE.toLocaleString('en')}, budget ${Math.round(BUDGET).toLocaleString('en')})`, { size, run: run.id }, run.head_sha.slice(0, 7));
})();
