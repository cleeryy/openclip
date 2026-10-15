// SPDX-License-Identifier: GPL-3.0-or-later
// Blend kernel tests with hand-computed pixel values.
// Convention: premultiplied inputs. Hand checks computed with the same
// float pipeline (un-premult -> blend straight -> over -> lround).
#include "test.h"

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
