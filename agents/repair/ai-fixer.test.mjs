import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { runRepair, verifyRepair } from './index.mjs';
import { makeAiFixer, classifyPaths, buildPrompt, parseProposal, anthropicComplete } from './ai-fixer.mjs';

const g = (cwd, ...a) => execFileSync('git', a, { cwd, encoding: 'utf8' }).trim();
function repo() {
  const d = mkdtempSync(join(tmpdir(), 'yh-ai-')); g(d, 'init', '-q', '-b', 'main'); g(d, 'config', 'user.email', 't@t'); g(d, 'config', 'user.name', 't');
  mkdirSync(join(d, 'test')); writeFileSync(join(d, 'add.js'), 'module.exports = (a, b) => a - b;\n');
  writeFileSync(join(d, 'test', 'add.test.js'), "const t=require('node:test'),a=require('node:assert');t('adds',()=>a.equal(require('../add')(2,3),5));\n");
  g(d, 'add', '-A'); g(d, 'commit', '-q', '-m', 'init'); return d;
}
const rec = () => { const l = []; const f = async (x) => { l.push(x); }; f.log = l; return f; };
const finding = { id: 5, fingerprint: 'code:add', title: 'add() returns the difference' };
const model = (obj) => async (prompt) => { model.lastPrompt = prompt; return 'Here you go:\n' + JSON.stringify(obj); };

test('policy: forbidden paths refused, sensitive paths need founder approval, ordinary source does not', () => {
  assert.deepEqual(classifyPaths(['.env', 'a/../../x', 'package-lock.json', 'app/release.jks']).forbidden.length, 4);
  const s = classifyPaths(['.github/workflows/a.yml', 'console/backend/migrations/001.sql', 'vercel.json', 'app/build.gradle.kts']); assert.equal(s.needsFounderApproval, true); assert.equal(s.sensitive.length, 4);
  assert.equal(classifyPaths(['agents/watch/index.js', 'add.js']).needsFounderApproval, false);
});
test('the prompt contains the real source with line numbers and the finding, and the proposal parser rejects junk', () => {
  const p = buildPrompt(finding, [{ path: 'add.js', text: 'a\nb' }], 'fail: 1'); assert.match(p, /=== add\.js ===/); assert.match(p, /\s2\| b/); assert.match(p, /add\(\) returns the difference/);
  assert.throws(() => parseProposal('no json')); assert.throws(() => parseProposal('{"edits":[{"path":1}]}'));
});
test('model proposes an edit -> isolated worktree -> tests pass -> independent QA verifies; main untouched', async () => {
  const d = repo(), main = g(d, 'rev-parse', 'main'), r = rec();
  const fixer = makeAiFixer({ complete: model({ rationale: 'operator typo', edits: [{ path: 'add.js', old: 'a - b', new: 'a + b' }] }), files: ['add.js'], finding });
  const out = await runRepair({ repoDir: d, base: 'main', finding, fixer, testCmd: 'node --test', report: r });
  assert.equal(out.ok, true); assert.match(model.lastPrompt, /a - b/); assert.equal(fixer.needsFounderApproval, false); assert.equal(fixer.proposal.rationale, 'operator typo');
  assert.equal(g(d, 'rev-parse', 'main'), main);
  const q = rec(); const v = await verifyRepair({ repoDir: d, base: 'main', finding: { id: 5 }, commit: out.commit, testCmd: 'node --test', report: q }); assert.equal(v.ok, true);
});
test('a wrong model proposal is rejected by the tests and never reported as prepared', async () => {
  const d = repo(), r = rec();
  const out = await runRepair({ repoDir: d, base: 'main', finding, testCmd: 'node --test', report: r,
    fixer: makeAiFixer({ complete: model({ rationale: 'guess', edits: [{ path: 'add.js', old: 'a - b', new: 'a * b' }] }), files: ['add.js'], finding }) });
  assert.equal(out.ok, false); assert.equal(out.stage, 'tests'); assert.ok(!r.log.some((x) => x.action === 'fix prepared'));
});
test('edits that do not match exactly once, touch forbidden files, or are too large are refused before anything is written', async () => {
  const d = repo();
  for (const [edits, re] of [[[{ path: 'add.js', old: 'nope', new: 'x' }], /matches 0 times/], [[{ path: '.env', old: 'a', new: 'b' }], /forbidden/], [[], /nothing to change/],
    [[{ path: 'add.js', old: 'a - b', new: Array(80).fill('x').join('\n') }], /limit/]]) {
    const r = rec(); const out = await runRepair({ repoDir: d, base: 'main', finding, testCmd: 'node --test', report: r, fixer: makeAiFixer({ complete: model({ rationale: '', edits }), files: ['add.js'], finding }) });
    assert.equal(out.ok, false); assert.match(JSON.stringify(r.log[0]), re);
  }
});
test('sensitive target is flagged for founder approval; adapter sends the prompt and returns the text', async () => {
  const d = repo(); mkdirSync(join(d, '.github')); writeFileSync(join(d, '.github', 'ci.yml'), 'a: 1\n'); g(d, 'add', '-A'); g(d, 'commit', '-q', '-m', 'ci');
  const fixer = makeAiFixer({ complete: model({ rationale: 'x', edits: [{ path: '.github/ci.yml', old: 'a: 1', new: 'a: 2' }] }), files: ['.github/ci.yml'], finding });
  await runRepair({ repoDir: d, base: 'main', finding, testCmd: 'node --test', report: rec(), fixer }); assert.equal(fixer.needsFounderApproval, true);
  let seen; const c = anthropicComplete({ apiKey: 'k', fetchFn: async (u, o) => { seen = { u, h: o.headers['x-api-key'], b: JSON.parse(o.body) }; return { ok: true, json: async () => ({ content: [{ text: 'hi' }] }) }; } });
  assert.equal(await c('p'), 'hi'); assert.equal(seen.b.messages[0].content, 'p'); assert.match(seen.u, /anthropic\.com/);
});
