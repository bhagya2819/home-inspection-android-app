#!/bin/bash
# Crash test (PRD §16.1): for each delay, wait for a shutter press in logcat, then force-stop the
# app that many seconds later. The tester presses the shutter on the phone and reopens the app
# after each kill; then run tools/verify-device.sh.
# Usage: tools/kill-on-shutter.sh [delays...]   (default: 0.15 0.6 1.5)
A=${ADB:-~/Library/Android/sdk/platform-tools/adb}
for d in ${@:-0.15 0.6 1.5}; do
  $A logcat -c
  until $A logcat -d -s ImageCapture:D | grep -q takePictureInternal; do sleep 0.05; done
  sleep $d; $A shell am force-stop com.homeinspection.app
  echo "$(date +%T) killed app ${d}s after shutter"
done
echo "all kills done"
