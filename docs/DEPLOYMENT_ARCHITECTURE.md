# Deployment architecture and the 2026-11-27 incident

## Two independent deployables
| Part | Source | Host | Domain | Build config |
|---|---|---|---|---|
| Static site | repo root (`scripts/build-static.js`) | Cloudflare Worker `yeehaw-site` | yeehaw.pro | own pipeline |
| API + founder console | `console/backend` | Vercel project `yeehaw-api` | api.yeehaw.pro | Root Directory `console/backend`, install `npm ci --omit=dev`, build `echo skip`, output `public`, functions in `console/backend/vercel.json` |
| Watcher (monitoring only) | `agents/watch` | Cloudflare Worker `yeehaw-agents` (Durable Object, no cron) | none public | own upload |

Neither deploy may touch the other. An API deploy must always pass `projectSettings.rootDirectory = "console/backend"`.

## Incident
A deployment created without `projectSettings.rootDirectory` built the repo root, i.e. the static site, and became production for `api.yeehaw.pro`. The API answered HTML. Vercel showed it as Ready. The watcher flagged failure at 14:05 UTC; recovery was verified at 14:29 UTC after redeploying with the correct settings.

## "Ready" is not proof. Verify after every API deploy
1. `GET /livez` -> JSON `{ok:true}` (new builds) or JSON 404 (older builds); never HTML.
2. `GET /healthz` -> 200 JSON (runs `SELECT 1`, proves the database).
3. `GET /v1/admin/overview` without credentials -> 401 JSON (proves auth is enforced).
4. `POST /v1/agent/report` with a wrong token -> 401 (503 `agent_reporting_disabled` means the AGENT_TOKEN is missing in that build).

## Known state after recovery (honest)
- Serving: build of b4f2353 (older than the latest source). Newer fixes (generic 4xx handler, CSP, `/livez`, event-driven agent pass) are committed on `wip/quick-tools` but NOT deployed; deploying them needs owner approval.
- `AGENT_TOKEN` is absent from the serving build, so agent reports are refused (503). The watcher queues reports and retries; nothing is lost. The owner must set Vercel `AGENT_TOKEN` to the value the Worker holds. No secret is handled by the assistant.
- `/v1/app-config` returns 404 on this build; the route in the app is `/v1/config` family and was verified earlier: re-verify after the next approved deploy.
