// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip T1 engine — figure stamps (see shapes.h).
#include "openclip/shapes.h"

#include <cmath>
#include <cstdint>
#include <string>
#include <vector>

#include "openclip/surface.h"

namespace openclip {
namespace {

// Signed distance to the shape edge, in px (< 0 inside, > 0 outside).
// Rect uses the rounded-box SDF; ellipse the scaled-circle
// approximation; polygon the ray-crossing test with an edge-distance
// fallback (exact enough for a centered stroke band).
double shapeSdf(const ShapeSpec& sp, double x, double y) {
    const double x0 = sp.x0, y0 = sp.y0, x1 = sp.x1, y1 = sp.y1;
    if (sp.kind == "rect") {
        const double cx = (x0 + x1) * 0.5, cy = (y0 + y1) * 0.5;
        const double hx = std::fabs(x1 - x0) * 0.5;
        const double hy = std::fabs(y1 - y0) * 0.5;
        double r = sp.corner;
        const double rMax = (hx < hy ? hx : hy);
        if (r < 0.0) r = 0.0;
        if (r > rMax) r = rMax;
        const double qx = std::fabs(x - cx) - (hx - r);
        const double qy = std::fabs(y - cy) - (hy - r);
        const double ax = qx > 0.0 ? qx : 0.0;
        const double ay = qy > 0.0 ? qy : 0.0;
        const double mx = qx > qy ? qx : qy;
        return std::hypot(ax, ay) + (mx < 0.0 ? mx : 0.0) - r;
    }
    if (sp.kind == "ellipse") {
        const double cx = (x0 + x1) * 0.5, cy = (y0 + y1) * 0.5;
        const double rx = std::fabs(x1 - x0) * 0.5;
        const double ry = std::fabs(y1 - y0) * 0.5;
        if (rx < 1e-9 || ry < 1e-9) return 1e9;
        // Scaled-circle approximation (exact on axes, close elsewhere;
        // the stroke band stays within +/-1px of truth).
        const double k = (rx + ry) * 0.5;
        return (std::hypot((x - cx) * (k / rx), (y - cy) * (k / ry)) - k);
    }
    // Polygon: regular, `sides` vertices, rotated by `rotation`
    // degrees around the frame center, inscribed in the frame's
    // smaller half-extent (even-odd containment).
    const double cx = (x0 + x1) * 0.5, cy = (y0 + y1) * 0.5;
    const double hx = std::fabs(x1 - x0) * 0.5;
    const double hy = std::fabs(y1 - y0) * 0.5;
    const double rad = hx < hy ? hx : hy;
    const double rot = sp.rotation * 3.141592653589793 / 180.0;
    bool inside = false;
    double edgeMin = 1e9;
    const int n = sp.sides;
    for (int k = 0; k < n; ++k) {
        const double a0 = rot + 2.0 * 3.141592653589793 * k / n;
        const double a1 = rot + 2.0 * 3.141592653589793 * (k + 1) / n;
        const double ax = cx + rad * std::cos(a0);
        const double ay = cy + rad * std::sin(a0);
        const double bx = cx + rad * std::cos(a1);
        const double by = cy + rad * std::sin(a1);
        if ((ay > y) != (by > y)) {
            const double xi = ax + (y - ay) / (by - ay) * (bx - ax);
            if (x < xi) inside = !inside;
        }
        // Distance point->segment (for the stroke band + fringe SDF).
        const double dx = bx - ax, dy = by - ay;
        const double l2 = dx * dx + dy * dy;
        double t = (l2 > 1e-12) ? ((x - ax) * dx + (y - ay) * dy) / l2 : 0.0;
        if (t < 0.0) t = 0.0;
        if (t > 1.0) t = 1.0;
        const double ex = ax + t * dx - x, ey = ay + t * dy - y;
        const double d = std::hypot(ex, ey);
        if (d < edgeMin) edgeMin = d;
    }
    return inside ? -edgeMin : edgeMin;
}

inline std::uint8_t clamp255(double v) {
    const long r = std::lround(v);
    return static_cast<std::uint8_t>(r < 0 ? 0 : (r > 255 ? 255 : r));
}

}  // namespace

bool stampShape(Surface& s, const ShapeSpec& spec,
                const std::uint8_t* mask, int maskStride,
                std::string& error) {
    error.clear();
    const int w = s.width(), h = s.height();
    if (w <= 0 || h <= 0) {
        error = "stampShape: empty surface";
        return false;
    }
    if (spec.kind != "rect" && spec.kind != "ellipse" &&
        spec.kind != "polygon") {
        error = "stampShape: kind must be rect|ellipse|polygon";
        return false;
    }
    const double fw = std::fabs(spec.x1 - spec.x0);
    const double fh = std::fabs(spec.y1 - spec.y0);
    if (!(fw > 0.0) || !(fh > 0.0)) {
        error = "stampShape: degenerate frame";
        return false;
    }
    if (spec.kind == "polygon" &&
        (spec.sides < 3 || spec.sides > 12)) {
        error = "stampShape: polygon sides must be in [3,12]";
        return false;
    }
    if (spec.corner < 0.0f) {
        error = "stampShape: corner must be >= 0";
        return false;
    }
    if (!spec.fillOn && !spec.strokeOn) {
        error = "stampShape: fill and stroke are both off";
        return false;
    }
    if (spec.strokeOn &&
        (!(spec.strokeW >= 1.0f) || spec.strokeW > 64.0f)) {
        error = "stampShape: strokeW must be in [1,64]";
        return false;
    }
    const double half = spec.strokeOn ? spec.strokeW * 0.5 : 0.0;
    // Integer bounds of the frame, padded by the stroke half-width +
    // one AA pixel.
    const double lo_x = spec.x0 < spec.x1 ? spec.x0 : spec.x1;
    const double hi_x = spec.x0 < spec.x1 ? spec.x1 : spec.x0;
    const double lo_y = spec.y0 < spec.y1 ? spec.y0 : spec.y1;
    const double hi_y = spec.y0 < spec.y1 ? spec.y1 : spec.y0;
    const int bx0 = std::max(0, static_cast<int>(std::floor(lo_x - half - 1)));
    const int by0 = std::max(0, static_cast<int>(std::floor(lo_y - half - 1)));
    const int bx1 = std::min(w - 1, static_cast<int>(std::ceil(hi_x + half + 1)));
    const int by1 = std::min(h - 1, static_cast<int>(std::ceil(hi_y + half + 1)));
    std::vector<std::uint8_t>& bytes = s.bytes();
    constexpr int kAA = 4;  // 4x4 coverage supersampling
    for (int y = by0; y <= by1; ++y) {
        for (int x = bx0; x <= bx1; ++x) {
            if (mask &&
                mask[static_cast<std::size_t>(y) * maskStride + x] == 0)
                continue;
            int fillHits = 0, strokeHits = 0;
            for (int sy = 0; sy < kAA; ++sy)
                for (int sx = 0; sx < kAA; ++sx) {
                    const double px = x + (sx + 0.5) / kAA - 0.5;
                    const double py = y + (sy + 0.5) / kAA - 0.5;
                    const double d = shapeSdf(spec, px, py);
                    if (spec.fillOn && d <= 0.0) ++fillHits;
                    if (spec.strokeOn && std::fabs(d) <= half)
                        ++strokeHits;
                }
            const double fillCov = fillHits / double(kAA * kAA);
            const double strokeCov = strokeHits / double(kAA * kAA);
            if (fillCov <= 0.0 && strokeCov <= 0.0) continue;
            // Stroke paints over fill (source-over each, opaque ink).
            const std::size_t i =
                (static_cast<std::size_t>(y) * w + x) * 4;
            double dr = bytes[i], dg = bytes[i + 1], db = bytes[i + 2];
            double da = bytes[i + 3] / 255.0;
            if (fillCov > 0.0) {
                const double sa = fillCov;
                dr = dr * (1.0 - sa) + spec.fillR * sa;
                dg = dg * (1.0 - sa) + spec.fillG * sa;
                db = db * (1.0 - sa) + spec.fillB * sa;
                da = sa + da * (1.0 - sa);
            }
            if (strokeCov > 0.0) {
                const double sa = strokeCov;
                dr = dr * (1.0 - sa) + spec.strokeR * sa;
                dg = dg * (1.0 - sa) + spec.strokeG * sa;
                db = db * (1.0 - sa) + spec.strokeB * sa;
                da = sa + da * (1.0 - sa);
            }
            bytes[i] = clamp255(dr);
            bytes[i + 1] = clamp255(dg);
            bytes[i + 2] = clamp255(db);
            bytes[i + 3] = clamp255(da * 255.0);
        }
    }
    return true;
}

}  // namespace openclip
