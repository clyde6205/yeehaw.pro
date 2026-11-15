# YeeHaw data-savings counter and offline-storage methodology

## What the home-screen counters mean

### App data received

The browser samples Android `TrafficStats.getUidRxBytes(applicationInfo.uid)` and accumulates positive deltas. The total is persisted locally and refreshed while the browser is in the foreground.

This is **app-UID received network traffic**, not a page-by-page measurement and not the phone's total mobile-data usage. It can include traffic from any network activity running under the browser app UID. Traffic handled by Android's separate DownloadManager process may not be included. Some Android builds may return an unsupported value; in that case the last verified total is retained.

The first sample after process start establishes a baseline. A lower value is treated as a counter reset and establishes a new baseline without subtracting from the persisted lifetime total.

### Offline text reused

When a user opens a saved offline reading copy, the app adds the exact UTF-8 byte count of the text shown locally. It does not increment when a page is merely saved.

This is a **confirmed lower-bound of text bytes served from local storage**. It does not include original images, CSS, JavaScript, fonts, media, HTTP headers, compression effects, or server-side data. It is not an estimate of the complete original page size and must not be marketed as total website data saved.

The home screen therefore uses the label **Offline text reused**, not a claim that the entire page's network transfer was avoided.

## Offline storage limits

- Maximum UTF-8 text per saved page: 256 KiB.
- Maximum total UTF-8 text payload in the offline library: 8 MiB. SQLite pages, indexes and metadata add some on-disk overhead beyond this text-payload ceiling.
- Maximum saved pages: 100.
- When a limit is reached, oldest saved pages are removed first.
- The app counts the actual UTF-8 payload bytes stored in SQLite, not Kotlin character count.
- These limits apply to the offline reading library only. Android System WebView owns its separate HTTP cache; YeeHaw does not currently claim a hard quota for that system-managed cache.

## Claims that are not currently supported

Do not claim:
- a specific percentage of total browsing data saved;
- complete offline copies of websites;
- data savings from Turbo mode beyond the actual image requests it prevents;
- storage saved on the whole phone;
- guaranteed speed gains on every website or every device.

## Required measurement before public savings claims

Use physical Android devices and controlled web pages with known resource sizes. Record cold-cache and warm-cache transfer totals using Android's per-app network usage tools, repeat each scenario on cellular and Wi-Fi, and verify the offline-copy path with network access disabled. Publish a savings percentage only after repeatable tests establish the denominator, device coverage, and measurement uncertainty.
