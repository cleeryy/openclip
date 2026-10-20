// SPDX-License-Identifier: GPL-3.0-or-later
// Stamp brush: soft round dab, source-over onto premultiplied surface;
// .myb XML parser (clean-room, supports radius/hardness/opaque).
#include "openclip/brush.h"

#include "openclip/surface.h"

#include <algorithm>
#include <cmath>
#include <cctype>

namespace openclip {
namespace {

inline float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

// Falloff: 1 at center -> 0 at edge. hardness h: plateau radius fraction h,
// cosine rolloff from h..1. h=1 is a hard disc (1px antialiased edge).
float falloff(float t, float hardness) {
    if (t >= 1.0f) return 0.0f;
    const float h = clamp01(hardness);
    if (t <= h) return 1.0f;
    const float span = 1.0f - h;
    if (span <= 1e-6f) return 0.0f;
    const float u = (t - h) / span;  // 0..1
    return 0.5f * (1.0f + std::cos(u * 3.14159265f));
}

// Minimal XML attr reader: value of attr="..." starting search at pos.
bool readAttr(const std::string& xml, std::size_t tagStart, const char* attr,
              std::string& value) {
    const std::string a = std::string(attr) + "=";
    std::size_t p = xml.find(a, tagStart);
    const std::size_t tagEnd = xml.find('>', tagStart);
    if (p == std::string::npos || (tagEnd != std::string::npos && p > tagEnd))
        return false;
    p += a.size();
    while (p < xml.size() && std::isspace((unsigned char)xml[p])) ++p;
    if (p >= xml.size() || (xml[p] != '"' && xml[p] != '\'')) return false;
    const char q = xml[p++];
    const std::size_t e = xml.find(q, p);
    if (e == std::string::npos || (tagEnd != std::string::npos && e > tagEnd))
        return false;
    value = xml.substr(p, e - p);
    return true;
}

bool parseFloat(const std::string& s, float& out) {
    try {
        std::size_t n = 0;
        out = std::stof(s, &n);
        while (n < s.size() && std::isspace((unsigned char)s[n])) ++n;
        return n == s.size();
    } catch (...) {
        return false;
    }
}

}  // namespace

Dab dabFromPressure(const BrushParams& p, float x, float y, float pressure,
                    std::uint8_t r, std::uint8_t g, std::uint8_t b) {
    const float pr = clamp01(pressure);
    Dab d;
    d.x = x;
    d.y = y;
    d.pressure = pr;
    d.radius = p.radius * (0.25f + 0.75f * pr);
    if (d.radius < 0.5f) d.radius = 0.5f;
    d.opacity = clamp01(p.opaque) * pr;
    d.r = r;
    d.g = g;
    d.b = b;
    return d;
}

std::vector<Dab> interpolateStamps(const BrushParams& p,
                                   float x0, float y0, float pr0,
                                   float x1, float y1, float pr1,
                                   std::uint8_t r, std::uint8_t g, std::uint8_t b) {
    std::vector<Dab> out;
    const float dx = x1 - x0, dy = y1 - y0;
    const float dist = std::sqrt(dx * dx + dy * dy);
    float step = p.spacing * p.radius;
    if (step < 0.5f) step = 0.5f;
    if (dist <= 0) {
        out.push_back(dabFromPressure(p, x0, y0, pr0, r, g, b));
        return out;
    }
    int n = static_cast<int>(std::ceil(dist / step));
    if (n < 1) n = 1;
    if (n > 4096) n = 4096;
    for (int i = 0; i <= n; ++i) {
        const float t = n == 0 ? 0.0f : static_cast<float>(i) / n;
        out.push_back(dabFromPressure(p, x0 + dx * t, y0 + dy * t,
                                      pr0 + (pr1 - pr0) * t, r, g, b));
    }
    return out;
}

void paintDab(Surface& s, const Dab& d, const BrushParams& p) {
    if (d.opacity <= 0.0f || d.radius <= 0.0f) return;
    const int x0 = static_cast<int>(std::floor(d.x - d.radius));
    const int x1 = static_cast<int>(std::ceil(d.x + d.radius));
    const int y0 = static_cast<int>(std::floor(d.y - d.radius));
    const int y1 = static_cast<int>(std::ceil(d.y + d.radius));
    const float sa = d.opacity;
    const float fr = d.r / 255.0f, fg = d.g / 255.0f, fb = d.b / 255.0f;
    for (int y = y0; y <= y1; ++y) {
        for (int x = x0; x <= x1; ++x) {
            const float dx = (x + 0.5f) - d.x;
            const float dy = (y + 0.5f) - d.y;
            const float t = std::sqrt(dx * dx + dy * dy) / d.radius;
            const float m = falloff(t, p.hardness) * sa;
            if (m <= 1e-4f) continue;
            Pixel dst = s.get(x, y);
            const float da = dst.a / 255.0f;
            const float oa = m + da * (1.0f - m);
            if (oa <= 0.0f) continue;
            // Source-over: straight src color over premultiplied dst.
            const float cr = fr * m + (dst.r / 255.0f) * (1.0f - m);
            const float cg = fg * m + (dst.g / 255.0f) * (1.0f - m);
            const float cb = fb * m + (dst.b / 255.0f) * (1.0f - m);
            Pixel o;
            auto qch = [](float v) -> std::uint8_t {
                long r = std::lround(v * 255.0f);
                return static_cast<std::uint8_t>(r < 0 ? 0 : (r > 255 ? 255 : r));
            };
            o.a = qch(oa);
            o.r = qch(cr);
            o.g = qch(cg);
            o.b = qch(cb);
            if (o.r > o.a) o.r = o.a;
            if (o.g > o.a) o.g = o.a;
            if (o.b > o.a) o.b = o.a;
            s.set(x, y, o);
        }
    }
}

void eraseDab(Surface& s, const Dab& d, const BrushParams& p) {
    if (d.opacity <= 0.0f || d.radius <= 0.0f) return;
    const int x0 = static_cast<int>(std::floor(d.x - d.radius));
    const int x1 = static_cast<int>(std::ceil(d.x + d.radius));
    const int y0 = static_cast<int>(std::floor(d.y - d.radius));
    const int y1 = static_cast<int>(std::ceil(d.y + d.radius));
    const float sa = d.opacity;
    for (int y = y0; y <= y1; ++y) {
        for (int x = x0; x <= x1; ++x) {
            const float dx = (x + 0.5f) - d.x;
            const float dy = (y + 0.5f) - d.y;
            const float t = std::sqrt(dx * dx + dy * dy) / d.radius;
            const float m = falloff(t, p.hardness) * sa;
            if (m <= 1e-4f) continue;
            Pixel dst = s.get(x, y);
            if (dst.a == 0) continue;
            const float da = dst.a / 255.0f;
            const float oa = da * (1.0f - m);
            if (oa <= 0.5f / 255.0f) {
                s.set(x, y, Pixel{0, 0, 0, 0});
                continue;
            }
            // Rescale rgb by oa/da: premultiplied invariant (rgb <= a)
            // holds because dst was premultiplied.
            const float k = oa / da;
            Pixel o;
            o.a = static_cast<std::uint8_t>(std::lround(oa * 255.0f));
            o.r = static_cast<std::uint8_t>(std::lround(dst.r * k));
            o.g = static_cast<std::uint8_t>(std::lround(dst.g * k));
            o.b = static_cast<std::uint8_t>(std::lround(dst.b * k));
            if (o.r > o.a) o.r = o.a;
            if (o.g > o.a) o.g = o.a;
            if (o.b > o.a) o.b = o.a;
            s.set(x, y, o);
        }
    }
}
void paintMaskDab(Surface& m, const Dab& d, const BrushParams& p) {
    (void)p;  // M5 parity: the mask stamp is a linear disc (hardness
               // does not apply); the brush radius/opacity still do.
    if (d.opacity <= 0.0f || d.radius <= 0.0f) return;
    // Bounds mirror the former shim loop byte-for-byte (pad + linear
    // falloff 1-dist/rad, exclusive ends); get/set clamp anyway.
    const int rad = d.radius > 0.5f ? static_cast<int>(d.radius) : 1;
    const int pad = static_cast<int>(d.radius) + 1;
    const int x0 = std::max(0, static_cast<int>(d.x) - pad);
    const int y0 = std::max(0, static_cast<int>(d.y) - pad);
    const int x1 = std::min(m.width(), static_cast<int>(d.x) + pad);
    const int y1 = std::min(m.height(), static_cast<int>(d.y) + pad);
    // M5 rule, byte-identical to the former shim loop: ink luminance
    // (scaled once by opacity here) blends toward the mask alpha with
    // the stamp's own opacity applied a second time via sa (kept for
    // behavior parity — mask strokes hide progressively).
    const float lum = (0.299f * d.r + 0.587f * d.g + 0.114f * d.b) *
                      clamp01(d.opacity);
    for (int y = y0; y < y1; ++y) {
        for (int x = x0; x < x1; ++x) {
            const float dx = (x + 0.5f) - d.x;
            const float dy = (y + 0.5f) - d.y;
            const float dist = std::sqrt(dx * dx + dy * dy);
            if (dist > rad) continue;
            const float sa = (1.0f - dist / rad) * clamp01(d.opacity);
            if (sa <= 1e-4f) continue;
            Pixel dst = m.get(x, y);
            const float da = dst.a / 255.0f;
            const float ta = lum / 255.0f;
            const float oa = ta * sa + da * (1.0f - sa);
            long v = std::lround(oa * 255.0f);
            if (v < 0) v = 0;
            if (v > 255) v = 255;
            const std::uint8_t b = static_cast<std::uint8_t>(v);
            m.set(x, y, Pixel{b, b, b, b});
        }
    }
}

bool parseMyb(const std::string& xml, BrushParams& out, std::string& error) {
    BrushParams p;  // defaults
    std::size_t pos = 0;
    bool anyTag = false;
    while ((pos = xml.find("<setting", pos)) != std::string::npos) {
        anyTag = true;
        std::string name, val;
        if (!readAttr(xml, pos, "name", name)) {
            error = "myb: <setting> without name";
            return false;
        }
        const std::size_t tagEnd = xml.find('>', pos);
        if (tagEnd == std::string::npos) {
            error = "myb: unterminated tag";
            return false;
        }
        // base_value may live on the <setting> tag or a nested <value> tag.
        std::string base;
        bool have = readAttr(xml, pos, "base_value", base);
        if (!have) {
            const std::size_t vpos = xml.find("<value", pos);
            const std::size_t close = xml.find("</setting>", pos);
            if (vpos != std::string::npos &&
                (close == std::string::npos || vpos < close))
                have = readAttr(xml, vpos, "base_value", base);
        }
        if (name == "radius" || name == "hardness" || name == "opaque") {
            if (!have) {
                error = std::string("myb: setting '") + name + "' lacks base_value";
                return false;
            }
            float f = 0;
            if (!parseFloat(base, f)) {
                error = std::string("myb: bad number for '") + name + "'";
                return false;
            }
            if (name == "radius") p.radius = f < 0.5f ? 0.5f : f;
            if (name == "hardness") p.hardness = clamp01(f);
            if (name == "opaque") p.opaque = clamp01(f);
        }
        // Unknown settings ignored.
        pos = tagEnd + 1;
        (void)val;
    }
    if (!anyTag) {
        error = "myb: no <setting> entries found";
        return false;
    }
    out = p;
    return true;
}

}  // namespace openclip
