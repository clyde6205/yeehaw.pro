// AI-assisted fixer for the repair agent. The model only PROPOSES exact text edits; everything else is enforced here, in code:
//  - which files it may read and touch (forbidden paths are refused, sensitive ones need founder approval),
//  - that every edit matches the real file exactly once (no guessing, no fuzzy patching),
//  - a hard limit on how much it may change,
//  - and the result still goes through isolated worktree -> tests -> independent QA -> founder approval (see index.mjs).
// The model is injected as `complete(prompt) -> string`, so tests use a fake and a real run can use any provider.
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join, normalize, sep } from 'node:path';

// Tests are never editable by the fixer: a repair must make the existing tests pass, not change what they check.
export const FORBIDDEN = [/\.(test|spec)\.[cm]?[jt]sx?$/, /(^|\/)(test|tests|__tests__|androidTest)\//, /(^|\/)\.env/, /(^|\/)node_modules\//, /\.(jks|keystore|p12|pem|key)$/i, /(^|\/)package-lock\.json$/, /(^|\/)\.git\//, /^main$/];
// Allowed, but a human (the founder) must approve before it is merged or deployed.
export const SENSITIVE = [/^\.github\//, /^vercel\.json$/, /(^|\/)wrangler\./, /\.gradle(\.kts)?$/, /AndroidManifest\.xml$/, /network_security_config\.xml$/,
  /(^|\/)migrations?\//, /\.sql$/, /(^|\/)(auth|security)[^/]*\.(js|mjs|cjs|ts|kt)$/i, /console\/backend\/server\.js$/];

export function classifyPaths(paths) {
  const bad = [], sensitive = [];
  for (const p of paths) {
    const n = normalize(p).split(sep).join('/');
    if (n.startsWith('..') || n.startsWith('/') || FORBIDDEN.some((r) => r.test(n))) bad.push(p);
    else if (SENSITIVE.some((r) => r.test(n))) sensitive.push(p);
  }
  return { forbidden: bad, sensitive, needsFounderApproval: sensitive.length > 0 };
}

export function buildPrompt(finding, files, failingOutput = '') {
  const src = files.map((f) => `=== ${f.path} ===\n${f.text.split('\n').map((l, i) => `${String(i + 1).padStart(4)}| ${l}`).join('\n')}`).join('\n\n');
  return [
    'You are the YeeHaw repair agent. Fix ONE real defect with the smallest correct change.',
    'Rules: change only what the defect requires; never weaken, skip or delete a test; no new dependencies; no secrets; keep behaviour otherwise identical.',
    'Answer with ONLY a JSON object: {"rationale": string, "edits": [{"path": string, "old": string, "new": string}]}.',
    '"old" must be copied EXACTLY from the file (without the line-number prefix) and occur exactly once in it. If you cannot find the defect, answer {"rationale": "...", "edits": []}.',
    '', `FINDING: ${finding.title}`, finding.detail ? `DETAIL: ${typeof finding.detail === 'string' ? finding.detail : JSON.stringify(finding.detail)}` : '',
    failingOutput ? `FAILING TEST OUTPUT:\n${failingOutput.slice(-3000)}` : '', '', 'SOURCE:', src].filter((x) => x !== '').join('\n');
}

export function parseProposal(text) {
  const m = String(text).match(/\{[\s\S]*\}/);
  if (!m) throw new Error('the model did not return JSON');
  const j = JSON.parse(m[0]);
  if (!Array.isArray(j.edits)) throw new Error('proposal has no edits array');
  for (const e of j.edits) if (typeof e.path !== 'string' || typeof e.old !== 'string' || typeof e.new !== 'string' || !e.old) throw new Error('malformed edit');
  return { rationale: String(j.rationale || ''), edits: j.edits };
}

/** Returns a fixer usable by runRepair({fixer}). `files` = repo-relative paths the model may read (from the finding / failing test). */
export function makeAiFixer({ complete, files, finding, failingOutput = '', maxFiles = 3, maxChangedLines = 60 }) {
  const fixer = async (dir) => {
    const pre = classifyPaths(files); if (pre.forbidden.length) throw new Error('refused: forbidden path ' + pre.forbidden.join(', '));
    const ctx = files.slice(0, maxFiles).map((p) => ({ path: p, text: readFileSync(join(dir, p), 'utf8') }));
    const prompt = buildPrompt(finding, ctx, failingOutput);
    const proposal = parseProposal(await complete(prompt));
    fixer.proposal = proposal; fixer.promptBytes = Buffer.byteLength(prompt);
    if (!proposal.edits.length) throw new Error('the model found nothing to change: ' + proposal.rationale);
    const cls = classifyPaths(proposal.edits.map((e) => e.path));
    if (cls.forbidden.length) throw new Error('refused: edit touches forbidden path ' + cls.forbidden.join(', '));
    fixer.needsFounderApproval = cls.needsFounderApproval; fixer.sensitive = cls.sensitive;
    const changed = proposal.edits.reduce((n, e) => n + Math.max(e.old.split('\n').length, e.new.split('\n').length), 0);
    if (changed > maxChangedLines) throw new Error(`refused: proposal changes ~${changed} lines (limit ${maxChangedLines})`);
    const staged = new Map();
    for (const e of proposal.edits) {
      const f = join(dir, e.path); if (!existsSync(f)) throw new Error('edit targets a file that does not exist: ' + e.path);
      const text = staged.get(e.path) ?? readFileSync(f, 'utf8');
      const n = text.split(e.old).length - 1;
      if (n !== 1) throw new Error(`edit for ${e.path} matches ${n} times (must be exactly once)`);
      staged.set(e.path, text.replace(e.old, () => e.new));
    }
    for (const [p, t] of staged) writeFileSync(join(dir, p), t);
  };
  return fixer;
}

/** Provider adapter (Anthropic Messages API). Needs ANTHROPIC_API_KEY in the environment that runs the repair; the platform itself holds none. */
export function anthropicComplete({ apiKey, model = 'claude-sonnet-5-5', fetchFn = fetch, maxTokens = 2000 }) {
  return async (prompt) => {
    const r = await fetchFn('https://api.anthropic.com/v1/messages', { method: 'POST', headers: { 'x-api-key': apiKey, 'anthropic-version': '2023-06-01', 'content-type': 'application/json' },
      body: JSON.stringify({ model, max_tokens: maxTokens, messages: [{ role: 'user', content: prompt }] }) });
    if (!r.ok) throw new Error('model call failed: HTTP ' + r.status);
    const j = await r.json(); return (j.content || []).map((c) => c.text || '').join('');
  };
}
