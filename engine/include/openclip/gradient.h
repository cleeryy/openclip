// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip T1 engine — gradient paint tool (linear/radial/free).
// Clean-room, from scratch (no mesh, no pigment model: RGB
// interpolation, documented). Paints pixels (opaque stops); this is
// NOT the gradientMap filter/adjustment (which recolors by luma).
#ifndef OPENCLIP_GRADIENT_H
#define OPENCLIP_GRADIENT_H

#include <cstdint>
#include <string>
#include <utility>
#include <vector>

namespace openclip {

class Surface;

struct GradientStop {
    std::uint8_t r = 0, g = 0, b = 0;
    float pos = 0.0f;  // 0..1
};

struct GradientSpec {
    std::string kind = "linear";  // "linear"|"radial"|"free"
    float x0 = 0, y0 = 0, x1 = 1, y1 = 0;  // linear: p0->p1;
    // radial: center + circumference point (zero radius = error)
    std::vector<GradientStop> stops;  // 2..8, sorted by pos (engine-side)
    // free: pts[i] anchors stops[i] (same size, required in free mode)
    std::vector<std::pair<float, float>> pts;
    bool dither = true;  // deterministic LCG +/-1 LSB (reproducible)
};

// Paint the gradient on s (source-over, opaque stops). Interpolation:
// linear = scalar projection onto p0->p1; radial = center distance /
// radius; free = normalized inverse-distance weights
// w_i = 1/(d_i^2+eps) over the stop anchors (bounded: <= 8 stops).
// The stop ramp is RGB-linear between sorted stops. Dithering adds a
// deterministic +/-1 LSB (fixed LCG seed every call, same as addNoise).
// mask (nullptr = no selection) constrains the paint. Returns false +
// surface byte-identical on invalid spec (unknown kind, < 2 stops,
// > 8 stops, positions outside [0,1], free mode with pts/stops size
// mismatch, zero-length linear segment or zero radial radius) or an
// empty surface.
// T5 §1 verrou alpha: like floodFillSeeded, transparent pixels are
// skipped when alphaLock is true (default false = legacy path).
bool paintGradient(Surface& s, const GradientSpec& spec,
                   const std::uint8_t* mask, int maskStride,
                   std::string& error, bool alphaLock = false);

}  // namespace openclip

#endif
