// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip M4 engine — document-level load/save across formats.
#ifndef OPENCLIP_DOCUMENT_H
#define OPENCLIP_DOCUMENT_H

#include <string>

#include "openclip/canvas.h"

namespace openclip {

enum class DocFormat : std::uint8_t {
    Unknown = 0,
    Ora,
    Png,
    Psd,
    Tiff,  // V+3 baseline (uncompressed/LZW, RGB/RGBA8, strips, page 1)
};

// Sniff the byte signature; extension only as a hint for ambiguous ZIP
// payloads. Never executes the file.
DocFormat detectFormat(const std::string& path);

// Load a full document into `c` (replacing its content). On false, `c` is
// left untouched and `error` carries a human-readable message.
bool loadDocument(Canvas& c, const std::string& path, std::string& error);

// Save `c` in `format` ("ora" | "png" | "psd"). PSD export is layered
// (V+2, see psd.h); `approxOut` (nullable) receives the PSD
// approximation count (0 = pixel-faithful, untouched for ora/png).
bool saveDocument(const Canvas& c, const std::string& path,
                  const std::string& format, std::string& error,
                  int* approxOut = nullptr);

}  // namespace openclip

#endif
