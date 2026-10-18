// SPDX-License-Identifier: GPL-3.0-or-later
// M10 procedural material tones (ARCHITECTURE.md "M10 additions"):
// deterministic 8px-periodic ink, premultiplied-safe, alpha untouched.
#include "test.h"

#include <string>
#include <vector>

#include "openclip/filters.h"
#include "openclip/surface.h"

using openclip::Pixel;
using openclip::Surface;
using openclip::applyTone;

namespace {

Surface solid(int w, int h, std::uint8_t r, std::uint8_t g,
              std::uint8_t b, std::uint8_t a) {
    Surface s;
    s.resize(w, h);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x)
            s.set(x, y, Pixel{r, g, b, a});
    return s;
}

}  // namespace

TEST(tone_hatch_repaints_with_ink) {
    Surface s = solid(32, 32, 255, 255, 255, 255);
    CHECK(applyTone(s, "hatch"));
    // The tone FILLS the layer: ink (96) or empty (0), never white left.
    bool anyInk = false, anyEmpty = false;
    for (int y = 0; y < 32; ++y)
        for (int x = 0; x < 32; ++x) {
            const Pixel p = s.get(x, y);
            if (p.r == 96 && p.g == 96 && p.b == 96) anyInk = true;
            if (p.r == 0 && p.g == 0 && p.b == 0) anyEmpty = true;
            CHECK_EQ(p.a, 255);  // alpha never moves
        }
    CHECK(anyInk);
    CHECK(anyEmpty);
}

TEST(tone_all_names_work) {
    const std::vector<std::string> names = {
        "hatch", "crosshatch", "dots", "grid", "checker", "stripes"};
    for (const auto& n : names) {
        Surface s = solid(16, 16, 255, 255, 255, 255);
        CHECK(applyTone(s, n));
        bool changed = false;
        for (int y = 0; y < 16 && !changed; ++y)
            for (int x = 0; x < 16; ++x)
                if (s.get(x, y).r != 255) changed = true;
        CHECK(changed);
    }
}

TEST(tone_unknown_name_false_and_untouched) {
    Surface s = solid(16, 16, 10, 20, 30, 40);
    const std::vector<std::uint8_t> before = s.bytes();
    CHECK(!applyTone(s, "bogus"));
    CHECK(before == s.bytes());
}

TEST(tone_rgb_clamped_to_alpha) {
    Surface s = solid(16, 16, 200, 200, 200, 64);
    CHECK(applyTone(s, "stripes"));
    for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 16; ++x) {
            const Pixel p = s.get(x, y);
            CHECK(p.r <= p.a);
            CHECK(p.g <= p.a);
            CHECK(p.b <= p.a);
            CHECK_EQ(p.a, 64);  // alpha untouched
        }
}
