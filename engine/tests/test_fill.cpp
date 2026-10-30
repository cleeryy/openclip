// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip T1 engine — flood-fill bucket + magic wand tests.
#include "test.h"

#include <string>
#include <vector>

#include "openclip/fill.h"
#include "openclip/surface.h"

using openclip::FillParams;
using openclip::Pixel;
using openclip::Surface;

namespace {

// Left half dark gray, right half light gray, all opaque.
Surface halves() {
    Surface s(32, 16);
    for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 32; ++x)
            s.set(x, y, x < 16 ? Pixel{40, 40, 40, 255}
                               : Pixel{200, 200, 200, 255});
    return s;
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

TEST(fill_exact_respects_boundary) {
    Surface s = halves();
    FillParams p;
    p.tolerance = 0.0f;
    p.antialias = false;
    std::string err;
    CHECK(openclip::floodFill(s, 4, 8, 255, 0, 0, p, nullptr, 0, err));
    // Left half repainted red...
    CHECK_EQ(s.get(2, 2).r, 255);
    CHECK_EQ(s.get(2, 2).g, 0);
    CHECK_EQ(s.get(14, 8).r, 255);
    // ...right half byte-identical (tol 0 never crosses the luma gap).
    CHECK_EQ(s.get(20, 8).r, 200);
    CHECK_EQ(s.get(20, 8).g, 200);
    CHECK_EQ(s.get(31, 15).r, 200);
    CHECK(rgbLeAlpha(s));
}

TEST(fill_tolerance_crosses_near_colors) {
    Surface s(32, 8);
    for (int y = 0; y < 8; ++y)
        for (int x = 0; x < 32; ++x) {
            const int v = 100 + x;  // smooth ramp 100..131
            s.set(x, y, Pixel{(std::uint8_t)v, (std::uint8_t)v,
                              (std::uint8_t)v, 255});
        }
    FillParams p;
    p.tolerance = 0.2f;  // 51 luma units: crosses the whole ramp
    p.antialias = false;
    std::string err;
    CHECK(openclip::floodFill(s, 0, 4, 0, 0, 255, p, nullptr, 0, err));
    for (int x = 0; x < 32; ++x) CHECK_EQ(s.get(x, 4).b, 255);
    // Narrow tolerance stops early: seed x=0 (v=100), tol 0.01 = 2.55.
    Surface t(32, 8);
    for (int y = 0; y < 8; ++y)
        for (int x = 0; x < 32; ++x) {
            const int v = 100 + x;
            t.set(x, y, Pixel{(std::uint8_t)v, (std::uint8_t)v,
                              (std::uint8_t)v, 255});
        }
    FillParams q;
    q.tolerance = 0.01f;
    q.antialias = false;
    CHECK(openclip::floodFill(t, 0, 4, 0, 0, 255, q, nullptr, 0, err));
    CHECK_EQ(t.get(0, 4).b, 255);
    CHECK_EQ(t.get(2, 4).b, 255);  // v=102 within 2.55
    CHECK_EQ(t.get(10, 4).b, 110);  // v=110 untouched
}

TEST(fill_antialias_softens_edge) {
    // Transparent canvas with an opaque white bar: fill the
    // transparent side with red — edge members must carry partial
    // alpha (soft rim), rgb still <= alpha.
    Surface s(24, 8);
    for (int y = 0; y < 8; ++y)
        for (int x = 0; x < 24; ++x)
            s.set(x, y, x < 12 ? Pixel{0, 0, 0, 0}
                               : Pixel{255, 255, 255, 255});
    FillParams p;
    p.tolerance = 0.05f;
    p.antialias = true;
    std::string err;
    CHECK(openclip::floodFill(s, 2, 4, 255, 0, 0, p, nullptr, 0, err));
    CHECK_EQ(s.get(2, 4).r, 255);  // deep interior: full ink
    bool partial = false;
    for (int y = 0; y < 8; ++y) {
        const auto px = s.get(11, y);
        if (px.a > 0 && px.a < 255) partial = true;
    }
    CHECK(partial);  // the frontier carries partial alpha
    CHECK(rgbLeAlpha(s));
    // Same scene without antialias: the frontier column is all-or-ink.
    Surface t(24, 8);
    for (int y = 0; y < 8; ++y)
        for (int x = 0; x < 24; ++x)
            t.set(x, y, x < 12 ? Pixel{0, 0, 0, 0}
                               : Pixel{255, 255, 255, 255});
    FillParams q;
    q.tolerance = 0.05f;
    q.antialias = false;
    CHECK(openclip::floodFill(t, 2, 4, 255, 0, 0, q, nullptr, 0, err));
    CHECK_EQ(t.get(10, 4).a, 255);
}

TEST(fill_respects_mask) {
    Surface s = halves();
    // Mask: only the top half is selected.
    std::vector<std::uint8_t> mask(32 * 16, 0);
    for (int y = 0; y < 8; ++y)
        for (int x = 0; x < 32; ++x) mask[y * 32 + x] = 255;
    FillParams p;
    p.tolerance = 1.0f;  // would take everything...
    p.antialias = false;
    std::string err;
    CHECK(openclip::floodFill(s, 4, 2, 0, 255, 0, p, mask.data(), 32, err));
    CHECK_EQ(s.get(4, 2).g, 255);  // selected: painted
    CHECK_EQ(s.get(4, 12).r, 40);  // masked out: byte-identical
    CHECK_EQ(s.get(4, 12).g, 40);
    CHECK_EQ(s.get(20, 12).r, 200);
}

TEST(fill_undo_restores) {
    // Engine-level byte-identity: snapshot, fill, restore = identical
    // (the shim wraps this in touchTile/endStroke; the invariant the
    // undo relies on is exact restoration).
    Surface s = halves();
    const auto ref = s.bytes();
    FillParams p;
    p.tolerance = 0.5f;
    p.antialias = true;
    p.expandPx = 1;
    std::string err;
    CHECK(openclip::floodFill(s, 4, 8, 10, 200, 90, p, nullptr, 0, err));
    CHECK(s.bytes() != ref);
    s.bytes() = ref;  // undo = tile restore
    CHECK(s.bytes() == ref);
}

TEST(fill_bad_seed_no_op) {
    Surface s = halves();
    const auto ref = s.bytes();
    FillParams p;
    std::string err;
    CHECK(!openclip::floodFill(s, -1, 8, 255, 0, 0, p, nullptr, 0, err));
    CHECK(!err.empty());
    CHECK(s.bytes() == ref);
    CHECK(!openclip::floodFill(s, 32, 8, 255, 0, 0, p, nullptr, 0, err));
    CHECK(s.bytes() == ref);
    CHECK(!openclip::floodFill(s, 4, 99, 255, 0, 0, p, nullptr, 0, err));
    CHECK(s.bytes() == ref);
    // Empty surface first (w=0 h=0 must not crash): default Surface.
    Surface empty;
    CHECK(!openclip::floodFill(empty, 0, 0, 255, 0, 0, p, nullptr, 0, err));
    // Invalid params refused.
    FillParams bad;
    bad.tolerance = 2.0f;
    CHECK(!openclip::floodFill(s, 4, 8, 255, 0, 0, bad, nullptr, 0, err));
    CHECK(s.bytes() == ref);
    bad.tolerance = 0.1f;
    bad.expandPx = 5;
    CHECK(!openclip::floodFill(s, 4, 8, 255, 0, 0, bad, nullptr, 0, err));
    CHECK(s.bytes() == ref);
}

// --- Magic wand (§4): same kernel, mask-output envelope ---

TEST(wand_exact_boundary) {
    Surface s = halves();
    std::vector<std::uint8_t> mask(32 * 16, 0);
    std::string err;
    CHECK(openclip::magicWand(s, 4, 8, 0.0f, 0, mask.data(), 32, err));
    for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 32; ++x)
            CHECK_EQ(mask[y * 32 + x], x < 16 ? 255 : 0);
}

TEST(wand_tolerance_expands) {
    Surface s(32, 8);
    for (int y = 0; y < 8; ++y)
        for (int x = 0; x < 32; ++x) {
            const int v = 100 + x;
            s.set(x, y, Pixel{(std::uint8_t)v, (std::uint8_t)v,
                              (std::uint8_t)v, 255});
        }
    std::vector<std::uint8_t> mask(32 * 8, 0);
    std::string err;
    CHECK(openclip::magicWand(s, 0, 4, 0.2f, 0, mask.data(), 32, err));
    for (int x = 0; x < 32; ++x) CHECK_EQ(mask[4 * 32 + x], 255);
    // Source pixels untouched by the wand (it never paints).
    for (int x = 0; x < 32; ++x) CHECK_EQ(s.get(x, 4).r, 100 + x);
}

TEST(wand_add_subtract) {
    Surface s = halves();
    std::vector<std::uint8_t> mask(32 * 16, 0);
    std::string err;
    CHECK(openclip::magicWand(s, 4, 8, 0.0f, 0, mask.data(), 32, err));
    // Add the right half: full canvas selected.
    CHECK(openclip::magicWand(s, 20, 8, 0.0f, 1, mask.data(), 32, err));
    for (std::size_t i = 0; i < mask.size(); ++i) CHECK_EQ(mask[i], 255);
    // Subtract the left half: right half remains.
    CHECK(openclip::magicWand(s, 4, 8, 0.0f, 2, mask.data(), 32, err));
    for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 32; ++x)
            CHECK_EQ(mask[y * 32 + x], x < 16 ? 0 : 255);
}

TEST(wand_bad_seed_no_op) {
    Surface s = halves();
    std::vector<std::uint8_t> mask(32 * 16, 7);
    const auto ref = mask;
    std::string err;
    CHECK(!openclip::magicWand(s, -1, 0, 0.1f, 0, mask.data(), 32, err));
    CHECK(!err.empty());
    CHECK(mask == ref);
    CHECK(!openclip::magicWand(s, 4, 8, 5.0f, 0, mask.data(), 32, err));
    CHECK(mask == ref);
    CHECK(!openclip::magicWand(s, 4, 8, 0.1f, 9, mask.data(), 32, err));
    CHECK(mask == ref);
    Surface empty;
    CHECK(!openclip::magicWand(empty, 0, 0, 0.1f, 0, mask.data(), 32, err));
    CHECK(mask == ref);
}

TEST(wand_mask_full_res) {
    Surface s = halves();
    const int w = s.width(), h = s.height();
    std::vector<std::uint8_t> mask(static_cast<std::size_t>(w) * h, 0);
    std::string err;
    CHECK(openclip::magicWand(s, 20, 3, 0.0f, 0, mask.data(), w, err));
    CHECK_EQ((int)mask.size(), w * h);  // full-resolution output
    long selected = 0;
    for (auto v : mask) {
        CHECK(v == 0 || v == 255);  // strictly binary
        if (v) ++selected;
    }
    CHECK_EQ(selected, (16L * 16L));  // exactly the right half
}

// B1 (ARCHITECTURE.md "B1 additions" §1): gap closing. A broken line
// with a 4 px hole leaks under the T1 kernel; dilating the barrier by
// 2 px (gapPx = 2) bridges gaps up to 4 px, so the fill stays above.
TEST(fill_gap_closing_bridges_small_gaps) {
    auto brokenLine = [] {
        Surface s(64, 64);
        for (int y = 0; y < 64; ++y)
            for (int x = 0; x < 64; ++x) {
                const bool line = y == 32 && (x < 30 || x >= 34);
                s.set(x, y, line ? Pixel{0, 0, 0, 255}
                                 : Pixel{255, 255, 255, 255});
            }
        return s;
    };
    FillParams p;
    p.tolerance = 0.0f;
    p.antialias = false;
    std::string err;
    Surface leak = brokenLine();
    CHECK(openclip::floodFill(leak, 32, 10, 255, 0, 0, p, nullptr, 0, err));
    // gapPx 0: the 4 px hole lets the fill reach the lower half
    // (red = painted: g drops to 0).
    CHECK_EQ(leak.get(32, 50).g, 0);
    // gapPx 1 only closes 2 px: still leaks.
    Surface half = brokenLine();
    p.gapPx = 1;
    CHECK(openclip::floodFill(half, 32, 10, 255, 0, 0, p, nullptr, 0, err));
    CHECK_EQ(half.get(32, 50).g, 0);
    // gapPx 2 closes the 4 px hole: the lower half stays byte-identical.
    Surface closed = brokenLine();
    p.gapPx = 2;
    CHECK(openclip::floodFill(closed, 32, 10, 255, 0, 0, p, nullptr, 0, err));
    CHECK_EQ(closed.get(32, 50).r, 255);
    CHECK_EQ(closed.get(32, 50).g, 255);
    CHECK_EQ(closed.get(10, 10).r, 255);
    CHECK_EQ(closed.get(10, 10).g, 0);
    // The grow-back restores the rim eaten by the closing: 1 px above
    // the line is painted again, the line itself never is.
    CHECK_EQ(closed.get(10, 31).g, 0);
    CHECK_EQ(closed.get(10, 32).r, 0);
    // Out-of-range gapPx = false + surface byte-identical.
    Surface guard = brokenLine();
    const Surface before = guard;
    p.gapPx = 17;
    CHECK(openclip::floodFill(guard, 32, 10, 255, 0, 0, p, nullptr, 0, err) ==
          false);
    bool same = true;
    for (int y = 0; y < 64; ++y)
        for (int x = 0; x < 64; ++x) {
            const Pixel a = before.get(x, y);
            const Pixel b = guard.get(x, y);
            if (a.r != b.r || a.g != b.g || a.b != b.b || a.a != b.a)
                same = false;
        }
    CHECK(same);
}

// B1 §1 surround: "draw the enclosure, fill it". Normal mode fills the
// seed's cavity only; surround fills EVERY enclosed cavity in one
// gesture, never the barrier itself.
TEST(fill_surround_fills_enclosed_cavities) {
    auto twoBoxes = [] {
        Surface s(96, 64);
        for (int y = 0; y < 64; ++y)
            for (int x = 0; x < 96; ++x) s.set(x, y, Pixel{255, 255, 255, 255});
        auto border = [&](int x0, int y0, int x1, int y1) {
            for (int x = x0; x <= x1; ++x) {
                s.set(x, y0, Pixel{0, 0, 0, 255});
                s.set(x, y1, Pixel{0, 0, 0, 255});
            }
            for (int y = y0; y <= y1; ++y) {
                s.set(x0, y, Pixel{0, 0, 0, 255});
                s.set(x1, y, Pixel{0, 0, 0, 255});
            }
        };
        border(8, 8, 24, 24);
        border(56, 8, 72, 24);
        return s;
    };
    FillParams p;
    p.tolerance = 0.0f;
    p.antialias = false;
    std::string err;
    Surface normal = twoBoxes();
    CHECK(openclip::floodFill(normal, 16, 16, 255, 0, 0, p, nullptr, 0, err));
    CHECK_EQ(normal.get(16, 16).g, 0);    // seed cavity painted
    CHECK_EQ(normal.get(64, 16).g, 255);  // other cavity untouched
    Surface surround = twoBoxes();
    p.surround = true;
    CHECK(openclip::floodFill(surround, 16, 16, 0, 0, 255, p, nullptr, 0,
                              err));
    CHECK_EQ(surround.get(16, 16).r, 0);  // blue there
    CHECK_EQ(surround.get(16, 16).b, 255);
    CHECK_EQ(surround.get(64, 16).b, 255);  // both cavities, one gesture
    CHECK_EQ(surround.get(8, 16).b, 0);     // barrier never painted
    CHECK_EQ(surround.get(8, 16).a, 255);
    // Seed outside every cavity = refused + surface byte-identical.
    Surface outside = twoBoxes();
    const Surface before = outside;
    CHECK(openclip::floodFill(outside, 2, 2, 255, 0, 0, p, nullptr, 0, err) ==
          false);
    bool same = true;
    for (int y = 0; y < 64; ++y)
        for (int x = 0; x < 96; ++x) {
            const Pixel a = before.get(x, y);
            const Pixel b = outside.get(x, y);
            if (a.r != b.r || a.g != b.g || a.b != b.b || a.a != b.a)
                same = false;
        }
    CHECK(same);
}

// B1 §1: surround + gap closing together (the actual "broken enclosure"
// gesture: a 2 px hole in the loop still fills the inside only).
TEST(fill_surround_closes_broken_enclosure) {
    Surface s(64, 64);
    for (int y = 0; y < 64; ++y)
        for (int x = 0; x < 64; ++x) s.set(x, y, Pixel{255, 255, 255, 255});
    for (int x = 16; x <= 48; ++x) {
        if (x >= 30 && x <= 31) continue;  // 2 px hole in the top edge
        s.set(x, 16, Pixel{0, 0, 0, 255});
        s.set(x, 48, Pixel{0, 0, 0, 255});
    }
    for (int y = 16; y <= 48; ++y) {
        s.set(16, y, Pixel{0, 0, 0, 255});
        s.set(48, y, Pixel{0, 0, 0, 255});
    }
    FillParams p;
    p.tolerance = 0.0f;
    p.antialias = false;
    p.gapPx = 1;
    p.surround = true;
    std::string err;
    // gapPx 1 closes the 2 px hole; without it the border flood escapes
    // through the hole and nothing is enclosed.
    Surface closed = s;
    CHECK(openclip::floodFill(closed, 32, 32, 0, 255, 0, p, nullptr, 0, err));
    CHECK_EQ(closed.get(32, 32).g, 255);
    CHECK_EQ(closed.get(20, 20).r, 0);
    CHECK_EQ(closed.get(2, 2).g, 255);  // outside untouched
    Surface open = s;
    p.gapPx = 0;
    CHECK(openclip::floodFill(open, 32, 32, 0, 255, 0, p, nullptr, 0, err) ==
          false);
}
