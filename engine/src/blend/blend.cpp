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

    // Composite the blended straight color over dst straight color.
    const float cr = blendChannel(mode, sr, dr) * sa + dr * da * (1.0f - sa);
    const float cg = blendChannel(mode, sg, dg) * sa + dg * da * (1.0f - sa);
    const float cb = blendChannel(mode, sb, db) * sa + db * da * (1.0f - sa);

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
        case BlendMode::Normal:
        default:
            return "svg:normal";
    }
}

}  // namespace openclip
