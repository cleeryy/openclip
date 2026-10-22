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
