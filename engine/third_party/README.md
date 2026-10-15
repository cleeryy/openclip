# Vendored third-party code (engine/third_party)

First-party OpenClip code is GPL-3.0-or-later. The files below are
single-file permissive/public-domain utilities, vendored unmodified
(except as noted). No external C++ frameworks.

| File | Project / License | Origin URL | Fetched |
|------|-------------------|------------|---------|
| `stb_image_write.h` (v1.16) | stb, public domain (Unlicense/MIT dual) — Sean Barrett / nothings.org | https://raw.githubusercontent.com/nothings/stb/master/stb_image_write.h | 2026-10-15 |
| `stb_image.h` (v2.30) | stb, public domain (Unlicense/MIT dual) — Sean Barrett / nothings.org | https://raw.githubusercontent.com/nothings/stb/master/stb_image.h | 2026-10-15 |

Notes:
- `stb_image_write.h` is used for PNG export and in-memory PNG encoding
  of OpenRaster layer data (compiled with `STB_IMAGE_WRITE_IMPLEMENTATION`
  in `src/io/ora.cpp`).
- `stb_image.h` is used for PNG decoding when loading OpenRaster layers
  (compiled with `STB_IMAGE_IMPLEMENTATION` in `src/io/ora.cpp`).
- ZIP handling for `.ora` is first-party clean-room code in
  `src/io/minizip.{h,cpp}` on top of system zlib (`-lz`); no vendored zip
  library was needed.
