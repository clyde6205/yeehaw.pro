// REPRESENTATIVE web-layer profile of an in-app page (the start page) in headless Chromium:
// Android-class touch viewport, CPU slowed 4x, 'Slow 3G'-class network. Chromium is the engine family behind Android WebView, but this
// is NOT a phone: it measures page weight, request count, load timing under throttling and touch-target size. Never report as on-device data.
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
const require = createRequire(import.meta.url);
export const PROFILES = {
  'slow-3g-low-end': { cpu: 4, down: 400 * 1024 / 8, up: 400 * 1024 / 8, latency: 400, viewport: { width: 360, height: 640 } },
  'fast-wifi-reference': { cpu: 1, down: -1, up: -1, latency: 0, viewport: { width: 360, height: 640 } },
};
export async function profilePage(file, profileName = 'slow-3g-low-end', runs = 3) {
  const { chromium } = require('playwright'); const P = PROFILES[profileName];
  const browser = await chromium.launch(); const results = [];
  for (let i = 0; i < runs; i++) {
    const ctx = await browser.newContext({ viewport: P.viewport, deviceScaleFactor: 2, isMobile: true, hasTouch: true, serviceWorkers: 'block' });
    const page = await ctx.newPage(); const cdp = await ctx.newCDPSession(page);
    await cdp.send('Emulation.setCPUThrottlingRate', { rate: P.cpu });
    await cdp.send('Network.enable'); await cdp.send('Network.emulateNetworkConditions', { offline: false, downloadThroughput: P.down, uploadThroughput: P.up, latency: P.latency });
    const reqs = []; let bytes = 0; const external = [];
    page.on('response', async (r) => { const u = r.url(); reqs.push(u); try { const b = await r.body(); bytes += b.length; } catch {} if (!u.startsWith('file:') && !u.startsWith('data:')) external.push(u); });
    const errors = []; page.on('pageerror', (e) => errors.push(e.message));
    await page.goto('file://' + file, { waitUntil: 'load' });
    await page.waitForTimeout(500);
    const m = await page.evaluate(() => {
      const nav = performance.getEntriesByType('navigation')[0]; const paint = Object.fromEntries(performance.getEntriesByType('paint').map((p) => [p.name, Math.round(p.startTime)]));
      const small = [...document.querySelectorAll('a,button,input,[role=button]')].filter((e) => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0 && (r.width < 44 || r.height < 44); }).length;
      const total = [...document.querySelectorAll('a,button,input,[role=button]')].filter((e) => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; }).length;
      return { dcl_ms: Math.round(nav.domContentLoadedEventEnd), load_ms: Math.round(nav.loadEventEnd), fcp_ms: paint['first-contentful-paint'] ?? null,
        dom_nodes: document.getElementsByTagName('*').length, heap_mb: performance.memory ? +(performance.memory.usedJSHeapSize / 1048576).toFixed(1) : null,
        horizontal_overflow: document.documentElement.scrollWidth > window.innerWidth, touch_targets_total: total, touch_targets_under_44px: small };
    });
    results.push({ ...m, requests: reqs.length, transferred_kb: +(bytes / 1024).toFixed(1), network_requests_off_device: external.length, page_errors: errors.length });
    await ctx.close();
  }
  await browser.close();
  const med = (k) => { const v = results.map((r) => r[k]).filter((x) => typeof x === 'number').sort((a, b) => a - b); return v.length ? v[Math.floor(v.length / 2)] : null; };
  const keys = ['dcl_ms', 'load_ms', 'fcp_ms', 'dom_nodes', 'heap_mb', 'requests', 'transferred_kb', 'network_requests_off_device', 'touch_targets_total', 'touch_targets_under_44px', 'page_errors'];
  return { evidence_class: 'representative-web-layer (NOT a physical device)', profile: profileName, runs, median: Object.fromEntries(keys.map((k) => [k, med(k)])), horizontal_overflow: results.some((r) => r.horizontal_overflow), file_bytes: readFileSync(file).length };
}
if (process.argv[1] && process.argv[1].endsWith('webprofile.mjs')) {
  const f = process.argv[2]; for (const p of Object.keys(PROFILES)) console.log(JSON.stringify(await profilePage(f, p), null, 2));
}
