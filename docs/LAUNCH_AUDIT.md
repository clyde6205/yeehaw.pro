# YeeHaw™ Launch Audit

Branch `integration/reconcile-main` @ `780d2a7` · PR #12 into `main` (unmerged; `main` untouched at 3c73d6a) · audited 2026-11-25.
Vision: fast browser for low-memory, low-grade phones on weak networks; spreads by QR/WhatsApp/Messenger/Telegram/Viber/SMS/Bluetooth; no login; free tier (consented ads) + server-verified Pro.

Every line carries one of five statuses. **Complete and verified** means a command, CI run or measurement is cited. Nothing is marked verified from reading code.

Legend: **CV** Complete and verified · **IV** Implemented but not verified · **PC** Partially complete · **BLK** Blocked by external provider or owner action · **NI** Not implemented

## 1. Repository and build (P0/P1)
| Requirement | Status | Evidence / gap |
|---|---|---|
| Repo integrated on one branch, PR open, `main` untouched | CV | `git ls-remote` shows branch = HEAD; PR #12 |
| Android compiles (debug + R8 release) | CV | Android build run 45594995936 success. Debug ≈3.2 MB, release unsigned ≈0.5 MB (size gate 12 MiB in workflow) |
| JVM unit tests (policy, retry/guard logic, config rules, signature verify) | CV | Same run: `:app:testDebugUnitTest` passed (LogicTest, PerformancePolicyTest, ConfigRulesTest) |
| Android lint blocks regressions | CV | Added to CI this session. It caught two real defects (Base64 API 26 call reachable on API 23–25; fine-only location request broken on Android 12+); both fixed, lint now green |
| Backend tests with Postgres in CI | CV | backend-tests workflow success on f6033ff; re-run on later commits via workflow |
| Static site build + checks in CI | CV | "Static product site checks" success on 8d0f69ce |
| Dependency vulnerability audit | PC | npm audit gate (high+) in backend CI after express 4.21.2→4.22.3 fixed 3 high advisories; site has 0. Gradle dependency audit not yet added |
| Emulator / API 23 instrumented tests | BLK | Private repo has no KVM on standard runners; software emulation unsupported by the action. Needs Firebase Test Lab (GCP service account), larger paid runner, self-hosted KVM, or public repo. Workflow honestly reports "NOT run (infrastructure)" |
| Signed release APK/AAB | BLK | No keystore secrets supplied |

## 2. Core browser (P2)
| Requirement | Status | Evidence / gap |
|---|---|---|
| Low-memory tab policy (live-tab cap by RAM class, eviction, restore, 12-tab ceiling) | IV | Logic unit-tested; behaviour on a 2 GB device not run |
| `onTrimMemory` / renderer-death rescue | IV | Code audited; no forced-kill device test |
| Weak-network retry (GET-only, capped backoff), failed-page panel | IV | Backoff logic unit-tested; no throttled-network run. POST auto-retry duplicate-submit bug fixed |
| Data Saver (Save-Data header on page requests, request skipping, honest counters) | PC | Mechanism implemented; **no measured savings exist**; WebView cannot add headers to subresources (documented) |
| Savings meter reports measured vs estimated separately | IV | Reviewed; needs comparison with TrafficStats on device |
| Private browsing (own process, separate data dir, wiped on exit, API 28+) | IV | Manifest and code audited; not run. Unavailable below API 28 by design (message shown) |
| Storage guard (refuse downloads when critically low) | IV | Code path added; not run on device |
| Bookmarks/history: single delete, confirmed clear-all | IV | Implemented; unit coverage none; not run |
| Every visible control works | PC | Audited by reading; no click-through on device |
| Device test matrix results | BLK | `DEVICE_TEST_RESULTS.md` has no numbers by design; needs a real phone or device farm |

## 3. Production infrastructure (P3)
| Requirement | Status | Evidence / gap |
|---|---|---|
| Neon `entitlements` migration (additive) | CV | Applied to live `yeehaw`; table present, other tables untouched |
| Backend `/v1/pro/verify`, admin subscriptions, signed config | CV (tests) / BLK (live) | Backend tests pass in CI; **live API still runs old code** |
| Vercel API deployed from this branch | BLK | Vercel GitHub app cannot see the private repo; owner must connect repo with Root Directory `console/backend` |
| `api.yeehaw.pro` DNS | BLK | Owner adds CNAME |
| Cloudflare site deploy of this branch | BLK | Build/check pass in CI; production state unreadable from sandbox; dashboard command to confirm: `npm ci && npm run build`, output `./public` |
| Vercel plan allows commercial use | BLK | Hobby forbids it; upgrade before charging |

## 4. Founder console and monetization (P4)
| Requirement | Status | Evidence / gap |
|---|---|---|
| Admin API: devices, config, blacklist, subscriptions view | CV (tests) | Backend tests |
| Roles, sessions, 2FA, richer user/entitlement management, security events | NI | Single bearer token today |
| Server-verified Pro (fails closed) | IV | Verifier wired and tested with mocks; real Play call untestable without credentials |
| Play service account / product | BLK | Owner creates in Play Console |
| Ads | BLK | No SDK bundled, no real AdMob IDs (docs/ADS_DECISION.md) |
| Rotate admin token / Neon password | BLK (owner declined 2026-11-25) | Accepted risk; recorded in KNOWN_ISSUES |

## 5. Site, i18n, accessibility, sharing, privacy (P5)
| Requirement | Status | Evidence / gap |
|---|---|---|
| Start page layout preserved, only additive icons | IV | Reviewed against v0.4.6 layout; no screenshot comparison |
| Marketing site, privacy, terms, support pages | CV (build) | Static checks pass; live rendering not checked from sandbox |
| Public site shows no GitHub repo/Actions links | CV | Removed; static check passes |
| Support email address | BLK | Owner must provide |
| 80 start-page languages | PC | Machine-prepared labels; not native-reviewed; other screens untranslated |
| RTL (ar, he, fa, ur, ps, sd) | IV | Direction set; not visually checked |
| TalkBack, font scaling, contrast, touch targets | NI | No audit performed yet |
| Share flows (QR, WhatsApp, Messenger, Telegram, Viber, SMS, Bluetooth) | IV | Wired; none exercised on device |
| Permissions minimal and justified | CV (review) | docs/PERMISSIONS_REVIEW.md; lint-driven location fix applied |
| Privacy policy + Data Safety answers | PC | Policy page exists; Play Data Safety form not filed |

## 6. Release (P6)
| Requirement | Status | Evidence / gap |
|---|---|---|
| Release checklist, security audit, deployment docs | CV | docs/RELEASE_CHECKLIST.md, SECURITY_AUDIT.md, PRODUCTION_DEPLOYMENT.md |
| Play listing, content rating, Data Safety, signed AAB | BLK | Owner actions; de-prioritized per owner |
| New-account closed test (12 testers × 14 days) | BLK | Owner; public Play launch cannot be same-day |
| Trademark search, legal review of disclaimers | BLK | Owner/lawyer |

## Honest bottom line
The code builds, lints clean and passes its unit and backend tests in CI. It has **never been run on a device or emulator**, the live API is **not** on this code, and nothing about speed, memory or data savings has been **measured**. Those are the three gaps between "builds" and "ready for launch".
