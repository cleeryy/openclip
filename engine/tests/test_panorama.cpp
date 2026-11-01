// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip issue #16 — panorama stitching tests (one per header).
#include "test.h"

#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "openclip/panorama.h"

using openclip::PanoImage;
using openclip::PanoParams;
using openclip::PanoResult;

namespace {

// Deterministic synthetic texture: a 2D colour gradient plus a fine
// diagonal pattern and one unique vertical stripe. The stripe makes the
// horizontal shift unambiguous (a pure linear ramp is translation
// invariant, so it could not honestly test alignment).
PanoImage makeBase(int w, int h) {
    PanoImage im;
    im.width = w;
    im.height = h;
    im.rgba.assign(static_cast<std::size_t>(w) * h * 4, 255);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const std::size_t i =
                (static_cast<std::size_t>(y) * w + x) * 4;
            int r = 16 + (x * 200) / (w - 1);
            int g = 20 + (y * 180) / (h - 1);
            int b = 40 + ((x * 13 + y * 7) % 60);
            if (x >= w * 45 / 100 && x < w * 52 / 100) {
                r = 250;
                g = 200;
                b = 80;
            }
            im.rgba[i] = static_cast<std::uint8_t>(r);
            im.rgba[i + 1] = static_cast<std::uint8_t>(g);
            im.rgba[i + 2] = static_cast<std::uint8_t>(b);
        }
    }
    return im;
}

PanoImage crop(const PanoImage& src, int x0, int y0, int w, int h) {
    PanoImage out;
    out.width = w;
    out.height = h;
    out.rgba.assign(static_cast<std::size_t>(w) * h * 4, 0);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const std::size_t si =
                (static_cast<std::size_t>(y0 + y) * src.width + x0 + x) * 4;
            const std::size_t di =
                (static_cast<std::size_t>(y) * w + x) * 4;
            for (int k = 0; k < 4; ++k) out.rgba[di + k] = src.rgba[si + k];
        }
    }
    return out;
}

int at(const PanoImage& im, int x, int y, int c) {
    return im.rgba[(static_cast<std::size_t>(y) * im.width + x) * 4 + c];
}

int at(const PanoResult& im, int x, int y, int c) {
    return im.rgba[(static_cast<std::size_t>(y) * im.width + x) * 4 + c];
}

PanoParams smallParams() {
    PanoParams p;
    p.feather = 24;
    p.maxPixels = 4 * 1024 * 1024;
    return p;
}

bool contains(const std::string& hay, const char* needle) {
    return hay.find(needle) != std::string::npos;
}

}  // namespace

TEST(panorama_align_known_translation) {
    const PanoImage base = makeBase(340, 240);
    const PanoImage a = crop(base, 0, 0, 220, 200);
    const PanoImage b = crop(base, 120, 20, 220, 200);
    int dx = 0, dy = 0;
    float score = 0.0f;
    std::string err;
    CHECK(openclip::alignPanoramaPair(a, b, smallParams(), dx, dy, score, err));
    CHECK_EQ(err, std::string());
    CHECK(std::abs(dx - 120) <= 1);
    CHECK(std::abs(dy - 20) <= 1);
    CHECK(score > 0.8f);
}

TEST(panorama_align_refuses_flat_and_weak) {
    PanoImage flat;
    flat.width = 64;
    flat.height = 64;
    flat.rgba.assign(64 * 64 * 4, 200);
    for (std::size_t i = 3; i < flat.rgba.size(); i += 4) flat.rgba[i] = 255;
    PanoImage grad = flat;
    for (int y = 0; y < 64; ++y)
        for (int x = 0; x < 64; ++x)
            grad.rgba[(static_cast<std::size_t>(y) * 64 + x) * 4] =
                static_cast<std::uint8_t>(x * 4);
    int dx = 0, dy = 0;
    float s = 0.0f;
    std::string err;
    CHECK(!openclip::alignPanoramaPair(flat, grad, smallParams(), dx, dy, s,
                                       err));
    CHECK(contains(err, "featureless"));
    // Two unrelated high-frequency patterns must not pass minScore.
    PanoImage checker = flat, stripes = flat;
    for (int y = 0; y < 64; ++y)
        for (int x = 0; x < 64; ++x) {
            const std::size_t i = (static_cast<std::size_t>(y) * 64 + x) * 4;
            const int c = ((x / 4 + y / 4) % 2) ? 250 : 5;
            checker.rgba[i] = checker.rgba[i + 1] = checker.rgba[i + 2] =
                static_cast<std::uint8_t>(c);
            const int d = (x % 5 < 2) ? 250 : 5;
            stripes.rgba[i] = stripes.rgba[i + 1] = stripes.rgba[i + 2] =
                static_cast<std::uint8_t>(d);
        }
    PanoParams strict = smallParams();
    strict.minScore = 0.95f;  // one of these can score moderately: refuse
    // Only assert the contract: when the score is below minScore it
    // refuses and says so; when it passes, offsets are returned. The
    // deterministic hostile case is the checker/stripe pair at 0.95.
    const bool ok = openclip::alignPanoramaPair(checker, stripes, strict, dx,
                                                dy, s, err);
    CHECK(!ok);
    CHECK(contains(err, "weak") || contains(err, "insufficient"));
}

TEST(panorama_stitch_recovers_placements) {
    const PanoImage base = makeBase(340, 240);
    std::vector<PanoImage> imgs{
        crop(base, 0, 0, 220, 200),
        crop(base, 120, 20, 220, 200),
    };
    PanoParams p = smallParams();
    p.feather = 32;
    const PanoResult r = openclip::stitchPanorama(imgs, p);
    CHECK(r.ok());
    CHECK_EQ(r.width, 340);
    CHECK_EQ(r.height, 220);
    CHECK_EQ(r.offsets.size(), static_cast<std::size_t>(2));
    CHECK(std::abs(r.offsets[1].dx - 120) <= 1);
    CHECK(std::abs(r.offsets[1].dy - 20) <= 1);
    CHECK_EQ(r.offsets[1].x, r.offsets[1].dx);
    CHECK_EQ(r.offsets[1].y, r.offsets[1].dy);
    CHECK(r.meanScore > 0.8f);
}

TEST(panorama_stitch_seam_is_invisible_on_gradient) {
    const PanoImage base = makeBase(340, 180);
    std::vector<PanoImage> imgs{
        crop(base, 0, 0, 220, 180),
        crop(base, 120, 0, 220, 180),
    };
    PanoParams p = smallParams();
    p.feather = 48;
    const PanoResult r = openclip::stitchPanorama(imgs, p);
    CHECK(r.ok());
    CHECK_EQ(r.width, 340);
    CHECK_EQ(r.height, 180);
    // Every pixel in the overlap equals the analytic base (the two
    // crops agree there); a visible seam would exceed one LSB.
    int maxDiff = 0;
    for (int y = 0; y < 180; ++y) {
        for (int x = 120; x < 220; ++x) {
            for (int c = 0; c < 3; ++c) {
                const int d = std::abs(at(r, x, y, c) - at(base, x, y, c));
                if (d > maxDiff) maxDiff = d;
            }
            CHECK_EQ(at(r, x, y, 3), 255);
        }
    }
    CHECK(maxDiff <= 1);
    // Adjacent-pixel steps across the seam match the synthetic texture's
    // own steps (the fine mod pattern has genuine jumps; a seam would
    // add one). Compare against the analytic base, not a flat threshold.
    int maxExcess = 0;
    for (int y = 0; y < 180; y += 17) {
        for (int x = 121; x < 150; ++x) {
            for (int c = 0; c < 3; ++c) {
                const int stepR = at(r, x, y, c) - at(r, x - 1, y, c);
                const int stepB = at(base, x, y, c) - at(base, x - 1, y, c);
                const int excess = std::abs(stepR - stepB);
                if (excess > maxExcess) maxExcess = excess;
            }
        }
    }
    CHECK(maxExcess <= 1);
}

TEST(panorama_stitch_compensates_exposure) {
    const PanoImage raw = makeBase(340, 180);
    // Darken the scene first so the 1.2x second frame never clips
    // (clipped highlights carry no recoverable gain information).
    PanoImage base = raw;
    for (std::size_t i = 0; i < base.rgba.size(); i += 4)
        for (int c = 0; c < 3; ++c)
            base.rgba[i + c] = static_cast<std::uint8_t>(base.rgba[i + c] * 7 / 10);
    PanoImage bright = crop(base, 120, 0, 220, 180);
    for (std::size_t i = 0; i < bright.rgba.size(); i += 4) {
        for (int c = 0; c < 3; ++c) {
            const int v = std::min(255, bright.rgba[i + c] * 12 / 10);
            bright.rgba[i + c] = static_cast<std::uint8_t>(v);
        }
    }
    std::vector<PanoImage> imgs{crop(base, 0, 0, 220, 180), bright};
    PanoParams p = smallParams();
    p.feather = 32;
    p.compensateExposure = true;
    const PanoResult r = openclip::stitchPanorama(imgs, p);
    CHECK(r.ok());
    CHECK(std::abs(r.offsets[1].gain - 1.0f / 1.2f) < 0.03f);
    int maxDiff = 0;
    for (int y = 0; y < 180; y += 9) {
        for (int x = 160; x < 200; ++x) {  // fully inside the second image
            for (int c = 0; c < 3; ++c) {
                const int d = std::abs(at(r, x, y, c) - at(base, x, y, c));
                if (d > maxDiff) maxDiff = d;
            }
        }
    }
    CHECK(maxDiff <= 3);
}

TEST(panorama_refusals_are_explicit) {
    const PanoImage base = makeBase(120, 100);
    std::vector<PanoImage> two{crop(base, 0, 0, 120, 100),
                               crop(base, 20, 0, 100, 100)};
    std::vector<PanoImage> narrow{crop(base, 0, 0, 120, 100),
                                  crop(base, 40, 0, 40, 100)};
    PanoParams p = smallParams();
    // Overlap smaller than minOverlap on an axis.
    p.minOverlap = 50;
    PanoResult r = openclip::stitchPanorama(narrow, p);
    CHECK(!r.ok());
    CHECK(contains(r.error, "overlap"));
    // Aspect ratio mismatch.
    PanoImage wide = crop(base, 0, 0, 120, 10);
    p = smallParams();
    r = openclip::stitchPanorama({two[0], wide}, p);
    CHECK(!r.ok());
    CHECK(contains(r.error, "inconsistent"));
    // Zero / malformed dimensions.
    PanoImage bad;
    r = openclip::stitchPanorama({two[0], bad}, p);
    CHECK(!r.ok());
    CHECK(contains(r.error, "dimensions") || contains(r.error, "malformed"));
    // Memory budget refusal happens BEFORE allocation.
    p = smallParams();
    p.maxPixels = 1000;
    r = openclip::stitchPanorama(two, p);
    CHECK(!r.ok());
    CHECK(contains(r.error, "memory"));
    // Too few images.
    r = openclip::stitchPanorama({two[0]}, p);
    CHECK(!r.ok());
    CHECK(contains(r.error, "two"));
}

TEST(panorama_cancel_reports_and_stops) {
    const PanoImage base = makeBase(340, 180);
    std::vector<PanoImage> imgs{
        crop(base, 0, 0, 220, 180),
        crop(base, 120, 0, 220, 180),
    };
    PanoParams p = smallParams();
    std::vector<float> seen;
    const PanoResult r = openclip::stitchPanorama(
        imgs, p, [&](float t) {
            seen.push_back(t);
            return seen.size() < 2;  // cancel at the first opportunity
        });
    CHECK(!r.ok());
    CHECK(r.cancelled);
    CHECK(r.error.empty());
    CHECK(!seen.empty());
    // Progress is reported monotonically on a completed run.
    seen.clear();
    const PanoResult ok = openclip::stitchPanorama(
        imgs, p, [&](float t) {
            seen.push_back(t);
            return true;
        });
    CHECK(ok.ok());
    CHECK(!seen.empty());
    float last = -1.0f;
    for (float t : seen) {
        CHECK(t >= last);
        CHECK(t >= 0.0f && t <= 1.0f);
        last = t;
    }
    CHECK(last >= 0.99f);
}

TEST(panorama_png_roundtrip_and_loader_scale) {
    const PanoImage base = makeBase(96, 64);
    const std::string path = testh::tmpPath("pano_roundtrip.png");
    std::string err;
    CHECK(openclip::writePanoramaPng(path, base, err));
    CHECK(err.empty());
    PanoImage back;
    CHECK(openclip::loadPanoramaImage(path, 0, 0, back, err));
    CHECK_EQ(back.width, 96);
    CHECK_EQ(back.height, 64);
    CHECK(back.rgba == base.rgba);  // opaque round trip is exact
    PanoImage scaled;
    CHECK(openclip::loadPanoramaImage(path, 48, 0, scaled, err));
    CHECK_EQ(scaled.width, 48);
    CHECK_EQ(scaled.height, 32);
    // Budget refusal after scaling.
    PanoImage none;
    CHECK(!openclip::loadPanoramaImage(path, 0, 100, none, err));
    CHECK(contains(err, "maxInputPixels"));
    std::remove(path.c_str());
}
