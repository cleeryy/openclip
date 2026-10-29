# Changelog

All notable changes to Calque (formerly OpenClip) are documented here.

The format follows [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/)
and the project adheres to [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html).
Pre-1.0 alpha tags use SemVer pre-release identifiers (`-alpha`, `-alpha.N`);
`v0.x.0-alpha` marks a feature tranche, not API stability.

## [Unreleased]

Nothing yet.

## [0.2.0-alpha] — 2026-10-29

First consolidated alpha of the Calque line: studio shell, comic/animation
tooling, post-v1 tranches and the T1→T5 parity tranches on top of the
M1→M10 engine. Verified on the local emulator gate with **engine 237/237
tests** and **emulator 248/248 tests** (tablet AVD 247/248, one known
gesture flake that passes in isolation).

### Added

- Studio shell, slices 0→7 (app-side, no new JNI): menu bar, document
  registry, collapsible dock with touch guard; 2-tap undo / 3-tap redo,
  pinch/pan/rotate view, stylus-first input, edge-swipe; HSV wheel,
  sliders, hex field, saved color sets and recents, composite eyedropper;
  live layer thumbnails with tactile reorder, searchable blend list,
  pinned size bar; timeline scrub with large thumbnails, delete guard and
  visual onion; true-diameter brush cursor and pressure curve graph;
  multi-document tabs (cap 4), one-tap save, JSON workspaces, pinnable
  command bar; single status line and phone-usable layout.
- `.clip` read-only compatibility, tier (a): container envelope, embedded
  database read-only access and zlib tiles converted to a multi-layer ORA
  document, with an honest composite-preview fallback and explicit refusal
  of hostile files (2 instrumented tests, fixtures generated in code).
- `.clip` read-only compatibility, tier (b): degraded vector/text import
  with validated bounded geometry, in-house rasterization, per-layer
  approximation counter and "degraded: N" banner (EN/FR), unreadable
  layers skipped with a mention (2 instrumented tests).
- V+1 foundations: rectangle/ellipse/lasso selection with session mask,
  marching ants, four actions and transformed mixing; editable text
  layers (system-font layout, PNG blit commit, `x-openclip-*` descriptor
  in ORA); `alpha-erase` dest-out eraser with the legacy paint-over mode
  kept frozen (engine 80/80, emulator 87/87).
- V+2 comics and long exports: panels, bubbles and speed lines on vector
  layers; reading-order metadata; vertical webtoon export; multipage
  `.ocpages/` projects; SVG vector export; Bézier point editing;
  copy/cut/paste; navigator; opt-in autosave; per-channel tone curves;
  keyboard shortcuts; GIF export; full-layer PSD records (engine 103/103,
  emulator 125/125).
- V+3 pro comfort: canvas color mixer, named selection channels, path
  snapping/simplification, text-on-path, posable libre mannequin, TIFF
  import, PDF first-page import, SVG `<path>` subset import, classic local
  denoise (gate absorbed by the T1 run: engine 148/148, emulator 181).
- T1 drawing foundations: scanline flood fill with tolerance and
  antialiasing under the selection mask, magic wand, gradient tool,
  geometric shape stamps, feather/quick mask, unsharp/color balance/
  threshold filters — 4 documented JNI entry points added to
  `ARCHITECTURE.md` before code.
- T2 text and vector tools: text runs with free font import, inline
  anchored editor, vertical layout and FR/EN hyphenation, outline mode,
  vector retrace/connect/per-path width and color, permanent trajectories,
  PSD `TySh` subset export, dedicated bubble layer — 4 documented JNI
  entry points (engine 170/170).
- T3 animation: layer keyframes (`dx`, `dy`, `scale`, `angle`, `opacity`)
  with interpolation, keyframed output camera, four parallel tracks,
  exposure sheet, APNG export, PNG sequence ZIP, stickers ≤ 512 px,
  audio-only export (engine 193/193, emulator 218).
- T4 transforms and creative filters: selection warp (perspective, skew,
  bulge), puppet warp (rigid MLS), smart shape, 10 creative filters
  (glow, drop shadow, crystallize, normal map, retro, pencil, Perlin
  clouds, lens blur, fisheye filter, panorama remap), 3D decor with
  procedural primitives and texture painting — 3 documented JNI entry
  points (engine 217/217, emulator 226 phone / 227 tablet).
- T5 exchange, pages and productivity: 10 additional blend modes (20
  total), layer flags (lock, alpha lock, reference, draft — reference and
  draft excluded from exports), composite-time fills (solid, gradient,
  foil), edge and watercolor filters, `.grd`/`.abr` import with documented
  subsets, harmony wheel, replayable macros, align, session history, 1 Hz
  info panel, `project.json` v2 (folio, crop, start page, tolerant of v1),
  folio numbering at export, read-only double-page view, mm guides with
  dpi conversion, zine imposition, EPUB export, PDF export, multipage PNG
  sequences — exactly 2 documented JNI entry points (engine 237/237,
  emulator 248).

### Changed

- Rebranded to **Calque**: launcher/display name (EN + FR), adaptive
  launcher icon (three sheets, monochrome variant, ink background),
  cinnabar accent limited to the primary call to action and dirty/i-N
  badges, OFL display font bundled with its license, export names
  `calque-*`. `applicationId`, `namespace` and `x-openclip-*` attributes
  stay `dev.openclip` / `openclip` — installs, saved data and SAF grants
  are keyed on them.
- Visual batch V1→V18: adaptive dock width, readable brush bar labels,
  clean footer, larger touch targets, dock no longer blocks canvas input,
  icon and launcher fixes, tableted and phone layouts both audited.
- Release builds enable R8 minification and resource shrinking with keep
  rules for the JNI surface and ONNX Runtime (`app/proguard-rules.pro`);
  the JNI symbol check no longer races `nm` under `pipefail`.
- README rewritten as a product document; parity inventory added
  (`docs/csp-functional-list.md`, `docs/parity-gap.md`).

### Fixed

- Animation tracks created by `nAddTrack` were opaque white instead of
  transparent, hiding lower tracks (product bug found during T3).
- Per-frame undo isolation for animation frames (`frameSwitchIsolatesStrokes`).
- Dock cascade: opening the side dock could leave controls out of reach
  and swallow taps in the instrumented suite.
- Layer rows at +48 dp: the instrumented tests now scroll the outer
  layer list before asserting (4 tests), matching how a user reaches the
  controls.

### Security

- WebDAV sync is limited to cleartext `127.0.0.1` / `localhost` through
  `network_security_config`; remote sync requires an HTTPS endpoint.
- No telemetry, no analytics SDK, no third-party crash reporting.

### Excluded

- Writing `.clip` (tier c) is definitively out of scope; ORA native, PSD
  interchange and the open export formats remain the only writes.
- AI denoise and auto-colorize remain deferred until permissively
  licensed mobile weights exist.

## [0.1.0-alpha.3] — 2026-10-20

Documentation-only tag, no functional change: recorded that `.clip`
write support (tier c) is definitively out of scope and refreshed the
post-v1 roadmap. The shipped APK is identical in behaviour to
`0.1.0-alpha.2` plus later `master` builds.

## [0.1.0-alpha.2] — 2026-10-18

- Fixed tablet studio layout and the touch/stroke pipeline (view matrix,
  stylus priority, dock sizing); emulator suite 24/24.

## [0.1.0-alpha] — 2026-10-18

- First public pre-release: engine M1→M10 (tiled canvas, brushes,
  filters, ORA/PSD/PNG I/O, layers/masks/clipping, vector layers and
  rulers, animation with onion skin, adjustment layers and on-device
  super-resolution, glTF wireframe reference, procedural tones and
  self-hosted WebDAV sync), Compose studio shell, debug-signed APK with
  `.sha256` published on GitHub Releases.

[Unreleased]: https://github.com/cleeryy/openclip/compare/v0.2.0-alpha...HEAD
[0.2.0-alpha]: https://github.com/cleeryy/openclip/compare/v0.1.0-alpha.3...v0.2.0-alpha
[0.1.0-alpha.3]: https://github.com/cleeryy/openclip/compare/v0.1.0-alpha.2...v0.1.0-alpha.3
[0.1.0-alpha.2]: https://github.com/cleeryy/openclip/compare/v0.1.0-alpha...v0.1.0-alpha.2
[0.1.0-alpha]: https://github.com/cleeryy/openclip/releases/tag/v0.1.0-alpha
