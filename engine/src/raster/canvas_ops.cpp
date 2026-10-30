// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip W1 (issue #28 slice A) — canvas geometry implementation.
#include "openclip/canvas_ops.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <utility>
#include <vector>

namespace openclip {

namespace {

struct Xform {
    int oldW = 0, oldH = 0, newW = 0, newH = 0;
    int dx = 0, dy = 0;
    bool resample = false;

    float mapX(float x) const {
        if (!resample) return x + static_cast<float>(dx);
        if (oldW <= 0) return x;
        return x * static_cast<float>(newW) / static_cast<float>(oldW);
    }
    float mapY(float y) const {
        if (!resample) return y + static_cast<float>(dy);
        if (oldH <= 0) return y;
        return y * static_cast<float>(newH) / static_cast<float>(oldH);
    }
    float scaleX() const {
        if (!resample || oldW <= 0) return 1.0f;
        return static_cast<float>(newW) / static_cast<float>(oldW);
    }
    float scaleY() const {
        if (!resample || oldH <= 0) return 1.0f;
        return static_cast<float>(newH) / static_cast<float>(oldH);
    }
};

Surface placeSurface(const Surface& src, int w, int h, int dx, int dy) {
    Surface out(w, h);
    for (int y = 0; y < h; ++y) {
        const int sy = y - dy;
        if (sy < 0 || sy >= src.height()) continue;
        for (int x = 0; x < w; ++x) {
            const int sx = x - dx;
            if (sx < 0 || sx >= src.width()) continue;
            out.set(x, y, src.get(sx, sy));
        }
    }
    return out;
}

// Mask placement pads with opaque white (a grown canvas keeps showing).
Surface placeMask(const Surface& src, int w, int h, int dx, int dy) {
    Surface out(w, h);
    for (int y = 0; y < h; ++y) {
        const int sy = y - dy;
        for (int x = 0; x < w; ++x) {
            const int sx = x - dx;
            if (sx < 0 || sx >= src.width() || sy < 0 || sy >= src.height()) {
                out.set(x, y, Pixel{255, 255, 255, 255});
            } else {
                out.set(x, y, src.get(sx, sy));
            }
        }
    }
    return out;
}

// Bilinear resample on premultiplied RGBA (alpha-safe: interpolated rgb
// is clamped to the interpolated alpha).
Surface scaleSurface(const Surface& src, int w, int h) {
    Surface out(w, h);
    const int sw = src.width(), sh = src.height();
    if (sw <= 0 || sh <= 0 || w <= 0 || h <= 0) return out;
    for (int y = 0; y < h; ++y) {
        const float sy = (static_cast<float>(y) + 0.5f) *
                             static_cast<float>(sh) /
                             static_cast<float>(h) -
                         0.5f;
        const int y0 = static_cast<int>(std::floor(sy));
        const float fy = sy - static_cast<float>(y0);
        const int ya = std::clamp(y0, 0, sh - 1);
        const int yb = std::clamp(y0 + 1, 0, sh - 1);
        for (int x = 0; x < w; ++x) {
            const float sx = (static_cast<float>(x) + 0.5f) *
                                 static_cast<float>(sw) / static_cast<float>(w) -
                             0.5f;
            const int x0 = static_cast<int>(std::floor(sx));
            const float fx = sx - static_cast<float>(x0);
            const int xa = std::clamp(x0, 0, sw - 1);
            const int xb = std::clamp(x0 + 1, 0, sw - 1);
            const Pixel p00 = src.get(xa, ya);
            const Pixel p10 = src.get(xb, ya);
            const Pixel p01 = src.get(xa, yb);
            const Pixel p11 = src.get(xb, yb);
            auto lerp = [&](std::uint8_t a, std::uint8_t b, std::uint8_t c,
                            std::uint8_t d) {
                const float top = static_cast<float>(a) * (1.0f - fx) +
                                  static_cast<float>(b) * fx;
                const float bot = static_cast<float>(c) * (1.0f - fx) +
                                  static_cast<float>(d) * fx;
                const float v = top * (1.0f - fy) + bot * fy;
                return static_cast<int>(v + 0.5f);
            };
            Pixel o;
            o.a = static_cast<std::uint8_t>(std::clamp(lerp(p00.a, p10.a, p01.a, p11.a), 0, 255));
            const int a = o.a;
            o.r = static_cast<std::uint8_t>(
                std::clamp(lerp(p00.r, p10.r, p01.r, p11.r), 0, a));
            o.g = static_cast<std::uint8_t>(
                std::clamp(lerp(p00.g, p10.g, p01.g, p11.g), 0, a));
            o.b = static_cast<std::uint8_t>(
                std::clamp(lerp(p00.b, p10.b, p01.b, p11.b), 0, a));
            out.set(x, y, o);
        }
    }
    return out;
}

// "<num>,<num>,..." transform for bubble frames/tails (4 numbers).
std::string transformCsv(const std::string& src, const Xform& t, bool lengthPair) {
    if (src.empty()) return src;
    std::vector<double> vals;
    std::size_t i = 0;
    while (i < src.size()) {
        std::size_t j = src.find(',', i);
        if (j == std::string::npos) j = src.size();
        const std::string tok = src.substr(i, j - i);
        char* endp = nullptr;
        const double v = std::strtod(tok.c_str(), &endp);
        if (endp == tok.c_str()) return src;  // unparsable: verbatim
        vals.push_back(v);
        i = j + 1;
    }
    if (vals.size() % 2 != 0) return src;
    std::string out;
    for (std::size_t k = 0; k < vals.size(); k += 2) {
        const bool isLen = lengthPair && (k >= 2);
        const double x = isLen ? vals[k] * t.scaleX() : t.mapX(static_cast<float>(vals[k]));
        const double y = isLen ? vals[k + 1] * t.scaleY()
                               : t.mapY(static_cast<float>(vals[k + 1]));
        if (k) out += ',';
        out += std::to_string(static_cast<long long>(std::llround(x)));
        out += ',';
        out += std::to_string(static_cast<long long>(std::llround(y)));
    }
    return out;
}

// "mode|x0|y0|x1|y1|hue|scale|shine" gradient span follow-up.
std::string transformFillParams(const std::string& src, const Xform& t) {
    if (src.empty()) return src;
    std::vector<std::string> parts;
    std::size_t i = 0;
    while (i <= src.size()) {
        std::size_t j = src.find('|', i);
        if (j == std::string::npos) j = src.size();
        parts.push_back(src.substr(i, j - i));
        if (j == src.size()) break;
        i = j + 1;
    }
    if (parts.size() < 8) return src;
    auto num = [&](int k, bool len, bool yAxis) {
        char* endp = nullptr;
        const double d = std::strtod(parts[k].c_str(), &endp);
        if (endp == parts[k].c_str()) return false;
        const double mapped = len ? d * (yAxis ? t.scaleY() : t.scaleX())
                                  : (yAxis ? t.mapY(static_cast<float>(d))
                                           : t.mapX(static_cast<float>(d)));
        parts[k] = std::to_string(static_cast<long long>(std::llround(mapped)));
        return true;
    };
    num(1, false, false);
    num(2, false, true);
    num(3, false, false);
    num(4, false, true);
    std::string out;
    for (std::size_t k = 0; k < parts.size(); ++k) {
        if (k) out += '|';
        out += parts[k];
    }
    return out;
}

Layer transformLayer(const Layer& in, int w, int h, const Xform& t) {
    Layer l = in;
    if (t.resample) {
        l.surface = scaleSurface(in.surface, w, h);
        if (l.hasMask) l.mask = scaleSurface(in.mask, w, h);
    } else {
        l.surface = placeSurface(in.surface, w, h, t.dx, t.dy);
        if (l.hasMask) l.mask = placeMask(in.mask, w, h, t.dx, t.dy);
    }
    for (auto& ch : l.children) ch = transformLayer(ch, w, h, t);
    const float sx = t.scaleX(), sy = t.scaleY();
    const float sw = std::sqrt(std::max(1e-6f, sx * sy));
    for (auto& p : l.vectorData) {
        for (auto& pt : p.pts) {
            pt.x = t.mapX(pt.x);
            pt.y = t.mapY(pt.y);
        }
        if (t.resample)
            for (auto& wv : p.widthProfile) wv *= sw;
    }
    l.bubbleFrame = transformCsv(in.bubbleFrame, t, true);
    l.bubbleTail = transformCsv(in.bubbleTail, t, false);
    l.fillParams = transformFillParams(in.fillParams, t);
    return l;
}

bool rebuild(Canvas& c, int w, int h, const Xform& t) {
    Canvas work;
    work.resize(w, h);
    const int active = c.activeLayer();
    for (std::size_t i = 0; i < c.layerCount(); ++i)
        work.adoptLayer(transformLayer(c.layer(i), w, h, t));
    c = std::move(work);
    c.setActiveLayer(active);
    return true;
}

}  // namespace

CanvasAnchor anchorFromName(const char* name) {
    if (!name) return CanvasAnchor::Center;
    const std::string s(name);
    if (s == "top-left") return CanvasAnchor::TopLeft;
    if (s == "top" || s == "top-center") return CanvasAnchor::Top;
    if (s == "top-right") return CanvasAnchor::TopRight;
    if (s == "left" || s == "center-left") return CanvasAnchor::Left;
    if (s == "right" || s == "center-right") return CanvasAnchor::Right;
    if (s == "bottom-left") return CanvasAnchor::BottomLeft;
    if (s == "bottom" || s == "bottom-center") return CanvasAnchor::Bottom;
    if (s == "bottom-right") return CanvasAnchor::BottomRight;
    return CanvasAnchor::Center;
}

const char* anchorToName(CanvasAnchor a) {
    switch (a) {
        case CanvasAnchor::TopLeft: return "top-left";
        case CanvasAnchor::Top: return "top-center";
        case CanvasAnchor::TopRight: return "top-right";
        case CanvasAnchor::Left: return "center-left";
        case CanvasAnchor::Center: return "center";
        case CanvasAnchor::Right: return "center-right";
        case CanvasAnchor::BottomLeft: return "bottom-left";
        case CanvasAnchor::Bottom: return "bottom-center";
        case CanvasAnchor::BottomRight: return "bottom-right";
    }
    return "center";
}

bool resizeCanvas(Canvas& c, int w, int h, CanvasAnchor anchor, bool resample,
                  std::string& err) {
    if (w <= 0 || h <= 0) {
        err = "canvas size must be positive";
        return false;
    }
    const int ow = c.width(), oh = c.height();
    if (ow == w && oh == h) return true;
    Xform t;
    t.oldW = ow;
    t.oldH = oh;
    t.newW = w;
    t.newH = h;
    t.resample = resample;
    if (!resample) {
        const int ai = static_cast<int>(anchor) % 3;
        const int aj = static_cast<int>(anchor) / 3;
        t.dx = (w - ow) * ai / 2;
        t.dy = (h - oh) * aj / 2;
    }
    return rebuild(c, w, h, t);
}

bool cropCanvas(Canvas& c, int x, int y, int w, int h, std::string& err) {
    if (w <= 0 || h <= 0) {
        err = "crop size must be positive";
        return false;
    }
    if (x < 0 || y < 0 || x + w > c.width() || y + h > c.height()) {
        err = "crop rectangle must be inside the canvas";
        return false;
    }
    Xform t;
    t.oldW = c.width();
    t.oldH = c.height();
    t.newW = w;
    t.newH = h;
    t.dx = -x;
    t.dy = -y;
    return rebuild(c, w, h, t);
}

PrintSize printSizeMm(int pxW, int pxH, double dpi) {
    PrintSize s;
    if (dpi <= 0.0 || pxW <= 0 || pxH <= 0) return s;
    s.widthMm = static_cast<double>(pxW) / dpi * 25.4;
    s.heightMm = static_cast<double>(pxH) / dpi * 25.4;
    return s;
}

}  // namespace openclip
