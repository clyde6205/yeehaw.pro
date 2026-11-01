# Yehaw Android browser architecture

## Engine choice

Yehaw uses the Android System WebView provider, a Chromium-based engine maintained and updated independently by the device vendor or Android WebView/Chrome provider. The APK does not bundle a second browser engine. This is the most practical way to keep the application package small on entry-level Android devices while using the device's existing web runtime.

Yehaw must load ordinary HTTP and HTTPS pages directly in WebView. It must not route default browsing through an HTML sanitizer, strip scripts, remove media, rewrite form actions, or silently downgrade a website. JavaScript and DOM storage are enabled; first-party cookies are enabled; third-party cookies are disabled by default; mixed content is blocked; Safe Browsing is enabled on supported Android releases.

Engine capability is bounded by the installed provider version and Android OS. WebView is not the complete Chrome desktop product and does not provide browser extensions or every Chrome-only capability. The app must report the active provider/version and handle unsupported platform APIs gracefully.

## Low-memory policy

- Keep the APK independent of a bundled browser engine.
- Limit live tabs conservatively on constrained devices.
- Evict background WebViews under memory pressure, retaining their last URL/title and approximate scroll position; recreate them on demand using the shared browser profile.
- Keep persistent history bounded.
- Offer an explicit memory-saver mode; never silently disable JavaScript or rewrite pages.
- Do not claim that every 1 GB device can run every modern website. A page's own memory requirements can exceed device capacity.

## Implemented browser features

- HTTP/HTTPS navigation, back, forward, reload, home, and tabs.
- Persistent local bookmarks and bounded browsing history, including SPA history API URL changes.
- Durable local session continuity: up to four normal-browser tabs, active tab, page URL/title and approximate vertical scroll position are saved to SQLite when the app pauses/stops and restored after process death or device restart. This does not capture passwords, payment data, live form values or every website's in-memory application state.
- User-triggered offline reading snapshots: capture up to 250,000 characters of readable page text per page, stored locally with a bounded 100-page library and controls to open, delete one or clear all. These are text-only reading copies, not full offline clones; images, scripts, embedded media, interactive forms and server-dependent features are not guaranteed offline.
- Optional cache-first browsing preference uses WebView's local HTTP cache before network access. It is best-effort only: server cache headers, site design, storage eviction and Android's WebView provider determine what is available without connectivity.
- Website file chooser integration for upload forms.
- Long-press link actions to open a link in the current tab, open it in a new tab, or copy its URL.
- DownloadManager-based downloads.
- In-page text search and a shortcut to the Android downloads manager.
- Selectable Google, DuckDuckGo, Bing, and Brave Search providers, with the preference saved locally.
- Optional desktop user-agent mode, persisted across launches and applied to new tabs.
- Optional memory saver that suppresses automatic image loading and pauses background tabs, persisted across launches.
- Clear history, bookmarks, cookies, cache, and Web Storage.
- User-controlled third-party-cookie compatibility toggle, persisted across launches.
- User-initiated popup windows in tabs and immersive full-screen HTML5 video.
- HTTPS-gated camera, microphone, and geolocation permission prompts backed by Android runtime permissions, with a shortcut to Android app-permission settings and geolocation grant clearing when browsing data is cleared.
- Default-browser role request on supported Android versions.
- Edge-to-edge system-bar inset handling for current Android target SDKs and minimum 48 dp touch targets on browser controls.

## Security and privacy boundaries

- Session continuity and offline reading data are stored locally in the app's SQLite database. Offline snapshots are created only after the user explicitly taps Save offline; the library is user-deletable. Session state stores URLs, titles and scroll offsets, not credentials or form fields. A privacy review and clear-data behavior audit remain release gates.

- No JavaScript bridge is exposed to page content.
- Direct local filesystem access is disabled. Content-provider access is enabled only to support Android's user-selected `content://` upload URIs.
- HTTP/HTTPS navigation is handled internally. A small allowlist of external schemes (telephone, email, SMS, maps and app marketplace) requires explicit user confirmation. Android `intent:` links are parsed with components, packages, flags and extras stripped before user-approved resolution; a validated HTTP(S) fallback may be opened. Unsupported schemes are blocked.
- WebView renderer-process termination is handled by discarding the failed tab and recovering the browser UI.
- Open HTTP/HTTPS tab URLs, titles and the active-tab index are saved in Android instance state and restored after activity/process recreation; inactive restored tabs are recreated lazily to limit startup memory use.
- Third-party cookies are disabled by default.
- TLS mixed content is blocked, and certificate errors are explicitly canceled; there is no user-facing certificate bypass.
- Android Safe Browsing is enabled where supported.
- A separate `:private_browser` process uses an Android 9+ WebView data-directory suffix and does not write to normal history/bookmarks. It clears cookies, cache, and Web Storage at session start/end, supports file uploads, media/location prompts and full-screen video. This is an initial single-window private profile, not yet independently security-audited; service-worker/cache cleanup, crash-recovery verification and independent privacy/security review remain release gates.
- Notifications, passkeys, advanced WebAuthn, persistent per-origin permission management/revocation, and full private-mode permission parity are not implemented.

## Required release gates

1. Build and lint on CI with a pinned Android Gradle Plugin, Gradle, JDK, SDK and Kotlin toolchain; the debug and unsigned release APK build is automated.
2. Run the browser launch smoke test on API 23, API 29 and API 35 emulators; expand the matrix to API 26 and API 33 after baseline stability.
3. Test at least one physical 1 GB, 2 GB and 3 GB RAM device.
4. Verify representative sites using JavaScript modules, SPA routing, local storage, cookies, OAuth redirects, file upload, downloads, video/audio, WebAssembly and responsive layouts.
5. Verify tab lifecycle, memory trim, process death/recreation, offline/error pages, accessibility and touch target sizing.
6. Verify file chooser cancellation, multiple-file selection, large downloads, redirects, and content-provider URI handling.
7. Complete independent security review and privacy/data-retention review.
8. Publish signed, reproducible release artifacts and staged rollout monitoring.

No global launch-readiness claim is valid until these gates pass on real devices.
