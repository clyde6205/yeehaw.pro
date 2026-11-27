// Security agent tooling. Read-only: it probes, it never changes anything. Findings use the same shape as every other agent
// ({fingerprint, agent:'security', severity, title, evidence, root_cause, proposal, risk}) and are published through the agent API.
//   scanApi   : active checks of a running API (authentication, authorization, exposure, headers, error handling, rate limiting)
//   scanSecrets: secret patterns in tracked files (secret protection)
//   parseAudit : `npm audit --json` output -> dependency findings
//   scanAndroidManifest: Android attack-surface checks used by the Android agent too
const F = (fingerprint, severity, title, evidence, root_cause, proposal, risk = 'medium') => ({ fingerprint: 'security:' + fingerprint, agent: 'security', severity, title, evidence, root_cause, proposal, risk });

const ADMIN_PATHS = ['/v1/admin/agents', '/v1/admin/devices', '/v1/admin/config', '/v1/admin/costs', '/v1/admin/engine'];
const AGENT_POST = ['/v1/agent/report', '/v1/agent/finding', '/v1/agent/action'];
const LEAK_PATHS = ['/.env', '/.git/config', '/package.json', '/server.js', '/schema.sql', '/vercel.json', '/node_modules/.package-lock.json', '/test/api.test.js'];

export async function scanApi(base, { fetchFn = fetch, burst = 0, https = base.startsWith('https://') } = {}) {
  base = base.replace(/\/$/, '');
  const checks = [], findings = [];
  const rec = (name, ok, detail, finding) => { checks.push({ name, ok, detail }); if (!ok && finding) findings.push(finding); };
  const req = async (path, o = {}) => { try { const r = await fetchFn(base + path, { redirect: 'manual', ...o }); return { r, text: await r.text().catch(() => '') }; } catch (e) { return { err: e.message }; } };

  // Authentication / authorization: every admin route must refuse missing and wrong credentials.
  for (const p of ADMIN_PATHS) for (const [label, headers] of [['no credentials', {}], ['wrong token', { authorization: 'Bearer not-a-real-token' }]]) {
    const { r, err } = await req(p, { headers });
    const ok = !!r && r.status === 401;
    rec(`auth: GET ${p} with ${label} -> 401`, ok, err || `HTTP ${r && r.status}`, F(`auth:${p}:${label.replace(/\s/g, '-')}`, 'critical', `Admin endpoint ${p} did not refuse ${label} (HTTP ${r ? r.status : 'error'})`, { path: p, status: r && r.status },
      'Authorization is missing or bypassable on an admin route.', 'Put the route behind the admin token check and add a regression test.', 'high'));
  }
  for (const p of AGENT_POST) for (const [label, headers] of [['no credentials', {}], ['wrong token', { authorization: 'Bearer nope' }]]) {
    const { r, err } = await req(p, { method: 'POST', headers: { 'content-type': 'application/json', ...headers }, body: '{}' });
    const ok = !!r && (r.status === 401 || r.status === 503);
    rec(`auth: POST ${p} with ${label} -> 401`, ok, err || `HTTP ${r && r.status}`, F(`auth:${p}:${label.replace(/\s/g, '-')}`, 'critical', `Agent endpoint ${p} accepted ${label} (HTTP ${r ? r.status : 'error'})`, { path: p, status: r && r.status },
      'The agent write API does not enforce its token.', 'Enforce AGENT_TOKEN on every agent route.', 'high'));
  }
  // Exposure: source, config and secrets must never be served.
  for (const p of LEAK_PATHS) {
    const { r, text, err } = await req(p);
    const served = !!r && r.status === 200 && !/<html/i.test(text.slice(0, 400)) ? true : !!r && r.status === 200 && /require\(|"dependencies"|DATABASE_URL/.test(text);
    rec(`exposure: ${p} is not served`, !served, err || `HTTP ${r && r.status}`, F('exposure:' + p, 'high', `${p} is publicly readable`, { path: p, status: r && r.status },
      'Static serving or routing exposes a project file.', 'Remove the route or restrict static serving to public/.', 'high'));
  }
  // Headers on a normal response.
  const h = await req('/healthz');
  if (h.r) {
    const g = (n) => h.r.headers.get(n);
    rec('headers: x-content-type-options nosniff', g('x-content-type-options') === 'nosniff', String(g('x-content-type-options')), F('headers:nosniff', 'low', 'Missing X-Content-Type-Options: nosniff', {}, 'Helmet not applied.', 'Enable helmet defaults.', 'none'));
    rec('headers: x-powered-by absent', !g('x-powered-by'), String(g('x-powered-by')), F('headers:powered-by', 'low', 'X-Powered-By discloses the framework', { value: g('x-powered-by') }, 'Express default header.', 'app.disable("x-powered-by").', 'none'));
    if (https) rec('headers: HSTS present', !!g('strict-transport-security'), String(g('strict-transport-security')), F('headers:hsts', 'medium', 'Missing Strict-Transport-Security on an HTTPS API', {}, 'HSTS not sent.', 'Enable HSTS (helmet).', 'none'));
    const acao = g('access-control-allow-origin');
    rec('cors: no wildcard origin on the API', acao !== '*', String(acao), F('cors:wildcard', 'medium', 'API answers Access-Control-Allow-Origin: *', { value: acao }, 'Permissive CORS lets any web page call the API from a browser.', 'Restrict CORS or remove it (the app is native, not a web client).', 'low'));
  } else rec('headers: /healthz reachable', false, h.err, null);
  // Error handling: bad input gets a clean 4xx and never a stack trace.
  const bad = await req('/v1/telemetry/boot', { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{bad' });
  const clean = !!bad.r && bad.r.status >= 400 && bad.r.status < 500 && !/\bat .*\(|node_modules|stack/i.test(bad.text);
  rec('errors: malformed JSON -> 4xx without stack trace', clean, bad.err || `HTTP ${bad.r && bad.r.status}`, F('errors:stack-or-5xx', 'medium', `Malformed JSON produced HTTP ${bad.r ? bad.r.status : 'error'} or leaked internals`, { status: bad.r && bad.r.status, sample: (bad.text || '').slice(0, 120) }, 'Body-parser errors reach the generic 500 handler.', 'Map parser errors to 400 invalid_request.', 'low'));
  const oversize = await req('/v1/telemetry/boot', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ x: 'y'.repeat(20000) }) });
  rec('limits: oversized body rejected (413/400)', !!oversize.r && [400, 413].includes(oversize.r.status), oversize.err || `HTTP ${oversize.r && oversize.r.status}`, F('limits:body-size', 'medium', 'Oversized request body was not rejected', { status: oversize.r && oversize.r.status }, 'No body size limit.', 'Add express.json({limit}).', 'low'));
  // Rate limiting: only when asked (it deliberately sends a burst).
  if (burst > 0) {
    const codes = []; for (let i = 0; i < burst; i++) { const x = await req('/v1/telemetry/boot', { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' }); codes.push(x.r && x.r.status); }
    rec(`rate limit: ${burst} rapid requests produce a 429`, codes.includes(429), `statuses seen: ${[...new Set(codes)].join(',')}`, F('ratelimit:telemetry', 'medium', 'Public telemetry endpoint did not rate limit a burst', { burst, statuses: [...new Set(codes)] }, 'No effective rate limiter.', 'Enable express-rate-limit on public routes.', 'low'));
  }
  return { checks, findings, passed: checks.filter((c) => c.ok).length, total: checks.length };
}

// ---- secret protection: patterns for real credentials in tracked files (values are never copied into findings)
const SECRET_PATTERNS = [
  ['GitHub token', /\bgh[pousr]_[A-Za-z0-9]{30,}\b|\bgithub_pat_[A-Za-z0-9_]{30,}\b/],
  ['npm token', /\bnpm_[A-Za-z0-9]{30,}\b/],
  ['Slack token', /\bxox[abprs]-[A-Za-z0-9-]{10,}\b/],
  ['AWS access key', /\bAKIA[0-9A-Z]{16}\b/],
  ['private key block', /-----BEGIN (?:RSA |EC |OPENSSH |)PRIVATE KEY-----/],
  ['database URL with password', /postgres(?:ql)?:\/\/[^:\s/]+:[^@\s/]{6,}@[^\s'"]+/],
  ['Stripe live key', /\bsk_live_[A-Za-z0-9]{16,}\b/],
];
const PLACEHOLDER = /(\.\.\.|yhpass|localhost|127\.0\.0\.1|example|user:pass|password@|<[^>]+>|\$\{|xxxx)/i;
export function scanSecrets(files) {
  const hits = [];
  for (const { path, content } of files) {
    if (/\.test\.[mc]?[jt]s$/.test(path)) continue; // test files hold deliberate fake secrets for the scanner's own tests
    if (/\.(png|jpg|jpeg|webp|ico|jar|keystore|jks|zip|apk|aab|woff2?)$/i.test(path) || /package-lock\.json$/.test(path)) continue;
    const lines = String(content).split('\n');
    lines.forEach((line, i) => { for (const [kind, re] of SECRET_PATTERNS) { const m = line.match(re); if (m && !PLACEHOLDER.test(/key|database/.test(kind) ? line : m[0])) hits.push({ path, line: i + 1, kind }); } });
  }
  const findings = hits.length ? [F('secrets:repo', 'critical', `${hits.length} possible credential(s) in tracked files`, { hits: hits.slice(0, 20), note: 'values deliberately not included' },
    'A credential-shaped string is committed.', 'Revoke the credential, remove it from history and load it from the environment.', 'high')] : [];
  return { hits, findings };
}

// ---- dependency vulnerabilities from `npm audit --json`
export function parseAudit(j) {
  const v = (j && j.metadata && j.metadata.vulnerabilities) || {};
  const crit = v.critical || 0, high = v.high || 0, mod = v.moderate || 0;
  const names = Object.entries((j && j.vulnerabilities) || {}).filter(([, x]) => ['critical', 'high'].includes(x.severity)).map(([n, x]) => ({ name: n, severity: x.severity, fixAvailable: !!x.fixAvailable })).slice(0, 15);
  const findings = [];
  if (crit + high > 0) findings.push(F('deps:high', crit ? 'critical' : 'high', `${crit} critical and ${high} high dependency vulnerabilities`, { counts: v, packages: names },
    'Installed production dependencies have known advisories.', 'Update the packages listed (run the test suite after) and redeploy.', 'medium'));
  else if (mod > 0) findings.push(F('deps:moderate', 'low', `${mod} moderate dependency vulnerabilities`, { counts: v }, 'Production dependencies have moderate advisories.', 'Update at the next maintenance window.', 'low'));
  return { counts: v, findings };
}

// ---- Android attack surface from a manifest (shared with the Android agent)
export function scanAndroidManifest(xml, networkConfig = '') {
  const out = [];
  const has = (re) => re.test(xml);
  if (has(/usesCleartextTraffic\s*=\s*"true"/)) {
    // Mitigated only when a network security config exists AND forbids cleartext to the project's own domain.
    const pinned = has(/android:networkSecurityConfig\s*=/) && (String(networkConfig).match(/<domain-config\b[\s\S]*?<\/domain-config>/g) || []).some((b) => /^<domain-config[^>]*cleartextTrafficPermitted\s*=\s*"false"/.test(b) && /yeehaw\.pro/.test(b));
    out.push(pinned
      ? F('android:cleartext-residual', 'low', 'Cleartext (http) allowed for user-chosen sites (accepted: a browser must open http pages); app endpoints on yeehaw.pro are pinned to https', { attribute: 'android:usesCleartextTraffic="true"', mitigation: 'network_security_config.xml domain-config yeehaw.pro cleartext=false' }, 'Only pages the user chooses to open can use http; app-owned calls cannot be downgraded.', 'None needed; keep the domain-config. HTTPS-first upgrade for user navigation is a separate product decision.', 'low')
      : F('android:cleartext', 'medium', 'App allows cleartext (http) traffic globally', { attribute: 'android:usesCleartextTraffic="true"' },
        'A browser must open http pages, so cleartext cannot simply be disabled; but app-owned API calls should be https only.', 'Keep cleartext for WebView page loads only; use a network security config that forbids cleartext to api.yeehaw.pro.', 'low'));
  }
  if (has(/android:allowBackup\s*=\s*"true"/)) out.push(F('android:backup', 'medium', 'allowBackup is enabled', {}, 'Browser data can be extracted with adb backup.', 'Set android:allowBackup="false".', 'low'));
  if (has(/android:debuggable\s*=\s*"true"/)) out.push(F('android:debuggable', 'high', 'Manifest sets debuggable=true', {}, 'Debuggable release builds expose app internals.', 'Remove the attribute; build types control it.', 'low'));
  const exported = [...xml.matchAll(/<(activity|service|receiver|provider)\b[^>]*>/g)].filter((m) => /android:exported\s*=\s*"true"/.test(m[0]) && !/intent-filter/.test(m[0]));
  const names = exported.map((m) => (m[0].match(/android:name="([^"]+)"/) || [])[1]).filter(Boolean);
  const nonLauncher = names.filter((n) => !/MainActivity$/.test(n));
  if (nonLauncher.length) out.push(F('android:exported', 'medium', `${nonLauncher.length} exported component(s) besides the launcher`, { components: nonLauncher }, 'Exported components can be started by other apps.', 'Set exported=false unless an intent-filter requires it.', 'low'));
  return out;
}
