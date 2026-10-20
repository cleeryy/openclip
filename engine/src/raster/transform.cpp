// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip V+2 — selection transform implementation (clean-room).
#include "openclip/transform.h"

#include "openclip/surface.h"

#include <cmath>

namespace openclip {

bool selectionBBox(const std::vector<std::uint8_t>& mask, int w, int h,
                   int& x0, int& y0, int& x1, int& y1) {
    if (w <= 0 || h <= 0 ||
        mask.size() != static_cast<std::size_t>(w) * h)
        return false;
    x0 = w;
    y0 = h;
    x1 = -1;
    y1 = -1;
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x)
            if (mask[static_cast<std::size_t>(y) * w + x] > 0) {
                if (x < x0) x0 = x;
                if (x > x1) x1 = x;
                if (y < y0) y0 = y;
                if (y > y1) y1 = y;
            }
    return x1 >= x0;
}

namespace {

inline Pixel sampleNearest(const Surface& s, int x, int y) {
    if (x < 0) x = 0;
    if (y < 0) y = 0;
    if (x >= s.width()) x = s.width() - 1;
    if (y >= s.height()) y = s.height() - 1;
    return s.get(x, y);
}

inline Pixel sampleBilinear(const Surface& s, double x, double y) {
    const int w = s.width(), h = s.height();
    int x0 = static_cast<int>(std::floor(x));
    int y0 = static_cast<int>(std::floor(y));
    const double fx = x - x0, fy = y - y0;
    // Clamp the 2x2 neighborhood (clamp-to-edge sampling, M3 rule).
    int x1 = x0 + 1, y1 = y0 + 1;
    if (x0 < 0) x0 = 0;
    if (y0 < 0) y0 = 0;
    if (x1 < 0) x1 = 0;
    if (y1 < 0) y1 = 0;
    if (x0 >= w) x0 = w - 1;
    if (y0 >= h) y0 = h - 1;
    if (x1 >= w) x1 = w - 1;
    if (y1 >= h) y1 = h - 1;
    const Pixel a = s.get(x0, y0), b = s.get(x1, y0);
    const Pixel c = s.get(x0, y1), d = s.get(x1, y1);
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
}

// Premultiplied source-over, in place (src drawn over dst).
inline void overPixel(const Pixel& src, Pixel& dst) {
    if (src.a == 0) return;
    if (src.a == 255) {
        dst = src;
        return;
    }
    const float sa = src.a / 255.0f, inv = 1.0f - sa;
    dst.r = static_cast<std::uint8_t>(src.r + dst.r * inv + 0.5f);
    dst.g = static_cast<std::uint8_t>(src.g + dst.g * inv + 0.5f);
    dst.b = static_cast<std::uint8_t>(src.b + dst.b * inv + 0.5f);
    dst.a = static_cast<std::uint8_t>(
        src.a + dst.a * inv + 0.5f > 255 ? 255 : src.a + dst.a * inv + 0.5f);
}

// Bilinear interpolation of the 3x3 mesh offset field at (x, y) inside
// the bbox [bx0,bx1]x[by0,by1]. Node (i,j) sits at the grid position.
inline void meshOffsetAt(const double mesh[9][2], int bx0, int by0, int bx1,
                         int by1, double x, double y, double& ox,
                         double& oy) {
    const double gx = (bx1 == bx0)
                          ? 0.0
                          : (x - bx0) * 2.0 / (bx1 - bx0);  // 0..2
    const double gy = (by1 == by0)
                          ? 0.0
                          : (y - by0) * 2.0 / (by1 - by0);
    double cx = gx < 0 ? 0 : (gx > 2 ? 2 : gx);
    double cy = gy < 0 ? 0 : (gy > 2 ? 2 : gy);
    int ix = static_cast<int>(cx);
    int iy = static_cast<int>(cy);
    if (ix > 1) ix = 1;
    if (iy > 1) iy = 1;
    const double fx = cx - ix, fy = cy - iy;
    const double* n00 = mesh[iy * 3 + ix];
    const double* n10 = mesh[iy * 3 + ix + 1];
    const double* n01 = mesh[(iy + 1) * 3 + ix];
    const double* n11 = mesh[(iy + 1) * 3 + ix + 1];
    ox = n00[0] * (1 - fx) * (1 - fy) + n10[0] * fx * (1 - fy) +
         n01[0] * (1 - fx) * fy + n11[0] * fx * fy;
    oy = n00[1] * (1 - fx) * (1 - fy) + n10[1] * fx * (1 - fy) +
         n01[1] * (1 - fx) * fy + n11[1] * fx * fy;
}

}  // namespace

bool blitSelection(Surface& dst, const Surface& src,
                   const std::vector<std::uint8_t>& mask,
                   const TransformSpec& spec) {
    const int w = src.width(), h = src.height();
    if (w <= 0 || h <= 0 ||
        mask.size() != static_cast<std::size_t>(w) * h)
        return false;
    if (spec.mode != "move" && spec.mode != "free" && spec.mode != "mesh")
        return false;
    int bx0, by0, bx1, by1;
    if (!selectionBBox(mask, w, h, bx0, by0, bx1, by1)) return false;

    dst.resize(w, h);
    dst.bytes() = src.bytes();
    // Clear the masked area in place (premultiplied-safe: bytes to 0).
    for (int y = by0; y <= by1; ++y)
        for (int x = bx0; x <= bx1; ++x) {
            const std::size_t i = static_cast<std::size_t>(y) * w + x;
            if (mask[i] > 0) {
                std::uint8_t* p = dst.bytes().data() + i * 4;
                p[0] = p[1] = p[2] = p[3] = 0;
            }
        }

    // Forward-map the bbox corners to bound the output region.
    const bool isMove = (spec.mode == "move");
    const double sc = (spec.mode == "free" && spec.scale > 0.0)
                          ? spec.scale
                          : 1.0;
    const double ang = (spec.mode == "free") ? spec.angleDeg * M_PI / 180.0
                                             : 0.0;
    const double ca = std::cos(ang), sa = std::sin(ang);
    const double cx = (bx0 + bx1 + 1) * 0.5, cy = (by0 + by1 + 1) * 0.5;
    auto fwd = [&](double x, double y, double& ox, double& oy) {
        if (isMove) {
            ox = x + spec.dx;
            oy = y + spec.dy;
        } else if (spec.mode == "free") {
            const double rx = (x - cx) * sc, ry = (y - cy) * sc;
            ox = cx + rx * ca - ry * sa + spec.dx;
            oy = cy + rx * sa + ry * ca + spec.dy;
        } else {
            double mx, my;
            meshOffsetAt(spec.mesh, bx0, by0, bx1, by1, x, y, mx, my);
            ox = x + mx + spec.dx;
            oy = y + my + spec.dy;
        }
    };
    double ox0 = 1e30, oy0 = 1e30, ox1 = -1e30, oy1 = -1e30;
    const double corners[4][2] = {
        {(double)bx0, (double)by0},
        {(double)bx1 + 1, (double)by0},
        {(double)bx0, (double)by1 + 1},
        {(double)bx1 + 1, (double)by1 + 1},
    };
    for (auto& c : corners) {
        double ox, oy;
        fwd(c[0], c[1], ox, oy);
        if (ox < ox0) ox0 = ox;
        if (oy < oy0) oy0 = oy;
        if (ox > ox1) ox1 = ox;
        if (oy > oy1) oy1 = oy;
    }
    int rx0 = static_cast<int>(std::floor(ox0)) - 1;
    int ry0 = static_cast<int>(std::floor(oy0)) - 1;
    int rx1 = static_cast<int>(std::ceil(ox1)) + 1;
    int ry1 = static_cast<int>(std::ceil(oy1)) + 1;
    if (rx0 < 0) rx0 = 0;
    if (ry0 < 0) ry0 = 0;
    if (rx1 >= w) rx1 = w - 1;
    if (ry1 >= h) ry1 = h - 1;

    auto maskAt = [&](int x, int y) -> bool {
        if (x < 0 || y < 0 || x >= w || y >= h) return false;
        return mask[static_cast<std::size_t>(y) * w + x] > 0;
    };

    for (int y = ry0; y <= ry1; ++y) {
        for (int x = rx0; x <= rx1; ++x) {
            // Inverse map: dst pixel center -> src coords.
            double sx, sy;
            if (isMove) {
                sx = x + 0.5 - spec.dx;
                sy = y + 0.5 - spec.dy;
            } else if (spec.mode == "free") {
                const double px = x + 0.5 - cx - spec.dx;
                const double py = y + 0.5 - cy - spec.dy;
                const double ux = (px * ca + py * sa) / sc;
                const double uy = (-px * sa + py * ca) / sc;
                sx = ux + cx;
                sy = uy + cy;
            } else {
                // Fixed-point iteration on s = p - O(s) - d.
                sx = x + 0.5 - spec.dx;
                sy = y + 0.5 - spec.dy;
                for (int it = 0; it < 3; ++it) {
                    double mx, my;
                    meshOffsetAt(spec.mesh, bx0, by0, bx1, by1, sx, sy, mx,
                                 my);
                    sx = x + 0.5 - spec.dx - mx;
                    sy = y + 0.5 - spec.dy - my;
                }
            }
            const int ix = static_cast<int>(std::floor(sx));
            const int iy = static_cast<int>(std::floor(sy));
            if (!maskAt(ix, iy)) continue;
            Pixel ps = isMove ? sampleNearest(src, ix, iy)
                              : sampleBilinear(src, sx - 0.5, sy - 0.5);
            Pixel pd = dst.get(x, y);
            overPixel(ps, pd);
            dst.set(x, y, pd);
        }
    }
    return true;
}

}  // namespace openclip
