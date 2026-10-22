// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip T2 engine — composite-time balloon kernel (see bubble.h).
#include "openclip/bubble.h"

#include <cmath>
#include <cstdlib>

#include "openclip/surface.h"

namespace openclip {
namespace {

bool parseFloatList(const std::string& s, float* out, int n) {
    std::size_t pos = 0;
    for (int i = 0; i < n; ++i) {
        while (pos < s.size() &&
               (s[pos] == ' ' || s[pos] == ',' || s[pos] == ';'))
            ++pos;
        if (pos >= s.size()) return false;
        char* endp = nullptr;
        const float v =
            std::strtof(s.c_str() + pos, &endp);
        if (endp == s.c_str() + pos) return false;
        out[i] = v;
        pos = static_cast<std::size_t>(endp - s.c_str());
    }
    return true;
}

bool parseHex(const std::string& s, std::uint8_t& r, std::uint8_t& g,
              std::uint8_t& b) {
    std::string h = s;
    if (!h.empty() && h[0] == '#') h = h.substr(1);
    auto hex = [](char c) -> int {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    };
    if (h.size() != 6) return false;
    int v[3];
    for (int i = 0; i < 3; ++i) {
        const int hi = hex(h[i * 2]), lo = hex(h[i * 2 + 1]);
        if (hi < 0 || lo < 0) return false;
        v[i] = hi * 16 + lo;
    }
    r = static_cast<std::uint8_t>(v[0]);
    g = static_cast<std::uint8_t>(v[1]);
    b = static_cast<std::uint8_t>(v[2]);
    return true;
}

// Point in body (ellipse or rounded rect with radius rad).
bool inBody(const BubbleSpec& sp, float px, float py, float rad) {
    if (sp.shape == "rounded") {
        const float x0 = sp.x + rad, x1 = sp.x + sp.w - rad;
        const float y0 = sp.y + rad, y1 = sp.y + sp.h - rad;
        const float cx = (px < x0) ? x0 : ((px > x1) ? x1 : px);
        const float cy = (py < y0) ? y0 : ((py > y1) ? y1 : py);
        const float dx = px - cx, dy = py - cy;
        return dx * dx + dy * dy <= rad * rad;
    }
    // Ellipse (default).
    const float cx = sp.x + sp.w * 0.5f, cy = sp.y + sp.h * 0.5f;
    const float rx = sp.w * 0.5f, ry = sp.h * 0.5f;
    if (rx <= 0 || ry <= 0) return false;
    const float nx = (px - cx) / rx, ny = (py - cy) / ry;
    return nx * nx + ny * ny <= 1.0f;
}

bool inTail(const BubbleSpec& sp, float px, float py) {
    if (!sp.hasTail) return false;
    // Triangle (bx,by) tip (tx,ty): base perpendicular to the tail
    // axis, half-width = clamp(|base-tip| * 0.35, 6..48px).
    float ax = sp.tx - sp.bx, ay = sp.ty - sp.by;
    const float len = std::sqrt(ax * ax + ay * ay);
    if (len < 1.0f) return false;
    ax /= len;
    ay /= len;
    float hw = len * 0.35f;
    if (hw < 6.0f) hw = 6.0f;
    if (hw > 48.0f) hw = 48.0f;
    const float nx = -ay, ny = ax;
    const float b1x = sp.bx + nx * hw, b1y = sp.by + ny * hw;
    const float b2x = sp.bx - nx * hw, b2y = sp.by - ny * hw;
    // Barycentric sign test.
    auto sign = [](float x1, float y1, float x2, float y2, float x3,
                   float y3) {
        return (x1 - x3) * (y2 - y3) - (x2 - x3) * (y1 - y3);
    };
    const float d1 = sign(px, py, b1x, b1y, b2x, b2y);
    const float d2 = sign(px, py, b2x, b2y, sp.tx, sp.ty);
    const float d3 = sign(px, py, sp.tx, sp.ty, b1x, b1y);
    const bool neg = (d1 < 0) || (d2 < 0) || (d3 < 0);
    const bool pos = (d1 > 0) || (d2 > 0) || (d3 > 0);
    return !(neg && pos);
}

bool inBalloon(const BubbleSpec& sp, float px, float py, float rad) {
    return inBody(sp, px, py, rad) || inTail(sp, px, py);
}

}  // namespace

bool parseBubbleSpec(const std::string& shape, const std::string& frame,
                     const std::string& tail, const std::string& fill,
                     const std::string& stroke, float strokeW,
                     BubbleSpec& spec, std::string& error) {
    BubbleSpec sp;
    if (shape != "ellipse" && shape != "rounded") {
        error = "bubble: unknown shape";
        return false;
    }
    sp.shape = shape;
    float f[4];
    if (!parseFloatList(frame, f, 4) || f[2] <= 0 || f[3] <= 0) {
        error = "bubble: bad frame";
        return false;
    }
    sp.x = f[0];
    sp.y = f[1];
    sp.w = f[2];
    sp.h = f[3];
    if (!tail.empty()) {
        float t[4];
        if (!parseFloatList(tail, t, 4)) {
            error = "bubble: bad tail";
            return false;
        }
        sp.hasTail = true;
        sp.bx = t[0];
        sp.by = t[1];
        sp.tx = t[2];
        sp.ty = t[3];
    }
    if (!fill.empty() && !parseHex(fill, sp.fillR, sp.fillG, sp.fillB)) {
        error = "bubble: bad fill";
        return false;
    }
    if (!stroke.empty() &&
        !parseHex(stroke, sp.strokeR, sp.strokeG, sp.strokeB)) {
        error = "bubble: bad stroke";
        return false;
    }
    sp.strokeW = (strokeW >= 1.0f && strokeW <= 32.0f) ? strokeW : 4.0f;
    spec = sp;
    return true;
}

bool renderBubble(const BubbleSpec& spec, Surface& out, std::string& error) {
    const int w = out.width(), h = out.height();
    if (w <= 0 || h <= 0 || spec.w <= 0 || spec.h <= 0) {
        error = "bubble: degenerate";
        out.clear();
        return false;
    }
    const float rad =
        (spec.shape == "rounded")
            ? std::min(spec.w, spec.h) / 6.0f
            : 0.0f;
    const float half = spec.strokeW * 0.5f;
    auto& px = out.bytes();
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            // 4x4 coverage supersampling (same pattern as stampShape).
            int fill = 0, edge = 0;
            for (int sy = 0; sy < 4; ++sy) {
                for (int sx = 0; sx < 4; ++sx) {
                    const float fx = x + (sx + 0.5f) / 4.0f;
                    const float fy = y + (sy + 0.5f) / 4.0f;
                    if (inBalloon(spec, fx, fy, rad)) {
                        ++fill;
                        continue;
                    }
                    // Stroke band: within half on either side of the
                    // edge — probe by sampling the neighborhood max.
                    bool near = false;
                    for (int oy = -1; oy <= 1 && !near; ++oy)
                        for (int ox = -1; ox <= 1 && !near; ++ox) {
                            const float nx =
                                fx + ox * half * 0.75f;
                            const float ny =
                                fy + oy * half * 0.75f;
                            if (inBalloon(spec, nx, ny, rad)) near = true;
                        }
                    if (near) ++edge;
                }
            }
            if (fill == 0 && edge == 0) continue;
            const std::size_t i =
                (static_cast<std::size_t>(y) * w + x) * 4;
            if (fill > 0) {
                // Source-over opaque fill at coverage fill/16.
                const int cov = fill;  // 1..16
                const std::uint8_t a =
                    static_cast<std::uint8_t>((cov * 255 + 8) / 16);
                const std::uint8_t fr = static_cast<std::uint8_t>(
                    (spec.fillR * cov + 8) / 16);
                const std::uint8_t fg = static_cast<std::uint8_t>(
                    (spec.fillG * cov + 8) / 16);
                const std::uint8_t fb = static_cast<std::uint8_t>(
                    (spec.fillB * cov + 8) / 16);
                // Blend over dst (premultiplied).
                const std::uint8_t da = px[i + 3];
                const int ia = 255 - a;
                px[i + 0] = static_cast<std::uint8_t>(
                    fr + (px[i + 0] * ia + 127) / 255);
                px[i + 1] = static_cast<std::uint8_t>(
                    fg + (px[i + 1] * ia + 127) / 255);
                px[i + 2] = static_cast<std::uint8_t>(
                    fb + (px[i + 2] * ia + 127) / 255);
                px[i + 3] = static_cast<std::uint8_t>(
                    a + (da * ia + 127) / 255);
            } else {
                // Stroke-only pixel (edge band, stroke color).
                const int cov = edge;
                const std::uint8_t a =
                    static_cast<std::uint8_t>((cov * 255 + 8) / 16);
                const std::uint8_t sr = static_cast<std::uint8_t>(
                    (spec.strokeR * cov + 8) / 16);
                const std::uint8_t sg = static_cast<std::uint8_t>(
                    (spec.strokeG * cov + 8) / 16);
                const std::uint8_t sb = static_cast<std::uint8_t>(
                    (spec.strokeB * cov + 8) / 16);
                const std::uint8_t da = px[i + 3];
                const int ia = 255 - a;
                px[i + 0] = static_cast<std::uint8_t>(
                    sr + (px[i + 0] * ia + 127) / 255);
                px[i + 1] = static_cast<std::uint8_t>(
                    sg + (px[i + 1] * ia + 127) / 255);
                px[i + 2] = static_cast<std::uint8_t>(
                    sb + (px[i + 2] * ia + 127) / 255);
                px[i + 3] = static_cast<std::uint8_t>(
                    a + (da * ia + 127) / 255);
            }
        }
    }
    return true;
}

}  // namespace openclip
