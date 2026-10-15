# OpenClip engine (M1)

C++17 host-buildable painting engine. Public API lives in
`include/openclip/`. No Android/JNI types in sources.

## Layout

- `include/openclip/` — `openclip.h` (umbrella), `surface.h`, `canvas.h`,
  `blend.h`, `brush.h`, `undo.h`, `ora.h`
- `src/raster/` — 256px-tile RGBA8 premultiplied surfaces, N-layer canvas
- `src/blend/` — NORMAL/MULTIPLY/SCREEN/OVERLAY/ADD/SOFT_LIGHT kernels
  (float pipeline, `std::lround` rounding, rgb clamped to alpha)
- `src/brush/` — stamp brush, pressure → size/opacity, supersampled stroke
  interpolation, clean-room MyPaint `.myb` parser (radius/hardness/opaque)
- `src/undo/` — stroke-level tile-snapshot history
- `src/io/` — OpenRaster save/load (`stack.xml` + names/opacities/blend
  names), PNG export; clean-room zip over system zlib, PNG via vendored stb
- `tests/` — headerless assert harness (`make test`)
- `third_party/` — vendored single-file headers, see `README.md` there

## Build

```sh
cd engine && make && make test
```
