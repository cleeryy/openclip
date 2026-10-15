// SPDX-License-Identifier: GPL-3.0-or-later
// M5 layer-system tests: masks, clipping, blend/opacity setters, moveLayer.
#include "test.h"

#include <string>

#include "openclip/blend.h"
#include "openclip/canvas.h"
#include "openclip/undo.h"

using openclip::BlendMode;
using openclip::Canvas;
using openclip::History;
using openclip::Layer;
using openclip::Pixel;
using openclip::Surface;

TEST(mask_multiply_hides) {
    Canvas c(8, 8);
    const std::size_t li = c.addLayer("paint");
    for (int y = 0; y < 8; ++y)
        for (int x = 0; x < 8; ++x)
            c.layer(li).surface.set(x, y, Pixel{255, 0, 0, 255});
    c.layer(li).addMask(8, 8);
    CHECK(c.layer(li).hasMask);
    // Black out a 4x4 corner of the mask (alpha 0 = hide).
    for (int y = 0; y < 4; ++y)
        for (int x = 0; x < 4; ++x)
            c.layer(li).mask.set(x, y, Pixel{0, 0, 0, 0});
    Surface out;
    c.composite(out);
    CHECK_EQ(out.get(0, 0).a, 0);    // masked away
    CHECK_EQ(out.get(7, 7).r, 255);  // white mask = full show
    CHECK_EQ(out.get(7, 7).a, 255);
    // Half mask alpha halves the pixel alpha.
    c.layer(li).mask.set(7, 7, Pixel{128, 128, 128, 128});
    c.composite(out);
    CHECK_EQ(out.get(7, 7).a, 128);
}

TEST(clipped_respects_stencil) {
    Canvas c(8, 8);
    const std::size_t stencil = c.addLayer("stencil");
    c.layer(stencil).visible = false;  // base hidden: stencil truly sparse
    const std::size_t mid = c.addLayer("mid");
    c.layer(mid).surface.set(2, 2, Pixel{255, 0, 0, 255});
    const std::size_t clip = c.addLayer("clip");
    c.layer(clip).clipped = true;
    c.layer(clip).surface.set(2, 2, Pixel{0, 0, 255, 255});
    c.layer(clip).surface.set(5, 5, Pixel{0, 0, 255, 255});
    Surface out;
    c.composite(out);
    CHECK_EQ(out.get(2, 2).b, 255);  // painted over the stencil
    CHECK_EQ(out.get(5, 5).a, 0);    // outside stencil: skipped
    // Unclip restores the contribution.
    c.layer(clip).clipped = false;
    c.composite(out);
    CHECK_EQ(out.get(5, 5).b, 255);
}

TEST(mask_undo_restores) {
    Canvas c(64, 64);
    const std::size_t li = c.addLayer("m");
    for (int y = 0; y < 64; ++y)
        for (int x = 0; x < 64; ++x)
            c.layer(li).surface.set(x, y, Pixel{0, 255, 0, 255});
    c.layer(li).addMask(64, 64);
    History h;
    h.beginStroke();
    h.touchTile(c, li, 0, 0, true);
    c.layer(li).mask.set(3, 3, Pixel{0, 0, 0, 0});
    h.endStroke(c);
    Surface out;
    c.composite(out);
    CHECK_EQ(out.get(3, 3).a, 0);
    CHECK(h.undo(c));
    c.composite(out);
    CHECK_EQ(out.get(3, 3).a, 255);
    CHECK(h.redo(c));
    c.composite(out);
    CHECK_EQ(out.get(3, 3).a, 0);
}

TEST(blend_name_mapping) {
    CHECK_EQ(openclip::blendFromName("multiply"), BlendMode::Multiply);
    CHECK_EQ(openclip::blendFromName("svg:screen"), BlendMode::Screen);
    CHECK_EQ(openclip::blendFromName("overlay"), BlendMode::Overlay);
    CHECK_EQ(openclip::blendFromName("plus"), BlendMode::Add);
    CHECK_EQ(openclip::blendFromName("soft-light"), BlendMode::SoftLight);
    CHECK_EQ(openclip::blendFromName("nope"), BlendMode::Normal);
    CHECK_EQ(std::string(openclip::blendToName(BlendMode::Multiply)),
             std::string("svg:multiply"));
    CHECK_EQ(std::string(openclip::blendToName(BlendMode::Normal)),
             std::string("svg:normal"));
}

TEST(group_with_masked_child) {
    Canvas c(8, 8);
    const std::size_t gi = c.addGroup("g");
    c.layer(gi).children.push_back(Layer());
    Layer& ch = c.layer(gi).children.back();
    ch.name = "kid";
    ch.surface.resize(8, 8);
    for (int y = 0; y < 8; ++y)
        for (int x = 0; x < 8; ++x) ch.surface.set(x, y, Pixel{0, 0, 255, 255});
    ch.addMask(8, 8);
    ch.mask.set(0, 0, Pixel{0, 0, 0, 0});
    Surface out;
    c.composite(out);
    CHECK_EQ(out.get(0, 0).a, 0);
    CHECK_EQ(out.get(7, 7).b, 255);
}

TEST(opacity_visibility_move_keep_active) {
    Canvas c(8, 8);
    const std::size_t a = c.addLayer("a");
    const std::size_t b = c.addLayer("b");
    c.layer(a).surface.set(0, 0, Pixel{255, 0, 0, 255});
    c.layer(b).surface.set(0, 0, Pixel{0, 0, 255, 255});
    c.setActiveLayer(1);
    // Visibility off hides the top layer.
    c.layer(b).visible = false;
    Surface out;
    c.composite(out);
    CHECK_EQ(out.get(0, 0).r, 255);
    c.layer(b).visible = true;
    // Half opacity blends the top layer instead of covering.
    c.layer(b).opacity = 0.5f;
    c.composite(out);
    CHECK(out.get(0, 0).r < 255);
    CHECK(out.get(0, 0).b > 0);
    // Move keeps the active index value (no retarget).
    c.moveLayer(1, 0);
    CHECK_EQ(c.activeLayer(), 1);
    c.composite(out);
    CHECK_EQ(out.get(0, 0).r, 255);  // red now on top
}
