# Roadmap — parity plan (windliest first, honest sizing)

Goal: widest possible feature parity with a professional painting app
(Clip Studio Paint 5 as reference scope, see csp-analysis.md) via
clean-room reimplementation. Each milestone ships something usable.

## M1 — Foundation (this build)
- [x] Repo, license (GPL-3.0), architecture contract (ARCHITECTURE.md)
- [x] Engine skeleton host-buildable: tiles, RGBA8 surfaces, blend modes
      (normal, multiply, screen, overlay, add, soft-light),
      stamp-based brush with pressure→size/opacity, stroke-level undo,
      OpenRaster save/load, PNG export
- [x] App skeleton: Compose UI shell (tool palette, layer panel stub,
      color picker), SurfaceView canvas rendering engine composites,
      stylus/stroke input pass (MotionEvent pressure wired to engine)
- [x] CI: engine tests (Linux), Android debug APK build, JNI symbols check

## M2 — Brush hardening
- [ ] MyPaint .myb palette import + preset browsing UI
- [ ] stabilization/smoother (1-2 frame input latency budget), tablet
      pressure curve editor, tilt support
- [ ] sub-stroke quality: interpolation > adequate, stamp density

## M3 — Filters (first 10, GPU/CPU adaptive)
- [ ] Gaussian blur, sharp+unsharp, mosaic, add noise, chromatic
      aberration, radial/motion blur, perlin noise layer, gradient map,
      posterize/level. Filter dialog framework + preview pipeline

## M4 — File I/O completeness
- [ ] OpenRaster: full round trip incl. layer groups and blend modes
- [ ] PSD import (layers/blends/masks) + PSD export (basic)
- [ ] EXIF-preserving PNG/JPEG export, export dialog (size scale)

## M5 — Layer system completeness
- [ ] layer groups + clipping + masks + alpha lock
- [ ] adjustment layers (brightness/contrast, hue, gradient map)
- [ ] layer panel UX: reorder, blink modes, blend mode dropdown

## M6 — Vector layers + rulers
- [ ] vector layer model (Krita-style, path data in engine), pressure
      mapped to width, vector eraser
- [ ] rulers (perspective 1/2/3-point, symmetry, ellipse)

## M7 — Animation
- [ ] timeline UI, frames, onion skin, playback at actual fps
- [ ] audio track on playback; export frame strip/webm

## M8 — AI tools
- [ ] super-resolution + denoise via portable runtime (ONNX Runtime)
- [ ] auto-colorize assist (if open weights available)

## M9 — Reference and 3D
- [ ] glTF reference-viewer pane (modèles mannequin libres OSMIT)
- [ ] camera distortion / fisheye tool on imported 3D

## M10 — Ecosystem options (no Google-services mandators)
- [ ] self-hosted sync/storage plugin (WebDAV/Forgejo releases)
- [ ] open material packaging + browsing UI

Not planned: ads/telemetry, Play Billing, fingerprinting SDKs, asset
store Celsys-only DRM — see LICENSE of first-party
GPL-3.0-or-later.
