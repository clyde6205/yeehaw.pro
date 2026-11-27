'use strict';
// Renders the real founder console (headless Chromium) against the real API and a seeded database. LOCAL evidence.
const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { makePool, migrate } = require('../db');
const { createApp } = require('../server');
const { generateKeyPem } = require('../lib/sign');
const { runOnce } = require('../agents/orchestrator');
let chromium; try { ({ chromium } = require('playwright')); } catch { chromium = null; }
const DB = process.env.TEST_DATABASE_URL || 'postgres://yh:yhpass@localhost:5432/yeehaw_test';
const ADMIN = crypto.randomBytes(32).toString('base64url'), AGENT = crypto.randomBytes(32).toString('base64url');
let pool, server, base;
before(async () => {
  pool = makePool(DB); await pool.query('DROP SCHEMA public CASCADE; CREATE SCHEMA public;'); await migrate(pool);
  server = createApp({ pool, adminToken: ADMIN, signingKeyPem: generateKeyPem(), rateLimits: false, agentToken: AGENT }).listen(0);
  base = 'http://127.0.0.1:' + server.address().port;
  for (let i = 0; i < 4; i++) { await pool.query("UPDATE agent_lease SET until = 'epoch'"); await runOnce(pool, {}); }
  await pool.query(`INSERT INTO agent_reports (agent,status,title,details) VALUES ('perf-bench','ok','POST /v1/telemetry/boot','{"before":"4 DB round trips, p50 2.79 ms","after":"1 DB round trip, p50 2.28 ms","evidence_class":"local"}')`);
  await pool.query(`INSERT INTO agent_findings (fingerprint, agent, severity, title, risk) VALUES ('perf:ui-demo','performance','medium','demo finding for the console render check','medium')`);
});
after(async () => { server.close(); await pool.end(); });

test('Agents tab shows all eight agents with real status, trend charts, and no script errors, at phone width', { skip: !chromium }, async () => {
  const b = await chromium.launch(); const ctx = await b.newContext({ viewport: { width: 360, height: 740 } }); const page = await ctx.newPage();
  const errors = []; page.on('pageerror', (e) => errors.push(e.message)); page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  await page.addInitScript((t) => sessionStorage.setItem('yh.tok', t), ADMIN);
  await page.goto(base + '/admin/'); await page.waitForSelector('nav button[data-t="agents"]');
  await page.click('nav button[data-t="agents"]'); await page.waitForSelector('text=The eight agents');
  const rows = await page.$$eval('div.card:has(h2:text("The eight agents")) tbody tr', (r) => r.map((x) => x.innerText.replace(/\s+/g, ' ').slice(0, 80)));
  assert.equal(rows.length, 8, rows.join(' | '));
  assert.ok(await page.$('text=Performance and health trends'));
  assert.ok(await page.$('text=1 DB round trip, p50 2.28 ms'), 'benchmark before/after shown');
  assert.ok(await page.$$eval('svg[aria-label="trend"]', (s) => s.length) >= 1 || await page.$('text=No samples recorded yet.'), 'chart or an honest empty message');
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
  assert.deepEqual(errors, []); 
  console.log('rows:', rows.join(' || ')); console.log('horizontal overflow at 360px:', overflow);
  await b.close();
});
