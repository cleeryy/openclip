// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip B2 engine — atomic text-layer commit (ARCHITECTURE.md "B2
// additions" §1): new layer + fiche in one undo, re-edit in one undo,
// refusals leave the document and the history untouched.
#include "test.h"

#include <string>

#include "openclip/canvas.h"
#include "openclip/surface.h"
#include "openclip/text_layer.h"
#include "openclip/undo.h"

using openclip::Canvas;
using openclip::History;
using openclip::Layer;
using openclip::Pixel;
using openclip::Surface;
using openclip::TextLayerCommit;
using openclip::TextLayerFiche;

namespace {

Surface textPixels(int w, int h, openclip::Pixel p) {
    Surface s(w, h);
    for (int y = h / 4; y < h / 2; ++y)
        for (int x = w / 4; x < w / 2; ++x) s.set(x, y, p);
    return s;
}

TextLayerFiche fiche(const std::string& text, const std::string& xform) {
    TextLayerFiche f;
    f.content = text;
    f.font = "serif";
    f.sizePx = 24;
    f.color = "#112233";
    f.box = "4,8,40";
    f.xform = xform;
    return f;
}

}  // namespace

TEST(text_layer_commit_new_is_one_undo) {
    Canvas c(64, 64);
    c.addLayer("base");
    c.layer(0).surface.set(1, 1, Pixel{9, 8, 7, 255});
    History h;
    const Surface px = textPixels(64, 64, Pixel{200, 30, 30, 255});
    TextLayerCommit spec;
    spec.index = -1;
    spec.name = "Tx hello";
    spec.pixels = &px;
    spec.fiche = fiche("hello", "12,1.5,0.75,6");
    std::string err;
    CHECK(openclip::commitTextLayer(c, h, spec, err));
    CHECK(err.empty());
    CHECK_EQ(c.layerCount(), std::size_t{2});
    CHECK_EQ(c.layer(1).name, std::string("Tx hello"));
    CHECK_EQ(c.layer(1).textContent, std::string("hello"));
    CHECK_EQ(c.layer(1).textFont, std::string("serif"));
    CHECK_EQ(c.layer(1).textSizePx, 24);
    CHECK_EQ(c.layer(1).textXform, std::string("12,1.5,0.75,6"));
    CHECK_EQ(c.layer(1).surface.get(20, 20).a, 255);
    // ONE undo removes the layer AND restores the pixels in one step.
    CHECK(h.undo(c));
    CHECK_EQ(c.layerCount(), std::size_t{1});
    CHECK_EQ(c.layer(0).surface.get(1, 1).a, 255);
    CHECK(!h.canUndo());
}

TEST(text_layer_reedit_is_one_undo) {
    Canvas c(64, 64);
    c.addLayer("title");
    Layer& l = c.layer(0);
    l.textContent = "AAAA";
    l.textFont = "sans-serif";
    l.textSizePx = 12;
    l.surface.set(2, 2, Pixel{1, 2, 3, 255});
    History h;
    const Surface px = textPixels(64, 64, Pixel{10, 200, 10, 255});
    TextLayerCommit spec;
    spec.index = 0;
    spec.pixels = &px;
    spec.fiche = fiche("BBBB", "");
    std::string err;
    CHECK(openclip::commitTextLayer(c, h, spec, err));
    CHECK_EQ(c.layerCount(), std::size_t{1});
    CHECK_EQ(c.layer(0).textContent, std::string("BBBB"));
    CHECK(c.layer(0).textXform.empty());  // identity clears the tuple
    CHECK_EQ(c.layer(0).surface.get(2, 2).a, 0);  // pixels replaced
    CHECK(h.undo(c));
    CHECK_EQ(c.layer(0).textContent, std::string("AAAA"));
    CHECK_EQ(c.layer(0).surface.get(2, 2).a, 255);
    CHECK(!h.canUndo());
}

TEST(text_layer_refusals_leave_no_history) {
    Canvas c(32, 32);
    c.addLayer("group");
    c.addLayer("raster");
    // Refusal: group target.
    History h;
    const Surface px = textPixels(32, 32, Pixel{1, 1, 1, 255});
    TextLayerCommit spec;
    spec.pixels = &px;
    spec.fiche = fiche("x", "");
    spec.index = 0;
    std::string err;
    c.layer(0).isGroup = true;
    CHECK(!openclip::commitTextLayer(c, h, spec, err));
    CHECK(!err.empty());
    CHECK(!h.canUndo());
    // Refusal: locked raster.
    spec.index = 1;
    c.layer(1).locked = true;
    CHECK(!openclip::commitTextLayer(c, h, spec, err));
    CHECK(!h.canUndo());
    // Refusal: locked -> unlocked but wrong pixel size.
    c.layer(1).locked = false;
    Surface small(8, 8);
    spec.pixels = &small;
    CHECK(!openclip::commitTextLayer(c, h, spec, err));
    CHECK(!h.canUndo());
    // Refusal: empty fiche.
    spec.pixels = &px;
    spec.fiche = TextLayerFiche{};
    CHECK(!openclip::commitTextLayer(c, h, spec, err));
    CHECK(!h.canUndo());
    CHECK_EQ(c.layerCount(), std::size_t{2});
    CHECK_EQ(c.layer(1).textContent, std::string());
}
