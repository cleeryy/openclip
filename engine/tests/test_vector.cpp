// SPDX-License-Identifier: GPL-3.0-or-later
// M6 vector-model tests: parse/resample, pressure→width, rasterize
// coverage, composite integration, eraser intersection, per-layer undo,
// no-op equality.
#include "test.h"

#include <string>

#include "openclip/canvas.h"
#include "openclip/surface.h"
#include "openclip/vector.h"

using openclip::Canvas;
using openclip::Pixel;
using openclip::Surface;
using openclip::VectorHistory;
using openclip::VectorPath;

static std::string hline(float y, float x0, float x1, float p) {
    return "[{\"x\":" + std::to_string(x0) + ",\"y\":" +
           std::to_string(y) + ",\"p\":" + std::to_string(p) + "},{\"x\":" +
           std::to_string(x1) + ",\"y\":" + std::to_string(y) + ",\"p\":" +
           std::to_string(p) + "}]";
}

TEST(vector_parse_resamples_to_8px_steps) {
    VectorPath p;
    std::string err;
    // 40px span at 8px steps -> 1 + 5 resampled points.
    CHECK(openclip::parseVectorPathJson(hline(10, 10, 50, 1), 1.0f, "stroke",
                                        p, err));
    CHECK_EQ(p.pts.size(), 6u);
    CHECK_EQ(p.widthProfile.size(), 6u);
    CHECK_EQ(p.kind, std::string("stroke"));
    // Endpoints preserved.
    CHECK(p.pts.front().x == 10.0f);
    CHECK(p.pts.back().x == 50.0f);
}

TEST(vector_pressure_maps_to_width) {
    VectorPath full, none;
    std::string err;
    CHECK(openclip::parseVectorPathJson(hline(10, 10, 18, 1), 1.0f, "stroke",
                                        full, err));
    CHECK(openclip::parseVectorPathJson(hline(10, 10, 18, 0), 1.0f, "stroke",
                                        none, err));
    // widthAt(1) = 16px, widthAt(0) = 4px (0.25x base).
    CHECK(full.widthProfile[0] > none.widthProfile[0]);
    CHECK(full.widthProfile[0] == 16.0f);
    CHECK(none.widthProfile[0] == 4.0f);
}

TEST(vector_rasterize_covers_path_bbox) {
    VectorPath p;
    std::string err;
    CHECK(openclip::parseVectorPathJson(hline(32, 10, 54, 1), 1.0f, "stroke",
                                        p, err));
    Surface out(64, 64);
    out.clear();
    openclip::rasterizeVectorPaths({p}, out, 0, 0, 0);
    // On-path pixels painted (radius 8 disc around y=32).
    CHECK_EQ(out.get(30, 32).a, 255);
    CHECK_EQ(out.get(10, 32).a, 255);
    CHECK(out.get(30, 36).a != 0);  // inside the 8px radius
    // Far from the path: untouched.
    CHECK_EQ(out.get(30, 50).a, 0);
    CHECK_EQ(out.get(0, 0).a, 0);
}

TEST(vector_append_composites_like_raster_layer) {
    Canvas c(64, 64);
    const std::size_t li = c.addLayer("vec");
    VectorHistory h;
    std::string err;
    const int n = openclip::appendVectorPath(c, h, li, hline(32, 10, 54, 1),
                                             1.0f, "stroke", err);
    CHECK_EQ(n, 1);
    CHECK(c.layer(li).isVector);
    Surface out;
    c.composite(out);
    CHECK_EQ(out.get(30, 32).a, 255);  // vector ink lands in composite
    CHECK_EQ(out.get(0, 0).a, 0);
    // Opacity flows through the same path as raster layers.
    c.layer(li).opacity = 0.5f;
    c.composite(out);
    CHECK(out.get(30, 32).a < 255);
    CHECK(out.get(30, 32).a > 0);
}

TEST(vector_eraser_removes_intersecting_path) {
    Canvas c(64, 64);
    const std::size_t li = c.addLayer("vec");
    VectorHistory h;
    std::string err;
    CHECK_EQ(openclip::appendVectorPath(c, h, li, hline(20, 10, 50, 1), 1.0f,
                                        "stroke", err),
             1);
    CHECK_EQ(openclip::appendVectorPath(c, h, li, hline(50, 10, 50, 1), 1.0f,
                                        "stroke", err),
             2);
    // Vertical eraser crossing only the y=20 path (ends at y=30, well
    // clear of path B at y=50: gap 20px > eraser pad 10px).
    const std::string er =
        "[{\"x\":30,\"y\":0,\"p\":1},{\"x\":30,\"y\":30,\"p\":1}]";
    CHECK_EQ(openclip::eraseVectorPaths(c, h, li, er, err), 1);
    CHECK_EQ(c.layer(li).vectorData.size(), 1u);
    CHECK(c.layer(li).vectorData[0].pts[0].y == 50.0f);  // survivor is B
    Surface out;
    c.composite(out);
    CHECK_EQ(out.get(30, 20).a, 0);  // A gone
    CHECK(out.get(30, 50).a != 0);   // B stays
}

TEST(vector_undo_pops_paths_per_layer) {
    Canvas c(64, 64);
    const std::size_t li = c.addLayer("vec");
    VectorHistory h;
    std::string err;
    CHECK_EQ(openclip::appendVectorPath(c, h, li, hline(20, 10, 50, 1), 1.0f,
                                        "stroke", err),
             1);
    CHECK_EQ(openclip::appendVectorPath(c, h, li, hline(50, 10, 50, 1), 1.0f,
                                        "stroke", err),
             2);
    CHECK(openclip::undoVectorPath(c, h, li));
    CHECK_EQ(c.layer(li).vectorData.size(), 1u);
    CHECK(openclip::undoVectorPath(c, h, li));
    CHECK(c.layer(li).vectorData.empty());
    CHECK(!openclip::undoVectorPath(c, h, li));  // empty history: false
}

TEST(vector_noop_leaves_composite_untouched) {
    Canvas c(64, 64);
    const std::size_t li = c.addLayer("vec");
    VectorHistory h;
    std::string err;
    Surface before;
    c.composite(before);
    // Garbage JSON: rejected, layer untouched, no undo snapshot.
    CHECK_EQ(openclip::appendVectorPath(c, h, li, "definitely not json", 1.0f,
                                        "stroke", err),
             -1);
    CHECK(c.layer(li).vectorData.empty());
    CHECK(h.empty());
    CHECK_EQ(openclip::eraseVectorPaths(c, h, li, "[]", err), -1);
    Surface after;
    c.composite(after);
    CHECK(before.bytes() == after.bytes());
}
