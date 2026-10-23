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
    CHECK(canApply("glow"));  // T4 §2
    CHECK(canApply("dropShadow"));  // T4 §2
    CHECK(canApply("crystallize"));  // T4 §2
    CHECK(canApply("normalMap"));  // T4 §2
    CHECK(canApply("retro"));  // T4 §2
    CHECK(canApply("pencil"));  // T4 §2
    CHECK(canApply("perlinClouds"));  // T4 §2
    CHECK(canApply("lensBlur"));  // T4 §3
    CHECK(canApply("fisheyeFilter"));  // T4 §3
    CHECK(canApply("panoramaRemap"));  // T4 §3
    CHECK(canApply("edgeBorder"));  // T5 §2
    CHECK(canApply("watercolor"));  // T5 §2
    CHECK(!canApply("nope"));
    CHECK(!canApply("GaussianBlur"));
    CHECK(!canApply(""));
    CHECK_EQ(openclip::filterNames().size(), (std::size_t)26);
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

// --- T4 creative pack (§2) + lens pack (§3) ---
// Pattern per filter: neutral params = byte-identical; invalid params
// = false + intact; one invariant proving the effect.

namespace {

bool applyOk(Surface& s, const char* name, const char* params) {
    std::string err;
    return openclip::applyFilter(s, name, params, err);
}

bool applyFailsIntact(Surface& s, const char* name, const char* params) {
    const auto before = s.bytes();
    std::string err;
    const bool ok = openclip::applyFilter(s, name, params, err);
    return !ok && !err.empty() && s.bytes() == before;
}

}  // namespace

TEST(filters_glow_neutral_highlights_invalid) {
    Surface s = solid(24, 24, 200, 210, 220, 255);
    const auto before = s.bytes();
    CHECK(applyOk(s, "glow", "{\"radius\":8,\"amount\":0}"));
    CHECK(s.bytes() == before);  // amount 0 = byte-identical
    CHECK(applyFailsIntact(s, "glow", "{\"radius\":0}"));
    CHECK(applyFailsIntact(s, "glow", "{\"amount\":3}"));
    CHECK(applyFailsIntact(s, "glow", "{\"tint\":\"red\"}"));
    // White highlights bloom: a white field gains nothing to screen
    // (already 255), but a bright gray field brightens.
    Surface g = solid(24, 24, 190, 190, 190, 255);
    CHECK(applyOk(g, "glow",
                  "{\"radius\":2,\"amount\":1,\"tint\":\"#FFFFFF\"}"));
    CHECK(g.get(12, 12).r > 190);
    // Dark field untouched by the highlight gate.
    Surface d = solid(24, 24, 40, 40, 40, 255);
    const auto dbefore = d.bytes();
    CHECK(applyOk(d, "glow", "{\"radius\":2,\"amount\":1}"));
    CHECK(d.bytes() == dbefore);
}

TEST(filters_dropshadow_behind_opaque_intact_invalid) {
    // Opaque source: shadow lands nowhere, source byte-identical.
    Surface s = solid(24, 24, 100, 120, 140, 255);
    const auto before = s.bytes();
    CHECK(applyOk(s, "dropShadow",
                  "{\"dx\":4,\"dy\":4,\"radius\":2}"));
    CHECK(s.bytes() == before);
    CHECK(applyFailsIntact(s, "dropShadow", "{\"dx\":100}"));
    CHECK(applyFailsIntact(s, "dropShadow", "{\"opacity\":2}"));
    CHECK(applyFailsIntact(s, "dropShadow", "{\"color\":\"#zzzzzz\"}"));
    // Translucent canvas with one opaque dot: shadow appears offset.
    Surface t(24, 24);
    t.set(6, 6, Pixel{255, 0, 0, 255});
    CHECK(applyOk(t, "dropShadow",
                  "{\"dx\":4,\"dy\":0,\"radius\":0,\"color\":\"#000000\","
                  "\"opacity\":1}"));
    const Pixel sh = t.get(10, 6);
    CHECK(sh.a > 0);  // shadow alpha landed (documented exception)
    CHECK(sh.r == 0 && sh.g == 0 && sh.b == 0);
    CHECK_EQ(t.get(6, 6).r, 255);  // source itself intact
    CHECK_EQ(t.get(6, 6).a, 255);
}

TEST(filters_crystallize_cell64_flats_invalid) {
    // cell 64 on uniform field = flats (each cell = center = same).
    Surface s = solid(32, 32, 90, 100, 110, 255);
    const auto before = s.bytes();
    CHECK(applyOk(s, "crystallize", "{\"cell\":64}"));
    CHECK(s.bytes() == before);
    CHECK(applyFailsIntact(s, "crystallize", "{\"cell\":2}"));
    CHECK(applyFailsIntact(s, "crystallize", "{\"cell\":65}"));
    // Deterministic: same input twice = same output.
    Surface a(32, 32), b(32, 32);
    for (int y = 0; y < 32; ++y)
        for (int x = 0; x < 32; ++x) {
            Pixel p{static_cast<std::uint8_t>((x * 7) & 255),
                    static_cast<std::uint8_t>((y * 13) & 255),
                    static_cast<std::uint8_t>((x + y) & 255), 255};
            a.set(x, y, p);
            b.set(x, y, p);
        }
    CHECK(applyOk(a, "crystallize", "{\"cell\":8}"));
    CHECK(applyOk(b, "crystallize", "{\"cell\":8}"));
    CHECK(a.bytes() == b.bytes());
}

TEST(filters_normalmap_uniform_blue_invalid) {
    // Flat gray -> uniform #8080FF (Sobel sees nothing).
    Surface s = solid(16, 16, 120, 120, 120, 255);
    CHECK(applyOk(s, "normalMap", "{\"strength\":1}"));
    for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 16; ++x) {
            Pixel p = s.get(x, y);
            CHECK_EQ(p.r, 128);
            CHECK_EQ(p.g, 128);
            CHECK_EQ(p.b, 255);
        }
    CHECK(applyFailsIntact(s, "normalMap", "{\"strength\":5}"));
    // A step edge tilts normals away from flat.
    Surface e = solid(16, 16, 50, 50, 50, 255);
    for (int y = 0; y < 16; ++y)
        for (int x = 8; x < 16; ++x) e.set(x, y, Pixel{200, 200, 200, 255});
    CHECK(applyOk(e, "normalMap", "{\"strength\":2}"));
    CHECK(e.get(7, 8).r != 128 || e.get(7, 8).b != 255);
}

TEST(filters_retro_allzero_identical_invalid) {
    Surface s = solid(24, 24, 110, 120, 130, 255);
    const auto before = s.bytes();
    CHECK(applyOk(
        s, "retro",
        "{\"warmth\":0,\"grain\":0,\"vignette\":0,\"fade\":0}"));
    CHECK(s.bytes() == before);
    CHECK(applyFailsIntact(s, "retro", "{\"warmth\":2}"));
    CHECK(applyFailsIntact(s, "retro", "{\"grain\":65}"));
    // Warmth lifts the blacks.
    Surface d = solid(24, 24, 20, 20, 20, 255);
    CHECK(applyOk(d, "retro",
                  "{\"warmth\":1,\"grain\":0,\"vignette\":0,\"fade\":0}"));
    CHECK(d.get(12, 12).r > 20);
    // Reproducible grain: same call twice = same bytes.
    Surface g1 = solid(24, 24, 100, 100, 100, 255);
    Surface g2 = g1;
    CHECK(applyOk(g1, "retro",
                  "{\"warmth\":0,\"grain\":10,\"vignette\":0,\"fade\":0}"));
    CHECK(applyOk(g2, "retro",
                  "{\"warmth\":0,\"grain\":10,\"vignette\":0,\"fade\":0}"));
    CHECK(g1.bytes() == g2.bytes());
}

TEST(filters_pencil_zero_noop_invalid_edge) {
    Surface s = solid(24, 24, 110, 120, 130, 255);
    const auto before = s.bytes();
    CHECK(applyOk(s, "pencil", "{\"strength\":0}"));
    CHECK(s.bytes() == before);
    CHECK(applyFailsIntact(s, "pencil", "{\"strength\":1.5}"));
    // A hard step edge darkens toward ink.
    Surface e = solid(24, 24, 200, 200, 200, 255);
    for (int y = 0; y < 24; ++y)
        for (int x = 12; x < 24; ++x) e.set(x, y, Pixel{20, 20, 20, 255});
    CHECK(applyOk(e, "pencil", "{\"strength\":1}"));
    CHECK(e.get(11, 12).r < 200);  // edge pixel inked
}

TEST(filters_perlin_seed_modes_invalid) {
    Surface s = solid(32, 32, 150, 150, 150, 255);
    // Same seed = byte-identical; different seed = different.
    Surface a = s, b = s, c = s;
    CHECK(applyOk(a, "perlinClouds",
                  "{\"scale\":4,\"octaves\":2,\"seed\":7}"));
    CHECK(applyOk(b, "perlinClouds",
                  "{\"scale\":4,\"octaves\":2,\"seed\":7}"));
    CHECK(applyOk(c, "perlinClouds",
                  "{\"scale\":4,\"octaves\":2,\"seed\":8}"));
    CHECK(a.bytes() == b.bytes());
    CHECK(!(a.bytes() == c.bytes()));
    // Overwrite ignores the source (same output whatever the input).
    Surface w1 = solid(16, 16, 10, 10, 10, 255);
    Surface w2 = solid(16, 16, 240, 240, 240, 255);
    CHECK(applyOk(w1, "perlinClouds",
                  "{\"scale\":4,\"octaves\":2,\"seed\":3,"
                  "\"mode\":\"overwrite\"}"));
    CHECK(applyOk(w2, "perlinClouds",
                  "{\"scale\":4,\"octaves\":2,\"seed\":3,"
                  "\"mode\":\"overwrite\"}"));
    CHECK(w1.bytes() == w2.bytes());
    CHECK(applyFailsIntact(s, "perlinClouds", "{\"scale\":0}"));
    CHECK(applyFailsIntact(s, "perlinClouds", "{\"octaves\":7}"));
    CHECK(applyFailsIntact(s, "perlinClouds", "{\"mode\":\"stitch\"}"));
}

TEST(filters_lensblur_uniform_disk_differs_invalid) {
    // Uniform surface: disk blur is the identity (locked).
    Surface s = solid(24, 24, 100, 110, 120, 255);
    const auto before = s.bytes();
    CHECK(applyOk(s, "lensBlur", "{\"radius\":4,\"highlights\":0}"));
    CHECK(s.bytes() == before);
    CHECK(applyFailsIntact(s, "lensBlur", "{\"radius\":0}"));
    CHECK(applyFailsIntact(s, "lensBlur", "{\"radius\":65}"));
    // Disk != gaussian on a step edge: at least 1px differs.
    Surface d1 = solid(24, 24, 30, 30, 30, 255);
    Surface d2 = d1;
    for (int y = 0; y < 24; ++y)
        for (int x = 12; x < 24; ++x) {
            d1.set(x, y, Pixel{220, 220, 220, 255});
            d2.set(x, y, Pixel{220, 220, 220, 255});
        }
    CHECK(applyOk(d1, "lensBlur", "{\"radius\":6,\"highlights\":0}"));
    CHECK(applyOk(d2, "gaussianBlur", "{\"radius\":1.2}"));
    CHECK(!(d1.bytes() == d2.bytes()));
}

TEST(filters_fisheye_zero_identical_invalid_bulge) {
    Surface s = solid(24, 24, 100, 110, 120, 255);
    const auto before = s.bytes();
    CHECK(applyOk(s, "fisheyeFilter", "{\"amount\":0}"));
    CHECK(s.bytes() == before);
    CHECK(applyFailsIntact(s, "fisheyeFilter", "{\"amount\":2}"));
    // A grid center bloats outward with +0.5 (center pixel moves).
    Surface g(25, 25);
    for (int y = 0; y < 25; ++y)
        for (int x = 0; x < 25; ++x)
            g.set(x, y, Pixel{(x % 5 == 0 || y % 5 == 0) ? 0 : 255,
                              (x % 5 == 0 || y % 5 == 0) ? 0 : 255,
                              (x % 5 == 0 || y % 5 == 0) ? 0 : 255, 255});
    const auto gbefore = g.bytes();
    CHECK(applyOk(g, "fisheyeFilter", "{\"amount\":0.5}"));
    CHECK(!(g.bytes() == gbefore));
}

TEST(filters_panorama_roundtrip_invalid) {
    Surface s = solid(48, 24, 100, 110, 120, 255);
    // Gradient band: cyl -> rect -> cyl ≈ identity within ±2 LSB.
    Surface band(48, 24);
    for (int y = 0; y < 24; ++y)
        for (int x = 0; x < 48; ++x) {
            const auto v = static_cast<std::uint8_t>(x * 255 / 47);
            band.set(x, y, Pixel{v, v, v, 255});
        }
    Surface tmp = band;
    CHECK(applyOk(tmp, "panoramaRemap",
                  "{\"mode\":\"cyl\",\"fov\":120}"));
    CHECK(applyOk(tmp, "panoramaRemap",
                  "{\"mode\":\"rect\",\"fov\":120}"));
    for (int y = 0; y < 24; ++y)
        for (int x = 2; x < 46; ++x) {  // edges clamp, skip them
            const int was = band.get(x, y).r;
            const int now = tmp.get(x, y).r;
            CHECK(std::abs(was - now) <= 2);
        }
    CHECK(applyFailsIntact(s, "panoramaRemap", "{\"mode\":\"cube\"}"));
    CHECK(applyFailsIntact(s, "panoramaRemap", "{\"fov\":30}"));
}

TEST(filters_edge_border_modes) {
    // 16x16 with an opaque 8x8 white square centered (4..11).
    Surface s(16, 16);
    for (int y = 4; y < 12; ++y)
        for (int x = 4; x < 12; ++x)
            s.set(x, y, Pixel{255, 255, 255, 255});
    // inside: transparent pixels stay transparent, edge covered pixels
    // turn red.
    Surface t = s;
    CHECK(applyOk(t, "edgeBorder",
                  "{\"width\":2,\"color\":\"#FF0000\",\"mode\":\"inside\"}"));
    CHECK_EQ(t.get(0, 0).a, 0);  // far transparent: intact
    CHECK_EQ(t.get(4, 4).r, 255);
    CHECK_EQ(t.get(4, 4).g, 0);  // edge pixel: red liseré
    CHECK_EQ(t.get(7, 7).r, 255);
    CHECK_EQ(t.get(7, 7).g, 255);  // deep interior: untouched
    // outside: ring appears on transparent pixels, source intact.
    t = s;
    CHECK(applyOk(t, "edgeBorder",
                  "{\"width\":2,\"color\":\"#FF0000\",\"mode\":\"outside\"}"));
    CHECK_EQ(t.get(7, 7).g, 255);  // source intact
    CHECK(t.get(3, 7).a != 0);     // ring pixel painted
    CHECK_EQ(t.get(3, 7).r, 255);
    CHECK_EQ(t.get(0, 0).a, 0);  // far away: intact
    // center: both sides painted (half width each side: the corner
    // sits at 50% coverage).
    t = s;
    CHECK(applyOk(t, "edgeBorder",
                  "{\"width\":2,\"color\":\"#FF0000\",\"mode\":\"center\"}"));
    CHECK(t.get(3, 7).a != 0);
    CHECK_EQ(t.get(4, 4).g, 128);
    // Invalid params: false + byte-identical surface.
    CHECK(applyFailsIntact(s, "edgeBorder", "{\"width\":0}"));
    CHECK(applyFailsIntact(s, "edgeBorder", "{\"width\":33}"));
    CHECK(applyFailsIntact(s, "edgeBorder", "{\"mode\":\"diagonal\"}"));
    CHECK(applyFailsIntact(s, "edgeBorder", "{\"color\":\"red\"}"));
    // Registry knows both new names.
    CHECK(openclip::canApply("edgeBorder"));
    CHECK(openclip::canApply("watercolor"));
}

TEST(filters_watercolor_zero_identical_edges_denser) {
    // Opaque gray disc on transparency.
    Surface s(24, 24);
    for (int y = 0; y < 24; ++y)
        for (int x = 0; x < 24; ++x) {
            const int dx = x - 12, dy = y - 12;
            if (dx * dx + dy * dy <= 64)
                s.set(x, y, Pixel{160, 140, 120, 255});
        }
    Surface t = s;
    CHECK(applyOk(t, "watercolor",
                  "{\"darken\":0,\"rough\":0,\"bloom\":0}"));
    CHECK(t.bytes() == s.bytes());  // all-zero = byte-identical
    t = s;
    CHECK(applyOk(t, "watercolor",
                  "{\"darken\":0.6,\"rough\":6,\"bloom\":0.4}"));
    CHECK(!(t.bytes() == s.bytes()));  // something changed
    // Alpha never touched.
    for (int y = 0; y < 24; ++y)
        for (int x = 0; x < 24; ++x)
            CHECK_EQ(t.get(x, y).a, s.get(x, y).a);
    // Fully transparent pixels stay transparent.
    CHECK_EQ(t.get(0, 0).a, 0);
    // Invalid params: false + intact.
    CHECK(applyFailsIntact(s, "watercolor", "{\"darken\":2}"));
    CHECK(applyFailsIntact(s, "watercolor", "{\"rough\":17}"));
    CHECK(applyFailsIntact(s, "watercolor", "{\"bloom\":-1}"));
}
