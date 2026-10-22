// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip T1 engine — geometric figure stamps (rectangle with
// optional rounded corners, ellipse, regular polygon 3..12 sides).
// Clean-room rasterization with 4x4 coverage supersampling
// (antialiased, premultiplied-safe). Committed pixels (one undo step
// shim-side); NOT editable vector paths (M6 is untouched).
#ifndef OPENCLIP_SHAPES_H
#define OPENCLIP_SHAPES_H

#include <cstdint>
#include <string>

namespace openclip {

class Surface;

struct ShapeSpec {
    std::string kind = "rect";  // "rect"|"ellipse"|"polygon"
    float x0 = 0, y0 = 0, x1 = 1, y1 = 1;  // frame (normalized shim-side)
    int sides = 4;        // polygon only: 3..12
    float rotation = 0;   // degrees, polygon only
    float corner = 0;     // corner radius px, rect only (>= 0)
    bool fillOn = true;
    std::uint8_t fillR = 0, fillG = 0, fillB = 0;
    bool strokeOn = false;
    float strokeW = 1;  // px, 1..64, centered on the edge (half outside)
    std::uint8_t strokeR = 0, strokeG = 0, strokeB = 0;
};

// Rasterize one shape on s (source-over, opaque ink). Coverage comes
// from 4x4 supersampled point-in-shape tests per pixel; the stroke is
// the band within strokeW/2 of the edge on either side. mask
// (nullptr = no selection) constrains the stamp. Returns false +
// surface byte-identical on degenerate spec (unknown kind, zero-area
// frame, polygon sides outside [3,12], negative corner, stroke width
// outside [1,64], fill and stroke both off) or an empty surface.
bool stampShape(Surface& s, const ShapeSpec& spec,
                const std::uint8_t* mask, int maskStride,
                std::string& error);

}  // namespace openclip

#endif
