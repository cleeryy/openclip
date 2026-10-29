// SPDX-License-Identifier: GPL-3.0-or-later
// M8 adjustment-layer tests (ARCHITECTURE.md "M8 additions"): the ops
// transform the accumulated output below the layer, unpremultiplied and
// repremultiplied with alpha untouched; ORA round-trips the descriptor.
#include "test.h"

#include <cstdio>
#include <string>

#include "openclip/canvas.h"
#include "openclip/ora.h"

using openclip::Canvas;
using openclip::Layer;
using openclip::Pixel;
using openclip::Surface;

namespace {

// Build a 3-layer stack: opaque gray base, an adjustment layer, then an
// optional top layer (returns its index, -1 when absent).
int buildStack(Canvas& c, std::uint8_t gray = 128, bool withTop = false) {
    c.addLayer("base");
    for (int y = 0; y < c.height(); ++y)
        for (int x = 0; x < c.width(); ++x)
            c.layer(0).surface.set(x, y, Pixel{gray, gray, gray, 255});
    const std::size_t adj = c.addLayer("adjust");
    if (withTop) {
        const std::size_t top = c.addLayer("top");
        c.layer(top).surface.set(2, 2, Pixel{255, 0, 0, 255});
    }
    return static_cast<int>(adj);
}

bool sameOut(const Surface& a, const Surface& b) {
    if (a.width() != b.width() || a.height() != b.height()) return false;
    for (int y = 0; y < a.height(); ++y)
        for (int x = 0; x < a.width(); ++x)
            if (!(a.get(x, y).r == b.get(x, y).r &&
                  a.get(x, y).g == b.get(x, y).g &&
                  a.get(x, y).b == b.get(x, y).b &&
                  a.get(x, y).a == b.get(x, y).a))
                return false;
    return true;
}

}  // namespace

TEST(adjust_brightness_shifts_gray_alpha_untouched) {
    Canvas c(8, 8);
    const int adj = buildStack(c);
    c.layer(adj).adjustType = "brightness";
    c.layer(adj).adjustAmount = 0.5;
    Surface out;
    c.composite(out);
    CHECK_EQ(out.get(4, 4).r, 192);  // 128 + 0.5*128
    CHECK_EQ(out.get(4, 4).g, 192);
    CHECK_EQ(out.get(4, 4).b, 192);
    CHECK_EQ(out.get(4, 4).a, 255);  // alpha never moves
}

TEST(adjust_contrast_increases_spread) {
    Canvas c(8, 8);
    const int adj = buildStack(c);
    c.layer(adj).adjustType = "contrast";
    c.layer(adj).adjustAmount = 1.0;  // x2 around 128
    c.layer(0).surface.set(0, 0, Pixel{96, 96, 96, 255});
    c.layer(0).surface.set(7, 7, Pixel{160, 160, 160, 255});
    Surface out;
    c.composite(out);
    CHECK_EQ(out.get(0, 0).r, 64);    // 128 + (96-128)*2
    CHECK_EQ(out.get(7, 7).r, 192);   // 128 + (160-128)*2
    CHECK_EQ(out.get(0, 0).a, 255);
}

TEST(adjust_saturation_pulls_toward_luma) {
    Canvas c(8, 8);
    const int adj = buildStack(c);
    c.layer(adj).adjustType = "saturation";
    c.layer(adj).adjustAmount = -1.0;  // full desaturate
    c.layer(0).surface.set(4, 4, Pixel{255, 90, 20, 255});
    Surface out;
    c.composite(out);
    const Pixel p = out.get(4, 4);
    CHECK_EQ(p.r, p.g);  // channels equal = luma gray
    CHECK_EQ(p.g, p.b);
    CHECK_EQ(p.a, 255);
}

TEST(adjust_hue_rotates_red_toward_green) {
    Canvas c(8, 8);
    const int adj = buildStack(c);
    c.layer(adj).adjustType = "hue";
    c.layer(adj).adjustAmount = 1.0 / 3.0;  // +60deg
    c.layer(0).surface.set(4, 4, Pixel{255, 0, 0, 255});
    Surface out;
    c.composite(out);
    const Pixel p = out.get(4, 4);
    // Red rotated 60 degrees lands in the yellow band: green appears,
    // red no longer dominates alone.
    CHECK(p.g > 180);
    CHECK(p.b < 120);
    CHECK_EQ(p.a, 255);
}

TEST(adjust_gradientmap_maps_endpoints) {
    Canvas c(8, 8);
    const int adj = buildStack(c);
    c.layer(adj).adjustType = "gradientMap";
    c.layer(adj).adjustStops = "[\"#FF0000:0\",\"#0000FF:1\"]";
    c.layer(0).surface.set(0, 0, Pixel{0, 0, 0, 255});      // black
    c.layer(0).surface.set(7, 7, Pixel{255, 255, 255, 255});  // white
    Surface out;
    c.composite(out);
    const Pixel blk = out.get(0, 0);
    CHECK(blk.r > 200);
    CHECK(blk.b < 60);
    const Pixel wht = out.get(7, 7);
    CHECK(wht.b > 200);
    CHECK(wht.r < 60);
}

TEST(adjust_unknown_type_is_identity) {
    Canvas c(8, 8);
    const int adj = buildStack(c);
    c.layer(adj).adjustType = "mystery";
    c.layer(adj).adjustAmount = 1.0;
    Surface out;
    c.composite(out);
    c.layer(adj).adjustType.clear();
    Surface plain;
    c.composite(plain);
    CHECK(sameOut(out, plain));
}

TEST(adjust_opacity_half_is_midpoint) {
    Canvas c(8, 8);
    const int adj = buildStack(c);
    c.layer(adj).adjustType = "brightness";
    c.layer(adj).adjustAmount = 0.5;  // +64 full
    c.layer(adj).opacity = 0.5f;      // halfway = +32
    Surface out;
    c.composite(out);
    CHECK_EQ(out.get(4, 4).r, 160);
}

TEST(adjust_layer_above_is_unaffected) {
    Canvas c(8, 8);
    const int adj = buildStack(c, 128, true);
    c.layer(adj).adjustType = "brightness";
    c.layer(adj).adjustAmount = 0.5;
    Surface out;
    c.composite(out);
    // The top red pixel paints AFTER the adjustment: untouched.
    CHECK_EQ(out.get(2, 2).r, 255);
    CHECK_EQ(out.get(2, 2).g, 0);
    // And the rest of the canvas IS adjusted.
    CHECK_EQ(out.get(7, 7).r, 192);
}

TEST(adjust_invisible_layer_skipped) {
    Canvas c(8, 8);
    const int adj = buildStack(c);
    c.layer(adj).adjustType = "brightness";
    c.layer(adj).adjustAmount = 1.0;
    c.layer(adj).visible = false;
    Surface out;
    c.composite(out);
    CHECK_EQ(out.get(4, 4).r, 128);  // base gray, untouched
}

TEST(adjust_ora_roundtrip_keeps_descriptor) {
    Canvas c(8, 8);
    const int adj = buildStack(c);
    c.layer(adj).adjustType = "saturation";
    c.layer(adj).adjustAmount = -1.0;
    c.layer(adj).adjustStops = "[\"#000000:0\",\"#FFFFFF:1\"]";
    const std::string path = testh::tmpPath("openclip_test_adjust.ora");
    std::string err;
    if (!openclip::saveOra(c, path, err)) {
        std::printf("  save failed: %s\n", err.c_str());
        std::fflush(stdout);
        CHECK(false);
        return;
    }
    Canvas d;
    if (!openclip::loadOra(d, path, err)) {
        std::printf("  load failed: %s\n", err.c_str());
        std::fflush(stdout);
        CHECK(false);
        std::remove(path.c_str());
        return;
    }
    std::remove(path.c_str());
    CHECK(d.layerCount() >= 2);
    CHECK_EQ(d.layer(1).adjustType, std::string("saturation"));
    CHECK(d.layer(1).adjustAmount < -0.99);
    CHECK_EQ(d.layer(1).adjustStops,
             std::string("[\"#000000:0\",\"#FFFFFF:1\"]"));
    // The descriptor still works after the round trip.
    Surface out;
    d.composite(out);
    const Pixel p = out.get(4, 4);
    CHECK_EQ(p.r, p.g);
    CHECK_EQ(p.g, p.b);
}

TEST(adjust_ora_unknown_type_ignored_on_load) {
    Canvas c(8, 8);
    const int adj = buildStack(c);
    c.layer(adj).adjustType = "mystery";
    c.layer(adj).adjustAmount = 1.0;
    const std::string path = testh::tmpPath("openclip_test_adjust2.ora");
    std::string err;
    if (!openclip::saveOra(c, path, err)) {
        std::printf("  save failed: %s\n", err.c_str());
        std::fflush(stdout);
        CHECK(false);
        return;
    }
    Canvas d;
    if (!openclip::loadOra(d, path, err)) {
        std::printf("  load failed: %s\n", err.c_str());
        std::fflush(stdout);
        CHECK(false);
        std::remove(path.c_str());
        return;
    }
    std::remove(path.c_str());
    CHECK(d.layerCount() >= 2);
    CHECK(d.layer(1).adjustType.empty());
}

// V+2 curves: identity descriptor (diagonal) is byte-identical.
TEST(curves_identity_is_byte_identical) {
    Canvas c(8, 8);
    const int adj = buildStack(c, 100);
    c.layer(adj).adjustType = "curves";
    c.layer(adj).adjustCurve = "rgb;0,0;255,255";
    Surface a;
    c.composite(a);
    Canvas plain(8, 8);
    buildStack(plain, 100);
    plain.layer(1).adjustType = "";
    Surface b;
    plain.composite(b);
    CHECK(sameOut(a, b));
    // Malformed spec = honest identity too.
    c.layer(adj).adjustCurve = "banana";
    Surface d;
    c.composite(d);
    CHECK(sameOut(a, d));
}

// V+2 curves: inversion maps gray 100 -> ~155 through the LUT.
TEST(curves_inversion_maps_gray) {
    Canvas c(8, 8);
    const int adj = buildStack(c, 100);
    c.layer(adj).adjustType = "curves";
    c.layer(adj).adjustCurve = "rgb;0,255;255,0";
    Surface out;
    c.composite(out);
    CHECK_EQ(out.get(4, 4).r, 155);  // 255-100
    CHECK_EQ(out.get(4, 4).g, 155);
    CHECK_EQ(out.get(4, 4).b, 155);
    CHECK_EQ(out.get(4, 4).a, 255);
}

// V+2 curves: per-channel mode isolates the channel, and the ORA
// round trip keeps adjust-curve verbatim + working.
TEST(curves_channel_and_ora_roundtrip) {
    Canvas c(8, 8);
    const int adj = buildStack(c, 100);
    c.layer(adj).adjustType = "curves";
    c.layer(adj).adjustCurve = "r;0,255;255,0";
    Surface out;
    c.composite(out);
    CHECK_EQ(out.get(4, 4).r, 155);  // red inverted...
    CHECK_EQ(out.get(4, 4).g, 100);  // ...green untouched...
    CHECK_EQ(out.get(4, 4).b, 100);  // ...blue untouched.
    const std::string path = testh::tmpPath("openclip_test_curves.ora");
    std::string err;
    if (!openclip::saveOra(c, path, err)) {
        std::printf("  save failed: %s\n", err.c_str());
        std::fflush(stdout);
        CHECK(false);
        return;
    }
    Canvas d;
    if (!openclip::loadOra(d, path, err)) {
        std::printf("  load failed: %s\n", err.c_str());
        std::fflush(stdout);
        CHECK(false);
        std::remove(path.c_str());
        return;
    }
    std::remove(path.c_str());
    CHECK(d.layerCount() >= 2);
    CHECK_EQ(d.layer(1).adjustType, std::string("curves"));
    CHECK_EQ(d.layer(1).adjustCurve, std::string("r;0,255;255,0"));
    Surface re;
    d.composite(re);
    CHECK(sameOut(out, re));
}
