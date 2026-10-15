#!/usr/bin/env bash
# Cross-check the frozen JNI contract (dev.openclip.engine.EngineHost).
#   --source-mode : grep Kotlin symbols vs C shim vs ARCHITECTURE.md, no build needed
#   (default)     : also check built libopenclip.so symbol tables via nm
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
KT="$REPO/app/src/main/java/dev/openclip/engine/EngineHost.kt"
SHIM="$REPO/app/src/main/cpp/ExternalC.cpp"
DOC="$REPO/ARCHITECTURE.md"

fail() { echo "FAIL: $1" >&2; exit 1; }
[ -f "$KT" ] || fail "missing $KT"
[ -f "$SHIM" ] || fail "missing $SHIM"

# Kotlin external funs under the fixed class prefix
kt_syms() {
  grep -oE 'external fun ([a-zA-Z0-9_]+)' "$KT" | awk '{print $3}' | sort -u
}

# Native JNI function table (symbolic names n*)
c_syms() {
  grep -oE 'Java_dev_openclip_engine_EngineHost_n[A-Za-z0-9_]+' "$SHIM" | sort -u
}

kt_all="$(kt_syms)"
if [ -z "$kt_all" ]; then fail "no external funs found in $KT"; fi

kt_missing_in_shim=0
while IFS= read -r sym; do
  grep -q "Java_dev_openclip_engine_EngineHost_${sym}(" "$SHIM" \
    || { echo "MISMATCH: $sym declared in Kotlin but missing in shim"; kt_missing_in_shim=1; }
  grep -q "[\` ]${sym}(" $DOC 2>/dev/null || \
  grep -q "${sym}" "$DOC" || \
    { echo "MISMATCH: $sym not documented in ARCHITECTURE.md"; kt_missing_in_shim=1; }
done <<< "$kt_all"

shim_missing_in_kt=0
while IFS= read -r nat; do
  sym="${nat#Java_dev_openclip_engine_EngineHost_}"
  grep -q "external fun ${sym}(" "$KT" \
    || { echo "MISMATCH: shim symbol $sym has no Kotlin declaration"; shim_missing_in_kt=1; }
done <<< "$(c_syms)"

[ "$kt_missing_in_shim" -eq 0 ] && [ "$shim_missing_in_kt" -eq 0 ] || exit 1

if [ "${1:-}" != "--source-mode" ]; then
  mapfile -t sos < <(find "$REPO/app/build" -name 'libopenclip.so' 2>/dev/null)
  [ "${#sos[@]}" -gt 0 ] || { echo "PASS(source-only): no built .so found to cross-check"; exit 0; }
  for so in "${sos[@]}"; do
    while IFS= read -r sym; do
      nm -gD "$so" 2>/dev/null | grep -q "Java_dev_openclip_engine_EngineHost_${sym}[(" \
        || { echo "MISMATCH: $sym not exported in $so"; exit 1; }
    done <<< "$kt_all"
  done
fi

echo "PASS: Kotlin/shim(ARCHITECTURE.md) JNI contract consistent"
