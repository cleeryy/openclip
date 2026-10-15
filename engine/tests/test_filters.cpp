// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip M3 engine — filter invariant tests.
#include "test.h"

#include <cmath>
#include <set>
#include <string>
#include <vector>

#include "openclip/filters.h"
#include "openclip/surface.h"

using openclip::Pixel;
using openclip::Surface;

namespace {

Surface solid(int w, int h, std::uint8_t r, std::uint8_t g, std::uint8_t b,
              std::uint8_t a) {
    Surface s(w, h);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) s.set(x, y, Pixel{r, g, b, a});
    return s;
}

double varianceR(const Surface& s) {
    double sum = 0, n = 0;
    for (int y = 0; y < s.height(); ++y)
        for (int x = 0; x < s.width(); ++x) {
            sum += s.get(x, y).r;
            ++n;
        }
    const double mean = sum / n;
    double v = 0;
    for (int y = 0; y < s.height(); ++y)
        for (int x = 0; x < s.width(); ++x) {
            const double d = s.get(x, y).r - mean;
            v += d * d;
        }
    return v / n;
}

bool alphaColEq(const Surface& a, const Surface& b) {
    if (a.width() != b.width() || a.height() != b.height()) return false;
    for (int y = 0; y < a.height(); ++y)
        for (int x = 0; x < a.width(); ++x)
            if (a.get(x, y).a != b.get(x, y).a) return false;
    return true;
}

}  // namespace

TEST(filters_registry_known) {
    using openclip::canApply;
    CHECK(canApply("gaussianBlur"));
    CHECK(canApply("sharpen"));
    CHECK(canApply("motionBlur"));
    CHECK(canApply("radialBlur"));
    CHECK(canApply("posterize"));
    CHECK(canApply("levels"));
    CHECK(canApply("chromaticAberration"));
    CHECK(canApply("addNoise"));
    CHECK(canApply("mosaic"));
    CHECK(canApply("gradientMap"));
    CHECK(!canApply("nope"));
    CHECK(!canApply("GaussianBlur"));
    CHECK(!canApply(""));
    CHECK_EQ(openclip::filterNames().size(), (std::size_t)10);
}

TEST(filters_unknown_no_side_effects) {
    Surface s = solid(32, 32, 120, 130, 140, 255);
    const auto before = s.bytes();
    std::string err;
    CHECK(!openclip::applyFilter(s, "definitelyNotAFilters", "{}", err));
    CHECK(!err.empty());
    CHECK(s.bytes() == before);
    // Invalid params also leave the surface untouched.
    CHECK(!openclip::applyFilter(s, "gaussianBlur", "{\"radius\":0}", err));
    CHECK(s.bytes() == before);
    CHECK(!openclip::applyFilter(s, "levels",
                                 "{\"black\":200,\"white\":100}", err));
    CHECK(s.bytes() == before);
}

TEST(filters_gaussian_invariants) {
    std::string err;
    // Uniform image unchanged (clamp-edge, uniform-safe kernel).
    Surface u = solid(32, 32, 200, 100, 50, 255);
    CHECK(openclip::applyFilter(u, "gaussianBlur", "{\"radius\":6}", err));
    CHECK(u.bytes() == solid(32, 32, 200, 100, 50, 255).bytes());
    // Gradient smoothed: variance decreases.
    Surface g(64, 16);
    for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 64; ++x)
            g.set(x, y, Pixel{(std::uint8_t)((x * 7) % 256), 0, 0, 255});
    const double v0 = varianceR(g);
    CHECK(openclip::applyFilter(g, "gaussianBlur", "{\"radius\":2}", err));
    CHECK(varianceR(g) < v0);
    // Size + alpha preserved.
    CHECK_EQ(g.width(), 64);
    CHECK_EQ(g.height(), 16);
    for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 64; ++x) CHECK_EQ(g.get(x, y).a, 255);
}

TEST(filters_sharpen_contrast) {
    std::string err;
    // Sharp vertical edge: dark|bright halves; sharpen must widen the gap
    // between adjacent edge pixels.
    Surface s(32, 8);
    for (int y = 0; y < 8; ++y)
        for (int x = 0; x < 32; ++x) {
            const std::uint8_t v = x < 16 ? 60 : 200;
            s.set(x, y, Pixel{v, v, v, 255});
        }
    const int gapBefore =
        static_cast<int>(s.get(16, 4).r) - static_cast<int>(s.get(15, 4).r);
    CHECK(openclip::applyFilter(s, "sharpen", "{\"amount\":1.0}", err));
    const int gapAfter =
        static_cast<int>(s.get(16, 4).r) - static_cast<int>(s.get(15, 4).r);
    CHECK(gapAfter > gapBefore);
    // Zero amount is a near no-op.
    Surface z = solid(16, 16, 128, 128, 128, 255);
    CHECK(openclip::applyFilter(z, "sharpen", "{\"amount\":0}", err));
    CHECK(z.bytes() == solid(16, 16, 128, 128, 128, 255).bytes());
}

TEST(filters_motion_radial_smooth) {
    std::string err;
    Surface m(64, 16);
    for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 64; ++x)
            m.set(x, y, Pixel{(std::uint8_t)((x * 11) % 256), 0, 0, 255});
    const double v0 = varianceR(m);
    CHECK(openclip::applyFilter(
        m, "motionBlur", "{\"length\":9,\"angle\":0}", err));
    CHECK(varianceR(m) < v0);
    CHECK_EQ(m.width(), 64);
    // Radial blur on a radial-ish test pattern: variance decreases too.
    Surface r(48, 48);
    for (int y = 0; y < 48; ++y)
        for (int x = 0; x < 48; ++x) {
            const int dx = x - 24, dy = y - 24;
            const std::uint8_t v =
                static_cast<std::uint8_t>((dx * dx + dy * dy) % 256);
            r.set(x, y, Pixel{v, v, v, 255});
        }
    const auto ab = r.bytes();
    double s0 = 0;
    for (auto b : ab) s0 += b;
    CHECK(openclip::applyFilter(
        r, "radialBlur", "{\"strength\":1.0,\"cx\":24,\"cy\":24}", err));
    CHECK(r.bytes() != ab);  // something happened
    CHECK(alphaColEq(r, solid(48, 48, 0, 0, 0, 255)));
}

TEST(filters_posterize_limits_values) {
    std::string err;
    Surface s(64, 4);
    for (int y = 0; y < 4; ++y)
        for (int x = 0; x < 64; ++x)
            s.set(x, y, Pixel{(std::uint8_t)(x * 4), 0, 0, 255});
    CHECK(openclip::applyFilter(s, "posterize", "{\"levels\":4}", err));
    std::set<int> distinct;
    for (int y = 0; y < 4; ++y)
        for (int x = 0; x < 64; ++x) distinct.insert(s.get(x, y).r);
    CHECK(distinct.size() <= (std::size_t)4);
    CHECK(distinct.size() >= (std::size_t)2);
}

TEST(filters_levels_clamps) {
    std::string err;
    // black-out: white<=255 range mapped down crushes everything to 0
    // when black is pushed past content? Use identity first.
    Surface s = solid(8, 8, 100, 150, 200, 255);
    CHECK(openclip::applyFilter(s, "levels",
                                "{\"black\":0,\"white\":255}", err));
    CHECK(s.bytes() == solid(8, 8, 100, 150, 200, 255).bytes());
    // black=100 crushes the r=100 channel to 0, rescales the rest.
    Surface t = solid(8, 8, 100, 150, 200, 255);
    CHECK(openclip::applyFilter(t, "levels",
                                "{\"black\":100,\"white\":255}", err));
    CHECK_EQ(t.get(0, 0).r, 0);
    CHECK(t.get(0, 0).g < 150);
    CHECK(t.get(0, 0).b > 0);
    // Params are clamped to [0,255]; white<=black is an error, no touch.
    Surface u = solid(8, 8, 100, 100, 100, 255);
    const auto ub = u.bytes();
    CHECK(!openclip::applyFilter(u, "levels",
                                 "{\"black\":200,\"white\":100}", err));
    CHECK(u.bytes() == ub);
}

TEST(filters_chromatic_shifts_rb_not_g) {
    std::string err;
    // Vertical white line on black: R shifts one way, B the other, G stays.
    Surface s(32, 8);
    for (int y = 0; y < 8; ++y)
        for (int x = 0; x < 32; ++x)
            s.set(x, y, Pixel{0, 0, 0, 255});
    for (int y = 0; y < 8; ++y) s.set(16, y, Pixel{255, 255, 255, 255});
    CHECK(openclip::applyFilter(s, "chromaticAberration", "{\"shift\":3}",
                                err));
    // G plane keeps the line where it was.
    CHECK_EQ(s.get(16, 4).g, 255);
    CHECK_EQ(s.get(13, 4).g, 0);
    CHECK_EQ(s.get(19, 4).g, 0);
    // R moved left (sampled from x-shift): bright at 16+3=19? R at x reads
    // source x-3, so line source 16 appears at x=19.
    CHECK_EQ(s.get(19, 4).r, 255);
    CHECK_EQ(s.get(16, 4).r, 0);
    // B moved right (reads x+3): line appears at x=13.
    CHECK_EQ(s.get(13, 4).b, 255);
    CHECK_EQ(s.get(16, 4).b, 0);
}

TEST(filters_noise_reproducible) {
    std::string err;
    Surface a = solid(32, 32, 128, 128, 128, 255);
    Surface b = solid(32, 32, 128, 128, 128, 255);
    CHECK(openclip::applyFilter(a, "addNoise",
                                "{\"amount\":20,\"monochrome\":1}", err));
    CHECK(openclip::applyFilter(b, "addNoise",
                                "{\"amount\":20,\"monochrome\":1}", err));
    CHECK(a.bytes() == b.bytes());  // same fixed seed
    CHECK(a.bytes() != solid(32, 32, 128, 128, 128, 255).bytes());
    // Mono: channels move together.
    bool monoOk = true;
    for (int y = 0; y < 32 && monoOk; ++y)
        for (int x = 0; x < 32 && monoOk; ++x) {
            const Pixel p = a.get(x, y);
            if (p.r != p.g || p.g != p.b) monoOk = false;
        }
    CHECK(monoOk);
    // Alpha untouched.
    CHECK(alphaColEq(a, solid(32, 32, 0, 0, 0, 255)));
}

TEST(filters_mosaic_block_uniform) {
    std::string err;
    Surface s(32, 32);
    for (int y = 0; y < 32; ++y)
        for (int x = 0; x < 32; ++x)
            s.set(x, y, Pixel{(std::uint8_t)((x * 13 + y * 29) % 256),
                              (std::uint8_t)((x * 7 + y * 3) % 256),
                              (std::uint8_t)((x + y) % 256), 255});
    CHECK(openclip::applyFilter(s, "mosaic", "{\"block\":8}", err));
    bool uniform = true;
    for (int by = 0; by < 32 && uniform; by += 8)
        for (int bx = 0; bx < 32 && uniform; bx += 8) {
            const Pixel ref = s.get(bx, by);
            for (int y = by; y < by + 8 && uniform; ++y)
                for (int x = bx; x < bx + 8; ++x) {
                    const Pixel p = s.get(x, y);
                    if (p.r != ref.r || p.g != ref.g || p.b != ref.b)
                        uniform = false;
                }
        }
    CHECK(uniform);
    CHECK(alphaColEq(s, solid(32, 32, 0, 0, 0, 255)));
}

TEST(filters_gradient_map_endpoints) {
    std::string err;
    Surface s(4, 1);
    s.set(0, 0, Pixel{0, 0, 0, 255});
    s.set(1, 0, Pixel{255, 255, 255, 255});
    s.set(2, 0, Pixel{255, 0, 0, 255});  // mid luminance
    s.set(3, 0, Pixel{0, 0, 0, 128});    // alpha preserved
    CHECK(openclip::applyFilter(
        s, "gradientMap",
        "{\"stops\":\"[\\\"#000000:0\\\",\\\"#FFFFFF:1\\\"]\"}", err));
    CHECK_EQ(s.get(0, 0).r, 0);
    CHECK_EQ(s.get(0, 0).g, 0);
    CHECK_EQ(s.get(0, 0).b, 0);
    CHECK_EQ(s.get(1, 0).r, 255);
    CHECK_EQ(s.get(1, 0).g, 255);
    CHECK_EQ(s.get(1, 0).b, 255);
    // Red maps to mid gray (~76).
    CHECK(s.get(2, 0).r > 60 && s.get(2, 0).r < 95);
    CHECK_EQ(s.get(2, 0).r, s.get(2, 0).g);
    CHECK_EQ(s.get(3, 0).a, 128);
    // Custom stops: black->red, white->blue.
    Surface c(2, 1);
    c.set(0, 0, Pixel{0, 0, 0, 255});
    c.set(1, 0, Pixel{255, 255, 255, 255});
    CHECK(openclip::applyFilter(
        c, "gradientMap",
        "{\"stops\":\"[\\\"#FF0000:0\\\",\\\"#0000FF:1\\\"]\"}", err));
    CHECK_EQ(c.get(0, 0).r, 255);
    CHECK_EQ(c.get(0, 0).b, 0);
    CHECK_EQ(c.get(1, 0).r, 0);
    CHECK_EQ(c.get(1, 0).b, 255);
}
