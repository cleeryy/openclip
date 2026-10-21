# OpenClip

Free and open-source painting app for Android tablets — a clean-room
reimplementation of the workflow conventions users expect from a
professional illustration tool (pressure brushes, layers, filters,
animation, comics pages). No proprietary code, assets or formats:
everything is written from scratch and released under GPL-3.0-or-later.

## Preview

No screenshots are bundled in this repository (binaries are banned
from git by project rule). To see the app:

- Build the debug APK (see below) and install it on a tablet or the
  local emulator — the studio shell (menu bar, tool rail, collapsible
  dock, color panel, layer panel, timeline) is usable immediately.
- Every CI run on `master` publishes a buildable debug APK as a
  workflow artifact (no screenshots, the APK itself).
- Tagged releases (e.g. `v0.1.0-alpha`) ship a signed APK +
  `.sha256` on GitHub Releases.

## Features

**Engine (M1→M10, `engine/` C++17, host-testable):** tiled RGBA8
canvas, blend modes (normal, multiply, screen, overlay, add,
soft-light, hard-light, difference, color, luminosity), pressure brush
engine with stabilization and pressure curve, MyPaint `.myb` presets,
10 procedural filters (blur, sharpen, mosaic, noise, chromatic
aberration, radial/motion blur, gradient map, posterize, levels) plus
denoise, non-destructive adjustment layers (brightness, contrast,
hue, saturation, gradient map, curves LUT), full layer system (groups,
masks, clipping, alpha), vector layers with pressure→width and
rulers (symmetry, ellipse, segment, 3-point perspective, guides,
grid), per-frame animation with onion skin, cel holds and cel
reorder, procedural manga tones (hatch, crosshatch, dots, grid,
checker, stripes), glTF wireframe reference with camera + fisheye,
animated GIF export (median-cut + LZW from scratch), TIFF import
baseline, layered PSD export with approximation counter.

**Studio shell (slices 0→7, app-side only):** menu bar, multi-doc tabs
(max 4), dockable collapsible panels, 2-tap undo / 3-tap redo,
pinch/pan/rotate view, stylus-first input, HSV wheel + swatch sets +
eyedropper, live layer thumbnails with tactile reorder, timeline with
scrub and large thumbnails, real-diameter brush cursor with pressure
curve graph, pinnable command bar, JSON workspaces. Tablet-first
(1920×1200@240), phone-usable with everything folded.

**`.clip` compatibility (read-only):** raster import (a) and
vector/text degraded import with per-layer approximation counter (b);
write (c) deliberately excluded — ORA native + PSD interchange remain
the interoperable writes.

**V+1 (selection, text, true eraser):** rect/ellipse/lasso selection
(app-side mask, marching ants, move/free/mesh transform),
editable text layers (system-font layout, pixels committed via PNG
blit, descriptor round-tripped in ORA), `alpha-erase` dest-out eraser
(legacy paint-over `eraser` mode kept frozen).

**V+2 (comics / long exports):** panels, bubbles and speed lines on
vector layers, reading order metadata, vertical webtoon export
(concat + slices), multipage `.ocpages/` projects, SVG export of
vector layers, Bézier point editing, copy/cut/paste, navigator,
opt-in autosave, per-channel tone curves, physical-keyboard
shortcuts.

**V+3 (pro comfort):** canvas color mixer, harmony wheel, named
selection channels, path snapping/simplification, text-on-path via
existing bridges, posable libre mannequin (glTF joints + pose),
PDF first-page import, SVG `<path>` subset import, classic local
denoise. AI denoise/colorize stay conditional on permissively
licensed mobile weights (same documented blocker as M8).

## Prerequisites

- Engine: a C++17 compiler + `make` (Linux host, no Android needed).
- App: Android SDK 34 + NDK r26+, JDK (pinned in
  `~/.gradle/gradle.properties`, never in the repo).
- The Gradle wrapper is checked in.

## Build

- Engine: `cd engine && make && make test`
- App (debug APK): `./gradlew :app:assembleDebug`
- Pre-push gate (engine tests + JNI symbol contract + Kotlin + NDK):
  `bash scripts/prepush.sh`
- JNI contract check only:
  `bash scripts/check_jni_symbols.sh --source-mode`

## Tests

- Engine: `cd engine && make test` (assert harness in
  `engine/tests/`, must stay green; one test file per header).
- App conformance: `bash scripts/emutest.sh` on the dev box — boots
  the x86_64 AVD (KVM when available, else TCG), installs the built
  APKs and runs the instrumented suite; must end with `OK (N tests)`.
  Never run heavy Gradle builds in parallel with the emulator
  (8 GB RAM box). The emulator suite intentionally does **not** run on
  GitHub CI (too slow/flaky there); CI builds the APKs and
  cross-checks JNI symbols instead.

## Repository layout

```
engine/          C++17 raster engine (public API: include/openclip/)
app/             Kotlin + Jetpack Compose app (JNI via EngineHost)
docs/            ROADMAP.md (plan), TASKS.md (executable log + proofs),
                 ARCHITECTURE.md mirror of the frozen JNI boundary,
                 slice specs, format research, backlog
scripts/         prepush.sh (local gate), emutest.sh (emulator gate),
                 check_jni_symbols.sh (JNI contract)
.github/         workflows: engine build+test, Android APK build,
                 release APKs on v* tags
```

## Clean-room rule

No code, strings, binaries, formats or assets from any proprietary
painting app are ever copied or reverse-engineered into this project.
Interchange uses open formats only: OpenRaster (native), PSD
(interchange), MyPaint brushes, glTF, GIF/WebM/PNG/JPEG. Commercial
`.xapk`/`.apk` files and extracted content stay out of git.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE). Vendored single-file
C++ utilities are permissively licensed and logged in
`engine/third_party/README.md` with origin URL and license.

## Status / roadmap

M1→M10 delivered, STUDIO slices 0→7 delivered, `.clip` read (a)+(b)
delivered, V+1 and V+2 delivered and emulator-gated, V+3 implemented
per spec. See [docs/ROADMAP.md](docs/ROADMAP.md) for the parity plan
and history, [docs/TASKS.md](docs/TASKS.md) for the executable log
with test counts, commits and CI runs, and
[docs/post-v1-backlog.md](docs/post-v1-backlog.md) for the DONE /
FUTURE / EXCLUDED triage.
