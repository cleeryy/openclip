// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip T4 — perspective/skew/bulge implementation (clean-room).
#include "openclip/warp.h"

#include "openclip/surface.h"
#include "openclip/transform.h"  // selectionBBox (shared mask geometry)

#include <cmath>

namespace openclip {

namespace {

inline Pixel sampleBilinearW(const Surface& s, double x, double y) {
    const int w = s.width(), h = s.height();
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
inline void overPixelW(const Pixel& src, Pixel& dst) {
    if (src.a == 0) return;
    if (src.a == 255) {
        dst = src;
        return;
    }
    const float sa = src.a / 255.0f, inv = 1.0f - sa;
    dst.r = static_cast<std::uint8_t>(src.r + dst.r * inv + 0.5f);
    dst.g = static_cast<std::uint8_t>(src.g + dst.g * inv + 0.5f);
    dst.b = static_cast<std::uint8_t>(src.b + dst.b * inv + 0.5f);
    const float na = src.a + dst.a * inv;
    dst.a = static_cast<std::uint8_t>(na > 255 ? 255 : na + 0.5f);
}

// 3x3 inverse (row-major). False on singular matrix.
bool invert3(const double m[9], double inv[9]) {
    const double det = m[0] * (m[4] * m[8] - m[5] * m[7]) -
                       m[1] * (m[3] * m[8] - m[5] * m[6]) +
                       m[2] * (m[3] * m[7] - m[4] * m[6]);
    if (!(std::fabs(det) > 1e-9)) return false;
    const double id = 1.0 / det;
    inv[0] = (m[4] * m[8] - m[5] * m[7]) * id;
    inv[1] = (m[2] * m[7] - m[1] * m[8]) * id;
    inv[2] = (m[1] * m[5] - m[2] * m[4]) * id;
    inv[3] = (m[5] * m[6] - m[3] * m[8]) * id;
    inv[4] = (m[0] * m[8] - m[2] * m[6]) * id;
    inv[5] = (m[2] * m[3] - m[0] * m[5]) * id;
    inv[6] = (m[3] * m[7] - m[4] * m[6]) * id;
    inv[7] = (m[1] * m[6] - m[0] * m[7]) * id;
    inv[8] = (m[0] * m[4] - m[1] * m[3]) * id;
    return true;
}

// Homography mapping quad q[4] (clockwise from top-left) to the unit
// square, solved from the 4 point correspondences (from scratch).
bool quadToUnit(const double q[4][2], double h[9]) {
    // Solve Ah = b for h (8 unknowns, h[8] = 1) via Gaussian elim.
    double a[8][9] = {};
    const double u[4] = {0, 1, 1, 0}, v[4] = {0, 0, 1, 1};
    for (int k = 0; k < 4; ++k) {
        const double x = q[k][0], y = q[k][1];
        a[2 * k][0] = x;
        a[2 * k][1] = y;
        a[2 * k][2] = 1;
        a[2 * k][6] = -u[k] * x;
        a[2 * k][7] = -u[k] * y;
        a[2 * k][8] = u[k];
        a[2 * k + 1][3] = x;
        a[2 * k + 1][4] = y;
        a[2 * k + 1][5] = 1;
        a[2 * k + 1][6] = -v[k] * x;
        a[2 * k + 1][7] = -v[k] * y;
        a[2 * k + 1][8] = v[k];
    }
    for (int col = 0; col < 8; ++col) {
        int piv = col;
        for (int r = col + 1; r < 8; ++r)
            if (std::fabs(a[r][col]) > std::fabs(a[piv][col])) piv = r;
        if (!(std::fabs(a[piv][col]) > 1e-9)) return false;
        if (piv != col)
            for (int c = col; c < 9; ++c) {
                const double t = a[col][c];
                a[col][c] = a[piv][c];
                a[piv][c] = t;
            }
        for (int r = 0; r < 8; ++r) {
            if (r == col) continue;
            const double f = a[r][col] / a[col][col];
            for (int c = col; c < 9; ++c) a[r][c] -= f * a[col][c];
        }
    }
    double hh[8];
    for (int i = 0; i < 8; ++i) {
        if (!(std::fabs(a[i][i]) > 1e-9)) return false;
        hh[i] = a[i][8] / a[i][i];
    }
    h[0] = hh[0];
    h[1] = hh[1];
    h[2] = hh[2];
    h[3] = hh[3];
    h[4] = hh[4];
    h[5] = hh[5];
    h[6] = hh[6];
    h[7] = hh[7];
    h[8] = 1.0;
    return true;
}

}  // namespace

bool blitWarp(Surface& dst, const Surface& src,
              const std::vector<std::uint8_t>& mask, const WarpSpec& spec) {
    const int w = src.width(), h = src.height();
    if (w <= 0 || h <= 0 ||
        mask.size() != static_cast<std::size_t>(w) * h)
        return false;
    if (spec.mode != "persp" && spec.mode != "skew" && spec.mode != "bulge")
        return false;
    int bx0, by0, bx1, by1;
    if (!selectionBBox(mask, w, h, bx0, by0, bx1, by1)) return false;
    const double cx = (bx0 + bx1 + 1) * 0.5, cy = (by0 + by1 + 1) * 0.5;
    const double bw = bx1 - bx0 + 1.0, bh = by1 - by0 + 1.0;

    // Mode-specific setup: output bound + inverse mapper.
    double ox0 = 1e30, oy0 = 1e30, ox1 = -1e30, oy1 = -1e30;
    double H[9] = {0};  // persp: output -> unit square of the quad
    double shx = 0, shy = 0, det = 1;  // skew: shear factors + determinant
    double bulgeR = 1;                 // bulge: influence radius px
    if (spec.mode == "persp") {
        if (!spec.quadSet) return false;
        double qtu[9];
        if (!quadToUnit(spec.quad, qtu)) return false;  // degenerate
        // Compose: unit -> bbox rect (output px -> unit -> bbox px).
        // H maps output px to quad-unit; bbox maps unit to src px.
        for (int i = 0; i < 9; ++i) H[i] = qtu[i];
        for (int k = 0; k < 4; ++k) {
            const double qx = spec.quad[k][0], qy = spec.quad[k][1];
            if (qx < ox0) ox0 = qx;
            if (qy < oy0) oy0 = qy;
            if (qx > ox1) ox1 = qx;
            if (qy > oy1) oy1 = qy;
        }
    } else if (spec.mode == "skew") {
        double ax = spec.ax, ay = spec.ay;
        if (!(ax >= -60.0) || ax > 60.0 || !(ay >= -60.0) || ay > 60.0) {
            // Clamp out-of-range shear (spec: ±60 clampés).
            if (ax < -60.0) ax = -60.0;
            if (ax > 60.0) ax = 60.0;
            if (ay < -60.0) ay = -60.0;
            if (ay > 60.0) ay = 60.0;
        }
        shx = std::tan(ax * M_PI / 180.0);
        shy = std::tan(ay * M_PI / 180.0);
        det = 1.0 - shx * shy;
        if (!(std::fabs(det) > 1e-9)) return false;
        const double corners[4][2] = {
            {(double)bx0, (double)by0},
            {(double)bx1 + 1, (double)by0},
            {(double)bx0, (double)by1 + 1},
            {(double)bx1 + 1, (double)by1 + 1},
        };
        for (auto& c : corners) {
            const double ox = c[0] + shx * (c[1] - cy);
            const double oy = c[1] + shy * (c[0] - cx);
            if (ox < ox0) ox0 = ox;
            if (oy < oy0) oy0 = oy;
            if (ox > ox1) ox1 = ox;
            if (oy > oy1) oy1 = oy;
        }
    } else {
        if (!(spec.amount >= -1.0) || spec.amount > 1.0) return false;
        double radius = spec.radius;
        if (!(radius >= 0.1) || radius > 1.0) {
            // Clamp into range (documented).
            if (radius < 0.1) radius = 0.1;
            if (radius > 1.0) radius = 1.0;
        }
        const double half = std::sqrt((bw * 0.5) * (bw * 0.5) +
                                      (bh * 0.5) * (bh * 0.5));
        bulgeR = radius * half;
        if (!(bulgeR > 0.0)) return false;
        const double grow = 1.0 + std::fabs(spec.amount);
        ox0 = cx - (bw * 0.5) * grow - 2;
        oy0 = cy - (bh * 0.5) * grow - 2;
        ox1 = cx + (bw * 0.5) * grow + 2;
        oy1 = cy + (bh * 0.5) * grow + 2;
    }
    int rx0 = static_cast<int>(std::floor(ox0)) - 1;
    int ry0 = static_cast<int>(std::floor(oy0)) - 1;
    int rx1 = static_cast<int>(std::ceil(ox1)) + 1;
    int ry1 = static_cast<int>(std::ceil(oy1)) + 1;
    if (rx0 < 0) rx0 = 0;
    if (ry0 < 0) ry0 = 0;
    if (rx1 >= w) rx1 = w - 1;
    if (ry1 >= h) ry1 = h - 1;

    dst.resize(w, h);
    dst.bytes() = src.bytes();
    // Clear the masked area (premultiplied-safe).
    for (int y = by0; y <= by1; ++y)
        for (int x = bx0; x <= bx1; ++x) {
            const std::size_t i = static_cast<std::size_t>(y) * w + x;
            if (mask[i] > 0) {
                std::uint8_t* p = dst.bytes().data() + i * 4;
                p[0] = p[1] = p[2] = p[3] = 0;
            }
        }
    auto maskAt = [&](int x, int y) -> bool {
        if (x < 0 || y < 0 || x >= w || y >= h) return false;
        return mask[static_cast<std::size_t>(y) * w + x] > 0;
    };
    for (int y = ry0; y <= ry1; ++y) {
        for (int x = rx0; x <= rx1; ++x) {
            const double px = x + 0.5, py = y + 0.5;
            double sx, sy;
            if (spec.mode == "persp") {
                // Output px -> quad unit -> bbox px.
                const double wz = H[6] * px + H[7] * py + H[8];
                if (!(std::fabs(wz) > 1e-9)) continue;
                const double u = (H[0] * px + H[1] * py + H[2]) / wz;
                const double v = (H[3] * px + H[4] * py + H[5]) / wz;
                sx = bx0 + u * bw;
                sy = by0 + v * bh;
            } else if (spec.mode == "skew") {
                // Inverse shear about the bbox center.
                const double qx = px - cx, qy = py - cy;
                sx = cx + (qx - shx * qy) / det;
                sy = cy + (qy - shy * qx) / det;
            } else {
                const double vx = px - cx, vy = py - cy;
                const double r = std::sqrt(vx * vx + vy * vy);
                if (r >= bulgeR) {
                    sx = px;
                    sy = py;
                } else {
                    // Smooth cosine falloff (1 at center, 0 at edge).
                    const double f =
                        (std::cos(M_PI * r / bulgeR) + 1.0) * 0.5;
                    const double s = 1.0 - spec.amount * f;
                    sx = cx + vx * s;
                    sy = cy + vy * s;
                }
            }
            const int ix = static_cast<int>(std::floor(sx));
            const int iy = static_cast<int>(std::floor(sy));
            if (!maskAt(ix, iy)) continue;
            Pixel ps = sampleBilinearW(src, sx - 0.5, sy - 0.5);
            Pixel pd = dst.get(x, y);
            overPixelW(ps, pd);
            dst.set(x, y, pd);
        }
    }
    return true;
}

}  // namespace openclip
