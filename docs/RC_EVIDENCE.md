# Release candidate evidence: commit e0d992a

Source: `integration/reconcile-main` @ `e0d992a65470c813042a943b92b15b0bcb37f219` (`main` untouched).
All 11 jobs completed successfully (checked per job, none cancelled or skipped):

| Workflow | Job | Result | Run |
|---|---|---|---|
| Static product site checks | static-site | success | https://github.com/clyde6205/yeehaw.pro/actions/runs/45768927851 |
| Backend tests | api | success | https://github.com/clyde6205/yeehaw.pro/actions/runs/45768927814 |
| Android build (compile, unit tests, lint) | debug-build | success | https://github.com/clyde6205/yeehaw.pro/actions/runs/45768927882 |
| Android release APK smoke | API 23 and API 36, R8-minified | success, success | https://github.com/clyde6205/yeehaw.pro/actions/runs/45768927889 |
| Android emulator smoke | API 23, API 23 low-end (1 GB, 1 core), API 29, API 35, API 36 | success x5 | https://github.com/clyde6205/yeehaw.pro/actions/runs/45768927899 |

Instrumented tests: 40 passed, 0 failed on API 29, 35 and 36. API 23 jobs report BUILD SUCCESSFUL with no failure (the Gradle log tail is truncated, so no per-test count is quoted).
Crash/ANR scan: no crash or ANR in any YeeHaw process on any emulator job.

Emulator measurements (emulator, not a phone; cold start `am start -W` TotalTime, 5 runs):
API 23 std 490-680 ms, total PSS ~67 MB; API 23 low-end 1 GB/1 core 689-865 ms, PSS ~68 MB; API 29 552-738 ms, ~100 MB; API 35 1058-1185 ms, ~97 MB; API 36 800-1032 ms, ~118 MB.

APK: 805,928 bytes, SHA-256 `4f8c7bc3608966ade9906b4619331b525d0258cc08e54a97114ce056dc9aa067`, v1+v2 signed with a throwaway CI key (not production).

## Test-side defects fixed on the way (no app behaviour was changed for these)
* Font-scale test assumed the address bar was visible on the start page (hidden by design); it now opens a page first.
* API 36 emulator reused a dead private-window task (`START_TASK_TO_FRONT`), which kept window focus and starved later tests; the tests now launch with CLEAR_TASK, return Home on cleanup, and CI disables window animations.
## Real app defect fixed
* Choosing "System default" after another language left the old process-wide Locale (WebView error pages stayed in the previous language). Fixed in `LocaleHelper`, regression test added.

## Not covered by this evidence
Emulators only, no physical phone, no mobile-network run; no human TalkBack session; translations are machine translations not reviewed by native speakers; production signing key not supplied; data-saver savings not measured on real traffic.
