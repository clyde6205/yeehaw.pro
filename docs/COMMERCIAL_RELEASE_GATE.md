# Yehaw Browser — commercial release gate

This document separates source implementation from evidence required for public release. A checked source feature is not automatically a passed device test.

## Engineering and CI gates

- [ ] Android debug APK builds on the exact release commit.
- [ ] Minified release APK builds on the exact release commit.
- [ ] Release APK is signed with the publisher-owned production keystore.
- [ ] Verify APK signature with Android apksigner and record certificate SHA-256.
- [ ] Release APK is no larger than 12 MiB.
- [ ] API 23, API 29, API 35, and API 36 emulator test suites pass on the exact same commit.
- [ ] Regression suite covers app launch, storage bounds, invalid URL rejection, session restoration, offline reading, and clear-data behavior.
- [ ] Test on physical Android devices, including a low-memory device and current Android version.
- [ ] Verify app upgrade from the previous published version and preservation/migration of user data.
- [ ] Run dependency, lint, and static security checks; resolve release-blocking findings.


## Data-savings and storage evidence

- [ ] Verify the app-UID received-byte counter on physical devices against Android's per-app network usage, including process restart, reboot/counter reset, unsupported-counter behavior, and downloads handled by Android DownloadManager.
- [ ] Verify that the offline reuse counter increments only when a saved local text copy is opened, persists across launches, and counts exact UTF-8 bytes.
- [ ] Preserve the distinction between app-wide received bytes and page-specific traffic; do not market either as total data saved.
- [ ] Verify the offline library's hard limits: 256 KiB UTF-8 per page, 8 MiB total, and 100 pages maximum, with oldest-first eviction.
- [ ] Measure cold-cache and warm-cache transfer sizes on metered mobile networks using controlled test pages and known resource sizes.
- [ ] Record startup time, page-load timing, memory use, cache hit/reuse behavior, battery use, and APK size on physical 1 GB, 2 GB, and 3 GB RAM devices.
- [ ] Do not publish percentage data-savings claims until controlled, repeatable device measurements support them.

## Store and publisher requirements

- [ ] Final app name, icon, screenshots, feature graphic, short and full descriptions.
- [ ] Public privacy policy URL and support contact.
- [ ] Complete Google Play Data safety declaration based on actual SDKs and data flows.
- [ ] Content rating, target audience, ads declaration, and app access instructions as applicable.
- [ ] Publisher-owned Play Console account and app signing configuration.
- [ ] Confirm current Google Play target API and testing-track requirements at submission time.
- [ ] Internal testing track install, update, and rollback verification.
- [ ] Closed/open testing and production rollout plan appropriate to Play Console requirements.
- [ ] Crash/ANR monitoring and a documented incident response contact.
- [ ] Accessibility review: TalkBack, focus order, text scaling, contrast, touch targets, landscape, and keyboard navigation.

## Payments and monetization

- [ ] Decide whether YeeHaw Pro is a subscription or a one-time product; product type, price, offer token and customer-facing copy must agree.
- [ ] Configure the product in the publisher-owned Google Play Console and verify all supported country pricing and tax behavior.
- [ ] Verify purchase tokens server-side using the Google Play Developer API; never grant paid entitlements from a client-only preference.
- [ ] Acknowledge eligible purchases, restore active purchases after reinstall/device change, and handle pending, cancelled, refunded, revoked and chargeback states.
- [ ] Test purchase and restore flows using Play license testers before any production payment is accepted.
- [ ] Keep paid checkout disabled until server verification and entitlement lifecycle tests pass.

## Global distribution and accessibility

- [ ] Localize native browser controls, start page, permission prompts, error states, onboarding, privacy policy and support content for the first launch markets.
- [ ] Verify right-to-left layout and text shaping for Arabic and other RTL locales.
- [ ] Test on constrained cellular networks, including high latency, packet loss, intermittent connectivity, data caps and transitions between Wi-Fi and mobile data.
- [ ] Verify search, downloads, file uploads, media and common sign-in flows across representative global sites and WebView provider versions.
- [ ] Review privacy disclosures, consent, age rating and consumer requirements for each launch market.
- [ ] Prepare a staged country rollout, crash/ANR monitoring, rollback plan and support coverage before broad distribution.

## Operational readiness

- [ ] Publish a support and security contact.
- [ ] Establish a vulnerability disclosure and response process.
- [ ] Establish versioning, release notes, staged rollout, rollback, and hotfix procedures.
- [ ] Confirm third-party licenses and notices for all shipped dependencies and assets.
- [ ] Confirm all branding, icons, screenshots, and marketing claims are owned or licensed.
- [ ] Test privacy controls, data deletion, and private browsing on physical devices.
- [ ] Review network behavior and privacy disclosures for the final build.

## Current source status

The app is a native Kotlin browser interface built on Android System WebView; Chromium is supplied and updated by the device. The repository has CI workflows for debug/release APK assembly and API 23/29/35/36 emulator tests. Release signing is optional in CI and requires publisher-owned secrets. No signed public APK or Play Store submission is asserted by this document.

**Release rule:** every gate must be supported by evidence attached to the release commit. Do not describe the product as globally launched or fully production-ready until these gates are satisfied.
