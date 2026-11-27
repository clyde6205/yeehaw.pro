// Always-on scheduler for the maintenance agents. Runs on Cloudflare Workers Cron Triggers (free plan), independent of GitHub.
// Every 5 minutes: probe the public site and API, ask the API to run its own fleet/database/orchestrator pass, and judge the
// answers (not just HTTP 200). Every report POST is checked; a failed delivery makes the run fail and is logged.
// Secrets: AGENT_TOKEN (wrangler secret). Variable: CONSOLE_URL. It holds no admin power and no database credentials.
const SITE = ['https://yeehaw.pro/', 'https://yeehaw.pro/privacy/', 'https://yeehaw.pro/support/'];
const UA = 'YeeHawAgents/1.0 (+https://yeehaw.pro/support/; scheduled health check)';
const TIMEOUT_MS = 10000;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const sig = (ms = TIMEOUT_MS) => (typeof AbortSignal !== 'undefined' && AbortSignal.timeout ? AbortSignal.timeout(ms) : undefined);

// GET/POST with a timeout and bounded retries on network errors and 5xx/429. Never retries other 4xx.
export async function withRetry(fetchFn, url, opts = {}, tries = 3, wait = 400) {
  let last;
  for (let i = 0; i < tries; i++) {
    try {
      const r = await fetchFn(url, { ...opts, signal: sig() });
      if (r.status < 500 && r.status !== 429) return r;
      last = new Error(`HTTP ${r.status}`); last.res = r;
    } catch (e) { last = e; }
    if (i < tries - 1) await sleep(wait * (i + 1));
  }
  if (last.res) return last.res;
  throw last;
}

export async function runAgents(env, fetchFn = fetch, opts = {}) {
  const base = String(env.CONSOLE_URL || '').replace(/\/$/, '');
  if (!base || !env.AGENT_TOKEN) return { ok: false, error: 'CONSOLE_URL and AGENT_TOKEN must be set' };
  const wait = opts.wait ?? 400;
  const auth = { authorization: 'Bearer ' + env.AGENT_TOKEN, 'content-type': 'application/json', 'user-agent': UA };
  const delivery = [];
  const send = async (agent, status, title, details = {}) => {
    try {
      const r = await withRetry(fetchFn, base + '/v1/agent/report', { method: 'POST', headers: auth, body: JSON.stringify({ agent, status, title: String(title).slice(0, 200), details }) }, 3, wait);
      if (r.status !== 201) { delivery.push(`${agent}: HTTP ${r.status}`); console.error(`[agents] report ${agent} not accepted: HTTP ${r.status}`); return false; }
      return true;
    } catch (e) { delivery.push(`${agent}: ${e.message}`); console.error(`[agents] report ${agent} failed: ${e.message}`); return false; }
  };

  // 1. public endpoints, judged by content where we can
  const bad = [];
  const probes = [...SITE.map((u) => [u, 'page']), [base + '/healthz', 'api']];
  for (const [url, kind] of probes) {
    try {
      const r = await withRetry(fetchFn, url, { redirect: 'follow', headers: { 'user-agent': UA } }, 2, wait);
      if (!r.ok) { bad.push(`${url} -> HTTP ${r.status}${r.status === 403 ? ' (possibly bot protection, not an outage)' : ''}`); continue; }
      if (kind === 'api') {
        const j = await r.json().catch(() => null);
        if (!j || j.ok !== true) bad.push(`${url} -> unhealthy body`);
      }
    } catch (e) { bad.push(`${url} -> ${e.message}`); }
  }
  await send('site-health', bad.length ? 'fail' : 'ok', bad.length ? `${bad.length} of ${probes.length} endpoints failing` : `${probes.length} of ${probes.length} endpoints healthy`, { bad });

  // 2. the orchestrator tick: interpret what it says
  let tick = { status: 'not run' }; let tickOk = false;
  try {
    const r = await withRetry(fetchFn, base + '/v1/agent/tick', { method: 'POST', headers: auth, body: '{}' }, 2, wait);
    const j = await r.json().catch(() => null);
    if (r.status === 202) { tick = { status: 'skipped: another run holds the lease' }; tickOk = true; }
    else if (!r.ok || !j) { tick = { status: `HTTP ${r.status}` }; await send('scheduler', 'fail', `agent tick failed: HTTP ${r.status}`, { http: r.status }); }
    else {
      tick = { status: 'ran', fleet: j.fleet, db: j.db, db_ms: j.db_ms, open_findings: j.open_findings };
      const problems = [];
      if (j.ok !== true) problems.push('tick ok=false');
      if (j.db !== 'ok') problems.push(`database ${j.db}`);
      if (j.fleet === 'fail') problems.push('fleet health fail');
      const errs = j.orchestrator && j.orchestrator.summary && j.orchestrator.summary.errors;
      if (errs && errs.length) problems.push(`${errs.length} specialist error(s)`);
      tickOk = problems.length === 0;
      await send('scheduler', tickOk ? 'ok' : (j.db !== 'ok' || j.fleet === 'fail' ? 'fail' : 'warn'),
        tickOk ? `tick ok: db ${j.db_ms ?? '?'} ms, ${Object.values(j.open_findings || {}).reduce((a, b) => a + Number(b || 0), 0)} open finding(s)` : `tick problems: ${problems.join(', ')}`, tick);
    }
  } catch (e) { tick = { status: e.message }; console.error('[agents] tick failed: ' + e.message); }

  // 3. if reports could not be delivered, say so loudly in the Worker log (the console cannot show it if it is unreachable)
  const ok = bad.length === 0 && tickOk && delivery.length === 0;
  if (!ok) console.error('[agents] run not healthy', JSON.stringify({ bad, tick, delivery }));
  return { ok, bad, tick, delivery };
}

export default {
  async scheduled(_event, env, ctx) { ctx.waitUntil(runAgents(env)); },
};
