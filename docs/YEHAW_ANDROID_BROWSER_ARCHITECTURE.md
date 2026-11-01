# Yehaw Android browser architecture

## Engine choice

Yehaw uses the Android System WebView provider, a Chromium-based engine maintained and updated independently by the device vendor or Android WebView/Chrome provider. The APK does not bundle a second browser engine. This is the most practical way to keep the application package small on entry-level Android devices while using the device's existing web runtime.

Yehaw must load ordinary HTTP and HTTPS pages directly in WebView. It must not route default browsing through an HTML sanitizer, strip scripts, remove media, rewrite form actions, or silently downgrade a website. JavaScript and DOM storage are enabled; first-party cookies are enabled; third-party cookies are disabled by default; mixed content is blocked; Safe Browsing is enabled on supported Android releases.

Engine capability is bounded by the installed provider version and Android OS. WebView is not the complete Chrome desktop product and does not provide browser extensions or every Chrome-only capability. The app must report the active provider/version and handle unsupported platform APIs gracefully.

## Low-memory policy

- Keep the APK independent of a bundled browser engine.
- Limit live tabs conservatively on constrained devices.
- Pause background WebViews under memory pressure.
- Keep persistent history bounded.
- Offer an explicit memory-saver mode; never silently disable JavaScript or rewrite pages.
- Do not claim that every 1 GB device can run every modern website. A page's own memory requirements can exceed device capacity.

## Implemented browser features

- HTTP/HTTPS navigation, back, forward, reload, home, and tabs.
- Persistent local bookmarks and bounded browsing history.
- Website file chooser integration for upload forms.
- DownloadManager-based downloads.
- In-page text search.
- Optional desktop user-agent mode, persisted across launches and applied to new tabs.
- Optional memory saver that suppresses automatic image loading and pauses background tabs, persisted across launches.
- Clear history, bookmarks, cookies, cache, and Web Storage.
- User-controlled third-party-cookie compatibility toggle, persisted across launches.
- User-initiated popup windows in tabs and immersive full-screen HTML5 video.
- HTTPS-gated camera, microphone, and geolocation permission prompts backed by Android runtime permissions.
- Default-browser role request on supported Android versions.

## Security and privacy boundaries

- No JavaScript bridge is exposed to page content.
- Direct local filesystem access is disabled. Content-provider access is enabled only to support Android's user-selected `content://` upload URIs.
- HTTP/HTTPS navigation is handled internally. A small allowlist of external schemes (telephone, email, SMS, maps and app marketplace) requires explicit user confirmation; unsupported schemes are blocked.
- WebView renderer-process termination is handled by discarding the failed tab and recovering the browser UI.
- Third-party cookies are disabled by default.
- TLS mixed content is blocked.
- Android Safe Browsing is enabled where supported.
- A separate `:private_browser` process uses an Android 9+ WebView data-directory suffix and does not write to normal history/bookmarks. It clears cookies, cache, and Web Storage at session start/end. This is an initial single-window private profile, not yet independently security-audited; service-worker/cache cleanup, crash-recovery verification and independent privacy/security review remain release gates.
- Notifications, passkeys, advanced WebAuthn, persistent per-origin permission management/revocation, and full private-mode permission parity are not implemented.

## Required release gates

1. Build and lint on CI with a pinned Android Gradle Plugin, Gradle, JDK, SDK and Kotlin toolchain.
2. Run emulator tests on API 23, API 26, API 29, API 33 and current Android.
3. Test at least one physical 1 GB, 2 GB and 3 GB RAM device.
4. Verify representative sites using JavaScript modules, SPA routing, local storage, cookies, OAuth redirects, file upload, downloads, video/audio, WebAssembly and responsive layouts.
5. Verify tab lifecycle, memory trim, process death/recreation, offline/error pages, accessibility and touch target sizing.
6. Verify file chooser cancellation, multiple-file selection, large downloads, redirects, and content-provider URI handling.
7. Complete independent security review and privacy/data-retention review.
8. Publish signed, reproducible release artifacts and staged rollout monitoring.

No global launch-readiness claim is valid until these gates pass on real devices.
