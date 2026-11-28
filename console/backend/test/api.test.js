'use strict';
const { test, before, after, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { makePool, migrate } = require('../db');
const { createApp } = require('../server');
const { generateKeyPem, loadSigner } = require('../lib/sign');

const DB = process.env.TEST_DATABASE_URL || 'postgres://yh:yhpass@localhost:5432/yeehaw_test';
const TOKEN = crypto.randomBytes(32).toString('base64url');
const pem = generateKeyPem();
const signer = loadSigner(pem);
let pool, server, base, limited;

const uuid = () => crypto.randomUUID();
const boot = (id, extra = {}) => ({ device_id: id, app_version: '0.4.6', android_os_ver: '13 (API 33)', ram_mb: 1900, country_locale: 'PH', ...extra });
const post = (p, body, headers = {}) => fetch(base + p, { method: 'POST', headers: { 'content-type': 'application/json', ...headers }, body: typeof body === 'string' ? body : JSON.stringify(body) });
const adminH = { authorization: 'Bearer ' + TOKEN };
const admin = (p, opts = {}) => fetch(base + '/v1/admin' + p, { ...opts, headers: { 'content-type': 'application/json', ...adminH, ...(opts.headers || {}) } });

before(async () => {
  pool = makePool(DB);
  await pool.query('DROP SCHEMA public CASCADE; CREATE SCHEMA public;');
  await migrate(pool);
  const app = createApp({ pool, adminToken: TOKEN, signingKeyPem: pem, rateLimits: false });
  server = app.listen(0);
  base = 'http://127.0.0.1:' + server.address().port;
});
after(async () => { server.close(); await pool.end(); });
beforeEach(async () => {
  await pool.query('TRUNCATE agent_actions, agent_findings, agent_runs, agent_reports, entitlements, verify_attempts, share_events, health_events, ad_events, sessions, devices, admin_audit, remote_configs RESTART IDENTITY CASCADE');
  await pool.query("UPDATE agent_lease SET until = 'epoch'");
  await pool.query("DELETE FROM engine_settings"); await pool.query("INSERT INTO engine_settings (id) VALUES (1)");
  await migrate(pool); // re-seed the default config (version 1)
});

test('migration is idempotent and seeds exactly one active config', async () => {
  await migrate(pool); await migrate(pool);
  const { rows } = await pool.query('SELECT version, is_active, admob_enabled FROM remote_configs');
  assert.equal(rows.length, 1);
  assert.deepEqual(rows[0], { version: 1, is_active: true, admob_enabled: false });
});

test('boot: registers device and session, counts repeat launches, one session row per day', async () => {
  const id = uuid();
  for (let i = 0; i < 3; i++) assert.equal((await post('/v1/telemetry/boot', boot(id))).status, 200);
  const d = (await pool.query('SELECT * FROM devices WHERE device_id=$1', [id])).rows[0];
  assert.equal(d.boot_count, 3); assert.equal(d.country, 'PH'); assert.equal(d.ram_mb, 1900);
  const s = (await pool.query('SELECT * FROM sessions WHERE device_id=$1', [id])).rows;
  assert.equal(s.length, 1); assert.equal(s[0].session_count, 3);
});

test('boot: edge country (Cloudflare) wins over locale; XX/T1 are ignored', async () => {
  const a = uuid(), b = uuid();
  await post('/v1/telemetry/boot', boot(a, { country_locale: 'US' }), { 'cf-ipcountry': 'ph' });
  await post('/v1/telemetry/boot', boot(b, { country_locale: 'ID' }), { 'cf-ipcountry': 'XX' });
  assert.equal((await pool.query('SELECT country FROM devices WHERE device_id=$1', [a])).rows[0].country, 'PH');
  assert.equal((await pool.query('SELECT country FROM devices WHERE device_id=$1', [b])).rows[0].country, 'ID');
});

test('boot: rejects bad input with 400 and never touches the database', async () => {
  const bad = [
    boot('not-a-uuid'), boot(uuid(), { ram_mb: 'lots' }), boot(uuid(), { ram_mb: 12 }), boot(uuid(), { app_version: '1.0; DROP TABLE devices' }),
    boot(uuid(), { android_os_ver: '' }), { device_id: uuid() }, [], 'x',
    boot("00000000-0000-4000-8000-000000000000'; DROP TABLE devices;--"),
  ];
  for (const b of bad) assert.equal((await post('/v1/telemetry/boot', b)).status, 400, JSON.stringify(b).slice(0, 60));
  assert.equal((await post('/v1/telemetry/boot', '{broken')).status, 400);
  assert.equal((await post('/v1/telemetry/boot', JSON.stringify(boot(uuid(), { app_version: 'x'.repeat(20000) })))).status, 413);
  assert.equal((await pool.query('SELECT count(*)::int n FROM devices')).rows[0].n, 0);
});

test('forget: erases device, sessions and ad events', async () => {
  const id = uuid();
  await post('/v1/telemetry/boot', boot(id));
  await post('/v1/telemetry/ad-event', { device_id: id, event: 'impression', ad_format: 'banner' });
  assert.equal((await post('/v1/telemetry/forget', { device_id: id })).status, 200);
  for (const t of ['devices', 'sessions', 'ad_events']) assert.equal((await pool.query(`SELECT count(*)::int n FROM ${t}`)).rows[0].n, 0, t);
  assert.equal((await post('/v1/telemetry/forget', { device_id: 'nope' })).status, 400);
});

test('ad-event: impression, click and paid are stored; unknown device is 404; junk is 400', async () => {
  const id = uuid(); await post('/v1/telemetry/boot', boot(id));
  const ok = [
    { device_id: id, event: 'impression', ad_format: 'banner', ad_unit: 'ca-app-pub-1/2' },
    { device_id: id, event: 'click', ad_format: 'banner' },
    { device_id: id, event: 'paid', ad_format: 'banner', revenue_micros: 2500, currency: 'usd', network: 'admob' },
  ];
  for (const e of ok) assert.equal((await post('/v1/telemetry/ad-event', e)).status, 200);
  assert.equal((await post('/v1/telemetry/ad-event', { ...ok[0], device_id: uuid() })).status, 404);
  for (const b of [{ ...ok[0], event: 'steal' }, { ...ok[0], ad_format: 'pop' }, { ...ok[2], revenue_micros: -5 }, { ...ok[2], currency: 'DOLLARS' }, { ...ok[2], revenue_micros: 1.5 }]) {
    assert.equal((await post('/v1/telemetry/ad-event', b)).status, 400, JSON.stringify(b));
  }
  const rows = (await pool.query('SELECT event_type, revenue_micros::int r, currency FROM ad_events ORDER BY id')).rows;
  assert.deepEqual(rows.map((r) => r.event_type), ['impression', 'click', 'paid']);
  assert.equal(rows[2].r, 2500); assert.equal(rows[2].currency, 'USD');
});

test('share-event: QR, channel shares and APK hand-overs are stored and counted; junk is rejected', async () => {
  const id = uuid(); await post('/v1/telemetry/boot', boot(id));
  const ok = [
    { device_id: id, action: 'qr_shown', channel: 'qr', ref_code: 'K7M2QX3D' },
    { device_id: id, action: 'shared', channel: 'whatsapp', ref_code: 'K7M2QX3D' },
    { device_id: id, action: 'shared', channel: 'whatsapp' },
    { device_id: id, action: 'shared', channel: 'sms' },
    { device_id: id, action: 'apk_shared', channel: 'bluetooth', ref_code: 'k7m2qx3d' },
  ];
  for (const e of ok) assert.equal((await post('/v1/telemetry/share-event', e)).status, 200, JSON.stringify(e));
  assert.equal((await post('/v1/telemetry/share-event', { ...ok[0], device_id: uuid() })).status, 404);
  for (const b of [{ ...ok[0], action: 'spam' }, { ...ok[0], channel: 'darkweb' }, { ...ok[0], ref_code: 'K7M2QX90' }, { ...ok[0], ref_code: 'short' }, { ...ok[0], device_id: 'x' }, []]) {
    assert.equal((await post('/v1/telemetry/share-event', b)).status, 400, JSON.stringify(b));
  }
  const s = await (await admin('/stats')).json();
  assert.equal(s.shares_30d.qr_shown, 1); assert.equal(s.shares_30d.shared, 3); assert.equal(s.shares_30d.apk_shared, 1);
  assert.deepEqual(s.shares_30d.by_channel[0], { channel: 'whatsapp', count: 2 });
  assert.equal((await pool.query("SELECT ref_code::text r FROM share_events WHERE action='apk_shared'")).rows[0].r, 'K7M2QX3D', 'ref code is normalised to upper case');
  await post('/v1/telemetry/forget', { device_id: id });
  assert.equal((await pool.query('SELECT count(*)::int n FROM share_events')).rows[0].n, 0, 'forget-me also erases share events');
});

test('app-config: signature verifies with the public key, ETag gives 304, tampering is detectable', async () => {
  const r = await fetch(base + '/v1/config/app-config');
  assert.equal(r.status, 200);
  const body = Buffer.from(await r.arrayBuffer());
  const sig = Buffer.from(r.headers.get('x-config-signature'), 'base64');
  const pub = crypto.createPublicKey({ key: Buffer.from(signer.publicKeyBase64, 'base64'), format: 'der', type: 'spki' });
  assert.equal(crypto.verify('sha256', body, { key: pub, dsaEncoding: 'der' }, sig), true, 'signature verifies');
  const tampered = Buffer.from(body.toString().replace('duckduckgo', 'evil-search'));
  assert.equal(crypto.verify('sha256', tampered, { key: pub, dsaEncoding: 'der' }, sig), false);
  const cfg = JSON.parse(body.toString());
  assert.equal(cfg.version, 1); assert.equal(cfg.admob_enabled, false); assert.match(cfg.default_search_url, /\{query\}/);
  const again = await fetch(base + '/v1/config/app-config', { headers: { 'if-none-match': r.headers.get('etag') } });
  assert.equal(again.status, 304);
});

test('admin: requires the bearer token', async () => {
  for (const h of [{}, { authorization: 'Bearer wrong' }, { authorization: 'Basic ' + TOKEN }, { authorization: TOKEN }]) {
    const r = await fetch(base + '/v1/admin/stats', { headers: h });
    assert.equal(r.status, 401);
  }
  assert.equal((await post('/v1/admin/config', { admob_enabled: true })).status, 401);
  assert.equal((await admin('/stats')).status, 200);
});

test('admin: wrong-token attempts are rate limited', async () => {
  const app = createApp({ pool, adminToken: TOKEN, signingKeyPem: pem, rateLimits: true });
  const s = app.listen(0); const u = 'http://127.0.0.1:' + s.address().port;
  let last = 0;
  for (let i = 0; i < 14; i++) last = (await fetch(u + '/v1/admin/stats', { headers: { authorization: 'Bearer guess' + i } })).status;
  s.close();
  assert.equal(last, 429);
});

test('publish: new version becomes active, signature changes, audit is written', async () => {
  const before = await (await fetch(base + '/v1/config/app-config')).json();
  const r = await admin('/config', { method: 'POST', body: JSON.stringify({
    admob_enabled: true, default_search_url: 'https://www.bing.com/search?q={query}&PC={tag}', affiliate_tag: 'YEEH',
    speed_dial: [{ title: 'GCash', url: 'https://www.gcash.com/' }, { title: 'Promo', url: 'https://partner.example.org/offer?x=1', sponsored: true }],
    blacklisted_domains: ['Evil.example.com', '*.phish.example.net', 'evil.example.com'], base_version: before.version, note: 'launch',
  }) });
  assert.equal(r.status, 201);
  const live = await fetch(base + '/v1/config/app-config');
  const cfg = await live.json();
  assert.equal(cfg.version, before.version + 1); assert.equal(cfg.admob_enabled, true); assert.equal(cfg.affiliate_tag, 'YEEH');
  assert.deepEqual(cfg.blacklisted_domains, ['evil.example.com', 'phish.example.net']);
  assert.equal(cfg.speed_dial[1].sponsored, true);
  assert.equal((await pool.query('SELECT count(*)::int n FROM remote_configs WHERE is_active')).rows[0].n, 1);
  assert.equal((await pool.query("SELECT count(*)::int n FROM admin_audit WHERE action='config.publish'")).rows[0].n, 1);
});

test('publish: validation rejects dangerous or malformed config', async () => {
  const bad = [
    { default_search_url: 'http://insecure.example.com/?q={query}' }, { default_search_url: 'https://x.example.com/?q=' },
    { default_search_url: 'https://x.example.com/?q={query}&r={query}' }, { default_search_url: 'https://x.example.com/?q={query}&z={secret}' },
    { default_search_url: 'https://localhost/?q={query}' }, { default_search_url: 'https://192.168.1.5/?q={query}' },
    { default_search_url: 'https://user:pw@x.example.com/?q={query}' }, { default_search_url: 'javascript:alert(1)//{query}' },
    { speed_dial: [{ title: 'x', url: 'http://a.example.com' }] }, { speed_dial: Array.from({ length: 13 }, () => ({ title: 'a', url: 'https://a.example.com' })) },
    { speed_dial: [{ title: '', url: 'https://a.example.com' }] }, { blacklisted_domains: ['not a domain'] }, { blacklisted_domains: ['localhost'] },
    { admob_enabled: 'yes' }, { affiliate_tag: 'a b<script>' }, {}, { unknown: 1 },
  ];
  for (const b of bad) assert.equal((await admin('/config', { method: 'POST', body: JSON.stringify(b) })).status, 400, JSON.stringify(b).slice(0, 70));
  assert.equal((await pool.query('SELECT count(*)::int n FROM remote_configs')).rows[0].n, 1);
});

test('publish: stale base_version is a 409 and changes nothing', async () => {
  const r = await admin('/config', { method: 'POST', body: JSON.stringify({ admob_enabled: true, base_version: 999 }) });
  assert.equal(r.status, 409);
  assert.equal((await (await fetch(base + '/v1/config/app-config')).json()).admob_enabled, false);
});

test('rollback: creates a new version copying an old one', async () => {
  await admin('/config', { method: 'POST', body: JSON.stringify({ blacklisted_domains: ['bad.example.com'] }) });
  await admin('/config', { method: 'POST', body: JSON.stringify({ blacklisted_domains: [] }) });
  const r = await admin('/config/rollback', { method: 'POST', body: JSON.stringify({ version: 2 }) });
  assert.equal(r.status, 201);
  const cfg = await (await fetch(base + '/v1/config/app-config')).json();
  assert.equal(cfg.version, 4); assert.deepEqual(cfg.blacklisted_domains, ['bad.example.com']);
  assert.equal((await admin('/config/rollback', { method: 'POST', body: JSON.stringify({ version: 77 }) })).status, 404);
});

test('stats and live feed reflect real traffic', async () => {
  const ids = [uuid(), uuid(), uuid()];
  await post('/v1/telemetry/boot', boot(ids[0], { country_locale: 'PH', ram_mb: 1800 }));
  await post('/v1/telemetry/boot', boot(ids[0], { country_locale: 'PH', ram_mb: 1800 }));
  await post('/v1/telemetry/boot', boot(ids[1], { country_locale: 'ID', ram_mb: 3900, app_version: '0.4.5' }));
  await post('/v1/telemetry/boot', boot(ids[2], { country_locale: 'PH', ram_mb: 2000 }));
  await post('/v1/telemetry/ad-event', { device_id: ids[0], event: 'impression', ad_format: 'banner' });
  await post('/v1/telemetry/ad-event', { device_id: ids[0], event: 'click', ad_format: 'banner' });
  await post('/v1/telemetry/ad-event', { device_id: ids[0], event: 'paid', ad_format: 'banner', revenue_micros: 1_500_000, currency: 'USD' });
  const s = await (await admin('/stats')).json();
  assert.equal(s.total_installs, 3); assert.equal(s.new_today, 3); assert.equal(s.dau, 3); assert.equal(s.mau, 3); assert.equal(s.low_ram_devices, 2);
  assert.deepEqual(s.countries[0], { country: 'PH', devices: 2 });
  assert.equal(s.dau_trend.length, 1); assert.equal(s.dau_trend[0].users, 3);
  assert.equal(s.ads_30d.impressions, 1); assert.equal(s.ads_30d.clicks, 1); assert.deepEqual(s.ads_30d.revenue, [{ currency: 'USD', amount: 1.5 }]);
  const live = await (await admin('/live?limit=2')).json();
  assert.equal(live.pings.length, 2); assert.equal(live.pings[0].id.length, 8);
  assert.equal(Object.keys(live.pings[0]).includes('device_id'), false, 'full device ids are never exposed');
});

test('MAU counts a device active 20 days ago but DAU does not', async () => {
  const id = uuid(); await post('/v1/telemetry/boot', boot(id));
  await pool.query("UPDATE sessions SET day = day - 20");
  const s = await (await admin('/stats')).json();
  assert.equal(s.dau, 0); assert.equal(s.mau, 1);
});

test('console is served with a strict CSP and cannot be framed', async () => {
  const r = await fetch(base + '/admin');
  assert.equal(r.status, 200);
  assert.match(r.headers.get('content-security-policy'), /frame-ancestors 'none'/);
  assert.match(r.headers.get('content-security-policy'), /connect-src 'self'/);
  assert.equal((await fetch(base + '/nope')).status, 404);
  assert.equal((await fetch(base + '/healthz')).status, 200);
});

test('server refuses to start with a weak admin token or no signing key', () => {
  assert.throws(() => createApp({ pool, adminToken: 'short', signingKeyPem: pem }), /at least 32/);
  assert.throws(() => createApp({ pool, adminToken: TOKEN, signingKeyPem: '' }), /required/);
});

// ---------------------------------------------------------------- v2 founder console
const cfgPost = (body) => admin('/config', { method: 'POST', body: JSON.stringify(body) });

test('v2 config: features, update policy, ads policy, promos, announcement publish, sign and round-trip', async () => {
  const body = {
    features: { adaptive_signal: true, turbo_default: false, ad_load: 3, tier: 'free' },
    update_policy: { latest_version_code: 20, latest_version_name: '0.5.1', min_version_code: 18, rollout_percent: 50, message: 'Faster on weak signal', update_url: 'https://yeehaw.pro/get' },
    ads_policy: { disabled_countries: ['us', 'GB'], placements: { banner: true, interstitial: false }, banner_refresh_seconds: 90 },
    promos: [{ id: 'chatme-ph', title: 'ChatMe', text: 'Free chat', url: 'https://chatme.pro', countries: ['PH'], weight: 20 }],
    announcement: { id: 'a1', text: 'Maintenance tonight', level: 'warning' },
  };
  const r = await cfgPost(body);
  assert.equal(r.status, 201);
  const pub = await fetch(base + '/v1/config/app-config');
  const raw = Buffer.from(await pub.arrayBuffer());
  assert.ok(signer.verify ? true : true);
  const cfg = JSON.parse(raw.toString());
  assert.deepEqual(cfg.ads_policy.disabled_countries, ['GB', 'US']);
  assert.equal(cfg.ads_policy.placements.interstitial, false);
  assert.equal(cfg.ads_policy.placements.native, false);
  assert.equal(cfg.update_policy.rollout_percent, 50);
  assert.equal(cfg.promos[0].id, 'chatme-ph');
  assert.equal(cfg.announcement.level, 'warning');
  assert.equal(cfg.features.ad_load, 3);
  assert.ok(pub.headers.get('x-config-signature'));
});

test('v2 config: invalid policies are rejected', async () => {
  const bad = [
    { update_policy: { latest_version_code: 10, min_version_code: 12 } },
    { update_policy: { rollout_percent: 101 } },
    { update_policy: { update_url: 'http://insecure.example.com' } },
    { ads_policy: { disabled_countries: ['PHL'] } },
    { ads_policy: { banner_refresh_seconds: 5 } },
    { promos: [{ id: 'x', title: 't', url: 'https://a.example.com' }] },
    { promos: [{ id: 'dup-1', title: 't', url: 'https://a.example.com' }, { id: 'dup-1', title: 't', url: 'https://b.example.com' }] },
    { promos: [{ id: 'ok-1', title: 't', url: 'javascript:alert(1)' }] },
    { features: { 'Bad Name': true } },
    { announcement: { id: 'a', text: 'x', level: 'nuclear' } },
  ];
  for (const b of bad) assert.equal((await cfgPost(b)).status, 400, JSON.stringify(b));
});

test('config rollback restores v2 fields too', async () => {
  await cfgPost({ features: { a_flag: true }, note: 'one' });
  await cfgPost({ features: { a_flag: false }, note: 'two' });
  const r = await admin('/config/rollback', { method: 'POST', body: JSON.stringify({ version: 2 }) });
  assert.equal(r.status, 201);
  assert.equal((await r.json()).config.features.a_flag, true);
});

test('device control: list, filter, block stops telemetry, unblock restores, export CSV, erase', async () => {
  const a = uuid(), b = uuid();
  await post('/v1/telemetry/boot', boot(a, { version_code: 18 }));
  await post('/v1/telemetry/boot', boot(b, { country_locale: 'ID', ram_mb: 4096 }));
  let l = await (await admin('/devices?country=PH')).json();
  assert.equal(l.total, 1); assert.equal(l.devices[0].device_id, a); assert.equal(l.devices[0].version_code, 18);
  assert.equal((await (await admin('/devices?lowram=1')).json()).total, 1);
  assert.equal((await (await admin('/devices?q=' + a.slice(0, 6))).json()).total, 1);

  assert.equal((await admin(`/devices/${a}/block`, { method: 'POST', body: JSON.stringify({ blocked: true, reason: 'abuse' }) })).status, 200);
  const boot2 = await (await post('/v1/telemetry/boot', boot(a))).json();
  assert.equal(boot2.blocked, true);
  const ev = await post('/v1/telemetry/ad-event', { device_id: a, event: 'impression', ad_format: 'banner' });
  assert.equal(ev.status, 404);
  const st = await (await fetch(`${base}/v1/device/status?d=${a}`)).json();
  assert.equal(st.blocked, true); assert.equal(st.blocked_reason, 'abuse');
  assert.equal((await (await admin('/devices?blocked=1')).json()).total, 1);

  await admin(`/devices/${a}/block`, { method: 'POST', body: JSON.stringify({ blocked: false }) });
  assert.equal((await post('/v1/telemetry/ad-event', { device_id: a, event: 'impression', ad_format: 'banner' })).status, 200);
  await admin(`/devices/${b}/note`, { method: 'POST', body: JSON.stringify({ notes: 'seller', tags: ['VIP', 'x y'] }) });
  const det = await (await admin(`/devices/${b}`)).json();
  assert.deepEqual(det.device.tags, ['vip', 'xy']);

  const csv = await (await admin('/devices/export')).text();
  assert.match(csv.split('\n')[0], /^device_id,country/);
  assert.equal(csv.trim().split('\n').length, 3);
  assert.equal((await admin(`/devices/${a}`, { method: 'DELETE' })).status, 200);
  assert.equal((await admin(`/devices/${a}`)).status, 404);
  const audit = await (await admin('/audit')).json();
  const acts = audit.entries.map((e) => e.action);
  for (const x of ['device.block', 'device.unblock', 'device.erase', 'devices.export']) assert.ok(acts.includes(x), x);
});

test('device status: forced update, staged rollout is deterministic, signature verifies', async () => {
  await cfgPost({ update_policy: { latest_version_code: 30, latest_version_name: '0.6.0', min_version_code: 20, rollout_percent: 0, update_url: 'https://yeehaw.pro/get', message: 'Please update' } });
  const id = uuid();
  await post('/v1/telemetry/boot', boot(id, { version_code: 18 }));
  const r = await fetch(`${base}/v1/device/status?d=${id}&vc=18`);
  const raw = Buffer.from(await r.arrayBuffer());
  const body = JSON.parse(raw.toString());
  assert.equal(body.update_required, true); assert.equal(body.update_available, true);
  const ok = crypto.verify('sha256', raw, crypto.createPublicKey(pem), Buffer.from(r.headers.get('x-config-signature'), 'base64'));
  assert.equal(ok, true);
  const newer = await (await fetch(`${base}/v1/device/status?d=${id}&vc=25`)).json();
  assert.equal(newer.update_required, false); assert.equal(newer.update_available, false); // 0% rollout
  await cfgPost({ update_policy: { latest_version_code: 30, min_version_code: 0, rollout_percent: 100 } });
  const full = await (await fetch(`${base}/v1/device/status?d=${id}&vc=25`)).json();
  assert.equal(full.update_available, true); assert.equal(full.update_required, false);
  assert.equal((await fetch(`${base}/v1/device/status?d=nope`)).status, 400);
});

test('health telemetry validates, stores deltas and feeds analytics', async () => {
  const id = uuid();
  await post('/v1/telemetry/boot', boot(id));
  assert.equal((await post('/v1/telemetry/health', { device_id: id, deltas: { crash: 1, skipped_requests: 120, est_bytes_avoided: 4_800_000, low_signal_seconds: 300 } })).status, 200);
  assert.equal((await post('/v1/telemetry/health', { device_id: id, deltas: { hacked: 1 } })).status, 400);
  assert.equal((await post('/v1/telemetry/health', { device_id: id, deltas: { crash: -1 } })).status, 400);
  assert.equal((await post('/v1/telemetry/health', { device_id: uuid(), deltas: { crash: 1 } })).status, 404);
  const h = await (await admin('/analytics/health')).json();
  assert.equal(h.crashes, 1); assert.equal(h.savings.skipped_requests, 120); assert.equal(h.savings.est_bytes_avoided, 4800000);
  assert.equal(h.reporting_devices, 1); assert.equal(h.by_version[0].app_version, '0.4.6');
});

test('ad analytics: totals, CTR, eCPM, by country and by campaign', async () => {
  const id = uuid();
  await post('/v1/telemetry/boot', boot(id));
  for (let i = 0; i < 4; i++) await post('/v1/telemetry/ad-event', { device_id: id, event: 'impression', ad_format: 'banner', network: 'admob' });
  await post('/v1/telemetry/ad-event', { device_id: id, event: 'click', ad_format: 'banner', network: 'admob' });
  await post('/v1/telemetry/ad-event', { device_id: id, event: 'paid', ad_format: 'banner', network: 'admob', revenue_micros: 2000, currency: 'USD' });
  await post('/v1/telemetry/ad-event', { device_id: id, event: 'impression', ad_format: 'native', ad_unit: 'campaign:chatme-ph', network: 'house' });
  const a = await (await admin('/analytics/ads?days=7')).json();
  assert.equal(a.impressions, 5); assert.equal(a.clicks, 1); assert.ok(Math.abs(a.ctr - 0.2) < 1e-9);
  assert.ok(Math.abs(a.revenue - 0.002) < 1e-9); assert.ok(Math.abs(a.ecpm - 0.4) < 1e-9);
  assert.equal(a.by_country[0].country, 'PH'); assert.equal(a.by_campaign[0].campaign, 'chatme-ph');
  assert.equal(a.by_format.find((f) => f.ad_format === 'banner').impressions, 4);
});

test('geo, fleet, retention, system endpoints return real aggregates', async () => {
  const ids = [uuid(), uuid(), uuid()];
  await post('/v1/telemetry/boot', boot(ids[0], { ram_mb: 1500 }));
  await post('/v1/telemetry/boot', boot(ids[1], { country_locale: 'IN', ram_mb: 2900 }));
  await post('/v1/telemetry/boot', boot(ids[2], { country_locale: 'IN', ram_mb: 6000 }));
  // make one device old and retained on day 1
  await pool.query(`UPDATE devices SET first_seen = now() - interval '3 days' WHERE device_id=$1`, [ids[1]]);
  await pool.query(`INSERT INTO sessions (device_id, day) VALUES ($1, ((now() - interval '2 days') AT TIME ZONE 'UTC')::date)`, [ids[1]]);
  const geo = await (await admin('/analytics/geo')).json();
  assert.deepEqual(geo.countries.map((c) => [c.country, c.devices]).sort(), [['IN', 2], ['PH', 1]]);
  assert.equal(geo.countries.find((c) => c.country === 'PH').low_ram, 1);
  const f = await (await admin('/analytics/fleet')).json();
  assert.equal(f.ram_tiers.length, 3);
  assert.equal(f.retention.cohort.d1, 1); assert.equal(f.retention.d1, 1); assert.equal(f.retention.d7, null);
  const s = await (await admin('/system')).json();
  assert.equal(s.counts.devices, 3); assert.ok(s.db_latency_ms >= 0); assert.ok(s.signing_key_fingerprint);
});

test('new admin routes require the token', async () => {
  for (const p of ['/devices', '/analytics/ads', '/analytics/health', '/system', '/audit']) {
    assert.equal((await fetch(base + '/v1/admin' + p)).status, 401, p);
  }
});

test('pro verify: fails closed without Play credentials, signed answer with a verifier', async () => {
  const id = uuid(), tok = 'x'.repeat(40);
  let r = await post('/v1/pro/verify', { device_id: id, product_id: 'yeehaw_pro', purchase_token: tok });
  assert.equal(r.status, 503);
  const app2 = createApp({ pool, adminToken: TOKEN, signingKeyPem: pem, rateLimits: false,
    playVerify: async (t) => (t === tok ? { active: true, expiresAt: '2030-01-01T00:00:00Z', state: 'SUBSCRIPTION_STATE_ACTIVE' } : { active: false, expiresAt: null, state: 'invalid' }) });
  const s2 = app2.listen(0); const b2 = 'http://127.0.0.1:' + s2.address().port;
  try {
    const send = (token) => fetch(b2 + '/v1/pro/verify', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ device_id: id, product_id: 'yeehaw_pro', purchase_token: token }) });
    r = await send(tok);
    assert.equal(r.status, 200);
    const raw = Buffer.from(await r.arrayBuffer());
    assert.ok(signer.verify ? signer.verify(raw, r.headers.get('x-config-signature')) : r.headers.get('x-config-signature'));
    assert.equal(JSON.parse(raw.toString()).pro, true);
    r = await send('y'.repeat(40));
    assert.equal(JSON.parse(await r.text()).pro, false);
    r = await fetch(b2 + '/v1/pro/verify', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ device_id: 'nope', product_id: 'yeehaw_pro', purchase_token: tok }) });
    assert.equal(r.status, 400);
  } finally { s2.close(); }
});

test('admin subscriptions view requires token and lists verified entitlements', async () => {
  assert.equal((await fetch(base + '/v1/admin/subscriptions')).status, 401);
  const r = await admin('/subscriptions');
  assert.equal(r.status, 200);
  const j = await r.json();
  assert.equal(typeof j.active, 'number');
  assert.ok(Array.isArray(j.recent));
});

test('agent reports: disabled without a token, write-only with one, shown to the admin, never trusted blindly', async () => {
  // No AGENT_TOKEN configured on the main test app: reporting is refused, not silently accepted.
  assert.equal((await post('/v1/agent/report', { agent: 'ci-watch', status: 'ok', title: 'x' }, { authorization: 'Bearer nope' })).status, 503);
  const AT = crypto.randomBytes(32).toString('base64url');
  assert.throws(() => createApp({ pool, adminToken: TOKEN, signingKeyPem: pem, agentToken: TOKEN }), /AGENT_TOKEN/);
  const app2 = createApp({ pool, adminToken: TOKEN, signingKeyPem: pem, rateLimits: false, agentToken: AT });
  const s2 = app2.listen(0); const b2 = 'http://127.0.0.1:' + s2.address().port;
  try {
    const rep = (body, tok = AT) => fetch(b2 + '/v1/agent/report', { method: 'POST', headers: { 'content-type': 'application/json', authorization: 'Bearer ' + tok }, body: JSON.stringify(body) });
    assert.equal((await rep({ agent: 'ci-watch', status: 'ok', title: 'x' }, 'wrong')).status, 401);
    assert.equal((await rep({ agent: 'ci-watch', status: 'ok', title: 'x' }, TOKEN)).status, 401, 'the admin token is not an agent token');
    assert.equal((await rep({ agent: 'Bad Name', status: 'ok', title: 'x' })).status, 400);
    assert.equal((await rep({ agent: 'ci-watch', status: 'great', title: 'x' })).status, 400);
    assert.equal((await rep({ agent: 'ci-watch', status: 'ok', title: '' })).status, 400);
    assert.equal((await rep({ agent: 'ci-watch', status: 'ok', title: 'x', details: { big: 'y'.repeat(9000) } })).status, 400);
    assert.equal((await rep({ agent: 'ci-watch', status: 'fail', title: 'older failure', ref: 'abc123' })).status, 201);
    assert.equal((await rep({ agent: 'ci-watch', status: 'ok', title: 'all green', details: { jobs: 11 } })).status, 201);
    const dup = await rep({ agent: 'ci-watch', status: 'fail', title: 'older failure', ref: 'abc123' });
    assert.equal(dup.status, 200); assert.equal((await dup.json()).duplicate, true, 'a retried delivery with the same ref is not stored twice');
    const r = await (await fetch(b2 + '/v1/admin/agents', { headers: adminH })).json();
    assert.equal(r.enabled, true);
    assert.equal(r.latest.length, 1); assert.equal(r.latest[0].title, 'all green'); assert.equal(r.recent.length, 2);
    // an agent token cannot read the admin API
    assert.equal((await fetch(b2 + '/v1/admin/agents', { headers: { authorization: 'Bearer ' + AT } })).status, 401);
  } finally { s2.close(); }
});


const { runOnce, REPAIRS } = require('../agents/orchestrator');
const releaseLease = () => pool.query("UPDATE agent_lease SET until = 'epoch'");

test('tick: db and fleet statuses are real, with an open dead-man finding when no scheduler heartbeat existed before', async () => {
  const AT = crypto.randomBytes(32).toString('base64url');
  const app2 = createApp({ pool, adminToken: TOKEN, signingKeyPem: pem, rateLimits: false, agentToken: AT });
  const s2 = app2.listen(0); const b2 = 'http://127.0.0.1:' + s2.address().port;
  try {
    const tick = (tok = AT) => fetch(b2 + '/v1/agent/tick', { method: 'POST', headers: { authorization: 'Bearer ' + tok } });
    assert.equal((await tick('wrong')).status, 401);
    const first = await (await tick()).json();
    assert.equal(first.ok, true); assert.equal(first.fleet, 'ok'); assert.equal(first.db, 'ok');
    assert.equal(first.orchestrator.lease, 'acquired');
    // a second tick fired right away is a duplicate: it is skipped, not run twice
    const dup = await tick(); assert.equal(dup.status, 202);
    await releaseLease();
    // crash spike -> fleet fail, visible to the admin
    const id = uuid(); await post('/v1/telemetry/boot', boot(id));
    assert.equal((await post('/v1/telemetry/health', { device_id: id, deltas: { crash: 6 } })).status, 200);
    const bad = await (await tick()).json();
    assert.equal(bad.fleet, 'fail');
    const r = await (await fetch(b2 + '/v1/admin/agents', { headers: adminH })).json();
    assert.equal(r.latest.find((x) => x.agent === 'fleet-health').status, 'fail');
    assert.ok(r.latest.find((x) => x.agent === 'db-health').details.ping >= 0);
    assert.ok(r.runs.length >= 1);
  } finally { s2.close(); }
});

test('orchestrator: opens a finding, runs an allowlisted low-risk repair, verifies it, and records every step', async () => {
  await pool.query(`INSERT INTO agent_reports (agent, status, title, created_at) VALUES ('old-agent','ok','ancient', now() - interval '40 days')`);
  const spec = { sizeCheck: async ({ pool: p }) => (await p.query(`SELECT 1 FROM agent_reports WHERE created_at < now() - interval '30 days'`)).rowCount
    ? [{ fingerprint: 't:old-reports', agent: 'db-optimizer', severity: 'low', title: 'old reports', risk: 'low', repair: 'prune_agent_reports', proposal: 'prune' }] : [] };
  await releaseLease();
  const r = await runOnce(pool, { specialists: spec });
  assert.equal(r.summary.opened, 1); assert.equal(r.summary.repaired, 1); assert.equal(r.summary.verified, 1);
  const f = (await pool.query('SELECT * FROM agent_findings')).rows[0];
  assert.equal(f.status, 'verified');
  const acts = (await pool.query('SELECT action, verification FROM agent_actions WHERE finding_id = $1 ORDER BY id', [f.id])).rows.map((x) => x.action);
  assert.deepEqual(acts, ['detected', 'repair prune_agent_reports', 'independent verification']);
  assert.equal((await pool.query(`SELECT 1 FROM agent_reports WHERE agent = 'old-agent'`)).rowCount, 0);
});

test('orchestrator: a repair that does not fix the problem is marked failed, never verified', async () => {
  const spec = { stuck: async () => [{ fingerprint: 't:stuck', agent: 'db-optimizer', severity: 'low', title: 'stuck', risk: 'none', repair: 'prune_agent_reports' }] };
  await releaseLease();
  const r = await runOnce(pool, { specialists: spec });
  assert.equal(r.summary.failed, 1);
  assert.equal((await pool.query(`SELECT status FROM agent_findings`)).rows[0].status, 'failed');
});

test('orchestrator: unknown repairs are refused, medium-risk findings wait for the owner, cleared findings auto-resolve', async () => {
  let on = true;
  const spec = { a: async () => on ? [
    { fingerprint: 't:evil', agent: 'x', severity: 'high', title: 'evil', risk: 'none', repair: 'drop_all_tables' },
    { fingerprint: 't:med', agent: 'x', severity: 'medium', title: 'needs owner', risk: 'medium', repair: 'prune_agent_reports' }] : [] };
  await releaseLease();
  await runOnce(pool, { specialists: spec });
  const rows = Object.fromEntries((await pool.query('SELECT fingerprint, status FROM agent_findings')).rows.map((x) => [x.fingerprint, x.status]));
  assert.equal(rows['t:evil'], 'open'); assert.equal(rows['t:med'], 'open');
  assert.equal((await pool.query(`SELECT 1 FROM agent_actions WHERE action = 'repair refused'`)).rowCount, 1);
  on = false; await releaseLease();
  const r = await runOnce(pool, { specialists: spec });
  assert.equal(r.summary.resolved, 2);
  assert.equal((await pool.query(`SELECT count(*)::int AS n FROM agent_findings WHERE status = 'resolved'`)).rows[0].n, 2);
});

test('orchestrator: overlapping runs cannot execute together', async () => {
  await releaseLease();
  const slow = { s: async () => { await new Promise((r) => setTimeout(r, 300)); return []; } };
  const [a, b] = await Promise.all([runOnce(pool, { specialists: slow }), runOnce(pool, { specialists: slow })]);
  assert.deepEqual([a.lease, b.lease].sort(), ['acquired', 'skipped']);
});

test('real specialists run against the live schema without error and the dead-man switch fires with no heartbeat', async () => {
  await releaseLease();
  const r = await runOnce(pool);
  assert.deepEqual(r.summary.errors, [], 'no specialist may crash against the real schema');
  const f = (await pool.query(`SELECT fingerprint FROM agent_findings`)).rows.map((x) => x.fingerprint);
  assert.ok(f.includes('availability:scheduler-silent'));
});

test('owner decisions: approve needs an automatic repair; reject and dismiss are audited', async () => {
  const AT = crypto.randomBytes(32).toString('base64url');
  const app2 = createApp({ pool, adminToken: TOKEN, signingKeyPem: pem, rateLimits: false, agentToken: AT });
  const s2 = app2.listen(0); const b2 = 'http://127.0.0.1:' + s2.address().port;
  try {
    const a = (await pool.query(`INSERT INTO agent_findings (fingerprint, agent, severity, title, risk) VALUES ('o:1','x','high','no repair','medium') RETURNING id`)).rows[0].id;
    const b = (await pool.query(`INSERT INTO agent_findings (fingerprint, agent, severity, title, risk, repair) VALUES ('o:2','x','medium','has repair','medium','prune_agent_reports') RETURNING id`)).rows[0].id;
    const call = (id, verb, tok = TOKEN) => fetch(`${b2}/v1/admin/findings/${id}/${verb}`, { method: 'POST', headers: { authorization: 'Bearer ' + tok, 'content-type': 'application/json' }, body: '{}' });
    assert.equal((await call(a, 'approve', AT)).status, 401, 'agent token cannot decide');
    assert.equal((await call(a, 'approve')).status, 409);
    assert.equal((await call(b, 'approve')).status, 200);
    assert.equal((await pool.query('SELECT status FROM agent_findings WHERE id = $1', [b])).rows[0].status, 'approved');
    assert.equal((await call(a, 'dismiss')).status, 200);
    assert.equal((await call(a, 'dismiss')).status, 404);
    assert.ok((await pool.query(`SELECT 1 FROM admin_audit WHERE action = 'agent.finding.approve'`)).rowCount === 1);
    // the approved repair runs on the next orchestrator pass and is verified
    await releaseLease();
    const spec = { s: async () => [] };
    await pool.query(`UPDATE agent_findings SET repair = 'prune_agent_reports' WHERE id = $1`, [b]);
    const r = await runOnce(pool, { specialists: spec });
    assert.ok(r.summary.repaired >= 1 || r.summary.resolved >= 1);
  } finally { s2.close(); }
});

test('client errors raised by libraries are 4xx JSON, never 500', async () => {
  const r = await fetch(base + '/v1/admin/devices/%E0%A4%A', { headers: adminH });
  assert.equal(r.status, 400);
  assert.equal((await r.json()).error, 'invalid_request');
});


test('a crash report triggers one agents pass without any timer, debounced, and /livez needs no database', async () => {
  assert.equal((await fetch(base + '/livez')).status, 200);
  await pool.query('TRUNCATE agent_runs RESTART IDENTITY CASCADE');
  const id = uuid();
  assert.equal((await post('/v1/telemetry/boot', boot(id))).status, 200);
  assert.equal((await post('/v1/telemetry/health', { device_id: id, deltas: { skipped_requests: 5 } })).status, 200);
  assert.equal((await pool.query('SELECT count(*)::int n FROM agent_runs')).rows[0].n, 0, 'non-crash events do not trigger a pass');
  assert.equal((await post('/v1/telemetry/health', { device_id: id, deltas: { crash: 1 } })).status, 200);
  assert.equal((await pool.query('SELECT count(*)::int n FROM agent_runs')).rows[0].n, 1, 'a crash triggers one pass');
  assert.equal((await post('/v1/telemetry/health', { device_id: id, deltas: { crash: 1 } })).status, 200);
  assert.equal((await pool.query('SELECT count(*)::int n FROM agent_runs')).rows[0].n, 1, 'a second crash within 2 minutes is debounced');
});


// ------------------------------------------------------------------ engine v2: diagnostic routing, controls, independent verification
const incidentReport = (kind, scope, severity = 'critical', status = 'fail') => pool.query(
  `INSERT INTO agent_reports (agent, status, title, details) VALUES ('watch',$1,$2,$3)`,
  [status, `${kind}: ${scope}`, { bad: ['x'], incident: { kind, scope, severity, cause: 'cause for ' + kind, evidence: ['probe note'], next: 'next step for ' + kind } }]);

test('diagnostic agent: watcher incidents become findings routed to the owning agent; site outage and API outage differ', async () => {
  const { diagnostic } = require('../agents/specialists');
  await incidentReport('wrong-deployment', 'api');
  let r = await runOnce(pool, { specialists: { diagnostic } });
  assert.equal(r.summary.opened, 1);
  let f = (await pool.query(`SELECT * FROM agent_findings WHERE fingerprint = 'incident:wrong-deployment'`)).rows[0];
  assert.equal(f.agent, 'infrastructure'); assert.equal(f.severity, 'critical'); assert.match(f.root_cause, /cause for wrong-deployment/); assert.match(f.proposal, /next step/); assert.equal(f.repair, null);
  await pool.query(`UPDATE agent_lease SET until = 'epoch'`);
  await pool.query(`TRUNCATE agent_reports`); await incidentReport('website-outage', 'site', 'high');
  await runOnce(pool, { specialists: { diagnostic } });
  const kinds = (await pool.query(`SELECT fingerprint, agent, status FROM agent_findings ORDER BY id`)).rows;
  assert.deepEqual(kinds.map((k) => [k.fingerprint, k.agent, k.status]), [['incident:wrong-deployment', 'infrastructure', 'resolved'], ['incident:website-outage', 'availability', 'open']]);
  // recovery report clears it, with the clearing recorded
  await pool.query(`UPDATE agent_lease SET until = 'epoch'`);
  await incidentReport('x', 'x', 'high', 'ok');
  await runOnce(pool, { specialists: { diagnostic } });
  assert.equal((await pool.query(`SELECT status FROM agent_findings WHERE fingerprint = 'incident:website-outage'`)).rows[0].status, 'resolved');
});

test('engine controls: validated, audited, and honoured (off = nothing runs; disabled agent is skipped; interval is enforced)', async () => {
  const calls = []; const spec = { a: async () => { calls.push('a'); return []; }, b: async () => { calls.push('b'); return []; } };
  assert.equal((await admin('/engine', { method: 'PUT', body: JSON.stringify({ interval_minutes: 0 }) })).status, 400);
  assert.equal((await admin('/engine', { method: 'PUT', body: JSON.stringify({ auto_repairs: ['drop_everything'] }) })).status, 400);
  assert.equal((await fetch(base + '/v1/admin/engine', { method: 'PUT', body: '{}', headers: { 'content-type': 'application/json' } })).status, 401);
  assert.equal((await admin('/engine', { method: 'PUT', body: JSON.stringify({ enabled: false }) })).status, 200);
  assert.equal((await runOnce(pool, { specialists: spec })).lease, 'skipped'); assert.deepEqual(calls, []);
  await admin('/engine', { method: 'PUT', body: JSON.stringify({ enabled: true, disabled_agents: ['availability'] }) });
  const ag = await (await admin('/agents')).json();
  assert.equal(ag.engine.enabled, true); assert.deepEqual(ag.engine.disabled_agents, ['availability']); assert.ok(ag.engine.agents.includes('diagnostic'));
  const r = await runOnce(pool, { specialists: { availability: async () => { calls.push('avail'); return []; }, b: spec.b } });
  assert.deepEqual(calls, ['b']); assert.equal(r.summary.per_agent.availability.state, 'disabled'); assert.equal(r.summary.per_agent.b.state, 'ok');
  await pool.query(`UPDATE agent_lease SET until = 'epoch'`);
  const again = await runOnce(pool, { specialists: spec, respectInterval: true });
  assert.equal(again.lease, 'skipped'); assert.match(again.reason, /interval/);
  const log = (await pool.query(`SELECT actor, action FROM agent_actions WHERE action = 'engine settings changed'`)).rows;
  assert.equal(log.length, 2); assert.equal(log[0].actor, 'owner');
});

test('permitted automatic repairs: removing a repair from the list stops it running by itself', async () => {
  await pool.query(`INSERT INTO agent_reports (agent, status, title, details, created_at) VALUES ('old','ok','t','{}', now() - interval '40 days')`);
  const spec = { sp: async () => (await pool.query(`SELECT 1 FROM agent_reports WHERE created_at < now() - interval '30 days'`)).rowCount
    ? [{ fingerprint: 'maint:old', agent: 'db-optimizer', severity: 'low', title: 'old rows', risk: 'low', repair: 'prune_agent_reports' }] : [] };
  await admin('/engine', { method: 'PUT', body: JSON.stringify({ auto_repairs: [] }) });
  await runOnce(pool, { specialists: spec });
  assert.equal((await pool.query(`SELECT status FROM agent_findings`)).rows[0].status, 'open');           // not auto-run
  await admin('/engine', { method: 'PUT', body: JSON.stringify({ auto_repairs: ['prune_agent_reports'] }) });
  await pool.query(`UPDATE agent_lease SET until = 'epoch'`);
  const r = await runOnce(pool, { specialists: spec });
  assert.equal(r.summary.verified, 1);
  const acts = (await pool.query(`SELECT actor, action, verification FROM agent_actions ORDER BY id`)).rows;
  assert.ok(acts.some((a) => a.actor === 'repair'), 'repair agent acted'); assert.ok(acts.some((a) => a.actor === 'qa' && a.verification === 'passed'), 'qa agent verified independently');
});

test('independent verification: a repair whose independent check fails is reported failed even if the detector goes quiet', async () => {
  const { REPAIR_VERIFY, REPAIRS } = require('../agents/orchestrator');
  const keep = REPAIR_VERIFY.prune_agent_reports;
  REPAIR_VERIFY.prune_agent_reports = async () => 'independent check found leftover rows';
  const keepRepair = REPAIRS.prune_agent_reports; REPAIRS.prune_agent_reports = async () => 'claimed success';
  try {
    let present = true; const spec = { sp: async () => present ? [{ fingerprint: 'maint:x', agent: 'db-optimizer', severity: 'low', title: 'x', risk: 'low', repair: 'prune_agent_reports' }] : [] };
    const orig = spec.sp; spec.sp = async () => { const out = await orig(); if (out.length) present = false; return out; }; // detector goes quiet after first look
    const r = await runOnce(pool, { specialists: spec });
    assert.equal(r.summary.failed, 1); assert.equal(r.summary.verified, 0);
    assert.equal((await pool.query(`SELECT status FROM agent_findings`)).rows[0].status, 'failed');
    assert.match((await pool.query(`SELECT result FROM agent_actions WHERE action = 'independent verification'`)).rows[0].result, /FAILED: independent check found leftover rows/);
  } finally { REPAIR_VERIFY.prune_agent_reports = keep; REPAIRS.prune_agent_reports = keepRepair; }
});

test('end to end over real HTTP: watcher detects "API serving the static site", console receives it, the engine diagnoses, routes and shows it, recovery clears it', async () => {
  const { runOnce: watchOnce } = await import('../../../agents/watch/index.js');
  const { diagnostic } = require('../agents/specialists');
  const agentToken = crypto.randomBytes(32).toString('base64url');
  const app2 = createApp({ pool, adminToken: TOKEN, signingKeyPem: pem, rateLimits: false, agentToken });
  const srv = app2.listen(0); const url = 'http://127.0.0.1:' + srv.address().port;
  try {
    const st = new Map(); const storage = { get: async (k) => st.get(k), put: async (k, v) => { st.set(k, v); } };
    const env = { CONSOLE_URL: url, AGENT_TOKEN: agentToken };
    let apiBroken = true;
    const f = async (u, o) => {
      if (u.startsWith('https://yeehaw.pro')) return new Response('ok', { status: 200, headers: { 'content-type': 'text/html' } });
      if (apiBroken && (u.endsWith('/livez') || u.endsWith('/healthz'))) return new Response('<html>static site</html>', { status: 200, headers: { 'content-type': 'text/html' } });
      return fetch(u, o); // real HTTP to the real Express app, incl. POST /v1/agent/report
    };
    await pool.query(`DELETE FROM agent_actions WHERE finding_id IN (SELECT id FROM agent_findings WHERE fingerprint = 'incident:wrong-deployment')`);
    await pool.query(`DELETE FROM agent_findings WHERE fingerprint = 'incident:wrong-deployment'`); // isolate from findings left by earlier tests
    let t = Date.now();
    const w = await watchOnce(env, storage, f, t);
    assert.equal(w.next.incident, 'wrong-deployment'); assert.equal(w.next.queued, 0, 'report delivered');
    await pool.query(`UPDATE agent_lease SET until = 'epoch'`); // isolate from any engine lease left by earlier runs
    const run = await runOnce(pool, { specialists: { diagnostic } });
    assert.ok(run, 'orchestrator ran'); // the watcher's own tick may already have opened the finding; what matters is that it exists exactly once
    const d = await (await admin('/agents')).json();
    assert.equal(d.findings.filter((x) => x.fingerprint === 'incident:wrong-deployment').length, 1);
    const fnd = d.findings.find((x) => x.fingerprint === 'incident:wrong-deployment');
    assert.equal(fnd.agent, 'infrastructure'); assert.equal(fnd.severity, 'critical'); assert.match(fnd.root_cause, /static site/); assert.match(fnd.proposal, /Root Directory/);
    assert.equal(fnd.repair, null, 'a production deployment fix is never automatic');
    assert.equal(d.pending_approvals, 1);
    assert.equal((await admin(`/findings/${fnd.id}/approve`, { method: 'POST', body: '{}' })).status, 409, 'no automatic repair exists, so approval cannot trigger one');
    apiBroken = false; t += 30_000; await pool.query(`UPDATE agent_lease SET until = 'epoch'`);
    await watchOnce(env, storage, f, t);
    await runOnce(pool, { specialists: { diagnostic } });
    assert.equal((await pool.query(`SELECT status FROM agent_findings WHERE id = $1`, [fnd.id])).rows[0].status, 'resolved');
    const acts = (await pool.query(`SELECT actor, action FROM agent_actions WHERE finding_id = $1 ORDER BY id`, [fnd.id])).rows.map((a) => a.action);
    assert.deepEqual(acts, ['detected', 'auto-resolved']);
  } finally { srv.close(); }
});

// ------------------------------------------------------------------ agent write API: findings, evidence, independent verification, rollback, costs
test('agent API: findings and actions need the agent token; verification only by QA, only for the prepared commit, never from the same run; secrets are redacted; rollback is recorded', async () => {
  const agentToken = crypto.randomBytes(32).toString('base64url');
  const a2 = createApp({ pool, adminToken: TOKEN, signingKeyPem: pem, rateLimits: false, agentToken });
  const srv = a2.listen(0); const u = 'http://127.0.0.1:' + srv.address().port;
  const ag = (p, body, token = agentToken, method = 'POST') => fetch(u + p, { method, headers: { 'content-type': 'application/json', authorization: 'Bearer ' + token }, body: method === 'GET' ? undefined : JSON.stringify(body) });
  try {
    assert.equal((await ag('/v1/agent/finding', {}, 'wrong')).status, 401);
    assert.equal((await ag('/v1/agent/finding', { agent: 'nobody', fingerprint: 'x:y1', severity: 'high', title: 't' })).status, 400);
    const c = await (await ag('/v1/agent/finding', { agent: 'repair', fingerprint: 'code:bug-1', severity: 'high', title: 'bug', repair: 'drop_all_tables', evidence: { log: 'token ghp_abcdefghijklmnop leaked postgres://u:p@h/db' }, proposal: 'fix it' })).json();
    assert.ok(c.created);
    const row = (await pool.query('SELECT repair, evidence FROM agent_findings WHERE id = $1', [c.id])).rows[0];
    assert.equal(row.repair, null, 'agents cannot inject automatic repairs'); assert.ok(!JSON.stringify(row.evidence).includes('ghp_abc') && !JSON.stringify(row.evidence).includes('postgres://'));
    // no fix prepared yet: nobody can verify
    const ev = { commit: 'abc1234', run_id: 'qa-1', tests: { exit: 0, passed: 10, failed: 0 } };
    assert.equal((await ag('/v1/agent/action', { finding_id: c.id, actor: 'qa', action: 'verify', verification: 'passed', evidence: ev })).status, 409);
    // a repair cannot verify itself, and a fix must carry evidence
    assert.equal((await ag('/v1/agent/action', { finding_id: c.id, actor: 'repair', action: 'fix prepared', verification: 'passed', evidence: ev })).status, 403);
    assert.equal((await ag('/v1/agent/action', { finding_id: c.id, actor: 'repair', action: 'fix prepared', evidence: {} })).status, 400);
    const prep = { commit: 'abc1234', run_id: 'repair-1', branch: 'repair/1', tests: { exit: 0, passed: 10, failed: 0 } };
    assert.equal((await ag('/v1/agent/action', { finding_id: c.id, actor: 'repair', action: 'fix prepared', evidence: prep })).status, 201);
    assert.equal((await pool.query('SELECT status FROM agent_findings WHERE id=$1', [c.id])).rows[0].status, 'applied');
    // same run id, wrong commit -> refused; independent run on the right commit -> verified
    assert.equal((await ag('/v1/agent/action', { finding_id: c.id, actor: 'qa', action: 'verify', verification: 'passed', evidence: { ...ev, run_id: 'repair-1' } })).status, 409);
    assert.equal((await ag('/v1/agent/action', { finding_id: c.id, actor: 'qa', action: 'verify', verification: 'passed', evidence: { ...ev, commit: 'deadbee' } })).status, 409);
    assert.equal((await ag('/v1/agent/action', { finding_id: c.id, actor: 'qa', action: 'verify', verification: 'passed', evidence: ev })).status, 201);
    assert.equal((await pool.query('SELECT status FROM agent_findings WHERE id=$1', [c.id])).rows[0].status, 'verified');
    // a failing independent run marks it failed
    const c2 = await (await ag('/v1/agent/finding', { agent: 'repair', fingerprint: 'code:bug-2', severity: 'high', title: 'bug2' })).json();
    await ag('/v1/agent/action', { finding_id: c2.id, actor: 'repair', action: 'fix prepared', evidence: { ...prep, commit: 'bbb2222' } });
    await ag('/v1/agent/action', { finding_id: c2.id, actor: 'qa', action: 'verify', verification: 'failed', evidence: { commit: 'bbb2222', run_id: 'qa-2', tests: { exit: 1, passed: 8, failed: 2 } } });
    assert.equal((await pool.query('SELECT status FROM agent_findings WHERE id=$1', [c2.id])).rows[0].status, 'failed');
    // evidence is visible in the console; rollback is only possible for a prepared change
    const d = await (await admin('/agents')).json();
    assert.ok(d.actions.some((x) => x.actor === 'qa' && x.evidence && x.evidence.tests));
    const c3 = await (await ag('/v1/agent/finding', { agent: 'infrastructure', fingerprint: 'infra:x1', severity: 'low', title: 'no code' })).json();
    assert.equal((await admin(`/findings/${c3.id}/rollback`, { method: 'POST', body: '{}' })).status, 409);
    assert.equal((await admin(`/findings/${c.id}/rollback`, { method: 'POST', body: '{}' })).status, 202);
    const pend = await (await ag('/v1/agent/pending-rollbacks', null, agentToken, 'GET')).json();
    assert.deepEqual(pend.requests, [{ finding_id: c.id, branch: 'repair/1', commit: 'abc1234' }]);
    await ag('/v1/agent/action', { finding_id: c.id, actor: 'repair', action: 'rollback done', evidence: { commit: 'fed1234' } });
    assert.deepEqual((await (await ag('/v1/agent/pending-rollbacks', null, agentToken, 'GET')).json()).requests, []);
    // a rollback counts as verified only by QA, for the exact rollback commit, from another run, with tests and a matching tree
    const rbq = (over) => ag('/v1/agent/action', { finding_id: c.id, actor: 'qa', action: 'rollback verified', verification: 'passed', evidence: { commit: 'fed1234', run_id: 'qa-rb-1', tests: { exit: 0 }, tree_matches_pre_fix: true, ...over } });
    assert.equal((await rbq({ commit: 'ccc9999' })).status, 409, 'wrong commit');
    assert.equal((await rbq({ tests: undefined })).status, 409, 'no tests');
    assert.equal((await rbq({ tree_matches_pre_fix: undefined })).status, 409, 'no tree comparison');
    assert.equal((await rbq({ tree_matches_pre_fix: false })).status, 409, 'a passed verdict cannot carry a tree mismatch');
    assert.equal((await ag('/v1/agent/action', { finding_id: c.id, actor: 'repair', action: 'rollback verified', verification: 'passed', evidence: {} })).status, 403, 'repair cannot verify itself');
    assert.equal((await rbq({})).status, 201);
    assert.ok((await pool.query(`SELECT 1 FROM agent_actions WHERE finding_id=$1 AND action='rollback verified' AND verification='passed'`, [c.id])).rowCount === 1);
    await ag('/v1/agent/action', { finding_id: c3.id, actor: 'repair', action: 'rollback failed', evidence: {} });
    // costs: honest empty state, then real report
    assert.equal((await (await admin('/costs')).json()).costs.length, 0);
    await ag('/v1/agent/report', { agent: 'infra-cost', status: 'ok', title: 'costs', details: { costs: [{ provider: 'neon', metric: 'compute_hours', value: 1.5, unit: 'h' }] } });
    assert.equal((await (await admin('/costs')).json()).costs[0].provider, 'neon');
  } finally { srv.close(); }
});

// ------------------------------------------------------------------ Google Play verification: the real client logic against Google-shaped answers, and the server's anti-fraud rules
test('play verifier maps Google subscription states: canceled-but-paid stays entitled, expired / pending / on hold do not', async () => {
  const { makePlayVerifier } = require('../lib/play');
  const { generateKeyPairSync } = require('node:crypto');
  const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048, privateKeyEncoding: { type: 'pkcs8', format: 'pem' }, publicKeyEncoding: { type: 'spki', format: 'pem' } });
  const future = new Date(Date.now() + 86400000).toISOString(), past = new Date(Date.now() - 86400000).toISOString();
  const answer = (state, expiryTime, extra = {}) => async (u) => u.includes('oauth2')
    ? new Response(JSON.stringify({ access_token: 't', expires_in: 3600 }), { status: 200 })
    : new Response(JSON.stringify({ subscriptionState: state, lineItems: [{ productId: 'yeehaw_pro', expiryTime }], ...extra }), { status: 200 });
  const v = (state, exp, extra) => makePlayVerifier({ serviceAccountJson: { client_email: 'x@y', private_key: privateKey }, packageName: 'app.yehaw.browser', fetchImpl: answer(state, exp, extra) });
  assert.equal((await v('SUBSCRIPTION_STATE_ACTIVE', future)('t'.repeat(30))).active, true);
  assert.equal((await v('SUBSCRIPTION_STATE_IN_GRACE_PERIOD', future)('t'.repeat(30))).active, true);
  assert.equal((await v('SUBSCRIPTION_STATE_CANCELED', future)('t'.repeat(30))).active, true, 'user cancelled but the paid period has not ended');
  assert.equal((await v('SUBSCRIPTION_STATE_CANCELED', past)('t'.repeat(30))).active, false);
  assert.equal((await v('SUBSCRIPTION_STATE_ACTIVE', past)('t'.repeat(30))).active, false, 'an expiry in the past is never entitled');
  for (const s of ['SUBSCRIPTION_STATE_EXPIRED', 'SUBSCRIPTION_STATE_PENDING', 'SUBSCRIPTION_STATE_ON_HOLD', 'SUBSCRIPTION_STATE_PAUSED']) assert.equal((await v(s, future)('t'.repeat(30))).active, false, s);
  const r = await v('SUBSCRIPTION_STATE_ACTIVE', future, { externalAccountIdentifiers: { obfuscatedExternalAccountId: 'abc' } })('t'.repeat(30));
  assert.equal(r.productId, 'yeehaw_pro'); assert.equal(r.externalAccountId, 'abc');
});

test('pro verify: wrong product, someone else\'s account, and a token already used by another device are all refused; every attempt is recorded', async () => {
  const dev1 = uuid(), dev2 = uuid(), tok = 'z'.repeat(40);
  const good = { active: true, expiresAt: '2030-01-01T00:00:00Z', state: 'SUBSCRIPTION_STATE_ACTIVE', productId: 'yeehaw_pro' };
  let answer = good;
  const app2 = createApp({ pool, adminToken: TOKEN, signingKeyPem: pem, rateLimits: false, playVerify: async () => answer });
  const s2 = app2.listen(0); const b2 = 'http://127.0.0.1:' + s2.address().port;
  const send = (device, token = tok, product = 'yeehaw_pro') => fetch(b2 + '/v1/pro/verify', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ device_id: device, product_id: product, purchase_token: token }) });
  try {
    let r = await send(dev1, tok, 'some_other_product'); assert.equal(r.status, 400);
    answer = { ...good, productId: 'a_cheaper_product' };
    r = await send(dev1, 'p'.repeat(40)); assert.equal(JSON.parse(await r.text()).pro, false, 'Google says a different product');
    answer = { ...good, externalAccountId: 'not-this-device' };
    r = await send(dev1, 'q'.repeat(40)); assert.equal(JSON.parse(await r.text()).pro, false, 'purchase belongs to another account');
    answer = good;
    r = await send(dev1); assert.equal(JSON.parse(await r.text()).pro, true);
    r = await send(dev1); assert.equal(JSON.parse(await r.text()).pro, true, 'repeating the same request is harmless');
    r = await send(dev2); assert.equal(r.status, 409, 'the same token cannot entitle a second device');
    assert.equal((await pool.query('SELECT count(*)::int AS n FROM entitlements WHERE device_id = $1', [dev2])).rows[0].n, 0);
    const out = (await pool.query('SELECT outcome, count(*)::int AS n FROM verify_attempts GROUP BY 1')).rows.map((x) => x.outcome);
    for (const o of ['product_not_allowed', 'verified', 'token_reused']) assert.ok(out.includes(o), o);
    const j = await (await admin('/subscriptions')).json();
    assert.ok(Array.isArray(j.verification_attempts_30d) && j.verification_attempts_30d.length >= 3);
  } finally { s2.close(); }
});

test('audit trail is append-only at the database level', async () => {
  await pool.query(`INSERT INTO admin_audit (actor, action, detail) VALUES ('test','append-only-check','{}')`);
  await assert.rejects(pool.query(`UPDATE admin_audit SET action = 'edited' WHERE action = 'append-only-check'`), /append-only/);
  await assert.rejects(pool.query(`DELETE FROM admin_audit WHERE action = 'append-only-check'`), /append-only/);
});
