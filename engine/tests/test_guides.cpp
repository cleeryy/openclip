// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip V+2 — guide snap tests (ARCHITECTURE.md "V+2 additions").
#include "test.h"

#include "openclip/guides.h"

using openclip::Guide;
using openclip::snapGrid;
using openclip::snapGuides;
using openclip::snapPathPoints;
using openclip::Vec2;

// Vertical guide snaps x within 2 px, leaves y alone, ignores far points.
TEST(guides_snap_vertical_2px) {
    std::vector<Guide> gs;
    Guide v;
    v.kind = Guide::Kind::Vertical;
    v.x = 100;
    gs.push_back(v);
    float x = 101.5f, y = 40;
    CHECK(snapGuides(gs, x, y));
    CHECK_EQ(x, 100);
    CHECK_EQ(y, 40);
    float fx = 103.0f, fy = 40;
    CHECK(!snapGuides(gs, fx, fy));
    CHECK_EQ(fx, 103.0f);
    // Negative tolerance never snaps.
    float nx = 100.5f, ny = 1;
    CHECK(!snapGuides(gs, nx, ny, -1.0f));
}

// Horizontal + circle projection; nearest guide wins.
TEST(guides_circle_and_nearest) {
    std::vector<Guide> gs;
    Guide h;
    h.kind = Guide::Kind::Horizontal;
    h.y = 50;
    gs.push_back(h);
    Guide c;
    c.kind = Guide::Kind::Circle;
    c.cx = 0;
    c.cy = 0;
    c.r = 10;
    gs.push_back(c);
    // Near the circle (dist 11 vs dy 39): projects onto r=10.
    float x = 11.0f, y = 0.0f;
    CHECK(snapGuides(gs, x, y));
    CHECK(x > 9.9f && x < 10.1f);
    CHECK(y > -0.1f && y < 0.1f);
    // Near the horizontal line: y snaps, x untouched.
    float hx = 200.0f, hy = 51.0f;
    CHECK(snapGuides(gs, hx, hy));
    CHECK_EQ(hx, 200.0f);
    CHECK_EQ(hy, 50);
    // Degenerate circle (r<=0) and center point never snap.
    std::vector<Guide> bad;
    Guide z;
    z.kind = Guide::Kind::Circle;
    z.cx = 5;
    z.cy = 5;
    z.r = 0;
    bad.push_back(z);
    float bx = 5.0f, by = 5.0f;
    CHECK(!snapGuides(bad, bx, by));
    std::vector<Guide> atCenter;
    Guide cc;
    cc.kind = Guide::Kind::Circle;
    cc.cx = 5;
    cc.cy = 5;
    cc.r = 10;
    atCenter.push_back(cc);
    float ax = 5.0f, ay = 5.0f;
    CHECK(!snapGuides(atCenter, ax, ay));
}

// V+3 grid snap (ARCHITECTURE.md "V+3 additions"): 16 px intersections
// within tolerance; far points untouched; tol <= 0 disables.
TEST(guides_grid_snap_16px) {
    float x = 30.0f, y = 35.0f;  // nearest 16-px node is (32,32)
    CHECK(snapGrid(x, y, 16.0f, 4.0f));
    CHECK_EQ(x, 32.0f);
    CHECK_EQ(y, 32.0f);
    float fx = 30.0f, fy = 35.0f;
    CHECK(!snapGrid(fx, fy, 16.0f, 1.0f));  // nearest is 2-3 px away
    CHECK_EQ(fx, 30.0f);
    CHECK_EQ(fy, 35.0f);
    float dx = 30.0f, dy = 35.0f;
    CHECK(!snapGrid(dx, dy, 16.0f, 0.0f));  // tol 0 disables
    CHECK(!snapGrid(dx, dy, 0.0f, 4.0f));   // step 0 = off
    float gx = 100.5f, gy = 1.0f;
    CHECK(!snapGuides({}, gx, gy, 0.0f));  // tol 0: no guides, no snap
}

// V+3 neighboring-path snap: nearest vertex within tolerance wins;
// tol <= 0 disables; empty lists never snap.
TEST(guides_path_vertex_snap) {
    std::vector<Vec2> pts = {{10.0f, 10.0f}, {90.0f, 20.0f}};
    float x = 11.0f, y = 12.0f;
    CHECK(snapPathPoints(pts, x, y, 4.0f));
    CHECK_EQ(x, 10.0f);
    CHECK_EQ(y, 10.0f);
    float fx = 50.0f, fy = 50.0f;
    CHECK(!snapPathPoints(pts, fx, fy, 4.0f));
    CHECK_EQ(fx, 50.0f);
    float tx = 11.0f, ty = 12.0f;
    CHECK(!snapPathPoints(pts, tx, ty, 0.0f));  // tol 0 disables
    float ex = 11.0f, ey = 12.0f;
    CHECK(!snapPathPoints({}, ex, ey, 4.0f));
}
