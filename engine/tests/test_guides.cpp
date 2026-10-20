// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip V+2 — guide snap tests (ARCHITECTURE.md "V+2 additions").
#include "test.h"

#include "openclip/guides.h"

using openclip::Guide;
using openclip::snapGuides;

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
