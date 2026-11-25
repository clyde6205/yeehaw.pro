# Security audit (2026-11-25, code review plus CI; not a penetration test)

## Android
- WebView: file access, content access and universal access from files are disabled for web content; the start page is served by `shouldInterceptRequest` at `start.yeehaw.internal`. Re-verify in code before release.
- Permissions: INTERNET, network state; camera, microphone and location are runtime-only and optional (docs/PERMISSIONS_REVIEW.md).
- Cleartext: `usesCleartextTraffic=true` is kept because ordinary browsing needs http sites; app-owned backend calls use https only.
- Remote config, block/update status and Pro answers are signed (ECDSA P-256) and verified on the device. Update links are allow-listed (https and yeehaw.pro or play.google.com); unit tested.
- Pro: granted only on a signed server answer after Google Play verification; 7-day grace; fails closed. Unit tested.
- Telemetry: anonymous id, consent for EEA/UK/CH, forget-me.
- Sharing uses FileProvider with a scoped path file.

## Backend (console/backend)
- helmet, rate limiting, parameterised pg queries, 28 passing API tests.
- Secrets only from environment variables; none committed.

## Open items
| Item | Status |
|---|---|
| Admin token and Neon password were exposed earlier in this project | OWNER DECISION 2026-11-25: keep the originals, no rotation. Risk accepted by the owner. Recommended anyway before real users: rotate in the Vercel and Neon dashboards. |
| Founder console roles, audit log, session hardening | partially complete; verify against the deployed API |
| Dependency vulnerability scan (npm audit, Gradle) | not run |
| Lint | not run in CI yet |
| Privacy policy and terms pages | exist in docs/legal; legal review not done. No compliance claim is made |
