# Known issues and blockers (updated 2026-11-25)

Category key: APP = application defect, BUILD, INFRA, PROVIDER config, OWNER (needs owner authorization), ENV (testing-environment limit).

| # | Issue | Category | Status / shortest path |
|---|-------|----------|------------------------|
| 1 | ~~Emulator tests cannot run~~ RESOLVED 2026-11-25: the runner has working KVM (`emulator -accel-check`: "KVM (version 12) is installed and usable"). The earlier failures came from the android-emulator-runner action's cmdline-tools setup, not from missing KVM. Workflow now drives the SDK emulator directly. Run 45605985490: API 23, 29, 35, 36 all pass the 12 instrumented tests. | ENV (fixed) | Emulator results are not real-device results; see #2. |
| 2 | No physical-device measurements exist (cold start, RSS, data use, 2G/3G, airplane mode). | ENV/OWNER | Owner runs the test on a 2 GB phone, or a device farm. Nothing in DEVICE_TEST_RESULTS.md may be filled in until measured. |
| 3 | ~~Live API served old code~~ RESOLVED 2026-11-26: production deployment `dpl_Dy6JjeDAZjeg4SWxpyu6tNG4bf61` (sha d781506, READY, region sin1) built from the now-public repo. Verified through Vercel: `/healthz` 200 (`SELECT 1` on Neon), `/v1/config/app-config` 200 with signature + ETag, `/v1/device/status` validates input (400 on bad id). Admin 401 and the other routes are covered by `backend-tests` against Postgres, not re-tested on production. | DONE | Production still depends on a git source; CI/GitHub can be disabled without affecting the running deployment. |
| 4 | `entitlements` migration | DONE 2026-11-25 | Applied to live Neon `yeehaw` with an additive `CREATE TABLE IF NOT EXISTS`; table exists, 0 rows; other tables untouched (devices 0, remote_configs 1, admin_audit 0). |
| 5 | Admin token and Neon password were exposed in earlier sessions. | OWNER (accepted risk) | Owner decided 2026-11-25 to keep the originals. Rotation is still recommended before real users. |
| 6 | Google Play verification needs a service account and a Play app with a `yeehaw_pro` subscription. | OWNER | Create the service account in Play Console and set `PLAY_SERVICE_ACCOUNT_JSON` / `PLAY_PACKAGE_NAME` in Vercel. Until then Pro fails closed. |
| 7 | Release signing keystore not provided. | OWNER | Add keystore secrets in GitHub Settings. Release APK is currently unsigned. |
| 8 | Ads are disabled: no AdMob SDK and no real IDs. | OWNER | Provide real AdMob IDs; evaluate low-RAM impact first. |
| 9 | `api.yeehaw.pro` is attached to the Vercel project but Vercel reports it misconfigured: the domain uses Cloudflare nameservers and has no record pointing to Vercel. The app's `API_BASE_URL` is this domain, so until fixed it cannot reach the live backend and uses cached/default config. | OWNER | In Cloudflare DNS add `CNAME api -> cname.vercel-dns.com`, proxy OFF (grey cloud). Then re-verify HTTPS and `/healthz`. |
| 10 | Cloudflare dashboard build command cannot be read from here. | OWNER | Confirm `npm ci && npm run build`, output `./public`. |
| 11 | Vercel Hobby plan forbids commercial use. | OWNER | Upgrade before charging users. |
| 12 | Play closed test: 12 testers x 14 days before production on new personal accounts. | OWNER | Recruit testers. |
| 13 | `yehaw_app/` is a legacy, separate project (`co.yehaw.browser`). | APP | Preserved, not deleted. Not part of the shipped app. Decide on archiving. |
| 14 | 80-language strings are machine-prepared, not reviewed by native speakers. | APP | Native review needed before claiming quality. |
| 15 | Emulator-only evidence: 19 instrumented tests pass on API 23/29/35/36 emulators (commit 83c6570), plus JVM unit tests. No physical low-end phone has been tested. | APP/OWNER | Needs a real 2 GB device run. |
| 16 | No dependency vulnerability audit (npm/Gradle) in CI; no accessibility audit. | APP | Next P1/P5 items. |
| 17 | ~~GitHub Actions blocked by account billing~~ WORKED AROUND 2026-11-26 by making the repo public (public-repo Actions are free). All jobs run again. Keep the repo public only while it holds no private material; nothing secret was found in a full history scan. | OWNER (done) | Decide later whether to return to private and fix billing. |
