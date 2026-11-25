# YeeHaw Browser (Android)

Native Kotlin browser for 2–4 GB Android Go devices in the Philippines. Package `co.yehaw.browser`, minSdk 29 (Android 10), targetSdk 35.

## Build
```bash
cd android
./gradlew testDebugUnitTest          # unit tests
./gradlew assembleDebug              # debug APK
./gradlew assembleRelease            # R8-shrunk release APK
bash tools/check_apk_size.sh app/build/outputs/apk/release/*.apk   # 12 MB gate (also runs in CI)
```
Signing: create `android/keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); it is git-ignored. Without it the release APK is unsigned.

## Architecture
| Area | Files |
|---|---|
| Shell / chrome | `MainActivity.kt`, `res/layout/activity_main.xml` |
| Tabs, WebView factory, clients | `browser/TabManager.kt`, `TabWebViewClient.kt`, `TabChromeClient.kt` |
| Low-RAM engine | `memory/MemoryLifecycleManager.kt` (ComponentCallbacks2 + idle sweeper), `memory/TabSuspensionManager.kt` (Room serialize / destroy / rehydrate) |
| Ad & tracker blocking | `net/AdBlockInterceptor.kt`, `net/BlocklistIndex.kt`, `assets/blocklist/hosts.bin` |
| Data saver | `net/DataSaverClient.kt` (+ `docs/DATA_SAVER_PROXY.md`) |
| Isolated accounts | `browser/ContainerManager.kt` (WebView Profiles) |
| Offline pages | `archive/PageArchiver.kt` (`saveWebArchive` → `.mhtml`) |
| Downloads | `download/DownloadService.kt` (HTTP Range, If-Range, auto-resume), `DownloadRepository.kt` |
| Start page | `assets/startpage/{index.html,styles.css,app.js,error.html}` |

### Memory behaviour
* Sleeping tab = URL, title, scroll and WebView back-stack saved to Room, WebView destroyed (0 MB). Restored on tap; offline restores use `LOAD_CACHE_ELSE_NETWORK`.
* Triggers: idle > 3 min (configurable), `RUNNING_LOW/CRITICAL`, `UI_HIDDEN` (session checkpoint), `MODERATE` (`clearCache(false)` + sleep background tabs), `COMPLETE` (sleep everything).
* Renderer killed by the OS (`onRenderProcessGone`) is recovered by rebuilding the tab instead of crashing; after 3 crashes in a minute an explanatory page is shown.
* Cold start loads only the active tab; all others stay asleep.

### Security decisions
* TLS errors are never bypassable (`handler.cancel()`).
* Start page uses `WebViewAssetLoader` (https origin, no `file://`) and a custom `yeehaw://` scheme, honoured only from the start page with a user gesture. No `addJavascriptInterface` anywhere.
* Wallet/bank hosts (`DomainUtils.PROTECTED`): no ad-block interference, no proxying, `FLAG_SECURE` (no screenshots).
* URLs the user opens are never rewritten.
* Safe Browsing enabled; geolocation, camera and mic requests are denied (v1).
* Downloads persist the session cookie only until the download reaches a terminal state.

## Directive deviations (deliberate, please review)
1. **Per-account cookies**: Android's global `CookieManager` cannot be partitioned. Accounts use `androidx.webkit` **Profiles** (`ProfileStore`, WebView ≥ ~110). Older WebViews fall back to a single Main account and the UI says so.
2. **"Route page requests through a proxy with `Via`"**: implemented for **subresource images only**. Proxying HTTPS documents/scripts/cookies through a third party would break TLS end-to-end and is unsafe for GCash/Maya/Shopee logins. Brotli/Gzip is handled; AVIF/WebP transcoding happens server-side (spec in `docs/DATA_SAVER_PROXY.md`). Off until an HTTPS proxy template is configured.
3. **Heavy autoplay video**: handled with `mediaPlaybackRequiresUserGesture` plus host blocking, not by blocking all video (that would break TikTok/Facebook).
4. **`WRITE_EXTERNAL_STORAGE`** is declared with `maxSdkVersion=28` as requested; it is inert at minSdk 29 (downloads use MediaStore).
5. **Blocklist**: a compiled sorted-hash index (`tools/compile_blocklist.py`) instead of a `HashSet<String>`; ~580 KB for ~74k domains (StevenBlack/hosts, MIT, see `NOTICE`).

## Known limitations / next
* `blob:` and `data:` downloads are not supported yet.
* No camera/mic permission prompts yet (Messenger calls, QR scanning).
* The Cloud image proxy server is not part of this repo.
* Launcher deep links (e.g. TikTok Shop, PAGASA radar) are root URLs; verify and refine in `assets/startpage/app.js`.
* Signing, Play Console listing, privacy policy and Data Safety form are still to do (see `docs/ANDROID_LAUNCH_CHECKLIST.md`).
