// SPDX-License-Identifier: GPL-3.0-or-later
// Blend kernels on premultiplied RGBA8.
// Method: un-premultiply to straight sRGB [0,1], apply the classic
// Porter-Duff-over compositing of the separable blend of straight colors,
// re-premultiply, round with lround + clamp. Fully transparent dst (a=0)
// yields src unchanged for every mode.
#include "openclip/blend.h"

#include "openclip/surface.h"

#include <cmath>
#include <cstring>

namespace openclip {
namespace {

inline float u8(float v) { return v / 255.0f; }

// From-scratch RGB<->HSL round trip (all channels 0..1) for the V+2
// Color / Luminosity modes. Achromatic (s=0) maps hue to 0.
inline void rgbToHsl(float r, float g, float b, float& h, float& s,
                     float& l) {
    const float mx = r > g ? (r > b ? r : b) : (g > b ? g : b);
    const float mn = r < g ? (r < b ? r : b) : (g < b ? g : b);
    l = (mx + mn) * 0.5f;
    if (mx == mn) {
        h = 0.0f;
        s = 0.0f;
        return;
    }
    const float d = mx - mn;
    s = l > 0.5f ? d / (2.0f - mx - mn) : d / (mx + mn);
    if (mx == r)
        h = (g - b) / d + (g < b ? 6.0f : 0.0f);
    else if (mx == g)
        h = (b - r) / d + 2.0f;
    else
        h = (r - g) / d + 4.0f;
    h /= 6.0f;
}

inline float hueToRgb(float p, float q, float t) {
    if (t < 0.0f) t += 1.0f;
    if (t > 1.0f) t -= 1.0f;
    if (t < 1.0f / 6.0f) return p + (q - p) * 6.0f * t;
    if (t < 0.5f) return q;
    if (t < 2.0f / 3.0f) return p + (q - p) * (2.0f / 3.0f - t) * 6.0f;
    return p;
}

inline void hslToRgb(float h, float s, float l, float& r, float& g,
                     float& b) {
    if (s == 0.0f) {
        r = g = b = l;
        return;
    }
    const float q = l < 0.5f ? l * (1.0f + s) : l + s - l * s;
    const float p = 2.0f * l - q;
    r = hueToRgb(p, q, h + 1.0f / 3.0f);
    g = hueToRgb(p, q, h);
    b = hueToRgb(p, q, h - 1.0f / 3.0f);
}

float blendChannel(BlendMode m, float s, float d) {
    switch (m) {
        case BlendMode::Normal:
            return s;
        case BlendMode::Multiply:
            return s * d;
        case BlendMode::Screen:
            return s + d - s * d;
        case BlendMode::Overlay:
            return d <= 0.5f ? 2.0f * s * d : 1.0f - 2.0f * (1.0f - s) * (1.0f - d);
        case BlendMode::Add:
            return s + d > 1.0f ? 1.0f : s + d;
        case BlendMode::SoftLight: {
            // Pegtop formulation: (1-2s)*d^2 + 2*s*d
            const float r = (1.0f - 2.0f * s) * d * d + 2.0f * s * d;
            return r < 0.0f ? 0.0f : (r > 1.0f ? 1.0f : r);
        }
        case BlendMode::HardLight:
            // Overlay with src/dst roles swapped.
            return s <= 0.5f ? 2.0f * s * d : 1.0f - 2.0f * (1.0f - s) * (1.0f - d);
        case BlendMode::Difference: {
            const float r = s - d;
            return r < 0.0f ? -r : r;
        }
        case BlendMode::Color:
        case BlendMode::Luminosity:
            // Non-separable: handled on whole pixels in blendPixel.
            return s;
    }
    return s;
}

inline std::uint8_t q(float v) {
    const long r = std::lround(v * 255.0f);
    return static_cast<std::uint8_t>(r < 0 ? 0 : (r > 255 ? 255 : r));
}

}  // namespace

void blendPixel(const Pixel& src, const Pixel& dst, BlendMode mode, Pixel& out) {
    const float sa = u8(src.a), da = u8(dst.a);
    const float oa = sa + da * (1.0f - sa);  // Porter-Duff over
    if (oa <= 0.0f) {
        out = Pixel{};
        return;
    }
    const float sr = sa > 0 ? u8(src.r) / sa : 0.0f;
    const float sg = sa > 0 ? u8(src.g) / sa : 0.0f;
    const float sb = sa > 0 ? u8(src.b) / sa : 0.0f;
    const float dr = da > 0 ? u8(dst.r) / da : 0.0f;
    const float dg = da > 0 ? u8(dst.g) / da : 0.0f;
    const float db = da > 0 ? u8(dst.b) / da : 0.0f;

    // Non-separable V+2 modes work on whole straight colors: Color =
    // source hue/saturation over destination luminance, Luminosity =
    // source luminance over destination hue/saturation.
    float br = 0.0f, bg = 0.0f, bb = 0.0f;
    if (mode == BlendMode::Color || mode == BlendMode::Luminosity) {
        float sh, ss, sl, dh, ds, dl;
        rgbToHsl(sr, sg, sb, sh, ss, sl);
        rgbToHsl(dr, dg, db, dh, ds, dl);
        if (mode == BlendMode::Color)
            hslToRgb(sh, ss, dl, br, bg, bb);
        else
            hslToRgb(dh, ds, sl, br, bg, bb);
    }

    // Composite the blended straight color over dst straight color.
    const float cr = (mode == BlendMode::Color || mode == BlendMode::Luminosity ? br : blendChannel(mode, sr, dr)) * sa + dr * da * (1.0f - sa);
    const float cg = (mode == BlendMode::Color || mode == BlendMode::Luminosity ? bg : blendChannel(mode, sg, dg)) * sa + dg * da * (1.0f - sa);
    const float cb = (mode == BlendMode::Color || mode == BlendMode::Luminosity ? bb : blendChannel(mode, sb, db)) * sa + db * da * (1.0f - sa);

    out.a = q(oa);
    // cr/cg/cb are already the premultiplied result colors.
    out.r = q(cr);
    out.g = q(cg);
    out.b = q(cb);
    // Clamp rgb <= a (rounding can overshoot by 1).
    if (out.r > out.a) out.r = out.a;
    if (out.g > out.a) out.g = out.a;
    if (out.b > out.a) out.b = out.a;
}

BlendMode blendFromName(const char* name) {
    if (!name) return BlendMode::Normal;
    const char* n = name;
    // Accept both "svg:multiply" and bare "multiply".
    const char* colon = std::strchr(n, ':');
    if (colon) n = colon + 1;
    if (!std::strcmp(n, "multiply")) return BlendMode::Multiply;
    if (!std::strcmp(n, "screen")) return BlendMode::Screen;
    if (!std::strcmp(n, "overlay")) return BlendMode::Overlay;
    if (!std::strcmp(n, "plus") || !std::strcmp(n, "add")) return BlendMode::Add;
    if (!std::strcmp(n, "soft-light") || !std::strcmp(n, "soft_light") ||
        !std::strcmp(n, "softlight"))
        return BlendMode::SoftLight;
    // V+2 additions (bare or svg:-prefixed via the colon rule above).
    if (!std::strcmp(n, "hard-light") || !std::strcmp(n, "hard_light") ||
        !std::strcmp(n, "hardlight"))
        return BlendMode::HardLight;
    if (!std::strcmp(n, "difference")) return BlendMode::Difference;
    if (!std::strcmp(n, "color")) return BlendMode::Color;
    if (!std::strcmp(n, "luminosity")) return BlendMode::Luminosity;
    return BlendMode::Normal;
}

const char* blendToName(BlendMode m) {
    switch (m) {
        case BlendMode::Multiply:
            return "svg:multiply";
        case BlendMode::Screen:
            return "svg:screen";
        case BlendMode::Overlay:
            return "svg:overlay";
        case BlendMode::Add:
            return "svg:plus";
        case BlendMode::SoftLight:
            return "svg:soft-light";
        case BlendMode::HardLight:
            return "svg:hard-light";
        case BlendMode::Difference:
            return "svg:difference";
        case BlendMode::Color:
            return "svg:color";
        case BlendMode::Luminosity:
            return "svg:luminosity";
        case BlendMode::Normal:
        default:
            return "svg:normal";
    }
}

}  // namespace openclip
