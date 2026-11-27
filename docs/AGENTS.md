# Maintenance agents and the founder console

What exists and is tested: four small read-mostly agents (`agents/*.cjs`) that run on GitHub Actions every 6 hours (and when `agents/` changes on the integration branches), plus a write-only reporting endpoint and an **Agents** tab in the founder console.

| Agent | What it does | Can it change anything? |
|---|---|---|
| `ci-watch` | Reads CI on `integration/reconcile-main`. If a job failed only because Maven Central rate-limited the runner (HTTP 429, no test failures), it re-runs the failed jobs (max 2 retries per commit). Everything else is reported as a real failure. | Only re-runs CI. Never edits code, never touches `main`, never merges. |
| `size-watch` | Release APK size from the latest successful release CI log vs the 12 MiB gate and a +25% budget over 972,583 bytes. | No |
| `deps-watch` | `npm audit` (high/critical) for the console backend and newest-version check for ZXing and Play Billing. | No |
| `site-health` | yeehaw.pro pages and `https://api.yeehaw.pro/healthz` respond. | No |

Each result goes to `POST /v1/agent/report` with a separate `AGENT_TOKEN` (write-only: it cannot read devices or change config; the admin token is rejected there). The console shows the latest result per agent and the last 100 reports. If `AGENT_TOKEN` is not set the endpoint answers 503 and the console says reporting is OFF: nothing is faked.

## To switch it on (OWNER, about 5 minutes)
1. Generate a token of at least 32 characters, different from `ADMIN_TOKEN`. Set it as `AGENT_TOKEN` in the Vercel project `yeehaw-api`, then redeploy.
2. In GitHub repo Settings > Secrets and variables > Actions add `AGENT_TOKEN` (same value) and `CONSOLE_URL` = `https://api.yeehaw.pro`.
3. The schema (`agent_reports` table) is created by the idempotent migration on the next API start.

## Limits to know
- GitHub only runs `schedule` workflows from the **default branch (`main`)**. Until the owner merges this work into `main`, the 6-hour schedule does not fire; the workflow still runs when `agents/` changes on the integration branches and can be started manually once it is on `main`.
- These agents detect and report. They do not write code. Autonomous code fixing needs an AI agent with a repository token; see below.

## Autonomous fixing (what it needs)
No plugin is required for the monitoring above. For an AI agent that reads a failing finding, edits code and opens a pull request, there are two options; neither is enabled:
- A scheduled Claude task in the owner's Claude account (uses the owner's plan, no extra API bill). It would run the prompt in `agents/FIXER_PROMPT.md`.
- `anthropics/claude-code-action` in GitHub Actions: needs an `ANTHROPIC_API_KEY` secret and bills per use. Not enabled because of the no-paid-services rule.
Guardrails for either: pull requests to `integration/reconcile-main` only, draft, never merge, never touch `main`, signing keys, secrets or the live database, every PR must pass the full CI matrix, every action reported through `/v1/agent/report`.

## Always-on mode (works with no GitHub at all)
`agents/worker/` is a Cloudflare Worker (free plan, Cron Trigger every 5 minutes) that does not depend on GitHub:
1. checks yeehaw.pro (home, privacy, support) and `/healthz` on the API and reports exactly which URLs fail;
2. calls `POST /v1/agent/tick`, where the API itself looks at crash/freeze counts in the last hour against the previous 24 hours and at database response time, and writes `fleet-health` and `db-health` reports (fail at 5 or more bad events that are more than 3x the usual rate; warn at 3 or more and 2x).
It holds only `AGENT_TOKEN`: no admin power and no database credentials. Tested with mocked network (`worker.test.mjs`) and the API tick is covered by the backend tests.

Deploy (OWNER, not done): `cd agents/worker && npx wrangler secret put AGENT_TOKEN && npx wrangler deploy`. It needs the same `AGENT_TOKEN` set on the API. Cloudflare Workers Cron Triggers are on the free plan; check current limits before relying on that.

Why not Neon or Vercel for the scheduler: Neon is the database and has no scheduler of its own that I could verify; Vercel Hobby cron is limited to once a day and Hobby forbids commercial use. A Worker cron is the cheapest always-on option I can verify from here.

## What "real time to Claude" can and cannot mean
Reports land in the console database within seconds of each check. A Claude session cannot sit connected forever and be pushed to: it can (a) watch while a session is open, (b) run as a scheduled task that wakes up periodically, reads the latest reports and CI, and opens draft pull requests per `agents/FIXER_PROMPT.md`. The scheduled task needs a way to read the console (a read-only credential) that has not been created; until then it can only see CI and public pages.

## Orchestration engine (in the API, repo code until deployed)

`POST /v1/agent/tick` (AGENT_TOKEN) is what the always-on Worker calls every 5 minutes. It takes a 20 s lease (a second tick during that window returns 202 and does nothing), measures the database with a ping, a read and a write (not just SELECT 1), writes `db-health` and `fleet-health` reports, then runs the orchestrator:

1. Specialists (availability dead-man switch, database size/indexes, Android compatibility from fleet telemetry, security, performance, QA) return findings with a fingerprint, severity, risk and proposal.
2. Findings are deduplicated by fingerprint. A finding that stops being reported is auto-resolved.
3. Only repairs on the allowlist (`prune_agent_reports`, `analyze:<table>`) may run, and only for low/none risk. Anything else, or any medium/high risk finding, waits in the console for Approve / Reject / Dismiss. Approving a finding with no automatic repair is refused (409): that one needs a code change on the integration branch.
4. After a repair the same specialist runs again; the finding becomes `verified` only if the problem is gone, otherwise `failed`.
5. Every step is written to `agent_actions`; owner decisions also go to `admin_audit`.

The Worker judges the tick response (database status, fleet status, specialist errors), retries transient failures, checks that each report was accepted (HTTP 201) and logs and fails the run if not.

Status as of this commit: tested locally against Postgres (backend suite 37 tests, Worker suite 9 tests) and the console view was exercised in a headless browser. It is NOT deployed until AGENT_TOKEN is set on the API and the Worker is deployed with its own secret; see the deployment log in RC_EVIDENCE.md for what has actually been observed live.

## Deploying the Worker (OWNER, about 3 minutes; needs your Cloudflare login)

1. `cd agents/worker && npx wrangler deploy` (log in when asked).
2. `npx wrangler secret put AGENT_TOKEN` and paste the same value that is set as AGENT_TOKEN on the Vercel project yeehaw-api.
3. In the Cloudflare dashboard open Workers > yeehaw-agents > Triggers and confirm the cron `*/5 * * * *`.
4. Within ten minutes the console Agents tab should show a `scheduler` report newer than five minutes; the Worker log (`npx wrangler tail`) prints `[agents] ...` lines only when something is wrong.
Until step 4 is seen, treat the always-on scheduler as not running.


## Classification (2026-11-27, corrected)
- Cron: removed from `yeehaw-agents`. It was only ever a scheduler, never the engine.
- `agents/watch` (Durable Object, 30 s alarm): MONITORING + DIAGNOSIS only. Classifies website/API/database outage and "API serving HTML = wrong deployment", queues reports until the console accepts them. It does not repair anything.
- `console/backend/agents/*` orchestrator + specialists: the engine core. Implemented: findings, lease, dedupe, allowlisted low-risk auto-repair, re-detection verification, audit log, approve/reject/dismiss. Partial: one correlation rule. Missing: code-repair agent (branch/PR), infrastructure agent, independent QA verification, performance before/after evidence, engine on/off and frequency controls, cost view, rollback control.

## Engine v2 (committed on wip/quick-tools, NOT deployed; needs owner approval + a DB schema migration for `engine_settings`)
- Diagnostic agent (`specialists.diagnostic`): reads the watcher's classified incident and opens an investigated finding (probe evidence, probable cause, next step), routed to `infrastructure` (wrong deployment, platform outage), `availability` (site/API outage) or `db-optimizer` (database outage). Production-deployment fixes are never automatic (`repair` is null, approve returns 409).
- Founder controls: `PUT /v1/admin/engine` (enable/disable engine, per-agent switches, minimum interval, permitted automatic repairs) and `POST /v1/admin/engine/run`; every change is written to `agent_actions` (actor owner) and the admin audit log. Console: Agents tab > Engine controls.
- Separation of duties: repairs are logged by actor `repair`; verification by actor `qa` uses an independent check (`REPAIR_VERIFY`) AND the detector re-run; a repair is `verified` only if both pass.
- Per-agent status per run (`summary.per_agent`: ok/disabled/error, findings, ms) is shown in the console.
- Tests: 44 backend (incl. watcher -> real HTTP -> console -> diagnosis -> recovery) and 12 watcher.
- Still missing: code-repair agent (branch + draft PR), infrastructure probes of Vercel/Cloudflare/Neon APIs from the engine, performance before/after measurements, cost view, rollback control, test-evidence storage.
