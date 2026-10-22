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

T1 additions (decided 2026-10-22, fondations dessin round —
spec docs/t1-fill.md; §6 is 0-JNI registry growth, §1–§4 add exactly
FOUR symbols, §5 is 0-JNI app-side nominal):

- Filter registry growth (0 symbol, `filters.h` + `filters.cpp`):
  `nApplyFilter` routes three new names with the M3 params rule
  (unknown keys ignored, missing keys = documented defaults, alpha
  never touched, rgb clamped to [0,alpha], false + byte-identical
  surface on invalid params):
  - `unsharp` `{"radius":2.0, "amount":0.6, "threshold":2}` — true
    unsharp mask with a parameterizable radius: out = c +
    amount*(c - gauss(c,radius)) per channel when |c-blur| >
    threshold, else c. radius must be in (0,64] (same bound as
    gaussianBlur), amount >= 0 (0 = byte-identical no-op), threshold
    0..255. (The M3 `sharpen` stays: amount-only against sigma=1.0.)
  - `colorBalance` `{"shadows":"0,0,0","midtones":"0,0,0",
    "highlights":"0,0,0"}` — three triplets "cr,mv,jb" each
    -100..+100 (cyan-red / magenta-green / yellow-blue per
    adversary channel, clamped). Zone weights are soft luma ramps:
    shadows = 1-luma01 clamped, highlights = luma01, midtones =
    1-|2*luma01-1| (peak at mid gray); out-of-range or malformed
    triplets are an error (false + untouched). All-zero triplets =
    byte-identical no-op.
  - `threshold` `{"level":128, "dither":0}` — Rec.601 luma >= level ?
    white : black (level clamped 0..255; level 0 = all white on any
    non-empty surface, 255 = all black except pure-white luma).
    dither != 0 = ordered Bayer-8 (same matrix as the V+2 GIF
    writer) deciding the binary output. Output is strictly 0/255
    per channel (alpha untouched, so rgb <= alpha holds).
  `canApply`/`filterNames` grow by exactly these three names.
- `nFloodFill(handle, x, y, colorHex, json): Boolean` — bucket fill
  on the ACTIVE raster layer (`openclip/fill.h::floodFill`, clean-room
  scanline flood, iterative span queue — never recursive; visited =
  W*H bitset, OOM = false + untouched). json
  `{tolerance:0..1 (default 0.12), antialias:bool (default true),
  expandPx:0..2 (default 0), sampleMerged:bool (default false)}`
  (unknown keys ignored, missing = defaults, M3 rule). Semantics:
  seed = pixel (x,y) (sampled on the composite when sampleMerged —
  the shim composites first and passes the seed color, the kernel
  stays composite-free); region = 4-connected pixels with
  |lum(px)-lum(seed)| <= tol*255 (Rec.601 luma, same weights as
  gradientMap); paint = session ink straight rgb source-over;
  expandPx dilates the painted region outward (anti-halo under the
  stroke); antialias paints the 1px frontier row at half coverage
  (documented approximation, premultiplied-safe, rgb <= alpha). The engine selection mask
  (V+2, when set via nSetSelectionMask) constrains the fill; when
  unset the app-side V+1 guard (tap outside the selection refused
  before the call) applies — never both sources at once. Undoable
  via full-layer tile snapshots (filter pattern: touchTile all,
  endStroke on success, cancelStroke on failure). False + layer
  untouched on: bad handle, seed outside the canvas, non-raster
  layer, invalid params.
- `nMagicWand(handle, x, y, json): Boolean` — SAME flood kernel in
  mask-output mode (`openclip/fill.h::magicWand`, no second
  implementation: 2 envelopes, 1 algorithm). json
  `{tolerance:0..1 (default 0.15), source:"layer"|"merged"
  (default "layer"), combine:"set"|"add"|"sub" (default "set")}`.
  source="layer" reads the ACTIVE raster layer; "merged" reads the
  shim-side composite pull (one pull, then the kernel — never a
  pull loop). Writes the engine selection mask V+2 (full-resolution
  0/255 bytes, session-global like nSetSelectionMask: cleared on
  frame structural ops and loads). combine merges with the live
  mask (set = replace, add = OR, sub = AND-NOT). The app mirrors
  the mask app-side for the marching-ants overlay (same dual path
  as nSetSelectionMask). Selection is metadata: NOT in engine undo
  (V+1 contract, session-only). False when: bad handle, seed
  outside, non-raster layer, unknown source/combine. Minimum-area
  rule (spec §4.2: regions < 4x4px are rejected with a hint) is
  enforced APP-SIDE BEFORE any engine call: the Kotlin mirror is
  computed first and below the threshold nothing is written on
  either side (neither engine nor app) — keeps the kernel honest
  and testable, no state to roll back.
  Tranche decision (§4.4 prototype measurement, 2026-10-22, KVM
  emulator x86_64): the Kotlin mirror floods ~1 Mpx in 211 ms
  (wandKotlinMirrorTiming1024, near-full-canvas worst case). The
  0-JNI repli is DECLINED: the mirror runs either way (overlay +
  stroke gate need local bytes), while the engine writer stays
  exact for translucent content (premultiplied metric) and avoids
  a full-res PNG encode/decode round trip per tap, which costs at
  least as much. Single authoritative writer per side: app mask
  for overlay/strokes, engine mask for blit/paint constraints —
  both derived from the same metric and source bytes.
- `nPaintGradient(handle, json): Boolean` — paints a gradient on the
  ACTIVE raster layer (`openclip/gradient.h::paintGradient`,
  clean-room, source-over, opaque stops). json `{kind:"linear"|
  "radial"|"free", x0,y0,x1,y1, stops:["#RRGGBB:pos",...] (2..8,
  sorted by pos inside the engine), pts:[[x,y]...]? (free mode:
  one anchor per stop), dither:bool (default true)}` (same stops
  encoding as gradientMap; unknown keys ignored, M3 rule).
  linear: projection of each pixel onto p0->p1; radial: distance
  from (x0,y0) over |(x1,y1)-(x0,y0)| (zero radius = error);
  free: normalized inverse-distance weights w_i = 1/(d_i^2+eps)
  over the stop anchors (bounded, <= 8 stops). Dithering =
  deterministic LCG ±1 LSB (same fixed seed as addNoise:
  reproducible call to call, |delta| <= 1 per channel vs
  dither=false). Engine selection mask constrains when set,
  app-side V+1 guard otherwise (same rule as nFloodFill). Undoable
  via full-layer tile snapshots (filter pattern). False + layer
  untouched on: bad handle, non-raster layer, < 2 stops, stops
  with unparseable color/pos, free mode with mismatched pts/stops
  sizes, degenerate geometry (zero-length linear segment, zero
  radial radius). Alpha-gradient stops are refused (opaque stops
  only — complex alpha gradients are FUTURE, §T1 spec).
- `nStampShape(handle, json): Boolean` — rasterizes ONE shape on the
  ACTIVE raster layer (`openclip/shapes.h::stampShape`, clean-room,
  coverage 4x4 supersampling = antialiased, source-over, stroke
  centered on the edge with half outside). json = ShapeSpec
  serialized `{kind:"rect"|"ellipse"|"polygon", x0,y0,x1,y1
  (frame, normalized shim-side), sides:3..12 (polygon, default 4),
  rotation:degrees (polygon only), corner:radius (rect only),
  fillOn:bool + fill:"#RRGGBB", strokeOn:bool + stroke:"#RRGGBB"
  + strokeW:1..64}` (unknown keys ignored, missing = code
  defaults, M3 rule). No editable vector paths (M6 is untouched):
  pixels are committed in 1 undo step. Engine selection mask
  constrains when set, app-side V+1 guard otherwise. False + layer
  untouched on: bad handle, non-raster layer, degenerate frame
  (zero area), sides outside 3..12, both fill and stroke off.
- §5 (feather/blur + quick mask) is 0-JNI BY CONSTRUCTION
  (nominal path, spec §5.3): the mask stays the app-side
  `SelectionMask` ByteArray (now soft 0..255 alpha, binary
  setters unchanged); feather = separable gaussian blur in Kotlin
  (radius 0..32 dialog slider, radius 0 = byte-identical); quick
  mask = session toggle rendering the mask as a 50% red veil with
  brush-radius disc stamping (white = add, black = remove);
  app-side undo stack (depth 20, session-only). The conditional
  `nFeatherSelection` symbol is NOT added (prototype measurement
  on the 2048px canvas decides; if a later slice measures > 300 ms
  on the target tablet it is documented here BEFORE code, per the
  frozen-JNI rule).
- No single `nPaintOp` generic symbol: the four ops keep their own
  names (the spec's review alternative is declined — explicit
  names keep `check_jni_symbols.sh` and per-op tests readable).

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

V+2 additions (defined 2026-10-20, BD/manga + long-export round —
spec docs/v2-bd-export.md):

- Frame rhythm: `nMoveCel(handle, from, to): Boolean` — reorder cels
  (erase+insert; `cur` follows the moved cel; out-of-range = false).
  Undoable as ONE entry: the shim keeps a pending move stack
  `{frameAfter, histDepthAfter, from, to}` (post-move frame + that
  frame's history depth); `nUndo` applies the inverse move when the
  pending top matches the current frame AND the frame history depth
  is back to the recorded depth (exact LIFO against interleaved pixel
  ops — a pixel op after the move is undone first, then the move). Pixel ops never clear the pending move; switching frames
  defers it until return. Redo of a move is not offered (honest
  limit). `nSetCelHold(handle, index, frames): Boolean` — per-cel
  exposure multiplier 1..32 (default 1, out-of-range = false); pure
  meta, never in undo, read by playback timers app-side and by
  `nExportGif` (GCE delay = round(hold * 100 / fps)). `nSetOnionCount
  (handle, before, after): Boolean` — real neighbor counts 0..4 each
  (out-of-range = false); `nCompositeInto` draws up to N previous/next
  composites tinted under the current frame. `nSetOnion(p, n, s)`
  stays valid and maps to counts 0/1 (backward compatible).
- Selection transform: `nSetSelectionMask(handle, path|null): Boolean`
  — uploads the app-side V+1 mask into the engine (PNG file,
  straight alpha used; exact canvas-size match required, false +
  previous mask kept otherwise; null/empty clears). Session-global
  (like the mask target), cleared on frame add/delete/switch/load.
  `nSelectionBlit(handle, json): Boolean` — transforms the masked
  content of the ACTIVE raster layer. JSON
  `{dx,dy,scale,angle,mode:"move|free|mesh",mesh:[[x,y]x9]}` (unknown
  keys ignored, M3 rule; identity defaults; `mesh` = 3x3 control
  offsets over the mask bbox, used only in mesh mode). Semantics:
  masked pixels are read, cleared in place, resampled (move =
  nearest translate; free = scale+rotate about the mask bbox center
  then translate, bilinear; mesh = per-cell bilinear warp, bilinear)
  and composited back source-over. Undoable via full-layer tile
  snapshots (filter pattern). False + layer untouched when no mask,
  bad layer, or malformed JSON.
- Generic layer fiche: `nSetLayerMeta(handle, index, key, value|null):
  Boolean` — ONE generic symbol instead of N fiche symbols (decided
  here per spec §1.4). Key must match `x-openclip-[a-z0-9-]*` (else
  false); value ≤ 4 KiB (else false); null/empty clears. Stored on the
  top-level layer (`Layer.meta` map, plain data like the M8/V+1
  fiches: survives resize/copy), persisted VERBATIM as ORA `<layer>`
  attrs, reloaded the same way (unknown `x-openclip-*` attrs on load
  are kept into `meta` too, so third-party round trips preserve
  fiches). `nLayerState` gains a `"meta"` object. Panels/bulles/speed
  fiches (`x-openclip-panel`, `-order`, `-bg`, `-tone`, `-density`,
  `-bubble`, `-bubble-text`, `-speed`) and doc reading
  (`x-openclip-reading` on canvas → ORA root attr) flow through this
  channel — zero extra symbols.
- Guides: NO new symbol. `nSetRulers` JSON grows a `"guides"` key:
  `[{x}|{y}|{cx,cy,r}]` (vertical / horizontal / concentric
  guides; unknown shapes ignored, M3 rule; empty/absent clears).
  Snap is engine-side (`openclip/guides.h::snapGuides`, tolerance
  2 px): input pipeline order is SMOOTH THEN SNAP (stabilize first,
  guides snap after — the existing M6 ellipse/line projection keeps
  running before smoothing; guides apply to the smoothed point so a
  guide within tolerance wins). V+2 Bézier = densification app-side,
  0 JNI (polylines densified along Catmull-Rom, replace =
  `nUndoVector` + `nAppendVectorPath`, 1 undo).
- GIF export: `nExportGif(handle, path, json): Boolean` — writes an
  ANIMATED GIF89a from the clip's frames (internal composite loop, no
  app-side pulling). JSON `{loop, maxWidth, dither, fps?}`: `loop`
  0 = play once (no NETSCAPE ext), -1 = infinite (ext 0), N > 1 = N
  plays; `maxWidth` (default 1024, box-downscale, aspect kept);
  `dither` bool (default true: ordered Bayer-8 on the median-cut
  palette); optional `fps` (default 12, the M7 app-side default —
  GCE delay_cs = round(hold * 100 / fps)). Palette = median-cut 256
  from scratch; LZW from scratch (GIF patents expired 2003–2004).
  Frames with 0 size / bad path = false, clip untouched. APNG is
  explicitly deferred (V+2-bis) — GIF + WebM cover short animation.
- Curves adjustment: NO new symbol. `nSetLayerAdjustment` JSON type
  `"curves"`: `{"type":"curves","points":[[in,out]x1..16],
  "channel":"rgb|r|g|b"}` → 256-entry LUT (linear interpolation,
  endpoints clamped, inputs sorted). Stored in `Layer.adjustCurve`
  (`"channel;i,o;…"`), ORA round trip as `adjust-curve` attr,
  surfaced in `nLayerState`. Composite-time, non-destructive, same
  mask/clip/opacity path as M8. Unknown channel → rgb; <1 or >16
  points → identity descriptor kept verbatim (honest no-op, still
  round trips).
- Extra blends: NO new symbol, NO UI change (the LayerPanel list is
  the single source). `blend.h` grows `HardLight`, `Difference`,
  `Color`, `Luminosity` (clean-room, from-scratch HSL round trip for
  color/luminosity). Names: `hard-light`, `difference`, `color`,
  `luminosity` (bare or `svg:`-prefixed, M5 rule). ORA keeps the
  `svg:` names (readers ignoring them degrade to Normal — same as
  M5). Invariants locked by engine tests (difference self = black,
  color keeps dst luma, luminosity keeps src luma…).
- Layered PSD: NO new symbol, `nSaveDocument(…, "psd")` unchanged in
  signature. `savePsd` writes real layer records for ELIGIBLE
  top-level layers (raster, no mask/clip/adjust/vector/fiche):
  name (truncated 31 chars), visibility flag, opacity byte, blend key
  mapped on the EXPLICIT table norm→`norm`, multiply→`mul `,
  screen→`scrn`, overlay→`over`, add→`lddg`, soft-light→`sSoft`;
  any other blend → `norm` + counted as approximated. Everything
  else (groups, masked/clipped/vector/adjust/text layers) is merged
  into ONE bottom `Merged approximations` record + counted. Raw
  planar RGBA per layer, then the merged composite (baseline
  readers keep working — our own `loadPsd` still reads the merged
  view). The approximation count is exposed via the new
  `nPsdApproxCount(handle): Int` (session sticky from the last save;
  0 = pixel-faithful). Groups/masks export stays an honest
  flatten — full fidelity is V+3 work.
- The rest of V+2 (§1 cases/bulles/vitesse, §2 lecture/multipage,
  §3.1 webtoon, §3.3 SVG, §4.2 copier/coller, §5.3 navigateur, §5.4
  autosave) is 0 JNI by construction: cases/bulles = vector paths
  (`kind` `"panel"`/`"bubble"` is an app-side convention, the engine
  treats any non-`eraser-stroke` kind as ink) + `nApplyTone` under
  polygonal masks (pattern V+1 §1.1/§1.5: composite before/after,
  app-side mix, `nLoadDocument` into a duplicated frame — reset-undo
  + flatten announced); multipage = dossier `.ocpages/` (ORA pages +
  `project.json` v1 + `thumbs/`, one page loaded at a time);
  webtoon = `nFrameCompositeInto`/`nCompositeInto` pulls + vertical
  concat + slicing app-side; SVG = `nLayerState` introspection +
  path serialization app-side; copier/coller = composite pull +
  `nAddLayer` + `nBlitPngIntoLayer` (+ alpha-erase under mask for
  couper), clipboard in `DocRegistry` (session-only); navigateur =
  downscaled composite pull + overlay tap-seek; autosave = opt-in
  local ORA + `project.json` writes, never mid-stroke.

V+2 getter (decided here per spec §3.3/§4.3 "à lever avant code"): a
single read-only introspection symbol, no pixel path —
`nVectorPaths(handle, index): String` returns the vector layer's
paths as `{"paths":[{"pts":[[x,y,p]...],"widths":[...],
"kind":"stroke"}]}` (empty object on bad index/non-vector layer).
SVG export (§3.3) and Bézier point editing (§4.3) read through it;
writes keep using `nUndoVector` + `nAppendVectorPath` (a replace =
clear-all + re-add: the pre-edit snapshot is consumed, so one vector
undo empties the layer and the original only comes back via an
app-side re-commit — documented deviation from the "1 undo"
shorthand, surfaced honestly in the UI).

T2 additions (decided 2026-10-22, text + advanced vector round —
spec docs/t2-text-vector.md; delivery order §4 → §3 → §1+§2 → §5):

- Conventions (all 5 items): M3 alpha contract holds (rgb <= alpha;
  only text commits and bubble fill touch alpha BY DESIGN); pixel
  writes = full-layer tile snapshots (1 step); vector ops = full
  VectorHistory snapshot (1 step); fiches are opaque (engine stores,
  round-trips, never interprets); overlay-only UI (inline field,
  trajectories, handles, bubble tail) never enters engine pixels;
  engine coords everywhere (screenToEngine); targets >= 48dp.
- §4 style per path + vertices (0 new symbol): VectorPath grows
  `tip` ("round"|"flat", default round), `sizeMul` (0.25..4, default
  1, multiplies the pressure width profile at raster time),
  `r,g,b` ink (default session ink — the M6 "black per layer" ends
  here), `corners` (1 = hard vertex, cuts the app-side Catmull-Rom
  densification segment; smooth by default). parseVectorPathJson
  reads the new keys (M3 rule: unknown ignored, bad tip = round,
  out-of-range size = 1, unparsable color = session ink);
  rasterizeVectorPaths loses its r,g,b params and paints per-path
  ink (`flat` = square stamps, same AA); eraser-stroke ignores
  color (dest-out, documented). nVectorPaths getter returns the new
  fields (tip/size/color/corners) read-only. nAppendVectorPath JSON
  grows the same optional keys; the §3 replace op accepts an
  optional `style:{...}` to re-apply style. nLayerState gains
  per-layer `"vectorPaths":N` (live path count) and
  `"vectorStored":bool` (sibling present and within cap — the
  visible 256 KiB counter, `.clip`-palier-b pattern).
- §4 ORA vector sibling (0 new symbol): every vector layer writes
  `data/vectorNNN.json`
  (`{"paths":[{pts,widths,tip,size,color,corners,kind}]}`) + attr
  `x-openclip-vector="data/vectorNNN.json"`, IN ADDITION to the
  (empty) PNG (third-party readers: PNG + ignored attr). Missing /
  unreadable sibling at load = PNG alone as a NORMAL raster layer
  (isVector cleared, never a broken layer). Over 256 KiB per layer
  = PNG alone + vectorStored false (visible counter, never
  silent). nLayerState itself is unchanged in shape (only the two
  vector keys above).
- §3 retrace / width / connect (THREE new symbols, below):
  indexed ops on vectorData (VectorHistory snapshot first, 1 step):
  - `nReplaceVectorPath(handle, index, id, pathJson): Boolean` —
    replaces path id's geometry (resampled ~8px like the append
    path, same curve exponent as the session), KEEPS tip/size/
    color/corners unless pathJson carries an optional
    `style:{tip?,size?,color?}`. False + layer intact on bad
    id/malformed JSON. `id` = positional index in vectorData
    (documented): the app re-reads nVectorPaths before every op
    (no id caching across gestures); stale id = false (tested).
  - `nConnectVectorPaths(handle, index, a, b, tolJson): Boolean` —
    fuses paths a+b when an endpoint pair is within tol (default
    12px, optional JSON `{"tol":..}` or bare number, M3 rule):
    concatenates in the seam-minimizing orientation, resamples,
    removes b. False + both intact when too far apart.
  - `nSetVectorWidths(handle, index, id, widthsJson): Boolean` —
    replaces path id's width profile (`{"widths":[...]}` or bare
    array; size must equal pts size, values clamped 0.5..128px).
    The falloff gesture math is app-side; the engine poses.
  Review note (spec §7): a single generic `nVectorOp(handle, json)`
  was considered and DECLINED (same clause as T1 §8): explicit
  names keep check_jni_symbols.sh and per-op tests readable. The
  4-symbol T2 budget below is the cap.
- §1 runs/outline/fonts + §2 inline/vertical/hyphen (0 new
  symbol): nSetLayerText JSON grows opaque keys (M3 rule):
  `outline:{w:0..8,color:"#RRGGBB"}` (absent = off),
  `runs:[{start,len,font,style,size,color}]` (absent/empty = the
  global style, byte-identical round trip; out-of-range runs =
  global style, pixels intact),
  `fontfile` (imported file name — REPRO `repli` below),
  `dir:"h"|"v"` (default h), `hyphen:bool` (default false).
  Layout stays app-side (maison line engine: per-run measure via
  Paint.getTextWidths, word split, pure function, StaticLayout
  replaced) + `hyphenate(word, lang)` rule-based from scratch
  (consonant break, never after 1st / before last 2 letters,
  words <= 5 untouched, documented approximate). Outline =
  STROKE pass under the fill pass in the same blit commit
  (1 undo). Vertical = columns top→bottom, right→left, latin
  pivoted 90° (honest simplified tategaki). ORA attrs `x-openclip-text-runs|outline|fontfile|
  dir|hyphen` (same clean degrade); nLayerState "text" object
  grows the same keys.
- §1 font REPRO repli (documented here BEFORE code per the frozen
  rule): user fonts are NOT embedded in the ORA (no new byte
  channel, keeps the 4-symbol cap): `fontfile` records the name,
  the bytes live app-side (`fonts/` cache); reload without the
  file = sans-serif + `font_substituted` banner (V+1 §2.4
  pattern), pixels always kept. Embedding stays FUTURE work.
- §5 PSD text + bubble layer (ONE new symbol, below): savePsd
  writes a minimal TypeToolObjectSetting subset per ELIGIBLE text
  layer (font name, size, color, frame, UTF-16BE string with u16
  length prefix; key `TySh` as an 8BIM tagged block in the layer
  extra data) IN ADDITION to the pixels (readers without text
  support see pixels, as today). runs > 1 flatten onto the base
  style + approx +1 (heuristic: the opaque runs string holding
  more than one run object; documented). Non-eligible text
  (masked/clipped/group/vector) merges into the base record as
  today. `nPsdApproxCount` counts the flattenings (existing
  channel, 0 new symbol).
  - `nSetLayerBubble(handle, index, json|null): Boolean` — bubble
    fiche on any layer (null/empty clears, normal layer again).
    JSON `{shape:"ellipse"|"rounded", frame:[x,y,w,h],
    tail:[bx,by,tx,ty]?, fill:"#FFFFFF", stroke:"#000000",
    strokeW:4, textLayer:-1}` (M3 rule). Meta, NOT in undo
    (like nSetLayerMeta and V+2 holds — dragging the tail is not
    a stroke). Composite-time render via the from-scratch
    `openclip/bubble.h::renderBubble` kernel (ellipse/rounded
    body + triangular tail, fill + centered stroke, 4x4 coverage
    AA, premultiplied-safe): stampShape (T1) operates on an
    active layer with undo and a mask contract, not on a
    composite-time temp — hence a dedicated pure kernel reusing
    the same supersampling PATTERN (documented here, no silent
    duplication). ORA attrs `x-openclip-bubble|frame|tail|fill|
    stroke|strokew|text` (same clean degrade); nLayerState gains
    `"bubble"` (badge `o`, panel). Bubble layers are PSD
    non-eligible (flattened + counted, V+2 §5.5 rule).
- T2 JNI budget: EXACTLY 4 new symbols (this section documents
  all four BEFORE code): nReplaceVectorPath, nConnectVectorPaths,
  nSetVectorWidths, nSetLayerBubble. Everything else is JSON
  growth on existing symbols.

V+3 additions (decided 2026-10-20, confort-pro round —
spec docs/v3-confort.md; 8/11 items are 0-JNI app-side, only §1.1,
§2b, §5, §6.1 and §8.1 touch the engine, and of those only §5 adds
symbols):

- Mixer knife (§1.1): NO new symbol. `nSetBrush` JSON grows two keys
  (M2 pattern): `"mix"` 0..1 (default 0 = byte-identical brush,
  existing tests lock it) and `"mixRadius"` 0.5..2.0 (pickup-window
  factor × dab radius, default 1). Per dab: average the straight
  local color under the pickup disc on the ACTIVE layer, blend
  ink*(1-mix)+pickup*mix, stamp with the normal size/opacity/
  pressure path. Intra-layer only (same scope as M3 filters).
  Undo = touchTile like strokes. On an armed mask target the mix is
  ignored (same redirect-to-mask stroke as brush/gomme V+1, the V+1
  guard); on adjustment layers the app refuses the stroke (existing
  guard); vector layers keep the M6 path.
- Rulers snap keys (§2b): NO new symbol. `nSetRulers` JSON grows
  `"snapTol"` (px, default 2 = V+2 behavior; <=0 disables ALL snap),
  `"grid"` (0|8|16|32, default 0 = off, other values ignored) and
  `"snapPaths"` (bool, default false: snap to neighboring vector
  path vertices within tolerance). Pipeline order stays SMOOTH THEN
  SNAP (spec §2): guides first, then grid, then path vertices —
  nearest snap wins per stage. Unknown shapes/keys ignored (M3 rule).
- V+3 text = existing bridges only (§3): text-on-path layouts
  app-side (platform drawTextOnPath, system font) and commits via
  `nBlitPngIntoLayer` (1 undo, V+1 pattern); warp reuses
  `nSelectionBlit` mode mesh (V+2); fiches flow through
  `nSetLayerMeta` (`x-openclip-text-path`, `x-openclip-text-offset`).
- Mannequin pose (§5): TWO new symbols (the only new symbols of
  V+3), gated on a libre model (gate asset, spec §5):
  - `nRefJoints(handle): String` — read-only introspection (M9
    `nVectorPaths` pattern, no pixels): named glTF scene nodes as
    `{"joints":[{"name":..,"parent":idx|-1,"pos":[x,y,z]}]}` (rest
    translation). Unnamed nodes (helpers, the M9 cube fixture) are
    EXCLUDED — a model with no named nodes reports
    `{"joints":[]}`. Empty string on bad handle.
  - `nSetRefPose(handle, json): Boolean` — `{bone:eulerDeg...}`
    where each value is either a single number (yaw about Y, the
    turntable axis) or `[x,y,z]` Euler degrees XYZ (R = Rz·Ry·Rx).
    Unknown bones ignored (M3 rule); each axis clamped to ±180;
    NaN/unparseable entries ignored. Always returns true on a live
    handle (never fails, like clamps elsewhere). Overlay-only:
    posed joints re-derive the node world matrices at render time
    in `nCompositeInto` (tracing aid, never in `nFrameCompositeInto`
    — thumbnails/exports stay clean, M9 rule); never in undo.
  - Skinning stays rigid-per-node (no weights in V+3): each node's
    subtree rotates about the node's rest translation. Pose is
    stored on the session model; `pose.json` persistence is
    app-side. Without a libre model only the cube-fixture behavior
    is guaranteed (empty joints, pose accepted but with no named
    bone to address).
- TIFF import (§6.1): NO new symbol. `nLoadDocument` accepts
  `.tif`/`.tiff`: baseline from scratch (no libtiff — single-file
  rule) — uncompressed + LZW, RGB/RGBA8, strips, planar 1, FIRST
  IFD only (page 1/N reported honestly). Refused with a message
  (false + session untouched, M4 contract): JPEG-in-TIFF, CMYK,
  16-bit, tiled. Endianness II/MM, SHORT/LONG tag types.
- Denoise (§8.1): NO new symbol. `nApplyFilter` registry gains
  `"denoise"` with params `{"strength":0..1 (default 0.5),
  "mode":"median|bilateral" (default median)}` — median 3×3 and
  naïve bilateral 3×3 from scratch, blended src*(1-s)+filtered*s
  (strength 0 = byte-identical no-op). Alpha contract M3 holds
  (alpha untouched, rgb clamped to [0,alpha]).
- 0-JNI by construction (§1.2 proof CMYK, §1.3 harmonies, §4 named
  channels, §6.2 PDF first-page via PdfRenderer, §6.3 SVG <path>
  subset → nAppendVectorPath, §7 physical-keyboard shortcuts,
  §8.2/§8.3 AI): no ARCHITECTURE.md change beyond this paragraph.
  §8.2/§8.3 stay CONDITIONAL (no permissive <5 Mo ONNX weight
  confirmed — same gate as M8); only classic §8.1 ships.
