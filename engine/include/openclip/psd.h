// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip M4 — PSD RGB8 baseline import/export (clean-room parser).
#ifndef OPENCLIP_PSD_H
#define OPENCLIP_PSD_H

#include <string>

#include "openclip/canvas.h"

namespace openclip {

// M4 baseline scope: RGB mode, 8-bit, 3–4 channels, raw (compression 0)
// Merged Image Data only. Layer Records are parsed by nothing in this
// baseline (the merged view is the display truth here); unsupported
// variants are refused with a descriptive error, never guessed.
bool loadPsd(Canvas& c, const std::string& path, std::string& error);
// Layered export (V+2, ARCHITECTURE.md "V+2 additions"): real layer
// records for ELIGIBLE top-level layers (raster, no mask/clip/
// adjustment/vector fiche), raw planar RGBA + name/visibility/
// opacity/blend key. Blend keys follow the EXPLICIT table norm, mul ,
// scrn, over, lddg (add), sSoft (soft-light); any other blend writes
// norm and counts the layer as approximated. Everything else (groups,
// masked/clipped/vector/adjustment layers) merges into ONE bottom
// "Merged approximations" record. The merged composite section is
// always written (baseline readers keep working; our own loadPsd
// still reads the merged view). `approxOut` (nullable) receives the
// approximation count (0 = pixel-faithful); the same count is
// surfaced app-side via nPsdApproxCount.
bool savePsd(const Canvas& c, const std::string& path, std::string& error,
             int* approxOut = nullptr);
// Diagnostic reader for OUR OWN layered layout (tests + UI counter):
// lists the layer record names bottom-to-top ("Merged approximations"
// first when present). False + error on unreadable/non-PSD files.
bool psdLayerNames(const std::string& path, std::vector<std::string>& names,
                   std::string& error);

}  // namespace openclip

#endif
