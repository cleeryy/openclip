// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip B1 engine — clone stamp, smudge, dodge/burn, pattern brush
// (ARCHITECTURE.md "B1 additions" §2–§4).
#include "test.h"

#include <cmath>
#include <string>
#include <vector>

#include "openclip/brush.h"
#include "openclip/retouch.h"
#include "openclip/surface.h"

using openclip::BrushParams;
using openclip::CloneAnchor;
using openclip::Dab;
using openclip::PatternSpec;
using openclip::PatternStamp;
using openclip::PatternWalker;
using openclip::Pixel;
using openclip::SmudgeState;
using openclip::Surface;

namespace {

BrushParams hardBrush(float radius) {
    BrushParams p;
    p.radius = radius;
    p.hardness = 1.0f;
    p.opaque = 1.0f;
    p.spacing = 0.1f;
    return p;
}

Surface opaqueFill(int w, int h, Pixel px) {
    Surface s(w, h);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) s.set(x, y, px);
    return s;
}

bool premultSafe(const Surface& s) {
    for (int y = 0; y < s.height(); ++y)
        for (int x = 0; x < s.width(); ++x) {
            const Pixel p = s.get(x, y);
            if (p.r > p.a || p.g > p.a || p.b > p.a) return false;
        }
    return true;
}

}  // namespace

// ---- Clone stamp ---------------------------------------------------------

TEST(retouch_clone_unaligned_reanchors_each_stroke) {
    CloneAnchor a;
    openclip::cloneSetSource(a, 10, 10, /*aligned=*/false);
    CHECK(openclip::cloneReady(a) == false);  // no stroke yet
    openclip::cloneStrokeBegin(a, 100, 100);
    CHECK(openclip::cloneReady(a));
    CHECK_EQ(a.offX, -90.0f);
    CHECK_EQ(a.offY, -90.0f);
    // A second stroke re-anchors: sampling follows the new start point.
    openclip::cloneStrokeBegin(a, 200, 50);
    CHECK_EQ(a.offX, -190.0f);
    CHECK_EQ(a.offY, -40.0f);
}

TEST(retouch_clone_aligned_keeps_offset) {
    CloneAnchor a;
    openclip::cloneSetSource(a, 10, 10, /*aligned=*/true);
    openclip::cloneStrokeBegin(a, 100, 100);
    CHECK_EQ(a.offX, -90.0f);
    CHECK_EQ(a.offY, -90.0f);
    // Aligned: the second stroke keeps sampling the same relative area.
    openclip::cloneStrokeBegin(a, 200, 50);
    CHECK_EQ(a.offX, -90.0f);
    CHECK_EQ(a.offY, -90.0f);
    // A fresh long press re-anchors even in aligned mode.
    openclip::cloneSetSource(a, 500, 400, true);
    CHECK(openclip::cloneReady(a) == false);
    openclip::cloneStrokeBegin(a, 0, 0);
    CHECK_EQ(a.offX, 500.0f);
    CHECK_EQ(a.offY, 400.0f);
}

TEST(retouch_clone_dab_copies_source) {
    Surface src = opaqueFill(16, 16, Pixel{0, 0, 0, 0});
    src.set(3, 3, Pixel{200, 10, 10, 255});
    Surface dst = opaqueFill(32, 32, Pixel{0, 0, 0, 0});
    const BrushParams p = hardBrush(4.0f);
    const Dab d = openclip::dabFromPressure(p, 20, 20, 1.0f, 0, 0, 0);
    // Source anchor (3,3) -> stroke at (20,20): offset (-17,-17) puts
    // the red pixel exactly at (20,20).
    openclip::cloneDab(dst, src, d, p, -17.0f, -17.0f);
    const Pixel hit = dst.get(20, 20);
    CHECK_EQ(hit.a, 255);
    CHECK_EQ(hit.r, 200);
    // Transparent source stays transparent (no ink from empty pixels).
    const Pixel empty = dst.get(24, 24);
    CHECK_EQ(empty.a, 0);
    CHECK(premultSafe(dst));
}

TEST(retouch_clone_self_sample_is_stable) {
    // Cloning a surface onto itself with a zero offset must not drift
    // (a pixel-exact copy is idempotent).
    Surface s = opaqueFill(24, 24, Pixel{0, 0, 0, 0});
    for (int y = 4; y < 20; ++y)
        for (int x = 4; x < 20; ++x) s.set(x, y, Pixel{90, 120, 200, 255});
    const Surface before = s;
    const BrushParams p = hardBrush(6.0f);
    const Dab d = openclip::dabFromPressure(p, 12, 12, 1.0f, 0, 0, 0);
    openclip::cloneDab(s, s, d, p, 0.0f, 0.0f);
    bool same = true;
    for (int y = 0; y < 24; ++y)
        for (int x = 0; x < 24; ++x) {
            const Pixel a = before.get(x, y);
            const Pixel b = s.get(x, y);
            if (a.r != b.r || a.g != b.g || a.b != b.b || a.a != b.a)
                same = false;
        }
    CHECK(same);
}

// ---- Smudge --------------------------------------------------------------

TEST(retouch_smudge_moves_paint_along_stroke) {
    Surface s = opaqueFill(80, 24, Pixel{255, 255, 255, 255});
    for (int y = 0; y < 24; ++y)
        for (int x = 20; x < 24; ++x) s.set(x, y, Pixel{0, 0, 0, 255});
    const BrushParams p = hardBrush(6.0f);
    SmudgeState st;
    // Left-to-right stroke across the black band.
    for (float x = 14.0f; x <= 34.0f; x += 2.0f) {
        const Dab d = openclip::dabFromPressure(p, x, 12, 1.0f, 0, 0, 0);
        openclip::smudgeDab(s, d, p, st, 1.0f);
    }
    // The white pixels just RIGHT of the band were tinted by the paint
    // carried along the stroke.
    const Pixel right = s.get(30, 12);
    CHECK(right.r < 250);
    // ...and pixels left of the stroke start are untouched.
    const Pixel left = s.get(4, 12);
    CHECK_EQ(left.r, 255);
    CHECK_EQ(left.a, 255);
    CHECK(premultSafe(s));
}

TEST(retouch_smudge_carries_alpha_onto_empty_pixels) {
    // Half-covered surface: smudging opaque paint into the empty half
    // grows alpha there (a paint commit, not a filter).
    Surface s = opaqueFill(32, 16, Pixel{0, 0, 0, 0});
    for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 16; ++x) s.set(x, y, Pixel{255, 0, 0, 255});
    const BrushParams p = hardBrush(5.0f);
    SmudgeState st;
    for (float x = 13.0f; x <= 19.0f; x += 1.5f) {
        const Dab d = openclip::dabFromPressure(p, x, 8, 1.0f, 0, 0, 0);
        openclip::smudgeDab(s, d, p, st, 1.0f);
    }
    const Pixel out = s.get(19, 8);
    CHECK(out.a > 0);
    CHECK(premultSafe(s));
}

// ---- Dodge / burn --------------------------------------------------------

TEST(retouch_dodge_and_burn_keep_alpha) {
    Surface s = opaqueFill(24, 24, Pixel{128, 128, 128, 255});
    const BrushParams p = hardBrush(6.0f);
    const Dab d = openclip::dabFromPressure(p, 12, 12, 1.0f, 0, 0, 0);
    openclip::dodgeBurnDab(s, d, p, /*burn=*/false);
    const Pixel lit = s.get(12, 12);
    CHECK(lit.r > 128);
    CHECK_EQ(lit.a, 255);  // alpha untouched (M3 contract)
    Surface b = opaqueFill(24, 24, Pixel{128, 128, 128, 255});
    openclip::dodgeBurnDab(b, d, p, /*burn=*/true);
    const Pixel dark = b.get(12, 12);
    CHECK(dark.r < 128);
    CHECK_EQ(dark.a, 255);
    // Fully transparent pixels are never inked by dodge/burn.
    Surface t(16, 16);
    const Dab td = openclip::dabFromPressure(p, 8, 8, 1.0f, 0, 0, 0);
    openclip::dodgeBurnDab(t, td, p, false);
    CHECK_EQ(t.get(8, 8).a, 0);
    CHECK(premultSafe(s));
    CHECK(premultSafe(b));
}

TEST(retouch_dodge_is_pressure_sensitive) {
    const BrushParams p = hardBrush(8.0f);
    Surface soft = opaqueFill(24, 24, Pixel{100, 100, 100, 255});
    Surface full = opaqueFill(24, 24, Pixel{100, 100, 100, 255});
    openclip::dodgeBurnDab(
        soft, openclip::dabFromPressure(p, 12, 12, 0.25f, 0, 0, 0), p, false);
    openclip::dodgeBurnDab(
        full, openclip::dabFromPressure(p, 12, 12, 1.0f, 0, 0, 0), p, false);
    CHECK(soft.get(12, 12).r > 100);
    CHECK(full.get(12, 12).r > soft.get(12, 12).r);
}

// ---- Pattern brush -------------------------------------------------------

TEST(retouch_pattern_spacing) {
    PatternSpec spec;
    spec.spacing = 12.0f;
    spec.scale = 1.0f;
    spec.jitterPos = 0.0f;
    spec.jitterScale = 0.0f;
    spec.jitterRot = 0.0f;
    spec.alongPath = true;
    PatternWalker w(spec);
    w.begin(0, 0, 1.0f);
    w.to(48, 0, 1.0f);
    const std::vector<PatternStamp> stamps = w.take();
    // 0, 12, 24, 36, 48 = five evenly spaced stamps.
    CHECK_EQ(stamps.size(), (std::size_t)5);
    CHECK_EQ(w.count(), (std::size_t)5);
    CHECK_EQ(stamps.front().x, 0.0f);
    for (std::size_t i = 0; i < stamps.size(); ++i) {
        CHECK(std::fabs(stamps[i].x - 12.0f * static_cast<float>(i)) < 0.01f);
        CHECK(std::fabs(stamps[i].y) < 0.01f);
        // Along the +x path the tangent angle is 0.
        CHECK(std::fabs(stamps[i].angleDeg) < 0.01f);
    }
    // take() drains: nothing left for a second call.
    CHECK_EQ(w.take().size(), (std::size_t)0);
}

TEST(retouch_pattern_rotation_follows_path) {
    PatternSpec spec;
    spec.spacing = 10.0f;
    spec.jitterPos = spec.jitterScale = spec.jitterRot = 0.0f;
    spec.alongPath = true;
    PatternWalker w(spec);
    w.begin(0, 0, 1.0f);
    w.to(0, 30, 1.0f);  // downward: tangent angle = +90 deg
    const auto stamps = w.take();
    CHECK_EQ(stamps.size(), (std::size_t)4);  // 0, 10, 20, 30
    // The first stamp (begin) has no direction yet; every following
    // stamp follows the segment tangent (+90 deg downward).
    CHECK(std::fabs(stamps.front().angleDeg) < 0.01f);
    bool allRight = stamps.size() > 1;
    for (std::size_t i = 1; i < stamps.size(); ++i)
        if (std::fabs(stamps[i].angleDeg - 90.0f) > 0.01f) allRight = false;
    CHECK(allRight);
    // Rotation bonus adds to the tangent.
    PatternSpec fixed = spec;
    fixed.alongPath = false;
    fixed.rotationDeg = 45.0f;
    PatternWalker f(fixed);
    f.begin(0, 0, 1.0f);
    CHECK(std::fabs(f.take().front().angleDeg - 45.0f) < 0.01f);
}

TEST(retouch_pattern_jitter_is_deterministic) {
    PatternSpec spec;
    spec.spacing = 8.0f;
    spec.jitterPos = 2.0f;
    spec.jitterScale = 0.2f;
    spec.jitterRot = 30.0f;
    spec.seed = 1234u;
    PatternWalker a(spec);
    a.begin(0, 0, 1.0f);
    a.to(40, 0, 1.0f);
    PatternWalker b(spec);
    b.begin(0, 0, 1.0f);
    b.to(40, 0, 1.0f);
    const auto sa = a.take();
    const auto sb = b.take();
    CHECK_EQ(sa.size(), sb.size());
    bool same = true;
    for (std::size_t i = 0; i < sa.size() && i < sb.size(); ++i) {
        if (sa[i].x != sb[i].x || sa[i].y != sb[i].y ||
            sa[i].angleDeg != sb[i].angleDeg || sa[i].scale != sb[i].scale)
            same = false;
    }
    CHECK(same);
    // A different seed must produce a different stream (jitter is real).
    spec.seed = 99u;
    PatternWalker c(spec);
    c.begin(0, 0, 1.0f);
    c.to(40, 0, 1.0f);
    const auto sc = c.take();
    bool differs = false;
    for (std::size_t i = 0; i < sc.size() && i < sa.size(); ++i)
        if (sc[i].x != sa[i].x || sc[i].angleDeg != sa[i].angleDeg)
            differs = true;
    CHECK(differs);
}

TEST(retouch_pattern_stamp_rotation) {
    // A 9x9 tile with a single opaque pixel at the right middle: after a
    // +90 deg clockwise rotation around the stamp centre it lands at the
    // bottom middle.
    Surface tile(9, 9);
    tile.set(8, 4, Pixel{255, 0, 0, 255});
    Surface dst(48, 48);
    PatternStamp st;
    st.x = 24.5f;  // pixel-centre aligned: deterministic nearest sampling
    st.y = 24.5f;
    st.angleDeg = 90.0f;
    st.scale = 1.0f;
    openclip::stampPattern(dst, tile, st, 1.0f);
    for (int y = 0; y < 48; ++y)
        for (int x = 0; x < 48; ++x) {
            const Pixel p = dst.get(x, y);
            if (p.a == 0) continue;
            CHECK_EQ(x, 24);
            CHECK_EQ(y, 28);
            CHECK_EQ(p.r, 255);
        }
    // Unrotated: the same pixel stays on the right.
    Surface d2(48, 48);
    st.angleDeg = 0.0f;
    openclip::stampPattern(d2, tile, st, 1.0f);
    CHECK_EQ(d2.get(28, 24).a, 255);
    CHECK_EQ(d2.get(24, 28).a, 0);
}

TEST(retouch_pattern_scale_and_opacity) {
    Surface tile(8, 8);
    for (int y = 0; y < 8; ++y)
        for (int x = 0; x < 8; ++x) tile.set(x, y, Pixel{0, 0, 255, 255});
    Surface dst(64, 64);
    PatternStamp st;
    st.x = 32;
    st.y = 32;
    st.angleDeg = 0;
    st.scale = 2.0f;  // 16px stamp -> 8..56? no: 32 +- 8
    openclip::stampPattern(dst, tile, st, 1.0f);
    CHECK_EQ(dst.get(32, 32).a, 255);
    CHECK_EQ(dst.get(32 + 7, 32).a, 255);
    CHECK_EQ(dst.get(32 + 9, 32).a, 0);
    // Half opacity on an empty destination -> half alpha.
    Surface d2(64, 64);
    openclip::stampPattern(d2, tile, st, 0.5f);
    const Pixel half = d2.get(32, 32);
    CHECK(half.a > 120 && half.a < 136);
    CHECK(premultSafe(d2));
}

TEST(retouch_motifs) {
    Surface dots;
    std::string err;
    CHECK(openclip::proceduralMotif("dots", 32, 255, 0, 0, dots, err));
    CHECK_EQ(dots.get(16, 16).a, 255);
    CHECK_EQ(dots.get(0, 0).a, 0);
    CHECK_EQ(dots.get(16, 16).r, 255);
    Surface ring;
    CHECK(openclip::proceduralMotif("ring", 32, 0, 255, 0, ring, err));
    CHECK_EQ(ring.get(16, 16).a, 0);   // hollow centre
    CHECK(ring.get(3, 16).a > 0);      // rim
    Surface star, cross;
    CHECK(openclip::proceduralMotif("star", 32, 0, 0, 0, star, err));
    CHECK(star.get(16, 6).a > 0);  // top tip (outer radius)
    CHECK_EQ(star.get(0, 0).a, 0);
    CHECK(openclip::proceduralMotif("cross", 32, 0, 0, 0, cross, err));
    CHECK(cross.get(16, 4).a > 0);
    CHECK_EQ(cross.get(2, 2).a, 0);
    Surface bad;
    CHECK(openclip::proceduralMotif("nope", 32, 0, 0, 0, bad, err) == false);
    CHECK(openclip::proceduralMotif("dots", 2, 0, 0, 0, bad, err) == false);
}
