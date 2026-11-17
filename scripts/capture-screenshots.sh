#!/usr/bin/env sh
# Takes screenshots of the installed debug app on a running emulator (called from CI after the device tests).
# The app is reinstalled first because the instrumented test run uninstalls it.
set -e
APK=$(ls app/build/outputs/apk/standard/debug/*.apk | head -1)
PKG=com.kar.reader.debug
mkdir -p screenshots
adb install -r "$APK" >/dev/null
shot() { # name destination(optional)
  adb shell am force-stop $PKG
  if [ -n "$2" ]; then adb shell am start -n $PKG/com.kar.reader.MainActivity --es com.kar.reader.extra.DESTINATION "$2" >/dev/null
  else adb shell am start -n $PKG/com.kar.reader.MainActivity >/dev/null; fi
  sleep 9
  adb exec-out screencap -p > "screenshots/$1.png"
}
adb shell cmd uimode night no || true
shot 1-home-light
shot 2-transcribe-light transcribe
shot 3-stats-light stats
shot 4-review-light review
adb shell cmd uimode night yes || true
shot 5-home-dark
shot 6-transcribe-dark transcribe
adb shell cmd uimode night no || true
ls -l screenshots
