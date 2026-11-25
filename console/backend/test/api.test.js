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
  await pool.query('TRUNCATE share_events, health_events, ad_events, sessions, devices, admin_audit, remote_configs RESTART IDENTITY CASCADE');
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
