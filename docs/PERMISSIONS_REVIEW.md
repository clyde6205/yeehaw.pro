# Permissions review (v0.6.0)
| Item | Decision | Why |
|---|---|---|
| INTERNET, ACCESS_NETWORK_STATE | Keep | Browsing; signal monitor. Normal permissions. |
| CAMERA, RECORD_AUDIO | Keep, runtime-only | A browser must serve sites that use them (video calls, QR/photo upload). Never asked at install or launch: the code (`onPermissionRequest`) shows a per-site dialog first, then the Android prompt. `uses-feature` camera/microphone `required=false` so Play does not hide the app from phones without them. |
| ACCESS_FINE_LOCATION | Keep, runtime-only | Same pattern via `onGeolocationPermissionsShowPrompt`. `uses-feature` location/gps `required=false` (added now: low-end phones often lack GPS and would otherwise be filtered out of Play). |
| usesCleartextTraffic=true | Keep (documented) | Many sites in target markets are still plain HTTP; a WebView cannot allow HTTP per-site through the manifest. YeeHaw's OWN calls (api.yeehaw.pro) are HTTPS-only: `BackendHttp` refuses any non-https URL. |
| allowBackup=false | Keep | Browsing data is not copied to cloud backups. |
| Clipboard | No permission | Clipboard Convenience is opt-in (OFF by default), reads only while the app has focus. |
| Photos | No permission | Screenshots saved via MediaStore (Android 10+) or app-private folder; nothing read. |
| Play declaration | Needed | Data Safety form: install id, app version, OS, RAM, region, crash/health counts (consent in EEA/UK/CH). Privacy policy URL required. |
