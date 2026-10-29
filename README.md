# Calque

Free and open-source painting studio for Android tablets — a clean-room
reimplementation of the workflow conventions artists expect from a
professional illustration tool: pressure brushes, layers and masks,
comic pages, frame-by-frame animation, long-form export. No proprietary
code, assets, strings or formats: everything is written from scratch and
released under GPL-3.0-or-later.

> **Brand note.** The launcher/display name is **Calque**; the Android
> `applicationId` stays **`dev.openclip`** (frozen — updates, saved data,
> persisted SAF grants and WebDAV keys are all keyed on it). Repository
> name, CI artifacts and the `x-openclip-*` ORA attributes keep the
> historical name on purpose. See [docs/rebrand.md](docs/rebrand.md).

## Preview

No screenshots are bundled in this repository: binaries are banned from
git by project rule (`.gitignore` refuses `*.apk` / `*.xapk`, see
[Clean-room rule](#clean-room-rule)). To see the app:

- Build the debug APK (see [Build](#build)) and install it on a tablet or
  the local emulator — the studio shell (menu bar, tool rail, collapsible
  dock, color panel, layer panel, timeline) is usable immediately.
- Every CI run on `master` publishes a buildable debug APK and the
  instrumented test APK as workflow artifacts (`openclip-apks`, 7-day
  retention) — the APK itself, not screenshots.
- Tagged releases (`v*`) publish a debug-signed APK plus its `.sha256`
  on GitHub Releases ([latest](https://github.com/cleeryy/openclip/releases)).

## Features

### Engine — M1→M10 (`engine/`, C++17, host-testable)

Tiled RGBA8 canvas with undo, 20 blend modes (normal, multiply, screen,
overlay, add, soft-light, hard-light, difference, color, luminosity,
darken, lighten, linear burn, linear dodge, vivid/linear/pin light,
subtract, divide, exclusion), pressure brush engine with stabilization
and pressure curve, MyPaint `.myb` presets, 26 named filters, layered
PSD import/export, OpenRaster native I/O, PNG/JPEG/TIFF, animated GIF
(median-cut + LZW from scratch), APNG, WebM/VP8 export side, glTF
wireframe reference with camera + fisheye, vector layers with
pressure→width and rulers (symmetry, ellipse, segment, three-point
perspective, guides, grid), per-frame animation with onion skin, cel
holds and cel reorder, procedural manga tones (hatch, crosshatch, dots,
grid, checker, stripes), non-destructive adjustment layers (brightness,
contrast, hue, saturation, gradient map, per-channel curves), layer
groups, masks, clipping, alpha lock, layer flags, composite-time fills
and keyframed layer transforms.

### Studio shell — slices 0→7 (app-side only)

Menu bar, multi-document tabs (max 4), dockable collapsible panels,
2-tap undo / 3-tap redo, pinch/pan/rotate view, stylus-first input,
HSV wheel + swatch sets + eyedropper, live layer thumbnails with tactile
reorder, timeline with scrub and large thumbnails, real-diameter brush
cursor with pressure curve graph, pinnable command bar, JSON workspaces.
Tablet-first (1920×1200@240), phone-usable with everything folded.

### Drawing foundations — T1

Scanline flood fill with tolerance and antialiasing under the selection
mask, magic wand, linear/radial/free gradient tool, geometric shape
stamps (rectangle, ellipse, polygon), feather and quick mask, unsharp
mask / color balance / threshold filters. Four documented JNI entry
points (`nFloodFill`, `nMagicWand`, `nPaintGradient`, `nStampShape`).

### Text and vector tools — T2

Editable text with runs (multiple fonts/sizes/colors per layer), inline
anchored editor, vertical layout and FR/EN hyphenation, outline mode,
free font import via SAF, vector retrace / connect / per-path width and
color, Bézier point editing, permanent vector trajectories, PSD `TySh`
subset export and a dedicated bubble layer composited at composite time.

### Animation — T3

Layer keyframes (`dx`, `dy`, `scale`, `angle`, `opacity`) with
interpolation, keyframed output camera, four parallel tracks, exposure
sheet, APNG export, PNG sequence (ZIP), stickers up to 512 px and
audio-only export. Frame undo stays per-frame and per-track.

### Transforms and creative filters — T4

Selection warp (perspective, skew, bulge), puppet warp (rigid MLS on a
whole layer), smart shape (stroke straightening with ghost preview),
10 additional creative filters (glow, drop shadow, crystallize,
normal map, retro, pencil, Perlin clouds, lens blur, fisheye filter,
panorama remap) and a 3D decor mode with procedural primitives plus
texture painting. Three documented JNI entry points (`nPuppetWarp`,
`nSetRefScene`, `nPaintRefMap`).

### Exchange, pages and productivity — T5

Layer flags (lock, alpha lock, reference, draft — reference/draft layers
are excluded from exports), composite-time fills (solid, gradient,
foil), edge/watercolor filters, `.grd` and `.abr` import with documented
subsets, color-harmony wheel (accord → three curve sheets + a
gradient sheet), replayable macros, align, session history, 1 Hz info
panel,
`project.json` v2 (work/folio/crop/start page, tolerant of v1), folio
numbering at export, read-only double-page view, mm guides with dpi
conversion, zine imposition (multiple of 4), EPUB export, PDF export and
multipage PNG sequences. Two documented JNI entry points
(`nSetLayerFlags`, `nSetLayerFill`).

### Selections, text, true eraser — V+1

Rect/ellipse/lasso selection with an app-side mask, marching ants and
move/free/mesh transform, editable text layers (system-font layout,
pixels committed through a PNG blit, descriptor round-tripped in ORA),
`alpha-erase` dest-out eraser (the legacy paint-over `eraser` mode is
kept frozen for compatibility).

### Comics and long exports — V+2

Panels, bubbles and speed lines on vector layers, reading-order
metadata, vertical webtoon export (concat + slices), multipage
`.ocpages/` projects, SVG export of vector layers, copy/cut/paste,
navigator, opt-in autosave, per-channel tone curves and physical-keyboard
shortcuts.

### Pro comfort — V+3

Canvas color mixer, named selection channels, path snapping and
simplification, text-on-path through the existing bridges, posable libre
mannequin (glTF joints + pose), TIFF import, PDF first-page import,
SVG `<path>` subset import and classic local denoise. AI denoise/colorize
stay conditional on permissively licensed mobile weights (same documented
blocker as M8).

### `.clip` compatibility — read-only, tiers (a) + (b)

Raster import (a) and degraded vector/text import with a per-layer
approximation counter (b). Writing tier (c) is deliberately excluded:
ORA native + PSD interchange remain the interoperable writes
([docs/ROADMAP.md](docs/ROADMAP.md) §3).

### Branding and release performance — alpha 0.2.0

Calque label and adaptive launcher icon (three sheets, monochrome
variant), cinnabar accent reserved for the primary call to action,
OFL display font bundled with its license in `app/src/main/assets/fonts/`,
export names `calque-*`. Release builds enable R8 minification and
resource shrinking with keep rules for the JNI surface and ONNX Runtime
(`app/proguard-rules.pro`), validated by the JNI symbol gate and a full
emulator run after every rule change.

Release tags publish three APKs — `calque-<tag>-arm64-v8a.apk`
(tablets, ~38 MB), `calque-<tag>-x86_64.apk` (emulators, ~45 MB) and
`calque-<tag>-universal.apk` — each with a `.sha256` companion. The
legacy `openclip-<tag>.apk` name is kept as an alias of the universal
build so older download links keep working.

## Prerequisites

- Engine: a C++17 compiler and `make` (Linux host, no Android needed).
- App: Android SDK 34 (`compileSdk`/`targetSdk` 34, `minSdk` 26), NDK 27
  (AGP 8.10 default), JDK 17. The JDK pin belongs in
  `~/.gradle/gradle.properties` (`org.gradle.java.home`) — never in the
  repository, it breaks CI runners.
- The Gradle wrapper is checked in; no global Gradle needed.

## Build

```bash
# Engine only (no Android SDK required)
cd engine && make && make test

# Debug APK
./gradlew :app:assembleDebug

# Instrumented test APK
./gradlew :app:assembleDebugAndroidTest

# Pre-push gate: engine tests + JNI symbol contract + Kotlin + NDK
bash scripts/prepush.sh

# JNI contract check only
bash scripts/check_jni_symbols.sh --source-mode
```

## Tests

- **Engine** — `cd engine && make test`. Headerless assert harness in
  `engine/tests/`, one test file per public header; must stay green
  (currently **237/237 tests, 78 784 checks**).
- **App conformance** — `bash scripts/emutest.sh` on the dev box. It
  boots or reuses the x86_64 AVD (`openclip-emu`), builds the x86_64-only
  APKs, installs them and runs the instrumented suite directly with
  `am instrument`, ending with `OK (N tests)`. Currently **248/248** on
  the phone AVD; the tablet AVD reports 247/248 with one known gesture
  flake that passes in isolation. Never run heavy Gradle builds in
  parallel with the emulator on the 8 GB dev box.
- The emulator suite intentionally does **not** run on GitHub CI
  (too slow and flaky there). CI builds the APKs and cross-checks the
  frozen JNI symbols instead — see `.github/workflows/`.

## Repository layout

```
engine/          C++17 raster engine (public API: engine/include/openclip/)
app/             Kotlin + Jetpack Compose app (JNI via EngineHost)
docs/            ROADMAP.md (plan), TASKS.md (executable log + proofs),
                 slice/tranche specs, format research, parity inventory
ARCHITECTURE.md  frozen JNI boundary (documented before code)
scripts/         prepush.sh (local gate), emutest.sh (emulator gate),
                 check_jni_symbols.sh (JNI contract)
.github/         workflows: engine build+test, Android APK build,
                 release APKs on v* tags; issue/PR templates, CODEOWNERS
```

## Clean-room rule

No code, strings, binaries, formats or assets from any proprietary
painting app are ever copied or reverse-engineered into this project.
Interchange uses open formats only: OpenRaster (native), PSD
(interchange), MyPaint brushes, glTF, GIF/APNG/WebM/PNG/JPEG/TIFF, EPUB
and PDF. Commercial package files and extracted content stay out of git
(see `.gitignore`), and test fixtures are generated in code rather than
committed as binaries.

## Contributing and security

- [CONTRIBUTING.md](CONTRIBUTING.md) — branch/PR workflow, mandatory
  gates, commit conventions, code style.
- [SECURITY.md](SECURITY.md) — supported versions and private reporting.
- [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) — Contributor Covenant 2.1.
- [AGENTS.md](AGENTS.md) — working conventions for human and AI
  contributors (repo layout, JNI freeze rule, gates).

## License

GPL-3.0-or-later. See [LICENSE](LICENSE). Vendored single-file C++
utilities are permissively licensed and logged in
[`engine/third_party/README.md`](engine/third_party/README.md) with origin
URL and license; bundled fonts ship their OFL text next to the files.

## Status and roadmap

Alpha. The complete M1→M10 engine, the STUDIO shell (slices 0→7), the
read-only `.clip` tiers (a)+(b), the post-v1 tranches V+1→V+3 and the
parity tranches T1→T5 are delivered and emulator-gated. Reference docs
and tracking:

- [docs/ROADMAP.md](docs/ROADMAP.md) — parity plan, delivered history and
  validation milestones.
- [docs/TASKS.md](docs/TASKS.md) — executable log with test counts,
  commits, CI runs and proof screenshot paths.
- [docs/parity-gap.md](docs/parity-gap.md) — source-vs-code parity
  inventory (v0 snapshot; deferred gaps tracked in
  [issue #4](https://github.com/cleeryy/openclip/issues/4)).
- [docs/post-v1-backlog.md](docs/post-v1-backlog.md) — original DONE /
  FUTURE / EXCLUDED triage that seeded V+1→V+3.
- Open issues — <https://github.com/cleeryy/openclip/issues> (feature
  requests and bug reports use the templates in `.github/ISSUE_TEMPLATE/`).

Known open items: engine CI `stack-use-after-scope` in PSD save
([#1](https://github.com/cleeryy/openclip/issues/1)), per-ABI release
splits ([#2](https://github.com/cleeryy/openclip/issues/2)), manual
tablet validation by the requester (1920×1200@240 target) and the
deferred AI denoise/colorize models ([#4](https://github.com/cleeryy/openclip/issues/4)).
