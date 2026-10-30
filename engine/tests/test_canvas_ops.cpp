// SPDX-License-Identifier: GPL-3.0-or-later
// W1 (issue #28 slice A): anchored canvas resize, crop, print size.
#include "test.h"

#include <cmath>
#include <string>

#include "openclip/canvas.h"
#include "openclip/canvas_ops.h"
#include "openclip/undo.h"

using openclip::Canvas;
using openclip::CanvasAnchor;
using openclip::History;
using openclip::Layer;
using openclip::Pixel;
using openclip::Surface;
using openclip::Vec2;

namespace {

int countOpaque(const Surface& s) {
    int n = 0;
    for (int y = 0; y < s.height(); ++y)
        for (int x = 0; x < s.width(); ++x)
            if (s.get(x, y).a != 0) ++n;
    return n;
}

}  // namespace

TEST(canvas_resize_grow_anchored_topleft) {
    Canvas c(8, 8);
    const std::size_t l = c.addLayer("px");
    c.layer(l).surface.set(0, 0, Pixel{255, 0, 0, 255});
    std::string err;
    CHECK(openclip::resizeCanvas(c, 16, 16, CanvasAnchor::TopLeft, false, err));
    CHECK_EQ(c.width(), 16);
    CHECK_EQ(c.height(), 16);
    CHECK_EQ(c.layer(l).surface.get(0, 0).r, 255);
    CHECK_EQ(countOpaque(c.layer(l).surface), 1);
}

TEST(canvas_resize_grow_anchored_center) {
    Canvas c(8, 8);
    const std::size_t l = c.addLayer("px");
    c.layer(l).surface.set(0, 0, Pixel{255, 0, 0, 255});
    std::string err;
    CHECK(openclip::resizeCanvas(c, 16, 16, CanvasAnchor::Center, false, err));
    CHECK_EQ(c.layer(l).surface.get(4, 4).r, 255);
    CHECK_EQ(c.layer(l).surface.get(0, 0).a, 0);
    CHECK_EQ(countOpaque(c.layer(l).surface), 1);
}

TEST(canvas_resize_shrink_crops_around_anchor) {
    Canvas c(16, 16);
    const std::size_t l = c.addLayer("px");
    c.layer(l).surface.set(0, 0, Pixel{10, 20, 30, 255});
    c.layer(l).surface.set(15, 15, Pixel{40, 50, 60, 255});
    std::string err;
    CHECK(openclip::resizeCanvas(c, 8, 8, CanvasAnchor::TopLeft, false, err));
    CHECK_EQ(c.width(), 8);
    CHECK_EQ(c.layer(l).surface.get(0, 0).r, 10);
    CHECK_EQ(countOpaque(c.layer(l).surface), 1);
}

TEST(canvas_resize_resample_stretches) {
    Canvas c(8, 8);
    const std::size_t l = c.addLayer("half");
    for (int y = 0; y < 8; ++y)
        for (int x = 0; x < 4; ++x)
            c.layer(l).surface.set(x, y, Pixel{255, 0, 0, 255});
    std::string err;
    CHECK(openclip::resizeCanvas(c, 16, 16, CanvasAnchor::Center, true, err));
    CHECK_EQ(c.width(), 16);
    CHECK_EQ(c.layer(l).surface.get(2, 8).a, 255);
    CHECK_EQ(c.layer(l).surface.get(2, 8).r, 255);
    CHECK_EQ(c.layer(l).surface.get(13, 8).a, 0);
    // Alpha contract survives interpolation (rgb <= a).
    for (int y = 0; y < 16; ++y) {
        for (int x = 0; x < 16; ++x) {
            const Pixel p = c.layer(l).surface.get(x, y);
            CHECK(p.r <= p.a);
            CHECK(p.g <= p.a);
            CHECK(p.b <= p.a);
        }
    }
}

TEST(canvas_resize_keeps_vector_points_aligned) {
    Canvas c(32, 32);
    const std::size_t v = c.addLayer("vec");
    c.layer(v).isVector = true;
    openclip::VectorPath path;
    path.pts.push_back(Vec2{10.0f, 10.0f});
    path.pts.push_back(Vec2{20.0f, 10.0f});
    c.layer(v).vectorData.push_back(path);
    std::string err;
    CHECK(openclip::resizeCanvas(c, 64, 64, CanvasAnchor::Center, false, err));
    CHECK_EQ(static_cast<int>(c.layer(v).vectorData[0].pts[0].x), 26);
    CHECK_EQ(static_cast<int>(c.layer(v).vectorData[0].pts[1].y), 26);
}

TEST(canvas_crop_inside) {
    Canvas c(16, 16);
    const std::size_t l = c.addLayer("px");
    c.layer(l).surface.set(4, 4, Pixel{1, 2, 3, 255});
    c.layer(l).surface.set(0, 0, Pixel{9, 9, 9, 255});
    std::string err;
    CHECK(openclip::cropCanvas(c, 4, 4, 8, 8, err));
    CHECK_EQ(c.width(), 8);
    CHECK_EQ(c.layer(l).surface.get(0, 0).r, 1);
    CHECK_EQ(countOpaque(c.layer(l).surface), 1);
}

TEST(canvas_crop_outside_refused) {
    Canvas c(16, 16);
    c.addLayer("l");
    std::string err;
    CHECK(!openclip::cropCanvas(c, 12, 12, 8, 8, err));
    CHECK(!err.empty());
    CHECK_EQ(c.width(), 16);
    CHECK(!openclip::cropCanvas(c, -1, 0, 4, 4, err));
    CHECK_EQ(c.width(), 16);
}

TEST(canvas_resize_negative_refused) {
    Canvas c(16, 16);
    std::string err;
    CHECK(!openclip::resizeCanvas(c, 0, 10, CanvasAnchor::Center, false, err));
    CHECK(!openclip::resizeCanvas(c, 10, -2, CanvasAnchor::Center, true, err));
    CHECK_EQ(c.width(), 16);
}

TEST(canvas_print_size_mm) {
    const openclip::PrintSize a = openclip::printSizeMm(1200, 2400, 300.0);
    CHECK(std::fabs(a.widthMm - 101.6) < 0.01);
    CHECK(std::fabs(a.heightMm - 203.2) < 0.01);
    const openclip::PrintSize b = openclip::printSizeMm(1200, 2400, 0.0);
    CHECK(b.widthMm == 0.0);
    CHECK(b.heightMm == 0.0);
    const openclip::PrintSize c = openclip::printSizeMm(4961, 7016, 300.0);
    CHECK(std::fabs(c.widthMm - 420.03) < 0.05);  // A3 at 300 dpi
}

TEST(canvas_resize_undo_snapshot) {
    Canvas c(8, 8);
    const std::size_t li = c.addLayer("l");
    c.layer(li).surface.set(0, 0, Pixel{5, 5, 5, 255});
    History h(4);
    h.beginSnapshot(c);
    std::string err;
    CHECK(openclip::resizeCanvas(c, 24, 12, CanvasAnchor::TopLeft, false, err));
    h.endSnapshot(c);
    CHECK_EQ(c.width(), 24);
    CHECK(h.undo(c));
    CHECK_EQ(c.width(), 8);
    CHECK_EQ(c.height(), 8);
    CHECK_EQ(c.layer(0).surface.get(0, 0).r, 5);
    CHECK(h.redo(c));
    CHECK_EQ(c.width(), 24);
}
