// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip V+2 — selection transform (clean-room, bilinear resample).
// Pure pixel function on Surface + mask bytes so engine tests lock it;
// undo snapshots and JSON parsing stay shim-side (nSelectionBlit,
// ARCHITECTURE.md "V+2 additions").
//
// Semantics: `src` is the layer surface, `mask` its w*h selection alpha
// (0 = untouched). The result starts as src with the masked area
// cleared, then the masked content is resampled through the transform
// and composited back source-over (premultiplied). Modes:
//   "move" = integer/fractional translate (nearest);
//   "free" = scale+rotate about the mask bbox center, then translate
//            (bilinear);
//   "mesh" = 3x3 control-offset warp over the mask bbox (bilinear per
//            cell); scale/angle ignored.
#ifndef OPENCLIP_TRANSFORM_H
#define OPENCLIP_TRANSFORM_H

#include <cstdint>
#include <string>
#include <vector>

namespace openclip {

class Surface;

struct TransformSpec {
    double dx = 0.0, dy = 0.0;  // translate (px)
    double scale = 1.0;         // free mode (<= 0 = identity)
    double angleDeg = 0.0;      // free mode, clockwise
    std::string mode = "move";  // "move" | "free" | "mesh"
    // Mesh mode: 9 control offsets (row-major over the mask bbox),
    // each {ox, oy} added to the grid node position.
    double mesh[9][2] = {};
};

// Mask bounding box (selected pixels with alpha > 0). False when empty.
bool selectionBBox(const std::vector<std::uint8_t>& mask, int w, int h,
                   int& x0, int& y0, int& x1, int& y1);

// dst = transform(src, mask, spec) per the semantics above. dst is
// resized to src. Returns false (dst untouched) on size mismatch,
// empty mask, or unknown mode.
bool blitSelection(Surface& dst, const Surface& src,
                   const std::vector<std::uint8_t>& mask,
                   const TransformSpec& spec);

}  // namespace openclip

#endif
