# Yehaw Browser — commercial release gate

This document separates source implementation from evidence required for public release. A checked source feature is not automatically a passed device test.

## Engineering and CI gates

- [ ] Android debug APK builds on the exact release commit.
- [ ] Minified release APK builds on the exact release commit.
- [ ] Release APK is signed with the publisher-owned production keystore.
- [ ] Verify APK signature with Android apksigner and record certificate SHA-256.
- [ ] API 23, API 29, and API 35 emulator test suites pass on the exact same commit.
- [ ] Regression suite covers app launch, storage bounds, invalid URL rejection, session restoration, offline reading, and clear-data behavior.
- [ ] Test on physical Android devices, including a low-memory device and current Android version.
- [ ] Verify app upgrade from the previous published version and preservation/migration of user data.
- [ ] Run dependency, lint, and static security checks; resolve release-blocking findings.

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

## Operational readiness

- [ ] Publish a support and security contact.
- [ ] Establish a vulnerability disclosure and response process.
- [ ] Establish versioning, release notes, staged rollout, rollback, and hotfix procedures.
- [ ] Confirm third-party licenses and notices for all shipped dependencies and assets.
- [ ] Confirm all branding, icons, screenshots, and marketing claims are owned or licensed.
- [ ] Test privacy controls, data deletion, and private browsing on physical devices.
- [ ] Review network behavior and privacy disclosures for the final build.

## Current source status

The app is a native Kotlin browser interface built on Android System WebView; Chromium is supplied and updated by the device. The repository has CI workflows for debug/release APK assembly and API 23/29/35 emulator tests. Release signing is optional in CI and requires publisher-owned secrets. No signed public APK or Play Store submission is asserted by this document.

**Release rule:** every gate must be supported by evidence attached to the release commit. Do not describe the product as globally launched or fully production-ready until these gates are satisfied.
