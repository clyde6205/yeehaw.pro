// Always-on scheduler for the maintenance agents. Runs on Cloudflare Workers Cron Triggers (free plan), independent of GitHub.
// Every 5 minutes: check the public site and API, ask the API to run its own fleet/database health agent, report results.
// Secrets: AGENT_TOKEN (wrangler secret). Variable: CONSOLE_URL. It holds no admin power and no database credentials.
const SITE = [
  ['https://yeehaw.pro/', 'page'],
  ['https://yeehaw.pro/privacy/', 'page'],
  ['https://yeehaw.pro/support/', 'page'],
];

export async function runAgents(env, fetchFn = fetch) {
  const base = String(env.CONSOLE_URL || '').replace(/\/$/, '');
  const auth = { authorization: 'Bearer ' + env.AGENT_TOKEN, 'content-type': 'application/json' };
  if (!base || !env.AGENT_TOKEN) return { ok: false, error: 'CONSOLE_URL and AGENT_TOKEN must be set' };
  const send = (agent, status, title, details = {}) =>
    fetchFn(base + '/v1/agent/report', { method: 'POST', headers: auth, body: JSON.stringify({ agent, status, title, details }) });

  const bad = [];
  const probes = [...SITE.map(([u]) => [u]), [base + '/healthz', 'api']];
  for (const [url, kind] of probes) {
    try {
      const r = await fetchFn(url, { redirect: 'follow', signal: AbortSignal.timeout(10000) });
      let ok = r.ok;
      if (ok && kind === 'api') ok = (await r.json()).ok === true;
      if (!ok) bad.push(`${url} -> HTTP ${r.status}`);
    } catch (e) { bad.push(`${url} -> ${e.message}`); }
  }
  await send('site-health', bad.length ? 'fail' : 'ok', bad.length ? `${bad.length} of ${probes.length} endpoints failing` : `${probes.length} of ${probes.length} endpoints healthy`, { bad });

  let tick = 'not run';
  try {
    const r = await fetchFn(base + '/v1/agent/tick', { method: 'POST', headers: auth });
    tick = r.ok ? 'ok' : `HTTP ${r.status}`;
    if (!r.ok) await send('scheduler', 'fail', `fleet/database health tick failed: HTTP ${r.status}`);
  } catch (e) { tick = e.message; }
  return { ok: bad.length === 0 && tick === 'ok', bad, tick };
}

export default {
  async scheduled(_event, env, ctx) { ctx.waitUntil(runAgents(env)); },
};
