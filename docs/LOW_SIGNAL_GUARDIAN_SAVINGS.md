# Low-signal mode, Guardian self-repair, live savings readout (v0.5.0)

Additive: no redesign. The start page keeps its tiles and bottom nav; two small stat rows were added under the existing data line.

## What each piece really does
- `core/SignalMonitor` - reads OS network state/bandwidth estimate plus *measured* page-load times and failures. Downgrades at once, upgrades after 8 s stable. It never claims to improve signal.
- Weak/offline policy (`MainActivity.applySignalPolicy`, `shouldInterceptRequest`) - prefers cached copies and skips images, video and fonts (main-frame navigations are never blocked).
- `core/RetryScheduler` - interrupted pages retry automatically with 3 s to 60 s backoff, immediately when the network returns, until they load.
- `core/Guardian` - crash handler with auto-relaunch (best effort on newer Android), crash-loop guard (3 crashes in 2 min => safe start, bookmarks/saved pages untouched), 20 s UI-freeze watchdog, rate-limited renderer-death rescue that reloads the lost page.
- `BrowserStoreRepair` - integrity check on open; a damaged DB is moved aside (never deleted) and a fresh one created.
- `core/SavingsMeter` - exact: requests skipped, seconds in weak-signal mode, app bytes received, offline text bytes reused. Estimated and labelled "est.": data avoided (conservative per-type averages, because a request never sent has no measurable size).

## Limits (be honest in store listings)
- Recovery cannot defeat OS-level kills, and Android 10+ may refuse a background relaunch after a crash; the next open still recovers and the loop guard still applies.
- Not yet compiled by Gradle or measured on hardware in this change. Required before release: `./gradlew assembleRelease`, then cold/warm start, RAM and data on a real 2 GB device, and throttled 2G/3G and airplane-mode tests.
