'use strict';
// Agent "site-health": are the public site and the API up right now? Read-only.
const { report } = require('./lib.cjs');
const checks = [
  ['https://yeehaw.pro/', (r) => r.ok],
  ['https://yeehaw.pro/privacy/', (r) => r.ok],
  ['https://yeehaw.pro/support/', (r) => r.ok],
  ['https://api.yeehaw.pro/healthz', async (r) => r.ok && (await r.json()).ok === true],
];
(async () => {
  const bad = [];
  for (const [url, ok] of checks) {
    try { const r = await fetch(url, { redirect: 'follow', signal: AbortSignal.timeout(15000) }); if (!(await ok(r))) bad.push(`${url} -> HTTP ${r.status}`); }
    catch (e) { bad.push(`${url} -> ${e.message}`); }
  }
  if (bad.length) await report('site-health', 'fail', `${bad.length} of ${checks.length} endpoints failing`, { bad });
  else await report('site-health', 'ok', `${checks.length} of ${checks.length} endpoints healthy`);
})();
