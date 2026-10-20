// SPDX-License-Identifier: GPL-3.0-or-later
// V+1 alpha-erase tests (ARCHITECTURE.md "V+1 additions"): dest-out
// semantics of eraseDab, legacy paintDab-white frozen, undo restore,
// mask rule (erase = black mask paint, pixels untouched).
#include "test.h"

#include <string>
#include <vector>

#include "openclip/brush.h"
#include "openclip/canvas.h"
#include "openclip/surface.h"
#include "openclip/undo.h"

using openclip::BrushParams;
using openclip::Canvas;
using openclip::dabFromPressure;
using openclip::eraseDab;
using openclip::History;
using openclip::paintDab;
using openclip::paintMaskDab;
using openclip::Pixel;
using openclip::Surface;

namespace {

Surface solid(int w, int h, std::uint8_t r, std::uint8_t g,
              std::uint8_t b, std::uint8_t a) {
    Surface s;
    s.resize(w, h);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x)
            s.set(x, y, Pixel{r, g, b, a});
    return s;
}

bool premultOk(const Surface& s) {
    for (int y = 0; y < s.height(); ++y)
        for (int x = 0; x < s.width(); ++x) {
            const Pixel p = s.get(x, y);
            if (p.r > p.a || p.g > p.a || p.b > p.a) return false;
        }
    return true;
}

}  // namespace

TEST(alphaEraseReducesAlpha) {
    Surface s = solid(32, 32, 200, 100, 50, 255);
    BrushParams p;
    p.hardness = 1.0f;
    eraseDab(s, dabFromPressure(p, 16, 16, 1.0f, 0, 0, 0), p);
    // Center erased, far corners untouched, premult invariant holds.
    CHECK(s.get(16, 16).a < 255);
    CHECK_EQ(s.get(0, 0).a, 255);
    CHECK_EQ(s.get(31, 31).a, 255);
    CHECK(premultOk(s));
}

TEST(alphaErasePressureScales) {
    BrushParams p;
    p.hardness = 1.0f;
    Surface hard = solid(32, 32, 200, 100, 50, 255);
    eraseDab(hard, dabFromPressure(p, 16, 16, 1.0f, 0, 0, 0), p);
    Surface soft = solid(32, 32, 200, 100, 50, 255);
    eraseDab(soft, dabFromPressure(p, 16, 16, 0.2f, 0, 0, 0), p);
    // Full pressure erases strictly more on the center pixel.
    CHECK(hard.get(16, 16).a < soft.get(16, 16).a);
    CHECK(premultOk(hard));
    CHECK(premultOk(soft));
}

TEST(alphaEraseUndoRestores) {
    Canvas c(64, 64);
    c.addLayer("l");
    Surface painted = solid(64, 64, 10, 200, 90, 255);
    c.layer(0).surface.bytes().swap(painted.bytes());
    std::vector<std::uint8_t> before = c.layer(0).surface.bytes();
    History h;
    h.beginStroke();
    for (int ty = 0; ty < c.layer(0).surface.tilesY(); ++ty)
        for (int tx = 0; tx < c.layer(0).surface.tilesX(); ++tx)
            h.touchTile(c, 0, tx, ty);
    BrushParams p;
    eraseDab(c.layer(0).surface, dabFromPressure(p, 32, 32, 1.0f, 0, 0, 0),
             p);
    h.endStroke(c);
    CHECK(c.layer(0).surface.bytes() != before);
    CHECK(h.undo(c));
    CHECK(c.layer(0).surface.bytes() == before);
}

TEST(alphaEraseLegacyModeKept) {
    // Legacy "eraser" = white source-over (M2/slice-5 frozen behavior):
    // paintDab with the session background color still lightens and
    // keeps full alpha — the compat lock for old presets.
    Surface s = solid(32, 32, 0, 64, 128, 255);
    BrushParams p;
    p.hardness = 1.0f;
    paintDab(s, dabFromPressure(p, 16, 16, 1.0f, 255, 255, 255), p);
    const Pixel c = s.get(16, 16);
    CHECK(c.r > 0 && c.g > 64 && c.b > 128);
    CHECK_EQ(c.a, 255);
    CHECK(premultOk(s));
}

TEST(alphaEraseOnMaskPaintsBlack) {
    // Gomme on a mask target = black mask paint, pixels untouched.
    Canvas c(64, 64);
    c.addLayer("l");
    Surface painted = solid(64, 64, 10, 200, 90, 255);
    c.layer(0).surface.bytes().swap(painted.bytes());
    c.layer(0).addMask(64, 64);
    const std::vector<std::uint8_t> pxBefore =
        c.layer(0).surface.bytes();
    const std::vector<std::uint8_t> maskBefore =
        c.layer(0).mask.bytes();
    BrushParams p;
    p.hardness = 1.0f;
    paintMaskDab(c.layer(0).mask, dabFromPressure(p, 32, 32, 1.0f, 0, 0, 0),
                 p);
    CHECK(c.layer(0).mask.bytes() != maskBefore);
    CHECK(c.layer(0).mask.get(32, 32).a < 255);
    CHECK(c.layer(0).surface.bytes() == pxBefore);
}
