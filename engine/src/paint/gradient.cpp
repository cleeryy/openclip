// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip T1 engine — gradient paint tool (see gradient.h).
#include "openclip/gradient.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <string>
#include <vector>

#include "openclip/surface.h"

namespace openclip {
namespace {

inline std::uint8_t clamp255(double v) {
    const long r = std::lround(v);
    return static_cast<std::uint8_t>(r < 0 ? 0 : (r > 255 ? 255 : r));
}

// Ramp one scalar t in [0,1] through the sorted stops (RGB-linear).
void rampAt(const std::vector<GradientStop>& stops, double t, double& r,
            double& g, double& b) {
    double tc = t < 0.0 ? 0.0 : (t > 1.0 ? 1.0 : t);
    const GradientStop* lo = &stops.front();
    const GradientStop* hi = &stops.back();
    for (const auto& s : stops) {
        if (s.pos <= tc) lo = &s;
        if (s.pos >= tc) {
            hi = &s;
            break;
        }
    }
    const double span = hi->pos - lo->pos;
    const double k = (span > 1e-9) ? (tc - lo->pos) / span : 0.0;
    r = lo->r + (hi->r - lo->r) * k;
    g = lo->g + (hi->g - lo->g) * k;
    b = lo->b + (hi->b - lo->b) * k;
}

bool validSpec(const GradientSpec& spec, std::string& error) {
    if (spec.kind != "linear" && spec.kind != "radial" &&
        spec.kind != "free") {
        error = "paintGradient: kind must be linear|radial|free";
        return false;
    }
    if (spec.stops.size() < 2 || spec.stops.size() > 8) {
        error = "paintGradient: need 2..8 stops";
        return false;
    }
    for (const auto& st : spec.stops) {
        if (!(st.pos >= 0.0f) || !(st.pos <= 1.0f)) {
            error = "paintGradient: stop pos must be in [0,1]";
            return false;
        }
    }
    if (spec.kind == "free" && spec.pts.size() != spec.stops.size()) {
        error = "paintGradient: free mode needs one anchor per stop";
        return false;
    }
    if (spec.kind == "linear") {
        const double dx = spec.x1 - spec.x0, dy = spec.y1 - spec.y0;
        if (dx * dx + dy * dy < 1e-9) {
            error = "paintGradient: linear segment has zero length";
            return false;
        }
    }
    if (spec.kind == "radial") {
        const double dx = spec.x1 - spec.x0, dy = spec.y1 - spec.y0;
        if (dx * dx + dy * dy < 1e-9) {
            error = "paintGradient: radial radius is zero";
            return false;
        }
    }
    return true;
}

}  // namespace

bool paintGradient(Surface& s, const GradientSpec& spec,
                   const std::uint8_t* mask, int maskStride,
                   std::string& error, bool alphaLock) {
    error.clear();
    const int w = s.width(), h = s.height();
    if (w <= 0 || h <= 0) {
        error = "paintGradient: empty surface";
        return false;
    }
    if (!validSpec(spec, error)) return false;
    // Sort a working copy by pos (callers may pass unsorted stops).
    std::vector<GradientStop> stops = spec.stops;
    std::sort(stops.begin(), stops.end(),
              [](const GradientStop& a, const GradientStop& b) {
                  return a.pos < b.pos;
              });
    const double dx = spec.x1 - spec.x0, dy = spec.y1 - spec.y0;
    const double len2 = dx * dx + dy * dy;
    std::vector<std::uint8_t>& bytes = s.bytes();
    // Deterministic LCG, fixed seed every call (reproducible, same
    // generator shape as the addNoise filter).
    std::uint32_t lcg = 0;
    auto ditherBit = [&]() -> int {
        lcg = lcg * 1664525u + 1013904223u;
        return static_cast<int>((lcg >> 16) % 3) - 1;  // -1|0|+1
    };
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            if (mask &&
                mask[static_cast<std::size_t>(y) * maskStride + x] == 0)
                continue;
            // T5 §1 verrou alpha (see fill.h).
            if (alphaLock &&
                bytes[(static_cast<std::size_t>(y) * w + x) * 4 + 3] == 0)
                continue;
            double t = 0.0;
            if (spec.kind == "linear") {
                t = ((x - spec.x0) * dx + (y - spec.y0) * dy) / len2;
            } else if (spec.kind == "radial") {
                const double rad = std::sqrt(len2);
                const double d = std::hypot(x - spec.x0, y - spec.y0);
                t = d / rad;
            } else {
                // Free: normalized inverse-distance weights.
                double wsum = 0.0;
                double accR = 0.0, accG = 0.0, accB = 0.0;
                for (std::size_t k = 0; k < stops.size(); ++k) {
                    const double ddx = x - spec.pts[k].first;
                    const double ddy = y - spec.pts[k].second;
                    const double wgt = 1.0 / (ddx * ddx + ddy * ddy + 1.0);
                    wsum += wgt;
                    accR += wgt * stops[k].r;
                    accG += wgt * stops[k].g;
                    accB += wgt * stops[k].b;
                }
                const std::size_t i =
                    (static_cast<std::size_t>(y) * w + x) * 4;
                double rr = accR / wsum, gg = accG / wsum, bb = accB / wsum;
                if (spec.dither) {
                    rr += ditherBit();
                    gg += ditherBit();
                    bb += ditherBit();
                }
                // Opaque stops: replace rgb, raise alpha BY DESIGN.
                bytes[i] = clamp255(rr);
                bytes[i + 1] = clamp255(gg);
                bytes[i + 2] = clamp255(bb);
                bytes[i + 3] = 255;
                continue;
            }
            double rr = 0, gg = 0, bb = 0;
            rampAt(stops, t, rr, gg, bb);
            if (spec.dither) {
                rr += ditherBit();
                gg += ditherBit();
                bb += ditherBit();
            }
            const std::size_t i = (static_cast<std::size_t>(y) * w + x) * 4;
            bytes[i] = clamp255(rr);
            bytes[i + 1] = clamp255(gg);
            bytes[i + 2] = clamp255(bb);
            bytes[i + 3] = 255;
        }
    }
    return true;
}

}  // namespace openclip
