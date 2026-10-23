// SPDX-License-Identifier: GPL-3.0-or-later
// T5 engine tests: fill layers (§2), lock flags + export exclusion
// (§1), alpha-lock stamps (§1). Clean-room, from scratch.
#include "test.h"

#include <cstdio>
#include <string>

#include "openclip/brush.h"
#include "openclip/canvas.h"
#include "openclip/fill.h"
#include "openclip/fill_layer.h"
#include "openclip/gradient.h"
#include "openclip/shapes.h"
#include "openclip/ora.h"
#include "openclip/psd.h"

using openclip::BrushParams;
using openclip::Canvas;
using openclip::dabFromPressure;
using openclip::FillSpec;
using openclip::Layer;
using openclip::parseFillSpec;
using openclip::Pixel;
using openclip::renderFill;
using openclip::Surface;

static void paintRed(Layer& l, int w, int h) {
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x)
            l.surface.set(x, y, Pixel{255, 0, 0, 255});
}

// §2: solid fill renders an opaque flat; the kept surface is ignored.
TEST(fill_solid_ignores_surface) {
    Canvas c(8, 8);
    const std::size_t li = c.addLayer("fill");
    paintRed(c.layer(li), 8, 8);
    c.layer(li).fillKind = "solid";
    c.layer(li).fillColor = "#00FF00";
    Surface out;
    c.composite(out);
    CHECK_EQ(out.get(0, 0).r, 0);
    CHECK_EQ(out.get(0, 0).g, 255);
    CHECK_EQ(out.get(0, 0).b, 0);
    CHECK_EQ(out.get(7, 7).a, 255);
    // The surface is still red underneath (kept, ignored).
    CHECK_EQ(c.layer(li).surface.get(0, 0).r, 255);
}

// §2: gradient renders a ramp; clearing the fiche restores the surface.
TEST(fill_gradient_ramp_and_clear) {
    Canvas c(16, 4);
    const std::size_t li = c.addLayer("grad");
    paintRed(c.layer(li), 16, 4);
    c.layer(li).fillKind = "gradient";
    c.layer(li).fillParams = "linear|0|0|16|0|||";
    // Unsorted stops (white first): the engine sorts them.
    c.layer(li).fillStops = "#FFFFFF:1,#000000:0";
    Surface out;
    c.composite(out);
    // Ramp ends (pixel centers sit one half-step inside the segment).
    CHECK(out.get(0, 1).r < 20);       // t≈0.03 end: near black
    CHECK(out.get(15, 1).r > 235);     // t≈0.97 end: near white
    const int mid = out.get(8, 1).r;
    CHECK(mid > 90 && mid < 170);  // mid ramp, roughly linear
    // Clear the fiche: the red surface shows again.
    c.layer(li).fillKind.clear();
    c.composite(out);
    CHECK_EQ(out.get(0, 0).r, 255);
    CHECK_EQ(out.get(0, 0).g, 0);
}

// §2: stops < 2 = parse error; radial zero radius = render error.
TEST(fill_bad_spec_is_error) {
    Canvas c(4, 4);
    c.addLayer("bad");
    Layer& l = c.layer(0);
    std::string err;
    FillSpec spec;
    l.fillKind = "gradient";
    l.fillStops = "#FFFFFF:0.5";
    CHECK(!parseFillSpec(l, spec, err));
    l.fillKind = "mystery";
    CHECK(!parseFillSpec(l, spec, err));
    l.fillKind = "gradient";
    l.fillStops = "#FFFFFF:0,#000000:1";
    l.fillParams = "radial|2|2|2|2|||";
    CHECK(parseFillSpec(l, spec, err));
    Surface out;
    CHECK(!renderFill(spec, 4, 4, out, err));
}

// §2: unknown kind paints nothing (transparent), never the surface.
TEST(fill_unknown_kind_paints_nothing) {
    Canvas c(4, 4);
    const std::size_t li = c.addLayer("weird");
    paintRed(c.layer(li), 4, 4);
    c.layer(li).fillKind = "hologram";
    c.layer(li).fillColor = "#00FF00";
    Surface out;
    c.composite(out);
    CHECK_EQ(out.get(0, 0).a, 0);
}

// §2: foil renders a non-empty deterministic field.
TEST(fill_foil_deterministic) {
    Canvas c(16, 16);
    const std::size_t li = c.addLayer("foil");
    c.layer(li).fillKind = "foil";
    c.layer(li).fillParams = "linear|0|0|0|0|200|4|0.5";
    Surface a, b;
    c.composite(a);
    c.composite(b);
    bool any = false;
    for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 16; ++x) {
            const Pixel pa = a.get(x, y), pb = b.get(x, y);
            CHECK_EQ(pa.r, pb.r);
            CHECK_EQ(pa.g, pb.g);
            CHECK_EQ(pa.b, pb.b);
            CHECK_EQ(pa.a, pb.a);
            if (pa.a != 0) any = true;
            // Premultiplied-safe.
            CHECK(pa.r <= pa.a && pa.g <= pa.a && pa.b <= pa.a);
        }
    CHECK(any);
    CHECK_EQ(a.get(0, 0).a, 255);  // opaque fill
}

// §2: opacity/mask/clip flow exactly like normal layers.
TEST(fill_opacity_mask_clip) {
    Canvas c(8, 8);
    const std::size_t li = c.addLayer("fill");
    c.layer(li).fillKind = "solid";
    c.layer(li).fillColor = "#FF0000";
    c.layer(li).opacity = 0.5f;
    Surface out;
    c.composite(out);
    // Opaque red at 50%: lround(255*0.5) = 128.
    CHECK_EQ(out.get(0, 0).r, 128);
    CHECK_EQ(out.get(0, 0).a, 128);
    // Full mask hide removes the fill.
    c.layer(li).opacity = 1.0f;
    c.layer(li).addMask(8, 8);
    for (int y = 0; y < 8; ++y)
        for (int x = 0; x < 8; ++x)
            c.layer(li).mask.set(x, y, Pixel{0, 0, 0, 0});
    c.composite(out);
    CHECK_EQ(out.get(0, 0).a, 0);
}

// §1: reference/draft layers show in the editor composite but vanish
// from the export composite.
TEST(flags_reference_draft_export_exclusion) {
    Canvas c(8, 8);
    const std::size_t base = c.addLayer("base");
    paintRed(c.layer(base), 8, 8);
    const std::size_t ref = c.addLayer("ref");
    c.layer(ref).fillKind = "solid";
    c.layer(ref).fillColor = "#0000FF";
    c.layer(ref).reference = true;
    const std::size_t dr = c.addLayer("draft");
    c.layer(dr).fillKind = "solid";
    c.layer(dr).fillColor = "#00FF00";
    c.layer(dr).draft = true;
    Surface ed, ex;
    c.composite(ed);
    c.compositeExport(ex);
    CHECK_EQ(ed.get(0, 0).g, 255);  // draft green on top in editor
    CHECK_EQ(ex.get(0, 0).r, 255);  // export: only the red base
    CHECK_EQ(ex.get(0, 0).g, 0);
    CHECK_EQ(ex.get(0, 0).b, 0);
    // compositeAnimExport (T3 chain) excludes them too.
    std::vector<openclip::LayerAnimSample> samples(c.layerCount());
    Surface ax;
    c.compositeAnimExport(ax, samples);
    CHECK_EQ(ax.get(0, 0).r, 255);
    CHECK_EQ(ax.get(0, 0).g, 0);
}

// §1: locked/alphaLock/reference/draft + fill fiche survive ORA.
TEST(flags_fill_ora_roundtrip) {
    Canvas c(16, 16);
    const std::size_t li = c.addLayer("fiche");
    paintRed(c.layer(li), 16, 16);
    c.layer(li).locked = true;
    c.layer(li).alphaLock = true;
    c.layer(li).reference = true;
    c.layer(li).draft = true;
    c.layer(li).fillKind = "gradient";
    c.layer(li).fillColor = "#112233";
    c.layer(li).fillStops = "#000000:0,#FFFFFF:1";
    c.layer(li).fillParams = "radial|8|8|16|8|0|4|0.5";
    const std::string path = "/tmp/openclip_test_t5.ora";
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
    CHECK_EQ(d.layerCount(), (std::size_t)1);
    CHECK(d.layer(0).locked);
    CHECK(d.layer(0).alphaLock);
    CHECK(d.layer(0).reference);
    CHECK(d.layer(0).draft);
    CHECK_EQ(d.layer(0).fillKind, std::string("gradient"));
    CHECK_EQ(d.layer(0).fillColor, std::string("#112233"));
    CHECK_EQ(d.layer(0).fillStops, std::string("#000000:0,#FFFFFF:1"));
    CHECK_EQ(d.layer(0).fillParams,
             std::string("radial|8|8|16|8|0|4|0.5"));
    // No leakage into the generic meta map.
    CHECK(d.layer(0).meta.find("x-openclip-locked") ==
          d.layer(0).meta.end());
    CHECK(d.layer(0).meta.find("x-openclip-fill") == d.layer(0).meta.end());
    std::remove(path.c_str());
}

// §1: reference/draft/fill layers count as PSD approximations.
TEST(flags_psd_approx_count) {
    Canvas c(8, 8);
    const std::size_t base = c.addLayer("base");
    paintRed(c.layer(base), 8, 8);
    const std::size_t ref = c.addLayer("ref");
    paintRed(c.layer(ref), 8, 8);
    c.layer(ref).reference = true;
    const std::string path = "/tmp/openclip_test_t5.psd";
    std::string err;
    int approx = -1;
    if (!openclip::savePsd(c, path, err, &approx)) {
        std::printf("  psd save failed: %s\n", err.c_str());
        std::fflush(stdout);
        CHECK(false);
        return;
    }
    CHECK(approx >= 1);  // the reference layer merged + counted
    std::remove(path.c_str());
}

// §1: alphaLock stamps only land on covered pixels; legacy path is
// byte-identical to the defaulted param.
TEST(alphalock_stamp_only_covered) {
    BrushParams p;
    p.radius = 3.0f;
    Surface s(16, 16), t(16, 16);
    s.set(8, 8, Pixel{200, 0, 0, 255});
    t.set(8, 8, Pixel{200, 0, 0, 255});
    const openclip::Dab d = dabFromPressure(p, 8, 8, 1.0f, 0, 0, 255);
    openclip::paintDab(s, d, p, true);
    openclip::paintDab(t, d, p);
    // The covered center pixel takes paint in both paths.
    CHECK_EQ(s.get(8, 8).b, t.get(8, 8).b);
    CHECK(s.get(8, 8).a != 0);
    // Transparent fringe inside the dab disc stays transparent under
    // alphaLock (legacy paints it).
    CHECK_EQ(s.get(8, 10).a, 0);
    // Transparent corner stays transparent under alphaLock.
    Surface u(16, 16);
    const openclip::Dab e = dabFromPressure(p, 2, 2, 1.0f, 0, 0, 255);
    openclip::paintDab(u, e, p, true);
    CHECK_EQ(u.get(2, 2).a, 0);
    Surface v(16, 16);
    openclip::paintDab(v, e, p);
    CHECK(v.get(2, 2).a != 0);  // legacy paints
}

// §1: flood/gradient/shape avec alphaLock — transparent intact,
// couvert peint ; legacy (défaut) byte-identique.
TEST(alphalock_kernels_only_covered) {
    std::string err;
    // Flood: covered seed paints covered pixels only.
    {
        Surface s(16, 16);
        s.set(4, 4, Pixel{200, 0, 0, 255});
        openclip::FillParams p;
        p.antialias = false;
        CHECK(openclip::floodFill(s, 4, 4, 0, 0, 255, p, nullptr, 0, err,
                                  true));
        CHECK_EQ(s.get(4, 4).b, 255);  // covered: painted
        CHECK_EQ(s.get(10, 10).a, 0);  // transparent: intact
    }
    // Flood from a transparent seed paints nothing at all.
    {
        Surface s(16, 16);
        s.set(4, 4, Pixel{200, 0, 0, 255});
        openclip::FillParams p;
        p.antialias = false;
        CHECK(openclip::floodFill(s, 10, 10, 0, 0, 255, p, nullptr, 0,
                                  err, true));
        CHECK_EQ(s.get(10, 10).a, 0);
        CHECK_EQ(s.get(4, 4).r, 200);
        // Legacy path paints the transparent region (control).
        Surface t(16, 16);
        t.set(4, 4, Pixel{200, 0, 0, 255});
        CHECK(openclip::floodFill(t, 10, 10, 0, 0, 255, p, nullptr, 0,
                                  err));
        CHECK(t.get(10, 10).a != 0);
    }
    // Gradient: transparent pixels skipped.
    {
        Surface s(33, 4);
        s.set(0, 2, Pixel{100, 100, 100, 255});
        openclip::GradientSpec spec;
        spec.kind = "linear";
        spec.x0 = 0;
        spec.y0 = 0;
        spec.x1 = 32;
        spec.y1 = 0;
        spec.stops = {{0, 0, 0, 0.0f}, {255, 255, 255, 1.0f}};
        spec.dither = false;
        CHECK(openclip::paintGradient(s, spec, nullptr, 0, err, true));
        CHECK(s.get(0, 2).a != 0);   // covered: painted
        CHECK_EQ(s.get(20, 2).a, 0);  // transparent: intact
    }
    // Shape: transparent pixels skipped.
    {
        Surface s(32, 32);
        s.set(8, 8, Pixel{100, 100, 100, 255});
        openclip::ShapeSpec spec;
        spec.kind = "rect";
        spec.x0 = 0;
        spec.y0 = 0;
        spec.x1 = 16;
        spec.y1 = 16;
        spec.fillOn = true;
        spec.fillR = 200;
        spec.fillG = 0;
        spec.fillB = 0;
        spec.strokeOn = false;
        CHECK(openclip::stampShape(s, spec, nullptr, 0, err, true));
        CHECK(s.get(8, 8).r != 100);  // covered: painted
        CHECK_EQ(s.get(2, 2).a, 0);   // transparent: intact
    }
}
