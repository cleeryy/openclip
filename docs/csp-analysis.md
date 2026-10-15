# Feature map of the analyzed app (clean-room study)

Source of analysis: passive static inspection of a public XAPK
distribution with the REA evidence chain (`inspect_artifact`, archive
inventory; evidence id `ev_952bdf19cb9e5e108d4fd918f51fbe049aebbbc374672c18a35ec7e5387a5156`).
Target SHA-256: `a2f6453d635cf00e1edb6e7d3b5c06baed74ec4bbc29da46d396a524ba1c3931`
(score: **observed**, authority: shipped-artifact, 2026-10-15).

App inspected: Clip Studio Paint 5.1.4 (Celsys), package
`jp.co.celsys.clipstudiopaint.googleplay`, min SDK 33, target SDK 36.
**No code, strings, assets or binaries were copied from it.** This file
documents only observed structural facts to scope our own
reimplementation under GPL-3.0.

## Observed architecture (static evidence)

| Fact | Evidence |
|---|---|
| Whole UI is one `NativeActivity` (`PWLegacyJniCustomNativeActivityAndroid`); no Android UI toolkit visible | decoded base manifest |
| Core engine is a single ~195 MB C++ shared object `libClipStudioPaint.so` | arm64 split `lib/` inventory |
| ~45 engine filter plugins `*.cpm.so`: Gauss/Motion/Radial/Rotation blur, Sharp×4 + UnSharpMask, Mosaic, Zigzag, Sphere, Pinch, Polar, Spiral, SimpleWave, Waveform, PerlinNoise, LensDistortion/Fisheye/Panorama, PencilArt, AddNoise, ChromaticAberration, FilmCameraStyle, NormalMap, ReduceNoise, Dust, LineWidth, Smoothing | arm64 split `lib/` inventory |
| File-format modules: PSD/PSB import+export, PDF(Core) import+export, audio utilities | arm64 split lib names |
| ML: on-device inference runtime (`libailia`), Caffe super-resolution weights x2/x3/x4 + denoise variants shipped in `Settings/PretrainedWeights/`, Samsung S-Pen prediction (TFLite stack `libSPen*`), ML Kit barcode scanning | arm64 split + asset inventory |
| UI web layer (dialogs/HTML) ships [Closure Library](https://developers.google.com/closure-library/) (legacy, retired framework) | `assets/HTML.zip` inventory |
| Preinstalled content plane (~348 MB asset pack): brush presets per language (`.bfps`), tool configs (`.todb/.tomd`), 108 workspace states (`.spws`), 16k+ material files (brushes/textures/templates with per-item catalog), 3D mannequins/scenes (`.c3dd/.cs3o`), poses (`.pep`), color sets, gradations, ICC profiles, additional fonts | `PreinstallFiles.apk` asset zips |
| Platform integration: Play billing, FCM, Asset Packs, Analytics, Crashlytics, AppsFlyer, CameraX, Bluetooth LE | manifest services/receivers/libraries |

## Derived scope for OpenClip (our choices, not their behavior)

- Keep: raster painting core, layers + blend modes, brush engine with
  pressure/volume, stabilize, ~20 classic filters, undo, PSD import +
  OpenRaster native + PNG/JPEG export, stylus/pressure-first input,
  keyboard shortcuts, French/English localization.
- Replace: `.bfps` brush presets → MyPaint `.myb` (open format);
  `.clip` model → OpenRaster; Celsys materials → open bundled
  materials; on-device ML → later phase with portable runtimes
  (ONNX/GGUF); asset store/cloud → future self-hosted option.
- Phase order and milestones: see [ROADMAP.md](ROADMAP.md).

## Known unknowns

- Exact UI behaviors respond to proprietary code we did not and will
  not decompile; UX parity is by convention, not by copying.
- Android-only native libs observed on arm64 only; x86 splits exist in
  the Play Store distribution but were not part of this XAPK.
