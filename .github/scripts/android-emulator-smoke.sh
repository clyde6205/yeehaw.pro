#!/usr/bin/env bash
set -uo pipefail

adb wait-for-device
for attempt in $(seq 1 120); do
  if [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
    break
  fi
  sleep 2
done

if [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]]; then
  echo "Android emulator did not finish booting." >&2
  adb logcat -d -v time > /tmp/yehaw-emulator-logcat.txt || true
  adb shell dumpsys meminfo > /tmp/yehaw-emulator-meminfo.txt 2>&1 || true
  exit 1
fi

set +e
gradle --no-daemon :app:connectedDebugAndroidTest
test_result=$?
set -e

if [[ "$test_result" -ne 0 ]]; then
  adb logcat -d -v time > /tmp/yehaw-emulator-logcat.txt || true
  adb shell dumpsys meminfo > /tmp/yehaw-emulator-meminfo.txt 2>&1 || true
fi

exit "$test_result"
