# Autonomous Optimization & Repair Engine: requirement-by-requirement status (2026-11-27)

Legend: DONE+TESTED = implemented and exercised by an automated test and by the end-to-end run (`node scripts/engine-e2e.mjs`, evidence in `docs/perf/e2e-evidence.json`) on a real HTTP console and real Postgres. NOT DEPLOYED = in `wip/quick-tools`, not on production (needs owner approval and a schema migration). PARTIAL / MISSING are stated plainly.

## Phase 1: production recovery
| Item | Status | Evidence |
|---|---|---|
| Restore working API | DONE | api.yeehaw.pro serves the API build of b4f2353 (probe: /healthz 200 DB, /v1/config/app-config 200 signed, admin 401, /admin/ 200 HTML) |
| Verify real endpoints / DB / auth / app reach API | DONE | `docs/DEPLOYMENT_ARCHITECTURE.md` checklist, probes from a Vercel sandbox |
| Preserve source fixes | DONE | all on `wip/quick-tools`; `main` untouched (3c73d6a2) |
| Deployment separation + root verified | DONE (documented) | Root Directory `console/backend`; analyzer flags a 4.2 s static build vs 11-12 s API builds (real data) |
| Agent endpoints enabled on production | **BLOCKED (owner)** | `/v1/agent/report` still 503: Vercel `AGENT_TOKEN` missing in the serving build |

## Phase 2: classification
Cron removed from `yeehaw-agents` (schedules list empty, verified through the Cloudflare API). Watcher = monitoring/diagnosis only (`agents/watch`). Not counted as the engine. DONE.

## Phase 3: engine
| Component | Status |
|---|---|
| Orchestrator (lease, dedupe, prioritised findings, routing, verification tracking, decision history, per-agent run status, on/off, per-agent switches, interval, permitted repairs) | DONE+TESTED, NOT DEPLOYED |
| Monitoring intelligence (watcher: site/API/DB probes, outage classification, HTML-instead-of-JSON detection, queue) | DONE+TESTED, DEPLOYED (Worker) |
| Diagnostic agent (watcher incident -> investigated finding with evidence, cause, next step, routed to owner agent) | DONE+TESTED, NOT DEPLOYED |
| Autonomous repair agent (isolated worktree/branch, protected-branch guard, tests before/after, evidence, optional draft PR, rollback on its own branch) | DONE+TESTED (4 unit tests + e2e: real change to `server.js`). Draft-PR push is implemented but off by default and not exercised against GitHub. |
| Performance agent (bench harness: round trips per request counted at the driver, p50/p95, bytes; before/after compare) | DONE+TESTED. Scope today: API hot paths. Browser/Android runtime metrics (memory, CPU, cold start) are NOT measurable from this environment and are not claimed. |
| Security agent | PARTIAL: existing detector (dependencies/secrets heuristics); `npm audit` on the backend currently reports 0 vulnerabilities (measured). No auth-review automation beyond the existing tests. |
| QA agent (independent verification on a fresh checkout, different run, different DB; rejects deleted tests / protected paths) | DONE+TESTED, NOT DEPLOYED. Server refuses any verification not from `qa`, for another commit, or from the same run. |
| Database reliability agent | PARTIAL: existing detector (size, dead rows, connections, latency). No query-plan or migration-planning automation. |
| Android compatibility agent | PARTIAL: existing detector over fleet telemetry (RAM/OS crash rates). No device/emulator testing here. |
| Infrastructure agent (Vercel failed deploys, wrong root signal, alias check, churn, stale code; Cloudflare bot mode + unexpected cron; Neon consumption) | DONE+TESTED on captured REAL data. Collection runs from connectors (this session); running unattended needs read-only API tokens as secrets (owner decision). |
| Loop DETECT→...→REPORT, approval gates, no self-verification | DONE+TESTED |

## Phase 4: console
Engine on/off, per-agent switches + status, interval, permitted repairs, run now, approve/reject/dismiss, audit trail with actor/time/result/verification, test evidence column, rollback button, costs card: DONE, rendered and checked in a real browser with real e2e data (0 page errors). NOT DEPLOYED. Missing: code diff viewer (the commit/branch is shown, the diff is not), performance-trend charts (before/after rows are shown as evidence entries).

## Phase 5: infrastructure and cost
Findings from real data and measured Neon consumption: 0.51 of 100 free CU-hours used month to date, $0 billed (measured). No paid service added. Cloudflare cost/usage and Vercel usage numbers are not collected yet (MISSING).

## Phase 6: the 12 scenarios
| # | Scenario | Result |
|---|---|---|
| 1 | API failure detected | PASS on real HTTP (watcher -> console) and in production on 2026-11-27 (real incident detected 14:05 UTC, recovered 14:29 UTC) |
| 2 | Diagnostic investigates actual cause | PASS (cause + evidence from probes; wrong-deployment classification) |
| 3 | Orchestrator assigns correct agent | PASS (`infrastructure` / `availability` / `db-optimizer` routing test) |
| 4 | Repair agent prepares a real correction | PASS (real edit of the boot handler, commit on `repair/1-perf-boot-round-trips`) |
| 5 | Automated tests execute | PASS (45/45 before and after, in isolated worktree) |
| 6 | QA independently verifies | PASS (fresh checkout, separate DB, 45/45; server rejects self-verification) |
| 7 | Console shows full history | PASS (e2e evidence + browser check) |
| 8 | Failed repair not reported successful | PASS (bad fix: tests 43/45, "fix failed", finding stays open, branch deleted) |
| 9 | Protected operations need approval | PASS (infrastructure finding has no repair: approve = 409; deploy/migration are never automatic) |
| 10 | Website outage vs API outage | PASS (classifier tests + e2e) |
| 11 | Incorrect Vercel configuration identified | PASS on REAL captured data (4.2 s build flagged critical; healthy 11.5 s build not flagged) |
| 12 | Measurable before/after performance | PASS: boot telemetry DB round trips 4 -> 1 (counted); local p50 2.79 -> 2.28 ms; projected at 20 ms RTT 82.8 -> 22.3 ms (arithmetic, labelled) |

Caveat that must stay attached to the table: scenarios 4-8, 10-12 ran against a local console and Postgres, not against the production deployment, because deploying the new code needs your approval and a database migration.

## Production deployment evidence (2026-11-27, ~15:00–15:07 UTC) — PRODUCTION, not local

- Pre-deploy: Neon backup branch `pre-engine-backup-2026-11-27` (br-steep-leaf-ao3cyirb); additive migration applied (`engine_settings` 1 row, `agent_actions.evidence` column present).
- AGENT_TOKEN: set in Vercel (production+preview, sensitive) and as Worker `yeehaw-agents` secret; values never printed in reports. Match proven behaviourally (below), not by reading secrets back.
- Deploy 1 `dpl_4hNWumoxbo2Ygnq6MVwtzoBWKcTh` (d7a595d) and deploy 2 `dpl_Brab6vqtCRoyUCJcrTU5MkhP3GWw` (85f95b2), both with rootDirectory `console/backend`; aliased to api.yeehaw.pro. Previous good deployment `dpl_5tkQW4b6…` kept as rollback target.
- Probed from a Vercel sandbox against https://api.yeehaw.pro: /healthz 200 (DB), /livez 200, /v1/config/app-config 200 signed, /admin/ 200, admin without token 401, /v1/agent/report no/wrong token 401, correct token 201 (previously 503), malformed JSON 400, yeehaw.pro 200.
- Found and fixed in production: duplicate deliveries with the same (agent, ref) were stored twice (2 rows); after deploy 2 the retry returns 200 `{duplicate:true}` and the table holds 1 row (verified in Neon).
- Persistence: rows written during deploy 1 are still present after deploy 2 (new instances) — survives backend restart.
- Not yet verified on production: Worker→API delivery end to end (Worker not yet exercised since secret set), engine pass on production data, rollback execution.

## Production redeploy 2 (2026-11-27, production evidence)
- Source: wip/quick-tools @ ef32094 (remote SHA verified equal to local). Root Directory console/backend. Env WATCH_STATUS_URL added (plain, production). Existing env vars untouched.
- dpl_6nAYZwbAmtV5cT65n7WTE7cyV9MF: ERROR at build ("npm run build" script missing: the project had no explicit build command). Never promoted; production stayed on the previous deployment.
- dpl_AmretwwuKFeUCScQ6kdvmMiRfcai: READY, alias api.yeehaw.pro assigned (buildCommand set explicitly to a no-op). Rollback targets preserved: dpl_Brab6vqt…, dpl_5tkQW4b6….
- Probes from a Vercel sandbox against the live domain: /healthz 200, /livez 200, /v1/config/app-config 200, /admin/ 200, /v1/admin/agents|trends and /v1/agent/pending-rollbacks 401 without credentials, /v1/agent/report malformed JSON 400, https://yeehaw.pro/ 200, Worker /status ok with no queued reports.
- NOT yet verified in production: authenticated roster/trends payloads (need ADMIN_TOKEN, which I do not hold), engine pass on production data, rendered console cards. Those cards are tested locally only.

## Token rotation, incident and tool reports (2026-11-27 ~15:25-15:35 UTC, production evidence)
- AGENT_TOKEN rotated: new value set (sensitive) in Vercel production and as the Worker yeehaw-agents secret. Proof of match without exposing it: Worker /selftest returned accepted:true (HTTP 201) and the sandbox publisher got 201 with the same value.
- **Self-inflicted incident, detected by the engine.** Redeploying with `deploymentId` (redeploy) built WITHOUT the console/backend root: api.yeehaw.pro served the static site's 404 HTML page instead of JSON (the Nov 27 wrong-directory failure mode). I saw it in my own probe, rolled back (request_rollback) and API /healthz returned {"ok":true} again within about a minute. Then deployed from gitSource with rootDirectory console/backend (dpl_E1nWM9uAgN8R622fCuRWKLAV1Do3, commit 7e0a22e) and promoted it. The Worker's own probes recorded the outage and the engine opened critical finding #2 "wrong-deployment (api)" with the evidence "api /livez: HTTP 404 returned an HTML page where the API must answer JSON". Lesson: never use deploymentId redeploys for this project; always gitSource + rootDirectory.
- Tool reports posted to production (HTTP 201): security-scan (30/30 live API checks, 0 secret hits, 1 manifest finding), android-analysis (2 static findings; limitation text included, no device), 3 perf-bench rows (4 -> 1 DB round trips per boot request; ms values are local, labelled).
- Engine pass on production: run 8, all seven specialists state ok, per-agent ms recorded, fleet ok, db_ms 9.

## Production = 66d2136 (dpl_7yZFX6kBPUxKtdro9YLExW9FCbZL), 2026-11-27 ~16:00 UTC
Branch head and production differed only by docs/tests/orchestrator changes before this deploy; now identical code. Verified after deploy: /healthz, /v1/config/app-config 200, /v1/agent/report 401 without token and 200 with it, Worker /selftest accepted, site 200.

## The 12 scenarios and their evidence class (honest)
| # | Scenario | Local | Preview | Production |
|---|---|---|---|---|
| 1 | API failure detection | yes (watch tests) | none | YES: finding #2, HTML 404 where JSON expected, seen by the Worker |
| 2 | Diagnostic investigation | yes | none | YES: finding #2 has root cause + evidence + next step |
| 3 | Orchestrator assignment | yes (incident -> infrastructure vs availability) | none | YES: finding #2 owned by infrastructure |
| 4 | Real repair preparation | yes (isolated worktree/branch/commit, orchestration.test.js) | none | NO (the code writer is scripted in tests; no AI code repair has run in production) |
| 5 | Automated tests | yes (before/after counts in evidence) | none | NO |
| 6 | Independent QA | yes (separate run id, fresh checkout, exact commit) | none | NO for code repairs; production orchestrator verification of server-side repairs: none occurred yet |
| 7 | Console history | yes (UI render test, 8 agents, audit rows) | none | rows persisted in production DB; console not viewed with production data (no ADMIN_TOKEN) |
| 8 | Failed repair rejected | yes (cheat fix, still-failing fix, deleted tests) | none | NO |
| 9 | Protected operations need approval | yes (agent token cannot approve; main/base refused; non-allowlisted refused) | none | production 401s for no/wrong tokens: YES |
| 10 | Website outage vs API outage | yes | none | partly: the Worker classified the real wrong-deployment case; a pure website outage was not induced |
| 11 | Wrong Vercel configuration | yes | none | YES (real incident, auto-resolved by run 9) |
| 12 | Measurable performance improvement | yes (4 -> 1 DB round trips per boot; ms local) | none | report posted to production; improvement itself measured locally only |
No preview deployments were used.

## Final push evidence (2026-11-28, commit 9dad5d9 build; later commits are agents/docs only)
**Autonomous pass (production):** the `yeehaw-agents` Worker now POSTs `/v1/agent/tick` itself (hourly while healthy, at once on a state change, 5 min during incidents, retry 10 min). Deployed 16:06:26Z; `agent_runs` id 11 started 16:06:54Z with no manual call.
**AI repair:** defect in `scanAndroidManifest` (pin regex spanned domain-configs). Model-written proposal (docs/evidence/ai-repair-model-answer.json) -> worktree -> tests 1 failed -> 0 failed -> QA fresh checkout verified. Negative control (wrong proposal) rejected at tests (docs/evidence/ai-repair-rejected-control.json). Model = a Claude instance in this session, not a production API call.
**Android build (GitHub Actions, real Gradle):** compileDebugKotlin, testDebugUnitTest, assembleDebug, assembleRelease (unsigned, R8), lint: BUILD SUCCESSFUL. Release APK 957,456 bytes (limit 12,582,912).
**Emulator measurements (x86 emulator on a CI runner; NOT a physical phone), API 23, instrumented QuickTools suite passed:**
- 1 GB RAM / 1 core profile: cold start TotalTime 612, 815, 721, 598, 723 ms (median 721); PSS total 66,610 KB (Java heap 4,352 KB, native 8,660 KB).
- 1.5 GB profile: cold start 793, 641, 822, 717, 662 ms (median 717); PSS 67,499 KB.
**Not measured by any workflow yet:** CPU %, frame jank, data consumption, battery, low-bandwidth and network-interruption behaviour on a device; API 35/36 emulator jobs failed on Maven 429 rate limiting (re-run requested).

## Final evidence matrix (2026-11-28, branch wip/quick-tools; production API = 66d2136, site Worker redeployed with /download/)
Class: P = production, L = local (embedded Postgres/unit), E = emulator in GitHub Actions CI (x86 emulator, NOT a physical phone), CI = GitHub Actions, N = not tested.
| # | Scenario | Agent | Class | Result / limitation |
|---|---|---|---|---|
| 1 | API failure detection | availability (Worker probe) | P | finding #2 recorded from the live API 404 |
| 2 | Diagnostic investigation | diagnostic | P | root cause + evidence + next step stored in finding #2 |
| 3 | Orchestrator assignment | orchestrator | P | finding #2 owned by infrastructure; auto-resolved in run 9 |
| 4 | Code repair preparation | repair | L + CI | real model-assisted fix of scanner defect (isolated worktree, tests, QA, rejected control) done with a Claude instance in this session. The unattended GitHub workflow (needs ANTHROPIC_API_KEY secret) has NOT run: N for production |
| 5 | Automated tests | qa | L + CI + E | agents tests, repair tests (19), watch tests (16), Android unit tests, instrumented suite on emulators |
| 6 | Independent QA | qa | L | separate run id and fresh checkout; N in production until the unattended workflow has a key |
| 7 | Console history | console | P data / L UI | production DB holds runs 1-12, 2 findings, 4 actions (read by SQL). The founder's own-browser view of /admin/ not yet confirmed by the owner |
| 8 | Failed repair rejected | qa | L + CI | negative-control run rejected; N in production |
| 9 | Protected ops need founder approval | orchestrator | L + P | agent token cannot approve; production returns 401 without/with wrong token |
| 10 | Website vs API outage | availability | L; partial P | Worker classified the real wrong-deployment incident; a pure website outage was not induced |
| 11 | Wrong Vercel configuration | diagnostic | P | real incident, resolved automatically |
| 12 | Measurable performance gain | performance | L (+E for Android) | 4 -> 1 DB round trips per boot (local timing). Android cold start/memory are emulator numbers |

Autonomous engine (P): Worker `yeehaw-agents` running since 15:29Z, probes every 30 s; orchestrator runs 11 (16:06Z) and 12 (17:06Z) were started by the Worker itself, no manual call; 0 tick failures.
Android (CI): debug+release build, lint, unit tests green; release workflow published a test APK and install-verified it on Android 6 (1 GB / 1 core) and Android 12 emulators. Browser regression suite (9 tests) passed on API 29 and API 35 emulators; API 23 failed one back-navigation test (test made URL-aware in a6c352c, re-run pending); API 36 untested after a Maven failure (earlier WebView native crash in LocalizationTest unresolved). Emulator frame stats are software-rendered and not meaningful.
Not tested: any physical phone, real mobile networks, battery, Google Play pre-launch, production-signed APK/AAB, unattended AI repair in CI, owner view of the production console, GitHub Actions secrets (CONSOLE_URL/AGENT_TOKEN empty), infrastructure collector with real read-only tokens.

## Android emulator results, run 46042886804 (commit 197c139) — EMULATOR numbers (x86 on a CI runner), not a physical phone
Full instrumented suite incl. the 9 browser regression tests: PASS on API 23 standard (1.5 GB/2 cores), API 23 low-end (1 GB/1 core), API 23 low-end + EDGE link (-netspeed edge -netdelay umts), API 29, API 35. API 36: FAIL (see below).
| Metric (ms unless noted) | API23 std | API23 1GB/1core | API23 1GB/1core EDGE | API29 | API35 |
|---|---|---|---|---|---|
| plain-HTTP page load | 350 | 320 | 229 | 158 | 189 |
| link tap -> new page | 195 | 336 | 135 | 132 | 194 |
| HTTPS (example.com, real internet) | 758 | 801 | 10,887 | 407 | 468 |
| 6 KB page at ~2 KB/s | 908 | 690 | 815 | 110 | 69 |
| cut mid-page -> recovered after link returns | 1,974 | 2,017 | 1,983 | 85 | 164 |
| retry panel shown on interruption | yes | yes | yes | yes | yes |
| cached resource, 2 visits -> server hits | 1 | 1 | 1 | 1 | 1 |
| swipe scrolled page (scrollY px) | 1,590 | 1,648 | 1,654 | 878 | 926 |
Cold start (earlier runs, same builds): API 23 1GB/1core 563-896 ms; EDGE profile 654-836 ms; app memory about 69 MB PSS (MemTotal 1.0 GB). Emulator frame stats are software-rendered (all frames "janky") and are not reported as phone performance. Caveat: the EDGE profile's 10.9 s HTTPS load is the shaped link, as intended.
API 36 (default userdebug image): browser tests fail because the system WebView crashes (SIGTRAP in Chrome_IOThread, all frames inside libwebviewchromium.so, no app frames). Reproduced in every run. Isolation experiment with a google_apis image: run 46128378585. Treated as an emulator-image problem, not proven: no physical Android 16 device tested.

### API 36 isolation result (run 46128378585, commit 940f2b7)
The same 9 browser regression tests PASS on an API 36 `google_apis` emulator image (job 140927967071); on the `default` userdebug image they still fail with the system-WebView SIGTRAP (Chrome_IOThread, no app frames). Conclusion: the API 36 crash is specific to the default test image's bundled WebView, not reproduced with the app on a google_apis image. Still emulator-only; no physical Android 16 phone tested. CI now includes both API 36 images.
The EDGE-profile job failed once in that run with "HTTPS page must load": the real-internet HTTPS load over the deliberately throttled link (10.9 s earlier) exceeded 60 s on that run. The wait limit was raised to 150 s; the time is recorded, not hidden.

## Feed optimisation (Facebook-family) — commit 0bc6ffb
Built in, always on, no new UI strings: `core/TrackerPolicy.kt`. (1) Third-party tracker/ad hosts (analytics, ad networks, Meta pixel/SDK on non-Facebook sites) get an empty response; never main-frame, never the page's own hosts, fbcdn content untouched on Facebook. (2) On facebook.com/instagram.com/messenger.com pages, images/iframes are marked lazy and video preload/autoplay is turned off, also for items added while scrolling. Existing: autoplay already required a user gesture; Turbo/adaptive saver already skips heavy assets on poor signal.
Evidence: unit tests (TrackerPolicyTest, 4 tests) and Android build/lint/unit tests green in CI (run 46136239425; release APK 959,128 bytes). NOT measured: real load-time or scroll-smoothness gain on a logged-in Facebook feed (needs a real account/phone; CI cannot log in). Claimed benefit is therefore design intent, not a measurement. Emulator suite on this commit still to run.

## Personal greeting on the start page — commit after 7bc742a
"Good morning, John. I'm YeeHaw. It's 8:20 AM, Tuesday, December 1. Let's surf." Pure local JavaScript in the start-page asset (no network, no native code, no permissions); nothing runs outside the start page.
- Name: only what the user types (pencil button), stored in the start page's local storage on the phone; first word only, honorifics skipped, anything that is not a clean first name is rejected (no name rather than a wrong one). Never read from the device, accounts or contacts.
- Date/time: device clock via Intl, localized per language, refreshed each minute and when the page becomes visible again.
- Toggle: on by default; the cross button hides it, the small smiley restores it.
- Voice: tap-only speaker button (browser speech synthesis, shown only if the engine exists). Not automatic: Chromium blocks speech without a tap, and speaking out loud on launch would be intrusive in public.
- Languages: 25 hand-checked (en es pt fr de it nl id ms fil tr ru uk pl ar ur fa hi bn ja ko zh zh-TW vi th). The start page has 80 UI languages; for the other 55 NO greeting is shown (never a wrong one) until native speakers confirm phrases. All 25 are machine-assisted drafts that still need native-speaker review before store launch.
- Tests: scripts/test-greeting.mjs (25 languages x 13 hours x with/without name, clock/date, name cleaning, hostile input, RTL) in `npm run check`. Not tested: a real WebView rendering of the greeting on a phone.

### Greeting language coverage, ordered by country population (37 languages; every one is a machine-assisted draft needing native-speaker review)
Top countries and their main languages: India (hi, bn, te, mr, ta, gu, kn, ml, pa, en) · China (zh) · USA (en, es) · Indonesia (id) · Pakistan (ur, en; Punjabi in Pakistan uses Shahmukhi script, so the Gurmukhi greeting is hidden for pa-PK) · Nigeria (en, ha; yo, ig not yet) · Brazil (pt) · Bangladesh (bn) · Russia (ru) · Ethiopia (am; Oromo not yet) · Mexico (es) · Japan (ja) · Egypt (ar) · Philippines (fil, en) · DR Congo (fr, sw; Lingala not yet) · Vietnam (vi) · Iran (fa) · Turkey (tr) · Germany (de) · Thailand (th). Also: ko, ms, it, nl, pl, uk, my, ne. Not yet: yo, ig, om, ln, or, as, si, km, kk, uz, ps, sd and the other start-page languages. A language whose dates the WebView cannot format shows no greeting.
