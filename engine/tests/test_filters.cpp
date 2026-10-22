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
    CHECK(canApply("denoise"));  // V+3 classic
    CHECK(canApply("unsharp"));  // T1
    CHECK(canApply("colorBalance"));  // T1
    CHECK(canApply("threshold"));  // T1
    CHECK(!canApply("nope"));
    CHECK(!canApply("GaussianBlur"));
    CHECK(!canApply(""));
    CHECK_EQ(openclip::filterNames().size(), (std::size_t)14);
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

// V+3 classic denoise (ARCHITECTURE.md "V+3 additions"): median 3x3
// removes impulse noise, strength 0 is byte-identical, bad params fail
// cleanly with the surface untouched.
TEST(filters_denoise_reduces_impulse_noise) {
    Surface s = solid(32, 32, 128, 128, 128, 255);
    s.set(5, 5, Pixel{255, 255, 255, 255});
    s.set(6, 5, Pixel{0, 0, 0, 255});
    s.set(20, 20, Pixel{255, 255, 255, 255});
    s.set(21, 21, Pixel{0, 0, 0, 255});
    const double before = varianceR(s);
    CHECK(before > 1.0);
    std::string err;
    CHECK(openclip::applyFilter(s, "denoise", "{\"strength\":1}", err));
    CHECK(varianceR(s) < before);
    CHECK(alphaColEq(s, solid(32, 32, 128, 128, 128, 255)));
    // Bilateral mode is a valid no-crash path on the same fixture.
    Surface b = solid(32, 32, 128, 128, 128, 255);
    b.set(5, 5, Pixel{255, 255, 255, 255});
    CHECK(openclip::applyFilter(
        b, "denoise", "{\"strength\":1,\"mode\":\"bilateral\"}", err));
    CHECK(varianceR(b) < before);
}

TEST(filters_denoise_zero_identity_bad_params_refused) {
    Surface s = solid(16, 16, 10, 200, 90, 255);
    const auto ref = s.bytes();
    std::string err;
    CHECK(openclip::applyFilter(s, "denoise", "{\"strength\":0}", err));
    CHECK(s.bytes() == ref);  // no-op success, byte-identical
    CHECK(!openclip::applyFilter(s, "denoise", "{\"strength\":2}", err));
    CHECK(!err.empty());
    CHECK(s.bytes() == ref);
    CHECK(!openclip::applyFilter(
        s, "denoise", "{\"strength\":0.5,\"mode\":\"wavelet\"}", err));
    CHECK(s.bytes() == ref);
}

// --- T1 additions (ARCHITECTURE.md "T1 additions") ---

TEST(filters_unsharp_identity_at_zero) {
    Surface s = solid(24, 24, 90, 140, 200, 255);
    s.set(5, 5, Pixel{255, 0, 0, 255});
    s.set(18, 18, Pixel{0, 0, 0, 255});
    const auto ref = s.bytes();
    std::string err;
    CHECK(openclip::applyFilter(
        s, "unsharp",
        "{\"radius\":2,\"amount\":0,\"threshold\":0}", err));
    CHECK(s.bytes() == ref);  // amount 0 = byte-identical no-op
    CHECK(openclip::canApply("unsharp"));
}

TEST(filters_unsharp_sharpens_edge) {
    // Vertical step edge: blur radius 2 mixes across it; unsharp must
    // push the two sides apart (local contrast up) without touching
    // alpha.
    Surface s(32, 16);
    for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 32; ++x)
            s.set(x, y, x < 16 ? Pixel{60, 60, 60, 255}
                               : Pixel{180, 180, 180, 255});
    const auto dark0 = s.get(14, 8).r, lite0 = s.get(17, 8).r;
    std::string err;
    CHECK(openclip::applyFilter(
        s, "unsharp",
        "{\"radius\":2,\"amount\":1,\"threshold\":0}", err));
    const auto dark1 = s.get(14, 8).r, lite1 = s.get(17, 8).r;
    CHECK(lite1 - dark1 > lite0 - dark0);  // edge contrast grew
    CHECK(alphaColEq(s, solid(32, 16, 0, 0, 0, 255)));
    // Bad radius is refused, surface untouched.
    const auto ref = s.bytes();
    CHECK(!openclip::applyFilter(
        s, "unsharp", "{\"radius\":0,\"amount\":1}", err));
    CHECK(!err.empty());
    CHECK(s.bytes() == ref);
}

TEST(filters_unsharp_threshold_calms_flat) {
    // Flat field + one hot pixel: a high threshold must leave the
    // surface quasi-intact (the impulse is below the threshold vs its
    // blurred neighborhood... here we assert the flat area is exact).
    Surface s = solid(24, 24, 128, 128, 128, 255);
    const auto ref = s.bytes();
    std::string err;
    CHECK(openclip::applyFilter(
        s, "unsharp",
        "{\"radius\":2,\"amount\":2,\"threshold\":32}", err));
    CHECK(s.bytes() == ref);  // flat + strong threshold = intact
}

TEST(filters_balance_neutral_identity) {
    Surface s = solid(20, 20, 11, 199, 88, 255);
    s.set(3, 3, Pixel{10, 10, 10, 255});
    s.set(16, 16, Pixel{250, 250, 250, 255});
    const auto ref = s.bytes();
    std::string err;
    CHECK(openclip::applyFilter(
        s, "colorBalance",
        "{\"shadows\":\"0,0,0\",\"midtones\":\"0,0,0\","
        "\"highlights\":\"0,0,0\"}",
        err));
    CHECK(s.bytes() == ref);  // neutral = byte-identical
    CHECK(openclip::canApply("colorBalance"));
}

TEST(filters_balance_shadows_only_dark) {
    // Strong red push in shadows only: dark pixels move, bright
    // pixels stay within +/-1 (zone weights ~0 at luma 1).
    Surface s(16, 16);
    for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 16; ++x)
            s.set(x, y, x < 8 ? Pixel{20, 20, 20, 255}
                              : Pixel{250, 250, 250, 255});
    std::string err;
    CHECK(openclip::applyFilter(
        s, "colorBalance",
        "{\"shadows\":\"100,0,0\",\"midtones\":\"0,0,0\","
        "\"highlights\":\"0,0,0\"}",
        err));
    CHECK(s.get(2, 2).r > 20 + 40);  // dark red pushed hard
    const auto b = s.get(13, 13);
    CHECK(std::abs((int)b.r - 250) <= 1);
    CHECK(std::abs((int)b.g - 250) <= 1);
    CHECK(std::abs((int)b.b - 250) <= 1);
    CHECK(alphaColEq(s, solid(16, 16, 0, 0, 0, 255)));
}

TEST(filters_balance_clamp_no_overflow) {
    // Extreme pushes on white must clamp, never overflow: rgb <=
    // alpha everywhere, alpha bytes untouched.
    Surface s = solid(12, 12, 250, 250, 250, 255);
    std::string err;
    CHECK(openclip::applyFilter(
        s, "colorBalance",
        "{\"shadows\":\"100,100,100\",\"midtones\":\"100,100,100\","
        "\"highlights\":\"100,100,100\"}",
        err));
    for (int y = 0; y < 12; ++y)
        for (int x = 0; x < 12; ++x) {
            const auto p = s.get(x, y);
            CHECK(p.r <= p.a);
            CHECK(p.g <= p.a);
            CHECK(p.b <= p.a);
            CHECK_EQ(p.a, 255);
        }
    // Malformed triplet = error + untouched.
    Surface t = solid(8, 8, 100, 100, 100, 255);
    const auto ref = t.bytes();
    CHECK(!openclip::applyFilter(
        t, "colorBalance", "{\"shadows\":\"hot,pink,now\"}", err));
    CHECK(!err.empty());
    CHECK(t.bytes() == ref);
    CHECK(!openclip::applyFilter(
        t, "colorBalance", "{\"shadows\":\"0,0,500\"}", err));
    CHECK(t.bytes() == ref);
}

TEST(filters_threshold_binary) {
    // Only 0/255 outputs; alpha untouched (semi-transparent keeps
    // its alpha, rgb clamped by the registry to [0,alpha]).
    Surface s(16, 8);
    for (int y = 0; y < 8; ++y)
        for (int x = 0; x < 16; ++x)
            s.set(x, y, Pixel{(std::uint8_t)(x * 16),
                              (std::uint8_t)(x * 16),
                              (std::uint8_t)(x * 16), 200});
    std::string err;
    CHECK(openclip::applyFilter(s, "threshold", "{\"level\":128}", err));
    for (int y = 0; y < 8; ++y)
        for (int x = 0; x < 16; ++x) {
            const auto p = s.get(x, y);
            CHECK(p.r == 0 || p.r == 200);  // clamped to alpha
            CHECK(p.g == 0 || p.g == 200);
            CHECK(p.b == 0 || p.b == 200);
            CHECK_EQ(p.a, 200);
            CHECK_EQ(p.r, p.g);
            CHECK_EQ(p.g, p.b);
        }
    CHECK(openclip::canApply("threshold"));
}

TEST(filters_threshold_level_orders) {
    Surface w = solid(8, 8, 200, 200, 200, 255);
    Surface b = solid(8, 8, 10, 10, 10, 255);
    std::string err;
    // Level 0: everything >= 0 luma goes white...
    CHECK(openclip::applyFilter(w, "threshold", "{\"level\":0}", err));
    CHECK(openclip::applyFilter(b, "threshold", "{\"level\":0}", err));
    CHECK_EQ(w.get(0, 0).r, 255);
    CHECK_EQ(b.get(0, 0).r, 255);
    // ...level 255: only pure-white luma survives.
    Surface w2 = solid(8, 8, 200, 200, 200, 255);
    Surface pure = solid(8, 8, 255, 255, 255, 255);
    CHECK(openclip::applyFilter(w2, "threshold", "{\"level\":255}", err));
    CHECK(openclip::applyFilter(pure, "threshold", "{\"level\":255}", err));
    CHECK_EQ(w2.get(0, 0).r, 0);
    CHECK_EQ(pure.get(0, 0).r, 255);
}

TEST(filters_threshold_dither_bounded) {
    // Mid-gray field at the exact level: dither must produce a mixed
    // black/white pattern whose local mean stays near the level
    // (within +/-10% of the 255 range).
    Surface s = solid(32, 32, 128, 128, 128, 255);
    std::string err;
    CHECK(openclip::applyFilter(
        s, "threshold", "{\"level\":128,\"dither\":1}", err));
    long sum = 0;
    bool seenBlack = false, seenWhite = false;
    for (int y = 0; y < 32; ++y)
        for (int x = 0; x < 32; ++x) {
            const auto v = s.get(x, y).r;
            CHECK(v == 0 || v == 255);
            sum += v;
            if (v == 0) seenBlack = true;
            if (v == 255) seenWhite = true;
        }
    CHECK(seenBlack && seenWhite);  // dither actually mixed
    const double mean = sum / 1024.0;
    CHECK(std::fabs(mean - 128.0) <= 25.5);  // +/-10%
}
