#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# OpenClip — LOCAL instrumented conformance gate (dev box only).
#
# GitHub CI intentionally has no emulator job (too slow/flaky): the
# instrumented suite is a local gate, run before closing a milestone.
# This script boots (or reuses) an x86_64 AVD, builds the x86_64-only
# debug + androidTest APKs, installs them and runs the whole suite with
# `am instrument`, then reports a single verdict.
#
# Usage: bash scripts/emutest.sh [--keep] [--no-build] [--cold]
#   --keep      leave the emulator running after the run (fast retries)
#   --no-build  reuse the existing APKs (skip Gradle)
#   --cold      cold-boot the guest (skip snapshot load); a fresh
#               snapshot is saved at shutdown for fast reboots
# Env: EMU_MEM (guest MB, default 1536), EMU_CORES (default 2),
#      EMU_ACCEL (auto|off, default auto)
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
AVD="openclip-emu"
SERIAL="emulator-5554"
RUN_LOG=/tmp/opencode/emutest.log
OUT_LOG=/tmp/opencode/emutest-last.log
mkdir -p /tmp/opencode

say() { echo "[emutest] $*"; }

JAVA_OK=0
if command -v java >/dev/null 2>&1; then JAVA_OK=1; else
  pin="$(sed -n 's/^org.gradle.java.home=//p' "$HOME/.gradle/gradle.properties" 2>/dev/null | head -1)"
  if [ -n "$pin" ] && [ -d "$pin" ]; then
    export JAVA_HOME="$pin"; export PATH="$pin/bin:$PATH"; JAVA_OK=1
  fi
fi
[ "$JAVA_OK" = 1 ] || { echo "no java found (avdmanager needs it)" >&2; exit 1; }
export ANDROID_SDK_ROOT="$SDK"
export PATH="$SDK/platform-tools:$SDK/emulator:$PATH"
command -v adb >/dev/null 2>&1 || { echo "adb not found under $SDK" >&2; exit 1; }

KEEP=0; BUILD=1; COLD=0
for a in "$@"; do
  case "$a" in
    --keep) KEEP=1;;
    --no-build) BUILD=0;;
    --cold) COLD=1;;
    *) echo "unknown arg: $a (see header)" >&2; exit 2;;
  esac
done

# ---------- system image + AVD ----------
SYSIMG="$SDK/system-images/android-34/default/x86_64"
[ -f "$SYSIMG/system.img" ] || { echo "missing $SYSIMG (sdkmanager 'system-images;android-34;default;x86_64')" >&2; exit 1; }
if [ ! -d "$HOME/.android/avd/$AVD.avd" ]; then
  AVDM="$(ls -d "$SDK"/cmdline-tools/*/bin/avdmanager 2>/dev/null | head -1)"
  [ -n "$AVDM" ] || { echo "avdmanager not found" >&2; exit 1; }
  say "creating AVD $AVD (android-34 default x86_64)"
  echo no | "$AVDM" create avd -n "$AVD" -k "system-images;android-34;default;x86_64" \
    --force --device pixel_6 >/dev/null 2>&1 \
    || echo no | "$AVDM" create avd -n "$AVD" -k "system-images;android-34;default;x86_64" --force
fi

# ---------- build (before boot: memory-safe on an 8 GB box) ----------
APP_APK="$REPO/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$REPO/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
if [ "$BUILD" = 1 ]; then
  say "building x86_64-only debug + androidTest APKs"
  ( cd "$REPO" && ./gradlew -q :app:assembleDebug :app:assembleDebugAndroidTest -PciAbi=x86_64 --build-cache )
fi
[ -f "$APP_APK" ] && [ -f "$TEST_APK" ] || { echo "APKs missing — run without --no-build" >&2; exit 1; }

# ---------- acceleration ----------
ACCEL="${EMU_ACCEL:-auto}"
if [ "$ACCEL" = auto ] && { [ ! -r /dev/kvm ] || [ ! -w /dev/kvm ]; }; then
  ACCEL=off
  say "WARNING: /dev/kvm unavailable in this container — TCG software mode (slow boot)"
fi

# ---------- start or reuse ----------
booted() {
  [ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] \
    && adb -s "$SERIAL" shell pm list packages >/dev/null 2>&1
}
if adb -s "$SERIAL" get-state >/dev/null 2>&1 && booted; then
  say "reusing running $SERIAL"
else
  adb -s "$SERIAL" emu kill >/dev/null 2>&1 || true
  sleep 1
  pkill -f "emulator.*-avd $AVD" 2>/dev/null || true
  sleep 1
  MEM="${EMU_MEM:-1536}"; CORES="${EMU_CORES:-2}"
  ARGS=(-avd "$AVD" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect
        -camera-back none -camera-front none -no-metrics -memory "$MEM" -cores "$CORES")
  [ "$COLD" = 1 ] && ARGS+=(-no-snapshot-load -wipe-data)
  ARGS+=(-accel "$ACCEL")
  say "starting emulator ($ACCEL, ${MEM}MB, ${CORES} cores) — log: $RUN_LOG"
  nohup "$SDK/emulator/emulator" "${ARGS[@]}" >"$RUN_LOG" 2>&1 &
fi

# ---------- boot wait (TCG needs patience) ----------
say "waiting for boot (deadline 30 min)"
deadline=$((SECONDS + 1800))
until booted; do
  if [ $SECONDS -ge $deadline ]; then
    echo "emulator never became usable — see $RUN_LOG" >&2
    exit 1
  fi
  sleep 10
done
say "boot gate passed ($((SECONDS / 1))s elapsed)"

# ---------- install + run ----------
install_retry() {
  local apk="$1"
  for i in 1 2 3; do
    if adb -s "$SERIAL" install -r -t "$apk" >/dev/null 2>&1; then return 0; fi
    echo "[emutest] install retry $i for $(basename "$apk")" >&2
    sleep 15
  done
  return 1
}
install_retry "$APP_APK" && install_retry "$TEST_APK"
say "installed app + test APKs; running suite"
adb -s "$SERIAL" shell am instrument -w -r \
  dev.openclip.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$OUT_LOG" || true

if grep -q 'FAILURES!!!' "$OUT_LOG"; then
  echo "[emutest] FAILED — see $OUT_LOG" >&2; rc=1
elif grep -qE 'OK \([0-9]+ test' "$OUT_LOG"; then
  echo "[emutest] PASS — $(grep -oE 'OK \([0-9]+ test[^)]*\)' "$OUT_LOG" | tail -1)"
  rc=0
else
  echo "[emutest] INCONCLUSIVE (instrumentation aborted or crashed?) — see $OUT_LOG" >&2; rc=1
fi

if [ "$KEEP" = 0 ]; then
  say "shutting down emulator (use --keep to reuse it next time)"
  adb -s "$SERIAL" emu kill >/dev/null 2>&1 || true
fi
exit $rc
