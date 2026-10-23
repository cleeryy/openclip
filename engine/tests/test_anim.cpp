// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip T3 — keyframe sampling, easing, camera, composite-time
// application (ARCHITECTURE.md "T3 additions" §1+§2).
#include "test.h"

#include <cmath>

#include "openclip/anim.h"
#include "openclip/canvas.h"

using openclip::AnimKey;
using openclip::CameraKey;
using openclip::CameraSample;
using openclip::LayerAnimSample;
using openclip::LayerKeys;

// §1: midpoint of two keys = half values (±eps).
TEST(animSampleMidpoint) {
    std::vector<AnimKey> t = {
        {0, {0, 0, 1, 0, 1}, "linear"},
        {10, {20, 10, 2, 90, 0}, "linear"},
    };
    LayerKeys k;
    CHECK(openclip::sampleAnimTrack(t, 5.0, k));
    CHECK(std::fabs(k.dx - 10.0f) < 1e-3);
    CHECK(std::fabs(k.dy - 5.0f) < 1e-3);
    CHECK(std::fabs(k.scale - 1.5f) < 1e-3);
    CHECK(std::fabs(k.angle - 45.0f) < 1e-3);
    CHECK(std::fabs(k.opacity - 0.5f) < 1e-3);
}

// §1: step = left key holds for the whole segment.
TEST(animEaseStepHolds) {
    std::vector<AnimKey> t = {
        {0, {4, 4, 1, 0, 1}, "step"},
        {10, {20, 20, 3, 90, 0}, "linear"},
    };
    LayerKeys k;
    CHECK(openclip::sampleAnimTrack(t, 9.99, k));
    CHECK_EQ(k.dx, 4.0f);
    CHECK_EQ(k.scale, 1.0f);
    CHECK(openclip::sampleAnimTrack(t, 10.0, k));
    CHECK_EQ(k.dx, 20.0f);
}

// §1: single key = hold everywhere (no extrapolation).
TEST(animSingleKeyHolds) {
    std::vector<AnimKey> t = {{3, {7, -2, 2, 30, 0.5f}, "linear"}};
    LayerKeys a, b;
    CHECK(openclip::sampleAnimTrack(t, 0.0, a));
    CHECK(openclip::sampleAnimTrack(t, 99.0, b));
    CHECK_EQ(a.dx, 7.0f);
    CHECK_EQ(b.dx, 7.0f);
    CHECK_EQ(a.scale, 2.0f);
}

// §1: no keys = no sample, composite stays byte-identical.
TEST(animEmptyLayerIdentical) {
    openclip::Canvas c(32, 32);
    c.addLayer("base");
    openclip::Layer& l = c.layer(0);
    for (int y = 0; y < 32; ++y)
        for (int x = 0; x < 32; ++x)
            l.surface.set(x, y, openclip::Pixel{10, 20, 30, 255});
    openclip::Surface a, b;
    c.composite(a);
    std::vector<AnimKey> empty;
    LayerKeys k;
    CHECK(!openclip::sampleAnimTrack(empty, 4.0, k));
    c.compositeAnim(b, {});
    CHECK_EQ(a.bytes().size(), b.bytes().size());
    CHECK(a.bytes() == b.bytes());
    // Identity samples also paint exactly like composite().
    std::vector<LayerAnimSample> id(1);
    id[0].on = true;
    openclip::Surface d;
    c.compositeAnim(d, id);
    CHECK(a.bytes() == d.bytes());
}

// §1: unparsable json = false, track untouched.
TEST(animBadJsonNoOp) {
    std::vector<AnimKey> t = {{0, {1, 1, 1, 0, 1}, "linear"}};
    std::vector<AnimKey> keep = t;
    CHECK(!openclip::parseAnimTrack("not json at all", t));
    CHECK(!openclip::parseAnimTrack("{\"nope\":[]}", t));
    CHECK(t.size() == keep.size() && t[0].cel == keep[0].cel);
    // Round trip: serialize -> parse keeps values + clamps.
    std::vector<AnimKey> src = {
        {2, {5, -5, 99, 10, 7}, "smooth"},
        {0, {0, 0, 0, 0, -3}, "bogus-ease"},
    };
    CHECK(openclip::parseAnimTrack(openclip::animTrackJson(src), t));
    CHECK_EQ(t.size(), 2u);
    CHECK_EQ(t[0].cel, 0);  // sorted by cel
    CHECK_EQ(t[1].keys.scale, 8.0f);  // clamped to 0.1..8
    CHECK_EQ(t[1].keys.opacity, 1.0f);
    CHECK_EQ(t[0].keys.scale, 0.1f);
    CHECK_EQ(t[0].keys.opacity, 0.0f);
    CHECK(t[0].ease == "linear");  // unknown ease -> linear
}

// §1: opacity 0.5 ~= half alpha on the composite.
TEST(animOpacityMultiplies) {
    openclip::Canvas c(16, 16);
    c.addLayer("base");
    openclip::Layer& l = c.layer(0);
    for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 16; ++x)
            l.surface.set(x, y, openclip::Pixel{200, 100, 50, 200});
    openclip::Surface full, half;
    c.composite(full);
    std::vector<LayerAnimSample> s(1);
    s[0].on = true;
    s[0].opacity = 0.5f;
    c.compositeAnim(half, s);
    CHECK_EQ(full.get(8, 8).a, 200);
    const int got = half.get(8, 8).a;
    CHECK(got >= 98 && got <= 102);
    CHECK(half.get(8, 8).r <= half.get(8, 8).a);  // M3 contract
}

// §2: zoom 2 ~= central quarter enlarged (±1px tolerance).
TEST(cameraZoomCrops) {
    openclip::Surface src(64, 64);
    src.clear();
    for (int y = 24; y < 40; ++y)
        for (int x = 24; x < 40; ++x)
            src.set(x, y, openclip::Pixel{255, 0, 0, 255});
    CameraSample cam;
    cam.on = true;
    cam.x = 32;
    cam.y = 32;
    cam.zoom = 2.0f;
    openclip::Surface dst;
    openclip::applyCameraCrop(src, cam, dst);
    CHECK_EQ(dst.width(), 64);
    // Watched rect = central 32x32 stretched over 64x64: the red
    // block (16x16 in watched coords) lands ~32x32 around center.
    CHECK(dst.get(32, 32).r == 255 && dst.get(32, 32).a == 255);
    CHECK(dst.get(0, 0).a == 0);  // hors-champ = transparent
    int red = 0;
    for (int y = 0; y < 64; ++y)
        for (int x = 0; x < 64; ++x)
            if (dst.get(x, y).r == 255 && dst.get(x, y).a == 255) ++red;
    CHECK(red >= 30 * 30 && red <= 34 * 34);
}

// §2: pan shifts the watched center.
TEST(cameraPanShifts) {
    openclip::Surface src(64, 64);
    src.clear();
    src.set(10, 10, openclip::Pixel{0, 255, 0, 255});
    CameraSample cam;
    cam.on = true;
    cam.x = 32;
    cam.y = 32;
    cam.zoom = 1.0f;
    openclip::Surface a, b;
    openclip::applyCameraCrop(src, cam, a);
    cam.x = 10;
    cam.y = 10;
    openclip::applyCameraCrop(src, cam, b);
    CHECK(a.get(10, 10).g == 255);
    CHECK(b.get(32, 32).g == 255);  // watched dot lands at center
}

// §2: disabled camera = caller keeps manual view (on=false).
TEST(cameraDisabledIdentical) {
    std::vector<CameraKey> empty;
    CameraSample s = openclip::sampleCameraTrack(empty, 3.0);
    CHECK(!s.on);
    CHECK_EQ(s.zoom, 1.0f);
}

// §2: single key = hold everywhere.
TEST(cameraSingleKeyHolds) {
    std::vector<CameraKey> t = {{2, 40, 30, 2, 15, "linear"}};
    CameraSample a = openclip::sampleCameraTrack(t, 0.0);
    CameraSample b = openclip::sampleCameraTrack(t, 50.0);
    CHECK(a.on && b.on);
    CHECK_EQ(a.x, 40.0f);
    CHECK_EQ(b.zoom, 2.0f);
    CHECK_EQ(a.angle, 15.0f);
}

// §2: bad json = false + round trip clamps zoom.
TEST(cameraBadJsonNoOp) {
    std::vector<CameraKey> t;
    CHECK(!openclip::parseCameraTrack("garbage", t));
    CHECK(t.empty());
    CHECK(!openclip::parseCameraTrack("{\"keys\":[{\"cel\":-5}]}", t));
    std::vector<CameraKey> src = {{0, 8, 8, 99, 0, "ease-in"}};
    CHECK(openclip::parseCameraTrack(openclip::cameraTrackJson(src), t));
    CHECK_EQ(t.size(), 1u);
    CHECK_EQ(t[0].zoom, 8.0f);
    CHECK(t[0].ease == "ease-in");
}
