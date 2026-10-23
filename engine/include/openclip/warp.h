// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip T4 — perspective/skew/bulge selection warp (clean-room,
// bilinear resample). Pure pixel function on Surface + mask bytes so
// engine tests lock it; undo snapshots and JSON parsing stay
// shim-side (nSelectionBlit, ARCHITECTURE.md "T4 additions").
//
// Semantics: `src` is the layer surface, `mask` its w*h selection alpha
// (0 = untouched). The result starts as src with the masked area
// cleared, then the masked content is remapped and composited back
// source-over (premultiplied). Sampling always reads the SOURCE
// surface, never the cleared tile — no tile seams (locked by test).
// Neutral params (quad = bbox, ax=ay=0, amount=0) are byte-identical.
// Modes:
//   "persp" = quad remap: the 4 bbox corners are pulled to 4 free
//             points (clockwise from top-left). Inverse homography
//             + bilinear. Degenerate quad (zero determinant, doubled
//             points) = false, dst untouched.
//   "skew"  = shear {ax,ay} degrees (clamped ±60) about the bbox
//             center + bilinear.
//   "bulge" = radial grow/pinch {amount} -1..1 (negative = pinch) +
//             {radius} 0.1..1 (fraction of the bbox half-diagonal),
//             cosine falloff, centered on the bbox + bilinear.
#ifndef OPENCLIP_WARP_H
#define OPENCLIP_WARP_H

#include <cstdint>
#include <string>
#include <vector>

namespace openclip {

class Surface;

struct WarpSpec {
    std::string mode = "persp";  // "persp" | "skew" | "bulge"
    // Persp: 4 quad corners, clockwise from top-left, absolute px.
    double quad[4][2] = {{0, 0}, {0, 0}, {0, 0}, {0, 0}};
    bool quadSet = false;
    double ax = 0.0, ay = 0.0;  // skew degrees (clamped ±60)
    double amount = 0.0;         // bulge -1..1
    double radius = 1.0;         // bulge 0.1..1 (half-diagonal fraction)
};

// dst = warp(src, mask, spec) per the semantics above. dst is resized
// to src. Returns false (dst untouched) on size mismatch, empty mask,
// unknown mode, degenerate geometry, or out-of-range params.
bool blitWarp(Surface& dst, const Surface& src,
              const std::vector<std::uint8_t>& mask, const WarpSpec& spec);

}  // namespace openclip

#endif
