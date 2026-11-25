# Android launch checklist

## Verify on real hardware (not done in CI)
- [ ] 2 GB Android Go device (itel/Infinix/Tecno/Redmi): `adb shell dumpsys meminfo co.yehaw.browser` after cold start (target PSS under 80 MB) and with 5+ tabs (under 180 MB). Renderer memory is a separate process; also check `adb shell dumpsys meminfo | grep -i webview`.
- [ ] Memory pressure: `adb shell am send-trim-memory co.yehaw.browser MODERATE` / `COMPLETE`; confirm tabs sleep and restore with scroll position and back-stack.
- [ ] Airplane mode: restore a sleeping tab, open a saved `.mhtml`, resume a download after reconnect.
- [ ] Default-browser role prompt on Android 10, 12, 14, 15 Go.
- [ ] Two Shopee/Facebook accounts side by side (Profiles); cookies do not leak between them.
- [ ] GCash/Maya login and OTP flows; confirm ad-block/proxy do not interfere and FLAG_SECURE behaves.
- [ ] Shopee seller image upload (file chooser), Messenger, TikTok, fullscreen video.
- [ ] Release APK ≤ 12 MB (`tools/check_apk_size.sh`), start-up time, ANR/StrictMode pass.

## Store / legal
- [ ] Release keystore + Play App Signing; `versionCode` policy.
- [ ] Privacy policy URL (browsing data stays on device; proxy sends image URLs if enabled).
- [ ] Play Data Safety form; Families/age rating; content rating questionnaire.
- [ ] Trademarks: launcher tiles use monograms, not brand logos; confirm usage of GCash/Maya/Shopee/Lazada/TikTok/Facebook names with counsel.
- [ ] Crash reporting choice (none bundled; keeps APK small and data on device).
- [ ] Verify launcher URLs in `assets/startpage/app.js` and blocklist refresh cadence.
