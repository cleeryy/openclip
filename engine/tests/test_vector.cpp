// SPDX-License-Identifier: GPL-3.0-or-later
// M6 vector-model tests: parse/resample, pressure→width, rasterize
// coverage, composite integration, eraser intersection, per-layer undo,
// no-op equality.
#include "test.h"

#include <algorithm>
#include <string>

#include "openclip/canvas.h"
#include "openclip/ora.h"
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
    openclip::rasterizeVectorPaths({p}, out);
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

// ---- T2 §4: style per path + sommets (ARCHITECTURE.md §T2) ----

static std::string hlineStyle(float y, float x0, float x1, float p,
                              const std::string& styleFrag) {
    return "{\"pts\":[" + hline(y, x0, x1, p).substr(1,
               hline(y, x0, x1, p).size() - 2) +
           "]," + styleFrag + "}";
}

TEST(vector_color_per_path) {
    Canvas c(64, 64);
    const std::size_t li = c.addLayer("vec");
    VectorHistory h;
    std::string err;
    CHECK_EQ(openclip::appendVectorPath(
                 c, h, li, hlineStyle(20, 10, 54, 1, "\"color\":\"#FF0000\""),
                 1.0f, "stroke", err),
             1);
    CHECK_EQ(openclip::appendVectorPath(
                 c, h, li, hlineStyle(50, 10, 54, 1, "\"color\":\"#0000FF\""),
                 1.0f, "stroke", err),
             2);
    Surface out;
    c.composite(out);
    const Pixel pr = out.get(30, 20);
    const Pixel pb = out.get(30, 50);
    CHECK(pr.a != 0 && pr.r > pr.b);  // red path: red dominates
    CHECK(pb.a != 0 && pb.b > pb.r);  // blue path: blue dominates
}

TEST(vector_tip_flat_squares) {
    // Same dot, round vs flat: flat covers the corner pixel that the
    // round disc misses.
    VectorPath round, flat;
    std::string err;
    CHECK(openclip::parseVectorPathJson(
        "[{\"x\":32,\"y\":32,\"p\":1}]", 1.0f, "stroke", round, err));
    CHECK(openclip::parseVectorPathJson(
        "{\"pts\":[{\"x\":32,\"y\":32,\"p\":1}],\"tip\":\"flat\"}", 1.0f,
        "stroke", flat, err));
    CHECK_EQ(flat.tip, std::string("flat"));
    CHECK_EQ(round.tip, std::string("round"));
    Surface o1(64, 64), o2(64, 64);
    openclip::rasterizeVectorPaths({round}, o1);
    openclip::rasterizeVectorPaths({flat}, o2);
    // Width 16 -> half 8: corner (40,40) is dx=dy=8 -> dist 11.3 > 8
    // (round misses) but inside the flat square.
    CHECK_EQ(o1.get(40, 40).a, 0);
    CHECK(o2.get(40, 40).a != 0);
}

TEST(vector_size_mul_scales) {
    VectorPath p1, p2;
    std::string err;
    CHECK(openclip::parseVectorPathJson(hline(32, 10, 54, 1), 1.0f, "stroke",
                                        p1, err));
    CHECK(openclip::parseVectorPathJson(
        hlineStyle(32, 10, 54, 1, "\"size\":2"), 1.0f, "stroke", p2, err));
    CHECK(p2.sizeMul == 2.0f);
    Surface o1(64, 64), o2(64, 64);
    openclip::rasterizeVectorPaths({p1}, o1);
    openclip::rasterizeVectorPaths({p2}, o2);
    auto halfW = [](const Surface& o) {
        int lo = 64, hi = -1;
        for (int y = 0; y < 64; ++y)
            if (o.get(30, y).a != 0) {
                lo = std::min(lo, y);
                hi = std::max(hi, y);
            }
        return hi - lo;
    };
    const int w1 = halfW(o1), w2 = halfW(o2);
    CHECK(w1 > 0 && w2 > 0);
    CHECK(w2 >= 2 * w1 - 2 && w2 <= 2 * w1 + 2);  // ~double, ±1px
}

TEST(vector_corners_round_trip) {
    Canvas c(64, 64);
    const std::size_t li = c.addLayer("vec");
    VectorHistory h;
    std::string err;
    const std::string j =
        "{\"pts\":[{\"x\":10,\"y\":10,\"p\":1},{\"x\":50,\"y\":10,\"p\":1},"
        "{\"x\":50,\"y\":50,\"p\":1}],\"color\":\"#00FF00\","
        "\"tip\":\"flat\",\"size\":1.5,\"corners\":[0,1,0],"
        "\"kind\":\"stroke\"}";
    CHECK_EQ(openclip::appendVectorPath(c, h, li, j, 1.0f, "stroke", err), 1);
    const std::string path = "/tmp/oc_vecstyle.ora";
    CHECK(openclip::saveOra(c, path, err));
    Canvas d;
    CHECK(openclip::loadOra(d, path, err));
    std::remove(path.c_str());
    CHECK(d.layer(li).isVector);
    CHECK_EQ(d.layer(li).vectorData.size(), 1u);
    const VectorPath& q = d.layer(li).vectorData[0];
    CHECK_EQ(q.tip, std::string("flat"));
    CHECK(q.sizeMul == 1.5f);
    CHECK_EQ(q.r, 0);
    CHECK_EQ(q.g, 255);
    CHECK_EQ(q.b, 0);
    CHECK_EQ(q.corners.size(), q.pts.size());
    Surface a, b;
    c.composite(a);
    d.composite(b);
    CHECK(a.bytes() == b.bytes());  // sibling restore is byte-identical
}

TEST(vector_sibling_missing_falls_back) {
    // Vector layer WITHOUT paths: no sibling attr -> reloads as a
    // normal raster layer (degraded propre, never broken).
    Canvas c(32, 32);
    const std::size_t li = c.addLayer("vec");
    c.layer(li).isVector = true;
    const std::string path = "/tmp/oc_vecnosib.ora";
    std::string err;
    CHECK(openclip::saveOra(c, path, err));
    Canvas d;
    CHECK(openclip::loadOra(d, path, err));
    std::remove(path.c_str());
    CHECK(!d.layer(li).isVector);
    CHECK(!d.layer(li).vectorStored);
}

TEST(vector_eraser_ignores_color) {
    Canvas c(64, 64);
    const std::size_t li = c.addLayer("vec");
    VectorHistory h;
    std::string err;
    CHECK_EQ(openclip::appendVectorPath(
                 c, h, li, hlineStyle(32, 10, 54, 1, "\"color\":\"#FF0000\""),
                 1.0f, "stroke", err),
             1);
    // Eraser crossing the path, carrying a (meaningless) color key:
    // geometry decides, color is ignored (dest-out either way).
    const std::string eraser =
        "{\"pts\":[{\"x\":30,\"y\":0,\"p\":1},{\"x\":30,\"y\":64,\"p\":1}],"
        "\"color\":\"#00FF00\"}";
    CHECK_EQ(openclip::eraseVectorPaths(c, h, li, eraser, err), 1);
    CHECK(c.layer(li).vectorData.empty());
    CHECK(openclip::undoVectorPath(c, h, li));  // 1 step restores
    CHECK_EQ(c.layer(li).vectorData.size(), 1u);
}

// ---- T2 §3: retracer / connecter / epaisseur (ARCHITECTURE.md §T2) ----

TEST(vector_replace_keeps_style) {
    Canvas c(64, 64);
    const std::size_t li = c.addLayer("vec");
    VectorHistory h;
    std::string err;
    CHECK_EQ(openclip::appendVectorPath(
                 c, h, li,
                 hlineStyle(20, 10, 50, 1,
                            "\"color\":\"#FF0000\",\"tip\":\"flat\""),
                 1.0f, "stroke", err),
             1);
    CHECK(openclip::replaceVectorPath(c, h, li, 0, hline(40, 10, 50, 1),
                                      1.0f, err));
    const VectorPath& q = c.layer(li).vectorData[0];
    CHECK_EQ(q.r, 255);  // style kept...
    CHECK_EQ(q.tip, std::string("flat"));
    Surface out;
    c.composite(out);
    CHECK_EQ(out.get(30, 20).a, 0);  // ...but geometry moved
    CHECK(out.get(30, 40).a != 0);
    // Explicit style re-application wins.
    CHECK(openclip::replaceVectorPath(
        c, h, li, 0,
        hlineStyle(40, 10, 50, 1, "\"style\":{\"color\":\"#0000FF\"}"), 1.0f,
        err));
    CHECK_EQ(c.layer(li).vectorData[0].b, 255);
}

TEST(vector_replace_bad_id_noop) {
    Canvas c(64, 64);
    const std::size_t li = c.addLayer("vec");
    VectorHistory h;
    std::string err;
    CHECK_EQ(openclip::appendVectorPath(c, h, li, hline(20, 10, 50, 1), 1.0f,
                                        "stroke", err),
             1);
    Surface before;
    c.composite(before);
    CHECK(!openclip::replaceVectorPath(c, h, li, 7, hline(40, 10, 50, 1),
                                       1.0f, err));
    CHECK(!openclip::replaceVectorPath(c, h, li, 0, "garbage", 1.0f, err));
    CHECK_EQ(c.layer(li).vectorData.size(), 1u);
    Surface after;
    c.composite(after);
    CHECK(before.bytes() == after.bytes());
}

TEST(vector_connect_joins_ends) {
    Canvas c(128, 64);
    const std::size_t li = c.addLayer("vec");
    VectorHistory h;
    std::string err;
    CHECK_EQ(openclip::appendVectorPath(c, h, li, hline(32, 10, 50, 1), 1.0f,
                                        "stroke", err),
             1);
    CHECK_EQ(openclip::appendVectorPath(c, h, li, hline(32, 54, 94, 1), 1.0f,
                                        "stroke", err),
             2);
    const std::size_t n0 = c.layer(li).vectorData[0].pts.size() +
                           c.layer(li).vectorData[1].pts.size();
    CHECK(openclip::connectVectorPaths(c, h, li, 0, 1, 12.0f, err));
    CHECK_EQ(c.layer(li).vectorData.size(), 1u);
    // Length ~ sum (one 4px seam stitch, resampled on the M6 grid).
    const std::size_t n1 = c.layer(li).vectorData[0].pts.size();
    CHECK(n1 + 2 >= n0 && n1 <= n0 + 2);
    Surface out;
    c.composite(out);
    CHECK(out.get(52, 32).a != 0);  // the seam is painted
}

TEST(vector_connect_too_far_noop) {
    Canvas c(128, 64);
    const std::size_t li = c.addLayer("vec");
    VectorHistory h;
    std::string err;
    CHECK_EQ(openclip::appendVectorPath(c, h, li, hline(10, 10, 30, 1), 1.0f,
                                        "stroke", err),
             1);
    CHECK_EQ(openclip::appendVectorPath(c, h, li, hline(50, 80, 100, 1),
                                        1.0f, "stroke", err),
             2);
    Surface before;
    c.composite(before);
    CHECK(!openclip::connectVectorPaths(c, h, li, 0, 1, 12.0f, err));
    CHECK_EQ(c.layer(li).vectorData.size(), 2u);
    Surface after;
    c.composite(after);
    CHECK(before.bytes() == after.bytes());
}

TEST(vector_widths_rescale) {
    Canvas c(64, 64);
    const std::size_t li = c.addLayer("vec");
    VectorHistory h;
    std::string err;
    CHECK_EQ(openclip::appendVectorPath(c, h, li, hline(32, 10, 54, 1), 1.0f,
                                        "stroke", err),
             1);
    const std::size_t n = c.layer(li).vectorData[0].pts.size();
    std::vector<float> wide(n, 32.0f);
    CHECK(openclip::setVectorWidths(c, h, li, 0, wide, err));
    Surface out;
    c.composite(out);
    // 32px profile covers y=32±16: edge pixel painted.
    CHECK(out.get(30, 44).a != 0);
    CHECK(out.get(30, 52).a == 0);
}

TEST(vector_widths_bad_size_noop) {
    Canvas c(64, 64);
    const std::size_t li = c.addLayer("vec");
    VectorHistory h;
    std::string err;
    CHECK_EQ(openclip::appendVectorPath(c, h, li, hline(32, 10, 54, 1), 1.0f,
                                        "stroke", err),
             1);
    const std::vector<float> before = c.layer(li).vectorData[0].widthProfile;
    CHECK(!openclip::setVectorWidths(c, h, li, 0, {1.0f, 2.0f}, err));
    CHECK(!openclip::setVectorWidths(c, h, li, 9, before, err));
    CHECK(c.layer(li).vectorData[0].widthProfile == before);
}

TEST(vector_op_undo_restores) {
    Canvas c(128, 64);
    for (int round = 0; round < 3; ++round) {
        const std::size_t li = c.addLayer("v");
        VectorHistory h;
        std::string err;
        CHECK_EQ(openclip::appendVectorPath(c, h, li, hline(20, 10, 50, 1),
                                            1.0f, "stroke", err),
                 1);
        CHECK_EQ(openclip::appendVectorPath(c, h, li, hline(20, 54, 94, 1),
                                            1.0f, "stroke", err),
                 2);
        Surface before;
        c.composite(before);
        bool ok = false;
        if (round == 0)
            ok = openclip::replaceVectorPath(c, h, li, 0, hline(40, 10, 50, 1),
                                             1.0f, err);
        else if (round == 1)
            ok = openclip::connectVectorPaths(c, h, li, 0, 1, 12.0f, err);
        else {
            const std::size_t n = c.layer(li).vectorData[0].pts.size();
            ok = openclip::setVectorWidths(c, h, li, 0,
                                           std::vector<float>(n, 24.0f), err);
        }
        CHECK(ok);
        CHECK(openclip::undoVectorPath(c, h, li));
        Surface after;
        c.composite(after);
        CHECK(before.bytes() == after.bytes());  // byte-identical restore
    }
}
