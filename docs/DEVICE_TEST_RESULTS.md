# Device test results

Rule: nothing below is filled in unless it was measured. Emulator results are labelled as emulator. Physical-device results do not exist yet.

## Test matrix (targets, not results)
| Class | Focus | Status |
|---|---|---|
| 1 GB RAM phone | startup, stability, memory pressure | NOT RUN |
| 2 GB RAM phone | browsing, tabs, background recovery, data use | NOT RUN on a real phone (emulator rows below) |
| 3 GB RAM phone | general performance, tab handling | NOT RUN |
| Modern Android (API 35/36) | compatibility, regression | EMULATOR ONLY: 12/12 instrumented tests pass (run 45605985490); no physical device |
| API 23 | minimum-version compatibility | EMULATOR ONLY: 12/12 pass (same run); no physical device |

## What has actually run
| Date | What | Where | Result |
|---|---|---|---|
| 2026-11-25 | JVM unit tests (13) | GitHub CI, run 45585817533, SHA 8e8d5893 | passed |
| 2026-11-25 | Debug + release assembly | same run | passed |
| 2026-11-25 | Emulator instrumented tests API 23/29/35/36 (x86_64, KVM) | GitHub CI, run 45605985490 | all four passed 12/12. (First API 23 run 45605089807: 11/12; the failing test asserted buttons that had moved into the More menu, test corrected.) Emulator results, not a physical phone |
| 2026-11-25 | API 23 armeabi-v7a software emulation | GitHub CI, run 45587739979 | did not start: android-emulator-runner accepts only x86/x86_64, which need KVM. Infrastructure limit; workflow removed |

## Emulator measurements (NOT a physical phone; x86_64 emulator on a GitHub runner with KVM)
Run 45610859057, debug build, app sitting on the start page after launch. 16 instrumented tests pass on every row.
| Android | Emulator RAM actually granted | Cold start `ThisTime` (5 launches, ms) | App total PSS on start page |
|---|---|---|---|
| 6.0 (API 23) | 1.5 GB (honoured) | 979, 899, 913, 834, 920 | about 68 MB |
| 10 (API 29) | 2.0 GB (image minimum overrides setting) | not captured | about 93 MB |
| 15 (API 35) | 2.5 GB | not captured | about 95 MB |
| 16 (API 36) | 2.5 GB | not captured | about 116 MB |
Caveats: emulator CPU/GPU/storage differ from real low-end phones, debug builds are slower and larger than release, and the figures describe the start page only, not heavy pages. They show the app runs and stays small at idle; they do not prove real-phone speed. A physical 2 GB phone run is still required before any speed or memory claim is made publicly.

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
