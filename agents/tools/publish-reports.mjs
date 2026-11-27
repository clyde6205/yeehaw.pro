// Posts the tool agents' results to the agent API so the founder console shows them.
// usage: AGENT_TOKEN=... node agents/tools/publish-reports.mjs --api https://api.yeehaw.pro [--repo .] [--only security,android,perf] [--dry]
// Reports carry a stable ref (tool + commit) so re-posting the same result is deduplicated by the API. Never prints the token.
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { scanSecrets, parseAudit, scanAndroidManifest, scanApi } from '../security/scan.mjs';
import { analyzeRepo, LIMITATION } from '../android/analyze.mjs';
const arg = (n, d) => { const i = process.argv.indexOf('--' + n); return i < 0 ? d : (process.argv[i + 1] && !process.argv[i + 1].startsWith('--') ? process.argv[i + 1] : true); };
const base = String(arg('api', '')).replace(/\/$/, ''), repo = String(arg('repo', '.')), dry = !!arg('dry', false);
const only = String(arg('only', 'security,android,perf')).split(',');
const token = process.env.AGENT_TOKEN || '';
const sha = execFileSync('git', ['-C', repo, 'rev-parse', '--short', 'HEAD'], { encoding: 'utf8' }).trim();
const reports = [];

if (only.includes('security')) {
  const files = execFileSync('git', ['-C', repo, 'ls-files'], { encoding: 'utf8' }).split('\n').filter(Boolean)
    .map((p) => { try { return { path: p, content: readFileSync(repo + '/' + p, 'utf8') }; } catch { return null; } }).filter(Boolean);
  const s = scanSecrets(files.filter((f) => !/\.test\.[mc]?js$/.test(f.path))); // test files hold deliberate fake secrets for the scanner's own tests
  const man = scanAndroidManifest(readFileSync(repo + '/app/src/main/AndroidManifest.xml', 'utf8'));
  const live = base ? await scanApi(base, { burst: 0 }) : null;
  const crit = [...s.findings, ...man, ...(live ? live.findings : [])];
  reports.push({ agent: 'security-scan', status: crit.length ? 'warn' : 'ok', ref: 'security-' + sha,
    title: `security scan: ${live ? live.passed + '/' + live.total + ' live API checks passed, ' : ''}${s.hits.length} secret hits in ${files.length} files, ${man.length} manifest findings`,
    details: { commit: sha, evidence_class: live ? 'production (live API checks) + local (repo scan)' : 'local', secrets_hits: s.hits.length, files_scanned: files.length, manifest_findings: man.length, live_failed: live ? live.checks.filter((c) => !c.ok).map((c) => c.name || c.id) : null } });
}
if (only.includes('android')) {
  const r = analyzeRepo(repo);
  reports.push({ agent: 'android-analysis', status: (r.findings || []).length ? 'warn' : 'ok', ref: 'android-' + sha,
    title: `android static analysis: ${(r.findings || []).length} finding(s)`,
    details: { commit: sha, evidence_class: 'static analysis (no device, no emulator)', limitation: LIMITATION, facts: r.facts, findings: (r.findings || []).map((f) => ({ severity: f.severity, title: f.title })) } });
}
if (only.includes('perf')) {
  const b = JSON.parse(readFileSync(repo + '/docs/perf/before.json', 'utf8')), a = JSON.parse(readFileSync(repo + '/docs/perf/after.json', 'utf8'));
  for (const k of Object.keys(b.endpoints)) {
    const x = b.endpoints[k], y = a.endpoints[k]; if (!y) continue;
    reports.push({ agent: 'perf-bench', status: 'ok', ref: 'perf-' + k.replace(/[^a-z0-9]+/gi, '-').slice(0, 50).toLowerCase() + '-' + String(a.measured_at).slice(0, 10), title: k,
      details: { before: `${x.db_round_trips_per_request} DB round trip(s), p50 ${x.p50_ms} ms`, after: `${y.db_round_trips_per_request} DB round trip(s), p50 ${y.p50_ms} ms`, evidence_class: 'local (embedded Postgres on the dev machine; not production, not on-device). The round-trip count is the transferable result; ms are local.' } });
  }
}
if (dry || !base) { console.log(JSON.stringify(reports, null, 1)); process.exit(0); }
if (!token) { console.error('AGENT_TOKEN not set'); process.exit(2); }
for (const r of reports) {
  const res = await fetch(base + '/v1/agent/report', { method: 'POST', headers: { 'content-type': 'application/json', authorization: 'Bearer ' + token }, body: JSON.stringify(r) });
  console.log(r.agent, r.ref, 'HTTP', res.status);
}
