// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip V+2 — selection transform tests (ARCHITECTURE.md "V+2").
#include "test.h"

#include "openclip/surface.h"
#include "openclip/transform.h"

using openclip::blitSelection;
using openclip::Pixel;
using openclip::selectionBBox;
using openclip::Surface;
using openclip::TransformSpec;

namespace {

// 8x8 surface: white, red 2x2 square at (2..3, 2..3). Mask selects the
// square exactly.
void fixture(Surface& src, std::vector<std::uint8_t>& mask) {
    src.resize(8, 8);
    for (int y = 0; y < 8; ++y)
        for (int x = 0; x < 8; ++x)
            src.set(x, y, Pixel{255, 255, 255, 255});
    for (int y = 2; y <= 3; ++y)
        for (int x = 2; x <= 3; ++x)
            src.set(x, y, Pixel{255, 0, 0, 255});
    mask.assign(64, 0);
    for (int y = 2; y <= 3; ++y)
        for (int x = 2; x <= 3; ++x) mask[y * 8 + x] = 255;
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

// Move translate (+2,+1): the square relocates exactly, the source
// region is cleared to transparent, nothing else changes.
TEST(transform_move_translate_exact) {
    Surface src;
    std::vector<std::uint8_t> mask;
    fixture(src, mask);
    TransformSpec t;
    t.dx = 2;
    t.dy = 1;
    Surface dst;
    CHECK(blitSelection(dst, src, mask, t));
    // Old square cleared...
    for (int y = 2; y <= 3; ++y)
        for (int x = 2; x <= 3; ++x) CHECK_EQ(dst.get(x, y).a, 0);
    // ...new square painted at (4..5, 3..4).
    for (int y = 3; y <= 4; ++y)
        for (int x = 4; x <= 5; ++x) {
            Pixel p = dst.get(x, y);
            CHECK_EQ(p.r, 255);
            CHECK_EQ(p.g, 0);
            CHECK_EQ(p.a, 255);
        }
    CHECK_EQ(redCount(dst), 4);
}

// Scale x2 (free mode): the 2x2 square covers 4x4 with no holes.
TEST(transform_scale_double_no_holes) {
    Surface src;
    std::vector<std::uint8_t> mask;
    fixture(src, mask);
    TransformSpec t;
    t.mode = "free";
    t.scale = 2.0;
    Surface dst;
    CHECK(blitSelection(dst, src, mask, t));
    // bbox center (2.5,2.5), 2x2 -> 4x4 block at (1..4, 1..4).
    int red = 0;
    for (int y = 1; y <= 4; ++y)
        for (int x = 1; x <= 4; ++x) {
            Pixel p = dst.get(x, y);
            if (p.a != 0) {
                ++red;
                CHECK(p.r > 200);  // red survives bilinear unpremult
            }
        }
    CHECK_EQ(red, 16);
}

// Rotate 90° (free mode): corners of the square permute exactly.
TEST(transform_rotate_90_corners) {
    Surface src;
    std::vector<std::uint8_t> mask;
    fixture(src, mask);
    // Paint corners distinctly: TL=red, TR=green, BL=blue, BR=white-ish
    // gray (opaque) so the rotation is observable.
    src.set(2, 2, Pixel{255, 0, 0, 255});
    src.set(3, 2, Pixel{0, 255, 0, 255});
    src.set(2, 3, Pixel{0, 0, 255, 255});
    src.set(3, 3, Pixel{128, 128, 128, 255});
    TransformSpec t;
    t.mode = "free";
    t.scale = 1.0;
    t.angleDeg = 90.0;
    Surface dst;
    CHECK(blitSelection(dst, src, mask, t));
    // 90° clockwise about (2.5,2.5): (2,2)->(3,2), (3,2)->(3,3),
    // (3,3)->(2,3), (2,3)->(2,2). Bilinear on exact pixels stays exact.
    CHECK_EQ(dst.get(3, 2).r, 255);  // was TL red
    CHECK_EQ(dst.get(3, 3).g, 255);  // was TR green
    CHECK_EQ(dst.get(2, 3).r, 128);  // was BR gray
    CHECK_EQ(dst.get(2, 2).b, 255);  // was BL blue
}

// Identity mesh warp = byte-identical (fixed-point converges, no-op
// offsets sample exact pixels).
TEST(transform_mesh_identity_byte_identical) {
    Surface src;
    std::vector<std::uint8_t> mask;
    fixture(src, mask);
    TransformSpec t;
    t.mode = "mesh";  // all offsets 0
    Surface dst;
    CHECK(blitSelection(dst, src, mask, t));
    CHECK_EQ(dst.bytes().size(), src.bytes().size());
    CHECK(dst.bytes() == src.bytes());
    // Rejections: empty mask, size mismatch, unknown mode.
    std::vector<std::uint8_t> empty(64, 0);
    CHECK(!blitSelection(dst, src, empty, t));
    std::vector<std::uint8_t> bad(10, 255);
    CHECK(!blitSelection(dst, src, bad, t));
    int x0, y0, x1, y1;
    CHECK(!selectionBBox(empty, 8, 8, x0, y0, x1, y1));
    TransformSpec badMode;
    badMode.mode = "warp";
    CHECK(!blitSelection(dst, src, mask, badMode));
}
