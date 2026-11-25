# Known issues and blockers (updated 2026-11-25)

Category key: APP = application defect, BUILD, INFRA, PROVIDER config, OWNER (needs owner authorization), ENV (testing-environment limit).

| # | Issue | Category | Status / shortest path |
|---|-------|----------|------------------------|
| 1 | ~~Emulator tests cannot run~~ RESOLVED 2026-11-25: the runner has working KVM (`emulator -accel-check`: "KVM (version 12) is installed and usable"). The earlier failures came from the android-emulator-runner action's cmdline-tools setup, not from missing KVM. Workflow now drives the SDK emulator directly. Run 45605985490: API 23, 29, 35, 36 all pass the 12 instrumented tests. | ENV (fixed) | Emulator results are not real-device results; see #2. |
| 2 | No physical-device measurements exist (cold start, RSS, data use, 2G/3G, airplane mode). | ENV/OWNER | Owner runs the test on a 2 GB phone, or a device farm. Nothing in DEVICE_TEST_RESULTS.md may be filled in until measured. |
| 3 | Live Vercel API (`yeehaw-api`, deployment dpl_ZbRCjj…) still serves OLD code (no `/v1/pro/verify`, no Subscriptions view, no Play wiring). Git-source deploy fails (`incorrect_git_source_info`: Vercel's GitHub app cannot see this private repo). Two file-upload deploy attempts today were incomplete (I omitted files); the first errored in build, the second was cancelled before it went live. Production was never switched. | PROVIDER/OWNER | Owner: Vercel project > Settings > Git > connect `clyde6205/yeehaw.pro` (install the Vercel GitHub app on the repo), Root Directory `console/backend`, production branch `main`. Then merge PR #12. |
| 4 | `entitlements` migration | DONE 2026-11-25 | Applied to live Neon `yeehaw` with an additive `CREATE TABLE IF NOT EXISTS`; table exists, 0 rows; other tables untouched (devices 0, remote_configs 1, admin_audit 0). |
| 5 | Admin token and Neon password were exposed in earlier sessions. | OWNER (accepted risk) | Owner decided 2026-11-25 to keep the originals. Rotation is still recommended before real users. |
| 6 | Google Play verification needs a service account and a Play app with a `yeehaw_pro` subscription. | OWNER | Create the service account in Play Console and set `PLAY_SERVICE_ACCOUNT_JSON` / `PLAY_PACKAGE_NAME` in Vercel. Until then Pro fails closed. |
| 7 | Release signing keystore not provided. | OWNER | Add keystore secrets in GitHub Settings. Release APK is currently unsigned. |
| 8 | Ads are disabled: no AdMob SDK and no real IDs. | OWNER | Provide real AdMob IDs; evaluate low-RAM impact first. |
| 9 | `api.yeehaw.pro` CNAME not added. | OWNER | Add the CNAME in the DNS provider. |
| 10 | Cloudflare dashboard build command cannot be read from here. | OWNER | Confirm `npm ci && npm run build`, output `./public`. |
| 11 | Vercel Hobby plan forbids commercial use. | OWNER | Upgrade before charging users. |
| 12 | Play closed test: 12 testers x 14 days before production on new personal accounts. | OWNER | Recruit testers. |
| 13 | `yehaw_app/` is a legacy, separate project (`co.yehaw.browser`). | APP | Preserved, not deleted. Not part of the shipped app. Decide on archiving. |
| 14 | 80-language strings are machine-prepared, not reviewed by native speakers. | APP | Native review needed before claiming quality. |
| 15 | JVM tests cover policy, retry/guard and config-signature logic only; no UI/WebView or instrumented tests. | APP/ENV | Needs emulator or device (see #1). Lint is now in CI. |
| 16 | No dependency vulnerability audit (npm/Gradle) in CI; no accessibility audit. | APP | Next P1/P5 items. |
| 17 | GitHub Actions is blocked on the owner's account: every job since ~19:45 fails before starting with "recent account payments have failed or your spending limit needs to be increased" (run 45616760757, attempts 1 and 2). Not a code failure. Commit after fdbdc47 (offline-list memory fix, retry-panel/private-process tests) has therefore NOT been verified by CI. | OWNER | GitHub > Settings > Billing & plans: fix the payment method and/or raise the Actions spending limit, then re-run `Android build` and `Android emulator smoke tests`. The sandbox cannot build Android locally (Google/Maven/Gradle hosts are unreachable). |
