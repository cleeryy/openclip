# Architecture

## Constraints

1. Clean-room: no code, assets, formats or binaries derived from
   Clip Studio Paint / Celsys. Open formats only: OpenRaster (`.ora`),
   PSD (interchange), MyPaint brush (`.myb`), glTF for 3D reference.
2. The engine must stay host-buildable (Linux + C++17) so it can be
   developed and tested without an Android device or NDK.
3. GPL-3.0-or-later for all first-party code. Vendored third-party
   single-file utilities must be permissively licensed and logged in
   `engine/third_party/README.md` with source and license.

## Module boundaries

```
app/     Kotlin + Compose; owns input (MotionEvent/stylus), UI panels, SAF file IO
         talks to engine ONLY through the JNI boundary below.
engine/  C++17, no Android/JNI types in sources.
  include/openclip/   public headers = the contract
  src/raster          tiles (0x100 px), surface model, color (RGBA8 now, RGBA16F later)
  src/blend           blend-mode kernel, normal|multiply|screen|overlay|add|soft-light|...
  src/brush           stamp-based brush engine + MyPaint .myb parser + pressure curve
  src/undo            command history (stroke-level)
  src/io              OpenRaster read/write (vendored miniz for zip), PNG via stb_image
  tests/              assert-based harness, no external framework
```

## JNI boundary (Phase 1, frozen)

Class: `dev.openclip.engine.EngineHost` (Kotlin object with `System.loadLibrary("openclip")`)
Symbol prefix: `Java_dev_openclip_engine_EngineHost_`

- `nInit(width: Int, height: Int, dpi: Float): Long` → canvas handle
- `nDispose(handle: Long)`
- `nSetBrush(handle: Long, json: String)` → MyPaint-compatible brush descriptor
- `nStrokeBegin(handle: Long, x: Float, y: Float, pressure: Float): Long` (stroke id)
- `nStrokeMove(handle: Long, strokeId: Long, x: Float, y: Float, pressure: Float)`
- `nStrokeEnd(handle: Long, strokeId: Long)`
- `nCompositeInto(handle: Long, pixels: IntArray, width: Int, height: Int)` — full-canvas composite into an ARGB int array for the SurfaceView blit (Phase 1; Phase 2 swaps to AHB/GL image)
- `nUndo(handle: Long): Boolean` / `nRedo(handle: Long): Boolean`

M1 additions (recorded after the fact here, per the rules above, then
implemented):

- `nLayerCount(handle: Long): Int`
- `nSetActiveLayer(handle: Long, index: Int): Int`
- `nAddLayer(handle: Long, name: String): Int` (name reserved, layers are
  auto-named in M1)

Brush JSON (shim-defined schema, MyPaint-compatible params; extra keys
ignored): `{"radius":8,"hardness":0.7,"opaque":1.0,"spacing":0.15,
"color":"#RRGGBB"}`.

M4 additions (defined 2026-10-15, file I/O round):

- `nLoadDocument(handle: Long, path: String): Boolean` — replaces the
  session canvas with a parsed document. Formats auto-detected by
  extension: `.ora` (full OpenRaster round trip incl. layer groups),
  `.png` (single flattened layer), `.psd` (import: flattened composite
  baseline; layer parse as far as RGB8 records support — engine lane
  documents actual depth in its report; no fidelity promises beyond
  that in M4).
- `nSaveDocument(handle: Long, path: String, format: String): Boolean`
  — `ora` | `png` (full composite render) | `psd` (basic flattened
  layer plus per-layer PSD records if feasible in round scope). File
  path is a real filesystem path (app resolves SAF URIs via
  DocumentFile + temp directory copy; engine never touches Android
  APIs).
- No false success: returns false with error surfaced via existing
  string channel on parse failure. Existing object state left untouched
  when loading fails.
- touchTile/history untouched: loading a document resets undo history
  (documented in shim).

M3 additions (defined 2026-10-15, filters round):

- `nApplyFilter(handle: Long, name: String, paramsJson: String): Boolean`
  — applies one named filter over the FULL active layer, engine-side,
  wrapped in a history stroke (undoable like a brush stroke). Returns
  false + no change on unknown name/params (CI validated). Composite
  stays unchanged until application succeeds.
- Filter registry (engine-owned, clean-room, all from scratch):
  kernel-based: `gaussianBlur` (separable, possible SRC-radius param),
  `sharpen`, `motionBlur` (angle/length), `radialBlur` (cx, cy, strength);
  pixelwise: `posterize` (levels), `levels` (in/out/black-point/white);
  browser le toArray: `chromaticAberration` (shift px), `addNoise`
  (amount, monochrome), `mosaic` (block size), `gradientMap` (stops
  array #hex:pos).
  Each filter test-gated on known pixel invariants in engine tests.
- params JSON keys are shim-documented engine-owned; unknown keys are
  ignored, missing keys fall back to defaults documented in headers.

M2 additions (defined 2026-10-15, implemented for M2):

- `nLoadMyb(handle: Long, xml: String): Boolean` — feed a MyPaint `.myb`
  document straight to the engine's `parseMyb`; returns false with the
  brush unchanged when malformed.
- New JSON keys, shim-defined semantics (no JNI change):
  `"stabilize"` 0..1 (exponential input smoothing before stamping),
  `"curve"` 0..1 (pressure exponent mapping, 0 = linear, 1 = heavy
  quadratic), `"mode"` `"brush"` | `"eraser"`. Eraser semantics stay
  documented in the shim: M2 erases toward canvas background, not layer
  alpha, until M5 adds alpha-erase.

Engine owns document truth; the app never caches swapped pixels.
Layers, filters, selection, vector, animation extend the same pattern
with explicit additions to `include/openclip/` only.

## Threading

App: input on UI thread → collection into a lock-free stroke queue →
engine strokes processed on a dedicated render thread
(`HandlerThread` in app). `nCompositeInto` is called on the render
thread only; results posted to SurfaceView.

## Validation ownership

- engine: `make && make test` green on Linux (CI: ci-engine.yml)
- app: CI build of debug APK (android.yml); engine JNI symbols must
  resolve (CI runs `check_jni_symbols.sh`)
