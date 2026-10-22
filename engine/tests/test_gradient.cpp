// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip T1 engine — gradient paint tests.
#include "test.h"

#include <cmath>
#include <string>
#include <vector>

#include "openclip/gradient.h"
#include "openclip/surface.h"

using openclip::GradientSpec;
using openclip::GradientStop;
using openclip::Pixel;
using openclip::Surface;

namespace {

GradientSpec linearBW() {
    GradientSpec spec;
    spec.kind = "linear";
    spec.x0 = 0;
    spec.y0 = 0;
    spec.x1 = 32;
    spec.y1 = 0;
    spec.stops = {{0, 0, 0, 0.0f}, {255, 255, 255, 1.0f}};
    spec.dither = false;
    return spec;
}

}  // namespace

TEST(gradient_linear_endpoints) {
    Surface s(33, 4);
    std::string err;
    CHECK(openclip::paintGradient(s, linearBW(), nullptr, 0, err));
    // p0 exact, p1 exact (ramp endpoints, no dither).
    CHECK_EQ(s.get(0, 2).r, 0);
    CHECK_EQ(s.get(32, 2).r, 255);
    CHECK_EQ(s.get(0, 2).a, 255);
    // Midpoint ~ half gray (linear ramp, +/-1 rounding).
    const int mid = s.get(16, 2).r;
    CHECK(std::abs(mid - 127) <= 1 || std::abs(mid - 128) <= 1);
    CHECK_EQ(s.get(16, 2).g, s.get(16, 2).r);
    CHECK_EQ(s.get(16, 2).b, s.get(16, 2).r);
}

TEST(gradient_radial_center_edge) {
    GradientSpec spec;
    spec.kind = "radial";
    spec.x0 = 16;
    spec.y0 = 16;
    spec.x1 = 32;
    spec.y1 = 16;  // radius 16
    spec.stops = {{255, 0, 0, 0.0f}, {0, 0, 255, 1.0f}};
    spec.dither = false;
    Surface s(33, 33);
    std::string err;
    CHECK(openclip::paintGradient(s, spec, nullptr, 0, err));
    CHECK_EQ(s.get(16, 16).r, 255);  // center = first stop
    CHECK_EQ(s.get(16, 16).b, 0);
    CHECK_EQ(s.get(32, 16).b, 255);  // rim = last stop
    CHECK_EQ(s.get(32, 16).r, 0);
    // Half radius ~ half mix.
    const auto mid = s.get(24, 16);
    CHECK(std::abs((int)mid.r - 127) <= 2);
    CHECK(std::abs((int)mid.b - 127) <= 2);
}

TEST(gradient_free_weights) {
    GradientSpec spec;
    spec.kind = "free";
    spec.stops = {{255, 0, 0, 0.0f}, {0, 0, 255, 1.0f}};
    spec.pts = {{0.0f, 8.0f}, {32.0f, 8.0f}};
    spec.dither = false;
    Surface s(33, 17);
    std::string err;
    CHECK(openclip::paintGradient(s, spec, nullptr, 0, err));
    // Near the red anchor: dominantly red.
    const auto near = s.get(2, 8);
    CHECK(near.r > 200);
    CHECK(near.b < 60);
    // Near the blue anchor: dominantly blue.
    const auto far = s.get(30, 8);
    CHECK(far.b > 200);
    CHECK(far.r < 60);
    // Middle: both channels carry weight.
    const auto mid = s.get(16, 8);
    CHECK(mid.r > 80 && mid.b > 80);
}

TEST(gradient_dither_bounded) {
    GradientSpec nod = linearBW();
    GradientSpec dit = linearBW();
    dit.dither = true;
    Surface a(33, 4), b(33, 4);
    std::string err;
    CHECK(openclip::paintGradient(a, nod, nullptr, 0, err));
    CHECK(openclip::paintGradient(b, dit, nullptr, 0, err));
    // Same call twice = same bytes (deterministic LCG, fixed seed).
    Surface c(33, 4);
    CHECK(openclip::paintGradient(c, dit, nullptr, 0, err));
    CHECK(b.bytes() == c.bytes());
    for (int y = 0; y < 4; ++y)
        for (int x = 0; x < 33; ++x)
            CHECK(std::abs((int)b.get(x, y).r - (int)a.get(x, y).r) <= 2);
}

TEST(gradient_respects_mask) {
    Surface s(33, 4);
    std::vector<std::uint8_t> mask(33 * 4, 0);
    for (int y = 0; y < 2; ++y)
        for (int x = 0; x < 33; ++x) mask[y * 33 + x] = 255;
    std::string err;
    CHECK(openclip::paintGradient(s, linearBW(), mask.data(), 33, err));
    CHECK_EQ(s.get(16, 0).a, 255);  // selected: painted
    CHECK_EQ(s.get(16, 3).a, 0);    // outside: byte-identical
}

TEST(gradient_bad_spec_no_op) {
    Surface s(16, 16);
    const auto ref = s.bytes();
    std::string err;
    GradientSpec one = linearBW();
    one.stops.pop_back();  // < 2 stops
    CHECK(!openclip::paintGradient(s, one, nullptr, 0, err));
    CHECK(!err.empty());
    CHECK(s.bytes() == ref);
    GradientSpec bad = linearBW();
    bad.kind = "conic";  // unknown kind
    CHECK(!openclip::paintGradient(s, bad, nullptr, 0, err));
    CHECK(s.bytes() == ref);
    GradientSpec zero = linearBW();
    zero.x1 = zero.x0;
    zero.y1 = zero.y0;  // zero-length segment
    CHECK(!openclip::paintGradient(s, zero, nullptr, 0, err));
    CHECK(s.bytes() == ref);
    GradientSpec rad;
    rad.kind = "radial";
    rad.x0 = rad.y0 = rad.x1 = rad.y1 = 5;  // zero radius
    rad.stops = {{0, 0, 0, 0.0f}, {255, 255, 255, 1.0f}};
    CHECK(!openclip::paintGradient(s, rad, nullptr, 0, err));
    CHECK(s.bytes() == ref);
    GradientSpec free;
    free.kind = "free";
    free.stops = {{255, 0, 0, 0.0f}, {0, 0, 255, 1.0f}};
    free.pts = {{0.0f, 0.0f}};  // size mismatch
    CHECK(!openclip::paintGradient(s, free, nullptr, 0, err));
    CHECK(s.bytes() == ref);
    Surface empty;
    GradientSpec ok = linearBW();
    CHECK(!openclip::paintGradient(empty, ok, nullptr, 0, err));
}
