// SPDX-License-Identifier: GPL-3.0-or-later
// Blend kernel tests with hand-computed pixel values.
// Convention: premultiplied inputs. Hand checks computed with the same
// float pipeline (un-premult -> blend straight -> over -> lround).
#include "test.h"

#include <cmath>

#include "openclip/blend.h"
#include "openclip/surface.h"

using openclip::BlendMode;
using openclip::blendFromName;
using openclip::blendPixel;
using openclip::blendToName;
using openclip::Pixel;

// Opaque red over opaque blue, Normal -> src wins exactly.
TEST(blend_normal_opaque) {
    Pixel s{255, 0, 0, 255}, d{0, 0, 255, 255}, o;
    blendPixel(s, d, BlendMode::Normal, o);
    CHECK_EQ(o.r, 255);
    CHECK_EQ(o.g, 0);
    CHECK_EQ(o.b, 0);
    CHECK_EQ(o.a, 255);
}

// Transparent src is a no-op for every mode.
TEST(blend_transparent_src_noop) {
    Pixel s{0, 0, 0, 0}, d{10, 20, 30, 200}, o;
    for (auto m : {BlendMode::Normal, BlendMode::Multiply, BlendMode::Screen,
                   BlendMode::Overlay, BlendMode::Add, BlendMode::SoftLight}) {
        blendPixel(s, d, m, o);
        CHECK_EQ(o.r, d.r);
        CHECK_EQ(o.g, d.g);
        CHECK_EQ(o.b, d.b);
        CHECK_EQ(o.a, d.a);
    }
}

// 50% grey src (128,128,128,255) over opaque black:
//  Multiply with black -> 0. Screen -> s+d-s*d = s = 128.
//  Overlay on black (d=0): 2*s*d = 0. Add: 128. SoftLight pegtop:
//  (1-2s)d^2+2sd with d=0 -> 0.
//  Grey over 50% grey with Multiply: (128/255)^2*255 = 64.25 -> 64.
TEST(blend_grey_over_black) {
    Pixel s{128, 128, 128, 255}, d{0, 0, 0, 255}, o;
    blendPixel(s, d, BlendMode::Multiply, o);
    CHECK_EQ(o.r, 0);
    Pixel mid{128, 128, 128, 255};
    blendPixel(s, mid, BlendMode::Multiply, o);
    CHECK_EQ(o.r, 64);
    blendPixel(s, d, BlendMode::Screen, o);
    CHECK_EQ(o.r, 128);
    blendPixel(s, d, BlendMode::Overlay, o);
    CHECK_EQ(o.r, 0);
    blendPixel(s, d, BlendMode::Add, o);
    CHECK_EQ(o.r, 128);
    blendPixel(s, d, BlendMode::SoftLight, o);
    CHECK_EQ(o.r, 0);
}

// 50% grey over opaque white (255):
//  Multiply -> 128. Screen -> 255+255-255*255/255... s+d-s*d = 1 -> 255.
//  Overlay on white (d=1): 1-2(1-s)(1-d) = 1 -> 255.
TEST(blend_grey_over_white) {
    Pixel s{128, 128, 128, 255}, d{255, 255, 255, 255}, o;
    blendPixel(s, d, BlendMode::Multiply, o);
    CHECK_EQ(o.r, 128);
    blendPixel(s, d, BlendMode::Screen, o);
    CHECK_EQ(o.r, 255);
    blendPixel(s, d, BlendMode::Overlay, o);
    CHECK_EQ(o.r, 255);
}

// Half-alpha white (127 premult ~ 0.498 straight... use 128,128,128,128:
// straight = 1.0 white at a=0.502) over opaque black, Normal:
// oa = 0.502, color = 1.0*0.502 + 0 = 0.502 -> lround(128.05) = 128.
TEST(blend_half_white_over_black) {
    Pixel s{128, 128, 128, 128}, d{0, 0, 0, 255}, o;
    blendPixel(s, d, BlendMode::Normal, o);
    CHECK_EQ(o.a, 255);
    CHECK_EQ(o.r, 128);
    CHECK_EQ(o.g, 128);
    CHECK_EQ(o.b, 128);
}

// Add clamps: opaque white + opaque white stays 255, alpha 255.
TEST(blend_add_clamp) {
    Pixel s{255, 255, 255, 255}, d{255, 255, 255, 255}, o;
    blendPixel(s, d, BlendMode::Add, o);
    CHECK_EQ(o.r, 255);
    CHECK_EQ(o.a, 255);
}

// SoftLight with mid src over mid dst is a fixed point-ish check:
// s=d=128/255=0.50196; pegtop = (1-2s)d^2+2sd = d (s=0.5 exactly).
// 128/255 = 0.50196, slightly above 0.5, expect 128 or 129.
TEST(blend_softlight_mid) {
    Pixel s{128, 128, 128, 255}, d{128, 128, 128, 255}, o;
    blendPixel(s, d, BlendMode::SoftLight, o);
    CHECK(o.r == 128 || o.r == 129);
    CHECK_EQ(o.a, 255);
}

// Name round-trips incl. ORA spellings.
TEST(blend_names) {
    CHECK(blendFromName("svg:multiply") == BlendMode::Multiply);
    CHECK(blendFromName("multiply") == BlendMode::Multiply);
    CHECK(blendFromName("svg:plus") == BlendMode::Add);
    CHECK(blendFromName("svg:soft-light") == BlendMode::SoftLight);
    CHECK(blendFromName("svg:normal") == BlendMode::Normal);
    CHECK(blendFromName("bogus") == BlendMode::Normal);
    CHECK(blendFromName(nullptr) == BlendMode::Normal);
    CHECK(std::string(blendToName(BlendMode::Multiply)) == "svg:multiply");
    CHECK(std::string(blendToName(BlendMode::Add)) == "svg:plus");
}

// V+2: difference of a pixel with itself is black (opaque kept);
// HardLight extremes act as black/white (2sd / 1-2(1-s)(1-d)).
TEST(blend_difference_self_is_black) {
    Pixel s{200, 100, 50, 255}, d{200, 100, 50, 255}, o;
    blendPixel(s, d, BlendMode::Difference, o);
    CHECK_EQ(o.r, 0);
    CHECK_EQ(o.g, 0);
    CHECK_EQ(o.b, 0);
    CHECK_EQ(o.a, 255);
    Pixel black{0, 0, 0, 255}, white{255, 255, 255, 255}, x{64, 128, 192,
                                                            255};
    Pixel hb, hw;
    blendPixel(black, x, BlendMode::HardLight, hb);
    blendPixel(white, x, BlendMode::HardLight, hw);
    CHECK_EQ(hb.r, 0);
    CHECK_EQ(hb.g, 0);
    CHECK_EQ(hb.b, 0);
    CHECK_EQ(hw.r, 255);
    CHECK_EQ(hw.g, 255);
    CHECK_EQ(hw.b, 255);
}

// V+2: color keeps the destination HSL lightness with the source
// hue/saturation; luminosity keeps the source lightness with the
// destination hue/saturation (HSL round trip, epsilon 2 for float).
TEST(blend_color_luminosity_luma) {
    auto light = [](const Pixel& p) {
        const float r = p.r / 255.0f, g = p.g / 255.0f, b = p.b / 255.0f;
        const float mx = r > g ? (r > b ? r : b) : (g > b ? g : b);
        const float mn = r < g ? (r < b ? r : b) : (g < b ? g : b);
        return (mx + mn) * 0.5f;
    };
    Pixel s{255, 0, 0, 255}, d{0, 0, 255, 255}, c, l;
    blendPixel(s, d, BlendMode::Color, c);
    blendPixel(s, d, BlendMode::Luminosity, l);
    // Color(red, blue): hue stays red, lightness becomes blue's (0.5).
    CHECK(c.r > 200);
    CHECK(c.g < 60);
    CHECK(c.b < 60);
    CHECK(std::abs(light(c) - light(d)) < 2.0f / 255.0f);
    // Luminosity(red, blue): lightness stays red's (0.5), hue blue.
    CHECK(l.b > 200);
    CHECK(l.r < 60);
    CHECK(l.g < 60);
    CHECK(std::abs(light(l) - light(s)) < 2.0f / 255.0f);
    CHECK_EQ(c.a, 255);
    CHECK_EQ(l.a, 255);
}

// V+2: name table covers the new modes (bare + svg: prefix).
TEST(blend_names_v2) {
    CHECK(blendFromName("hard-light") == BlendMode::HardLight);
    CHECK(blendFromName("svg:hard-light") == BlendMode::HardLight);
    CHECK(blendFromName("difference") == BlendMode::Difference);
    CHECK(blendFromName("svg:difference") == BlendMode::Difference);
    CHECK(blendFromName("color") == BlendMode::Color);
    CHECK(blendFromName("luminosity") == BlendMode::Luminosity);
    CHECK(std::string(blendToName(BlendMode::HardLight)) ==
          "svg:hard-light");
    CHECK(std::string(blendToName(BlendMode::Difference)) ==
          "svg:difference");
    CHECK(std::string(blendToName(BlendMode::Color)) == "svg:color");
    CHECK(std::string(blendToName(BlendMode::Luminosity)) ==
          "svg:luminosity");
}
