// SPDX-License-Identifier: GPL-3.0-or-later
#include "test.h"

#include <cstdio>

#include "openclip/brush.h"
#include "openclip/canvas.h"
#include "openclip/ora.h"

using openclip::BlendMode;
using openclip::BrushParams;
using openclip::Canvas;
using openclip::dabFromPressure;
using openclip::exportPng;
using openclip::loadOra;
using openclip::paintDab;
using openclip::saveOra;

static bool pxEq(openclip::Pixel a, openclip::Pixel b) {
    return a.r == b.r && a.g == b.g && a.b == b.b && a.a == b.a;
}

TEST(ora_roundtrip) {
    Canvas c(64, 48);
    c.addLayer("background & <one>");
    c.layer(0).opacity = 0.5f;
    c.addLayer("second");
    c.layer(1).blend = BlendMode::Multiply;
    c.layer(1).visible = false;
    BrushParams p;
    paintDab(c.layer(0).surface,
             dabFromPressure(p, 10, 10, 1.0f, 255, 0, 0), p);
    paintDab(c.layer(1).surface,
             dabFromPressure(p, 20, 20, 1.0f, 0, 255, 0), p);

    const std::string path = testh::tmpPath("openclip_test.ora");
    std::string err;
    if (!saveOra(c, path, err)) {
        std::printf("  save failed: %s\n", err.c_str());
        std::fflush(stdout);
        CHECK(false);  // no tmp dir / unwritable: bail out, no crash
        return;
    }

    Canvas d;
    if (!loadOra(d, path, err)) {
        std::printf("  load failed: %s\n", err.c_str());
        std::fflush(stdout);
        CHECK(false);
        std::remove(path.c_str());
        return;
    }
    CHECK_EQ(d.width(), 64);
    CHECK_EQ(d.height(), 48);
    CHECK_EQ(d.layerCount(), (std::size_t)2);
    CHECK_EQ(d.layer(0).name, std::string("background & <one>"));
    CHECK(d.layer(0).opacity > 0.49f && d.layer(0).opacity < 0.51f);
    CHECK_EQ(d.layer(1).blend, BlendMode::Multiply);
    CHECK(!d.layer(1).visible);
    // Painted pixels survive (premult round-trip allows +-1).
    auto a = c.layer(0).surface.get(10, 10);
    auto b = d.layer(0).surface.get(10, 10);
    CHECK(pxEq(a, b));
    auto a2 = c.layer(1).surface.get(20, 20);
    auto b2 = d.layer(1).surface.get(20, 20);
    CHECK(pxEq(a2, b2));
    // Untouched pixel stays transparent.
    CHECK_EQ(d.layer(0).surface.get(60, 40).a, 0);
    std::remove(path.c_str());
}

TEST(png_export_writes_file) {    Canvas c(16, 16);
    c.addLayer("l");
    BrushParams p;
    paintDab(c.layer(0).surface,
             dabFromPressure(p, 8, 8, 1.0f, 255, 255, 255), p);
    const std::string path = testh::tmpPath("openclip_test.png");
    std::string err;
    if (!exportPng(c, path, err)) {
        std::printf("  export failed: %s\n", err.c_str());
        std::fflush(stdout);
        CHECK(false);
        return;
    }
    FILE* f = std::fopen(path.c_str(), "rb");
    CHECK(f != nullptr);
    if (f) {
        unsigned char sig[8];
        CHECK(std::fread(sig, 1, 8, f) == 8);
        // PNG magic.
        CHECK(sig[0] == 137 && sig[1] == 'P' && sig[2] == 'N' &&
              sig[3] == 'G');
        std::fclose(f);
    }
    std::remove(path.c_str());
}

TEST(ora_save_unwritable_path_fails_cleanly) {
    // Regression: saveOra must return false (not crash callers) when the
    // destination directory does not exist; the harness must keep running.
    Canvas c(8, 8);
    c.addLayer("l");
    std::string err;
    CHECK(!saveOra(c, testh::tmpPath("openclip_no_such_dir_xyz/test.ora"), err));
    CHECK(!err.empty());
    // Suite is still alive after the failure path.
    CHECK_EQ(c.layerCount(), (std::size_t)1);
}

// A2: the eight new blend modes survive an ORA round trip through
// composite-op (canonical svg: kebab-case names).
TEST(ora_a2_blend_modes_roundtrip) {
    Canvas c(16, 16);
    const BlendMode modes[] = {
        BlendMode::ColorBurn,  BlendMode::ColorDodge, BlendMode::AddGlow,
        BlendMode::Max,        BlendMode::DarkerColor, BlendMode::LighterColor,
        BlendMode::Hue,        BlendMode::Saturation};
    for (auto m : modes) {
        const std::size_t li = c.addLayer(blendToName(m));
        c.layer(li).blend = m;
    }
    const std::string path = testh::tmpPath("openclip_a2_blends.ora");
    std::string err;
    CHECK(saveOra(c, path, err));
    Canvas d;
    CHECK(loadOra(d, path, err));
    CHECK_EQ(d.layerCount(), (std::size_t)8);
    for (std::size_t i = 0; i < d.layerCount(); ++i) {
        CHECK_EQ(d.layer(i).blend, modes[i]);
        CHECK_EQ(d.layer(i).name, std::string(blendToName(modes[i])));
    }
    std::remove(path.c_str());
}

// A2: isolation survives the round trip (auto = pass-through, isolated
// = buffered group).
TEST(ora_a2_isolation_roundtrip) {
    Canvas c(16, 16);
    const std::size_t pass = c.addGroup("pass");
    CHECK(c.layer(pass).passThrough);
    const std::size_t iso = c.addGroup("iso");
    c.layer(iso).passThrough = false;
    c.layer(iso).children.push_back(openclip::Layer());
    c.layer(iso).children.back().name = "kid";
    c.layer(iso).children.back().surface.resize(16, 16);
    const std::string path = testh::tmpPath("openclip_a2_isolation.ora");
    std::string err;
    CHECK(saveOra(c, path, err));
    Canvas d;
    CHECK(loadOra(d, path, err));
    CHECK_EQ(d.layerCount(), (std::size_t)2);
    CHECK(d.layer(0).passThrough);
    CHECK(!d.layer(1).passThrough);
    CHECK_EQ(d.layer(1).children.size(), (std::size_t)1);
    std::remove(path.c_str());
}

// A2: an unknown composite-op is degraded to Normal for compositing
// but preserved verbatim in the meta fiche, so a re-save never loses
// it (no corruption for readers that do not know the mode).
TEST(ora_a2_unknown_blend_preserved) {
    Canvas c(16, 16);
    const std::size_t li = c.addLayer("odd");
    c.layer(li).meta["x-openclip-blend"] = "svg:third-party-mode";
    const std::string path = testh::tmpPath("openclip_a2_unknown.ora");
    std::string err;
    CHECK(saveOra(c, path, err));
    Canvas d;
    CHECK(loadOra(d, path, err));
    CHECK_EQ(d.layer(0).blend, BlendMode::Normal);
    CHECK_EQ(d.layer(0).meta.count("x-openclip-blend"), (std::size_t)1);
    CHECK_EQ(d.layer(0).meta["x-openclip-blend"],
             std::string("svg:third-party-mode"));
    // Second generation: still written back (lossless round trip).
    const std::string path2 = testh::tmpPath("openclip_a2_unknown2.ora");
    CHECK(saveOra(d, path2, err));
    Canvas e;
    CHECK(loadOra(e, path2, err));
    CHECK_EQ(e.layer(0).meta["x-openclip-blend"],
             std::string("svg:third-party-mode"));
    std::remove(path.c_str());
    std::remove(path2.c_str());
}
