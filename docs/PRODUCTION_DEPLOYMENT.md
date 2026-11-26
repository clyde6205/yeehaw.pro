# Production deployment (state as last verified)
| System | Intended | Verified state |
|---|---|---|
| Cloudflare Worker `yeehaw-pro` | serves ./public built by `npm run build` | worker exists; dashboard build command not verifiable from the sandbox; live site blocked by the sandbox proxy |
| Vercel project `yeehaw-api` | runs console/backend (Express) | project exists; alias https://yeehaw-api.vercel.app still serves OLD code; git-source deploy failed (`incorrect_git_source_info`) |
| Neon `rapid-hat-12220373` / db `yeehaw` | Postgres | connection used earlier; `entitlements` migration APPLIED 2026-11-25 (additive; table present, 0 rows; no data touched) |
| api.yeehaw.pro | CNAME to Vercel | attached to project; DNS record missing in Cloudflare (Vercel: misconfigured) |

## Neon migration procedure (additive, no reset)
1. Create a Neon snapshot or branch as backup.
2. Run only `CREATE TABLE IF NOT EXISTS entitlements ...` from console/backend/schema.sql.
3. Verify with a SELECT on information_schema; do not truncate or drop anything.

## Env vars (names only)
DATABASE_URL, admin token, PLAY_SERVICE_ACCOUNT_JSON, PLAY_PACKAGE_NAME, config signing key.

## Notes
- Vercel Hobby plan is non-commercial; upgrade before charging users.
- Owner decision 2026-11-25: existing admin token and Neon password stay in use (see SECURITY_AUDIT.md).

## Incident note 2026-11-25
Two manual Vercel uploads were made from this session with incomplete file sets. The first failed at build (no public dir). The second was cancelled before it became READY. Production kept serving dpl_ZbRCjj (still current, confirmed by a refused rollback: "already the current production deployment"). Lesson: deploy through the Git integration, not hand-assembled uploads.

## Backend CI
`.github/workflows/backend-tests.yml` runs `npm ci && npm test` against a Postgres 16 service on every backend change. Latest: passed at f6033ff (includes the new Subscriptions view test).
