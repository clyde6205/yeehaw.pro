// Performance agent: measures the API's hot telemetry paths on a real HTTP server and a real Postgres.
// Reports exact database round trips per request (counted at the driver), measured latency percentiles, and response bytes.
// Latency over a real network is NOT simulated: the "projected" figure is round trips x a stated RTT, labelled as arithmetic.
import { createRequire } from 'node:module';
import { randomUUID, randomBytes } from 'node:crypto';
const require = createRequire(import.meta.url);

export async function bench({ backendDir, dbUrl, n = 200, rttMs = 20 }) {
  const { makePool, migrate } = require(backendDir + '/db.js');
  const { createApp } = require(backendDir + '/server.js');
  const { generateKeyPem } = require(backendDir + '/lib/sign.js');
  const pool = makePool(dbUrl);
  let queries = 0;
  pool.on('connect', (c) => { const q = c.query.bind(c); c.query = (...x) => { queries++; return q(...x); }; }); // every statement the driver sends is counted
  await pool.query('DROP SCHEMA public CASCADE; CREATE SCHEMA public;'); await migrate(pool);
  const token = randomBytes(32).toString('base64url');
  const srv = createApp({ pool, adminToken: token, signingKeyPem: generateKeyPem(), rateLimits: false }).listen(0);
  const base = 'http://127.0.0.1:' + srv.address().port;
  const ids = Array.from({ length: 20 }, () => randomUUID());
  const boot = (id) => ({ device_id: id, app_version: '0.6.0', android_os_ver: '13 (API 33)', ram_mb: 1900, country_locale: 'PH' });
  const cases = {
    'POST /v1/telemetry/boot (new device)': () => fetch(base + '/v1/telemetry/boot', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(boot(randomUUID())) }),
    'POST /v1/telemetry/boot (repeat launch)': () => fetch(base + '/v1/telemetry/boot', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(boot(ids[Math.floor(Math.random() * ids.length)])) }),
    'GET /v1/config/app-config': () => fetch(base + '/v1/config/app-config'),
  };
  for (const id of ids) await (await fetch(base + '/v1/telemetry/boot', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(boot(id)) })).arrayBuffer();
  const pct = (a, p) => a[Math.min(a.length - 1, Math.floor(a.length * p))];
  const out = {};
  for (const [name, call] of Object.entries(cases)) {
    for (let i = 0; i < 20; i++) await (await call()).arrayBuffer(); // warm up
    queries = 0; const lat = []; let bytes = 0, status = 0;
    for (let i = 0; i < n; i++) { const t = process.hrtime.bigint(); const r = await call(); const b = await r.arrayBuffer(); lat.push(Number(process.hrtime.bigint() - t) / 1e6); bytes = b.byteLength; status = r.status; }
    lat.sort((a, b) => a - b);
    const qpr = queries / n;
    out[name] = { requests: n, status, db_round_trips_per_request: Number(qpr.toFixed(2)), p50_ms: Number(pct(lat, 0.5).toFixed(2)), p95_ms: Number(pct(lat, 0.95).toFixed(2)), response_bytes: bytes, projected_ms_at_rtt: Number((pct(lat, 0.5) + qpr * rttMs).toFixed(1)) };
  }
  srv.close(); await pool.end();
  return { measured_at: new Date().toISOString(), node: process.version, rtt_assumed_ms: rttMs, note: 'latency measured locally; projected_ms_at_rtt = measured p50 + round trips x assumed RTT (arithmetic, not a measurement)', endpoints: out };
}

export function compare(before, after) {
  return Object.keys(before.endpoints).map((k) => {
    const b = before.endpoints[k], a = after.endpoints[k];
    return { endpoint: k, round_trips: [b.db_round_trips_per_request, a.db_round_trips_per_request], p50_ms: [b.p50_ms, a.p50_ms], p95_ms: [b.p95_ms, a.p95_ms], projected_ms_at_rtt: [b.projected_ms_at_rtt, a.projected_ms_at_rtt], response_bytes: [b.response_bytes, a.response_bytes],
      improved: a.db_round_trips_per_request < b.db_round_trips_per_request || a.p50_ms < b.p50_ms * 0.9 };
  });
}
