# YeeHaw™ Browser — Full Audit and GPT Coding Handoff
Audit date: 2026-11-25 (Manila). Repo `clyde6205/yeehaw.pro`, branch `feature/low-signal-watchdog-savings` (head `4cfb66d`), PR #11 into `main`, nothing merged.
Status key: ✅ proven with evidence · 🟡 written/compiled but not exercised · ❌ not done · ⚠️ risk.
**Rule for the auditor: a ✅ needs the evidence named in its row. If you cannot reproduce it, downgrade it.**

## 0. Mission (never lose this)
Win billions of users on **low-memory, low-grade phones** in the most populous, poorest countries. Growth by sharing: **QR first**, then WhatsApp, Messenger, Telegram, Viber, SMS, Bluetooth/Nearby APK hand-over. No mandatory login. Free tier (consented ads) + Pro (server-verified). Never claim to add RAM/signal/storage; never auto-delete user data. Start page layout is FIXED (screenshot v0.4.6): only additive tap icons allowed.

## 1. Architecture snapshot
- Android app: Kotlin, native `Activity` + `WebView`, minSdk 23, target/compile 36, package `app.yehaw.browser`, v0.6.0 (code 20). ~4,900 Kotlin lines; `MainActivity.kt` ≈ 2,000 lines (a refactor risk).
- Start page: asset `startpage.html` served at `start.yeehaw.internal` via `shouldInterceptRequest`; `?a=` action router; 80-language i18n inline; stats line cycles 3 views on tap.
- `core/`: `SignalMonitor`, `RetryScheduler`, `SavingsMeter`, `Guardian` (crash relaunch, crash-loop safe start, freeze watchdog, renderer rescue, DB repair), `AppTools` (StorageGuard, DataSaverPolicy, QuickPasteStore, ClipboardWatcher, ScreenshotTool, MarkupView).
- `console/` (in app): TelemetryManager, RemoteConfigManager (ECDSA P-256 signed), DeviceStatusManager, HealthReporter, ProEntitlement, ShareManager + QrCode, SearchRoutingEngine, ConsoleBootstrap. **No AdMob SDK bundled** (decision below).
- Backend `console/backend`: Node 22, Express 4.21, pg, helmet, rate limits. Routes: `/healthz`, `/v1/config/app-config` (signed), `/v1/device/status` (signed), `/v1/telemetry/{boot,forget,ad-event,share-event,health}`, `/v1/pro/verify` (signed, new), `/v1/admin/*` (bearer), `/admin` (vanilla-JS founder console).
- Infra: Neon Postgres project `rapid-hat-12220373` (db `yeehaw`, schema v2 migrated; **`entitlements` table NOT yet migrated live**), Vercel project `yeehaw-api` (alias https://yeehaw-api.vercel.app, still OLD code), Cloudflare Worker `yeehaw-pro` (site), domain at Porkbun with Cloudflare DNS. `api.yeehaw.pro` needs a DNS CNAME to `042ef5a15772dae0.vercel-dns-017.com` (DNS only).

## 2. Evidence ledger
| # | Claim | Status | Evidence / how to reproduce |
|---|---|---|---|
| 1 | v0.6.0 compiles and builds | ✅ | GitHub Actions "Android build" run 45578016894 green on `cb605a2`; artifact `yehaw-android-apks` |
| 2 | Release APK small | ✅ | Unsigned R8 release 496,632 B; debug 3.2 MB (limit 12 MiB enforced by workflow) |
| 3 | Release APK is signed/installable | ❌ | Secrets `ANDROID_KEYSTORE_*` absent → unsigned. Cannot install/upload |
| 4 | Backend API tests | ✅ | `cd console/backend && npm ci && npm test` → 28/28 pass vs local Postgres 16 |
| 5 | Founder console UI | 🟡 | Playwright E2E passed on SEEDED local data only (not real users) |
| 6 | Emulator smoke tests | ❌ | CI runners have no KVM ("hardware acceleration is not available"); runs 45578784748, 45579043449 failed before any app test |
| 7 | Real-device performance (2 GB phone: cold start, RSS, jank) | ❌ | Never measured. All speed/memory claims are unverified |
| 8 | Weak-signal mode, Guardian self-repair, savings meter | 🟡 | Compiles; logic unit-checked with stubs earlier; never run on a device or under throttled network |
| 9 | "Data avoided" number | ⚠️ | ESTIMATE (conservative per-type averages), labelled "est." Exact counters: requests skipped, weak-signal seconds, app bytes (TrafficStats), offline bytes reused |
| 10 | Apps tools (Memory Saver, Adaptive Data Saver, Storage Protection, Quick Paste, Clipboard Convenience, Screenshot & Markup, Offline Reading) | 🟡 | Compiles in #1; no manual run-through; Screenshot save/share needs Android 10+ for share (older: saved to app folder) |
| 11 | Share menu (QR, WhatsApp, Messenger, Telegram, Viber, SMS, Bluetooth APK) | 🟡 | QR generator verified earlier by decoding 23 codes with OpenCV; app flow not run. WhatsApp rejects APK files; Play-installed (split) apps can't re-share the APK |
| 12 | App → console reporting | 🟡 | Code wired (boot, health deltas, signed config, device block/update, blacklist, search routing). Console will show zeros until live API is updated and a real build runs |
| 13 | Live API updated | ❌ | Vercel `create_deployment` from git failed (`incorrect_git_source_info`). Live API = old version. Needs Vercel↔GitHub link (root `console/backend`) or `vercel deploy --prod` |
| 14 | Server-verified Pro | 🟡 | `/v1/pro/verify` + signed answer + client `ProEntitlement` + purchase acknowledge. Play API call never exercised (needs `PLAY_SERVICE_ACCOUNT_JSON`). Unacknowledged purchases are auto-refunded after 3 days — ack happens only after verify succeeds |
| 15 | Ads | ❌ | No AdMob SDK (adds MBs + RAM). Needs real AdMob app/unit ids, UMP consent, low-RAM skip. See `docs/ADS_DECISION.md`. Old docs claim "AdMob already in app" — wrong |
| 16 | Permissions | ✅ | `docs/PERMISSIONS_REVIEW.md`: camera/mic/location runtime-only per-site; hardware features optional; cleartext kept (HTTP sites) but app's own calls HTTPS-only |
| 17 | Website yeehaw.pro | 🟡 | `npm run build && npm run check` pass locally and in CI. Live site status unconfirmed (sandbox cannot reach it). Cloudflare build command must be `npm ci && npm run build` (docs/CLOUDFLARE_DEPLOY.md). Earlier finding: repo root may have been deployed as assets (source exposure) — verify |
| 18 | 80-language start page | 🟡 | Tested 7 locales in Chromium, no script errors. Machine-assisted; **needs native review**. Only start-page labels translated; tagline sub-line, stats views 2–3 and all other screens are English |
| 19 | ™ and disclaimers | 🟡 | ™ on brand + legal line. `docs/LEGAL_DISCLAIMERS.md` draft needs a lawyer; no trademark search done |
| 20 | CI supply chain | ✅ | All workflow actions pinned to full commit SHAs (repo policy forced this) |
| 21 | Google Play launch today | ❌ | New personal accounts need closed test: ≥12 testers, 14 consecutive days (https://support.google.com/googleplay/android-developer/answer/14151465). Page doesn't cover organization accounts |

## 3. Known defects and risks (ranked)
1. ⚠️ **Nothing has run on a phone.** Every performance, memory and "works on 2G" claim is untested. Highest-priority unknown.
2. ⚠️ **Secrets exposure:** admin token and Neon password were shown in earlier chat sessions — rotate both. Keystore and Play service account not yet created.
3. ⚠️ **Vercel Hobby plan forbids commercial use** → move API to a paid host before launch.
4. ⚠️ Blocked-device control (`DeviceStatusManager`) shows a non-dismissable dialog then `finish()`: a wrong admin click locks a user out. Add confirmation + audit in console.
5. ⚠️ `MainActivity` is ~2,000 lines; high regression risk. Extract features into classes before adding more.
6. ⚠️ Screenshot via `WebView.draw()` captures only the visible area and can be blank with hardware-accelerated layers on some devices — verify.
7. ⚠️ Clipboard Convenience reads clipboard on window focus (opt-in). Android 13+ shows a system "pasted from" toast — acceptable, document in privacy policy.
8. ⚠️ Guardian relaunch via AlarmManager is best-effort on Android 10+ (background-start limits).
9. ⚠️ `adaptiveSaver` defaults ON and Turbo defaults ON: images/video are skipped by default. Product decision: confirm this is the intended default for first-run.
10. Data Safety form, privacy policy URL, content rating, target audience (13+; not a Families app) not filed.

## 4. Backlog for GPT (priority order, each with an acceptance test)
**P0 — launch blockers**
1. **Merge path:** review PR #11, confirm CI green, merge to `main`. *Accept:* `main` build green; tag `v0.6.0`.
2. **Signing:** generate upload keystore (owner stores offline), add the four `ANDROID_KEYSTORE_*` secrets via GitHub UI (never in chat). *Accept:* workflow prints `apksigner verify` OK.
3. **Deploy API:** link Vercel to the repo (root `console/backend`) or CLI deploy; run schema migration (creates `entitlements`); set `PLAY_SERVICE_ACCOUNT_JSON`, rotate `ADMIN_TOKEN` and Neon password; add `api.yeehaw.pro` CNAME. *Accept:* `curl https://api.yeehaw.pro/healthz` → `{"ok":true}`; `/v1/config/app-config` signature verifies with the app's public key; `POST /v1/pro/verify` with a Play test token returns signed `pro:true`.
4. **Real-device test** on a 2 GB Android Go phone (+ one API 23 phone): cold start time, RSS (`adb shell dumpsys meminfo app.yehaw.browser.debug`), data used per page vs Chrome, throttled 2G/3G, airplane mode, forced crash relaunch. *Accept:* a table of measured numbers in `docs/DEVICE_TEST_RESULTS.md` with device models and Android versions. Do NOT publish speed claims without it.
5. **Website:** fix Cloudflare build, confirm `https://yeehaw.pro/` and `/get/` return 200 and that repo source is NOT downloadable. *Accept:* `curl -I` outputs; `curl https://yeehaw.pro/app/build.gradle.kts` → 404.
6. **Play track:** create Play Console app, internal testing then closed test; recruit 12+ testers for 14 days. Fill Data Safety, privacy policy (https://yeehaw.pro/privacy), content rating. *Accept:* screenshots of completed forms; tester count.
**P1 — product completeness**
7. Automated device testing: Firebase Test Lab (Robo + `BrowserSmokeTest`) since GitHub runners lack KVM. *Accept:* passing report URL.
8. JVM unit tests for pure logic: `ProEntitlement.validUntil`, `DataSaverPolicy`, `ClipboardWatcher.isLink`, `QuickPasteStore.insertScript`, `DeviceStatusManager.safeUpdateUrl`, `SearchRouting`. Add `testImplementation` junit and run `:app:testDebugUnitTest` in CI. *Accept:* green CI.
9. Console: confirm-before-block, audit log display, "Pro entitlements" view, health charts from real data.
10. Ads (only after owner supplies AdMob ids): separate `ads` flavor, UMP, skip on `isLowRamDevice()` and Pro. *Accept:* release non-ads flavor stays < 1 MB.
11. Translate all remaining UI strings and the app's dialogs; native-speaker review of the 80 start-page rows (`docs` data lives in `startpage.html`). Verify RTL layout for ar/he/fa/ur/ps/sd on a device.
12. Refactor `MainActivity` into feature classes without changing behavior (smoke test before/after).
**P2**
13. Trademark search (PH IPOPHL, US, EU, IN); lawyer review of `LEGAL_DISCLAIMERS.md`; EU digital-consent ages.
14. Replace "Independent browser, not affiliated with Google" wording check with counsel; confirm no Chrome/Google logos used.
15. Accessibility pass (TalkBack, font scaling, contrast).

## 5. Rules for any coder (GPT or human) working on this repo
- Work on branches + PRs. No force-push. Never commit secrets, keystores, tokens, `.env`.
- Never change the start-page layout except additive tiles; verify with a screenshot against v0.4.6.
- Every claim of "works/fast/saves X" needs a measured number and the device it came from. Estimates must be labelled "est."
- Never auto-delete user data. Never add mandatory login. Network calls from the app: HTTPS only, to `api.yeehaw.pro` only, with consent where required (EEA/UK/CH).
- Keep release APK ≤ 12 MiB (currently ~0.5 MB) and avoid heavy SDKs; justify any dependency with its size/RAM cost.
- Pin GitHub Actions to full SHAs. No GraphQL `gh` calls from Claude sandboxes (REST only).
- After each change: build in CI, run backend tests, update `docs/LAUNCH_AUDIT.md` with evidence.

## 6. Prompt to give GPT (copy everything in the box)
```
You are the lead Android + backend engineer for YeeHaw™ Browser (repo clyde6205/yeehaw.pro, branch feature/low-signal-watchdog-savings, PR #11). Read docs/FULL_AUDIT_AND_GPT_HANDOFF.md first, then docs/LAUNCH_AUDIT.md and docs/INTEGRATION.md. The mission: a tiny, fast browser for low-memory, low-grade phones, spreading by QR/WhatsApp/Messenger/Telegram/Viber/SMS/Bluetooth, no login, free tier + server-verified Pro.
Work through section 4 backlog in order (P0 first). For every task: (1) make a branch, (2) change code, (3) run backend tests (cd console/backend && npm ci && npm test) and the GitHub Actions Android build, (4) report evidence (commands, run URLs, measured numbers), (5) update docs/LAUNCH_AUDIT.md. Do not claim anything works unless you have evidence; mark unverified items as unverified. Never print or commit secrets. Ask the owner before destructive actions (deleting workers, DNS, repos, secrets) and for anything needing their accounts (Play Console, AdMob, Porkbun, signing keys). Keep the start page layout unchanged. Keep the release APK under 12 MiB and avoid heavy SDKs.
Start now with P0 items 1–3, then ask the owner for the 2 GB test phone results (item 4).
```
