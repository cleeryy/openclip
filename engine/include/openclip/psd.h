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
// Flat export: one composite layer RGB+alpha, raw planar. No layer
// records written; Photoshop-compatible enough for interchange at this
// scope.
bool savePsd(const Canvas& c, const std::string& path, std::string& error);

}  // namespace openclip

#endif
