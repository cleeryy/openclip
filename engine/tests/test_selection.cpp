// SPDX-License-Identifier: GPL-3.0-or-later
// W1 (issue #28 slice A): selection refinement — grow/shrink/feather,
// polyline fill, select-from-layer-alpha.
#include "test.h"

#include <utility>
#include <vector>

#include "openclip/selection.h"
#include "openclip/surface.h"

using openclip::AlphaPlane;
using openclip::Pixel;
using openclip::Surface;

namespace {

void fillPlane(AlphaPlane& p, int x0, int y0, int x1, int y1, std::uint8_t v) {
    for (int y = y0; y < y1; ++y)
        for (int x = x0; x < x1; ++x) p.set(x, y, v);
}

}  // namespace

TEST(selection_grow_expands_square_window) {
    AlphaPlane p;
    p.resize(32, 32);
    p.set(16, 16, 255);
    openclip::growPlane(p, 2);
    // 5x5 square window around the single pixel.
    CHECK_EQ(openclip::planeCoverage(p), 25);
    CHECK_EQ(p.at(14, 14), 255);
    CHECK_EQ(p.at(18, 18), 255);
    CHECK_EQ(p.at(13, 16), 0);
}

TEST(selection_grow_soft_levels_preserved) {
    AlphaPlane p;
    p.resize(16, 16);
    p.set(8, 8, 100);
    openclip::growPlane(p, 1);
    CHECK_EQ(openclip::planeCoverage(p), 9);
    CHECK_EQ(p.at(8, 8), 100);  // max filter keeps the original level
    CHECK_EQ(p.at(7, 8), 100);
}

TEST(selection_shrink_contracts) {
    AlphaPlane p;
    p.resize(16, 16);
    fillPlane(p, 4, 4, 12, 12, 255);  // 8x8 block
    openclip::shrinkPlane(p, 1);
    CHECK_EQ(openclip::planeCoverage(p), 36);  // 6x6
    CHECK_EQ(p.at(4, 4), 0);
    CHECK_EQ(p.at(8, 8), 255);
}

TEST(selection_shrink_empties_thin_selection) {
    AlphaPlane p;
    p.resize(8, 8);
    p.set(4, 4, 255);
    openclip::shrinkPlane(p, 1);
    CHECK_EQ(openclip::planeCoverage(p), 0);
}

TEST(selection_feather_softens_edge) {
    AlphaPlane p;
    p.resize(32, 32);
    fillPlane(p, 8, 8, 24, 24, 255);
    openclip::featherPlane(p, 2);
    const int centre = p.at(16, 16);
    const int edge = p.at(8, 16);
    const int outside = p.at(2, 16);
    CHECK_EQ(centre, 255);
    CHECK(edge > 0 && edge < 255);  // graded edge
    CHECK(outside < edge);
    CHECK(outside <= 40);
}

TEST(selection_feather_radius_zero_is_identity) {
    AlphaPlane p;
    p.resize(8, 8);
    fillPlane(p, 2, 2, 6, 6, 255);
    const std::vector<std::uint8_t> before = p.alpha;
    openclip::featherPlane(p, 0);
    CHECK(p.alpha == before);
    openclip::growPlane(p, 0);
    CHECK(p.alpha == before);
}

TEST(selection_polygon_fill_triangle) {
    AlphaPlane p;
    p.resize(32, 32);
    const std::vector<std::pair<float, float>> tri = {
        {4.0f, 4.0f}, {28.0f, 4.0f}, {16.0f, 28.0f}};
    openclip::fillPolygonPlane(p, tri);
    CHECK_EQ(p.at(16, 10), 255);   // inside
    CHECK_EQ(p.at(4, 4), 255);     // outline
    CHECK_EQ(p.at(2, 30), 0);      // outside
    CHECK_EQ(p.at(28, 28), 0);     // outside
    CHECK(openclip::planeCoverage(p) > 100);
}

TEST(selection_polygon_too_few_points_noop) {
    AlphaPlane p;
    p.resize(8, 8);
    openclip::fillPolygonPlane(p, {{0.0f, 0.0f}, {4.0f, 4.0f}});
    CHECK_EQ(openclip::planeCoverage(p), 0);
}

TEST(selection_from_layer_alpha) {
    Surface s(16, 16);
    s.set(4, 4, Pixel{255, 0, 0, 200});
    s.set(8, 8, Pixel{0, 255, 0, 90});
    AlphaPlane p;
    openclip::alphaPlaneFromSurface(s, 100, p);
    CHECK_EQ(p.at(4, 4), 200);  // soft alpha kept
    CHECK_EQ(p.at(8, 8), 0);    // below threshold
    CHECK_EQ(openclip::planeCoverage(p), 1);
    openclip::alphaPlaneFromSurface(s, 255, p);
    CHECK_EQ(openclip::planeCoverage(p), 0);
}

TEST(selection_radius_clamped) {
    AlphaPlane p;
    p.resize(8, 8);
    p.set(4, 4, 255);
    openclip::growPlane(p, 10000);  // clamps to kSelectionRadiusMax
    CHECK_EQ(openclip::planeCoverage(p), 64);  // covers the plane
}
