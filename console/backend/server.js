'use strict';
const path = require('node:path');
const express = require('express');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');
const { makePool, migrate } = require('./db');
const V = require('./lib/validate');
const { loadSigner, safeEqual } = require('./lib/sign');
const { makePlayVerifier } = require('./lib/play');

const crypto = require('node:crypto');
const CONFIG_TTL_SECONDS = 3600;
const bucketOf = (id) => parseInt(crypto.createHash('sha256').update(id).digest('hex').slice(0, 8), 16) % 100;

const { runOnce: runOrchestrator, getSettings: getEngineSettings, REPAIRS: ENGINE_REPAIRS } = require('./agents/orchestrator');
const ENGINE_AGENTS = Object.keys(require('./agents/specialists'));

function createApp({ pool, adminToken, signingKeyPem, rateLimits = true, trustProxy = 1, playVerify = null, agentToken = null }) {
  if (!pool) throw new Error('pool is required');
  if (!adminToken || adminToken.length < 32) throw new Error('ADMIN_TOKEN must be at least 32 characters');
  if (!signingKeyPem) throw new Error('CONFIG_SIGNING_KEY_PEM is required');
  if (agentToken && (agentToken.length < 32 || agentToken === adminToken)) throw new Error('AGENT_TOKEN must be at least 32 characters and different from ADMIN_TOKEN');
  const signer = loadSigner(signingKeyPem.replace(/\\n/g, '\n'));

  const app = express();
  app.disable('x-powered-by');
  app.set('etag', false);
  app.set('trust proxy', trustProxy);

  app.use(helmet({
    contentSecurityPolicy: {
      useDefaults: false,
      directives: {
        'default-src': ["'self'"],
        'script-src': ["'self'", "'unsafe-inline'"],
        'style-src': ["'self'", "'unsafe-inline'"],
        'img-src': ["'self'", 'data:'],
        'connect-src': ["'self'"],
        'frame-ancestors': ["'none'"],
        'base-uri': ["'none'"],
        'form-action': ["'self'"],
      },
    },
    crossOriginEmbedderPolicy: false,
  }));

  const limiter = (windowMs, limit) => rateLimits
    ? rateLimit({ windowMs, limit, standardHeaders: true, legacyHeaders: false, message: { error: 'rate_limited' } })
    : (_req, _res, next) => next();
  const small = express.json({ limit: '8kb', strict: true });
  const big = express.json({ limit: '256kb', strict: true });
  const wrap = (fn) => (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);

  // ------------------------------------------------------------------ public: telemetry
  app.post('/v1/telemetry/boot', limiter(60_000, 30), small, wrap(async (req, res) => {
    const p = V.bootPayload(req.body);
    // Prefer the edge-derived country (Cloudflare / Vercel) over the device locale; never store IP addresses.
    const edge = String(req.get('cf-ipcountry') || req.get('x-vercel-ip-country') || '').toUpperCase();
    const country = /^[A-Z]{2}$/.test(edge) && edge !== 'XX' && edge !== 'T1' ? edge : p.country;
    // One statement, one round trip, still atomic: device upsert + daily session upsert (skipped when the device is blocked).
    const r = await pool.query(
      `WITH d AS (
         INSERT INTO devices (device_id, app_version, android_os_ver, ram_mb, country, version_code)
         VALUES ($1,$2,$3,$4,$5,$6)
         ON CONFLICT (device_id) DO UPDATE SET last_seen = now(), boot_count = devices.boot_count + 1,
           app_version = EXCLUDED.app_version, android_os_ver = EXCLUDED.android_os_ver,
           ram_mb = EXCLUDED.ram_mb, country = COALESCE(EXCLUDED.country, devices.country),
           version_code = COALESCE(EXCLUDED.version_code, devices.version_code)
         RETURNING device_id, blocked),
       s AS (
         INSERT INTO sessions (device_id, day) SELECT device_id, (now() AT TIME ZONE 'UTC')::date FROM d WHERE NOT blocked
         ON CONFLICT (device_id, day) DO UPDATE SET session_count = sessions.session_count + 1, last_at = now()
         RETURNING 1)
       SELECT blocked FROM d`,
      [p.device_id, p.app_version, p.android_os_ver, p.ram_mb, country, p.version_code]);
    if (r.rows[0].blocked) return res.status(200).json({ ok: true, blocked: true });
    res.status(200).json({ ok: true });
  }));

  // User-initiated erasure ("forget me"): removes the device and, by cascade, its sessions and ad events.
  app.post('/v1/telemetry/forget', limiter(60_000, 10), small, wrap(async (req, res) => {
    const id = String((req.body && req.body.device_id) || '').toLowerCase();
    if (!V.UUID_RE.test(id)) throw new V.ValidationError('device_id: must be a UUID', 'device_id');
    await pool.query('DELETE FROM devices WHERE device_id = $1', [id]);
    res.status(200).json({ ok: true });
  }));

  app.post('/v1/telemetry/ad-event', limiter(60_000, 240), small, wrap(async (req, res) => {
    const e = V.adEventPayload(req.body);
    const r = await pool.query(
      `INSERT INTO ad_events (device_id, event_type, ad_format, ad_unit, network, revenue_micros, currency)
       SELECT $1,$2,$3,$4,$5,$6,$7 WHERE EXISTS (SELECT 1 FROM devices WHERE device_id = $1 AND NOT blocked)`,
      [e.device_id, e.event, e.ad_format, e.ad_unit, e.network, e.revenue_micros, e.currency]
    );
    if (!r.rowCount) return res.status(404).json({ error: 'unknown_device' });
    res.status(200).json({ ok: true });
  }));

  app.post('/v1/telemetry/share-event', limiter(60_000, 120), small, wrap(async (req, res) => {
    const e = V.shareEventPayload(req.body);
    const r = await pool.query(
      `INSERT INTO share_events (device_id, action, channel, ref_code)
       SELECT $1,$2,$3,$4 WHERE EXISTS (SELECT 1 FROM devices WHERE device_id = $1 AND NOT blocked)`,
      [e.device_id, e.action, e.channel, e.ref_code]
    );
    if (!r.rowCount) return res.status(404).json({ error: 'unknown_device' });
    res.status(200).json({ ok: true });
  }));

  // One agents pass triggered by a real event instead of a clock. Debounced through the database (at most one per 2 minutes
  // across all instances); the orchestrator takes its own lease. Never fails the request that triggered it.
  const agentsOnEvent = async (reason) => {
    try {
      const last = await pool.query(`SELECT 1 FROM agent_runs WHERE started_at > now() - interval '2 minutes' LIMIT 1`);
      if (last.rowCount) return { skipped: 'recent_run' };
      const { rows } = await pool.query(`SELECT
          COALESCE(sum(value) FILTER (WHERE kind IN ('crash','freeze') AND created_at > now() - interval '1 hour'), 0)::int AS bad_1h,
          COALESCE(sum(value) FILTER (WHERE kind IN ('crash','freeze') AND created_at <= now() - interval '1 hour' AND created_at > now() - interval '25 hours'), 0)::int AS bad_prev24h
        FROM health_events`);
      const r = rows[0]; const base = r.bad_prev24h / 24;
      const fleet = r.bad_1h >= 5 && r.bad_1h > 3 * base ? 'fail' : r.bad_1h >= 3 && r.bad_1h > 2 * base ? 'warn' : 'ok';
      if (fleet !== 'ok') await pool.query('INSERT INTO agent_reports (agent, status, title, details) VALUES ($1,$2,$3,$4)', ['fleet-health', fleet,
        `crashes+freezes last hour: ${r.bad_1h} (usual ${base.toFixed(1)}/h); triggered by ${reason}`, { bad_1h: r.bad_1h, bad_prev24h: r.bad_prev24h, trigger: reason }]);
      return await runOrchestrator(pool, { respectInterval: true });
    } catch (e) { return { error: e.message }; }
  };

  // Device-reported health and measured savings (deltas). Counts only: no URLs, no page content.
  app.post('/v1/telemetry/health', limiter(60_000, 30), small, wrap(async (req, res) => {
    const h = V.healthPayload(req.body);
    if (!h.rows.length) return res.status(200).json({ ok: true });
    const ok = await pool.query('SELECT 1 FROM devices WHERE device_id = $1 AND NOT blocked', [h.device_id]);
    if (!ok.rowCount) return res.status(404).json({ error: 'unknown_device' });
    const kinds = h.rows.map((r) => r[0]), vals = h.rows.map((r) => r[1]);
    await pool.query(
      `INSERT INTO health_events (device_id, kind, value) SELECT $1, k, v FROM unnest($2::text[], $3::bigint[]) AS t(k, v)`,
      [h.device_id, kinds, vals]
    );
    // Event-driven monitoring: a crash or freeze report is exactly when the agents should look, so run one pass now (no timer).
    if (kinds.some((k) => k === 'crash' || k === 'freeze')) await agentsOnEvent('crash-or-freeze');
    res.status(200).json({ ok: true });
  }));

  // Per-device status, signed like the config: blocked, update required/available (staged rollout by device bucket).
  app.get('/v1/device/status', limiter(60_000, 60), wrap(async (req, res) => {
    const id = String(req.query.d || '').toLowerCase();
    if (!V.UUID_RE.test(id)) throw new V.ValidationError('d: must be a UUID', 'd');
    const vc = Number.parseInt(req.query.vc, 10);
    const dev = (await pool.query('SELECT blocked, blocked_reason, version_code FROM devices WHERE device_id = $1', [id])).rows[0];
    const row = await activeRow();
    const up = (row && row.update_policy) || {};
    const code = Number.isInteger(vc) ? vc : (dev && dev.version_code) || 0;
    const required = !!up.min_version_code && code > 0 && code < up.min_version_code;
    const available = !!up.latest_version_code && code > 0 && code < up.latest_version_code && bucketOf(id) < (up.rollout_percent ?? 100);
    const body = Buffer.from(JSON.stringify({
      blocked: !!(dev && dev.blocked), blocked_reason: dev && dev.blocked ? (dev.blocked_reason || '') : '',
      update_required: required, update_available: required || available,
      latest_version_name: up.latest_version_name || '', update_url: up.update_url || '', update_message: up.message || '',
      checked_at: new Date().toISOString(),
    }), 'utf8');
    res.set({ 'X-Config-Signature': signer.sign(body), 'Cache-Control': 'no-store' });
    res.type('application/json').send(body);
  }));


  // Server-side Pro check. The app sends a Play purchase token; the server asks Google Play and returns a SIGNED answer.
  // Fails closed: with no Play credentials configured it answers 503 and the app stays on the free tier.
  app.post('/v1/pro/verify', limiter(60_000, 12), small, wrap(async (req, res) => {
    const b = req.body || {};
    const id = String(b.device_id || '').toLowerCase();
    if (!V.UUID_RE.test(id)) throw new V.ValidationError('device_id: must be a UUID', 'device_id');
    const product = String(b.product_id || '');
    if (!/^[a-z0-9_.]{3,64}$/.test(product)) throw new V.ValidationError('product_id: invalid', 'product_id');
    const tokenIn = String(b.purchase_token || '');
    if (tokenIn.length < 20 || tokenIn.length > 2048) throw new V.ValidationError('purchase_token: invalid', 'purchase_token');
    if (!playVerify) return res.status(503).json({ error: 'verification_not_configured' });
    let result;
    try { result = await playVerify(tokenIn); } catch (e) { return res.status(502).json({ error: 'play_unavailable' }); }
    const hash = crypto.createHash('sha256').update(tokenIn).digest('hex');
    await pool.query(
      `INSERT INTO entitlements (device_id, product_id, token_hash, state, active, expires_at, verified_at)
       VALUES ($1,$2,$3,$4,$5,$6, now())
       ON CONFLICT (device_id) DO UPDATE SET product_id=$2, token_hash=$3, state=$4, active=$5, expires_at=$6, verified_at=now()`,
      [id, product, hash, result.state, result.active, result.expiresAt]);
    const body = Buffer.from(JSON.stringify({
      device_id: id, pro: !!result.active, expires_at: result.expiresAt, state: result.state, checked_at: new Date().toISOString(),
    }), 'utf8');
    res.set({ 'X-Config-Signature': signer.sign(body), 'Cache-Control': 'no-store' });
    res.type('application/json').send(body);
  }));

  // ------------------------------------------------------------------ public: signed remote config
  const configCache = new Map(); // version -> { body, sig }
  const rowToConfig = (r) => ({
    version: r.version,
    issued_at: new Date(r.created_at).toISOString(),
    ttl_seconds: CONFIG_TTL_SECONDS,
    default_search_url: r.default_search_url,
    affiliate_tag: r.affiliate_tag,
    speed_dial: r.speed_dial,
    blacklisted_domains: r.blacklisted_domains,
    admob_enabled: r.admob_enabled,
    features: r.features || {},
    update_policy: r.update_policy || {},
    ads_policy: r.ads_policy || {},
    promos: r.promos || [],
    announcement: r.announcement || null,
  });
  const activeRow = async (db = pool) => (await db.query('SELECT * FROM remote_configs WHERE is_active')).rows[0];

  app.get('/v1/config/app-config', limiter(60_000, 120), wrap(async (req, res) => {
    const row = await activeRow();
    if (!row) return res.status(503).json({ error: 'no_active_config' });
    let hit = configCache.get(row.version);
    if (!hit) {
      const body = Buffer.from(JSON.stringify(rowToConfig(row)), 'utf8');
      hit = { body, sig: signer.sign(body) };
      configCache.clear();
      configCache.set(row.version, hit);
    }
    const etag = `"v${row.version}"`;
    res.set({ ETag: etag, 'X-Config-Version': String(row.version), 'X-Config-Signature': hit.sig, 'Cache-Control': 'public, max-age=300' });
    if (req.get('if-none-match') === etag) return res.status(304).end();
    res.type('application/json').send(hit.body);
  }));

  // ------------------------------------------------------------------ maintenance agents
  // A separate, write-only token: an agent can report but can never read devices or change the config.
  app.post('/v1/agent/report', limiter(60_000, 60), express.json({ limit: '16kb' }), wrap(async (req, res) => {
    if (!agentToken) return res.status(503).json({ error: 'agent_reporting_disabled' });
    const h = req.get('authorization') || '';
    const token = h.startsWith('Bearer ') ? h.slice(7) : '';
    if (!token || !safeEqual(token, agentToken)) return res.status(401).json({ error: 'unauthorized' });
    const b = req.body || {};
    const agent = typeof b.agent === 'string' ? b.agent : '';
    const title = typeof b.title === 'string' ? b.title.trim().slice(0, 200) : '';
    const ref = typeof b.ref === 'string' ? b.ref.slice(0, 80) : null;
    const details = b.details && typeof b.details === 'object' && !Array.isArray(b.details) ? b.details : {};
    if (!/^[a-z0-9_-]{2,40}$/.test(agent)) return res.status(400).json({ error: 'bad_agent' });
    if (!['ok', 'warn', 'fail', 'fixed'].includes(b.status)) return res.status(400).json({ error: 'bad_status' });
    if (!title) return res.status(400).json({ error: 'bad_title' });
    if (JSON.stringify(details).length > 8000) return res.status(400).json({ error: 'details_too_large' });
    await pool.query('INSERT INTO agent_reports (agent, status, title, details, ref) VALUES ($1,$2,$3,$4,$5)', [agent, b.status, title, details, ref]);
    res.status(201).json({ ok: true });
  }));

  // ---- agent write API used by the repair / infrastructure / QA agents (same write-only AGENT_TOKEN; never reads devices or config)
  const agentAuth = (req, res) => {
    if (!agentToken) { res.status(503).json({ error: 'agent_reporting_disabled' }); return false; }
    const hd = req.get('authorization') || '';
    const t = hd.startsWith('Bearer ') ? hd.slice(7) : '';
    if (!t || !safeEqual(t, agentToken)) { res.status(401).json({ error: 'unauthorized' }); return false; }
    return true;
  };
  const SECRET_RE = /(ghp_|gho_|github_pat_|sk-|npm_|xox[bp]-)[A-Za-z0-9_-]{8,}|Bearer\s+[A-Za-z0-9._~+\/=-]{12,}|postgres(?:ql)?:\/\/[^\s"']+/g;
  const redact = (v) => JSON.parse(JSON.stringify(v ?? null, (_k, x) => (typeof x === 'string' ? x.replace(SECRET_RE, '[redacted]') : x)));
  const FINDING_AGENTS = ['infrastructure', 'repair', 'performance', 'security', 'qa', 'diagnostic', 'availability', 'db-optimizer', 'android'];
  const SEV = ['info', 'low', 'medium', 'high', 'critical'];
  app.post('/v1/agent/finding', limiter(60_000, 60), express.json({ limit: '16kb' }), wrap(async (req, res) => {
    if (!agentAuth(req, res)) return;
    const b = req.body || {};
    if (!FINDING_AGENTS.includes(b.agent)) return res.status(400).json({ error: 'bad_agent' });
    if (typeof b.fingerprint !== 'string' || !/^[a-z0-9_:.\/-]{3,120}$/.test(b.fingerprint)) return res.status(400).json({ error: 'bad_fingerprint' });
    if (!SEV.includes(b.severity)) return res.status(400).json({ error: 'bad_severity' });
    const title = typeof b.title === 'string' ? b.title.trim().slice(0, 200) : '';
    if (!title) return res.status(400).json({ error: 'bad_title' });
    const evidence = redact(b.evidence && typeof b.evidence === 'object' ? b.evidence : {});
    if (JSON.stringify(evidence).length > 8000) return res.status(400).json({ error: 'details_too_large' });
    const str = (v) => (typeof v === 'string' ? redact(v).slice(0, 1000) : null);
    // `repair` is never accepted from outside: automatic server-side repairs exist only in the orchestrator allowlist.
    const ex = await pool.query(`SELECT id FROM agent_findings WHERE fingerprint = $1 AND status IN ('open','approved','applied','failed')`, [b.fingerprint]);
    if (ex.rowCount) {
      await pool.query(`UPDATE agent_findings SET evidence=$2, title=$3, severity=$4, root_cause=$5, proposal=$6, updated_at=now() WHERE id=$1`, [ex.rows[0].id, evidence, title, b.severity, str(b.root_cause), str(b.proposal)]);
      return res.status(200).json({ ok: true, id: Number(ex.rows[0].id), created: false });
    }
    const ins = await pool.query(`INSERT INTO agent_findings (fingerprint, agent, severity, title, evidence, root_cause, proposal, risk) VALUES ($1,$2,$3,$4,$5,$6,$7,$8) RETURNING id`,
      [b.fingerprint, b.agent, b.severity, title, evidence, str(b.root_cause), str(b.proposal), ['none', 'low', 'medium', 'high'].includes(b.risk) ? b.risk : 'medium']);
    await pool.query('INSERT INTO agent_actions (finding_id, actor, action, result) VALUES ($1,$2,$3,$4)', [ins.rows[0].id, b.agent, 'detected', title]);
    res.status(201).json({ ok: true, id: Number(ins.rows[0].id), created: true });
  }));

  // An agent records what it did. A finding can become `verified` only through actor `qa`, and only for the very commit a `repair`
  // action prepared, and only from a different run (so an agent can never mark its own work successful).
  app.post('/v1/agent/action', limiter(60_000, 120), express.json({ limit: '16kb' }), wrap(async (req, res) => {
    if (!agentAuth(req, res)) return;
    const b = req.body || {};
    const id = Number(b.finding_id);
    if (!Number.isSafeInteger(id)) return res.status(400).json({ error: 'bad_finding' });
    if (!['repair', 'qa', 'infrastructure', 'performance', 'security'].includes(b.actor)) return res.status(400).json({ error: 'bad_actor' });
    const action = typeof b.action === 'string' ? b.action.trim().slice(0, 80) : '';
    if (!action) return res.status(400).json({ error: 'bad_action' });
    if (b.verification != null && !['passed', 'failed'].includes(b.verification)) return res.status(400).json({ error: 'bad_verification' });
    if (b.verification != null && b.actor !== 'qa') return res.status(403).json({ error: 'only_qa_verifies', detail: 'Only the QA agent can record a verification outcome.' });
    const evidence = redact(b.evidence && typeof b.evidence === 'object' ? b.evidence : {});
    if (JSON.stringify(evidence).length > 8000) return res.status(400).json({ error: 'details_too_large' });
    const f = (await pool.query('SELECT id, status FROM agent_findings WHERE id = $1', [id])).rows[0];
    if (!f) return res.status(404).json({ error: 'no_finding' });
    if (b.actor === 'repair' && /^fix prepared/.test(action) && !(/^[0-9a-f]{7,40}$/.test(evidence.commit || '') && evidence.run_id && evidence.tests)) return res.status(400).json({ error: 'evidence_required', detail: 'A prepared fix must carry commit, run_id and test results.' });
    let status = null;
    if (b.verification != null) {
      const prior = (await pool.query(`SELECT evidence FROM agent_actions WHERE finding_id = $1 AND actor = 'repair' AND action LIKE 'fix prepared%' ORDER BY id DESC LIMIT 1`, [id])).rows[0];
      if (!prior || !prior.evidence || prior.evidence.commit !== evidence.commit || prior.evidence.run_id === evidence.run_id || !evidence.run_id || !evidence.tests)
        return res.status(409).json({ error: 'not_independent', detail: 'Verification must cover the commit the repair agent prepared, come from a different run, and include test results.' });
      status = b.verification === 'passed' ? 'verified' : 'failed';
    }
    await pool.query('INSERT INTO agent_actions (finding_id, actor, action, result, verification, evidence) VALUES ($1,$2,$3,$4,$5,$6)',
      [id, b.actor, action, typeof b.result === 'string' ? redact(b.result).slice(0, 1000) : null, b.verification || null, evidence]);
    if (status) await pool.query(`UPDATE agent_findings SET status = $2, updated_at = now(), resolved_at = CASE WHEN $2 = 'verified' THEN now() END WHERE id = $1`, [id, status]);
    else if (b.actor === 'repair' && /^fix prepared/.test(action)) await pool.query(`UPDATE agent_findings SET status = 'applied', updated_at = now() WHERE id = $1 AND status IN ('open','approved','failed')`, [id]);
    else if (b.actor === 'repair' && action === 'rollback done') await pool.query(`UPDATE agent_findings SET status = 'dismissed', updated_at = now() WHERE id = $1`, [id]);
    res.status(201).json({ ok: true, status });
  }));
  app.get('/v1/agent/pending-rollbacks', limiter(60_000, 60), wrap(async (req, res) => {
    if (!agentAuth(req, res)) return;
    const r = await pool.query(`SELECT a.finding_id, a.evidence FROM agent_actions a WHERE a.action = 'rollback requested'
      AND NOT EXISTS (SELECT 1 FROM agent_actions d WHERE d.finding_id = a.finding_id AND d.action = 'rollback done' AND d.id > a.id) ORDER BY a.id`);
    res.json({ requests: r.rows.map((x) => ({ finding_id: Number(x.finding_id), branch: x.evidence && x.evidence.branch, commit: x.evidence && x.evidence.commit })) });
  }));

  // The server-side agent tick. Called every 5 minutes by the always-on scheduler (agents/worker). It measures the database
  // (round trip, schema read, write), checks fleet crash rate, then runs the orchestrator (specialist agents -> findings ->
  // approved or low-risk repairs -> verification). It needs no admin power and returns no device data.
  app.post('/v1/agent/tick', limiter(60_000, 30), wrap(async (req, res) => {
    if (!agentToken) return res.status(503).json({ error: 'agent_reporting_disabled' });
    const h = req.get('authorization') || '';
    const token = h.startsWith('Bearer ') ? h.slice(7) : '';
    if (!token || !safeEqual(token, agentToken)) return res.status(401).json({ error: 'unauthorized' });
    const lease = await pool.query(`UPDATE agent_lease SET until = now() + interval '20 seconds' WHERE id = 1 AND until < now() RETURNING id`);
    // (the orchestrator takes its own, longer lease; this short one only stops a double-fired tick from double-reporting)
    if (!lease.rowCount) return res.status(202).json({ ok: true, skipped: 'duplicate_or_overlapping_tick' });
    const t0 = Date.now(); await pool.query('SELECT 1'); const pingMs = Date.now() - t0;
    const t1 = Date.now(); const cfg = await pool.query('SELECT version FROM remote_configs WHERE is_active LIMIT 1'); const readMs = Date.now() - t1;
    const t2 = Date.now(); await pool.query(`INSERT INTO agent_reports (agent, status, title, details) VALUES ('scheduler','ok','tick received',$1)`, [{}]); const writeMs = Date.now() - t2;
    const dbMs = pingMs + readMs + writeMs;
    const dbStatus = !cfg.rowCount ? 'fail' : dbMs > 1500 ? 'warn' : 'ok';
    await pool.query('INSERT INTO agent_reports (agent, status, title, details) VALUES ($1,$2,$3,$4)', ['db-health', dbStatus,
      !cfg.rowCount ? 'database answers but no active config row exists' : `database ping ${pingMs} ms, read ${readMs} ms, write ${writeMs} ms`, { ms: dbMs, ping: pingMs, read: readMs, write: writeMs }]);
    const { rows } = await pool.query(`SELECT
        COALESCE(sum(value) FILTER (WHERE kind IN ('crash','freeze') AND created_at > now() - interval '1 hour'), 0)::int AS bad_1h,
        COALESCE(sum(value) FILTER (WHERE kind IN ('crash','freeze') AND created_at <= now() - interval '1 hour' AND created_at > now() - interval '25 hours'), 0)::int AS bad_prev24h,
        (SELECT count(DISTINCT device_id) FROM sessions WHERE day = (now() AT TIME ZONE 'UTC')::date)::int AS active_today
      FROM health_events`);
    const r = rows[0]; const baseHourly = r.bad_prev24h / 24;
    let fleet = 'ok';
    if (r.bad_1h >= 5 && r.bad_1h > 3 * baseHourly) fleet = 'fail'; else if (r.bad_1h >= 3 && r.bad_1h > 2 * baseHourly) fleet = 'warn';
    await pool.query('INSERT INTO agent_reports (agent, status, title, details) VALUES ($1,$2,$3,$4)', ['fleet-health', fleet,
      `crashes+freezes last hour: ${r.bad_1h} (usual ${baseHourly.toFixed(1)}/h); active devices today: ${r.active_today}`, { bad_1h: r.bad_1h, bad_prev24h: r.bad_prev24h, active_today: r.active_today }]);
    await pool.query(`UPDATE agent_lease SET until = 'epoch' WHERE id = 1`); // release the short lease; the orchestrator takes its own
    let orch;
    try { orch = await runOrchestrator(pool, { respectInterval: true }); } catch (e) { orch = { lease: 'error', error: e.message }; }
    const sev = (await pool.query(`SELECT severity, count(*)::int AS n FROM agent_findings WHERE status IN ('open','approved','applied','failed') GROUP BY 1`)).rows;
    res.json({ ok: orch.lease !== 'error', fleet, db: dbStatus, db_ms: dbMs, orchestrator: orch, open_findings: Object.fromEntries(sev.map((x) => [x.severity, x.n])) });
  }));

  // ------------------------------------------------------------------ admin
  const failedAuth = limiter(15 * 60_000, 10);
  const requireAdmin = (req, res, next) => {
    const h = req.get('authorization') || '';
    const token = h.startsWith('Bearer ') ? h.slice(7) : '';
    if (token && safeEqual(token, adminToken)) return next();
    return failedAuth(req, res, () => res.status(401).json({ error: 'unauthorized' }));
  };
  const admin = express.Router();
  admin.use(limiter(60_000, 240), requireAdmin);
  admin.use((_req, res, next) => { res.set('Cache-Control', 'no-store'); next(); });

  admin.get('/stats', wrap(async (_req, res) => {
    const today = `(now() AT TIME ZONE 'UTC')::date`;
    const q = (sql, p) => pool.query(sql, p).then((r) => r.rows);
    const [tot, newToday, dau, mau, low, countries, trend, versions, ads, revenue, shares] = await Promise.all([
      q('SELECT count(*)::int AS n FROM devices'),
      q(`SELECT count(*)::int AS n FROM devices WHERE (first_seen AT TIME ZONE 'UTC')::date = ${today}`),
      q(`SELECT count(*)::int AS n FROM sessions WHERE day = ${today}`),
      q(`SELECT count(DISTINCT device_id)::int AS n FROM sessions WHERE day > ${today} - 30`),
      q('SELECT count(*)::int AS n FROM devices WHERE ram_mb <= 2048'),
      q(`SELECT COALESCE(country::text,'??') AS country, count(*)::int AS devices FROM devices GROUP BY 1 ORDER BY 2 DESC, 1 LIMIT 10`),
      q(`SELECT to_char(day,'YYYY-MM-DD') AS day, count(*)::int AS users FROM sessions WHERE day > ${today} - 14 GROUP BY day ORDER BY day`),
      q('SELECT app_version, count(*)::int AS devices FROM devices GROUP BY 1 ORDER BY 2 DESC LIMIT 5'),
      q(`SELECT event_type, count(*)::int AS n FROM ad_events WHERE created_at > now() - interval '30 days' GROUP BY 1`),
      q(`SELECT currency::text AS currency, sum(revenue_micros)::bigint AS micros FROM ad_events WHERE event_type='paid' AND created_at > now() - interval '30 days' GROUP BY 1 ORDER BY 2 DESC`),
      q(`SELECT action, channel, count(*)::int AS n FROM share_events WHERE created_at > now() - interval '30 days' GROUP BY 1, 2`),
    ]);
    const ev = Object.fromEntries(ads.map((r) => [r.event_type, r.n]));
    const sumAction = (a) => shares.filter((r) => r.action === a).reduce((t, r) => t + r.n, 0);
    const byChannel = {};
    shares.forEach((r) => { byChannel[r.channel] = (byChannel[r.channel] || 0) + r.n; });
    res.json({
      total_installs: tot[0].n, new_today: newToday[0].n, dau: dau[0].n, mau: mau[0].n,
      low_ram_devices: low[0].n, countries, dau_trend: trend, versions,
      shares_30d: {
        qr_shown: sumAction('qr_shown'), shared: sumAction('shared'), apk_shared: sumAction('apk_shared'),
        by_channel: Object.entries(byChannel).map(([channel, count]) => ({ channel, count })).sort((a, b) => b.count - a.count || a.channel.localeCompare(b.channel)),
      },
      ads_30d: { impressions: ev.impression || 0, clicks: ev.click || 0, revenue: revenue.map((r) => ({ currency: r.currency, amount: Number(r.micros) / 1e6 })) },
    });
  }));

  admin.get('/live', wrap(async (req, res) => {
    const limit = Math.min(Math.max(parseInt(req.query.limit, 10) || 50, 1), 200);
    const { rows } = await pool.query(
      `SELECT left(d.device_id::text, 8) AS id, d.country::text AS country, d.app_version, d.android_os_ver, d.ram_mb,
              s.last_at AS at, s.session_count
         FROM sessions s JOIN devices d USING (device_id) ORDER BY s.last_at DESC LIMIT $1`, [limit]);
    res.json({ pings: rows });
  }));

  admin.get('/config', wrap(async (_req, res) => {
    const row = await activeRow();
    const hist = await pool.query('SELECT version, is_active, note, created_by, created_at FROM remote_configs ORDER BY version DESC LIMIT 20');
    res.json({ config: rowToConfig(row), signing_key_fingerprint: signer.fingerprint, history: hist.rows });
  }));

  async function publish(patch, { actor, action, detail }) {
    const client = await pool.connect();
    try {
      await client.query('BEGIN');
      const cur = (await client.query('SELECT * FROM remote_configs WHERE is_active FOR UPDATE')).rows[0];
      if (patch.base_version != null && cur && patch.base_version !== cur.version) {
        await client.query('ROLLBACK');
        const err = new Error('config changed since you loaded it; reload and retry'); err.status = 409; throw err;
      }
      const next = (await client.query('SELECT COALESCE(max(version),0)+1 AS v FROM remote_configs')).rows[0].v;
      const m = { ...(cur || {}), ...patch.fields };
      await client.query('UPDATE remote_configs SET is_active = false WHERE is_active');
      const ins = await client.query(
        `INSERT INTO remote_configs (version,is_active,default_search_url,affiliate_tag,speed_dial,blacklisted_domains,admob_enabled,note,created_by,
                                     features,update_policy,ads_policy,promos,announcement)
         VALUES ($1,true,$2,$3,$4::jsonb,$5,$6,$7,$8,$9::jsonb,$10::jsonb,$11::jsonb,$12::jsonb,$13::jsonb) RETURNING *`,
        [next, m.default_search_url, m.affiliate_tag, JSON.stringify(m.speed_dial), m.blacklisted_domains, m.admob_enabled, patch.fields.note ?? null, actor,
          JSON.stringify(m.features || {}), JSON.stringify(m.update_policy || {}), JSON.stringify(m.ads_policy || {}), JSON.stringify(m.promos || []),
          m.announcement == null ? null : JSON.stringify(m.announcement)]
      );
      await client.query('INSERT INTO admin_audit (actor, action, detail) VALUES ($1,$2,$3::jsonb)', [actor, action, JSON.stringify({ ...detail, version: next })]);
      await client.query('COMMIT');
      configCache.clear();
      return ins.rows[0];
    } catch (e) { await client.query('ROLLBACK').catch(() => {}); throw e; } finally { client.release(); }
  }

  admin.post('/config', big, wrap(async (req, res) => {
    const base = req.body && req.body.base_version;
    if (base != null && !Number.isInteger(base)) throw new V.ValidationError('base_version: must be an integer', 'base_version');
    const fields = V.configPatch(Object.fromEntries(Object.entries(req.body).filter(([k]) => k !== 'base_version')));
    const row = await publish({ fields, base_version: base }, { actor: 'admin', action: 'config.publish', detail: { changed: Object.keys(fields) } });
    res.status(201).json({ config: rowToConfig(row) });
  }));

  admin.post('/config/rollback', small, wrap(async (req, res) => {
    const v = req.body && req.body.version;
    if (!Number.isInteger(v)) throw new V.ValidationError('version: must be an integer', 'version');
    const old = (await pool.query('SELECT * FROM remote_configs WHERE version = $1', [v])).rows[0];
    if (!old) return res.status(404).json({ error: 'unknown_version' });
    const fields = {
      default_search_url: old.default_search_url, affiliate_tag: old.affiliate_tag, speed_dial: old.speed_dial,
      blacklisted_domains: old.blacklisted_domains, admob_enabled: old.admob_enabled, note: `rollback to v${v}`,
      features: old.features, update_policy: old.update_policy, ads_policy: old.ads_policy, promos: old.promos, announcement: old.announcement,
    };
    const row = await publish({ fields }, { actor: 'admin', action: 'config.rollback', detail: { from: v } });
    res.status(201).json({ config: rowToConfig(row) });
  }));

  const audit = (action, detail) => pool.query('INSERT INTO admin_audit (actor, action, detail) VALUES ($1,$2,$3::jsonb)', ['admin', action, JSON.stringify(detail || {})]);
  const TODAY = `(now() AT TIME ZONE 'UTC')::date`;
  const rows = (sql, p) => pool.query(sql, p).then((r) => r.rows);

  // ---- users / devices
  const devFilter = (q) => {
    const w = [], p = [];
    if (q.q) { p.push(String(q.q).toLowerCase().replace(/[%_\\]/g, '')); w.push(`(device_id::text LIKE $${p.length} || '%' OR lower(tags::text) LIKE '%' || $${p.length} || '%')`); }
    if (q.country && /^[A-Za-z]{2}$/.test(q.country)) { p.push(q.country.toUpperCase()); w.push(`country = $${p.length}`); }
    if (q.version) { p.push(String(q.version).slice(0, 32)); w.push(`app_version = $${p.length}`); }
    if (q.blocked === '1') w.push('blocked');
    if (q.lowram === '1') w.push('ram_mb <= 2048');
    return { where: w.length ? 'WHERE ' + w.join(' AND ') : '', p };
  };
  admin.get('/devices', wrap(async (req, res) => {
    const { where, p } = devFilter(req.query);
    const limit = Math.min(Math.max(parseInt(req.query.limit, 10) || 50, 1), 200);
    const offset = Math.max(parseInt(req.query.offset, 10) || 0, 0);
    const total = (await pool.query(`SELECT count(*)::int AS n FROM devices ${where}`, p)).rows[0].n;
    const list = await rows(
      `SELECT device_id, country::text AS country, app_version, version_code, android_os_ver, ram_mb, boot_count, first_seen, last_seen, blocked, blocked_reason, tags
         FROM devices ${where} ORDER BY last_seen DESC LIMIT ${limit} OFFSET ${offset}`, p);
    res.json({ total, limit, offset, devices: list });
  }));
  admin.get('/devices/export', wrap(async (req, res) => {
    const { where, p } = devFilter(req.query);
    const list = await rows(`SELECT device_id, country::text AS country, app_version, android_os_ver, ram_mb, boot_count, first_seen, last_seen, blocked FROM devices ${where} ORDER BY last_seen DESC LIMIT 50000`, p);
    const esc = (v) => { const s = v == null ? '' : v instanceof Date ? v.toISOString() : String(v); return /^[=+\-@]/.test(s) || /[",\n]/.test(s) ? '"' + (/^[=+\-@]/.test(s) ? "'" : '') + s.replace(/"/g, '""') + '"' : s; };
    const head = 'device_id,country,app_version,android_os_ver,ram_mb,boot_count,first_seen,last_seen,blocked';
    await audit('devices.export', { rows: list.length });
    res.set({ 'Content-Type': 'text/csv; charset=utf-8', 'Content-Disposition': 'attachment; filename="yeehaw-devices.csv"' });
    res.send([head, ...list.map((r) => head.split(',').map((k) => esc(r[k])).join(','))].join('\n'));
  }));
  admin.get('/devices/:id', wrap(async (req, res) => {
    const id = req.params.id.toLowerCase();
    if (!V.UUID_RE.test(id)) throw new V.ValidationError('id: must be a UUID', 'id');
    const d = (await pool.query('SELECT * FROM devices WHERE device_id = $1', [id])).rows[0];
    if (!d) return res.status(404).json({ error: 'unknown_device' });
    const [days, ads, health, shares] = await Promise.all([
      rows(`SELECT to_char(day,'YYYY-MM-DD') AS day, session_count FROM sessions WHERE device_id=$1 ORDER BY day DESC LIMIT 30`, [id]),
      rows(`SELECT event_type, count(*)::int AS n, COALESCE(sum(revenue_micros),0)::bigint AS micros FROM ad_events WHERE device_id=$1 GROUP BY 1`, [id]),
      rows(`SELECT kind, sum(value)::bigint AS total FROM health_events WHERE device_id=$1 GROUP BY 1`, [id]),
      rows(`SELECT action, channel, count(*)::int AS n FROM share_events WHERE device_id=$1 GROUP BY 1,2`, [id]),
    ]);
    res.json({ device: d, sessions: days, ads, health: health.map((h) => ({ kind: h.kind, total: Number(h.total) })), shares });
  }));
  admin.post('/devices/:id/block', small, wrap(async (req, res) => {
    const id = req.params.id.toLowerCase();
    if (!V.UUID_RE.test(id)) throw new V.ValidationError('id: must be a UUID', 'id');
    if (typeof (req.body && req.body.blocked) !== 'boolean') throw new V.ValidationError('blocked: must be true or false', 'blocked');
    const reason = req.body.blocked ? String(req.body.reason || '').slice(0, 200) : null;
    const r = await pool.query(`UPDATE devices SET blocked=$2, blocked_reason=$3, blocked_at=CASE WHEN $2 THEN now() ELSE NULL END WHERE device_id=$1`, [id, req.body.blocked, reason]);
    if (!r.rowCount) return res.status(404).json({ error: 'unknown_device' });
    await audit(req.body.blocked ? 'device.block' : 'device.unblock', { device: id.slice(0, 8), reason });
    res.json({ ok: true });
  }));
  admin.post('/devices/:id/note', small, wrap(async (req, res) => {
    const id = req.params.id.toLowerCase();
    if (!V.UUID_RE.test(id)) throw new V.ValidationError('id: must be a UUID', 'id');
    const notes = String((req.body && req.body.notes) || '').slice(0, 500);
    const tags = Array.isArray(req.body && req.body.tags) ? [...new Set(req.body.tags.map((t) => String(t).toLowerCase().replace(/[^a-z0-9_-]/g, '').slice(0, 24)).filter(Boolean))].slice(0, 8) : [];
    const r = await pool.query('UPDATE devices SET notes=$2, tags=$3 WHERE device_id=$1', [id, notes, tags]);
    if (!r.rowCount) return res.status(404).json({ error: 'unknown_device' });
    await audit('device.note', { device: id.slice(0, 8) });
    res.json({ ok: true });
  }));
  admin.delete('/devices/:id', wrap(async (req, res) => {
    const id = req.params.id.toLowerCase();
    if (!V.UUID_RE.test(id)) throw new V.ValidationError('id: must be a UUID', 'id');
    const r = await pool.query('DELETE FROM devices WHERE device_id = $1', [id]);
    await audit('device.erase', { device: id.slice(0, 8), existed: r.rowCount > 0 });
    res.json({ ok: true, erased: r.rowCount });
  }));

  // ---- advertising analytics
  admin.get('/analytics/ads', wrap(async (req, res) => {
    const days = Math.min(Math.max(parseInt(req.query.days, 10) || 30, 1), 365);
    const w = `a.created_at > now() - ($1 || ' days')::interval`, p = [String(days)];
    const [totals, byDay, byFormat, byCountry, byNetwork, byCampaign] = await Promise.all([
      rows(`SELECT event_type, count(*)::int AS n, COALESCE(sum(revenue_micros),0)::bigint AS micros FROM ad_events a WHERE ${w} GROUP BY 1`, p),
      rows(`SELECT to_char((a.created_at AT TIME ZONE 'UTC')::date,'YYYY-MM-DD') AS day,
              count(*) FILTER (WHERE event_type='impression')::int AS impressions, count(*) FILTER (WHERE event_type='click')::int AS clicks,
              COALESCE(sum(revenue_micros) FILTER (WHERE event_type='paid'),0)::bigint AS micros
            FROM ad_events a WHERE ${w} GROUP BY 1 ORDER BY 1`, p),
      rows(`SELECT ad_format, count(*) FILTER (WHERE event_type='impression')::int AS impressions, count(*) FILTER (WHERE event_type='click')::int AS clicks,
              COALESCE(sum(revenue_micros) FILTER (WHERE event_type='paid'),0)::bigint AS micros FROM ad_events a WHERE ${w} GROUP BY 1 ORDER BY 2 DESC`, p),
      rows(`SELECT COALESCE(d.country::text,'??') AS country, count(*) FILTER (WHERE event_type='impression')::int AS impressions,
              count(*) FILTER (WHERE event_type='click')::int AS clicks, COALESCE(sum(revenue_micros) FILTER (WHERE event_type='paid'),0)::bigint AS micros
            FROM ad_events a JOIN devices d USING (device_id) WHERE ${w} GROUP BY 1 ORDER BY 4 DESC, 2 DESC LIMIT 25`, p),
      rows(`SELECT COALESCE(network,'unknown') AS network, count(*) FILTER (WHERE event_type='impression')::int AS impressions,
              COALESCE(sum(revenue_micros) FILTER (WHERE event_type='paid'),0)::bigint AS micros FROM ad_events a WHERE ${w} GROUP BY 1 ORDER BY 3 DESC, 2 DESC`, p),
      rows(`SELECT ad_unit AS campaign, count(*) FILTER (WHERE event_type='impression')::int AS impressions, count(*) FILTER (WHERE event_type='click')::int AS clicks
            FROM ad_events a WHERE ${w} AND ad_unit LIKE 'campaign:%' GROUP BY 1 ORDER BY 2 DESC LIMIT 50`, p),
    ]);
    const t = Object.fromEntries(totals.map((r) => [r.event_type, r]));
    const imp = (t.impression || {}).n || 0, clk = (t.click || {}).n || 0, micros = Number((t.paid || {}).micros || 0);
    const num = (r) => ({ ...r, micros: undefined, revenue: Number(r.micros) / 1e6, ctr: r.impressions ? (r.clicks || 0) / r.impressions : 0,
      ecpm: r.impressions ? (Number(r.micros) / 1e6) / r.impressions * 1000 : 0 });
    res.json({
      days, impressions: imp, clicks: clk, ctr: imp ? clk / imp : 0, revenue_note: 'Revenue is summed across currencies reported by the ad SDK; check the currency before comparing.',
      revenue: micros / 1e6, ecpm: imp ? micros / 1e6 / imp * 1000 : 0,
      by_day: byDay.map(num), by_format: byFormat.map(num), by_country: byCountry.map(num), by_network: byNetwork.map(num),
      by_campaign: byCampaign.map((r) => ({ campaign: r.campaign.slice(9), impressions: r.impressions, clicks: r.clicks, ctr: r.impressions ? r.clicks / r.impressions : 0 })),
    });
  }));

  // ---- global operations
  admin.get('/analytics/geo', wrap(async (_req, res) => {
    const list = await rows(
      `SELECT COALESCE(d.country::text,'??') AS country, count(*)::int AS devices,
              count(*) FILTER (WHERE d.ram_mb <= 2048)::int AS low_ram,
              count(*) FILTER (WHERE d.last_seen > now() - interval '7 days')::int AS active_7d,
              count(*) FILTER (WHERE d.first_seen > now() - interval '7 days')::int AS new_7d
         FROM devices d GROUP BY 1 ORDER BY 2 DESC, 1 LIMIT 100`);
    res.json({ countries: list });
  }));
  admin.get('/analytics/fleet', wrap(async (_req, res) => {
    const [versions, os, ram, installs, dau] = await Promise.all([
      rows(`SELECT app_version, count(*)::int AS devices FROM devices GROUP BY 1 ORDER BY 2 DESC LIMIT 12`),
      rows(`SELECT android_os_ver, count(*)::int AS devices FROM devices GROUP BY 1 ORDER BY 2 DESC LIMIT 12`),
      rows(`SELECT CASE WHEN ram_mb<=1024 THEN '<=1 GB' WHEN ram_mb<=2048 THEN '1-2 GB' WHEN ram_mb<=3072 THEN '2-3 GB' WHEN ram_mb<=4096 THEN '3-4 GB' ELSE '>4 GB' END AS tier, count(*)::int AS devices FROM devices GROUP BY 1 ORDER BY min(ram_mb)`),
      rows(`SELECT to_char((first_seen AT TIME ZONE 'UTC')::date,'YYYY-MM-DD') AS day, count(*)::int AS n FROM devices WHERE first_seen > now() - interval '30 days' GROUP BY 1 ORDER BY 1`),
      rows(`SELECT to_char(day,'YYYY-MM-DD') AS day, count(*)::int AS n FROM sessions WHERE day > ${TODAY} - 30 GROUP BY day ORDER BY day`),
    ]);
    const ret = (await rows(
      `SELECT count(*) FILTER (WHERE (d.first_seen AT TIME ZONE 'UTC')::date <= ${TODAY}-1)::int AS c1,
              count(*) FILTER (WHERE (d.first_seen AT TIME ZONE 'UTC')::date <= ${TODAY}-1 AND EXISTS (SELECT 1 FROM sessions s WHERE s.device_id=d.device_id AND s.day=(d.first_seen AT TIME ZONE 'UTC')::date+1))::int AS r1,
              count(*) FILTER (WHERE (d.first_seen AT TIME ZONE 'UTC')::date <= ${TODAY}-7)::int AS c7,
              count(*) FILTER (WHERE (d.first_seen AT TIME ZONE 'UTC')::date <= ${TODAY}-7 AND EXISTS (SELECT 1 FROM sessions s WHERE s.device_id=d.device_id AND s.day=(d.first_seen AT TIME ZONE 'UTC')::date+7))::int AS r7,
              count(*) FILTER (WHERE (d.first_seen AT TIME ZONE 'UTC')::date <= ${TODAY}-30)::int AS c30,
              count(*) FILTER (WHERE (d.first_seen AT TIME ZONE 'UTC')::date <= ${TODAY}-30 AND EXISTS (SELECT 1 FROM sessions s WHERE s.device_id=d.device_id AND s.day=(d.first_seen AT TIME ZONE 'UTC')::date+30))::int AS r30
         FROM devices d`))[0];
    const pct = (r, c) => (c ? r / c : null);
    res.json({ versions, android: os, ram_tiers: ram, installs_30d: installs, dau_30d: dau,
      retention: { d1: pct(ret.r1, ret.c1), d7: pct(ret.r7, ret.c7), d30: pct(ret.r30, ret.c30), cohort: { d1: ret.c1, d7: ret.c7, d30: ret.c30 } } });
  }));
  admin.get('/analytics/health', wrap(async (req, res) => {
    const days = Math.min(Math.max(parseInt(req.query.days, 10) || 30, 1), 365);
    const p = [String(days)];
    const [totals, trend, byVersion, reporting] = await Promise.all([
      rows(`SELECT kind, sum(value)::bigint AS total, count(DISTINCT device_id)::int AS devices FROM health_events WHERE created_at > now() - ($1 || ' days')::interval GROUP BY 1`, p),
      rows(`SELECT to_char((created_at AT TIME ZONE 'UTC')::date,'YYYY-MM-DD') AS day,
              COALESCE(sum(value) FILTER (WHERE kind='crash'),0)::bigint AS crash, COALESCE(sum(value) FILTER (WHERE kind='freeze'),0)::bigint AS freeze,
              COALESCE(sum(value) FILTER (WHERE kind='render_rescue'),0)::bigint AS render_rescue
            FROM health_events WHERE created_at > now() - ($1 || ' days')::interval GROUP BY 1 ORDER BY 1`, p),
      rows(`SELECT d.app_version, COALESCE(sum(h.value) FILTER (WHERE h.kind='crash'),0)::bigint AS crashes, count(DISTINCT d.device_id)::int AS devices
            FROM health_events h JOIN devices d USING (device_id) WHERE h.created_at > now() - ($1 || ' days')::interval GROUP BY 1 ORDER BY 2 DESC LIMIT 10`, p),
      rows(`SELECT count(DISTINCT device_id)::int AS n FROM health_events WHERE created_at > now() - ($1 || ' days')::interval`, p),
    ]);
    const t = Object.fromEntries(totals.map((r) => [r.kind, Number(r.total)]));
    res.json({
      days, reporting_devices: reporting[0].n,
      crashes: t.crash || 0, freezes: t.freeze || 0, render_rescues: t.render_rescue || 0, store_repairs: t.store_repair || 0,
      savings: { skipped_requests: t.skipped_requests || 0, est_bytes_avoided: t.est_bytes_avoided || 0, low_signal_seconds: t.low_signal_seconds || 0, data_received_bytes: t.data_received_bytes || 0 },
      trend: trend.map((r) => ({ day: r.day, crash: Number(r.crash), freeze: Number(r.freeze), render_rescue: Number(r.render_rescue) })),
      by_version: byVersion.map((r) => ({ app_version: r.app_version, crashes: Number(r.crashes), devices: r.devices })),
    });
  }));

  admin.get('/audit', wrap(async (req, res) => {
    const limit = Math.min(Math.max(parseInt(req.query.limit, 10) || 100, 1), 500);
    res.json({ entries: await rows('SELECT at, actor, action, detail FROM admin_audit ORDER BY id DESC LIMIT $1', [limit]) });
  }));
  // Subscriptions: read-only view of server-verified Pro entitlements (Google Play is the source of truth).
  admin.get('/subscriptions', wrap(async (_req, res) => {
    const [counts, list] = await Promise.all([
      rows(`SELECT state, active, count(*)::int AS n FROM entitlements GROUP BY 1, 2 ORDER BY 3 DESC`),
      rows(`SELECT left(device_id::text, 8) AS id, product_id, state, active, expires_at, verified_at FROM entitlements ORDER BY verified_at DESC LIMIT 100`),
    ]);
    res.json({
      active: counts.filter((r) => r.active).reduce((t, r) => t + r.n, 0),
      inactive: counts.filter((r) => !r.active).reduce((t, r) => t + r.n, 0),
      by_state: counts, recent: list,
      note: 'Counts come from server-side Google Play verification. Revenue is not shown: purchase amounts are not stored; use Play Console financial reports.',
    });
  }));
  admin.get('/system', wrap(async (_req, res) => {
    const t0 = Date.now();
    await pool.query('SELECT 1');
    const [size, counts, cfg] = await Promise.all([
      rows('SELECT pg_database_size(current_database())::bigint AS bytes'),
      rows(`SELECT (SELECT count(*) FROM devices)::int AS devices, (SELECT count(*) FROM sessions)::int AS sessions, (SELECT count(*) FROM ad_events)::int AS ad_events,
                   (SELECT count(*) FROM health_events)::int AS health_events, (SELECT count(*) FROM share_events)::int AS share_events, (SELECT count(*) FROM devices WHERE blocked)::int AS blocked`),
      activeRow(),
    ]);
    res.json({ db_latency_ms: Date.now() - t0, db_bytes: Number(size[0].bytes), counts: counts[0], active_config_version: cfg && cfg.version,
      signing_key_fingerprint: signer.fingerprint, node: process.version, server_time: new Date().toISOString() });
  }));

  admin.get('/agents', wrap(async (_req, res) => {
    const q = (sql) => pool.query(sql).then((r) => r.rows);
    const [latest, recent, findings, actions, runs] = await Promise.all([
      q(`SELECT DISTINCT ON (agent) agent, status, title, details, ref, created_at FROM agent_reports ORDER BY agent, created_at DESC`),
      q(`SELECT id, agent, status, title, ref, created_at FROM agent_reports ORDER BY created_at DESC LIMIT 100`),
      q(`SELECT id, fingerprint, agent, severity, title, evidence, root_cause, proposal, risk, repair, status, created_at, updated_at FROM agent_findings ORDER BY (status IN ('open','approved','applied','failed')) DESC, id DESC LIMIT 100`),
      q(`SELECT a.id, a.finding_id, a.actor, a.action, a.result, a.verification, a.evidence, a.created_at FROM agent_actions a ORDER BY a.id DESC LIMIT 100`),
      q(`SELECT id, started_at, finished_at, ok, summary FROM agent_runs ORDER BY id DESC LIMIT 20`),
    ]);
    const engine = await getEngineSettings(pool);
    res.json({ enabled: !!agentToken, engine: { ...engine, agents: ENGINE_AGENTS, permitted_repairs_available: Object.keys(ENGINE_REPAIRS) }, latest, recent, findings, actions, runs,
      pending_approvals: findings.filter((f) => f.status === 'open' && (f.risk === 'medium' || f.risk === 'high' || !f.repair)).length });
  }));

  // Engine controls: on/off, per-agent switches, how often a pass may run, which automatic repairs are permitted. Every change is audited.
  admin.put('/engine', small, wrap(async (req, res) => {
    const b = req.body || {}; const cur = await getEngineSettings(pool); const next = { ...cur };
    if (b.enabled !== undefined) { if (typeof b.enabled !== 'boolean') return res.status(400).json({ error: 'bad_enabled' }); next.enabled = b.enabled; }
    if (b.disabled_agents !== undefined) {
      if (!Array.isArray(b.disabled_agents) || b.disabled_agents.some((a) => !ENGINE_AGENTS.includes(a))) return res.status(400).json({ error: 'bad_agents', allowed: ENGINE_AGENTS });
      next.disabled_agents = [...new Set(b.disabled_agents)];
    }
    if (b.interval_minutes !== undefined) {
      if (!Number.isInteger(b.interval_minutes) || b.interval_minutes < 1 || b.interval_minutes > 1440) return res.status(400).json({ error: 'bad_interval' });
      next.interval_minutes = b.interval_minutes;
    }
    if (b.auto_repairs !== undefined) {
      if (!Array.isArray(b.auto_repairs) || b.auto_repairs.some((a) => !Object.prototype.hasOwnProperty.call(ENGINE_REPAIRS, a))) return res.status(400).json({ error: 'bad_repairs', allowed: Object.keys(ENGINE_REPAIRS) });
      next.auto_repairs = [...new Set(b.auto_repairs)];
    }
    await pool.query('UPDATE engine_settings SET enabled=$1, disabled_agents=$2, interval_minutes=$3, auto_repairs=$4, updated_at=now() WHERE id=1', [next.enabled, next.disabled_agents, next.interval_minutes, next.auto_repairs]);
    await pool.query('INSERT INTO agent_actions (finding_id, actor, action, result) VALUES (NULL,$1,$2,$3)', ['owner', 'engine settings changed', JSON.stringify({ from: { enabled: cur.enabled, disabled_agents: cur.disabled_agents, interval_minutes: cur.interval_minutes, auto_repairs: cur.auto_repairs }, to: { enabled: next.enabled, disabled_agents: next.disabled_agents, interval_minutes: next.interval_minutes, auto_repairs: next.auto_repairs } })]);
    await audit('agent.engine.settings', { enabled: next.enabled });
    res.json({ ok: true, engine: next });
  }));
  // Run one engine pass now (ignores the interval, still respects on/off, per-agent switches and the lease).
  admin.post('/engine/run', small, wrap(async (_req, res) => {
    const out = await runOrchestrator(pool);
    await audit('agent.engine.run', { lease: out.lease });
    res.json(out);
  }));

  // Roll back a prepared/verified code change: records the owner's request; the repair agent then reverts the commit on its own
  // branch (never on a protected branch) and records `rollback done`. Findings without a recorded code change cannot be rolled back.
  admin.post('/findings/:id/rollback', small, wrap(async (req, res) => {
    const id = Number(req.params.id);
    if (!Number.isSafeInteger(id)) return res.status(400).json({ error: 'bad_id' });
    const prior = (await pool.query(`SELECT evidence FROM agent_actions WHERE finding_id = $1 AND actor = 'repair' AND action LIKE 'fix prepared%' ORDER BY id DESC LIMIT 1`, [id])).rows[0];
    if (!prior) return res.status(409).json({ error: 'nothing_to_roll_back', detail: 'No code change was prepared for this finding.' });
    await pool.query('INSERT INTO agent_actions (finding_id, actor, action, result, evidence) VALUES ($1,$2,$3,$4,$5)', [id, 'owner', 'rollback requested', 'owner asked for the prepared change to be reverted', { branch: prior.evidence.branch, commit: prior.evidence.commit }]);
    await audit('agent.finding.rollback', { finding: id });
    res.status(202).json({ ok: true });
  }));
  // Resource and operating costs as last measured by the infrastructure agent. Nothing is estimated here.
  admin.get('/costs', wrap(async (_req, res) => {
    const r = (await pool.query(`SELECT details, created_at FROM agent_reports WHERE agent = 'infra-cost' ORDER BY created_at DESC LIMIT 1`)).rows[0];
    res.json(r ? { measured_at: r.created_at, costs: r.details.costs || [], note: r.details.note || null } : { measured_at: null, costs: [], note: 'No cost measurement has been reported yet.' });
  }));

  // Owner decisions on findings. Approving a finding that has an allowlisted repair lets the next orchestrator pass run and verify it.
  for (const [verb, to] of [['approve', 'approved'], ['reject', 'rejected'], ['dismiss', 'dismissed']]) {
    admin.post(`/findings/:id/${verb}`, small, wrap(async (req, res) => {
      const id = Number(req.params.id);
      if (!Number.isSafeInteger(id)) return res.status(400).json({ error: 'bad_id' });
      const r = await pool.query(`UPDATE agent_findings SET status = $2, updated_at = now() WHERE id = $1 AND status IN ('open','failed') RETURNING id, repair`, [id, to]);
      if (!r.rowCount) return res.status(404).json({ error: 'not_open' });
      if (verb === 'approve' && !r.rows[0].repair) { await pool.query(`UPDATE agent_findings SET status = 'open' WHERE id = $1`, [id]); return res.status(409).json({ error: 'no_automatic_repair', detail: 'This finding has no allowlisted automatic repair; handle it as code on the integration branch.' }); }
      await pool.query('INSERT INTO agent_actions (finding_id, actor, action, result) VALUES ($1,$2,$3,$4)', [id, 'owner', verb, 'decision recorded from the founder console']);
      await audit('agent.finding.' + verb, { finding: id });
      res.json({ ok: true, status: to });
    }));
  }

  app.use('/v1/admin', admin);

  // Founder console (single file). Served from this origin so the API needs no CORS.
  app.get(['/admin', '/admin/'], (_req, res) => {
    res.set('Cache-Control', 'no-store');
    res.sendFile(path.join(__dirname, 'admin', 'index.html'));
  });

  // Liveness without touching the database, so an external prober can check the API every few seconds without keeping the database awake.
  app.get('/livez', (_req, res) => res.json({ ok: true, live: true }));
  app.get('/healthz', wrap(async (_req, res) => { await pool.query('SELECT 1'); res.json({ ok: true }); }));
  app.get('/', (_req, res) => res.json({ service: 'yeehaw-console-api', ok: true }));

  app.use((_req, res) => res.status(404).json({ error: 'not_found' }));
  // eslint-disable-next-line no-unused-vars
  app.use((err, _req, res, _next) => {
    if (err instanceof V.ValidationError) return res.status(400).json({ error: 'invalid_request', message: err.message, field: err.field });
    if (err.type === 'entity.parse.failed') return res.status(400).json({ error: 'invalid_json' });
    if (err.type === 'entity.too.large') return res.status(413).json({ error: 'payload_too_large' });
    if (err.status === 409) return res.status(409).json({ error: 'conflict', message: err.message });
    // Any other client error raised by a library or by the serverless runtime (bad path encoding, runtime body parsing) is the caller's fault, not a 500.
    const st = err.status || err.statusCode;
    if (Number.isInteger(st) && st >= 400 && st < 500) return res.status(st).json({ error: st === 400 ? 'invalid_request' : 'bad_request' });
    console.error('[error]', err);
    res.status(500).json({ error: 'server_error' });
  });

  return app;
}

module.exports = { createApp };

if (require.main === module) {
  (async () => {
    const pool = makePool();
    await migrate(pool);
    const app = createApp({
      pool,
      adminToken: process.env.ADMIN_TOKEN,
      agentToken: process.env.AGENT_TOKEN || null,
      signingKeyPem: process.env.CONFIG_SIGNING_KEY_PEM,
      trustProxy: Number(process.env.TRUST_PROXY ?? 1),
      playVerify: makePlayVerifier({ serviceAccountJson: process.env.PLAY_SERVICE_ACCOUNT_JSON, packageName: process.env.PLAY_PACKAGE_NAME || 'app.yehaw.browser' }),
    });
    const port = Number(process.env.PORT || 8080);
    const server = app.listen(port, () => console.log(`yeehaw-console-api listening on :${port}`));
    const stop = () => server.close(() => pool.end().then(() => process.exit(0)));
    process.on('SIGTERM', stop); process.on('SIGINT', stop);
  })().catch((e) => { console.error(e); process.exit(1); });
}
