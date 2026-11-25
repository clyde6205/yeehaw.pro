# YeeHaw™ Launch Audit (for independent verification, e.g. by GPT)
Vision: a fast browser that works on low-memory, low-grade phones on weak networks, spreads by QR/WhatsApp/Messenger/Telegram/Viber/SMS/Bluetooth, no login, free (consented ads) + server-verified Pro.
Status key: ✅ done+evidenced · 🟡 written, unverified · ❌ not done. **Verifier: demand the evidence column.**

## A. Build & quality
- ✅ v0.6.0 compiles in CI: Android build run 45578016894 green on commit cb605a2. Debug APK 3.2 MB, release (unsigned, R8) 496,632 bytes, limit 12 MiB. Evidence: GitHub Actions run + artifact `yehaw-android-apks`.
- ❌ Unit/instrumented tests for core/ (SignalMonitor hysteresis, RetryScheduler, Guardian crash-loop).
- ❌ Lint/R8/ProGuard release check; APK size on release.
## B. Low-memory claim (core promise)
- 🟡 Emulator smoke test (CI, API 23/29/35/36) being re-run on a KVM runner; result in Actions. ❌ Real-device test on 2 GB phone: cold start, RSS, jank. Evidence: logs + numbers.
- ❌ Throttled 2G/3G + airplane-mode runs.
- 🟡 Savings meter: exact counters vs ESTIMATED avoided bytes. Evidence: compare against `TrafficStats` on device.
- ❌ Low-RAM behaviour (`onTrimMemory`, tab discard) verified.
## C. Self-repair
- 🟡 Crash relaunch, crash-loop safe start, freeze watchdog, renderer-death rescue, DB repair. Evidence: forced-crash test on device (Android 10+ background-start limits make relaunch best-effort).
## D. Apps-tile tools
- 🟡 Built and compiled (Apps tile → YeeHaw tools): Memory Saver, Adaptive Data Saver, Storage Protection, Quick Paste, Clipboard Convenience (opt-in), Screenshot & Markup, Offline Reading. Not yet exercised on a device. Evidence needed: manual run-through.
## E. Growth/sharing
- 🟡 Share menu wired (QR, WhatsApp, Messenger, Telegram, Viber, SMS, Bluetooth/Nearby APK). ❌ Verify QR, WhatsApp/Messenger/Telegram/Viber/SMS, Bluetooth APK share on device; Play policy on sharing APKs.
## F. Backend & founder console
- ✅ 27 backend tests pass locally; browser E2E on seeded (fake) data.
- ❌ Live API not updated (Vercel git source failed). 🟡 App now wired to console (telemetry boot, health counts, signed config, device block/update, blacklist, search routing); needs the API deployed to see data.
- 🟡 Server-verified Pro: `/v1/pro/verify` + signed answer + client (backend 28/28 tests; Play call untested without credentials). ❌ Needs `PLAY_SERVICE_ACCOUNT_JSON`.
- ❌ Ads: AdMob SDK intentionally NOT bundled (size/RAM); needs real AdMob ids — see docs/ADS_DECISION.md.
- ❌ Rotate admin token + Neon password. Vercel Hobby forbids commercial use → paid host. ❌ DNS CNAME api.yeehaw.pro.
## G. Security/privacy
- ✅ Permission review done (docs/PERMISSIONS_REVIEW.md): runtime-only prompts, hardware features optional, cleartext justified, app's own calls HTTPS-only.
- 🟡 Anonymous telemetry + consent. ❌ Privacy policy URL, Data Safety form.
## H. Web
- 🟡 Cloudflare: build+check pass in CI; dashboard build command must be `npm ci && npm run build` (docs/CLOUDFLARE_DEPLOY.md). Not confirmable from here. ❌ yeehaw.pro/get serves APK/Play link.
## I. i18n & legal
- 🟡 80 start-page languages (labels only; stats modes 2–3 and tagline sub-line English; other screens not translated). Native review required. RTL: ar, he, fa, ur, ps, sd.
- 🟡 ™ on brand/legal line. ❌ Trademark search. 🟡 Disclaimers in docs/LEGAL_DISCLAIMERS.md need lawyer review.
## J. Play release
- ❌ Signed AAB, Play App Signing, content rating, target-audience, Data Safety, store listing.
- ❌ New personal accounts need closed test: 12+ testers, 14 consecutive days, before production (support.google.com/googleplay/android-developer/answer/14151465). **Public Play launch cannot be same-day.**
