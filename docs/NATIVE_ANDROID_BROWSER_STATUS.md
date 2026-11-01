# Native Android browser — implementation status

Branch: `feat/native-browser-foundation`

## Implemented in this milestone

- Native Kotlin Android application module with no third-party runtime libraries.
- Android HTTP/HTTPS browser intent filters.
- System WebView engine integration (engine supplied by Android, not bundled into this APK).
- Address/search bar, back, forward, reload, home, new tab and tab picker.
- Four-tab cap to bound retained WebView instances on low-memory devices.
- Android browser-role request on explicit user action (Android 10+).
- DownloadManager handoff for HTTP/HTTPS downloads.
- Safe Browsing where supported, HTTPS-only mixed-content policy, no file/content access, no JavaScript bridge, third-party cookies disabled.
- Memory-pressure handling for inactive tabs.

## Important limitations — not production complete

- This is a native browser foundation, not yet a full cloud-rendering browser.
- Cloud proxy, Chromium remote-rendering tier, account sync, bookmarks/history persistence, private mode, robust file chooser support, permissions UI, ad/reward systems and backend services are not implemented here.
- JavaScript is enabled for compatibility with modern websites. A user-facing lightweight mode and per-site compatibility controls still need implementation.
- The Android build has not been executed in this environment. A successful GitHub source commit is not evidence of a successful APK build.
- APK size and RAM use must be measured on real devices; no size or performance guarantee is made.
- Review Android API and target-SDK behavior on supported devices before release.

## Build locally

Open the repository in Android Studio, allow Gradle sync, then run the `app` configuration on an Android 6.0+ device/emulator. Build a debug APK with:

```bash
gradle :app:assembleDebug
```

For release, configure signing outside source control and run:

```bash
gradle :app:assembleRelease
```

Never commit signing keys or production credentials.
