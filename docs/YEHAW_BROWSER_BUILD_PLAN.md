# Yehaw Android browser — delivery plan

## Product definition

Yehaw is an Android web browser. The native application is written in Kotlin and uses the Android System WebView provider for page rendering. The engine is maintained by the Android device or its WebView/Chrome provider; Yehaw does not bundle Chromium.

The retired Filipino directory, service launcher, recipes product, editorial tools, and associated database are out of scope and must not be reintroduced.

## Product principles

- Directly load ordinary HTTP and HTTPS websites without proxy rewriting or content sanitisation.
- Keep the browser app layer and background-tab memory footprint conservative.
- Make privacy behavior explicit and user-controlled; never market private browsing as anonymity.
- Preserve local browser data only where the user expects it and provide clear deletion controls.
- Do not advertise capabilities that are not implemented and tested on supported Android versions.
- Keep the Android app and its public product website separate. Vercel hosts the product site; Android CI builds the APK.

## Delivery phases

### 1. Browser foundation
- Maintain address/search navigation, history navigation, tabs, popup handling, and session recovery.
- Validate lifecycle behavior across rotation, process recreation, renderer termination, and low-memory eviction.
- Keep tab count adaptive to available memory and test representative low-RAM devices.

### 2. Privacy and security
- Maintain HTTPS certificate-error blocking, Safe Browsing where available, mixed-content blocking, and no JavaScript bridge.
- Require user confirmation for external app launches and sensitive site permissions.
- Test private-profile separation, interrupted-session cleanup, permissions, uploads, and downloads.
- Clearly warn that private-mode downloads are written to shared device storage.
- Provide and test clear-data behavior for history, bookmarks, cookies, cache, WebStorage, and offline snapshots.

### 3. Browser quality
- Test real sites with forms, media playback, file uploads, downloads, redirects, popups, SPA navigation, and accessibility services.
- Measure startup time, APK size, memory pressure, battery use, and renderer recovery on physical devices.
- Validate text scaling, screen readers, keyboard navigation, touch targets, and landscape layouts.
- Verify Android 6 through current supported Android versions with their installed WebView providers.

### 4. Release engineering
- Keep CI green for debug and unsigned release assembly and emulator smoke tests.
- Configure production signing only with the owner's keystore and secrets stored outside source control.
- Produce a signed release APK/AAB, verify its signature, checksum, versioning, and install/upgrade behavior.
- Complete privacy disclosures, store listing, support contact, crash monitoring, and release rollback procedures before public launch.

## Explicit non-goals unless separately approved

- Bundling or forking Chromium.
- Cloud browsing, remote rendering, a VPN, or a proxy.
- Account creation or cloud synchronisation.
- Advertising, behavioural analytics, payments, or monetisation.
- Browser extensions or claims of full desktop Chrome compatibility.

## Launch gate

A successful source commit or emulator build is not a launch. Public release requires a signed distributable, physical-device validation, security/privacy review, accessibility review, accurate user disclosures, and an operational support and update process.
