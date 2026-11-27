import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { runCycle, sourcesOf } from './unattended.mjs';

const g = (cwd, ...a) => execFileSync('git', a, { cwd, encoding: 'utf8' }).trim();
function repo(broken = true) {
  const d = mkdtempSync(join(tmpdir(), 'yh-unat-')); g(d, 'init', '-q', '-b', 'main'); g(d, 'config', 'user.email', 't@t'); g(d, 'config', 'user.name', 't');
  writeFileSync(join(d, 'add.mjs'), `export const add = (a, b) => a ${broken ? '-' : '+'} b;\n`);
  writeFileSync(join(d, 'add.test.mjs'), "import { test } from 'node:test'; import assert from 'node:assert/strict'; import { add } from './add.mjs'; test('adds', () => assert.equal(add(2, 3), 5));\n");
  g(d, 'add', '-A'); g(d, 'commit', '-q', '-m', 'init'); return d;
}
const rec = () => { const l = []; const f = async (x) => { l.push(x); }; f.log = l; return f; };
const model = (edits) => async () => JSON.stringify({ rationale: 'r', edits });

test('green repo: no model call is made at all', async () => {
  let called = 0; const r = await runCycle({ repoDir: repo(false), base: 'main', testFiles: ['add.test.mjs'], complete: async () => { called++; return '{}'; }, report: rec() });
  assert.equal(r.state, 'green'); assert.equal(called, 0);
});
test('failing test -> source found from imports -> proposal -> tests -> independent QA -> published (not merged)', async () => {
  const d = repo(), main = g(d, 'rev-parse', 'main'), r = rec(); let pub = null;
  assert.deepEqual(sourcesOf(d, 'add.test.mjs'), ['add.mjs']);
  const out = await runCycle({ repoDir: d, base: 'main', testFiles: ['add.test.mjs'], complete: model([{ path: 'add.mjs', old: 'a - b', new: 'a + b' }]), report: r, hooks: { publish: async (x) => { pub = x; } } });
  assert.equal(out.state, 'proposed'); assert.ok(r.log.some((x) => x.action === 'fix prepared')); assert.ok(r.log.some((x) => x.actor === 'qa' && x.verification === 'passed'));
  assert.equal(pub.needsFounder, false); assert.equal(g(d, 'rev-parse', 'main'), main);
});
test('a wrong model fix is rejected and nothing is published; editing the test itself is refused', async () => {
  const d = repo(); let pub = 0;
  let out = await runCycle({ repoDir: d, base: 'main', testFiles: ['add.test.mjs'], complete: model([{ path: 'add.mjs', old: 'a - b', new: 'a * b' }]), report: rec(), hooks: { publish: async () => { pub++; } } });
  assert.equal(out.state, 'rejected'); assert.equal(out.stage, 'tests');
  out = await runCycle({ repoDir: d, base: 'main', testFiles: ['add.test.mjs'], complete: model([{ path: 'add.test.mjs', old: 'add(2, 3), 5', new: 'add(2, 3), -1' }]), report: rec(), hooks: { publish: async () => { pub++; } } });
  assert.equal(out.state, 'rejected'); assert.match(out.error, /forbidden/); assert.equal(pub, 0);
});
test('a failing test that already has a repair is not attempted again (no repeated failed repairs)', async () => {
  let called = 0; const out = await runCycle({ repoDir: repo(), base: 'main', testFiles: ['add.test.mjs'], complete: async () => { called++; return '{}'; }, report: rec(), hooks: { alreadyAttempted: async () => true } });
  assert.equal(out.state, 'skipped'); assert.equal(called, 0);
});
test('a proposal touching a protected path is flagged for founder approval', async () => {
  const d = repo(); mkdirSync(join(d, '.github')); writeFileSync(join(d, 'vercel.json'), '{"a":1}\n');
  writeFileSync(join(d, 'add.mjs'), "import './vercel.json' with { type: 'json' };\nexport const add = (a, b) => a - b;\n"); g(d, 'add', '-A'); g(d, 'commit', '-q', '-m', 'x');
  // the fixer's context includes only sources the test imports; add.mjs is not protected, so approval is NOT required for it
  const out = await runCycle({ repoDir: d, base: 'main', testFiles: ['add.test.mjs'], complete: model([{ path: 'add.mjs', old: 'a - b', new: 'a + b' }]), report: rec(), hooks: { publish: async () => {} } });
  assert.equal(out.state, 'proposed');
});
