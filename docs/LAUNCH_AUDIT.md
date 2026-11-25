# YeeHaw™ Launch Audit (for independent verification, e.g. by GPT)
Vision: a fast browser that works on low-memory, low-grade phones on weak networks, spreads by QR/WhatsApp/Messenger/Telegram/Viber/SMS/Bluetooth, no login, free (consented ads) + server-verified Pro.
Status key: ✅ done+evidenced · 🟡 written, unverified · ❌ not done. **Verifier: demand the evidence column.**

## A. Build & quality
- 🟡 Kotlin signal/guardian/savings code written (branch `feature/low-signal-watchdog-savings`, PR #11). Evidence needed: green `./gradlew assembleRelease` in CI. `MainActivity` edits NEVER compiled.
- ❌ Unit/instrumented tests for core/ (SignalMonitor hysteresis, RetryScheduler, Guardian crash-loop).
- ❌ Lint/R8/ProGuard release check; APK size on release.
## B. Low-memory claim (core promise)
- ❌ Real-device test on 2 GB phone: cold start, RSS, jank. Evidence: logs + numbers.
- ❌ Throttled 2G/3G + airplane-mode runs.
- 🟡 Savings meter: exact counters vs ESTIMATED avoided bytes. Evidence: compare against `TrafficStats` on device.
- ❌ Low-RAM behaviour (`onTrimMemory`, tab discard) verified.
## C. Self-repair
- 🟡 Crash relaunch, crash-loop safe start, freeze watchdog, renderer-death rescue, DB repair. Evidence: forced-crash test on device (Android 10+ background-start limits make relaunch best-effort).
## D. Apps-tile tools
- ❌ Memory Saver, Adaptive Data Saver, Storage Protection, Quick Paste, Clipboard Convenience, Screenshot & Markup, Offline Reading — not built.
## E. Growth/sharing
- 🟡 Share tile exists. ❌ Verify QR, WhatsApp/Messenger/Telegram/Viber/SMS, Bluetooth APK share on device; Play policy on sharing APKs.
## F. Backend & founder console
- ✅ 27 backend tests pass locally; browser E2E on seeded (fake) data.
- ❌ Live API not updated (Vercel git source failed). ❌ App does not report to console (modules not wired) → console shows zeros.
- ❌ Server-verified Pro (entitlement still client-side). ❌ Real AdMob IDs + consent flow verified.
- ❌ Rotate admin token + Neon password. Vercel Hobby forbids commercial use → paid host. ❌ DNS CNAME api.yeehaw.pro.
## G. Security/privacy
- ❌ Permission review: CAMERA, RECORD_AUDIO, FINE_LOCATION, `usesCleartextTraffic=true` in manifest — justify or remove.
- 🟡 Anonymous telemetry + consent. ❌ Privacy policy URL, Data Safety form.
## H. Web
- ❌ Cloudflare site 404 fix (`scripts/check-static.js` path; generate `public/`). ❌ yeehaw.pro/get serves APK/Play link.
## I. i18n & legal
- 🟡 80 start-page languages (labels only; stats modes 2–3 and tagline sub-line English; other screens not translated). Native review required. RTL: ar, he, fa, ur, ps, sd.
- 🟡 ™ on brand/legal line. ❌ Trademark search. 🟡 Disclaimers in docs/LEGAL_DISCLAIMERS.md need lawyer review.
## J. Play release
- ❌ Signed AAB, Play App Signing, content rating, target-audience, Data Safety, store listing.
- ❌ New personal accounts need closed test: 12+ testers, 14 consecutive days, before production (support.google.com/googleplay/android-developer/answer/14151465). **Public Play launch cannot be same-day.**
