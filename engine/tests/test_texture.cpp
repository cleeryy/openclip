// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip B1 engine — layer texture effect (ARCHITECTURE.md "B1
// additions" §5): deterministic height field, alpha-preserving
// modulation, fiche parse and ORA round trip.
#include "test.h"

#include <string>
#include <vector>

#include "openclip/canvas.h"
#include "openclip/ora.h"
#include "openclip/surface.h"
#include "openclip/texture.h"

using openclip::Canvas;
using openclip::Layer;
using openclip::Pixel;
using openclip::Surface;
using openclip::TextureSpec;

namespace {

Surface textured() {
    Surface s(48, 32);
    for (int y = 0; y < 32; ++y)
        for (int x = 0; x < 48; ++x) s.set(x, y, Pixel{200, 120, 60, 255});
    return s;
}

bool premultSafe(const Surface& s) {
    for (int y = 0; y < s.height(); ++y)
        for (int x = 0; x < s.width(); ++x) {
            const Pixel p = s.get(x, y);
            if (p.r > p.a || p.g > p.a || p.b > p.a) return false;
        }
    return true;
}

}  // namespace

TEST(texture_spec_parse_defaults_and_validation) {
    TextureSpec spec;
    std::string err;
    CHECK(openclip::parseTextureSpec("{\"kind\":\"canvas\"}", spec, err));
    CHECK(spec.kind == "canvas");
    CHECK_EQ(spec.scale, 1.0f);
    CHECK_EQ(spec.strength, 0.35f);
    CHECK(openclip::parseTextureSpec(
        "{\"kind\":\"grain\",\"scale\":4,\"strength\":0.8,\"seed\":42}", spec,
        err));
    CHECK_EQ(spec.scale, 4.0f);
    CHECK_EQ(spec.strength, 0.8f);
    CHECK_EQ(spec.seed, (std::uint32_t)42);
    // Clamps: scale 0.25..8, strength 0..1.
    CHECK(openclip::parseTextureSpec(
        "{\"kind\":\"paper\",\"scale\":99,\"strength\":5}", spec, err));
    CHECK_EQ(spec.scale, 8.0f);
    CHECK_EQ(spec.strength, 1.0f);
    // Unknown kind / malformed JSON = false + error.
    CHECK(openclip::parseTextureSpec("{\"kind\":\"marble\"}", spec, err) ==
          false);
    CHECK(!err.empty());
    CHECK(openclip::parseTextureSpec("not json", spec, err) == false);
}

TEST(texture_height_is_deterministic) {
    TextureSpec spec;
    spec.kind = "paper";
    spec.scale = 2.0f;
    spec.strength = 0.5f;
    spec.seed = 11;
    std::vector<std::uint8_t> a, b;
    std::string err;
    CHECK(openclip::renderTextureHeight(spec, 40, 24, a, err));
    CHECK(openclip::renderTextureHeight(spec, 40, 24, b, err));
    CHECK_EQ(a.size(), (std::size_t)(40 * 24));
    CHECK(a == b);
    // Not a flat field: the texture actually varies.
    int lo = 255, hi = 0;
    for (auto v : a) {
        if (v < lo) lo = v;
        if (v > hi) hi = v;
    }
    CHECK(hi - lo > 10);
    // A different seed gives a different field.
    spec.seed = 12;
    std::vector<std::uint8_t> c;
    CHECK(openclip::renderTextureHeight(spec, 40, 24, c, err));
    CHECK(!(a == c));
    // Every kind renders.
    for (const char* kind : {"paper", "canvas", "grain"}) {
        spec.kind = kind;
        std::vector<std::uint8_t> h;
        CHECK(openclip::renderTextureHeight(spec, 20, 20, h, err));
    }
}

TEST(texture_apply_preserves_alpha_and_varies_colour) {
    Surface s = textured();
    TextureSpec spec;
    spec.kind = "canvas";
    spec.scale = 1.5f;
    spec.strength = 0.6f;
    spec.seed = 3;
    openclip::applyTexture(s, spec);
    CHECK(premultSafe(s));
    // Alpha untouched everywhere, fully transparent stays byte-identical.
    bool alphaKept = true;
    bool varied = false;
    const Pixel first = s.get(0, 0);
    for (int y = 0; y < s.height(); ++y)
        for (int x = 0; x < s.width(); ++x) {
            const Pixel p = s.get(x, y);
            if (p.a != 255) alphaKept = false;
            if (p.r != first.r || p.g != first.g || p.b != first.b)
                varied = true;
        }
    CHECK(alphaKept);
    CHECK(varied);
    Surface empty(16, 16);
    openclip::applyTexture(empty, spec);
    CHECK_EQ(empty.get(8, 8).a, 0);
}

TEST(texture_strength_zero_is_identity) {
    Surface s = textured();
    const Surface before = s;
    TextureSpec spec;
    spec.kind = "paper";
    spec.strength = 0.0f;
    openclip::applyTexture(s, spec);
    bool same = true;
    for (int y = 0; y < s.height(); ++y)
        for (int x = 0; x < s.width(); ++x) {
            const Pixel a = before.get(x, y);
            const Pixel b = s.get(x, y);
            if (a.r != b.r || a.g != b.g || a.b != b.b || a.a != b.a)
                same = false;
        }
    CHECK(same);
}

TEST(texture_composite_applies_only_to_textured_layer) {
    Canvas c(48, 32);
    const std::size_t bg = c.addLayer("bg");
    const std::size_t top = c.addLayer("top");
    for (int y = 0; y < 32; ++y)
        for (int x = 0; x < 48; ++x) {
            c.layer(bg).surface.set(x, y, Pixel{255, 255, 255, 255});
            c.layer(top).surface.set(x, y, Pixel{120, 120, 120, 255});
        }
    Surface plain;
    c.composite(plain);
    c.layer(top).textureKind = "canvas";
    c.layer(top).textureParams = "1.5|0.6|3";
    Surface texturedComp;
    c.composite(texturedComp);
    CHECK(premultSafe(texturedComp));
    bool changed = false;
    for (int y = 0; y < 32; ++y)
        for (int x = 0; x < 48; ++x) {
            if (plain.get(x, y).r != texturedComp.get(x, y).r) changed = true;
        }
    CHECK(changed);
    // Clearing the fiche restores the exact previous composite.
    c.layer(top).textureKind.clear();
    c.layer(top).textureParams.clear();
    Surface restored;
    c.composite(restored);
    bool same = true;
    for (int y = 0; y < 32; ++y)
        for (int x = 0; x < 48; ++x) {
            const Pixel a = plain.get(x, y);
            const Pixel b = restored.get(x, y);
            if (a.r != b.r || a.g != b.g || a.b != b.b || a.a != b.a)
                same = false;
        }
    CHECK(same);
}

TEST(texture_fiche_parse_from_layer) {
    Layer l;
    std::string err;
    l.textureKind = "paper";
    l.textureParams = "2.5|0.4|17";
    TextureSpec spec;
    CHECK(openclip::parseTextureFiche(l, spec, err));
    CHECK(spec.kind == "paper");
    CHECK_EQ(spec.scale, 2.5f);
    CHECK_EQ(spec.strength, 0.4f);
    CHECK_EQ(spec.seed, (std::uint32_t)17);
    // Missing params fall back to defaults (M3 spirit).
    l.textureParams.clear();
    CHECK(openclip::parseTextureFiche(l, spec, err));
    CHECK_EQ(spec.scale, 1.0f);
    CHECK_EQ(spec.strength, 0.35f);
    // Unknown kind = refused (layer keeps pixels).
    l.textureKind = "marble";
    CHECK(openclip::parseTextureFiche(l, spec, err) == false);
    l.textureKind.clear();
    CHECK(openclip::parseTextureFiche(l, spec, err) == false);
}

TEST(texture_ora_round_trip) {
    Canvas c(32, 32);
    const std::size_t idx = c.addLayer("tex");
    for (int y = 0; y < 32; ++y)
        for (int x = 0; x < 32; ++x)
            c.layer(idx).surface.set(x, y, Pixel{180, 160, 140, 255});
    c.layer(idx).textureKind = "grain";
    c.layer(idx).textureParams = "3|0.55|9";
    const std::string path = testh::tmpPath("b1_texture.ora");
    std::string err;
    CHECK(openclip::saveOra(c, path, err));
    Canvas loaded;
    CHECK(openclip::loadOra(loaded, path, err));
    CHECK_EQ(loaded.layerCount(), (std::size_t)1);
    const Layer& l = loaded.layer(0);
    CHECK(l.hasTexture());
    CHECK(l.textureKind == "grain");
    CHECK(l.textureParams == "3|0.55|9");
    // Composite of the loaded document matches the source composite.
    Surface a, b;
    c.composite(a);
    loaded.composite(b);
    bool same = true;
    for (int y = 0; y < 32; ++y)
        for (int x = 0; x < 32; ++x) {
            const Pixel p = a.get(x, y);
            const Pixel q = b.get(x, y);
            if (p.r != q.r || p.g != q.g || p.b != q.b || p.a != q.a)
                same = false;
        }
    CHECK(same);
}
