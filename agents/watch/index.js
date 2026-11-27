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
    if (r.status === 403) return { ok: true, blocked: true, note: `${name}: HTTP 403 (access protection; availability unverified)` };
    if (kind === 'api-live') {
      if (r.status === 200) { const j = await r.json().catch(() => null); return j && j.ok === true ? { ok: true } : { ok: false, note: `${name}: unhealthy body` }; }
      // Older API builds have no /livez: a JSON 404 from the API still proves the function is serving.
      if (r.status === 404 && /json/i.test(r.headers.get('content-type') || '')) return { ok: true, note: `${name}: /livez not deployed yet; API answered` };
      return { ok: false, note: `${name}: HTTP ${r.status}` };
    }
    if (kind === 'api-db') {
      const j = r.status === 200 ? await r.json().catch(() => null) : null;
      return j && j.ok === true ? { ok: true } : { ok: false, note: `${name}: HTTP ${r.status}` };
    }
    return r.ok ? { ok: true } : { ok: false, note: `${name}: HTTP ${r.status}` };
  } catch (e) { return { ok: false, note: `${name}: ${e.message}` }; }
}

// Pure decision: given previous state and the new probe results, what (if anything) must be reported?
export function decide(prev, results, now) {
  const bad = results.filter((r) => !r.ok).map((r) => r.note);
  const warn = results.filter((r) => r.ok && r.blocked).map((r) => r.note);
  const status = bad.length ? 'fail' : 'ok';
  const changed = status !== (prev.status || 'ok');
  const since = changed ? now : (prev.since || now);
  const stale = status === 'fail' && now - (prev.lastReportedAt || 0) >= REREPORT_MS;
  const report = changed || stale
    ? { agent: 'watch', status, title: status === 'fail' ? `${bad.length} of ${results.length} live probes failing` : 'live probes recovered', details: { bad, warn, since: new Date(since).toISOString() } }
    : null;
  return { next: { status, since, lastReportedAt: report ? now : (prev.lastReportedAt || 0), lastRunAt: now, bad, warn }, report };
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
  const token = String(env.AGENT_TOKEN || '').trim();
  if (report && token && !/[^\x21-\x7e]/.test(token)) {
    try {
      const r = await fetchFn(base + '/v1/agent/report', { method: 'POST', headers: { authorization: 'Bearer ' + token, 'content-type': 'application/json', 'user-agent': UA }, body: JSON.stringify(report), signal: sig() });
      if (r.status !== 201) { next.lastReportedAt = prev.lastReportedAt || 0; next.deliveryError = `HTTP ${r.status}`; } else delete next.deliveryError;
    } catch (e) { next.lastReportedAt = prev.lastReportedAt || 0; next.deliveryError = e.message; }
  } else if (report) { next.lastReportedAt = prev.lastReportedAt || 0; next.deliveryError = 'AGENT_TOKEN missing or unsendable'; }
  await storage.put('state', next);
  return { next, report };
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
    const s = (await this.state.storage.get('state')) || {};
    const alarm = await this.state.storage.getAlarm();
    return Response.json({ running: alarm != null, status: s.status || 'unknown', since: s.since ? new Date(s.since).toISOString() : null,
      lastRunAt: s.lastRunAt ? new Date(s.lastRunAt).toISOString() : null, bad: s.bad || [], warn: s.warn || [], deliveryError: s.deliveryError || null });
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
