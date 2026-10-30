// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip B2 — brush tip coverage mask + masked dab stamping.
#include "openclip/brush_tip.h"

#include <algorithm>
#include <cmath>

#include "openclip/surface.h"

namespace openclip {
namespace {

inline std::uint8_t qch(float v) {
    const long r = std::lround(v * 255.0f);
    return static_cast<std::uint8_t>(r < 0 ? 0 : (r > 255 ? 255 : r));
}

// Bilinear alpha sample at tip coords (u,v) in pixel units; outside the
// mask = 0 so the stamp edge stays clean.
float sampleAlpha(const BrushTip& t, float u, float v) {
    if (u < -0.5f || v < -0.5f ||
        u > static_cast<float>(t.w) - 0.5f ||
        v > static_cast<float>(t.h) - 0.5f)
        return 0.0f;
    const int x0 = static_cast<int>(std::floor(u));
    const int y0 = static_cast<int>(std::floor(v));
    const float fx = u - static_cast<float>(x0);
    const float fy = v - static_cast<float>(y0);
    auto at = [&](int x, int y) -> float {
        if (x < 0 || y < 0 || x >= t.w || y >= t.h) return 0.0f;
        return t.alpha[static_cast<std::size_t>(y) * t.w + x] / 255.0f;
    };
    const float a = at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx;
    const float b = at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx;
    return a * (1 - fy) + b * fy;
}

}  // namespace

bool brushTipFromRgba(const std::uint8_t* rgba, int w, int h, BrushTip& out) {
    if (!rgba || w <= 0 || h <= 0 || w > 1024 || h > 1024) return false;
    const std::size_t n = static_cast<std::size_t>(w) * h;
    BrushTip t;
    t.w = w;
    t.h = h;
    t.alpha.resize(n);
    bool any = false;
    for (std::size_t i = 0; i < n; ++i) {
        t.alpha[i] = rgba[i * 4 + 3];
        if (t.alpha[i] != 0) any = true;
    }
    if (!any) return false;  // an all-transparent tip paints nothing
    out = std::move(t);
    return true;
}

bool brushTipFromSurface(const Surface& s, BrushTip& out) {
    return brushTipFromRgba(s.bytes().data(), s.width(), s.height(), out);
}

void paintTipDab(Surface& s, const Dab& d, const BrushParams& p,
                 const BrushTip& tip, bool alphaLock) {
    (void)p;  // the mask owns the falloff: hardness only shapes round dabs
    if (!tip.valid() || d.opacity <= 0.0f || d.radius <= 0.0f) return;
    const float wpx = d.radius * 2.0f;
    const float hpx = wpx * static_cast<float>(tip.h) /
                      static_cast<float>(tip.w);
    const float x0f = d.x - wpx * 0.5f;
    const float y0f = d.y - hpx * 0.5f;
    const int x0 = static_cast<int>(std::floor(x0f));
    const int x1 = static_cast<int>(std::ceil(x0f + wpx));
    const int y0 = static_cast<int>(std::floor(y0f));
    const int y1 = static_cast<int>(std::ceil(y0f + hpx));
    const float sa = std::min(1.0f, d.opacity);
    const float fr = d.r / 255.0f, fg = d.g / 255.0f, fb = d.b / 255.0f;
    for (int y = y0; y <= y1; ++y) {
        if (y < 0 || y >= s.height()) continue;
        for (int x = x0; x <= x1; ++x) {
            if (x < 0 || x >= s.width()) continue;
            // Pixel-centre -> tip pixel coords (mask spans wpx x hpx).
            const float u = ((x + 0.5f) - x0f) / wpx * tip.w;
            const float v = ((y + 0.5f) - y0f) / hpx * tip.h;
            const float m = sampleAlpha(tip, u, v) * sa;
            if (m <= 1e-4f) continue;
            Pixel dst = s.get(x, y);
            if (alphaLock && dst.a == 0) continue;
            const float da = dst.a / 255.0f;
            const float oa = m + da * (1.0f - m);
            if (oa <= 0.0f) continue;
            Pixel o;
            o.a = qch(oa);
            o.r = qch(fr * m + (dst.r / 255.0f) * (1.0f - m));
            o.g = qch(fg * m + (dst.g / 255.0f) * (1.0f - m));
            o.b = qch(fb * m + (dst.b / 255.0f) * (1.0f - m));
            if (o.r > o.a) o.r = o.a;
            if (o.g > o.a) o.g = o.a;
            if (o.b > o.a) o.b = o.a;
            s.set(x, y, o);
        }
    }
}

}  // namespace openclip
