// Unattended repair cycle, run by GitHub Actions (.github/workflows/ai-repair.yml) or by hand:
//   detect failing test file -> read its source -> model proposes exact edits -> isolated worktree -> tests -> independent QA on a fresh checkout
//   -> record to the console -> push ONLY a repair/* branch and open a DRAFT pull request. It never merges, never touches main/wip branches, never deploys.
// Protected-area edits (workflows, deploy config, migrations, auth, manifest...) are allowed to be PROPOSED but the PR is labelled founder-approval.
// Bounded: at most one repair attempt per run, one open repair per failing file, no model call when everything is green.
import { execFileSync, spawnSync } from 'node:child_process';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { createHash } from 'node:crypto';
import { runRepair, verifyRepair, consoleReporter } from './index.mjs';
import { makeAiFixer, anthropicComplete } from './ai-fixer.mjs';

const sh = (cwd, cmd, args) => { const r = spawnSync(cmd, args, { cwd, encoding: 'utf8', timeout: 300_000, env: Object.fromEntries(Object.entries(process.env).filter(([k]) => k !== 'NODE_TEST_CONTEXT')) }); return { exit: r.status, out: (r.stdout || '') + (r.stderr || '') }; };

export function findFailingTests(repoDir, testFiles) {
  return testFiles.filter((f) => existsSync(join(repoDir, f))).map((f) => ({ file: f, ...sh(repoDir, 'node', ['--test', f]) })).filter((r) => r.exit !== 0);
}
/** Source files a test imports (relative imports only, excluding the test itself). */
export function sourcesOf(repoDir, testFile) {
  const text = readFileSync(join(repoDir, testFile), 'utf8'), out = new Set();
  for (const m of text.matchAll(/from\s+'(\.{1,2}\/[^']+)'|require\('(\.{1,2}\/[^']+)'\)/g)) {
    const rel = relative(repoDir, join(repoDir, dirname(testFile), m[1] || m[2]));
    if (existsSync(join(repoDir, rel)) && !/\.(test|spec)\./.test(rel)) out.add(rel);
  }
  return [...out];
}

export async function runCycle({ repoDir, base, testFiles, complete, report, hooks = {}, finding: given }) {
  const failing = findFailingTests(repoDir, testFiles);
  if (!failing.length) return { state: 'green', note: 'all watched tests pass; no model call made' };
  const f = failing[0], fp = 'ci:test-failing:' + f.file;
  const id = given?.id ?? parseInt(createHash('sha1').update(fp).digest('hex').slice(0, 6), 16);
  const finding = { id, fingerprint: fp, title: `Test failing: ${f.file}`, detail: 'A watched test fails on the current branch.' };
  if (hooks.alreadyAttempted && (await hooks.alreadyAttempted(finding))) return { state: 'skipped', note: 'a repair for this failing test already exists (open or previously failed); not trying again' };
  const files = sourcesOf(repoDir, f.file); if (!files.length) return { state: 'no-source', note: 'could not map the failing test to a source file', test: f.file };
  const fixer = makeAiFixer({ complete, files, finding, failingOutput: f.out });
  const testCmd = `node --test ${f.file}`;
  const out = await runRepair({ repoDir, base, finding, fixer, testCmd, report });
  if (!out.ok) return { state: 'rejected', stage: out.stage, error: out.error, finding };
  const qa = await verifyRepair({ repoDir, base, finding, commit: out.commit, testCmd, report });
  if (!qa.ok) return { state: 'qa-rejected', reasons: qa.reasons, finding };
  const needsFounder = !!fixer.needsFounderApproval;
  if (hooks.publish) await hooks.publish({ finding, branch: out.branch, commit: out.commit, needsFounder, rationale: fixer.proposal.rationale, sensitive: fixer.sensitive || [] });
  return { state: needsFounder ? 'proposed-needs-founder' : 'proposed', branch: out.branch, commit: out.commit, finding };
}

async function main() {
  const repoDir = process.cwd(), key = process.env.ANTHROPIC_API_KEY, base = process.env.BASE_BRANCH || execFileSync('git', ['rev-parse', '--abbrev-ref', 'HEAD'], { cwd: repoDir, encoding: 'utf8' }).trim();
  const consoleUrl = process.env.CONSOLE_URL, agentToken = process.env.AGENT_TOKEN;
  const post = async (path, body) => { if (!consoleUrl || !agentToken) return null; try { const r = await fetch(consoleUrl.replace(/\/$/, '') + path, { method: 'POST', headers: { 'content-type': 'application/json', authorization: 'Bearer ' + agentToken }, body: JSON.stringify(body) }); return r.ok ? r.json().catch(() => ({})) : null; } catch { return null; } };
  const testFiles = (process.env.WATCH_TESTS || 'agents/watch/watch.test.mjs agents/repair/repair.test.mjs agents/repair/ai-fixer.test.mjs agents/infra/analyze.test.mjs agents/infra/collect.test.mjs agents/android/analyze.test.mjs agents/worker/worker.test.mjs agents/security/cleartext-pin.test.mjs').split(/\s+/);
  if (!key) {
    console.log('AI provider not configured (ANTHROPIC_API_KEY repository secret missing); no repair attempted.');
    await post('/v1/agent/report', { agent: 'repair', status: 'warn', title: 'AI repair provider not configured', ref: 'ai-repair-provider', details: { missing: 'ANTHROPIC_API_KEY', how: 'docs/AI_REPAIR_SETUP.md' } }); return;
  }
  const complete = anthropicComplete({ apiKey: key, model: process.env.REPAIR_MODEL || 'claude-sonnet-5-5' });
  // register the finding with the console first so actions have a real finding id
  let consoleId = null;
  const report = async (a) => { if (consoleId != null) await consoleReporter(consoleUrl, agentToken)({ ...a, finding_id: consoleId }).catch(() => {}); };
  const hooks = {
    alreadyAttempted: async (f) => { const r = await post('/v1/agent/finding', { agent: 'repair', fingerprint: f.fingerprint, severity: 'medium', title: f.title, evidence: { watched_test: f.fingerprint }, root_cause: 'A watched test fails on ' + base, proposal: 'Prepare an AI-assisted fix on an isolated branch, verify it independently, open a draft PR.', risk: 'low' }); if (r && r.id) consoleId = r.id;
      const ls = sh(repoDir, 'git', ['ls-remote', '--heads', 'origin', `repair/*-${f.fingerprint.replace(/[^a-z0-9]+/gi, '-').toLowerCase().slice(0, 30)}`]); return ls.out.trim().length > 0; },
    publish: async ({ finding, branch, commit, needsFounder, rationale, sensitive }) => {
      sh(repoDir, 'git', ['push', '-q', 'origin', branch]);
      const body = `Automated, model-assisted repair for **${finding.title}**.\n\n- Model rationale: ${rationale}\n- Tests failed before, pass after; independently verified on a fresh checkout of ${commit.slice(0, 7)} by the QA step.\n- Never auto-merged. ${needsFounder ? '**Touches protected paths (' + sensitive.join(', ') + '): founder approval required.**' : 'Review the diff before merging.'}`;
      const r = sh(repoDir, 'gh', ['pr', 'create', '--draft', '--base', base, '--head', branch, '--title', `repair: ${finding.title}`, '--body', body, ...(needsFounder ? ['--label', 'founder-approval'] : [])]);
      console.log(r.out.trim());
    },
  };
  const res = await runCycle({ repoDir, base, testFiles, complete, report, hooks });
  console.log(JSON.stringify({ state: res.state, note: res.note, branch: res.branch, reasons: res.reasons, stage: res.stage }));
  if (res.state !== 'green') await post('/v1/agent/report', { agent: 'repair', status: res.state.startsWith('proposed') ? 'fixed' : 'warn', title: 'unattended repair cycle: ' + res.state, ref: 'ai-repair-' + Date.now(), details: { state: res.state, branch: res.branch, stage: res.stage, reasons: res.reasons } });
}
if (process.argv[1] && process.argv[1].endsWith('unattended.mjs')) main().catch((e) => { console.error('repair cycle error:', e.message); process.exit(1); });
