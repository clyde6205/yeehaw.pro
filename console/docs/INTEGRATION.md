# YeeHaw Founder Console: integration guide

```
Android app (existing)                       api.yeehaw.pro (Node + Postgres)              You
 TelemetryManager  ── POST /v1/telemetry/boot ──►  devices, sessions            ◄── /admin  (live stats)
 AdMonetizationManager ─ POST …/ad-event ────────►  ad_events                  ◄── /admin  (ads on/off)
 RemoteConfigManager ◄─ GET /v1/config/app-config ─ remote_configs (signed)     ◄── /admin  (search, speed dial, blocklist)
```

## 1. Deploy the backend
Needs Node 20+ and PostgreSQL 13+ (Neon, Supabase, Render Postgres all work).

```bash
cd backend
npm ci
npm run keys          # prints ADMIN_TOKEN, writes out/config-signing-key.pem + out/yeehaw_config_pubkey.txt
```
Environment variables on the host (see `.env.example`):

| Variable | Value |
|---|---|
| `DATABASE_URL` | your Postgres URL (TLS is enforced unless the host is localhost) |
| `ADMIN_TOKEN` | the token from `npm run keys` (store it in a password manager) |
| `CONFIG_SIGNING_KEY_PEM` | contents of `out/config-signing-key.pem` |
| `TRUST_PROXY` | `2` when behind Cloudflare + a PaaS proxy, `1` behind one proxy |

Start command `node server.js` (creates the tables and a default config on first boot). Health check: `GET /healthz`.
Point `api.yeehaw.pro` at the service (Cloudflare DNS, proxied). Cloudflare adds `CF-IPCountry`, which the server uses for the country chart; IP addresses are never stored.
**Also protect `/admin` with Cloudflare Access (or an IP allow-list).** The token is a second lock, not the only one.

## 2. Put the public key in the app
Copy `out/yeehaw_config_pubkey.txt` to `app/src/main/assets/yeehaw_config_pubkey.txt`. Without it remote config stays off (fail closed). Keep the private key backed up offline: rotating it means shipping an app update.

## 3. Add the Kotlin files
Copy `android/app/src/main/java/app/yehaw/browser/console/*.kt` into the project. Set `BackendConfig.API_BASE_URL` if your API host differs. No new Gradle dependencies (AdMob is already in your app).

**MainActivity**
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    /* ... your existing setup ... */
    ConsoleBootstrap.init(this, adContainer)   // adContainer: a FrameLayout above your bottom bar, or null
}
override fun onResume()  { super.onResume();  ConsoleBootstrap.onResume() }
override fun onPause()   { ConsoleBootstrap.onPause(); super.onPause() }
override fun onDestroy() { ConsoleBootstrap.onDestroy(); super.onDestroy() }
```
**Search hook** (address bar and the start page's `?a=search&q=` action):
```kotlin
webView.loadUrl(SearchRoutingEngine.resolve(typedText, userEngineTemplate = null))
```
Pass your own `userEngineTemplate` (https URL with `{query}`) when the user explicitly picked another engine in Settings, so their choice is honoured.

**Emergency blacklist** (in your WebViewClient):
```kotlin
override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
    if (RemoteConfigManager.shouldBlock(req.url)) return RemoteConfigManager.blockedResponse(req.url.host, req.isForMainFrame)
    /* ... your existing blocking ... */
}
override fun onPageFinished(view: WebView, url: String?) {
    ConsoleBootstrap.onPageFinished(view, url)   // pushes the speed dial into the start page
    /* ... */
}
```
**Start page:** paste `assets/startpage_speeddial_snippet.html` into `assets/startpage.html` before `</body>`.

**Consent / privacy**
* EEA, UK and Switzerland: nothing is sent until you call `TelemetryManager.setConsent(context, true)`. Show a short opt-in dialog on first run for those regions (`TelemetryManager.isConsentRequired()`); elsewhere telemetry runs unless the user opts out.
* Add to the Privacy tile: *Stop sharing and delete my data* → `TelemetryManager.forgetMe(context)`.
* Ads in those regions also need your own consent flag via `AdMonetizationManager.setAdsConsent(activity, true)` and, before relying on ads there, Google's UMP consent form.

## 3b. Sharing: QR code, channels, APK hand-over (core growth feature)
Files: `QrCode.kt` (offline QR generator, verified by decoding 23 codes with an independent decoder), `ShareManager.kt`, `ShareCore.kt`.

* Start page **Share** tile (the `?a=share` action you already handle natively): `ShareManager.showQrDialog(activity)`.
  It shows a bright, scannable QR of `https://yeehaw.pro/get?r=<code>&c=qr` plus *Share* (system sheet) and *WhatsApp* buttons.
* Per-channel buttons anywhere in the UI: `ShareManager.shareVia(activity, ShareManager.Channel.WHATSAPP)` (also MESSENGER, TELEGRAM, VIBER, SMS, SYSTEM). Falls back to the system sheet if the app is missing. Messages are localised (English, Filipino, Indonesian, Hindi, Bengali, Vietnamese, Spanish, Portuguese).
* Offline hand-over: `ShareManager.canShareApk(ctx)` / `shareApk(activity)` sends the installed APK over Bluetooth, Nearby Share or file-share apps (no mobile data needed). Only for direct-download installs; Play App Bundle installs are split and cannot be re-shared. WhatsApp does not accept APK files.
* Every link carries a random referral code (not derived from any user data) and the channel; the console's **How the app spreads** card counts QR shown, links shared, APK hand-overs and top channels.

Manifest additions (Android 11+ needs `<queries>` to detect installed chat apps; the APK share needs a FileProvider):
```xml
<queries>
  <package android:name="com.whatsapp" /><package android:name="com.facebook.orca" />
  <package android:name="org.telegram.messenger" /><package android:name="com.viber.voip" />
</queries>
<provider android:name="androidx.core.content.FileProvider" android:authorities="${applicationId}.shareprovider"
          android:exported="false" android:grantUriPermissions="true">
  <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/share_paths" />
</provider>
```
`res/xml/share_paths.xml`: `<paths><cache-path name="share" path="share/" /></paths>`

Website side: publish `get/index.html` (in `cloudflare_site_fix.zip`) at `https://yeehaw.pro/get`; set its `INSTALL_URL` to your Play Store listing (or APK download page) once it exists.

## 4. Before the Play Store release (important)
* Your release APK (v0.4.6) still uses **Google's TEST AdMob app id** in the manifest. Create the real AdMob app + banner unit, replace `com.google.android.gms.ads.APPLICATION_ID` and `BackendConfig.BANNER_AD_UNIT_ID`. Until then `AdMonetizationManager` deliberately shows **no ads** in release builds.
* The share text points to a private GitHub URL; change it to `https://yeehaw.pro`.
* Play Console **Data safety**: declare a random app-install identifier, app version, device/OS info (RAM), approximate region, ad interaction/performance data; "collected, not sold"; data deletion available in-app. Publish a privacy policy at `https://yeehaw.pro/privacy`.
* Sign the release build (the uploaded `app-release-unsigned.apk` cannot be installed or uploaded).

## 5. Day-to-day in the console
* **Ads switch**: publishes a new config version immediately; devices pick it up at their next refresh (on app start, at most every 6 h, `ttl` 1 h).
* **Search routing**: set `default_search_url` such as `https://www.bing.com/search?q={query}&PC={tag}` and your partner tag; the preview shows the exact URL.
* **Speed dial** (max 12) and **blacklist** (subdomains included) publish the same way. Every publish is a new numbered version; **Versions → Roll back** restores any earlier one in one click.
* Two admins publishing at once: the second gets a conflict and must reload (no silent overwrite).

## 6. What is and is not verified
Verified in my sandbox: backend against real PostgreSQL (18 API tests incl. injection attempts, rate limiting, signature, rollback, conflicts), the console in real Chromium (35 checks), the Kotlin signature/validation/search/parser/share code on a real JVM against configs signed by the real server (49 checks), the QR generator by decoding its images with OpenCV (23/23, versions 1-10); all Android modules type-check. The live API on Vercel + Neon answers health and signed-config requests and its signature verifies with the delivered public key.
**Not verified**: running inside your APK (I had no Gradle/Android SDK here), the real AdMob SDK calls (type-checked against stand-ins), and deployment to your hosting account.
