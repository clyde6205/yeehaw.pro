import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { scanApi, scanSecrets, parseAudit, scanAndroidManifest } from './scan.mjs';
const require = createRequire(import.meta.url);
const { makePool, migrate } = require('../../console/backend/db');
const { createApp } = require('../../console/backend/server');
const { generateKeyPem } = require('../../console/backend/lib/sign');
const DB = process.env.TEST_DATABASE_URL || 'postgres://yh:yhpass@localhost:5432/yeehaw_test';
let pool, server, base;
before(async () => {
  pool = makePool(DB); await migrate(pool);
  server = createApp({ pool, adminToken: 'a'.repeat(40), signingKeyPem: generateKeyPem(), rateLimits: true, agentToken: 'b'.repeat(40) }).listen(0);
  base = 'http://127.0.0.1:' + server.address().port;
});
after(async () => { server.close(); await pool.end(); });

test('scanApi: the real app passes every authentication, exposure, header, error and limit check', async () => {
  const r = await scanApi(base, { burst: 45 });
  const failed = r.checks.filter((c) => !c.ok);
  assert.deepEqual(failed, [], 'failed checks: ' + JSON.stringify(failed));
  assert.ok(r.total >= 30, 'ran ' + r.total + ' checks');
});
test('scanApi: detects a deliberately insecure API (regression for the scanner itself)', async () => {
  const http = await import('node:http');
  const bad = http.createServer((q, s) => {
    if (q.url.startsWith('/v1/admin')) { s.writeHead(200, { 'content-type': 'application/json' }); return s.end('{}'); }
    if (q.url === '/.env') { s.writeHead(200); return s.end('DATABASE_URL=postgres://u:p@h/db'); }
    if (q.url === '/healthz') { s.writeHead(200, { 'x-powered-by': 'Express', 'access-control-allow-origin': '*' }); return s.end('{"ok":true}'); }
    s.writeHead(500); s.end('Error at Object.<anonymous> (/app/node_modules/x.js:1:1)');
  }).listen(0);
  try {
    const r = await scanApi('http://127.0.0.1:' + bad.address().port);
    const fp = r.findings.map((f) => f.fingerprint);
    assert.ok(fp.some((x) => x.startsWith('security:auth:/v1/admin/agents')), 'open admin route flagged');
    assert.ok(fp.includes('security:exposure:/.env'));
    assert.ok(fp.includes('security:headers:powered-by') && fp.includes('security:cors:wildcard') && fp.includes('security:headers:nosniff'));
    assert.ok(fp.includes('security:errors:stack-or-5xx'));
    assert.equal(r.findings.find((f) => f.fingerprint.startsWith('security:auth:')).severity, 'critical');
  } finally { bad.close(); }
});
test('scanSecrets: finds real-looking credentials, ignores placeholders, never copies the value', () => {
  const fake = 'ghp_' + 'A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8';
  const r = scanSecrets([{ path: 'a.js', content: `const t = "${fake}";\nconst db = "postgres://yh:yhpass@localhost/x";` }, { path: 'b.md', content: 'DATABASE_URL=postgres://user:pass@host/db' }]);
  assert.equal(r.hits.length, 1); assert.equal(r.hits[0].kind, 'GitHub token'); assert.equal(r.hits[0].line, 1);
  assert.ok(!JSON.stringify(r.findings).includes(fake));
  assert.equal(scanSecrets([{ path: '.env.example', content: 'K="-----BEGIN PRIVATE KEY-----\\n...\\n-----END PRIVATE KEY-----"' }]).hits.length, 0, 'documented placeholder is not a secret');
  assert.equal(scanSecrets([{ path: 'k.pem', content: '-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0B' }]).hits.length, 1);
  assert.equal(scanSecrets([{ path: 'c.js', content: 'nothing here' }]).findings.length, 0);
});
test('parseAudit turns npm audit output into severity-ranked findings', () => {
  const a = parseAudit({ metadata: { vulnerabilities: { critical: 1, high: 2, moderate: 3 } }, vulnerabilities: { lodash: { severity: 'high', fixAvailable: true }, foo: { severity: 'low' } } });
  assert.equal(a.findings[0].severity, 'critical'); assert.deepEqual(a.findings[0].evidence.packages, [{ name: 'lodash', severity: 'high', fixAvailable: true }]);
  assert.equal(parseAudit({ metadata: { vulnerabilities: { moderate: 2 } } }).findings[0].severity, 'low');
  assert.equal(parseAudit({ metadata: { vulnerabilities: {} } }).findings.length, 0);
});
test('scanAndroidManifest flags the real manifest honestly (cleartext) and a bad one thoroughly', () => {
  const real = scanAndroidManifest(readFileSync(new URL('../../app/src/main/AndroidManifest.xml', import.meta.url), 'utf8'));
  assert.deepEqual(real.map((f) => f.fingerprint), ['security:android:cleartext']);
  const bad = scanAndroidManifest('<application android:allowBackup="true" android:debuggable="true"><service android:name=".S" android:exported="true"/></application>').map((f) => f.fingerprint);
  assert.deepEqual(bad.sort(), ['security:android:backup', 'security:android:debuggable', 'security:android:exported']);
});
