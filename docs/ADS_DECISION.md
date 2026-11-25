# Ads: decision needed from the founder
The AdMob SDK adds several MB to the APK and noticeably more RAM at runtime. The v0.6.0 release APK is ~0.4 MB (measured in CI). YeeHaw's promise is low-memory phones, so the SDK is NOT bundled in this build.
Wired and working today: the console's ads switch (stored, signed, delivered), consent flow, Pro entitlement that removes ads.
To enable ads: (1) create the real AdMob app + banner unit; (2) add `play-services-ads` + Google UMP consent to a separate `ads` build flavor; (3) skip ads when `ActivityManager.isLowRamDevice()` or Pro. `console/android/.../AdMonetizationManager.kt` is the ready-made manager (test ids only).
Until real ids exist, shipping ads would violate AdMob policy (test ids earn nothing).
