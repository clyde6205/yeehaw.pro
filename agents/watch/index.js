// Always-on watcher. NOT a cron trigger and NOT a clock-driven job on a server: a Durable Object that wakes itself with its own
// alarm every 30 s, probes the public site and the API, and writes to the founder console only when something CHANGES (or every
// 15 min while something stays broken). Normal "all fine" periods write nothing, so the database can sleep (free-tier compute).
// The database itself is probed rarely (every 30 min) and by real events inside the API (crash/freeze reports).
// Secrets: AGENT_TOKEN. Variable: CONSOLE_URL. No admin power, no database credentials.
const SITE = ['https://yeehaw.pro/', 'https://yeehaw.pro/privacy/', 'https://yeehaw.pro/support/'];
const UA = 'YeeHawWatch/1.0 (+https://yeehaw.pro/support/; availability probe)';
const EVERY_MS = 30_000, REREPORT_MS = 15 * 60_000, DB_EVERY_MS = 30 * 60_000, TIMEOUT_MS = 8000;

const sig = () => (typeof AbortSignal !== 'undefined' && AbortSignal.timeout ? AbortSignal.timeout(TIMEOUT_MS) : undefined);

// One probe -> { ok, note }. A 403 is access protection, never a confirmed outage (kept as 'blocked').
export async function probe(fetchFn, name, url, kind) {
  try {
    const r = await fetchFn(url, { redirect: 'follow', headers: { 'user-agent': UA }, signal: sig() });
    const ct = String((r.headers && r.headers.get && r.headers.get('content-type')) || '');
    const html = /text\/html/i.test(ct);
    const base = { name, kind, status: r.status, html };
    if (r.status === 403) return { ...base, ok: true, blocked: true, note: `${name}: HTTP 403 (access protection; availability unverified)` };
    if (kind === 'api-live') {
      if (r.status === 200 && !html) { const j = await r.json().catch(() => null); return j && j.ok === true ? { ...base, ok: true } : { ...base, ok: false, note: `${name}: unhealthy body` }; }
      // Older API builds have no /livez: a JSON 404 from the API still proves the function is serving.
      if (r.status === 404 && /json/i.test(ct)) return { ...base, ok: true, note: `${name}: /livez not deployed yet; API answered` };
      return { ...base, ok: false, note: html ? `${name}: HTTP ${r.status} returned an HTML page where the API must answer JSON` : `${name}: HTTP ${r.status}` };
    }
    if (kind === 'api-db') {
      const j = r.status === 200 && !html ? await r.json().catch(() => null) : null;
      return j && j.ok === true ? { ...base, ok: true } : { ...base, ok: false, note: html ? `${name}: HTTP ${r.status} returned an HTML page where the API must answer JSON` : `${name}: HTTP ${r.status}` };
    }
    return r.ok ? { ...base, ok: true } : { ...base, ok: false, note: `${name}: HTTP ${r.status}` };
  } catch (e) { return { name, kind, status: 0, html: false, ok: false, note: `${name}: ${e.message}` }; }
}

// Diagnosis: turn raw probe results into a classified incident with a probable cause and a next step.
// Pure function: it only reads the probe results. It never claims more than the evidence shows.
export function classify(results) {
  const page = results.filter((r) => r.kind === 'page');
  const api = results.filter((r) => r.kind === 'api-live' || r.kind === 'api-db');
  const pageBad = page.filter((r) => !r.ok), apiBad = api.filter((r) => !r.ok);
  if (!pageBad.length && !apiBad.length) return null;
  const apiHtml = apiBad.filter((r) => r.html);
  if (apiHtml.length) return { kind: 'wrong-deployment', scope: 'api', severity: 'critical',
    cause: 'api.yeehaw.pro is answering with an HTML page instead of JSON. The API deployment has most likely been replaced by the static site (Vercel Root Directory not console/backend) or the domain points at the wrong project.',
    evidence: apiHtml.map((r) => r.note), next: 'Check the Vercel project Root Directory and which deployment api.yeehaw.pro is assigned to; roll back or redeploy the last good API deployment from console/backend.' };
  if (apiBad.length && pageBad.length) return { kind: 'platform-outage', scope: 'site+api', severity: 'critical',
    cause: 'Both the website and the API are failing, which points at something shared (Cloudflare, DNS or the network path) rather than one deployment.',
    evidence: [...pageBad, ...apiBad].map((r) => r.note), next: 'Check Cloudflare status, DNS records for yeehaw.pro and api.yeehaw.pro, and whether the Worker yeehaw-site is serving.' };
  if (apiBad.length) { const dbOnly = apiBad.every((r) => r.kind === 'api-db');
    return dbOnly ? { kind: 'database-outage', scope: 'database', severity: 'high', cause: 'The API process is up (liveness passes) but its database check fails.', evidence: apiBad.map((r) => r.note), next: 'Check Neon project status, connection limits and credentials.' }
      : { kind: 'api-outage', scope: 'api', severity: 'critical', cause: 'The website is up but the API is failing.', evidence: apiBad.map((r) => r.note), next: 'Check the latest Vercel deployment state and runtime logs for yeehaw-api.' }; }
  return { kind: 'website-outage', scope: 'site', severity: 'high', cause: 'The API is up but website pages are failing; the API is not the cause.',
    evidence: pageBad.map((r) => r.note), next: 'Check the Cloudflare Worker yeehaw-site and its custom domains.' };
}

// Pure decision: given previous state and the new probe results, what (if anything) must be reported?
export function decide(prev, results, now) {
  const bad = results.filter((r) => !r.ok).map((r) => r.note);
  const warn = results.filter((r) => r.ok && r.blocked).map((r) => r.note);
  const status = bad.length ? 'fail' : 'ok';
  const changed = status !== (prev.status || 'ok');
  const since = changed ? now : (prev.since || now);
  const stale = status === 'fail' && now - (prev.lastReportedAt || 0) >= REREPORT_MS;
  const incident = classify(results);
  const report = changed || stale
    ? { agent: 'watch', status, title: status === 'fail' ? (incident ? `${incident.kind}: ${incident.scope}` : `${bad.length} of ${results.length} live probes failing`) : 'live probes recovered',
        details: { bad, warn, since: new Date(since).toISOString(), ...(incident ? { incident } : {}) } }
    : null;
  return { next: { status, since, lastReportedAt: report ? now : (prev.lastReportedAt || 0), lastRunAt: now, bad, warn, incident: incident ? incident.kind : null }, report };
}

export async function runOnce(env, storage, fetchFn = fetch, now = Date.now()) {
  const base = String(env.CONSOLE_URL || '').replace(/\/$/, '');
  const prev = (await storage.get('state')) || {};
  const probes = SITE.map((u) => probe(fetchFn, u.replace('https://', ''), u, 'page'));
  probes.push(probe(fetchFn, 'api /livez', base + '/livez', 'api-live'));
  const dueDb = prev.lastDbAt == null || now - prev.lastDbAt >= DB_EVERY_MS;
  if (dueDb) probes.push(probe(fetchFn, 'api /healthz (database)', base + '/healthz', 'api-db'));
  const results = await Promise.all(probes);
  const { next, report } = decide(prev, results, now);
  if (dueDb) next.lastDbAt = now; else next.lastDbAt = prev.lastDbAt;
  // Delivery with a local queue: if the console cannot take a report (API down, reporting disabled), keep it and retry later.
  const queue = ((await storage.get('queue')) || []).slice(-49);
  if (report) queue.push({ at: now, report });
  const token = String(env.AGENT_TOKEN || '').trim();
  const keep = [];
  if (queue.length) {
    if (!token || /[^\x21-\x7e]/.test(token)) { keep.push(...queue); next.deliveryError = 'AGENT_TOKEN missing or unsendable'; }
    else {
      let blocked = false;
      for (const item of queue) {
        if (blocked) { keep.push(item); continue; }
        try {
          const r = await fetchFn(base + '/v1/agent/report', { method: 'POST', headers: { authorization: 'Bearer ' + token, 'content-type': 'application/json', 'user-agent': UA },
            body: JSON.stringify({ ...item.report, details: { ...item.report.details, observed_at: new Date(item.at).toISOString(), delayed: item.at !== now } }), signal: sig() });
          if (r.status !== 201) { blocked = true; keep.push(item); next.deliveryError = `HTTP ${r.status}`; }
        } catch (e) { blocked = true; keep.push(item); next.deliveryError = e.message; }
      }
      if (!blocked) delete next.deliveryError;
    }
  } else delete next.deliveryError;
  next.queued = keep.length;
  next.lastReportedAt = keep.some((k) => k.at === now) ? (prev.lastReportedAt || 0) : (report ? now : next.lastReportedAt);
  await storage.put('queue', keep);
  await storage.put('state', next);
  return { next, report };
}

/** Proves, from inside the Worker and with the Worker's own secret, that the console accepts this Worker's AGENT_TOKEN.
 *  Rate limited (one send per 10 minutes) so the public URL cannot be used to flood the console. Never returns the token. */
export async function selfTest(env, storage, fetchFn = fetch, now = Date.now()) {
  const last = (await storage.get('selftestAt')) || 0;
  if (now - last < 10 * 60_000) return { sent: false, reason: 'rate limited: one self-test per 10 minutes', retryInSeconds: Math.ceil((10 * 60_000 - (now - last)) / 1000) };
  const token = String(env.AGENT_TOKEN || '').trim();
  if (!token || /[^\x21-\x7e]/.test(token)) return { sent: false, reason: 'AGENT_TOKEN missing or unsendable in the Worker' };
  await storage.put('selftestAt', now);
  const base = String(env.CONSOLE_URL || '').replace(/\/$/, '');
  try {
    const r = await fetchFn(base + '/v1/agent/report', { method: 'POST', headers: { authorization: 'Bearer ' + token, 'content-type': 'application/json', 'user-agent': UA },
      body: JSON.stringify({ agent: 'watch-selftest', status: 'ok', title: 'Worker self-test: the console accepted this Worker\'s token', ref: 'selftest-' + now, details: { at: new Date(now).toISOString() } }), signal: sig() });
    return { sent: true, httpStatus: r.status, accepted: r.status === 201 };
  } catch (e) { return { sent: true, accepted: false, error: e.message }; }
}

export class Watch {
  constructor(state, env) { this.state = state; this.env = env; }
  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === '/start' || url.pathname === '/') {
      const at = await this.state.storage.getAlarm();
      if (at == null) await this.state.storage.setAlarm(Date.now() + 1000);
      return Response.json({ running: true, nextAlarm: at ?? 'soon' });
    }
    if (url.pathname === '/selftest') return Response.json(await selfTest(this.env, this.state.storage));
    const s = (await this.state.storage.get('state')) || {};
    const alarm = await this.state.storage.getAlarm();
    return Response.json({ running: alarm != null, status: s.status || 'unknown', since: s.since ? new Date(s.since).toISOString() : null,
      lastRunAt: s.lastRunAt ? new Date(s.lastRunAt).toISOString() : null, bad: s.bad || [], warn: s.warn || [], incident: s.incident || null, queued: s.queued || 0, deliveryError: s.deliveryError || null });
  }
  async alarm() {
    try { await runOnce(this.env, this.state.storage); } catch (e) { console.error('[watch] run failed: ' + e.message); }
    await this.state.storage.setAlarm(Date.now() + EVERY_MS); // always re-arm, even after an error
  }
}

export default {
  async fetch(request, env) {
    const id = env.WATCH.idFromName('main');
    const stub = env.WATCH.get(id);
    return stub.fetch(request);
  },
};
