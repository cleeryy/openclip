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

// V+3 mixer knife (ARCHITECTURE.md "V+3 additions"): mix 0 is the plain
// brush, mix 1 pushes local paint only, out-of-range clamps.
TEST(mixer_zero_is_byte_identical) {
    Canvas a(32, 32);
    a.addLayer("bg");
    Canvas b(32, 32);
    b.addLayer("bg");
    BrushParams plain;
    plain.radius = 6.0f;
    plain.hardness = 1.0f;
    BrushParams mixed = plain;
    mixed.mix = 0.0f;
    mixed.mixRadius = 2.0f;  // radius window is irrelevant at mix 0
    Dab d = dabFromPressure(plain, 16.0f, 16.0f, 1.0f, 0, 0, 255);
    paintDab(a.layer(0).surface, d, plain);
    paintDab(b.layer(0).surface, d, mixed);
    CHECK(a.layer(0).surface.bytes() == b.layer(0).surface.bytes());
}

TEST(mixer_one_pushes_local_paint_only) {
    // White flat, blue ink: a pure knife (mix 1) picks up white and
    // introduces no blue; the plain brush paints blue.
    Canvas knife(32, 32);
    knife.addLayer("bg");
    Canvas plain(32, 32);
    plain.addLayer("bg");
    for (Canvas* c : {&knife, &plain})
        for (int y = 0; y < 32; ++y)
            for (int x = 0; x < 32; ++x)
                c->layer(0).surface.set(x, y, {255, 255, 255, 255});
    BrushParams pk;
    pk.radius = 6.0f;
    pk.hardness = 1.0f;
    pk.mix = 1.0f;
    BrushParams pb = pk;
    pb.mix = 0.0f;
    paintDab(knife.layer(0).surface,
             dabFromPressure(pk, 16.0f, 16.0f, 1.0f, 0, 0, 255), pk);
    paintDab(plain.layer(0).surface,
             dabFromPressure(pb, 16.0f, 16.0f, 1.0f, 0, 0, 255), pb);
    auto kc = knife.layer(0).surface.get(16, 16);
    auto pc = plain.layer(0).surface.get(16, 16);
    // Knife: no new ink introduced (stays white).
    CHECK_EQ(kc.r, 255);
    CHECK_EQ(kc.b, 255);
    // Brush: blue dominates at the dab center.
    CHECK(pc.b > 200 && pc.r < 100);
}

TEST(mixer_out_of_range_clamped) {
    Canvas c(32, 32);
    c.addLayer("bg");
    for (int y = 0; y < 32; ++y)
        for (int x = 0; x < 32; ++x)
            c.layer(0).surface.set(x, y, {255, 255, 255, 255});
    BrushParams p;
    p.radius = 6.0f;
    p.hardness = 1.0f;
    p.mix = 5.0f;  // clamps to 1: pure knife, no blue on white
    paintDab(c.layer(0).surface,
             dabFromPressure(p, 16.0f, 16.0f, 1.0f, 0, 0, 255), p);
    auto k = c.layer(0).surface.get(16, 16);
    CHECK_EQ(k.r, 255);
    CHECK_EQ(k.b, 255);
    p.mix = -1.0f;  // clamps to 0: plain brush, blue appears
    p.mixRadius = 99.0f;  // clamps to 2.0, irrelevant at mix 0
    paintDab(c.layer(0).surface,
             dabFromPressure(p, 8.0f, 8.0f, 1.0f, 0, 0, 255), p);
    auto q = c.layer(0).surface.get(8, 8);
    CHECK(q.b > 200 && q.r < 100);
}
