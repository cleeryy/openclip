// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip T4 — puppet warp implementation (clean-room).
#include "openclip/puppet.h"

#include "openclip/surface.h"

#include <cmath>

namespace openclip {

bool puppetWarp(Surface& dst, const Surface& src,
                const std::vector<PuppetPin>& pins, double radius) {
    const int w = src.width(), h = src.height();
    if (w <= 0 || h <= 0) return false;
    if (pins.size() > 64) return false;
    double rad = radius;
    if (!(rad > 0.0)) {
        // Default: 25% of the canvas diagonal (spec §1.3).
        rad = 0.25 * std::sqrt(static_cast<double>(w * w + h * h));
        if (!(rad > 0.0)) return false;
    }
    dst.resize(w, h);
    dst.bytes() = src.bytes();
    if (pins.empty()) return true;  // byte-identical no-op
    // Keep off-canvas anchors out (spec: ignored, honest).
    std::vector<PuppetPin> live;
    for (const auto& p : pins) {
        if (!(p.x >= 0 && p.x < w && p.y >= 0 && p.y < h)) continue;
        if (!(std::isfinite(p.dx) && std::isfinite(p.dy))) continue;
        live.push_back(p);
    }
    if (live.empty()) return true;
    auto sample = [&](double x, double y) -> Pixel {
        int x0 = static_cast<int>(std::floor(x));
        int y0 = static_cast<int>(std::floor(y));
        const double fx = x - x0, fy = y - y0;
        int x1 = x0 + 1, y1 = y0 + 1;
        if (x0 < 0) x0 = 0;
        if (y0 < 0) y0 = 0;
        if (x1 < 0) x1 = 0;
        if (y1 < 0) y1 = 0;
        if (x0 >= w) x0 = w - 1;
        if (y0 >= h) y0 = h - 1;
        if (x1 >= w) x1 = w - 1;
        if (y1 >= h) y1 = h - 1;
        const Pixel a = src.get(x0, y0), b = src.get(x1, y0);
        const Pixel c = src.get(x0, y1), d = src.get(x1, y1);
        Pixel o;
        const double w00 = (1 - fx) * (1 - fy), w10 = fx * (1 - fy);
        const double w01 = (1 - fx) * fy, w11 = fx * fy;
        o.r = static_cast<std::uint8_t>(a.r * w00 + b.r * w10 + c.r * w01 +
                                        d.r * w11 + 0.5);
        o.g = static_cast<std::uint8_t>(a.g * w00 + b.g * w10 + c.g * w01 +
                                        d.g * w11 + 0.5);
        o.b = static_cast<std::uint8_t>(a.b * w00 + b.b * w10 + c.b * w01 +
                                        d.b * w11 + 0.5);
        o.a = static_cast<std::uint8_t>(a.a * w00 + b.a * w10 + c.a * w01 +
                                        d.a * w11 + 0.5);
        return o;
    };
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const double vx = x + 0.5, vy = y + 0.5;
            double sw = 0, ox = 0, oy = 0;
            for (const auto& p : live) {
                const double ddx = vx - (p.x + 0.5);
                const double ddy = vy - (p.y + 0.5);
                const double dist = std::sqrt(ddx * ddx + ddy * ddy);
                if (dist >= rad) continue;
                // Cosine falloff: 1 at the pin, 0 at `rad`.
                const double f =
                    (std::cos(M_PI * dist / rad) + 1.0) * 0.5;
                sw += f;
                ox += f * p.dx;
                oy += f * p.dy;
            }
            if (sw <= 0.0) continue;  // far field: intact
            dst.set(x, y, sample(vx - ox / sw - 0.5, vy - oy / sw - 0.5));
        }
    }
    return true;
}

}  // namespace openclip
