# Android release: current state and how to get a signed APK + AAB

## Published now (early-access test build)
- Page: https://yeehaw.pro/download/ (button "Download Android App" on the home page)
- Release: https://github.com/clyde6205/yeehaw.pro/releases/tag/android-v0.6.0-7daa3cf-test (pre-release)
- APK `yeehaw-browser-0.6.0-7daa3cf-test.apk`, 4,041,270 bytes, versionName 0.6.0 / versionCode 20, commit 7daa3cf
- SHA-256 `7112c947a5a787a28e9f8c20a67f357c917e7dc1763f4e422df43cd6fb9d563d`
- Debug-signed, package `app.yehaw.browser.debug`. NOT the Play Store build; no AAB exists yet (AAB is only built when production signing secrets are set).
- CI install verification (emulator, not a physical phone): Android 6.0 at 1 GB RAM / 1 core: install OK, cold start 807/850/776/794/885 ms; Android 12: install OK, cold start 1668/910/956/816/900 ms (first launch slower), PSS ~89 MB; 400-event monkey run without crash/ANR on both.
- Hosting is a GitHub Release asset (permanent while the tag exists). Cloudflare R2 is not used: enabling it needs an account subscription that you must confirm.

## Owner steps for the production-signed APK and AAB (key never goes in chat)
Run on your own computer. Keep the .jks backup offline; losing it means you cannot update the Play app.
1. `keytool -genkeypair -v -keystore yehaw-production.jks -alias yehaw -keyalg RSA -keysize 4096 -validity 10000`
2. `base64 -w0 yehaw-production.jks | gh secret set ANDROID_KEYSTORE_BASE64 -R clyde6205/yeehaw.pro`
3. `gh secret set ANDROID_KEYSTORE_PASSWORD -R clyde6205/yeehaw.pro` (paste when prompted)
4. `gh secret set ANDROID_KEY_ALIAS -R clyde6205/yeehaw.pro` (value: yehaw)
5. `gh secret set ANDROID_KEY_PASSWORD -R clyde6205/yeehaw.pro`
6. Edit `.github/release-request.json` on wip/quick-tools and push. The release workflow then builds a signed APK and an AAB, install-verifies, and publishes (non-prerelease).
7. Upload the AAB in Google Play Console (internal testing first). Play App Signing is recommended.
