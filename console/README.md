# YeeHaw Founder Console
`backend/`  Node 20 + Express + PostgreSQL API and the single-file founder console (served at `/admin`).
`android/`  Drop-in Kotlin modules for the existing YeeHaw app (package `app.yehaw.browser.console`).
`docs/INTEGRATION.md`  Step-by-step deployment and wiring guide. Start there.

## Founder console v2 (deploy notes)
- New tabs: Overview, Users, Advertising, Updates & flags, Operations, Search & blocklist, System & audit. Single file, no CDN.
- New endpoints: `/v1/device/status` (signed), `/v1/telemetry/health`, admin `/devices*`, `/analytics/{ads,geo,fleet,health}`, `/audit`, `/system`.
- Database: `schema.sql` is idempotent. Already applied to the live Neon project (`rapid-hat-12220373`).
- Deploy: connect this repo to the Vercel project `yeehaw-api` (Settings > Git), root directory `console/backend`, or run `vercel deploy --prod` from `console/backend`.
- Tests: `cd console/backend && npm ci && npm test` (27 tests, needs Postgres; `TEST_DATABASE_URL`).
