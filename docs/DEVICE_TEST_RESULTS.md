# Device test results

Rule: nothing below is filled in unless it was measured. Emulator results are labelled as emulator. Physical-device results do not exist yet.

## Test matrix (targets, not results)
| Class | Focus | Status |
|---|---|---|
| 1 GB RAM phone | startup, stability, memory pressure | NOT RUN |
| 2 GB RAM phone | browsing, tabs, background recovery, data use | NOT RUN |
| 3 GB RAM phone | general performance, tab handling | NOT RUN |
| Modern Android (API 35/36) | compatibility, regression | NOT RUN (no KVM on GitHub private-repo runners) |
| API 23 | minimum-version compatibility | NOT RUN (needs Firebase Test Lab or a KVM runner) |

## What has actually run
| Date | What | Where | Result |
|---|---|---|---|
| 2026-11-25 | JVM unit tests (7) | GitHub CI, run 45585817533, SHA 8e8d5893 | passed |
| 2026-11-25 | Debug + release assembly | same run | passed |
| 2026-11-25 | Emulator smoke API 23/29/35/36 (x86_64) | GitHub CI | did not start: no /dev/kvm. Infrastructure limit, not an app result |
| 2026-11-25 | API 23 armeabi-v7a software emulation | GitHub CI, run 45587739979 | did not start: android-emulator-runner accepts only x86/x86_64, which need KVM. Infrastructure limit; workflow removed |

## Release artifact (measured from CI)
- Release APK (unsigned, R8): 496,632 bytes (commit cb605a2). Target < 12 MiB.
- Debug APK: about 3.2 MB.
Re-measure on the final signed artifact.

## Data Saver measurement method
- Exact: bytes and requests counted by the app's own interceptor (SavingsMeter); blocked requests are counted exactly.
- Estimated: bytes avoided for blocked resources are labelled estimates, never presented as measured traffic.
- Limit: WebView does not expose per-response wire bytes after TLS/compression, so the saved percentage is not a measurement and is not claimed.
- To measure for real: compare Android per-app data usage (Settings > Network) or `adb shell dumpsys netstats` on the same page set with Data Saver on and off, on a physical phone.

## Fields to record per physical run
Model, Android version, WebView version, RAM, cold start, warm start, RSS, tab switching, background/foreground recovery, memory-pressure recovery, CPU, battery, page load, data used, offline behaviour, network recovery, crashes/ANRs. Compare with another browser under the same conditions.
