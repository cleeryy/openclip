// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip M1 engine — OpenRaster save/load + PNG export.
#ifndef OPENCLIP_ORA_H
#define OPENCLIP_ORA_H

#include <string>

namespace openclip {

class Canvas;

// Save canvas as OpenRaster (.ora): mimetype, stack.xml, per-layer PNGs,
// mergedimage.png thumbnail. Returns false + error on failure.
bool saveOra(const Canvas& c, const std::string& path, std::string& error);
// Load .ora (layer names, opacities, blend names from stack.xml).
bool loadOra(Canvas& c, const std::string& path, std::string& error);

// Export composited canvas to PNG file.
bool exportPng(const Canvas& c, const std::string& path, std::string& error);

}  // namespace openclip

#endif
