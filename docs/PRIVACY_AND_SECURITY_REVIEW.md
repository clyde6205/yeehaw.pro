# Yehaw Browser — privacy and security review

**Review scope:** source-level review of the Android client in this repository. This is an engineering checklist, not a third-party penetration test, legal opinion, or certification.

## Data handled on-device

| Data | Purpose | Storage / retention |
|---|---|---|
| Open tabs | Restore browsing session | SQLite session table; maximum four tabs |
| History | Recently visited pages | SQLite; maximum 1,000 entries |
| Bookmarks | User-saved sites | SQLite; user controlled |
| Offline reading copies | Explicit user-requested text snapshots | SQLite; maximum 100 pages and 250,000 characters per page |
| Cookies and website storage | Website functionality | Android System WebView profile |
| Downloads | User-requested files | Android DownloadManager / shared Downloads |
| Site permissions | Camera, microphone, location | Android runtime permissions and WebView permission callbacks |

## Existing safeguards verified in source

- Only HTTP and HTTPS URLs are accepted into history, bookmarks, sessions, and offline snapshots.
- WebView file-system access is disabled; content URI access remains enabled for user-selected uploads.
- Mixed content is blocked.
- SSL certificate errors are cancelled; there is no user-facing certificate bypass.
- Camera and microphone requests are restricted to HTTPS origins, explicitly confirmed in-app, and gated by Android runtime permission.
- Geolocation requests are restricted to HTTPS origins and explicit user confirmation.
- External application links require confirmation; unsupported schemes are blocked.
- Private browsing uses a separate WebView data-directory suffix in a separate Android process on Android 9+; it clears cookies, WebStorage, cache, and history when opening/closing.
- No JavaScript interface is exposed to web content.
- Clear browsing data clears history, bookmarks, offline copies, cookies, cache, WebStorage, form data, SSL preferences, and saved geolocation permissions.

## Remaining security verification before public release

- Test private-profile isolation and cleanup on physical Android 9+ devices, including process death and force-stop scenarios.
- Verify download behavior, content URI handling, file chooser cancellation, and untrusted file names on physical devices.
- Review external intent parsing against malicious intent URI test cases.
- Verify permission revocation and denial flows, including navigation away while a permission dialog is open.
- Review Android backup, app data export, crash reporting, analytics, and any future telemetry before enabling any such service.
- Run dependency and static security analysis on the final release commit.
- Commission an independent mobile application security assessment before making security certification claims.

## Privacy commitments for launch

- Publish a plain-language privacy policy and support contact before public distribution.
- Disclose that websites visited by the user receive normal network requests and may set cookies or collect data under their own policies.
- Disclose that downloads are saved to the device's shared Downloads folder and may remain after private browsing closes.
- Do not claim private browsing hides activity from websites, network operators, employers, or internet providers.
- Do not claim that cache-first mode guarantees offline access; offline copies are explicit text snapshots.
- Define a support process for privacy requests and security vulnerability reports.

## Release gate

Do not label the app production-secure until the remaining verification above is completed and recorded against the exact release commit.
