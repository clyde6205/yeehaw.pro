// Autonomous Repair Agent + independent QA verifier.
// Safety model (enforced in code, covered by tests):
//   * work happens only in a throw-away git worktree on a NEW branch repair/<finding>-<slug>; the checked-out tree is never touched
//   * protected branches (main, master, the base branch) can never be the target of a commit or push
//   * a fix is reported as "prepared" only if the tests pass AFTER the change; otherwise it is reported as failed
//   * pushing and opening a draft PR are opt-in (push:true); nothing is merged by this agent, ever
//   * QA re-runs the tests on a fresh checkout of the exact commit, from a different run id, and also rejects diffs that
//     delete tests or touch protected paths; only QA can record a verification (the console enforces this too)
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';

const PROTECTED_PATHS = [/^\.github\//, /\.jks$/, /\.keystore$/, /(^|\/)\.env/, /(^|\/)signing/i, /^console\/backend\/vercel\.json$/];
const git = (cwd, ...a) => execFileSync('git', a, { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
const slug = (s) => String(s).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 30) || 'fix';

export function runTests(cwd, cmd, timeoutMs = 300_000) {
  const t0 = Date.now();
  const r = spawnSync('bash', ['-lc', cmd], { cwd, encoding: 'utf8', timeout: timeoutMs, env: Object.fromEntries(Object.entries({ ...process.env, CI: '1' }).filter(([k]) => k !== 'NODE_TEST_CONTEXT')) });
  const out = `${r.stdout || ''}\n${r.stderr || ''}`;
  const num = (re) => { const m = out.match(re); return m ? Number(m[1]) : null; };
  return { cmd, exit: r.status === null ? -1 : r.status, passed: num(/^# pass (\d+)/m), failed: num(/^# fail (\d+)/m), ms: Date.now() - t0, tail: out.trim().split('\n').slice(-12).join('\n').slice(0, 1500) };
}

export function guardBranch(branch, base, extraProtected = []) {
  const bad = new Set(['main', 'master', 'HEAD', base, ...extraProtected].filter(Boolean));
  if (bad.has(branch) || !/^repair\/[a-z0-9._-]+$/.test(branch)) throw new Error(`refusing to write to branch "${branch}": repair agent only writes to repair/* branches`);
}

function applyFix(dir, fixer) {
  if (typeof fixer === 'function') return fixer(dir);
  if (fixer && fixer.patch) { const f = join(dir, '.repair.patch'); writeFileSync(f, fixer.patch); try { git(dir, 'apply', '--index', f); } finally { rmSync(f, { force: true }); } return; }
  throw new Error('no fixer supplied');
}

/** Prepare a fix for one finding. report(action) records it (console API or test recorder). */
export async function runRepair({ repoDir, base, finding, fixer, testCmd, report, push = false, draftPr = false, runId = 'repair-' + randomUUID().slice(0, 8), author = 'YeeHaw Repair Agent <repair@yeehaw.pro>' }) {
  const branch = `repair/${finding.id}-${slug(finding.fingerprint || finding.title || 'fix')}`;
  guardBranch(branch, base);
  const work = mkdtempSync(join(tmpdir(), 'yh-repair-'));
  const wt = join(work, 'wt');
  let prepared = false;
  try {
    git(repoDir, 'worktree', 'add', '-q', '-b', branch, wt, base);
    const before = runTests(wt, testCmd);
    try { await applyFix(wt, fixer); } catch (e) {
      await report({ finding_id: finding.id, actor: 'repair', action: 'fix failed', result: 'could not apply the change: ' + e.message, evidence: { run_id: runId, branch, tests: { before } } });
      return { ok: false, stage: 'apply', error: e.message };
    }
    git(wt, 'add', '-A');
    if (!git(wt, 'status', '--porcelain')) { await report({ finding_id: finding.id, actor: 'repair', action: 'fix failed', result: 'the fixer produced no change', evidence: { run_id: runId, branch, tests: { before } } }); return { ok: false, stage: 'empty' }; }
    const after = runTests(wt, testCmd);
    if (after.exit !== 0) {
      await report({ finding_id: finding.id, actor: 'repair', action: 'fix failed', result: 'tests still fail after the change; nothing is proposed', evidence: { run_id: runId, branch, tests: { before, after } } });
      return { ok: false, stage: 'tests', before, after };
    }
    git(wt, '-c', `user.name=${author.split(' <')[0]}`, '-c', `user.email=${author.match(/<(.*)>/)[1]}`, 'commit', '-q', '-m', `repair: ${finding.title || finding.fingerprint}\n\nFinding #${finding.id}. Prepared by the repair agent; tests ran before and after. Not merged.`);
    const commit = git(wt, 'rev-parse', 'HEAD'), diffstat = git(wt, 'diff', '--stat', `${base}..HEAD`).slice(-800);
    let pr = null;
    if (push) { git(wt, 'push', '-q', 'origin', branch); if (draftPr) pr = execFileSync('gh', ['pr', 'create', '--draft', '--base', base, '--head', branch, '--title', `repair: ${finding.title}`, '--body', `Automated repair for finding #${finding.id}. Tests before/after attached in the console. Needs human review.`], { cwd: wt, encoding: 'utf8' }).trim(); }
    await report({ finding_id: finding.id, actor: 'repair', action: 'fix prepared', result: 'change committed on an isolated branch; awaiting independent QA and owner review', evidence: { commit, branch, run_id: runId, tests: { before, after }, diffstat, patch: git(wt, 'diff', `${base}..HEAD`).slice(0, 5000), pushed: push, pr } });
    prepared = true;
    return { ok: true, commit, branch, before, after };
  } finally { try { git(repoDir, 'worktree', 'remove', '--force', wt); } catch {} if (!prepared) { try { git(repoDir, 'branch', '-D', branch); } catch {} } rmSync(work, { recursive: true, force: true }); }
}

/** Independent QA. Fresh checkout of the exact commit, different run id. Records the verdict through report(). */
export async function verifyRepair({ repoDir, base, finding, commit, testCmd, report, runId = 'qa-' + randomUUID().slice(0, 8) }) {
  const work = mkdtempSync(join(tmpdir(), 'yh-qa-')), wt = join(work, 'wt');
  const reasons = [];
  try {
    git(repoDir, 'worktree', 'add', '-q', '--detach', wt, commit);
    const names = git(wt, 'diff', '--name-status', `${base}...${commit}`).split('\n').filter(Boolean).map((l) => l.split('\t'));
    const touched = names.map((n) => n[n.length - 1]);
    const protectedHit = touched.filter((f) => PROTECTED_PATHS.some((re) => re.test(f)));
    if (protectedHit.length) reasons.push('touches protected paths: ' + protectedHit.join(', '));
    const removedTests = names.filter((n) => n[0] === 'D' && /(^|\/)(test|tests|__tests__)\/|\.(test|spec)\./.test(n[1])).map((n) => n[1]);
    if (removedTests.length) reasons.push('deletes test files: ' + removedTests.join(', '));
    const tests = runTests(wt, testCmd);
    if (tests.exit !== 0) reasons.push(`tests fail on a fresh checkout of ${commit.slice(0, 7)} (exit ${tests.exit})`);
    const verification = reasons.length ? 'failed' : 'passed';
    await report({ finding_id: finding.id, actor: 'qa', action: 'independent verification', result: reasons.length ? 'FAILED: ' + reasons.join('; ') : 'passed: fresh checkout, tests green, no protected paths, no tests removed', verification, evidence: { commit, run_id: runId, tests, files: touched.slice(0, 30), reasons } });
    return { ok: !reasons.length, reasons, tests };
  } finally { try { git(repoDir, 'worktree', 'remove', '--force', wt); } catch {} rmSync(work, { recursive: true, force: true }); }
}

/** Owner-requested rollback: revert the prepared commit on its own repair/* branch. Never touches protected branches. */
export async function rollbackRepair({ repoDir, base, finding, branch, commit, report }) {
  guardBranch(branch, base);
  const work = mkdtempSync(join(tmpdir(), 'yh-rb-')), wt = join(work, 'wt');
  try {
    git(repoDir, 'worktree', 'add', '-q', wt, branch);
    git(wt, '-c', 'user.name=YeeHaw Repair Agent', '-c', 'user.email=repair@yeehaw.pro', 'revert', '--no-edit', commit);
    const rev = git(wt, 'rev-parse', 'HEAD');
    await report({ finding_id: finding.id, actor: 'repair', action: 'rollback done', result: `reverted ${commit.slice(0, 7)} on ${branch}`, evidence: { commit: rev, reverted: commit, branch } });
    return { ok: true, commit: rev };
  } finally { try { git(repoDir, 'worktree', 'remove', '--force', wt); } catch {} rmSync(work, { recursive: true, force: true }); }
}

/** Default reporter: the console's agent API. */
export function consoleReporter(base, token, fetchFn = fetch) {
  return async (body) => { const r = await fetchFn(base.replace(/\/$/, '') + '/v1/agent/action', { method: 'POST', headers: { 'content-type': 'application/json', authorization: 'Bearer ' + token }, body: JSON.stringify(body) }); if (r.status !== 201) throw new Error(`console refused the action (HTTP ${r.status})`); return r.json(); };
}
