# Known issues and blockers (updated 2026-11-25)

Category key: APP = application defect, BUILD, INFRA, PROVIDER config, OWNER (needs owner authorization), ENV (testing-environment limit).

| # | Issue | Category | Status / shortest path |
|---|-------|----------|------------------------|
| 1 | Emulator tests cannot run on GitHub-hosted runners (no KVM; "hardware acceleration is not available"). Runs 45578021287, 45578784748, 45579043449. | ENV | Repo is private, so GitHub gives no KVM on standard runners (KVM is offered on public-repo standard runners and on paid larger runners); the x86-only emulator action cannot fall back to software emulation (tried, run 45587739979). Options: Firebase Test Lab (real API 23 devices, needs a Google Cloud service account), a paid larger runner, or a self-hosted KVM runner; API 23 needs its own device row. Not an app failure. |
| 2 | No physical-device measurements exist (cold start, RSS, data use, 2G/3G, airplane mode). | ENV/OWNER | Owner runs the test on a 2 GB phone, or a device farm. Nothing in DEVICE_TEST_RESULTS.md may be filled in until measured. |
| 3 | Live Vercel API (`yeehaw-api`) still serves old code; git-source deploy failed with `incorrect_git_source_info`. | PROVIDER | Link GitHub repo to the Vercel project (owner OAuth) or deploy from an uploaded build. |
| 4 | `entitlements` migration not applied to live Neon. | OWNER/PROVIDER | Additive `CREATE TABLE IF NOT EXISTS`; apply after taking a Neon branch/snapshot. No data reset. |
| 5 | Admin token and Neon password were exposed in earlier sessions. | OWNER | Rotate in the Vercel and Neon dashboards. Never paste the new values in chat. |
| 6 | Google Play verification needs a service account and a Play app with a `yeehaw_pro` subscription. | OWNER | Create the service account in Play Console and set `PLAY_SERVICE_ACCOUNT_JSON` / `PLAY_PACKAGE_NAME` in Vercel. Until then Pro fails closed. |
| 7 | Release signing keystore not provided. | OWNER | Add keystore secrets in GitHub Settings. Release APK is currently unsigned. |
| 8 | Ads are disabled: no AdMob SDK and no real IDs. | OWNER | Provide real AdMob IDs; evaluate low-RAM impact first. |
| 9 | `api.yeehaw.pro` CNAME not added. | OWNER | Add the CNAME in the DNS provider. |
| 10 | Cloudflare dashboard build command cannot be read from here. | OWNER | Confirm `npm ci && npm run build`, output `./public`. |
| 11 | Vercel Hobby plan forbids commercial use. | OWNER | Upgrade before charging users. |
| 12 | Play closed test: 12 testers x 14 days before production on new personal accounts. | OWNER | Recruit testers. |
| 13 | `yehaw_app/` is a legacy, separate project (`co.yehaw.browser`). | APP | Preserved, not deleted. Not part of the shipped app. Decide on archiving. |
| 14 | 80-language strings are machine-prepared, not reviewed by native speakers. | APP | Native review needed before claiming quality. |
| 15 | No JVM unit tests yet for the pure-logic helpers. | APP | Adding in P1. |
