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

// T5: the ten new modes — names (bare + svg: + snake) and invariants.
TEST(blend_names_t5) {
    CHECK(blendFromName("darken") == BlendMode::Darken);
    CHECK(blendFromName("svg:darken") == BlendMode::Darken);
    CHECK(blendFromName("lighten") == BlendMode::Lighten);
    CHECK(blendFromName("linear-burn") == BlendMode::LinearBurn);
    CHECK(blendFromName("svg:linear-burn") == BlendMode::LinearBurn);
    CHECK(blendFromName("linear_burn") == BlendMode::LinearBurn);
    CHECK(blendFromName("linear-dodge") == BlendMode::LinearDodge);
    CHECK(blendFromName("linear_dodge") == BlendMode::LinearDodge);
    CHECK(blendFromName("vivid-light") == BlendMode::VividLight);
    CHECK(blendFromName("svg:vivid-light") == BlendMode::VividLight);
    CHECK(blendFromName("vivid_light") == BlendMode::VividLight);
    CHECK(blendFromName("linear-light") == BlendMode::LinearLight);
    CHECK(blendFromName("pin-light") == BlendMode::PinLight);
    CHECK(blendFromName("pin_light") == BlendMode::PinLight);
    CHECK(blendFromName("subtract") == BlendMode::Subtract);
    CHECK(blendFromName("divide") == BlendMode::Divide);
    CHECK(blendFromName("exclusion") == BlendMode::Exclusion);
    CHECK(std::string(blendToName(BlendMode::Darken)) == "svg:darken");
    CHECK(std::string(blendToName(BlendMode::Lighten)) == "svg:lighten");
    CHECK(std::string(blendToName(BlendMode::LinearBurn)) ==
          "svg:linear-burn");
    CHECK(std::string(blendToName(BlendMode::LinearDodge)) ==
          "svg:linear-dodge");
    CHECK(std::string(blendToName(BlendMode::VividLight)) ==
          "svg:vivid-light");
    CHECK(std::string(blendToName(BlendMode::LinearLight)) ==
          "svg:linear-light");
    CHECK(std::string(blendToName(BlendMode::PinLight)) == "svg:pin-light");
    CHECK(std::string(blendToName(BlendMode::Subtract)) == "svg:subtract");
    CHECK(std::string(blendToName(BlendMode::Divide)) == "svg:divide");
    CHECK(std::string(blendToName(BlendMode::Exclusion)) ==
          "svg:exclusion");
}

// T5: darken/lighten are commutative per channel (min/max).
TEST(blend_darken_lighten_commutative) {
    Pixel s{200, 50, 128, 255}, d{100, 180, 64, 255}, o1, o2;
    blendPixel(s, d, BlendMode::Darken, o1);
    blendPixel(d, s, BlendMode::Darken, o2);
    CHECK_EQ(o1.r, 100);
    CHECK_EQ(o1.g, 50);
    CHECK_EQ(o1.b, 64);
    CHECK_EQ(o1.r, o2.r);
    CHECK_EQ(o1.g, o2.g);
    CHECK_EQ(o1.b, o2.b);
    blendPixel(s, d, BlendMode::Lighten, o1);
    blendPixel(d, s, BlendMode::Lighten, o2);
    CHECK_EQ(o1.r, 200);
    CHECK_EQ(o1.g, 180);
    CHECK_EQ(o1.b, 128);
    CHECK_EQ(o1.r, o2.r);
    CHECK_EQ(o1.g, o2.g);
    CHECK_EQ(o1.b, o2.b);
}

// T5: linear-dodge is the documented twin of Add (same clamped sum).
TEST(blend_linear_dodge_twins_add) {
    Pixel s{200, 100, 30, 255}, d{100, 180, 240, 255}, o1, o2;
    blendPixel(s, d, BlendMode::Add, o1);
    blendPixel(s, d, BlendMode::LinearDodge, o2);
    CHECK_EQ(o1.r, o2.r);
    CHECK_EQ(o1.g, o2.g);
    CHECK_EQ(o1.b, o2.b);
    CHECK_EQ(o1.r, 255);  // 200+100 clamps
    CHECK_EQ(o1.g, 255);  // 100+180 clamps
    CHECK_EQ(o1.b, 255);  // 30+240 clamps
}

// T5: subtract clamps at zero (unlike Difference which mirrors);
// divide by white is identity.
TEST(blend_subtract_divide) {
    Pixel s{200, 200, 200, 255}, d{100, 100, 100, 255}, o;
    blendPixel(s, d, BlendMode::Subtract, o);
    CHECK_EQ(o.r, 0);
    blendPixel(s, d, BlendMode::Difference, o);
    CHECK_EQ(o.r, 100);  // |200-100| mirrors, subtract clamps
    Pixel w{255, 255, 255, 255}, g{128, 64, 32, 255};
    blendPixel(w, g, BlendMode::Divide, o);
    CHECK_EQ(o.r, 128);
    CHECK_EQ(o.g, 64);
    CHECK_EQ(o.b, 32);
    Pixel blk{0, 0, 0, 255};
    blendPixel(blk, g, BlendMode::Divide, o);
    CHECK_EQ(o.r, 255);  // divide by zero (d>0) -> white
    blendPixel(blk, blk, BlendMode::Divide, o);
    CHECK_EQ(o.r, 0);  // 0/0 -> black
}

// T5: exclusion with 50% gray over 50% gray keeps gray; black src is
// the neutral (identity), like Normal.
TEST(blend_exclusion_neutral) {
    Pixel g{128, 128, 128, 255}, o;
    blendPixel(g, g, BlendMode::Exclusion, o);
    // 2*s*(1-s) with s=128/255 lands one LSB under 128 (locked: 127).
    CHECK_EQ(o.r, 127);
    Pixel blk{0, 0, 0, 255}, d{90, 140, 200, 255};
    blendPixel(blk, d, BlendMode::Exclusion, o);
    CHECK_EQ(o.r, d.r);
    CHECK_EQ(o.g, d.g);
    CHECK_EQ(o.b, d.b);
}

// T5: mid-gray source is the documented neutral for vivid, linear
// and pin light (identity on any destination).
TEST(blend_midgray_neutral_lights) {
    Pixel mid{128, 128, 128, 255}, d{90, 140, 200, 255}, o;
    for (auto m : {BlendMode::VividLight, BlendMode::LinearLight,
                   BlendMode::PinLight}) {
        blendPixel(mid, d, m, o);
        // 128/255 = 0.502: vivid -> burn(1.004->d) = d; linear ->
        // d + 0.004 -> off by one at most; pin -> min(1.004, d) = d.
        CHECK(std::abs((int)o.r - (int)d.r) <= 1);
        CHECK(std::abs((int)o.g - (int)d.g) <= 1);
        CHECK(std::abs((int)o.b - (int)d.b) <= 1);
    }
    // linear-burn neutral is white, subtract neutral is black.
    Pixel w{255, 255, 255, 255}, blk{0, 0, 0, 255};
    blendPixel(w, d, BlendMode::LinearBurn, o);
    CHECK_EQ(o.r, d.r);
    CHECK_EQ(o.g, d.g);
    CHECK_EQ(o.b, d.b);
    blendPixel(blk, d, BlendMode::Subtract, o);
    CHECK_EQ(o.r, d.r);
    CHECK_EQ(o.g, d.g);
    CHECK_EQ(o.b, d.b);
}

// T5: transparent source stays a no-op for every new mode.
TEST(blend_transparent_src_noop_t5) {
    Pixel s{0, 0, 0, 0}, d{10, 20, 30, 200}, o;
    for (auto m : {BlendMode::Darken, BlendMode::Lighten,
                   BlendMode::LinearBurn, BlendMode::LinearDodge,
                   BlendMode::VividLight, BlendMode::LinearLight,
                   BlendMode::PinLight, BlendMode::Subtract,
                   BlendMode::Divide, BlendMode::Exclusion}) {
        blendPixel(s, d, m, o);
        CHECK_EQ(o.r, d.r);
        CHECK_EQ(o.g, d.g);
        CHECK_EQ(o.b, d.b);
        CHECK_EQ(o.a, d.a);
    }
}
