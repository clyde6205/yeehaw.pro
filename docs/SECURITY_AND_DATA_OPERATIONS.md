# Yehaw Browser — security and data operations

## Product boundary
Yehaw is an Android web browser. The former Filipino directory product, its database, editorial workflows, and public directory APIs are retired and are not part of this application.

## Browser security model
- Web content runs in Android System WebView; this project does not bundle Chromium.
- JavaScript is enabled for modern-site compatibility. The app does not expose a JavaScript-to-native bridge.
- File-system access from web content is disabled. Content URI access is enabled only to support a user-selected upload through Android's file chooser.
- Mixed content is blocked, Safe Browsing is enabled where supported, and SSL certificate errors are always cancelled. There is no user override to ignore certificate errors.
- Third-party cookies are disabled by default and can be enabled by the user.
- External application links require a user confirmation. Unsupported URL schemes are blocked.
- Camera, microphone, and location requests are restricted to HTTPS origins and require both an in-app confirmation and Android runtime permission.

## Private browsing
Private browsing runs in a separate Android process with a distinct WebView data-directory suffix on Android 9 and later. It does not write to the normal browser history, bookmarks, or saved sessions. The private process clears its WebView cookies, cache, history, and WebStorage when opening and closing a private session. Private browsing is not anonymity: websites, network operators, downloaded files, and external applications may still observe activity. Downloads are explicitly confirmed because files and Android download records are stored outside the private profile.

## Local data
Normal browsing history is bounded to 1,000 entries. Session continuity is bounded to four tabs. Offline reading copies are user-triggered, text-only snapshots with bounded size and count. These records are stored locally in the app's private database.

## Release controls
Production release signing must use a user-owned keystore and secrets stored outside GitHub source. Never commit signing keys, passwords, access tokens, or production credentials. Real-device security, privacy, and performance testing is required before public release.
