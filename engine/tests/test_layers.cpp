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

// ---- A2: pass-through groups (issue #29) ---------------------------

namespace {

void fillAll(openclip::Surface& s, int w, int h, Pixel p) {
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) s.set(x, y, p);
}

// Append one raster child to a group, sized and filled.
Layer& addChild(Layer& g, const char* name, int w, int h, Pixel p) {
    g.children.push_back(Layer());
    Layer& ch = g.children.back();
    ch.name = name;
    ch.surface.resize(w, h);
    fillAll(ch.surface, w, h, p);
    return ch;
}

}  // namespace

// A2: a pass-through group must composite exactly like its children
// ungrouped (same order, own blend modes, group opacity 1).
TEST(passthrough_group_equals_ungrouped) {
    Canvas grouped(8, 8), flat(8, 8);
    for (Canvas* c : {&grouped, &flat}) {
        const std::size_t b = c->addLayer("base");
        fillAll(c->layer(b).surface, 8, 8, Pixel{40, 90, 160, 255});
    }
    // Grouped: one pass-through group holding multiply + screen kids.
    const std::size_t gi = grouped.addGroup("g");
    CHECK(grouped.layer(gi).passThrough);
    Layer& mul = addChild(grouped.layer(gi), "mul", 8, 8,
                          Pixel{180, 60, 40, 255});
    mul.blend = BlendMode::Multiply;
    Layer& scr = addChild(grouped.layer(gi), "scr", 8, 8,
                          Pixel{30, 120, 200, 128});
    scr.blend = BlendMode::Screen;
    // Flat: the same two layers at the top level.
    const std::size_t m2 = flat.addLayer("mul");
    fillAll(flat.layer(m2).surface, 8, 8, Pixel{180, 60, 40, 255});
    flat.layer(m2).blend = BlendMode::Multiply;
    const std::size_t s2 = flat.addLayer("scr");
    fillAll(flat.layer(s2).surface, 8, 8, Pixel{30, 120, 200, 128});
    flat.layer(s2).blend = BlendMode::Screen;

    Surface a, b;
    grouped.composite(a);
    flat.composite(b);
    for (int y = 0; y < 8; ++y)
        for (int x = 0; x < 8; ++x) {
            const Pixel pa = a.get(x, y), pb = b.get(x, y);
            CHECK_EQ(pa.r, pb.r);
            CHECK_EQ(pa.g, pb.g);
            CHECK_EQ(pa.b, pb.b);
            CHECK_EQ(pa.a, pb.a);
        }
    // Sanity: the group is not a no-op (screen lifted the base).
    CHECK_EQ(a.get(4, 4).a, 255);
    CHECK(a.get(4, 4).r != 40);
}

// A2: nested pass-through groups reach the parent accumulator (a nested
// group used to vanish: children were painted as empty rasters).
TEST(passthrough_nested_group_composites) {
    Canvas c(8, 8);
    const std::size_t outer = c.addGroup("outer");
    c.layer(outer).children.push_back(Layer());
    Layer& nest = c.layer(outer).children.back();
    nest.name = "nest";
    nest.isGroup = true;
    CHECK(nest.passThrough);
    addChild(nest, "leaf", 8, 8, Pixel{0, 255, 0, 255});
    Surface out;
    c.composite(out);
    CHECK_EQ(out.get(4, 4).g, 255);
    CHECK_EQ(out.get(4, 4).a, 255);
    CHECK_EQ(out.get(0, 0).g, 255);
}

// A2: a pass-through group honors its own mask and clipped flag.
TEST(passthrough_group_mask_and_clip) {
    Canvas c(8, 8);
    const std::size_t base = c.addLayer("stencil");
    c.layer(base).surface.set(2, 2, Pixel{255, 255, 0, 255});
    const std::size_t gi = c.addGroup("g");
    Layer& kid = addChild(c.layer(gi), "kid", 8, 8, Pixel{0, 0, 255, 255});
    (void)kid;
    c.layer(gi).clipped = true;
    Surface out;
    c.composite(out);
    CHECK_EQ(out.get(2, 2).b, 255);  // inside the stencil: painted
    CHECK_EQ(out.get(5, 5).a, 0);    // outside: the whole group is clipped
    // Un-clip, attach a group mask that hides the left half.
    c.layer(gi).clipped = false;
    c.layer(gi).addMask(8, 8);
    for (int y = 0; y < 8; ++y)
        for (int x = 0; x < 4; ++x)
            c.layer(gi).mask.set(x, y, Pixel{0, 0, 0, 0});
    c.composite(out);
    CHECK_EQ(out.get(0, 0).a, 0);
    CHECK_EQ(out.get(7, 7).b, 255);
}

// A2: pass-through group opacity multiplies every child (documented
// approximation); a half-opaque group leaves half the child alpha.
TEST(passthrough_group_opacity_scales_children) {
    Canvas c(8, 8);
    const std::size_t gi = c.addGroup("g");
    c.layer(gi).opacity = 0.5f;
    addChild(c.layer(gi), "red", 8, 8, Pixel{255, 0, 0, 255});
    Surface out;
    c.composite(out);
    CHECK_EQ(out.get(4, 4).a, 128);
    CHECK_EQ(out.get(4, 4).r, 128);
    CHECK_EQ(out.get(4, 4).g, 0);
}

// A2 non-regression: an isolated group still composites its children
// into a buffer and blends that buffer with the group's mode/opacity
// (two-stage math), byte for byte.
TEST(group_isolated_matches_two_stage_math) {
    Canvas c(8, 8);
    const std::size_t b = c.addLayer("base");
    fillAll(c.layer(b).surface, 8, 8, Pixel{100, 100, 100, 255});
    const std::size_t gi = c.addGroup("g");
    c.layer(gi).passThrough = false;
    addChild(c.layer(gi), "scr", 8, 8, Pixel{128, 128, 128, 255})
        .blend = BlendMode::Screen;
    Surface out;
    c.composite(out);
    // Isolated: the buffer holds the screen child composited over
    // transparency (= the child itself), then the group (normal, 1.0)
    // replaces the base: 128, byte for byte.
    CHECK_EQ(out.get(4, 4).r, 128);
    CHECK_EQ(out.get(4, 4).a, 255);
    // Same group as pass-through: the child screens the base directly
    // (0.502 + 0.392 - 0.197 = 0.697 -> 178) - proof the flag drives
    // the path.
    c.layer(gi).passThrough = true;
    c.composite(out);
    CHECK_EQ(out.get(4, 4).r, 178);
    CHECK_EQ(out.get(4, 4).a, 255);
}
