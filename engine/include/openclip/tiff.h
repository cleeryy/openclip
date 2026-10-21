// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip V+3 — TIFF import baseline (ARCHITECTURE.md "V+3 additions").
// Clean-room, from scratch (no libtiff — single-file rule). Baseline:
// uncompressed + LZW, RGB/RGBA 8-bit, strips, planar 1, FIRST IFD only
// (page 1/N). Refused honestly (false + session untouched): JPEG-in-
// TIFF, CMYK, 16-bit, tiled, horizontal-differencing predictor.
#ifndef OPENCLIP_TIFF_H
#define OPENCLIP_TIFF_H

#include <string>

namespace openclip {

class Canvas;

// Replace `c` with the baseline TIFF content. On false `c` is left
// untouched and `error` carries a human-readable reason.
bool loadTiff(Canvas& c, const std::string& path, std::string& error);

}  // namespace openclip

#endif
