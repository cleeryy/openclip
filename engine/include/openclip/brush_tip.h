// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip B2 (issue #31 slice B) — installable brush tips.
//
// A `.abr` (or any) tip is an 8-bit coverage mask. The app writes it as
// an RGBA PNG (alpha = coverage); the shim decodes the PNG, calls
// brushTipFromRgba and every paint dab stamps this mask instead of the
// round falloff. Pure Surface logic, host testable, no JNI.
// ARCHITECTURE.md "B2 additions" §3.
#ifndef OPENCLIP_BRUSH_TIP_H
#define OPENCLIP_BRUSH_TIP_H

#include <cstdint>
#include <vector>

#include "openclip/brush.h"

namespace openclip {

class Surface;

struct BrushTip {
    int w = 0, h = 0;
    std::vector<std::uint8_t> alpha;  // coverage, row-major

    bool valid() const {
        return w > 0 && h > 0 &&
               alpha.size() == static_cast<std::size_t>(w) * h;
    }
    void clear() {
        w = 0;
        h = 0;
        alpha.clear();
    }
};

// Extract the ALPHA channel of a straight RGBA8 buffer (rgb ignored:
// a tip is a coverage mask). False + `out` untouched on a bad size or
// an all-zero tip (an empty stamp would silently paint nothing).
bool brushTipFromRgba(const std::uint8_t* rgba, int w, int h, BrushTip& out);

// Convenience overload over a premultiplied Surface (the PNG decoder
// output). Same rules.
bool brushTipFromSurface(const Surface& s, BrushTip& out);

// Stamp `tip` centred on the dab, scaled to the pressure-resolved dab
// diameter (width = 2*radius, height keeps the mask aspect), bilinear
// alpha sampling, source-over premultiplied. `alphaLock` skips
// transparent destination pixels (T5 §1 rule). Off-canvas pixels are
// clipped, the alpha invariant rgb <= a is preserved.
void paintTipDab(Surface& s, const Dab& d, const BrushParams& p,
                 const BrushTip& tip, bool alphaLock = false);

}  // namespace openclip

#endif
