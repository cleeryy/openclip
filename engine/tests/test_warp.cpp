// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip T4 — warp (persp/skew/bulge) + puppet tests
// (ARCHITECTURE.md "T4 additions" §1).
#include "test.h"

#include "openclip/puppet.h"
#include "openclip/surface.h"
#include "openclip/warp.h"

using openclip::blitWarp;
using openclip::Pixel;
using openclip::PuppetPin;
using openclip::puppetWarp;
using openclip::Surface;
using openclip::WarpSpec;

namespace {

// 16x16: white, red 4x4 square at (4..7, 4..7). Mask selects it.
void fixture(Surface& src, std::vector<std::uint8_t>& mask) {
    src.resize(16, 16);
    for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 16; ++x)
            src.set(x, y, Pixel{255, 255, 255, 255});
    for (int y = 4; y <= 7; ++y)
        for (int x = 4; x <= 7; ++x)
            src.set(x, y, Pixel{255, 0, 0, 255});
    mask.assign(256, 0);
    for (int y = 4; y <= 7; ++y)
        for (int x = 4; x <= 7; ++x) mask[y * 16 + x] = 255;
}

int redCount(const Surface& s) {
    int n = 0;
    for (int y = 0; y < s.height(); ++y)
        for (int x = 0; x < s.width(); ++x) {
            Pixel p = s.get(x, y);
            if (p.r == 255 && p.g == 0 && p.b == 0 && p.a == 255) ++n;
        }
    return n;
}

}  // namespace

// Identity quad = byte-identical (homography of the bbox onto itself).
TEST(warp_persp_identity_byte_identical) {
    Surface src;
    std::vector<std::uint8_t> mask;
    fixture(src, mask);
    WarpSpec t;
    t.mode = "persp";
    t.quadSet = true;
    t.quad[0][0] = 4;
    t.quad[0][1] = 4;
    t.quad[1][0] = 8;
    t.quad[1][1] = 4;
    t.quad[2][0] = 8;
    t.quad[2][1] = 8;
    t.quad[3][0] = 4;
    t.quad[3][1] = 8;
    Surface dst;
    CHECK(blitWarp(dst, src, mask, t));
    CHECK(dst.bytes() == src.bytes());
}

// Degenerate quad (doubled points / zero area) = false + untouched.
TEST(warp_persp_degenerate_false_intact) {
    Surface src;
    std::vector<std::uint8_t> mask;
    fixture(src, mask);
    WarpSpec t;
    t.mode = "persp";
    t.quadSet = true;
    Surface dst;
    const std::vector<std::uint8_t> before = src.bytes();
    // Doubled points.
    t.quad[0][0] = 4;
    t.quad[0][1] = 4;
    t.quad[1][0] = 4;
    t.quad[1][1] = 4;
    t.quad[2][0] = 8;
    t.quad[2][1] = 8;
    t.quad[3][0] = 4;
    t.quad[3][1] = 8;
    CHECK(!blitWarp(dst, src, mask, t));
    CHECK(src.bytes() == before);
    // Collinear (zero area).
    t.quad[0][0] = 0;
    t.quad[0][1] = 0;
    t.quad[1][0] = 4;
    t.quad[1][1] = 0;
    t.quad[2][0] = 8;
    t.quad[2][1] = 0;
    t.quad[3][0] = 12;
    t.quad[3][1] = 0;
    CHECK(!blitWarp(dst, src, mask, t));
    CHECK(src.bytes() == before);
    // Missing quad.
    WarpSpec noQuad;
    noQuad.mode = "persp";
    CHECK(!blitWarp(dst, src, mask, noQuad));
}

// Persp shift: quad = bbox + (2,1) moves the square, clears the old one.
TEST(warp_persp_shift_moves_square) {
    Surface src;
    std::vector<std::uint8_t> mask;
    fixture(src, mask);
    WarpSpec t;
    t.mode = "persp";
    t.quadSet = true;
    t.quad[0][0] = 6;
    t.quad[0][1] = 5;
    t.quad[1][0] = 10;
    t.quad[1][1] = 5;
    t.quad[2][0] = 10;
    t.quad[2][1] = 9;
    t.quad[3][0] = 6;
    t.quad[3][1] = 9;
    Surface dst;
    CHECK(blitWarp(dst, src, mask, t));
    CHECK_EQ(dst.get(4, 4).a, 0);  // old square cleared
    CHECK_EQ(redCount(dst), 16);   // square preserved (translation)
}

// Skew 0/0 = byte-identical; skew moves pixels; clamp ±60.
TEST(warp_skew_neutral_identical) {
    Surface src;
    std::vector<std::uint8_t> mask;
    fixture(src, mask);
    WarpSpec t;
    t.mode = "skew";
    Surface dst;
    CHECK(blitWarp(dst, src, mask, t));
    CHECK(dst.bytes() == src.bytes());
}

TEST(warp_skew_shear_moves_and_clamps) {
    Surface src;
    std::vector<std::uint8_t> mask;
    fixture(src, mask);
    WarpSpec t;
    t.mode = "skew";
    t.ax = 45.0;  // shear x by tan45 = 1 per row off-center
    Surface dst;
    CHECK(blitWarp(dst, src, mask, t));
    CHECK(!(dst.bytes() == src.bytes()));  // something moved
    // Out-of-range shear is clamped, not rejected.
    WarpSpec big = t;
    big.ax = 80.0;
    Surface dst2;
    CHECK(blitWarp(dst2, src, mask, big));
    WarpSpec clamped = t;
    clamped.ax = 60.0;
    Surface dst3;
    CHECK(blitWarp(dst3, src, mask, clamped));
    CHECK(dst2.bytes() == dst3.bytes());
}

// Bulge 0 = byte-identical; ±1 bounded (no crash at edges).
TEST(warp_bulge_neutral_identical) {
    Surface src;
    std::vector<std::uint8_t> mask;
    fixture(src, mask);
    WarpSpec t;
    t.mode = "bulge";
    Surface dst;
    CHECK(blitWarp(dst, src, mask, t));
    CHECK(dst.bytes() == src.bytes());
}

TEST(warp_bulge_full_bounded) {
    Surface src;
    std::vector<std::uint8_t> mask;
    fixture(src, mask);
    for (double amt : {-1.0, 1.0}) {
        WarpSpec t;
        t.mode = "bulge";
        t.amount = amt;
        t.radius = 1.0;
        Surface dst;
        CHECK(blitWarp(dst, src, mask, t));
        CHECK_EQ(dst.bytes().size(), src.bytes().size());
    }
    // amount out of range = false + intact.
    WarpSpec bad;
    bad.mode = "bulge";
    bad.amount = 2.0;
    Surface dst;
    const std::vector<std::uint8_t> before = src.bytes();
    CHECK(!blitWarp(dst, src, mask, bad));
    CHECK(src.bytes() == before);
    // radius clamps into [0.1,1].
    WarpSpec r0;
    r0.mode = "bulge";
    r0.amount = 0.5;
    r0.radius = 0.0;
    WarpSpec r1 = r0;
    r1.radius = 0.1;
    Surface d0, d1;
    CHECK(blitWarp(d0, src, mask, r0));
    CHECK(blitWarp(d1, src, mask, r1));
    CHECK(d0.bytes() == d1.bytes());
    // Unknown mode = false.
    WarpSpec um;
    um.mode = "twirl";
    CHECK(!blitWarp(dst, src, mask, um));
}

// Puppet: 0 pins = identical; 1 pin = local move, far field intact.
TEST(puppet_zero_pins_identical) {
    Surface src;
    src.resize(16, 16);
    for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 16; ++x)
            src.set(x, y, Pixel{static_cast<std::uint8_t>(x * 16),
                                static_cast<std::uint8_t>(y * 16), 128, 255});
    const std::vector<std::uint8_t> before = src.bytes();
    Surface dst;
    CHECK(puppetWarp(dst, src, {}, 4.0));
    CHECK(dst.bytes() == before);
}

TEST(puppet_single_pin_local) {
    Surface src;
    src.resize(32, 32);
    for (int y = 0; y < 32; ++y)
        for (int x = 0; x < 32; ++x) src.set(x, y, Pixel{255, 255, 255, 255});
    src.set(8, 8, Pixel{255, 0, 0, 255});  // red dot near the pin
    PuppetPin pin;
    pin.x = 8;
    pin.y = 8;
    pin.dx = 4;
    pin.dy = 0;
    Surface dst;
    CHECK(puppetWarp(dst, src, {pin}, 8.0));
    // The red dot moved right (bilinear keeps it reddish near target).
    Pixel at = dst.get(12, 8);
    CHECK(at.r > 128 && at.g < 200);
    // Far corner intact.
    CHECK_EQ(dst.get(31, 31).r, 255);
    CHECK_EQ(dst.get(31, 31).g, 255);
    CHECK_EQ(dst.get(0, 31).r, 255);
}

TEST(puppet_limits) {
    Surface src;
    src.resize(8, 8);
    for (int y = 0; y < 8; ++y)
        for (int x = 0; x < 8; ++x)
            src.set(x, y, Pixel{10, 20, 30, 255});
    const std::vector<std::uint8_t> before = src.bytes();
    Surface dst;
    // 65 pins = false, untouched.
    std::vector<PuppetPin> many(65);
    CHECK(!puppetWarp(dst, src, many, 4.0));
    CHECK(src.bytes() == before);
    // Off-canvas pins ignored -> identical.
    PuppetPin off;
    off.x = -50;
    off.y = -50;
    off.dx = 10;
    off.dy = 10;
    CHECK(puppetWarp(dst, src, {off}, 4.0));
    CHECK(dst.bytes() == before);
    // Empty surface = false.
    Surface empty;
    CHECK(!puppetWarp(dst, empty, {}, 4.0));
}
