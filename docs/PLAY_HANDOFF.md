# Google Play handoff: YeeHaw Browser (app.yehaw.browser, v0.6.0, versionCode 20)

Prepared for the account owner. Nothing here was submitted to Google. Everything marked OWNER needs the owner's Play Console, signing key or legal decision.
Every claim below is limited to what CI evidence shows (see `docs/RC_EVIDENCE.md`). Where evidence is missing it says so.

## 1. What is ready

| Item | State | Evidence |
|---|---|---|
| App code on `integration/reconcile-main` (main untouched) | Ready | commit 9476dee, all CI jobs green |
| Release APK, R8-minified, installs and runs API 23 and 36 | Ready | release-smoke run 45787022375 |
| Emulator suite API 23, 23 low-end (1 GB, 1 core), 29, 35, 36 | Green | run 45787022259 |
| Release size | 972,592 bytes (limit in gate doc: 12 MiB) | CI log |
| Release AAB (Play upload format) built and inspected in CI | See section 2 | android-bundle workflow |
| Six Quick Tools (Calculator, Unit Converter, QR Scanner, QR Generator, Notes, PDF Reader) | Ready, emulator-tested | `docs/QUICK_TOOLS.md` |
| Privacy policy page (mentions Quick Tools, camera use) | Ready, in the repo, deployed by the static site | `public/privacy/index.html` |
| Terms and Support pages | Ready, in the repo | `public/terms`, `public/support` |
| UI in 11 languages incl. Arabic and Urdu RTL | Machine translations, NOT native-reviewed | `docs/LOCALIZATION.md` |

## 2. Release bundle and signing (OWNER)

Play requires an Android App Bundle (.aab). CI builds one with `gradle :app:bundleRelease` and signs it only with a throwaway key so it can be inspected. That file must NOT be uploaded to Play.

To produce the real upload bundle:
1. In Play Console create the app `app.yehaw.browser` and enroll in Play App Signing (recommended: Google holds the app signing key, you hold an upload key).
2. Create an upload keystore on your own machine (`keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000`). Keep it and its passwords out of the repo.
3. Provide the four values the build already reads (path, store password, alias, key password; see `hasReleaseSigning` in `app/build.gradle.kts`), then run `gradle :app:bundleRelease` locally or in a CI job with those as secrets.
4. Upload `app/build/outputs/bundle/release/app-release.aab` to Internal testing first.

I did not create, handle or change any production key.

## 3. Store listing draft (English; edit freely)

Name: YeeHaw Browser
Short description (80 max): Lightweight browser for low-memory phones, with calculator, QR, notes and PDF tools.
Full description:

YeeHaw Browser is a small web browser made for phones with limited memory and unreliable connections.

Browse the web with tabs, bookmarks, history and downloads. Save pages to read offline. Use the built-in data-saver options when your connection is slow or expensive.

Quick Tools are built in, work offline and need no account:
- Calculator
- Unit Converter
- QR Code Scanner (asks for camera permission only when you open it)
- QR Code Generator (save or share the image)
- Notes (stored only on your phone)
- PDF Reader (open a PDF, move between pages, zoom; text search on Android 15 and newer)

Your tabs, history, bookmarks, notes and saved pages stay on your device. Websites can ask to use your camera, microphone or location; you are asked each time and can refuse.

Available in English, Spanish, Portuguese, Indonesian, Filipino, Russian, Hindi, Bengali, Urdu, Arabic and Chinese (Simplified). Translations are machine-generated and may contain mistakes.

Do not add percentage data-savings, speed or "uses less RAM" claims: none are measured on physical phones (known issue 15).

Release notes (first release): First release. Browser with tabs, offline pages and data-saver options, plus six built-in Quick Tools.

Category suggestion: Communication or Tools (owner's choice). Contact email: OWNER (none exists in the repo; Play requires one).

## 4. Assets still missing (OWNER or design)

- 512x512 icon (launcher icon exists in the app; a store icon export is needed).
- 1024x500 feature graphic.
- At least 2 phone screenshots (CI does not produce store-quality screenshots; take them on a real phone or the emulator).
- Support email address and confirmation that the support page shows it.

## 5. Data safety form: answers derived from the actual data flows

Source: `public/privacy/index.html`, `docs/PERMISSIONS_REVIEW.md`, code. The owner is the submitter and must confirm each answer is true at submission.

- Data collected and sent off the device:
  - App info and performance: anonymous random install ID, app version, Android version, phone memory size, country code, and crash/health counts. Purpose: app functionality and analytics. Not linked to a name or account. EEA/UK/CH consent as described in the privacy policy.
  - Purchase information: Google Play purchase token is sent to our server only when Pro is used; a hash of the token and subscription status are stored.
- Not collected: name, email, contacts, precise location sent to us, photos, files, notes, browsing history (these stay on device). The browser itself sends requests to the websites the user visits, like any browser.
- Encryption in transit: YeeHaw's own API calls are HTTPS-only (`BackendHttp` refuses non-https). Browsing may use plain HTTP because `usesCleartextTraffic=true` (documented in `PERMISSIONS_REVIEW.md`).
- Data deletion: user can clear browsing data in the app; there is no account. Server-side deletion path for the install ID is not documented: OWNER to decide and state it.
- Ads: none shown, no ad SDK bundled. Declare "No ads" while that stays true.
- Not independently verified: the third-party WebView and Google Play Billing library collect their own data under Google's terms; confirm in the form's SDK section.

## 6. Permissions declarations

Declared in the release APK: INTERNET, ACCESS_NETWORK_STATE, CAMERA, RECORD_AUDIO, ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION, READ/WRITE_EXTERNAL_STORAGE (max SDK 28 only), com.android.vending.BILLING (from Play Billing library) and one app-private signature permission.
- Camera, microphone and location are never requested at install or launch. They are requested when a website asks (per-site dialog first) or, for the camera, when the user opens the QR Scanner.
- `uses-feature` camera, microphone and location are `required=false`, so low-end phones without them are not filtered out.
- No broad storage, SMS, call log, contacts or accessibility permissions. No foreground service, no background service.
- Play may ask for a justification video or text for camera/microphone/location in a browser: the above describes the use.
- BILLING is present because Pro is wired in. Pro fails closed (paid features stay locked) until the owner configures the Play product and server verification (known issue 6). Either finish that or remove billing before launch; do not enable checkout unverified.

## 7. Content rating, target audience, declarations (OWNER submits)

- Suggested answers for the questionnaire: browser with unrestricted web access, no user-generated content hosted by us, no in-app purchases of digital goods until Pro is live. A browser reaching the open web usually lands in a higher rating tier because of web content: answer honestly and accept the result.
- Target audience: general audience, not directed at children (matches privacy policy). Do not enroll in Designed for Families.
- Ads declaration: no ads. Government/health/financial/news app declarations: none apply to the code as written, owner to confirm.
- Target API: targetSdk 36. Confirm Google's current target-API requirement at submission.

## 8. Testing track requirements (OWNER)

New personal developer accounts need a closed test with 12 testers for 14 days before production (known issue 12). An organization account may be exempt: check the account type in Play Console. Start with Internal testing (no waiting period) to verify install, update and rollback.

## 9. Launch gates that are NOT met (be honest in any launch decision)

From `docs/COMMERCIAL_RELEASE_GATE.md`, unmet today:
- No physical-phone test (low-memory phone, real mobile network, battery, thermal).
- No TalkBack session by a human.
- Translations not reviewed by native speakers, including permission and consent wording.
- Cold start and memory not measured before/after Quick Tools.
- No dependency vulnerability scan or lint gate in CI.
- Upgrade-from-previous-version test not run.
- No crash/ANR monitoring in production, no incident contact.
- Data-savings numbers unmeasured: make no savings claims.
- Intermittent API 29 failure of one test (known issue 21), cause unknown.
- Vercel Hobby plan forbids commercial use (needed before charging users, known issue 11).

Reasonable path: upload to Internal testing now, run on real phones, fix findings, then closed test.

## 10. Owner checklist, in order

1. Play Console: create app, enroll in Play App Signing, set support email.
2. Create upload keystore (section 2) and build the real AAB.
3. Upload AAB to Internal testing; install on 2 or more real phones, one with 2 GB RAM or less.
4. Complete Data safety (section 5), content rating, target audience, privacy policy URL `https://yehaw.pro/privacy`, store listing and assets (sections 3 and 4).
5. Decide Pro: configure the Play product, service account and server variables, or ship without billing enabled.
6. Native-speaker review of at least permission and consent text in the launch languages.
7. Closed test if the account type requires it, then staged production rollout with crash/ANR monitoring.
