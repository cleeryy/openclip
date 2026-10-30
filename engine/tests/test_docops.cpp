// SPDX-License-Identifier: GPL-3.0-or-later
// W1 (issue #28 slice A): merge down / merge visible / flatten /
// rasterize — render-preserving structural ops + one-step snapshot undo.
#include "test.h"

#include <string>

#include "openclip/canvas.h"
#include "openclip/docops.h"
#include "openclip/undo.h"

using openclip::BlendMode;
using openclip::Canvas;
using openclip::History;
using openclip::Pixel;
using openclip::Surface;

namespace {

void fillRect(Surface& s, int x0, int y0, int x1, int y1, Pixel p) {
    for (int y = y0; y < y1; ++y)
        for (int x = x0; x < x1; ++x) s.set(x, y, p);
}

std::vector<std::uint8_t> compositeBytes(Canvas& c) {
    Surface out;
    c.composite(out);
    return out.bytes();
}

std::vector<std::uint8_t> exportBytes(Canvas& c) {
    Surface out;
    c.compositeExport(out);
    return out.bytes();
}

}  // namespace

TEST(docops_merge_down_exact) {
    Canvas c(32, 32);
    const std::size_t a = c.addLayer("base");
    fillRect(c.layer(a).surface, 0, 0, 32, 32, Pixel{200, 10, 10, 255});
    const std::size_t b = c.addLayer("top");
    fillRect(c.layer(b).surface, 8, 8, 20, 20, Pixel{0, 0, 200, 255});
    const std::vector<std::uint8_t> before = compositeBytes(c);
    std::string err;
    CHECK(openclip::mergeDown(c, 1, err));
    CHECK(err.empty());
    CHECK_EQ(c.layerCount(), 1u);
    CHECK(compositeBytes(c) == before);
    // The merged layer is a plain raster at the destination slot.
    CHECK_EQ(c.layer(0).name, std::string("base"));
    CHECK(!c.layer(0).isGroup);
    CHECK(c.layer(0).opacity == 1.0f);
}

TEST(docops_merge_down_group_flattens) {
    Canvas c(32, 32);
    const std::size_t a = c.addLayer("base");
    fillRect(c.layer(a).surface, 0, 0, 32, 32, Pixel{255, 255, 255, 255});
    const std::size_t g = c.addGroup("grp");
    openclip::Layer& grp = c.layer(g);
    openclip::Layer childA;
    childA.name = "g1";
    childA.surface.resize(32, 32);
    fillRect(childA.surface, 2, 2, 12, 12, Pixel{0, 128, 0, 255});
    openclip::Layer childB;
    childB.name = "g2";
    childB.surface.resize(32, 32);
    fillRect(childB.surface, 6, 6, 18, 18, Pixel{0, 0, 0, 128});
    grp.children.push_back(childA);
    grp.children.push_back(childB);
    const std::vector<std::uint8_t> before = compositeBytes(c);
    std::string err;
    CHECK(openclip::mergeDown(c, 1, err));
    CHECK_EQ(c.layerCount(), 1u);
    CHECK(!c.layer(0).isGroup);
    CHECK(c.layer(0).children.empty());
    CHECK(compositeBytes(c) == before);
}

TEST(docops_merge_down_clipped_keeps_render) {
    // Clipped layer merges into its base and the stencil is resolved
    // against the base's own alpha (exact when the base is opaque).
    Canvas c(16, 16);
    const std::size_t st = c.addLayer("hidden-stencil");
    c.layer(st).surface.set(2, 2, Pixel{0, 0, 0, 0});
    c.layer(0).visible = false;
    const std::size_t mid = c.addLayer("mid");
    c.layer(mid).surface.set(2, 2, Pixel{255, 0, 0, 255});
    const std::size_t clip = c.addLayer("clip");
    c.layer(clip).clipped = true;
    c.layer(clip).surface.set(2, 2, Pixel{0, 0, 255, 255});
    c.layer(clip).surface.set(5, 5, Pixel{0, 0, 255, 255});
    const std::vector<std::uint8_t> before = compositeBytes(c);
    std::string err;
    CHECK(openclip::mergeDown(c, 2, err));
    CHECK_EQ(c.layerCount(), 2u);
    CHECK(compositeBytes(c) == before);
}

TEST(docops_merge_onto_group_refused) {
    Canvas c(16, 16);
    const std::size_t g = c.addGroup("grp");
    (void)g;
    const std::size_t top = c.addLayer("top");
    fillRect(c.layer(top).surface, 0, 0, 4, 4, Pixel{10, 20, 30, 255});
    const std::vector<std::uint8_t> before = compositeBytes(c);
    std::string err;
    CHECK(!openclip::mergeDown(c, 1, err));
    CHECK(!err.empty());
    CHECK_EQ(c.layerCount(), 2u);
    CHECK(compositeBytes(c) == before);
}

TEST(docops_merge_hidden_source_refused) {
    Canvas c(16, 16);
    const std::size_t a0 = c.addLayer("a");
    c.layer(a0).surface.set(0, 0, Pixel{255, 0, 0, 255});
    const std::size_t b = c.addLayer("b");
    c.layer(b).surface.set(1, 1, Pixel{0, 255, 0, 255});
    c.layer(b).visible = false;
    const std::vector<std::uint8_t> before = compositeBytes(c);
    std::string err;
    CHECK(!openclip::mergeDown(c, 1, err));
    CHECK(compositeBytes(c) == before);
}

TEST(docops_merge_visible_keeps_hidden) {
    Canvas c(16, 16);
    const std::size_t a = c.addLayer("a");
    fillRect(c.layer(a).surface, 0, 0, 16, 16, Pixel{255, 255, 255, 255});
    const std::size_t hidden = c.addLayer("hidden");
    fillRect(c.layer(hidden).surface, 0, 0, 4, 4, Pixel{255, 0, 255, 255});
    c.layer(hidden).visible = false;
    const std::size_t top = c.addLayer("top");
    fillRect(c.layer(top).surface, 4, 4, 10, 10, Pixel{0, 0, 255, 255});
    const std::vector<std::uint8_t> before = compositeBytes(c);
    std::string err;
    CHECK(openclip::mergeVisible(c, err));
    CHECK_EQ(c.layerCount(), 2u);  // merged + hidden
    CHECK(compositeBytes(c) == before);
    // The hidden layer survives with its pixels.
    bool foundHidden = false;
    for (std::size_t i = 0; i < c.layerCount(); ++i) {
        if (c.layer(i).name == "hidden") {
            foundHidden = true;
            CHECK_EQ(c.layer(i).surface.get(0, 0).r, 255);
        }
    }
    CHECK(foundHidden);
}

TEST(docops_merge_visible_needs_two) {
    Canvas c(8, 8);
    c.addLayer("only");
    std::string err;
    CHECK(!openclip::mergeVisible(c, err));
    CHECK(!err.empty());
    CHECK_EQ(c.layerCount(), 1u);
}

TEST(docops_flatten_drops_reference_layers) {
    Canvas c(16, 16);
    const std::size_t base = c.addLayer("base");
    fillRect(c.layer(base).surface, 0, 0, 16, 16, Pixel{240, 240, 240, 255});
    const std::size_t ref = c.addLayer("ref");
    fillRect(c.layer(ref).surface, 0, 0, 8, 8, Pixel{255, 0, 0, 255});
    c.layer(ref).reference = true;
    const std::vector<std::uint8_t> before = exportBytes(c);
    std::string err;
    CHECK(openclip::flattenDocument(c, err));
    CHECK_EQ(c.layerCount(), 1u);
    CHECK(exportBytes(c) == before);
    // The aid is gone from the export composite (no red pixel).
    Surface out;
    c.compositeExport(out);
    CHECK_EQ(out.get(2, 2).r, 240);
}

TEST(docops_rasterize_fill_bakes_pixels) {
    Canvas c(16, 16);
    const std::size_t f = c.addLayer("fill");
    c.layer(f).fillKind = "solid";
    c.layer(f).fillColor = "#204080";
    const std::vector<std::uint8_t> before = compositeBytes(c);
    std::string err;
    CHECK(openclip::rasterizeLayer(c, f, err));
    CHECK(!c.layer(f).hasFill());
    CHECK_EQ(static_cast<int>(c.layer(f).surface.get(8, 8).r), 0x20);
    CHECK_EQ(static_cast<int>(c.layer(f).surface.get(8, 8).g), 0x40);
    CHECK_EQ(static_cast<int>(c.layer(f).surface.get(8, 8).b), 0x80);
    CHECK(compositeBytes(c) == before);
}

TEST(docops_rasterize_adjustment_consumes_below) {
    Canvas c(16, 16);
    const std::size_t base = c.addLayer("base");
    fillRect(c.layer(base).surface, 0, 0, 16, 16, Pixel{100, 100, 100, 255});
    const std::size_t adj = c.addLayer("adj");
    c.layer(adj).adjustType = "brightness";
    c.layer(adj).adjustAmount = 0.5;
    const std::vector<std::uint8_t> before = compositeBytes(c);
    std::string err;
    CHECK(openclip::rasterizeLayer(c, adj, err));
    CHECK_EQ(c.layerCount(), 1u);
    CHECK(c.layer(0).adjustType.empty());
    CHECK(compositeBytes(c) == before);
}

TEST(docops_rasterize_bakes_opacity_and_mask) {
    Canvas c(16, 16);
    const std::size_t l = c.addLayer("paint");
    fillRect(c.layer(l).surface, 0, 0, 16, 16, Pixel{255, 0, 0, 255});
    c.layer(l).opacity = 0.5f;
    c.layer(l).addMask(16, 16);
    c.layer(l).mask.set(0, 0, Pixel{0, 0, 0, 0});
    const std::vector<std::uint8_t> before = compositeBytes(c);
    std::string err;
    CHECK(openclip::rasterizeLayer(c, l, err));
    CHECK(!c.layer(l).hasMask);
    CHECK(c.layer(l).opacity == 1.0f);
    CHECK(c.layer(l).blend == BlendMode::Normal);
    CHECK(compositeBytes(c) == before);
}

TEST(docops_rasterize_already_raster_refused) {
    Canvas c(8, 8);
    const std::size_t l = c.addLayer("plain");
    c.layer(l).surface.set(0, 0, Pixel{1, 2, 3, 255});
    const std::vector<std::uint8_t> before = compositeBytes(c);
    std::string err;
    CHECK(!openclip::rasterizeLayer(c, l, err));
    CHECK(!err.empty());
    CHECK(compositeBytes(c) == before);
}

TEST(docops_merge_undo_is_one_step) {
    Canvas c(16, 16);
    const std::size_t a = c.addLayer("a");
    fillRect(c.layer(a).surface, 0, 0, 16, 16, Pixel{255, 255, 255, 255});
    const std::size_t b = c.addLayer("b");
    fillRect(c.layer(b).surface, 2, 2, 8, 8, Pixel{0, 0, 0, 255});
    History h(8);
    const std::vector<std::uint8_t> before = compositeBytes(c);
    h.beginSnapshot(c);
    std::string err;
    CHECK(openclip::mergeDown(c, 1, err));
    h.endSnapshot(c);
    CHECK_EQ(c.layerCount(), 1u);
    CHECK_EQ(h.undoDepth(), 1u);
    CHECK(h.undo(c));
    CHECK_EQ(c.layerCount(), 2u);
    CHECK(compositeBytes(c) == before);
    CHECK(h.redo(c));
    CHECK_EQ(c.layerCount(), 1u);
}

TEST(docops_refused_op_records_no_undo) {
    Canvas c(8, 8);
    c.addLayer("g");
    c.layer(0).isGroup = true;
    const std::size_t t0 = c.addLayer("t");
    c.layer(t0).surface.set(0, 0, Pixel{9, 9, 9, 255});
    History h(8);
    h.beginSnapshot(c);
    std::string err;
    CHECK(!openclip::mergeDown(c, 1, err));
    h.endSnapshot(c);
    CHECK_EQ(h.undoDepth(), 0u);
    CHECK(!h.canUndo());
}
