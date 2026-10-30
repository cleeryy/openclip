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
        case BlendMode::Darken:
            // T5: assombrir (per-channel min, commutative).
            return s < d ? s : d;
        case BlendMode::Lighten:
            // T5: éclaircir (per-channel max, commutative).
            return s < d ? d : s;
        case BlendMode::LinearBurn: {
            // T5: densité linéaire − (clamped sum minus one).
            const float r = s + d - 1.0f;
            return r < 0.0f ? 0.0f : r;
        }
        case BlendMode::LinearDodge: {
            // T5: documented twin of Add (clamped sum, own name).
            const float r = s + d;
            return r > 1.0f ? 1.0f : r;
        }
        case BlendMode::VividLight: {
            // T5: vive — burn under mid gray, dodge above.
            if (s <= 0.5f) {
                const float r = 2.0f * s + d - 1.0f;
                return r < 0.0f ? 0.0f : r;
            }
            const float r = 2.0f * (s - 0.5f) + d;
            return r > 1.0f ? 1.0f : r;
        }
        case BlendMode::LinearLight: {
            // T5: linéaire — signed double-strength offset, clamped.
            const float r = d + 2.0f * s - 1.0f;
            if (r < 0.0f) return 0.0f;
            if (r > 1.0f) return 1.0f;
            return r;
        }
        case BlendMode::PinLight: {
            // T5: ponctuelle — darken under mid gray, lighten above.
            if (s <= 0.5f) {
                const float t = 2.0f * s;
                return t < d ? t : d;
            }
            const float t = 2.0f * (s - 0.5f);
            return t > d ? t : d;
        }
        case BlendMode::Subtract: {
            // T5: soustraire (clamped at zero, signed unlike
            // Difference which mirrors).
            const float r = d - s;
            return r < 0.0f ? 0.0f : r;
        }
        case BlendMode::Divide: {
            // T5: diviser (clamped; divide-by-zero yields white
            // unless both are zero).
            if (s <= 0.0f) return d <= 0.0f ? 0.0f : 1.0f;
            const float r = d / s;
            return r > 1.0f ? 1.0f : r;
        }
        case BlendMode::Exclusion: {
            // T5: incrustation partielle (50% gray is neutral:
            // s=0.5 yields d).
            return s + d - 2.0f * s * d;
        }
        case BlendMode::ColorBurn: {
            // A2: densité couleur − — 1 - min(1, (1-d)/s), clamped.
            // White destination stays white; black source burns to black.
            if (d >= 1.0f) return 1.0f;
            if (s <= 0.0f) return 0.0f;
            const float r = 1.0f - (1.0f - d) / s;
            return r < 0.0f ? 0.0f : r;
        }
        case BlendMode::ColorDodge: {
            // A2: densité couleur + — min(1, d/(1-s)), clamped.
            // Black destination stays black; white source is neutral.
            if (d <= 0.0f) return 0.0f;
            if (s >= 1.0f) return 1.0f;
            const float r = d / (1.0f - s);
            return r > 1.0f ? 1.0f : r;
        }
        case BlendMode::AddGlow: {
            // A2: ajout luminescent — screen plus one more source
            // contribution (d + 2s - s*d), brighter than Add on dark
            // backdrops and still additive on light ones.
            const float r = d + 2.0f * s - s * d;
            return r > 1.0f ? 1.0f : r;
        }
        case BlendMode::Max:
            // A2: mélange maximal — documented twin of Lighten.
            return s < d ? d : s;
        case BlendMode::Color:
        case BlendMode::Luminosity:
        case BlendMode::Hue:
        case BlendMode::Saturation:
        case BlendMode::DarkerColor:
        case BlendMode::LighterColor:
            // Non-separable: handled on whole pixels in blendPixel.
            return s;
    }
    return s;
}

// A2: non-separable modes pick a whole straight RGB triple (never one
// channel at a time). Returns false for the separable families.
inline bool nonSeparable(BlendMode m, float sr, float sg, float sb,
                         float dr, float dg, float db, float& br,
                         float& bg, float& bb) {
    switch (m) {
        case BlendMode::Color:
        case BlendMode::Luminosity:
        case BlendMode::Hue:
        case BlendMode::Saturation: {
            float sh, ss, sl, dh, ds, dl;
            rgbToHsl(sr, sg, sb, sh, ss, sl);
            rgbToHsl(dr, dg, db, dh, ds, dl);
            // Color = source hue+sat over dst lightness (V+2).
            // Luminosity = source lightness over dst hue+sat (V+2).
            // Hue = source hue over dst sat+lightness (A2).
            // Saturation = source sat over dst hue+lightness (A2).
            switch (m) {
                case BlendMode::Color:
                    hslToRgb(sh, ss, dl, br, bg, bb);
                    break;
                case BlendMode::Luminosity:
                    hslToRgb(dh, ds, sl, br, bg, bb);
                    break;
                case BlendMode::Hue:
                    hslToRgb(sh, ds, dl, br, bg, bb);
                    break;
                default:
                    hslToRgb(dh, ss, dl, br, bg, bb);
                    break;
            }
            return true;
        }
        case BlendMode::DarkerColor:
        case BlendMode::LighterColor: {
            // A2: darker/lighter color — the whole source or the whole
            // destination wins, compared by HSL lightness (the same
            // luma definition the Color/Luminosity modes use). Ties
            // keep the destination.
            float sh, ss, sl, dh, ds, dl;
            rgbToHsl(sr, sg, sb, sh, ss, sl);
            rgbToHsl(dr, dg, db, dh, ds, dl);
            const bool dark = m == BlendMode::DarkerColor;
            const bool dstWins = dark ? (dl <= sl) : (dl >= sl);
            if (dstWins) {
                br = dr;
                bg = dg;
                bb = db;
            } else {
                br = sr;
                bg = sg;
                bb = sb;
            }
            return true;
        }
        default:
            return false;
    }
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

    // Non-separable modes (V+2 Color/Luminosity, A2 Hue/Saturation/
    // darker/lighter color) work on whole straight colors; the
    // separable families go through blendChannel per channel.
    float br = 0.0f, bg = 0.0f, bb = 0.0f;
    const bool whole = nonSeparable(mode, sr, sg, sb, dr, dg, db, br, bg, bb);
    const float fr = whole ? br : blendChannel(mode, sr, dr);
    const float fg = whole ? bg : blendChannel(mode, sg, dg);
    const float fb = whole ? bb : blendChannel(mode, sb, db);

    // Composite the blended straight color over dst straight color.
    const float cr = fr * sa + dr * da * (1.0f - sa);
    const float cg = fg * sa + dg * da * (1.0f - sa);
    const float cb = fb * sa + db * da * (1.0f - sa);

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
    // T5 additions (bare or svg:-prefixed via the colon rule above;
    // snake_case aliases accepted on input, canonical output stays
    // kebab-case like the V+2 names).
    if (!std::strcmp(n, "darken")) return BlendMode::Darken;
    if (!std::strcmp(n, "lighten")) return BlendMode::Lighten;
    if (!std::strcmp(n, "linear-burn") || !std::strcmp(n, "linear_burn") ||
        !std::strcmp(n, "linearburn"))
        return BlendMode::LinearBurn;
    if (!std::strcmp(n, "linear-dodge") || !std::strcmp(n, "linear_dodge") ||
        !std::strcmp(n, "lineardodge"))
        return BlendMode::LinearDodge;
    if (!std::strcmp(n, "vivid-light") || !std::strcmp(n, "vivid_light") ||
        !std::strcmp(n, "vividlight"))
        return BlendMode::VividLight;
    if (!std::strcmp(n, "linear-light") || !std::strcmp(n, "linear_light") ||
        !std::strcmp(n, "linearlight"))
        return BlendMode::LinearLight;
    if (!std::strcmp(n, "pin-light") || !std::strcmp(n, "pin_light") ||
        !std::strcmp(n, "pinlight"))
        return BlendMode::PinLight;
    if (!std::strcmp(n, "subtract")) return BlendMode::Subtract;
    if (!std::strcmp(n, "divide")) return BlendMode::Divide;
    if (!std::strcmp(n, "exclusion")) return BlendMode::Exclusion;
    // A2 additions (bare or svg:-prefixed via the colon rule above;
    // snake_case/kebab aliases accepted on input, canonical output
    // stays kebab-case).
    if (!std::strcmp(n, "color-burn") || !std::strcmp(n, "color_burn") ||
        !std::strcmp(n, "colorburn"))
        return BlendMode::ColorBurn;
    if (!std::strcmp(n, "color-dodge") || !std::strcmp(n, "color_dodge") ||
        !std::strcmp(n, "colordodge"))
        return BlendMode::ColorDodge;
    if (!std::strcmp(n, "add-glow") || !std::strcmp(n, "add_glow") ||
        !std::strcmp(n, "addglow"))
        return BlendMode::AddGlow;
    if (!std::strcmp(n, "max") || !std::strcmp(n, "maximum") ||
        !std::strcmp(n, "lighten-2") || !std::strcmp(n, "lighten_2") ||
        !std::strcmp(n, "lighten2"))
        return BlendMode::Max;
    if (!std::strcmp(n, "darker-color") || !std::strcmp(n, "darker_color") ||
        !std::strcmp(n, "darkercolor"))
        return BlendMode::DarkerColor;
    if (!std::strcmp(n, "lighter-color") ||
        !std::strcmp(n, "lighter_color") || !std::strcmp(n, "lightercolor"))
        return BlendMode::LighterColor;
    if (!std::strcmp(n, "hue")) return BlendMode::Hue;
    if (!std::strcmp(n, "saturation")) return BlendMode::Saturation;
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
        // T5 additions (canonical kebab-case svg: names; the ORA
        // round trip keeps these, third-party readers degrade to
        // Normal per the M5 rule).
        case BlendMode::Darken:
            return "svg:darken";
        case BlendMode::Lighten:
            return "svg:lighten";
        case BlendMode::LinearBurn:
            return "svg:linear-burn";
        case BlendMode::LinearDodge:
            return "svg:linear-dodge";
        case BlendMode::VividLight:
            return "svg:vivid-light";
        case BlendMode::LinearLight:
            return "svg:linear-light";
        case BlendMode::PinLight:
            return "svg:pin-light";
        case BlendMode::Subtract:
            return "svg:subtract";
        case BlendMode::Divide:
            return "svg:divide";
        case BlendMode::Exclusion:
            return "svg:exclusion";
        // A2 additions (canonical kebab-case svg: names; the ORA round
        // trip keeps these, third-party readers degrade to Normal and
        // the original name is preserved in the meta fiche).
        case BlendMode::ColorBurn:
            return "svg:color-burn";
        case BlendMode::ColorDodge:
            return "svg:color-dodge";
        case BlendMode::AddGlow:
            return "svg:add-glow";
        case BlendMode::Max:
            return "svg:max";
        case BlendMode::DarkerColor:
            return "svg:darker-color";
        case BlendMode::LighterColor:
            return "svg:lighter-color";
        case BlendMode::Hue:
            return "svg:hue";
        case BlendMode::Saturation:
            return "svg:saturation";
        case BlendMode::Normal:
        default:
            return "svg:normal";
    }
}

}  // namespace openclip
