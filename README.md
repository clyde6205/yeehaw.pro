# Yehaw — Lightweight Android Browser

Yehaw is an Android-first web browser project. The former Filipino directory, service launcher and recipes website has been retired as the product direction. The public website is now the browser product and development information site.

## Android application

The native Android app lives in `app/` and is written in Kotlin. It renders web pages using Android System WebView, a Chromium-based engine supplied and updated by the device. Yehaw does **not** bundle the full Chromium engine.

The active browser implementation includes:
- Direct HTTP/HTTPS web browsing, JavaScript and DOM storage
- Tab management with memory-aware background WebView eviction
- SQLite-backed bookmarks, bounded history and session restoration
- Downloads through Android DownloadManager and website file uploads
- Find-in-page, sharing, desktop-site mode and selectable search engines
- Private browsing profile and permission prompts
- User-triggered text-only offline reading snapshots

Compatibility and memory use depend on Android version, the installed WebView provider, device resources and the websites being visited. The project must not be described as production-ready until automated checks, physical-device tests, security/privacy review, accessibility checks, release signing and operational readiness are complete.

## Website

The Vercel website is a lightweight, responsive product page for the Yehaw Android browser. It is not a browser running inside the website and it does not provide an APK download until a signed public release has been published.

## Build and test Android

The repository includes GitHub Actions workflows for Android builds and emulator smoke tests. View them at:

https://github.com/clyde6205/yehaw.app/actions

Open the project in Android Studio and run the Gradle Android application configuration. A debug build can be produced with:

```bash
./gradlew :app:assembleDebug
```

For release distribution, configure a user-owned signing key and secrets outside source control. Never commit signing keys or production credentials.

## Vercel

The Vercel project is linked to this GitHub repository. The static product site is built with:

```bash
npm ci
npm run build
```

The build output is `public/`. Vercel serves the static browser product page; Android APKs are built separately through the Android CI workflow.

## Release principles

- Do not claim that a release is available until a signed APK/AAB is actually published.
- Do not claim support for every Android device or website; test across representative Android and WebView versions.
- Do not enable production analytics, ads, accounts, payments or data collection without their security, privacy and legal requirements being implemented and reviewed.
- Keep user data and signing credentials out of source control.
