# Release candidate evidence: commit e0d992a

Source: `integration/reconcile-main` @ `e0d992a65470c813042a943b92b15b0bcb37f219` (`main` untouched).
All 11 jobs completed successfully (checked per job, none cancelled or skipped):

| Workflow | Job | Result | Run |
|---|---|---|---|
| Static product site checks | static-site | success | https://github.com/clyde6205/yeehaw.pro/actions/runs/45768927851 |
| Backend tests | api | success | https://github.com/clyde6205/yeehaw.pro/actions/runs/45768927814 |
| Android build (compile, unit tests, lint) | debug-build | success | https://github.com/clyde6205/yeehaw.pro/actions/runs/45768927882 |
| Android release APK smoke | API 23 and API 36, R8-minified | success, success | https://github.com/clyde6205/yeehaw.pro/actions/runs/45768927889 |
| Android emulator smoke | API 23, API 23 low-end (1 GB, 1 core), API 29, API 35, API 36 | success x5 | https://github.com/clyde6205/yeehaw.pro/actions/runs/45768927899 |

Instrumented tests: 40 passed, 0 failed on API 29, 35 and 36. API 23 jobs report BUILD SUCCESSFUL with no failure (the Gradle log tail is truncated, so no per-test count is quoted).
Crash/ANR scan: no crash or ANR in any YeeHaw process on any emulator job.

Emulator measurements (emulator, not a phone; cold start `am start -W` TotalTime, 5 runs):
API 23 std 490-680 ms, total PSS ~67 MB; API 23 low-end 1 GB/1 core 689-865 ms, PSS ~68 MB; API 29 552-738 ms, ~100 MB; API 35 1058-1185 ms, ~97 MB; API 36 800-1032 ms, ~118 MB.

APK: 805,928 bytes, SHA-256 `4f8c7bc3608966ade9906b4619331b525d0258cc08e54a97114ce056dc9aa067`, v1+v2 signed with a throwaway CI key (not production).

## Test-side defects fixed on the way (no app behaviour was changed for these)
* Font-scale test assumed the address bar was visible on the start page (hidden by design); it now opens a page first.
* API 36 emulator reused a dead private-window task (`START_TASK_TO_FRONT`), which kept window focus and starved later tests; the tests now launch with CLEAR_TASK, return Home on cleanup, and CI disables window animations.
## Real app defect fixed
* Choosing "System default" after another language left the old process-wide Locale (WebView error pages stayed in the previous language). Fixed in `LocaleHelper`, regression test added.

## Not covered by this evidence
Emulators only, no physical phone, no mobile-network run; no human TalkBack session; translations are machine translations not reviewed by native speakers; production signing key not supplied; data-saver savings not measured on real traffic.

## Quick Tools build (code commit 8d0b26c)

Six tools (Calculator, Unit Converter, QR Scanner, QR Generator, Notes, PDF Reader) added under Menu > Quick Tools. See `docs/QUICK_TOOLS.md`.

| Check | Result | Run |
|---|---|---|
| Debug build + unit | success | https://github.com/clyde6205/yeehaw.pro/actions/runs/45783789477 |
| Release APK smoke, API 23 and API 36, R8-minified | success, success | https://github.com/clyde6205/yeehaw.pro/actions/runs/45783789485 |
| Emulator suite API 23, 23 lowend (1 GB, 1 core), 29, 35, 36 | first attempt: 4 success, API 29 failed (known issue 21); full rerun: all 5 success | https://github.com/clyde6205/yeehaw.pro/actions/runs/45783789513 (attempt 2) |

Measured: release APK 972,583 bytes (RC without Quick Tools: 805,928), +166,655 bytes (+20.7%), covering six tools, ZXing core 3.5.3 (decode only), 110 new strings in 11 languages. SHA-256 `df27906c76e486e271dbeeab1bc05b807796e4a9fede94c464e3ebe3cbfe9424` (throwaway CI key, not production). No new permissions (CAMERA already declared, requested at runtime only). Cold-start before/after was not measured.

Fixes found by CI: API 23 native crash in the platform PDF engine (header check); a failed page was announced as "Page loaded" (app fix in onPageFinished).

Not covered: physical phone, real camera scan, TalkBack, native-speaker review of the new strings (machine translations).


## Agent engine: what is deployed (observed 2026-11-27)

- API deployment dpl_6AKoNk36F6E8oEzzBq3P1n7GbGSV (production, aliased to api.yeehaw.pro) built from commit b4f2353 on wip/quick-tools, state READY.
- `AGENT_TOKEN` set as a sensitive Vercel variable (production and preview). First deploy showed `/v1/agent/tick` answering 503 because `api/index.js` did not pass the token; fixed in b4f2353.
- Neon project yeehaw-console: the agent_* tables did not exist; created with the idempotent statements from `schema.sql` (additive, nothing dropped or altered).
- Live proof: `POST https://api.yeehaw.pro/v1/agent/tick` from a Vercel sandbox returned 200 `{ok:true, fleet:"ok", db:"ok", db_ms:6, orchestrator:{lease:"acquired", run:"1"...}}`. Neon then held rows: scheduler/db-health/fleet-health reports and agent_runs id 1 (ok). A wrong token was not used against the new build; 401 behaviour is covered by the backend tests only.
- NOT deployed: the Cloudflare Worker (no Cloudflare deploy credential in this session). So the five-minute Cron Trigger has NOT been demonstrated; only a manual tick was. Owner steps are in AGENTS.md.
- NOT yet observed: the founder console Agents tab on the live site (tested in a headless browser against a local database only).
