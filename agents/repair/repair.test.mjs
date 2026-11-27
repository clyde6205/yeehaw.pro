import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, mkdirSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { runRepair, verifyRepair, rollbackRepair, guardBranch } from './index.mjs';

const g = (cwd, ...a) => execFileSync('git', a, { cwd, encoding: 'utf8' }).trim();
function repo() {
  const d = mkdtempSync(join(tmpdir(), 'yh-fixture-'));
  g(d, 'init', '-q', '-b', 'main'); g(d, 'config', 'user.email', 't@t'); g(d, 'config', 'user.name', 't');
  mkdirSync(join(d, 'test'));
  writeFileSync(join(d, 'add.js'), 'module.exports = (a, b) => a - b; // BUG\n');
  writeFileSync(join(d, 'test', 'add.test.js'), "const t=require('node:test'),a=require('node:assert');t('adds',()=>a.equal(require('../add')(2,3),5));\n");
  g(d, 'add', '-A'); g(d, 'commit', '-q', '-m', 'init');
  return d;
}
const CMD = 'node --test';
const rec = () => { const l = []; const f = async (x) => { l.push(x); }; f.log = l; return f; };

test('guard: only repair/* branches; main, master and the base are refused', () => {
  for (const b of ['main', 'master', 'wip/quick-tools', 'feature/x']) assert.throws(() => guardBranch(b, 'wip/quick-tools'));
  assert.doesNotThrow(() => guardBranch('repair/12-add', 'main'));
});

test('real defect: tests fail before, the repair agent fixes it, tests pass after, QA independently verifies on a fresh checkout, main is untouched', async () => {
  const d = repo(), r = rec(), mainBefore = g(d, 'rev-parse', 'main');
  const out = await runRepair({ repoDir: d, base: 'main', finding: { id: 7, fingerprint: 'code:add-bug', title: 'add() subtracts' }, testCmd: CMD, report: r,
    fixer: (wt) => writeFileSync(join(wt, 'add.js'), 'module.exports = (a, b) => a + b;\n') });
  assert.equal(out.ok, true); assert.notEqual(out.before.exit, 0); assert.equal(out.after.exit, 0); assert.equal(out.after.failed, 0);
  assert.equal(r.log[0].action, 'fix prepared'); assert.match(r.log[0].evidence.branch, /^repair\/7-/); assert.equal(r.log[0].evidence.tests.before.failed, 1);
  assert.equal(g(d, 'rev-parse', 'main'), mainBefore, 'main untouched'); assert.equal(readFileSync(join(d, 'add.js'), 'utf8').includes('a - b'), true, 'working tree untouched');
  const q = rec();
  const v = await verifyRepair({ repoDir: d, base: 'main', finding: { id: 7 }, commit: out.commit, testCmd: CMD, report: q });
  assert.equal(v.ok, true); assert.equal(q.log[0].actor, 'qa'); assert.equal(q.log[0].verification, 'passed'); assert.notEqual(q.log[0].evidence.run_id, r.log[0].evidence.run_id);
  // rollback reverts on the repair branch only
  const rb = rec();
  await rollbackRepair({ repoDir: d, base: 'main', finding: { id: 7 }, branch: out.branch, commit: out.commit, report: rb });
  assert.equal(rb.log[0].action, 'rollback done'); assert.equal(g(d, 'rev-parse', 'main'), mainBefore);
  assert.equal(g(d, 'show', `${out.branch}:add.js`).includes('a - b'), true);
});

test('a bad fix is NOT reported as prepared; an empty fix and a patch that does not apply are failures too', async () => {
  const d = repo();
  let r = rec();
  let o = await runRepair({ repoDir: d, base: 'main', finding: { id: 1, fingerprint: 'a' }, testCmd: CMD, report: r, fixer: (wt) => writeFileSync(join(wt, 'add.js'), 'module.exports = () => 0;\n') });
  assert.equal(o.ok, false); assert.equal(o.stage, 'tests'); assert.equal(r.log[0].action, 'fix failed'); assert.ok(!r.log.some((x) => x.action === 'fix prepared'));
  r = rec(); o = await runRepair({ repoDir: d, base: 'main', finding: { id: 2, fingerprint: 'b' }, testCmd: CMD, report: r, fixer: () => {} });
  assert.equal(o.stage, 'empty');
  r = rec(); o = await runRepair({ repoDir: d, base: 'main', finding: { id: 3, fingerprint: 'c' }, testCmd: CMD, report: r, fixer: { patch: 'not a patch' } });
  assert.equal(o.stage, 'apply'); assert.equal(r.log[0].action, 'fix failed');
});

test('QA rejects a "fix" that goes green by deleting the tests, and one that touches protected paths', async () => {
  const d = repo();
  const r = rec();
  const o = await runRepair({ repoDir: d, base: 'main', finding: { id: 9, fingerprint: 'cheat' }, testCmd: 'node -e "process.exit(0)"', report: r,
    fixer: (wt) => { rmSync(join(wt, 'test', 'add.test.js')); mkdirSync(join(wt, '.github')); writeFileSync(join(wt, '.github', 'x.yml'), 'a: 1\n'); } });
  assert.equal(o.ok, true, 'the agent itself cannot tell; that is why QA is separate');
  const q = rec();
  const v = await verifyRepair({ repoDir: d, base: 'main', finding: { id: 9 }, commit: o.commit, testCmd: 'node -e "process.exit(0)"', report: q });
  assert.equal(v.ok, false); assert.equal(q.log[0].verification, 'failed'); assert.match(q.log[0].result, /deletes test files/); assert.match(q.log[0].result, /protected paths/);
});

test('prepared fix carries the actual diff for the console to show', async () => {
  const d = repo(), r = rec();
  await runRepair({ repoDir: d, base: 'main', finding: { id: 5, fingerprint: 'diff' }, testCmd: CMD, report: r, fixer: (wt) => writeFileSync(join(wt, 'add.js'), 'module.exports = (a, b) => a + b;\n') });
  assert.match(r.log[0].evidence.patch, /-module\.exports = \(a, b\) => a - b;/); assert.match(r.log[0].evidence.patch, /\+module\.exports = \(a, b\) => a \+ b;/);
});
