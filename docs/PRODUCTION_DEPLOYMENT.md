# Production deployment (state as last verified)
| System | Intended | Verified state |
|---|---|---|
| Cloudflare Worker `yeehaw-pro` | serves ./public built by `npm run build` | worker exists; dashboard build command not verifiable from the sandbox; live site blocked by the sandbox proxy |
| Vercel project `yeehaw-api` | runs console/backend (Express) | project exists; alias https://yeehaw-api.vercel.app still serves OLD code; git-source deploy failed (`incorrect_git_source_info`) |
| Neon `rapid-hat-12220373` / db `yeehaw` | Postgres | connection used earlier; `entitlements` migration NOT applied live |
| api.yeehaw.pro | CNAME to Vercel | not added (owner DNS) |

## Neon migration procedure (additive, no reset)
1. Create a Neon snapshot or branch as backup.
2. Run only `CREATE TABLE IF NOT EXISTS entitlements ...` from console/backend/schema.sql.
3. Verify with a SELECT on information_schema; do not truncate or drop anything.

## Env vars (names only)
DATABASE_URL, admin token, PLAY_SERVICE_ACCOUNT_JSON, PLAY_PACKAGE_NAME, config signing key.

## Notes
- Vercel Hobby plan is non-commercial; upgrade before charging users.
- Owner decision 2026-11-25: existing admin token and Neon password stay in use (see SECURITY_AUDIT.md).
