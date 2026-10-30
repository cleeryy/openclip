// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip B2 engine — brush tip masks (ARCHITECTURE.md "B2 additions"
// §3): alpha extraction, masked stamping, aspect, alpha lock, clipping.
#include "test.h"

#include <cstdint>
#include <vector>

#include "openclip/brush.h"
#include "openclip/brush_tip.h"
#include "openclip/surface.h"

using openclip::BrushParams;
using openclip::BrushTip;
using openclip::Dab;
using openclip::Pixel;
using openclip::Surface;

namespace {

std::vector<std::uint8_t> rgbaOf(const std::vector<std::uint8_t>& alpha,
                                 int n) {
    std::vector<std::uint8_t> out(static_cast<std::size_t>(n) * 4, 0);
    for (int i = 0; i < n; ++i) out[static_cast<std::size_t>(i) * 4 + 3] = alpha[i];
    return out;
}

Dab dabAt(float x, float y, float radius) {
    Dab d;
    d.x = x;
    d.y = y;
    d.radius = radius;
    d.opacity = 1.0f;
    d.pressure = 1.0f;
    d.r = 20;
    d.g = 40;
    d.b = 60;
    return d;
}

int inkCount(const Surface& s) {
    int n = 0;
    for (int y = 0; y < s.height(); ++y)
        for (int x = 0; x < s.width(); ++x)
            if (s.get(x, y).a > 0) ++n;
    return n;
}

}  // namespace

TEST(brush_tip_extracts_alpha_only) {
    const std::vector<std::uint8_t> px = rgbaOf({0, 255, 128, 255}, 4);
    BrushTip t;
    CHECK(openclip::brushTipFromRgba(px.data(), 2, 2, t));
    CHECK(t.valid());
    CHECK_EQ(t.w, 2);
    CHECK_EQ(t.h, 2);
    CHECK_EQ(static_cast<int>(t.alpha[1]), 255);
    CHECK_EQ(static_cast<int>(t.alpha[2]), 128);
    // All-transparent masks are refused (they would paint nothing).
    const std::vector<std::uint8_t> zero = rgbaOf({0, 0, 0, 0}, 4);
    BrushTip refused;
    CHECK(!openclip::brushTipFromRgba(zero.data(), 2, 2, refused));
    CHECK(!refused.valid());
    CHECK(!openclip::brushTipFromRgba(px.data(), 0, 2, refused));
    CHECK(!openclip::brushTipFromRgba(nullptr, 2, 2, refused));
}

TEST(brush_tip_stamps_mask_shape) {
    // Left half opaque, right half empty (row-major 4x2): the stamp
    // must be a half disc, never a full one.
    const std::vector<std::uint8_t> px =
        rgbaOf({255, 255, 0, 0, 255, 255, 0, 0}, 8);
    BrushTip t;
    CHECK(openclip::brushTipFromRgba(px.data(), 4, 2, t));
    Surface s(64, 64);
    openclip::paintTipDab(s, dabAt(32, 32, 8), BrushParams{}, t);
    int left = 0, right = 0;
    for (int y = 0; y < 64; ++y)
        for (int x = 0; x < 64; ++x) {
            if (s.get(x, y).a == 0) continue;
            if (x < 32) ++left;
            else ++right;
        }
    CHECK(left > 20);
    CHECK_EQ(right, 0);  // the empty half never paints
    const Pixel p = s.get(26, 30);  // inside the opaque half
    CHECK_EQ(static_cast<int>(p.a), 255);
    CHECK(p.r >= 15 && p.r <= 22);  // straight-ish ink at full coverage
    CHECK(p.r <= p.a && p.g <= p.a && p.b <= p.a);
}

TEST(brush_tip_keeps_aspect_and_clips) {
    // A wide 8x2 mask stays wide when stamped.
    std::vector<std::uint8_t> alpha(16, 255);
    const std::vector<std::uint8_t> px = rgbaOf(alpha, 16);
    BrushTip t;
    CHECK(openclip::brushTipFromRgba(px.data(), 8, 2, t));
    Surface s(64, 64);
    openclip::paintTipDab(s, dabAt(32, 32, 8), BrushParams{}, t);
    int minX = 64, maxX = -1, minY = 64, maxY = -1;
    for (int y = 0; y < 64; ++y)
        for (int x = 0; x < 64; ++x) {
            if (s.get(x, y).a == 0) continue;
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }
    CHECK(maxX - minX > (maxY - minY) * 2);
    // A stamp at the canvas corner is clipped, never out of bounds.
    Surface corner(16, 16);
    openclip::paintTipDab(corner, dabAt(0, 0, 6), BrushParams{}, t);
    CHECK(inkCount(corner) > 0);
    CHECK(inkCount(corner) < inkCount(s));
}

TEST(brush_tip_alpha_lock_and_identity) {
    const std::vector<std::uint8_t> px = rgbaOf({255, 255, 255, 255}, 4);
    BrushTip t;
    CHECK(openclip::brushTipFromRgba(px.data(), 2, 2, t));
    Surface s(32, 32);
    s.set(16, 16, Pixel{0, 0, 0, 255});  // one covered pixel only
    openclip::paintTipDab(s, dabAt(16, 16, 6), BrushParams{}, t, true);
    int painted = 0;
    for (int y = 0; y < 32; ++y)
        for (int x = 0; x < 32; ++x)
            if (x != 16 || y != 16) {
                if (s.get(x, y).a != 0) ++painted;
            }
    CHECK_EQ(painted, 0);  // alpha lock: transparent pixels skipped
    const Pixel c = s.get(16, 16);
    CHECK_EQ(static_cast<int>(c.a), 255);
    CHECK(c.r <= c.a && c.g <= c.a && c.b <= c.a);
    // An empty tip is a no-op.
    Surface blank(8, 8);
    BrushTip emptyTip;
    openclip::paintTipDab(blank, dabAt(4, 4, 4), BrushParams{}, emptyTip);
    CHECK_EQ(inkCount(blank), 0);
}
