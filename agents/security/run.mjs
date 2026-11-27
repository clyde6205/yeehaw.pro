// CLI: node agents/security/run.mjs --api https://api.yeehaw.pro [--repo .] [--burst 0] [--publish --token-env AGENT_TOKEN]
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { scanApi, scanSecrets, parseAudit, scanAndroidManifest } from './scan.mjs';
import { publish } from '../infra/analyze.mjs';
const arg = (n, d) => { const i = process.argv.indexOf('--' + n); return i < 0 ? d : (process.argv[i + 1] && !process.argv[i + 1].startsWith('--') ? process.argv[i + 1] : true); };
const out = { evidence_class: arg('class', 'local'), at: new Date().toISOString() }, findings = [];
const repo = arg('repo', null);
if (repo) {
  const files = execFileSync('git', ['-C', repo, 'ls-files'], { encoding: 'utf8' }).split('\n').filter(Boolean)
    .map((p) => { try { return { path: p, content: readFileSync(repo + '/' + p, 'utf8') }; } catch { return null; } }).filter(Boolean);
  const s = scanSecrets(files); out.secrets = { files_scanned: files.length, hits: s.hits.length }; findings.push(...s.findings);
  try { const a = parseAudit(JSON.parse(execFileSync('npm', ['audit', '--omit=dev', '--json'], { cwd: repo + '/console/backend', encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }))); out.dependencies = a.counts; findings.push(...a.findings); }
  catch (e) { try { const a = parseAudit(JSON.parse(e.stdout)); out.dependencies = a.counts; findings.push(...a.findings); } catch { out.dependencies = 'npm audit could not run'; } }
  findings.push(...scanAndroidManifest(readFileSync(repo + '/app/src/main/AndroidManifest.xml', 'utf8')));
}
if (arg('api', null)) { const r = await scanApi(String(arg('api')), { burst: Number(arg('burst', 0)) }); out.api = { passed: r.passed, total: r.total, failed: r.checks.filter((c) => !c.ok) }; findings.push(...r.findings); }
out.findings = findings.map((f) => ({ fingerprint: f.fingerprint, severity: f.severity, title: f.title }));
if (arg('publish', false)) out.published = await publish({ base: String(arg('api')), token: process.env[String(arg('token-env', 'AGENT_TOKEN'))], findings });
console.log(JSON.stringify(out, null, 2));
