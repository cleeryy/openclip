# Device validation kit (issue #32 §5)

A short, repeatable script for a **real tablet** with an active stylus. The
emulator gate (`scripts/emutest.sh`) proves the engine and the UI wiring; it
cannot prove how the app *feels* on glass. Run this kit before a beta tag and
keep the filled sheet with the release notes.

- Target device: Android tablet ≥ 4 GB RAM, Android 11+, active stylus
  (pressure + hover). Phone runs are welcome but not the acceptance device.
- Build: `calque-<version>-arm64-v8a.apk` (or the universal APK), installed
  with `adb install -r` over the previous build — documents must survive.
- Session length: ~25 min. Record screen + logcat
  (`adb logcat -c && adb logcat > validation.log`) for the whole run.
- Any FAIL blocks the tag. A "degraded but acceptable" item needs the reason
  written in the notes.

## 0. Setup

- [ ] Install over the previous build; existing documents, SAF grants and
      pinned brushes are intact.
- [ ] Airplane mode OFF; autosave ON (Settings → Autosave, 5 min).
- [ ] Fresh 2048×1536 document (`+` tab), 300 dpi, white background.
- [ ] Stylus paired; hover cursor visible over the canvas (if the stylus has
      hover).

## 1. Stylus pressure at three speeds

Draw one long diagonal stroke per speed on separate layers.

| Speed | Gesture | Pass criteria |
|---|---|---|
| Slow | 3 s across the canvas | Width follows pressure smoothly, no stepped bands, no dropped dabs |
| Medium | 1 s | No gaps, no "beads" bigger than 2× the spacing |
| Fast | 0.2 s (flick) | The stroke arrives at the end point, no visible lag tail, no straight-line shortcut |

- [ ] Slow stroke PASS / FAIL
- [ ] Medium stroke PASS / FAIL
- [ ] Fast stroke PASS / FAIL
- [ ] Pressure curve: light press < 25 % of max width, hard press = max, no
      sudden jump at the tip of the curve.
- [ ] Eraser (stylus button) erases only where pressed; alpha intact.

## 2. Palm rejection

- [ ] Rest the palm on the canvas while drawing a stroke: the stroke is
      clean, no stray dots, no camera/zoom jump.
- [ ] Two fingers on the canvas *while* the stylus draws: the stroke
      continues (navigation is ignored once the stylus is down).
- [ ] Put the stylus away, then pan/zoom with two fingers: navigation works
      normally, no ink.
- [ ] Palm + pinch, then stylus: the interrupted gesture leaves no ink
      (rollback path).

## 3. Perceived latency

- [ ] Draw 20 fast circles: the ink appears under the tip, not behind it.
      Any visible lag > 1 pen-width is a FAIL.
- [ ] Toggle a heavy document (4096² × 12 layers, if the device allows it —
      the new-canvas dialog shows the memory cap): strokes stay usable.
- [ ] Zoom 400 % and draw: the cursor ring and the ink stay aligned.

## 4. Hover and cursor

- [ ] Hover at 10 mm: the ring follows with no jitter.
- [ ] Hover near the edge: the ring is clipped exactly at the canvas border,
      not at the screen border.
- [ ] Hover during a two-finger zoom: the ring does not fight the loupe.

## 5. Canvas edges and rotation

- [ ] Draw along all four edges and the four corners: no offset, no dead
      band, no stroke bounce.
- [ ] Rotate the device while a stroke is open: the app saves/keeps the
      document, no crash, the canvas recenters (zoom fit).
- [ ] Rotate back; repeat a stroke at the same coordinates: no visible
      displacement.

## 6. 200 % font scale and TalkBack

- [ ] Settings → Display → Font size = largest (200 %).
      Menu bar, command bar, layer panel and new-canvas dialog: all actions
      reachable, nothing truncated past the point of being tappable.
- [ ] New-canvas dialog: OK/Cancel, W/H steppers, orientation swap — all
      tappable (≥ 48 dp) and announced.
- [ ] TalkBack ON: layer add (+), doc add (+), menu (☰), play/pause, page
      up/down announce a sentence, not a glyph ("plus", "flèche").
- [ ] TalkBack OFF again; font size back to default.

## 7. Multi-document

- [ ] Open 4 documents (the ceiling). Switching tabs keeps each undo stack
      and view zoom.
- [ ] Draw in doc 1, switch to doc 4 and back: the composite matches what
      was left, no cross-document ink.
- [ ] Open a 5th document: the polite "4 documents" dialog appears, nothing
      is created.
- [ ] Kill the app from the recents screen mid-stroke, relaunch: the
      recovery prompt offers the snapshot (issue #32 §2) and restores it.

## 8. Memory limits (measured policy)

- [ ] New-canvas dialog at 4096×4096: the real estimate is shown and OK is
      enabled while under the device cap (824 MB on a 2.5 GB emulator; a
      4 GB tablet gets ~1365 MB).
- [ ] Push W/H until the polite refusal appears: OK greys out, nothing is
      created (no OOM, no crash).
- [ ] Layer add past the document cap (fill a big canvas): the layer panel
      shows the polite memory notice, the document stays intact.
- [ ] Undo after 50+ strokes: works; the settings panel shows the undo
      budget and the oldest steps being dropped is silent (by design).

## 9. `.clip` corpus harness (local files only)

Real Celsys files never enter git. Keep them in a local folder and push it
to the device before a validation run:

```bash
adb push ~/clip-corpus /sdcard/Download/clip-corpus
CALQUE_CLIP_CORPUS=/sdcard/Download/clip-corpus bash scripts/emutest.sh
```

The instrumented `ClipCorpusTest` then imports every `*.clip` (bounded to
200 files) through `DocClip` → ORA → engine, and logs one line per file plus
a summary:

```bash
adb logcat -d -s OPENCLIP_CORPUS
# SUMMARY files=.. ok=.. previewOnly=.. degraded=.. refused=.. avgLayers=..
```

Pass criteria:

- [ ] No crash, no ANR on the whole corpus.
- [ ] Every file the importer *accepts* produces an ORA that the engine
      loads and composites (hard assertion in the test).
- [ ] The refused list is recorded in the release notes (compatibility data,
      not a gate): accepted / preview-only / degraded / refused counts.
- [ ] When `CALQUE_CLIP_CORPUS` is unset the suite still ends `OK (N tests)`
      (the corpus test skips itself).

## 10. Sign-off sheet

```
Build:        calque-<version>-<abi>.apk   (sha256 ................)
Device:       ................  Android .., RAM .... GB
Stylus:       ................  (pressure / hover: yes/no)
Date / tester: ................

§1 pressure     PASS / FAIL   notes:
§2 palm         PASS / FAIL   notes:
§3 latency      PASS / FAIL   notes:
§4 hover        PASS / FAIL   notes:
§5 edges/rot    PASS / FAIL   notes:
§6 a11y         PASS / FAIL   notes:
§7 multi-doc    PASS / FAIL   notes:
§8 memory caps  PASS / FAIL   notes:
§9 corpus       accepted .. / preview .. / degraded .. / refused .. (of ..)
Verdict:        SHIP / BLOCK
```
