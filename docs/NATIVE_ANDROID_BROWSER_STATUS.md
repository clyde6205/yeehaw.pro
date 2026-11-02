# Yehaw Android browser — implementation status

Production source branch: `main`

## Implemented in source
- Native Kotlin Android application with Android System WebView as the rendering engine (Chromium engine supplied and updated by the device; not bundled in this APK).
- HTTP/HTTPS address navigation, configurable search engine, back/forward/reload/home, bounded tabs, popup tabs, and tab restoration after memory eviction.
- Local bookmarks, bounded browsing history, session continuity, and user-triggered text-only offline reading snapshots.
- Downloads through Android DownloadManager, user-selected file uploads, find-in-page, page sharing, desktop user-agent mode, and fullscreen HTML5 video.
- Safe Browsing where supported, HTTPS certificate-error blocking, mixed-content blocking, no JavaScript bridge, and user-mediated external-app and sensitive-site permissions.
- Separate-process private browsing profile on Android 9+ with cookie/cache/history/WebStorage cleanup and no writes to normal browser history or bookmarks.
- CI workflows build debug and release APKs and run emulator smoke tests on API 23, 29, and 35.

## Release and launch status
- CI always builds a debug APK and a release APK. Without production signing secrets, the release APK is explicitly unsigned and cannot be distributed as a normal consumer release.
- The build pipeline supports signing with a user-owned keystore supplied as GitHub Actions secrets. Required secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. The keystore itself is never committed to the repository.
- No production-signed APK is published by this repository yet.
- No account system, cloud sync, cloud rendering/proxy, built-in VPN, ad-blocking engine, or Chromium fork is claimed.
- No real-device battery, memory, performance, accessibility, or broad website compatibility certification is claimed.
- Android System WebView availability and version are controlled by the device/vendor.
- Store listing, privacy disclosures, support operations, real-device testing, and release monitoring still require completion and verification.

## Build
Use Android Studio with JDK 17 and Android SDK 35, or run:

```bash
gradle --no-daemon :app:assembleDebug :app:assembleRelease
```

The release output is signed only when all four production signing environment variables are present. Never commit signing keys or passwords.
