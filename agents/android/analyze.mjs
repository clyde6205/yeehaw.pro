// Android Compatibility agent. Two kinds of evidence, never mixed up in a report:
//   STATIC  : analysis of the real Android project sources (manifest, Gradle, Kotlin). Proves what the code does or does not do.
//   REPRESENTATIVE : the start page measured in headless Chromium with a slow-network, slow-CPU, small-touch-screen profile (see webprofile.mjs).
// Neither is a measurement on a physical Android phone. Nothing here may be reported as "verified on device".
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { scanAndroidManifest } from '../security/scan.mjs';

const walk = (d, out = []) => { for (const n of readdirSync(d)) { const p = join(d, n); statSync(p).isDirectory() ? walk(p, out) : out.push(p); } return out; };
const F = (fp, severity, title, evidence, root_cause, proposal, risk = 'low') => ({ fingerprint: 'android:' + fp, agent: 'android-compat', severity, title, evidence, root_cause, proposal, risk });

/** Pure: facts + findings from source texts. `files` = [{path, content}]. */
export function analyzeProject({ manifest, gradle, files }) {
  const facts = {}, findings = [];
  const num = (re, s) => { const m = s.match(re); return m ? Number(m[1]) : null; };
  facts.minSdk = num(/minSdk\s*=\s*(\d+)/, gradle); facts.targetSdk = num(/targetSdk\s*=\s*(\d+)/, gradle);
  facts.r8 = /isMinifyEnabled\s*=\s*true/.test(gradle); facts.resourceShrink = /isShrinkResources\s*=\s*true/.test(gradle);
  facts.dependencies = [...gradle.matchAll(/^\s*implementation\("([^"]+)"\)/gm)].map((m) => m[1]);
  facts.permissions = [...manifest.matchAll(/<uses-permission android:name="([^"]+)"/g)].map((m) => m[1].replace('android.permission.', ''));
  facts.backgroundComponents = [...manifest.matchAll(/<(service|receiver)\b/g)].length;
  facts.largeHeap = /largeHeap\s*=\s*"true"/.test(manifest);
  facts.exportedActivities = [...manifest.matchAll(/<activity\b[^>]*>/g)].filter((m) => /exported="true"/.test(m[0])).length;
  const kt = files.filter((f) => f.path.endsWith('.kt'));
  const all = kt.map((f) => f.content).join('\n');
  facts.kotlinFiles = kt.length; facts.kotlinLines = all.split('\n').length;
  facts.handlesTrimMemory = /onTrimMemory/.test(all); facts.handlesLowMemory = /onLowMemory|TRIM_MEMORY/.test(all);
  facts.workManagerOrAlarms = /WorkManager|JobScheduler|setRepeating|setInexactRepeating|setExactAndAllowWhileIdle|startForegroundService|ForegroundService/.test(all); // one-shot AlarmManager.set (crash restart) is not periodic work
  facts.webViewOffscreenPreRaster = /offscreenPreRaster\s*=\s*false/.test(all);
  facts.safeBrowsing = /safeBrowsingEnabled\s*=\s*true/.test(all);
  facts.mixedContentBlocked = /MIXED_CONTENT_NEVER_ALLOW/.test(all);
  facts.fileAccessDisabled = /allowFileAccess\s*=\s*false/.test(all);
  facts.dataSaver = /Save-Data/.test(all);
  facts.offlineCacheMode = /LOAD_CACHE_ELSE_NETWORK/.test(all);
  facts.liveTabPolicy = /fun maxLiveTabs/.test(all);

  if (!facts.r8 || !facts.resourceShrink) findings.push(F('shrink', 'medium', 'Release build does not shrink code/resources', { r8: facts.r8, resourceShrink: facts.resourceShrink }, 'Larger APK and more memory-mapped code on low-RAM devices.', 'Enable R8 minify and resource shrinking.'));
  if (facts.largeHeap) findings.push(F('largeheap', 'medium', 'largeHeap requested', {}, 'A browser on a 1-2 GB phone should not ask for a bigger heap.', 'Remove android:largeHeap.'));
  if (!facts.handlesTrimMemory) findings.push(F('trim-memory', 'high', 'No onTrimMemory handling', {}, 'Background tabs are never released under memory pressure; the OS will kill the whole browser.', 'Discard background WebViews on TRIM_MEMORY_RUNNING_LOW and above.', 'medium'));
  if (facts.workManagerOrAlarms) findings.push(F('background-work', 'medium', 'Background scheduling APIs are used by the browser runtime', {}, 'Periodic wake-ups cost battery and RAM on low-end phones.', 'Remove them from the browser runtime; keep the engine server side.'));
  if (facts.backgroundComponents) findings.push(F('background-components', 'medium', `${facts.backgroundComponents} service/receiver component(s) declared`, {}, 'Declared background components can be started by the system.', 'Remove them unless required.'));
  if (facts.dependencies.length > 6) findings.push(F('dependencies', 'low', `${facts.dependencies.length} direct dependencies`, { dependencies: facts.dependencies }, 'Each library adds APK size and class loading at startup.', 'Review each one for necessity.'));
  if (!facts.dataSaver) findings.push(F('data-saver', 'low', 'Save-Data header is never sent', {}, 'Servers cannot serve lighter pages to a user on expensive data.', 'Send Save-Data: on when data saver is active.'));
  // Rule: a polling thread that keeps waking while the app is in the background (battery overhead).
  for (const f of kt) {
    for (const m of f.content.matchAll(/while\s*\(true\)\s*\{([\s\S]*?)\n\s{12}\}\n/g)) {
      const body = m[1]; const sleep = body.match(/Thread\.sleep\((\d[\d_]*)L?\)/);
      if (!sleep) continue; const ms = Number(sleep[1].replace(/_/g, ''));
      const before = body.slice(0, sleep.index);
      if (ms <= 5000 && !/resumed|foreground|isForeground/.test(before)) findings.push(F('background-polling:' + f.path.split('/').pop(), 'medium',
        `A background thread wakes every ${ms / 1000} s even when the browser is in the background (${f.path.split('/').pop()})`, { file: f.path, interval_ms: ms },
        'The loop only checks the foreground flag after sleeping, so it keeps waking the CPU while the app is hidden (battery cost on every phone, worst on old batteries).',
        'Gate the loop on the foreground flag: when backgrounded, wait (e.g. 30 s) and skip the main-thread post.', 'medium'));
    }
  }
  findings.push(...scanAndroidManifest(manifest));
  return { facts, findings };
}

export function analyzeRepo(root) {
  const read = (p) => readFileSync(join(root, p), 'utf8');
  const files = walk(join(root, 'app/src/main/java')).map((p) => ({ path: p.replace(root + '/', ''), content: readFileSync(p, 'utf8') }));
  return analyzeProject({ manifest: read('app/src/main/AndroidManifest.xml'), gradle: read('app/build.gradle.kts'), files });
}
export const LIMITATION = 'Static analysis and a representative web-layer profile only. No physical Android device or emulator was available; on-device cold start, memory, frame rate and battery are NOT verified.';
