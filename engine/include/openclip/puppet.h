// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip T4 — puppet warp on a whole raster layer (clean-room).
// Pure pixel function so engine tests lock it; undo snapshots and
// JSON parsing stay shim-side (nPuppetWarp, ARCHITECTURE.md
// "T4 additions").
//
// Rigid-by-neighborhood moving least squares, translation-only
// variant (translations are rigid; same spirit as guides.h/anim.h:
// pure, no Canvas): each output pixel samples the source at
// v - D(v), where D(v) is the falloff-weighted mean of the pin
// displacements. Weight = cosine falloff over `radius` px (1 at the
// pin, 0 beyond). Honest limits: 0 pins = byte-identical; 1 pin =
// local translation, far field intact; off-canvas pins ignored;
// > 64 pins = false; radius <= 0 = default (25% of the diagonal).
#ifndef OPENCLIP_PUPPET_H
#define OPENCLIP_PUPPET_H

#include <cstddef>
#include <vector>

namespace openclip {

class Surface;

struct PuppetPin {
    double x = 0.0, y = 0.0;    // anchor (canvas px)
    double dx = 0.0, dy = 0.0;  // displacement (px)
};

// dst = puppetWarp(src, pins, radius): dst resized to src. False
// (dst untouched) on size mismatch, > 64 pins, or empty surface.
bool puppetWarp(Surface& dst, const Surface& src,
                const std::vector<PuppetPin>& pins, double radius);

}  // namespace openclip

#endif
