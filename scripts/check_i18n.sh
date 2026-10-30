#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# OpenClip — EN/FR string parity gate (issue #32 §4).
#
# Every user-facing string lives twice: res/values/strings.xml (EN,
# default) and res/values-fr/strings.xml (FR). This script fails the
# build when the two disagree in a way a translator would:
#   * a key present in one file and missing in the other,
#   * a duplicate key inside one file (AGP would silently merge),
#   * a format placeholder set that differs (%1$s, %2$d, %d, %%),
#   * a missing/extra positional index in an otherwise equal placeholder.
# Wording differences are NOT checked: an English and a French sentence
# are supposed to differ. Call from scripts/prepush.sh; standalone:
#   bash scripts/check_i18n.sh
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
EN="${1:-$REPO/app/src/main/res/values/strings.xml}"
FR="${2:-$REPO/app/src/main/res/values-fr/strings.xml}"

[ -f "$EN" ] || { echo "check_i18n: missing $EN" >&2; exit 2; }
[ -f "$FR" ] || { echo "check_i18n: missing $FR" >&2; exit 2; }

python3 - "$EN" "$FR" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter

en_path, fr_path = sys.argv[1], sys.argv[2]

# A placeholder is %[index$][flags][width][.precision]type — we compare
# the multiset of full placeholder spellings, so "%1$s" vs "%s" counts as
# a mismatch (translators must keep the positional contract), while a
# reordered "%1$s ... %2$d" vs "%2$d ... %1$s" stays valid.
PH = re.compile(r"%(?:\d+\$)?[-+#0]*\d*(?:\.\d+)?[a-zA-Z%]")


def load(path):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        print(f"check_i18n: {path}: invalid XML: {exc}", file=sys.stderr)
        sys.exit(2)
    out, dups = {}, []
    for el in root.findall("string"):
        name = el.get("name")
        if name is None:
            continue
        if name in out:
            dups.append(name)
        out[name] = "".join(el.itertext())
    return out, dups


en, en_dups = load(en_path)
fr, fr_dups = load(fr_path)

errors = []
if en_dups:
    errors.append(f"duplicate keys in EN: {', '.join(sorted(set(en_dups)))}")
if fr_dups:
    errors.append(f"duplicate keys in FR: {', '.join(sorted(set(fr_dups)))}")

missing_fr = sorted(set(en) - set(fr))
extra_fr = sorted(set(fr) - set(en))
for k in missing_fr:
    errors.append(f"missing in FR: {k}")
for k in extra_fr:
    errors.append(f"not in EN (extra): {k}")

bad = 0
for key in sorted(set(en) & set(fr)):
    a = Counter(PH.findall(en[key]))
    b = Counter(PH.findall(fr[key]))
    if a != b:
        bad += 1
        errors.append(
            f"placeholder mismatch {key}: EN {sorted(a.elements())} "
            f"vs FR {sorted(b.elements())}"
        )

if errors:
    print(f"check_i18n: {len(errors)} problem(s) across "
          f"{len(en)} EN / {len(fr)} FR strings", file=sys.stderr)
    for e in errors:
        print(f"  - {e}", file=sys.stderr)
    sys.exit(1)

print(f"check_i18n: OK ({len(en)} keys, EN/FR parity, placeholders match)")
PY
