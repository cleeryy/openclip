// SPDX-License-Identifier: GPL-3.0-or-later
#include "test.h"

#include <cmath>

#include "openclip/canvas.h"
#include "openclip/stroke.h"
#include "openclip/undo.h"

using openclip::BrushParams;
using openclip::Canvas;
using openclip::History;
using openclip::PointCurve;
using openclip::Stroke;

TEST(stroke_curve_mapping) {
    PointCurve lin = PointCurve::fromJsonCurve(0.0f);
    CHECK(lin.exponent > 0.99f && lin.exponent < 1.01f);
    CHECK(lin.at(0.5f) > 0.49f && lin.at(0.5f) < 0.51f);
    PointCurve heavy = PointCurve::fromJsonCurve(1.0f);
    CHECK(heavy.exponent > 3.99f && heavy.exponent < 4.01f);
    // pow(0.5, 4) = 0.0625: heavy curve crushes mid pressure.
    CHECK(heavy.at(0.5f) > 0.05f && heavy.at(0.5f) < 0.08f);
    CHECK_EQ(heavy.at(0.0f), 0.0f);
    CHECK(heavy.at(1.0f) > 0.999f);
}

TEST(stroke_stamp_count_accumulator) {
    // 100px straight line, spacing 0.15, radius 10 => step 1.5px.
    // 1 (begin) + floor(100/1.5) = 67 stamps, whatever the chunking of to().
    Canvas c(256, 64);
    c.addLayer("l0");
    BrushParams p;
    p.radius = 10.0f;
    p.spacing = 0.15f;
    Stroke s(c.layer(0).surface, p, PointCurve::fromJsonCurve(0.0f), 0.0f,
             255, 0, 0);
    s.begin(10.0f, 32.0f, 1.0f);
    // 10 moves of 10px each: a naive per-segment count would over-emit.
    for (int i = 1; i <= 10; ++i) s.to(10.0f + i * 10.0f, 32.0f, 1.0f);
    s.end();
    CHECK(s.stampCount() >= (std::size_t)64);
    CHECK(s.stampCount() <= (std::size_t)70);

    // Same line in ONE move must give the same count (+/-1 for rounding).
    Canvas c2(256, 64);
    c2.addLayer("l0");
    Stroke s2(c2.layer(0).surface, p, PointCurve::fromJsonCurve(0.0f), 0.0f,
              255, 0, 0);
    s2.begin(10.0f, 32.0f, 1.0f);
    s2.to(110.0f, 32.0f, 1.0f);
    s2.end();
    const long a = static_cast<long>(s.stampCount());
    const long b = static_cast<long>(s2.stampCount());
    CHECK(a - b >= -1 && a - b <= 1);
}

TEST(stroke_stabilization_damps_jitter) {
    // Alternating +/-30px jitter around y=32 with max stabilization:
    // the smoothed cursor must drift far less than the raw input.
    Canvas c(256, 128);
    c.addLayer("l0");
    BrushParams p;
    p.radius = 4.0f;
    p.spacing = 0.5f;
    Stroke s(c.layer(0).surface, p, PointCurve::fromJsonCurve(0.0f), 1.0f,
             255, 0, 0);
    s.begin(10.0f, 32.0f, 1.0f);
    float maxDev = 0;
    for (int i = 1; i <= 20; ++i) {
        const float y = 32.0f + ((i % 2 == 0) ? 30.0f : -30.0f);
        s.to(10.0f + i * 5.0f, y, 1.0f);
        const float dev = std::fabs(s.state().py - 32.0f);
        if (dev > maxDev) maxDev = dev;
    }
    s.end();
    // Raw jitter is 30px; stabilized cursor must stay well under half.
    CHECK(maxDev < 12.0f);
    // No stabilization: cursor tracks input exactly (dev hits 30).
    Canvas c2(256, 128);
    c2.addLayer("l0");
    Stroke s2(c2.layer(0).surface, p, PointCurve::fromJsonCurve(0.0f), 0.0f,
              255, 0, 0);
    s2.begin(10.0f, 32.0f, 1.0f);
    float maxDev2 = 0;
    for (int i = 1; i <= 20; ++i) {
        const float y = 32.0f + ((i % 2 == 0) ? 30.0f : -30.0f);
        s2.to(10.0f + i * 5.0f, y, 1.0f);
        const float dev = std::fabs(s2.state().py - 32.0f);
        if (dev > maxDev2) maxDev2 = dev;
    }
    s2.end();
    CHECK(maxDev2 > 29.0f);
}

TEST(stroke_roundtrip_with_undo) {
    // Vertical stroke (round trip x=10->10.x) with History wired:
    // undo must restore the pre-stroke surface exactly.
    Canvas c(300, 300);  // spans 2x2 tiles
    c.addLayer("l0");
    History h;
    auto& surf = c.layer(0).surface;
    const auto blank = surf.bytes();
    BrushParams p;
    p.radius = 6.0f;
    h.beginStroke();
    Stroke s(surf, p, PointCurve::fromJsonCurve(0.0f), 0.0f, 0, 0, 255,
             &h, &c, 0);
    s.begin(10.0f, 10.0f, 1.0f);
    for (int i = 1; i <= 20; ++i) s.to(10.0f, 10.0f + i * 10.0f, 1.0f);
    // Round trip back up.
    for (int i = 19; i >= 0; --i) s.to(10.0f, 10.0f + i * 10.0f, 1.0f);
    s.end();
    h.endStroke(c);
    CHECK(surf.bytes() != blank);  // stroke painted something
    CHECK(h.canUndo());
    CHECK(h.undo(c));
    CHECK(surf.bytes() == blank);
    CHECK(h.canRedo());
    CHECK(h.redo(c));
    CHECK(surf.bytes() != blank);
}

TEST(stroke_dupes_ignored) {
    Canvas c(128, 128);
    c.addLayer("l0");
    BrushParams p;
    p.radius = 8.0f;
    p.spacing = 0.15f;
    Stroke s(c.layer(0).surface, p, PointCurve::fromJsonCurve(0.0f), 0.0f,
             255, 0, 0);
    s.begin(10.0f, 10.0f, 1.0f);
    const std::size_t afterBegin = s.stampCount();
    for (int i = 0; i < 10; ++i) s.to(10.0f, 10.0f, 1.0f);  // exact dupes
    CHECK_EQ(s.stampCount(), afterBegin);
    s.end();
}
