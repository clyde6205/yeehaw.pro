// Unattended, read-only collection of infrastructure usage/health from Vercel, Cloudflare and Neon, then analysis + publish to the console.
// Runs from the scheduled GitHub Actions job (free on a public repo). Each provider needs ONE read-only credential stored as a repository secret;
// a missing credential is reported honestly (exactly which permission is missing) and never blocks the other providers. Nothing here changes infrastructure.
import { analyzeVercel, analyzeCloudflare, analyzeNeon, publish } from './analyze.mjs';

export const NEEDS = {
  vercel: { env: 'VERCEL_TOKEN', how: 'Vercel: create a token for a team member whose role is Viewer (read-only) on team yeehaw; store it as the repository secret VERCEL_TOKEN.' },
  cloudflare: { env: 'CLOUDFLARE_API_TOKEN', how: 'Cloudflare: create an API token with ONLY "Workers Scripts: Read" on the account; store it as the repository secret CLOUDFLARE_API_TOKEN.' },
  neon: { env: 'NEON_API_KEY', how: 'Neon: create an API key for a member with the read-only (viewer) role, or a project-scoped key; store it as the repository secret NEON_API_KEY.' },
};
const get = async (fetchFn, url, token) => { const r = await fetchFn(url, { headers: { authorization: 'Bearer ' + token, accept: 'application/json' } }); if (!r.ok) throw new Error(`HTTP ${r.status} from ${new URL(url).host}`); return r.json(); };

export async function collectVercel({ fetchFn, token, projectId, teamId, alias }) {
  const q = `projectId=${projectId}&teamId=${teamId}`;
  const list = await get(fetchFn, `https://api.vercel.com/v6/deployments?${q}&target=production&limit=20`, token);
  const deployments = (list.deployments || []).map((d) => ({ id: d.uid, state: d.state || d.readyState, target: d.target, createdAt: d.created || d.createdAt, buildingAt: d.buildingAt, ready: d.ready, meta: d.meta || {} }));
  const ready = deployments.filter((d) => d.state === 'READY').sort((a, b) => b.createdAt - a.createdAt)[0];
  let serving = null;
  if (ready) { const a = await get(fetchFn, `https://api.vercel.com/v2/deployments/${ready.id}/aliases?teamId=${teamId}`, token); serving = { ...ready, alias: (a.aliases || []).map((x) => x.alias) }; }
  return { serving, deployments };
}
export async function collectCloudflare({ fetchFn, token, accountId, workers }) {
  const schedules = {};
  for (const w of workers) { const j = await get(fetchFn, `https://api.cloudflare.com/client/v4/accounts/${accountId}/workers/scripts/${w}/schedules`, token); schedules[w] = (j.result && j.result.schedules) || []; }
  return { schedules };
}
export async function collectNeon({ fetchFn, token, projectId }) {
  const j = (await get(fetchFn, `https://console.neon.tech/api/v2/projects/${projectId}`, token)).project || {};
  return { compute_time_seconds: j.compute_time_seconds || 0, active_time_seconds: j.active_time_seconds || 0, data_transfer_bytes: j.data_transfer_bytes || 0, consumption_period_start: j.consumption_period_start || new Date().toISOString(),
    subscription_type: j.subscription_type || 'unknown', autoscaling_limit_max_cu: (j.default_endpoint_settings && j.default_endpoint_settings.autoscaling_limit_max_cu) || 0.25 };
}

/** env: process.env-like. Returns { ran:[], skipped:[{provider, missing, how}], failed:[{provider,error}], findings, costs }. */
export async function collectAll({ env, fetchFn = fetch, now = Date.now(), cfg }) {
  const out = { ran: [], skipped: [], failed: [], findings: [], costs: [] };
  const step = async (provider, fn) => { const n = NEEDS[provider]; if (!env[n.env]) { out.skipped.push({ provider, missing: n.env, how: n.how }); return; }
    try { await fn(env[n.env]); out.ran.push(provider); } catch (e) { out.failed.push({ provider, error: e.message }); } };
  await step('vercel', async (token) => { const d = await collectVercel({ fetchFn, token, ...cfg.vercel }); out.findings.push(...analyzeVercel({ ...d, now, expect: { alias: cfg.vercel.alias, headSha: cfg.headSha } })); });
  await step('cloudflare', async (token) => { const d = await collectCloudflare({ fetchFn, token, ...cfg.cloudflare }); out.findings.push(...analyzeCloudflare({ schedules: d.schedules })); });
  await step('neon', async (token) => { const a = analyzeNeon(await collectNeon({ fetchFn, token, ...cfg.neon }), now); out.findings.push(...a.findings); out.costs.push(...a.costs); });
  return out;
}

export const DEFAULT_CFG = { vercel: { projectId: 'prj_T0pJqK224bTHRzzWa4yfSS4TzA5K', teamId: 'team_8KLpbWaAYahCzTTclfbBwGUt', alias: 'api.yeehaw.pro' },
  cloudflare: { accountId: 'a46b816992b2e9d26f6fa6ceb60c3ec3', workers: ['yeehaw-site', 'yeehaw-agents'] }, neon: { projectId: 'rapid-hat-12220373' } };

if (process.argv[1] && process.argv[1].endsWith('collect.mjs')) {
  const base = process.env.CONSOLE_URL, token = process.env.AGENT_TOKEN;
  const r = await collectAll({ env: process.env, cfg: { ...DEFAULT_CFG, headSha: process.env.GITHUB_SHA } });
  console.log(JSON.stringify({ ran: r.ran, skipped: r.skipped.map((s) => s.provider + ' needs ' + s.missing), failed: r.failed, findings: r.findings.length, costs: r.costs.length }));
  if (base && token) {
    await publish({ base, token, findings: r.findings, costs: r.costs.length ? r.costs : null });
    const h = { 'content-type': 'application/json', authorization: 'Bearer ' + token };
    const status = r.failed.length ? 'fail' : r.skipped.length ? 'warn' : 'ok';
    await fetch(base + '/v1/agent/report', { method: 'POST', headers: h, body: JSON.stringify({ agent: 'infra-collect', status, title: `infrastructure collection: ${r.ran.length} provider(s) read, ${r.skipped.length} not configured, ${r.failed.length} failed`, details: { ran: r.ran, skipped: r.skipped, failed: r.failed } }) });
  }
}
