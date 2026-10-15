// SPDX-License-Identifier: GPL-3.0-or-later
#include "test.h"

#include "openclip/brush.h"
#include "openclip/canvas.h"

using openclip::BrushParams;
using openclip::Canvas;
using openclip::dabFromPressure;
using openclip::Dab;
using openclip::interpolateStamps;
using openclip::paintDab;
using openclip::parseMyb;

TEST(brush_pressure_mapping) {
    BrushParams p;
    p.radius = 10.0f;
    p.opaque = 0.8f;
    Dab full = dabFromPressure(p, 0, 0, 1.0f, 255, 0, 0);
    CHECK_EQ(full.radius, 10.0f);
    CHECK_EQ(full.opacity, 0.8f);
    Dab half = dabFromPressure(p, 0, 0, 0.5f, 255, 0, 0);
    // radius = 10*(0.25+0.75*0.5) = 6.25 ; opacity = 0.8*0.5 = 0.4
    CHECK(half.radius > 6.24f && half.radius < 6.26f);
    CHECK(half.opacity > 0.39f && half.opacity < 0.41f);
    Dab zero = dabFromPressure(p, 0, 0, 0.0f, 255, 0, 0);
    CHECK_EQ(zero.opacity, 0.0f);
    CHECK(zero.radius < full.radius);
}

TEST(brush_interpolation_spacing) {
    BrushParams p;
    p.radius = 10.0f;
    p.spacing = 0.1f;  // step = 1px
    auto dabs = interpolateStamps(p, 0, 0, 1.0f, 10, 0, 1.0f, 0, 0, 0);
    // dist 10 / step 1 -> n=10 -> 11 stamps, endpoints exact.
    CHECK_EQ(dabs.size(), (std::size_t)11);
    CHECK_EQ(dabs.front().x, 0.0f);
    CHECK_EQ(dabs.back().x, 10.0f);
    // Zero-length stroke still yields one dab.
    auto one = interpolateStamps(p, 5, 5, 0.5f, 5, 5, 0.5f, 0, 0, 0);
    CHECK_EQ(one.size(), (std::size_t)1);
}

TEST(brush_paint_center_opaque) {
    Canvas c(32, 32);
    c.addLayer("bg");
    BrushParams p;
    p.radius = 4.0f;
    p.hardness = 1.0f;
    Dab d = dabFromPressure(p, 16.0f, 16.0f, 1.0f, 255, 0, 0);
    paintDab(c.layer(0).surface, d, p);
    auto px = c.layer(0).surface.get(16, 16);
    CHECK_EQ(px.a, 255);
    CHECK_EQ(px.r, 255);
    CHECK_EQ(px.g, 0);
    // Far outside the dab: untouched.
    auto far = c.layer(0).surface.get(0, 0);
    CHECK_EQ(far.a, 0);
}

TEST(myb_parser) {
    BrushParams p;
    std::string err;
    const std::string xml =
        "<brush name=\"test\">\n"
        "  <setting name=\"radius\" base_value=\"12.5\"/>\n"
        "  <setting name=\"hardness\" base_value=\"0.3\"/>\n"
        "  <setting name=\"opaque\" base_value=\"0.9\"/>\n"
        "  <setting name=\"unknown_future\" base_value=\"42\"/>\n"
        "</brush>\n";
    CHECK(parseMyb(xml, p, err));
    CHECK(p.radius > 12.4f && p.radius < 12.6f);
    CHECK(p.hardness > 0.29f && p.hardness < 0.31f);
    CHECK(p.opaque > 0.89f && p.opaque < 0.91f);

    // Nested <value base_value="..."/> form.
    const std::string xml2 =
        "<brush><setting name=\"radius\"><value base_value=\"5\"/>"
        "</setting><setting name=\"hardness\" base_value=\"1\"/>"
        "<setting name=\"opaque\" base_value=\"1\"/></brush>";
    CHECK(parseMyb(xml2, p, err));
    CHECK_EQ(p.radius, 5.0f);

    // Malformed: no settings at all.
    CHECK(!parseMyb("<brush></brush>", p, err));
    CHECK(!err.empty());
    // Malformed: non-numeric radius.
    CHECK(!parseMyb(
        "<brush><setting name=\"radius\" base_value=\"big\"/></brush>", p,
        err));
}
