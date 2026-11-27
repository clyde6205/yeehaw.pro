'use strict';
// Shared helpers for the maintenance agents. Node 20+, no dependencies.
const CONSOLE_URL = (process.env.CONSOLE_URL || '').replace(/\/$/, '');
const AGENT_TOKEN = process.env.AGENT_TOKEN || '';
const GH = process.env.GITHUB_REPOSITORY || 'clyde6205/yeehaw.pro';
const GH_TOKEN = process.env.GITHUB_TOKEN || process.env.GH_TOKEN || '';

async function gh(path, opts = {}) {
  const r = await fetch('https://api.github.com/repos/' + GH + path, {
    ...opts,
    headers: { accept: 'application/vnd.github+json', ...(GH_TOKEN ? { authorization: 'Bearer ' + GH_TOKEN } : {}), ...(opts.headers || {}) },
  });
  return r;
}

/** Report one result to the founder console. Never throws; says plainly when reporting is not configured. */
async function report(agent, status, title, details = {}, ref = null) {
  const line = `[${agent}] ${status.toUpperCase()}: ${title}`;
  console.log(line);
  if (process.env.GITHUB_STEP_SUMMARY) require('fs').appendFileSync(process.env.GITHUB_STEP_SUMMARY, `- ${line}\n`);
  if (!CONSOLE_URL || !AGENT_TOKEN) { console.log(`[${agent}] console reporting skipped: CONSOLE_URL / AGENT_TOKEN not set`); return false; }
  try {
    const r = await fetch(CONSOLE_URL + '/v1/agent/report', { method: 'POST', headers: { 'content-type': 'application/json', authorization: 'Bearer ' + AGENT_TOKEN }, body: JSON.stringify({ agent, status, title, details, ref }) });
    if (!r.ok) { console.log(`[${agent}] console rejected the report: HTTP ${r.status}`); return false; }
    return true;
  } catch (e) { console.log(`[${agent}] console unreachable: ${e.message}`); return false; }
}
module.exports = { report, gh, GH, GH_TOKEN };
