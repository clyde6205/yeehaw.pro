# Quick Tools

Six optional tools, reached from **Tools → Quick Tools** in the browser. Status of verification is stated per tool below; nothing here is claimed to pass until the CI run listed in `docs/DEVICE_TEST_RESULTS.md` has completed.

| Tool | What it does | Offline | Permission | Storage |
|---|---|---|---|---|
| Calculator | + − × ÷, brackets, %, exact decimal arithmetic (0.1 + 0.2 = 0.3) | yes | none | none |
| Unit Converter | Length, weight, temperature, volume, area, speed, time, data size | yes | none | remembers last unit pair per category |
| QR Code Scanner | Camera scan; classifies the result; never opens anything by itself | yes (decoding is local) | Camera, asked only when the tool is opened | none; frames are not saved or sent |
| QR Code Generator | Text or link → QR code (up to 213 bytes); save PNG, share | yes | none | PNG in Pictures/YeeHaw on Android 10+; on Android 9 and older in the app's own Pictures folder (no storage permission needed) |
| Notes | Create, edit, search, copy, share, delete | yes | none | own SQLite file `yeehaw_notes.db`, up to 500 notes of 50,000 characters |
| PDF Reader | Open from the system file picker; next/previous page, jump to page, zoom, text search where the phone supports it | yes | none (system picker) | none; a pipe-only provider is copied to a private temp file (≤150 MB) and deleted on close |

## Design rules that were kept
* **Modular.** Each tool is its own `Activity` in `app.yehaw.browser.tools`. Nothing in the browser start path references that package except one menu line guarded by `QuickTools.ENABLED`. Set it to `false` and the menu entry disappears; browsing is unaffected. Deleting the package plus the manifest entries and the menu line removes the feature.
* **No background work.** No service, receiver, worker or scheduled job. The camera is held only while the scanner is on screen. The PDF renderer uses one thread that ends with the screen.
* **One new dependency:** `com.google.zxing:core:3.5.3` (pure Java, Apache-2.0), used only to *decode* QR codes. QR *generation* is the project's own `QrCode.kt`. It adds to the APK size; the exact number is recorded in `docs/DEVICE_TEST_RESULTS.md` once measured from CI builds before/after.
* **Android's own PDF renderer.** No PDF library is bundled.

## Safe QR link handling
A scanned code is classified before anything happens (`QrContent`):
* `http`/`https` links show the website name, are **not opened until the person taps "Open in YeeHaw"**, and carry warnings for unencrypted links, hosts with unusual or look-alike characters (punycode/non-ASCII), number-only addresses, and `user@host` tricks.
* Anything else that would launch another app or run code (`intent:`, `javascript:`, `file:`, `content:`, `data:` …) is shown as unsafe text that can be copied but never opened.
* Plain text is shown as text.

## Known limits (stated, not hidden)
* **PDF text search** relies on the platform's `PdfRenderer.Page.searchText`, available on Android 15+ with the PDF extension. On every other phone the tool says so plainly; reading, zoom and page navigation work on all supported versions. Search could not be exercised on API 23/29 for this reason.
* **Password-protected PDFs** are reported as unsupported, not opened.
* **QR generator capacity** is 213 bytes (QR version 10, level M); longer input is refused with a message.
* **Scanner on emulators:** the emulator camera cannot show a real QR code in CI, so the scanning *decode path* is covered by unit tests that decode generated codes, and the permission and lifecycle paths by instrumented tests. A real-camera test on a physical phone has not been done.
* Translations of the new strings are machine translations (all ten non-English languages) and have not been reviewed by native speakers; the in-app notice already says so.
* Backlog, deliberately **not started**: currency converter, translator, weather, barcode scanner. Excluded: flashlight, video downloader.

## Tests
* JVM unit tests: `app/src/test/java/app/yehaw/browser/tools/QuickToolsLogicTest.kt` (calculator precedence/errors/precision, converter factors and round trips, number parsing, QR content classification, QR generate→decode round trip).
* Instrumented tests: `app/src/androidTest/java/app/yehaw/browser/QuickToolsTest.kt` (hub lists six tools and each opens; calculator; converter; QR generator decode-back, save and share; scanner permission-denied and granted paths; notes persistence/search/delete; PDF generated on the device opened, paged, zoomed, jumped, searched, corrupt and missing files; every tool in Arabic, Urdu, English and Hindi for direction, labels and 48 dp targets). They run in the emulator matrix including API 23 with 1 GB RAM and one core.
