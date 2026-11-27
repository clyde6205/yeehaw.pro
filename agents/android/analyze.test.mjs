import { test } from 'node:test';
import assert from 'node:assert/strict';
import { analyzeProject, analyzeRepo, LIMITATION } from './analyze.mjs';
const root = new URL('../../', import.meta.url).pathname;
test('real project facts are read correctly', () => {
  const r = analyzeRepo(root);
  assert.equal(r.facts.minSdk, 23); assert.equal(r.facts.r8, true); assert.equal(r.facts.resourceShrink, true);
  assert.equal(r.facts.handlesTrimMemory, true); assert.equal(r.facts.workManagerOrAlarms, false); assert.equal(r.facts.backgroundComponents, 0);
  assert.equal(r.facts.webViewOffscreenPreRaster, true); assert.equal(r.facts.mixedContentBlocked, true); assert.equal(r.facts.safeBrowsing, true);
  assert.ok(r.facts.dependencies.length <= 6);
});
test('real project: the watchdog no longer polls in the background; the only finding is the accepted cleartext residual', () => {
  const fp = analyzeRepo(root).findings.map((f) => f.fingerprint);
  assert.deepEqual(fp, ['security:android:cleartext-residual']);
  assert.ok(fp.includes('security:android:cleartext-residual'));
});
test('rules fire on a deliberately bad project and stay quiet on a good one', () => {
  const bad = analyzeProject({ manifest: '<application android:largeHeap="true"><service android:name=".S"/></application>', gradle: 'implementation("a:b:1")\n'.repeat(8), files: [{ path: 'X.kt', content: 'WorkManager.getInstance()' }] });
  const fp = bad.findings.map((f) => f.fingerprint);
  for (const x of ['android:shrink', 'android:largeheap', 'android:trim-memory', 'android:background-work', 'android:background-components', 'android:dependencies', 'android:data-saver']) assert.ok(fp.includes(x), x);
  const good = analyzeProject({ manifest: '<application/>', gradle: 'isMinifyEnabled = true\nisShrinkResources = true\n', files: [{ path: 'Y.kt', content: 'override fun onTrimMemory(l:Int){}\nval h = "Save-Data"' }] });
  assert.deepEqual(good.findings.map((f) => f.fingerprint), []);
});
test('a loop that gates on the foreground flag before sleeping is not flagged', () => {
  const gated = 'class A {\n    fun f() {\n        val t = Thread({\n            while (true) {\n                if (!resumed) { Thread.sleep(30_000L); continue }\n                Thread.sleep(2_000L)\n            }\n        })\n    }\n}\n';
  const r = analyzeProject({ manifest: '<application/>', gradle: 'isMinifyEnabled = true\nisShrinkResources = true', files: [{ path: 'G.kt', content: 'onTrimMemory Save-Data\n' + gated }] });
  assert.equal(r.findings.some((f) => f.fingerprint.startsWith('android:background-polling')), false);
});
test('the limitation statement is explicit that nothing was verified on a device', () => assert.match(LIMITATION, /NOT verified/));
