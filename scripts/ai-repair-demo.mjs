// Runs the real repair pipeline with a model-written proposal read from a file. Usage: node scripts/ai-repair-demo.mjs <repoDir> <baseBranch> <proposal.json> <out.json>
import { readFileSync, writeFileSync } from 'node:fs';
import { runRepair, verifyRepair } from '../agents/repair/index.mjs';
import { makeAiFixer } from '../agents/repair/ai-fixer.mjs';
const [repoDir, base, proposal, out] = process.argv.slice(2);
const log = []; const report = async (x) => { log.push(x); };
const finding = { id: Number(process.env.FID || 21), fingerprint: 'code:scan-android-cleartext-pinned' + (process.env.FID ? '-ctl' : ''), title: 'scanAndroidManifest reports cleartext as pinned when yeehaw.pro is in a domain-config that PERMITS cleartext' };
const testCmd = 'node --test agents/repair/real-defect.test.mjs agents/android/analyze.test.mjs';
const fixer = makeAiFixer({ complete: async () => readFileSync(proposal, 'utf8'), files: ['agents/security/scan.mjs'], finding });
const r = await runRepair({ repoDir, base, finding, fixer, testCmd, report });
let qa = null; if (r.ok) { const ql = []; qa = await verifyRepair({ repoDir, base, finding: { id: Number(process.env.FID || 21) }, commit: r.commit, testCmd, report: async (x) => { ql.push(x); } }); qa.log = ql; }
writeFileSync(out, JSON.stringify({ result: r, fixer: { rationale: fixer.proposal && fixer.proposal.rationale, needsFounderApproval: fixer.needsFounderApproval }, log, qa }, null, 1));
console.log(JSON.stringify({ ok: r.ok, stage: r.stage, branch: r.branch, commit: r.commit, before: r.before && { exit: r.before.exit, failed: r.before.failed }, after: r.after && { exit: r.after.exit, passed: r.after.passed, failed: r.after.failed }, qa: qa && { ok: qa.ok }, approval: fixer.needsFounderApproval }));
