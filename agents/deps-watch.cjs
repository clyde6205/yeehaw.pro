'use strict';
// Agent "deps-watch": known-vulnerability and outdated-version check for the console backend (npm audit) and the three
// Android libraries. Read-only: it reports; a human or the fixer agent opens a PR.
const { execSync } = require('child_process');
const { report } = require('./lib.cjs');
const LIBS = [['com.google.zxing', 'core', '3.5.3'], ['com.android.billingclient', 'billing-ktx', '7.1.1']];
(async () => {
  const notes = []; let worst = 'ok';
  try {
    const out = execSync('npm audit --json --omit=dev', { cwd: 'console/backend', encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
    const v = JSON.parse(out).metadata.vulnerabilities; if (v.high + v.critical) { worst = 'fail'; notes.push(`npm audit: ${v.critical} critical, ${v.high} high`); }
  } catch (e) {
    try { const v = JSON.parse(e.stdout).metadata.vulnerabilities; if (v.high + v.critical) { worst = 'fail'; notes.push(`npm audit: ${v.critical} critical, ${v.high} high`); } else if (v.moderate) { worst = worst === 'ok' ? 'warn' : worst; notes.push(`npm audit: ${v.moderate} moderate`); } }
    catch { worst = 'warn'; notes.push('npm audit could not run'); }
  }
  for (const [g, a, cur] of LIBS) {
    try {
      const x = await (await fetch(`https://repo1.maven.org/maven2/${g.replace(/\./g, '/')}/${a}/maven-metadata.xml`, { signal: AbortSignal.timeout(15000) })).text();
      const latest = (x.match(/<release>([^<]+)<\/release>/) || [])[1];
      if (latest && latest !== cur) { if (worst === 'ok') worst = 'warn'; notes.push(`${a}: ${cur} -> ${latest} available`); }
    } catch (e) { if (worst === 'ok') worst = 'warn'; notes.push(`${a}: version check failed (${e.message})`); }
  }
  await report('deps-watch', worst, notes.length ? notes.join('; ') : 'no known high vulnerabilities; libraries current', { notes });
})();
