# Maintenance agents and the founder console

What exists and is tested: four small read-mostly agents (`agents/*.js`) that run on GitHub Actions every 6 hours (and when `agents/` changes on the integration branches), plus a write-only reporting endpoint and an **Agents** tab in the founder console.

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
