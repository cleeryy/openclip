// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip T1 engine — figure stamp tests.
#include "test.h"

#include <cmath>
#include <string>
#include <vector>

#include "openclip/shapes.h"
#include "openclip/surface.h"

using openclip::Pixel;
using openclip::ShapeSpec;
using openclip::Surface;

namespace {

ShapeSpec rectFill(float x0, float y0, float x1, float y1) {
    ShapeSpec sp;
    sp.kind = "rect";
    sp.x0 = x0;
    sp.y0 = y0;
    sp.x1 = x1;
    sp.y1 = y1;
    sp.fillOn = true;
    sp.fillR = 200;
    sp.fillG = 20;
    sp.fillB = 20;
    sp.strokeOn = false;
    return sp;
}

bool rgbLeAlpha(const Surface& s) {
    for (int y = 0; y < s.height(); ++y)
        for (int x = 0; x < s.width(); ++x) {
            const auto p = s.get(x, y);
            if (p.r > p.a || p.g > p.a || p.b > p.a) return false;
        }
    return true;
}

}  // namespace

TEST(shape_rect_fills_interior) {
    Surface s(32, 32);
    std::string err;
    CHECK(openclip::stampShape(s, rectFill(4, 4, 20, 20), nullptr, 0, err));
    CHECK_EQ(s.get(12, 12).r, 200);  // deep interior: full ink
    CHECK_EQ(s.get(12, 12).a, 255);
    CHECK_EQ(s.get(0, 0).a, 0);  // far outside: untouched
    CHECK_EQ(s.get(31, 31).a, 0);
    CHECK(rgbLeAlpha(s));
}

TEST(shape_ellipse_aa) {
    ShapeSpec sp;
    sp.kind = "ellipse";
    sp.x0 = 2;
    sp.y0 = 2;
    sp.x1 = 30;
    sp.y1 = 30;
    sp.fillOn = true;
    sp.fillR = 0;
    sp.fillG = 0;
    sp.fillB = 200;
    Surface s(32, 32);
    std::string err;
    CHECK(openclip::stampShape(s, sp, nullptr, 0, err));
    CHECK_EQ(s.get(16, 16).b, 200);  // center: full ink
    // The rim must carry partial coverage (4x4 supersampling), not a
    // hard staircase: some pixel on the circle diagonal is partial.
    bool partial = false;
    for (int k = 0; k < 32 && !partial; ++k)
        for (int l = 0; l < 32 && !partial; ++l) {
            const auto p = s.get(k, l);
            if (p.a > 0 && p.a < 255) partial = true;
        }
    CHECK(partial);
    CHECK(rgbLeAlpha(s));
}

TEST(shape_polygon_sides) {
    // Hexagon: 6-fold symmetry around the center — sample 6 lobes at
    // radius*0.95 (must be inked) and 6 gaps just outside (untouched).
    ShapeSpec sp;
    sp.kind = "polygon";
    sp.x0 = 0;
    sp.y0 = 0;
    sp.x1 = 64;
    sp.y1 = 64;
    sp.sides = 6;
    sp.rotation = 0;
    sp.fillOn = true;
    sp.fillR = 200;
    sp.fillG = 200;
    sp.fillB = 0;
    Surface s(65, 65);
    std::string err;
    CHECK(openclip::stampShape(s, sp, nullptr, 0, err));
    const double cx = 32.0, cy = 32.0;
    for (int k = 0; k < 6; ++k) {
        const double a = 2.0 * 3.141592653589793 * k / 6.0;
        const int lx = (int)std::lround(cx + 28.0 * std::cos(a));
        const int ly = (int)std::lround(cy + 28.0 * std::sin(a));
        CHECK(s.get(lx, ly).a > 127);  // lobe inked
        const double ga = a + 3.141592653589793 / 6.0;
        const int gx = (int)std::lround(cx + 33.0 * std::cos(ga));
        const int gy = (int)std::lround(cy + 33.0 * std::sin(ga));
        if (gx >= 0 && gx < 65 && gy >= 0 && gy < 65)
            CHECK_EQ(s.get(gx, gy).a, 0);  // gap outside: untouched
    }
    CHECK(rgbLeAlpha(s));
}

TEST(shape_stroke_width) {
    // Wide stroked rect, no fill: band thickness ~ strokeW +/-1px.
    ShapeSpec sp = rectFill(8, 8, 24, 24);
    sp.fillOn = false;
    sp.strokeOn = true;
    sp.strokeW = 6;
    sp.strokeR = 0;
    sp.strokeG = 200;
    sp.strokeB = 0;
    Surface s(32, 32);
    std::string err;
    CHECK(openclip::stampShape(s, sp, nullptr, 0, err));
    // Top edge at y=8: the band spans y in [5,11]; measure the inked
    // run at x=16 over the top half only (alpha > 127 counts as inked).
    int run = 0;
    for (int y = 0; y < 16; ++y)
        if (s.get(16, y).a > 127) ++run;
    CHECK(run >= 5 && run <= 8);  // 6 +/- 1 (AA fringe)
    CHECK_EQ(s.get(16, 16).a, 0);  // hollow center
    CHECK(rgbLeAlpha(s));
}

TEST(shape_respects_mask) {
    Surface s(32, 32);
    std::vector<std::uint8_t> mask(32 * 32, 0);
    for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 32; ++x) mask[y * 32 + x] = 255;
    std::string err;
    CHECK(openclip::stampShape(s, rectFill(4, 4, 20, 20), mask.data(), 32,
                               err));
    CHECK_EQ(s.get(12, 8).a, 255);  // selected: painted
    CHECK_EQ(s.get(12, 20).a, 0);   // outside: byte-identical
}

TEST(shape_degenerate_no_op) {
    Surface s(16, 16);
    const auto ref = s.bytes();
    std::string err;
    ShapeSpec zero = rectFill(5, 5, 5, 5);  // zero-area frame
    CHECK(!openclip::stampShape(s, zero, nullptr, 0, err));
    CHECK(!err.empty());
    CHECK(s.bytes() == ref);
    ShapeSpec bad = rectFill(2, 2, 10, 10);
    bad.kind = "star";
    CHECK(!openclip::stampShape(s, bad, nullptr, 0, err));
    CHECK(s.bytes() == ref);
    ShapeSpec poly = rectFill(2, 2, 10, 10);
    poly.kind = "polygon";
    poly.sides = 2;
    CHECK(!openclip::stampShape(s, poly, nullptr, 0, err));
    CHECK(s.bytes() == ref);
    ShapeSpec neither = rectFill(2, 2, 10, 10);
    neither.fillOn = false;  // stroke already off
    CHECK(!openclip::stampShape(s, neither, nullptr, 0, err));
    CHECK(s.bytes() == ref);
    ShapeSpec wide = rectFill(2, 2, 10, 10);
    wide.strokeOn = true;
    wide.strokeW = 0;
    CHECK(!openclip::stampShape(s, wide, nullptr, 0, err));
    CHECK(s.bytes() == ref);
    Surface empty;
    CHECK(!openclip::stampShape(empty, rectFill(0, 0, 8, 8), nullptr, 0,
                                err));
}
