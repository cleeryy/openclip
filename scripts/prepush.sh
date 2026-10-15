#!/usr/bin/env bash
# Local pre-push gate: everything that CI would build, minus the emulator.
# Usage: bash scripts/prepush.sh [--fast]
#   --fast : engine build + tests only
# Machine-level JDK pin lives in $HOME/.gradle/gradle.properties
# (org.gradle.java.home) — never in this repo, it would break CI runners.
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
fail() { echo "PREPUSH FAIL: $1" >&2; exit 1; }

echo "[1/4] engine build + tests"
( cd "$REPO/engine" && make -j"$(nproc)" && make test ) || fail "engine"
if [ "${1:-}" != "--fast" ]; then
  echo "[2/4] check_jni_symbols (source-mode)"
  bash "$REPO/scripts/check_jni_symbols.sh" --source-mode || fail "jni symbols"
  echo "[3/4] Kotlin compile (main + androidTest)"
  ( cd "$REPO" && ./gradlew -q :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin ) \
    || fail "kotlin"
  echo "[4/4] native NDK compile (libopenclip.so, arm64-v8a debug variant)"
  ( cd "$REPO" && ./gradlew -q :app:externalNativeBuildDebug ) || fail "ndk"
else
  echo "[2/4] skipped (--fast)"
  echo "[3/4] skipped (--fast)"
  echo "[4/4] skipped (--fast)"
fi
echo "PREPUSH OK"
