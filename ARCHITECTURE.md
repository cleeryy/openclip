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

M7 additions (defined 2026-10-16, animation round):

- Frame model: the session canvas becomes one frame of a clip. Frames
  are full Canvas copies (documented cost; small canvases in tests).
  Symbols: `nAddFrame(handle): Int` (blank, appended after current),
  `nDuplicateFrame(handle): Int` (copy of current), `nDeleteFrame(handle,
  index): Boolean`, `nSetFrame(handle, index): Boolean` (current editing
  frame; loading a document applies to the current frame),
  `nFrameCount(handle): Int`, `nFrameCompositeInto(handle, index, pixels,
  w, h): Int` (app timeline thumbnails and playback pull these).
- Onion skin: `nSetOnion(handle, previous: Boolean, next: Boolean,
  strength: Float): Boolean` — nCompositeInto draws previous (tinted)
  and next frames at `strength` alpha beneath the current frame.
- App: timeline strip (Thumbnails via nFrameCompositeInto scaled 96px),
  fps field default 12, play/pause button (app-side timer, no engine
  loop), onion toggles. Audio track + webm export = M7b post-baseline
  (documented here) — not this round.
- Undo: strokes stay per-frame — each frame owns its undo stacks, so
  switching frames shows the destination frame's stack (empty on a
  fresh frame, preserved on return) — shim documented.

M7b additions (defined 2026-10-16, audio + webm round):

- No new engine/JNI symbols. M7b is app-side only: frames come from
  the existing nFrameCompositeInto, audio from platform MediaPlayer.
- WebM export: app pulls each frame full-res in index order through a
  MediaCodec VP8 surface-input encoder + MediaMuxer OUTPUT_FORMAT_WEBM
  at the clip fps (frames paced at 1/fps through a surface input, EOS
  on last frame). SAF
  CreateDocument("video/webm") via the M4 cache-file bridge (mux to
  cache, copy to the SAF uri). Encoder absence or surface-input
  failure surfaces as export_failed, never a crash.
- Audio track: one clip-level track, session-only (not stored in ORA).
  SAF OpenDocument("audio/*") → cache copy. ClipAudio controller
  (attach/play/pause/stop/release + hasAudio/isPlaying flags);
  TimelineStrip play drives audio via onPlayingChange; fps flows out
  via onFpsChange (default 12) for the exporter.
- Tests (instrumented, +2): webmExportProducesPlayableFile (EBML
  header 0x1A45DFA3 + size floor on a 3-frame clip) and
  audioAttachPlayPause (WAV fixture generated in-code, controller
  state machine; audibility never asserted).

M8 additions (defined 2026-10-16, AI + adjustment round):

- Adjustment layers (engine, non-destructive): nSetLayerAdjustment
  (handle, index, json|null) turns a layer into an adjustment layer
  (null clears the descriptor). JSON: {"type":"brightness"|"contrast"|
  "hue"|"saturation"|"gradientMap", "amount": -1..1 (gradientMap
  ignores amount), "stops":"[\"#RRGGBB:pos\",...]" (gradientMap, same
  encoding as the M3 filter)}. Unknown keys ignored, missing keys use
  code defaults — the M3 params rule.
- Semantics: an adjustment layer paints no pixels; at composite time,
  when the per-pixel layer walk reaches it, its op applies to the
  accumulated output value of everything below (top-level loop and
  group-child loop alike). The pixel is UNPREMULTIPLIED, transformed,
  REPREMULTIPLIED and clamped to [0,alpha] (alpha untouched — the M3
  alpha contract). brightness: +amount*128 on rgb. contrast: scale
  1+amount around 128. saturation: mix toward luma by factor 1+amount.
  hue: rotate by amount*180deg (HSL). gradientMap: stops ramp by
  luminance. Mask/clip/opacity/visibility apply exactly as for normal
  layers. Descriptor survives resize; nLayerState JSON gains
  "adjust":{...}|null. ORA round trip persists the descriptor
  (new <layer> attrs; load of unknown type ignores the descriptor).
- AI super-resolution (app only — no engine/JNI symbols): dependency
  com.microsoft.onnxruntime:onnxruntime-android:1.31.0 (MIT) and the
  bundled model assets/models/super-resolution-10.onnx (ESPCN x3,
  ~240KB, Apache-2.0, https://huggingface.co/onnxmodelzoo/
  super-resolution-10). Flow: current-frame composite bitmap -> luma
  SR x3 + bicubic chroma -> PNG in cache -> nLoadDocument (M7: load
  replaces the current frame, so the clip gains the upscaled frame
  with no new symbols). Model bytes load via createSession(byte[]);
  failure surfaces as a status string, never a crash. The model input
  is FIXED 224x224 (the card's dynamic claim is wrong): the luma plane
  is bicubic-resized to 224 and the x3 output back to exactly 3w x 3h;
  canvases far from 224px lose some SR crispness (honest limit).
  Denoise and auto-colorize are deferred (no permissively-licensed
  mobile weights confirmed) — honest note, revisit in M8b.

M9 additions (defined 2026-10-17, 3D reference + lens round):

- glTF reference model: vendored single-header cgltf (MIT, logged in
  engine/third_party/README.md). nLoadGlbReference(handle, path): Int —
  loads a .glb/.gltf (first scene's meshes, positions + triangle
  edges only, no textures/materials) into the session reference model;
  returns the triangle edge count, -1 on parse/load failure.
  nSetRefView(handle, json): Boolean — keys {yaw,pitch,roll} (degrees,
  0..360), {dist} (model-units, default 3), {fov} (10..120, default
  45), {fisheye} (0..1, default 0 = rectilinear), {offX,offY} (pixels);
  unknown keys ignored, missing keys keep the previous value (M3 rule).
  nSetRefVisible(handle, on): Boolean.
- Rendering: the reference wireframe draws OVER the current frame in
  nCompositeInto only (a tracing aid, never in nFrameCompositeInto —
  thumbnails/export stay clean, like onion). Camera projection:
  perspective + optional fisheye radial distortion (r' = r*(1+k*r^2),
  k = fisheye*0.001); edges rasterized 1px dark gray at 50% alpha.
- App: reference pane (import .glb via SAF → cache file, view sliders
  yaw/pitch/roll/dist/fov/fisheye + show toggle, strings EN/FR). No
  model bundled yet: the engine test fixture is a generated in-code
  cube .glb (clean-room); bundling a libre mannequin is a later asset
  decision.
- 3-point perspective (deferred from M6): nSetRulers accepts up to
  THREE points in "perspective":[[{x,y},...]]; the shim ruler transform
  snaps strokes to the 3-point grid (existing M6 code path).
- Tests: engine — cube fixture loads with 18 edges, projected edges
  land in the composite above frame pixels, fisheye>0 displaces edge
  pixels radially, visible=false removes them, 3-point ruler snaps;
  instrumented +1 — refLoadAndCompositeShowsEdges (fixture .glb written
  to cacheDir, JNI load, composite shows non-white pixels, toggle
  clears them).

M10 additions (defined 2026-10-17, ecosystem round — final):

- Materials (engine): procedural clean-room tones, no assets. nApplyTone
  (handle, name): Boolean — fills the ACTIVE layer's pixels with a
  deterministic 8px-periodic ink pattern (premultiplied-safe: rgb
  clamped to [0,alpha], alpha untouched — the M3 contract); undoable
  like a filter (full-layer tile snapshots, cancel on failure).
  Names: "hatch" (diagonal lines), "crosshatch", "dots", "grid",
  "checker", "stripes" (vertical). Unknown name returns false, layer
  untouched. No new formats, no telemetry.
- WebDAV sync (app only — no engine/JNI symbols): WebdavClient
  (HttpURLConnection, basic auth over https, no TLS bypass) with
  push(docFile, url, user, pass) and pull(url, user, pass): File.
  The UI dialog carries url/user/pass fields (plain, session-only);
  failures surface as status strings. Server trust is the user's own
  self-host (the "sans telemetry" goal: no cloud, no analytics).
- Tests: engine — each tone name repaints the layer with the expected
  periodicity (row/column sampling), alpha stays untouched, unknown
  name = false + byte-identical layer; instrumented +1 — webdav
  round-trip against an in-process loopback WebDAV responder
  (ServerSocket: PUT 201 + GET bytes back + auth failure 401 surfaces
  as failure) and a tone fill via JNI changes the composite.

M6 additions (defined 2026-10-15, vector + rulers round):

- Vector layer model (clean-room, no SVG dependency at the engine level):
  `struct VectorPath { std::vector<openclip::Vec2> pts; std::vector<float>
  widthProfile; std::string kind; /* stroke|eraser-stroke */ }` stored per
  Layer as `std::vector<VectorPath> vectorData` (Layer::isVector=true,
  rests in a normal raster-blend pipeline by rasterizing with the same
  blend/opacity semantics at composite time).
- Symbols: `nAddVectorLayer(handle, name): Int`,
  `nSetVectorMode(handle, index, on: Boolean): Boolean`,
  `nVectorEraser(handle, index, /*bool*/ on, pathJson): Boolean`,
  `nAppendVectorPath(handle, index, pathJson): Int` (pts [{"x":..,"y":..,
  "p":..}], pressure→width via same PointCurve), `nUndoVector(handle,
  index): Boolean` (per-path undo stack per vector layer).
- Rulers (engine-owned overlays computed at composite time, drawn into
  the raster frame): `nSetRulers(handle, kindsJson): Boolean` with kinds
  `{"symmetryX":1.0|"symmetryY":..|"perspective":[[{x:..,y:..},...]]|
  "ellipse":[{cx,cy,rx,ry}]}` — strokes mirrored/snap to the active
  ruler(s) by the SHIM before stamping (single place where input is
  transformed).
- App: vector layer panel entry + ruler toggle row; pressure→width is
  mapped by the engine curve, not by the UI.

M5 additions (defined 2026-10-15, layer system round):

- Layer tree control: `nSetLayerVisibility(handle, index, visible: Boolean)`,
  `nSetLayerOpacity(handle, index, opacity: Float): Boolean`,
  `nSetLayerBlend(handle, index, name: String): Boolean`,
  `nSetLayerName(handle, index, name: String): Boolean`,
  `nAddGroup(handle, name: String): Int`,
  `nMoveLayer(handle, from: Int, to: Int): Boolean`,
  `nAppendChild(handle, parent: Int, name: String): Int`.
- Clipping: `nSetLayerClipped(handle, index, clipped: Boolean): Boolean`
  — clipped layer composites only where the layer above is already
  painted (documented in composite order: the clipped layer uses the
  alpha of the previous layer as its stencil).
- Masks: `nAddLayerMask(handle, index): Boolean` (full-white mask),
  `nMaskTarget(handle, index, onMask: Boolean): Boolean` (redirect
  strokes to the mask; black = hide). Composite multiplies layer alpha
  by mask alpha each pixel. Mask strokes participate in undo.
- App: channels for panel state are these exact symbols; no extra
  engine parsing at the UI site.
- Panel snapshot: `nLayerState(handle): String` — compact JSON
  `{"active":Int,"maskTarget":Int,
  "layers":[{"self":{name,visible,opacity,blend,group,mask,clipped},
  "children":[{...}]}, ...]}` (top-level document order, children for
  groups). Read-only introspection for the Compose LayerPanel; the
  engine stays the single source of truth.

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

V+1 additions (defined 2026-10-20, selection/text/alpha-erase round —
spec docs/v1-selection-texte-gomme.md):

- Eraser mode key extended, NO new symbol. The `mode` JSON key accepted
  by `nSetBrush` grows from `"brush"|"eraser"` to
  `"brush"|"eraser"|"alpha-erase"` (shim-routed, M2 pattern):
  - `"brush"` = paint session ink (unchanged).
  - `"eraser"` = LEGACY alias, frozen: paints toward the canvas
    background (white), exactly the M2/slice-5 behavior. Kept so old
    presets (`.myb` with `mode:"eraser"`) and old docs keep their
    committed look; engine tests lock paintDab-white unchanged.
  - `"alpha-erase"` = dest-out on the ACTIVE raster layer: the stamp
    reduces layer alpha (`a' = a*(1-m)`, rgb rescaled premultiplied and
    clamped `[0,alpha]` — the M3 rgb<=alpha contract, alpha modified BY
    DESIGN here). Pressure scales the erased quantity. Undo = same
    touchTile path as strokes. On an armed mask target the eraser does
    NOT touch pixels: it stamps BLACK into the mask (hide), mirroring
    the M5 "ink luminance drives mask" rule (the mask stamp loop moved
    shim -> engine paintMaskDab, byte-identical, so make test locks
    it). On adjustment layers or
    hidden layers the app refuses the stroke (same barred-ring guard as
    the brush); vector layers keep the M6 `nVectorEraser` path.
- `nSetLayerText(handle, index, json|null): Boolean` — stores the text
  fiche on a top-level layer (opaque to the engine, M8 pattern: null or
  empty clears). JSON keys (shim-parsed, unknown ignored, missing =
  defaults): `text` (string), `font` (system family, default
  `sans-serif`), `size` (px, default 0 = unset), `style`
  (`regular|bold|italic|bold-italic`), `align` (`left|center|right`),
  `leading` (factor, default 1.0), `tracking` (px, default 0),
  `color` (`#RRGGBB`), `box` (`x,y,w`). The engine NEVER rasterizes
  text (no font stack — single-file rule holds): layout is app-side
  (platform Canvas) and pixels are committed through
  `nBlitPngIntoLayer`. ORA round trip persists the fiche as
  `x-openclip-text|font|size|style|align|leading|tracking|color|box`
  attrs on the `<layer>` (readers that ignore unknown attrs degrade to
  the visible pixels, like M8). Load of missing/malformed attrs =
  normal layer (identity). `nLayerState` gains an optional `"text"`
  object (same keys) so the app recovers the fiche (badge `Tx`,
  re-edit) with no extra symbol.
- `nBlitPngIntoLayer(handle, index, path): Boolean` — decodes a PNG
  file (existing stb path, straight->premult conversion shared with
  the PNG loader) and REPLACES the layer surface pixels. Requires an
  exact canvas-size match; false + layer untouched otherwise. Undoable
  via full-layer tile snapshots (filter pattern: touchTile all,
  endStroke on success, cancelStroke on failure). Used by text commit /
  re-edit; each commit is one undo step.
- Selection: deliberately ZERO engine/JNI in V+1. The mask is an
  app-side 1-bit full-resolution buffer (per document, session-only,
  never persisted — closing the doc drops it, announced in the UI);
  marching-ants overlay is composite-only; strokes split at the mask
  boundary app-side; masked filter/tone = composite pull before/after,
  app-side mix under the mask, `nLoadDocument` of the mixed single
  frame into a DUPLICATED frame with the original frame deleted
  (frame count preserved; the load resets undo + flattens layers, both
  announced in the UI — same M4 load contract as everywhere else).
