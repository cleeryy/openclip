// SPDX-License-Identifier: GPL-3.0-or-later
#include "test.h"

#include "openclip/canvas.h"

using openclip::BlendMode;
using openclip::Canvas;
using openclip::Pixel;
using openclip::Surface;

TEST(surface_tiles) {
    Surface s(300, 300);
    CHECK_EQ(s.tilesX(), 2);
    CHECK_EQ(s.tilesY(), 2);
    Pixel p{10, 20, 30, 40};
    s.set(299, 299, p);
    auto q = s.get(299, 299);
    CHECK_EQ(q.r, 10);
    CHECK_EQ(q.a, 40);
    // Out of bounds is a safe no-op / transparent.
    s.set(-1, -1, p);
    CHECK_EQ(s.get(-1, 0).a, 0);
    std::vector<std::uint8_t> t;
    s.tileCopy(1, 1, t);
    CHECK_EQ(t.size(), (std::size_t)256 * 256 * 4);
    s.set(299, 299, Pixel{});
    s.tileRestore(1, 1, t);
    CHECK_EQ(s.get(299, 299).r, 10);
}

TEST(canvas_layers_composite) {
    Canvas c(8, 8);
    c.addLayer("a");
    c.addLayer("b");
    CHECK_EQ(c.layerCount(), (std::size_t)2);
    c.layer(0).surface.set(0, 0, Pixel{255, 0, 0, 255});
    c.layer(1).surface.set(0, 0, Pixel{0, 0, 255, 255});
    Surface out;
    c.composite(out);
    // Top layer (b, blue, normal) wins.
    CHECK_EQ(out.get(0, 0).b, 255);
    CHECK_EQ(out.get(0, 0).r, 0);
    // Hidden top layer reveals bottom.
    c.layer(1).visible = false;
    c.composite(out);
    CHECK_EQ(out.get(0, 0).r, 255);
    // Zero-opacity layer contributes nothing.
    c.layer(1).visible = true;
    c.layer(1).opacity = 0.0f;
    c.composite(out);
    CHECK_EQ(out.get(0, 0).r, 255);
    // Move: layer order swap.
    c.layer(1).opacity = 1.0f;
    c.moveLayer(1, 0);
    c.composite(out);
    CHECK_EQ(out.get(0, 0).r, 255);  // red now on top
    c.removeLayer(1);
    CHECK_EQ(c.layerCount(), (std::size_t)1);
}
