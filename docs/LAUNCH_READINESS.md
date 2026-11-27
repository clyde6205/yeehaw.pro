# YeeHaw launch-readiness report — 2026-11-27 (Manila evening)

Status labels: VERIFIED (observed in this session), PARTIAL, NOT VERIFIED.

## A. Production status
- api.yeehaw.pro serves the JSON API from dpl_6AKoNk… — VERIFIED (/healthz 200 JSON; agent tick wrote Neon rows).
- yeehaw.pro, www.yeehaw.pro, /privacy/, /support/, /terms/ now return 200 from a Cloudflare Worker (`yeehaw-site`) — VERIFIED from an outside sandbox. Cause of the earlier 404s: apex pointed at GitHub Pages addresses (one wrong) and Pages was not serving; DNS records removed and replaced by Worker custom domains. Old records (A 185.199.108/109/110/111.153, www CNAME clyde6205.github.io) are recorded in docs/RC_EVIDENCE.md for rollback.
- API fixes 8959661 (malformed JSON → 4xx) and the tighter CSP are in the repo but NOT deployed (deploying would re-save project settings; separate API/site Vercel projects are the agreed follow-up).
- Admin-authorised production routes — NOT VERIFIED (admin token not available to the assistant).

## B. Cloudflare integration
- Worker `yeehaw-agents`: current code deployed (repo 29c256a), CONSOLE_URL set, AGENT_TOKEN set, cron currently OFF — VERIFIED.
- Worker → API token path — VERIFIED (scheduled runs wrote scheduler/db-health/fleet-health/site-health rows to Neon).
- Cron left disabled by the owner's rule; enabling is one owner decision.

## C. Agent engine
- Specialists (availability, database, androidCompat, security, performance, qa), orchestrator, verified-repair loop, findings dedupe, lease — implemented, backend suite 38/38, deployed on API — VERIFIED.
- Repairs limited to an allowlist (prune_agent_reports, analyze:<table>); medium/high risk waits for owner approval.

## D. Android browser (emulator evidence from GitHub CI, run b236451; no physical phone)
- Release APK ~0.97 MB (972,586 B), AAB 1,002,978 B (measured).
- Cold start `am start -W` TotalTime: API 23 standard 686–878 ms; API 23 low-end (1 GB, 1 core) 849–867 ms; API 29 617–677 ms; API 35 1311–1492 ms; API 36 932–1067 ms; release R8 API 23 564–784 ms, API 36 971–1137 ms. Emulators, not phones: indicative only.
- Memory: native heap 16–25 MB after start (meminfo on API 35/36 emulators).
- Crash rate on real devices: NOT VERIFIED (no field data yet; console shows 0 crashes/freezes in last hour, 34 active devices today).
- API 29 retry-panel test is intermittent (KNOWN_ISSUES row 21, OPEN).

## E. Security
- Parameterised SQL; allowlisted identifiers; constant-time token compare; failed-admin-auth limiter (10 per 15 min); agent endpoints rate-limited; helmet headers + HSTS; CSP without third-party script hosts (in repo); npm audit 0 vulnerabilities (production deps).
- No CORS headers granted (preflight 404). Malformed JSON gave 500 in production (fixed in repo, not deployed).
- Site Worker sends nosniff, frame deny, HSTS, CSP, referrer and permissions policies.
- Owner items: rotate ADMIN_TOKEN and the Neon password (both were exposed earlier in the project), Vercel Hobby plan is for non-commercial use.

## F. Repository
- Work is on `wip/quick-tools`; `main` untouched (3c73d6a2d753). New: site-worker generator, Worker token validation, evidence docs.

## G. Outstanding blockers (owner)
1. Production upload keystore / Play App Signing / Play Console account and closed-test requirement.
2. Store assets: 1024×500 feature graphic, ≥2 phone screenshots (512 icon exists).
3. Support mailbox ceo@yeehaw.pro confirmed and published on /support/ (page still says "being set up").
4. Rotate ADMIN_TOKEN and Neon password; decide Pro billing or hide it.
5. Physical-phone, real-network and TalkBack tests; native-speaker translation review.
6. Split API and static site Vercel projects, then deploy 8959661 + CSP.
7. Decide whether to enable the Worker cron (every 5 minutes).
