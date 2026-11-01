// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip issue #16 — multi-frame panorama stitching (from scratch,
// feature-based, no neural network and no new third party).
//
// The module is pure and host-testable: it consumes RGBA8 images in
// premultiplied form (the engine Surface layout) and returns a stitched
// RGBA8 buffer plus the recovered placements. File loading/writing ride
// stb_image/stb_image_write (already vendored); the JNI shim only
// parses JSON and forwards progress/cancel.
//
// Honest limits (documented in ARCHITECTURE.md "Issue #16 additions"):
// translation only — rotation/scale/parallax are NOT estimated;
// alignment is chained pairwise (i against i-1), so long strips drift;
// transparent inputs are averaged in premultiplied space.
#ifndef OPENCLIP_PANORAMA_H
#define OPENCLIP_PANORAMA_H

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace openclip {

// RGBA8 premultiplied, row-major, `width * height * 4` bytes.
struct PanoImage {
    int width = 0;
    int height = 0;
    std::vector<std::uint8_t> rgba;
};

struct PanoParams {
    int feather = 48;               // seam ramp width, px (0..512)
    bool compensateExposure = true; // mean-luma gain in overlaps
    int maxPixels = 16 * 1024 * 1024;  // output canvas budget
    int minOverlap = 16;            // required overlap on BOTH axes, px
    float minScore = 0.62f;         // gradient correlation accepted
};

struct PanoOffset {
    int x = 0;            // placement of this image in the output
    int y = 0;
    int dx = 0;           // offset relative to the previous image
    int dy = 0;
    float score = 0.0f;   // alignment confidence 0..1 (1 = perfect)
    float gain = 1.0f;    // exposure gain applied (1 = unchanged)
};

struct PanoResult {
    int width = 0;
    int height = 0;
    std::vector<std::uint8_t> rgba;
    std::vector<PanoOffset> offsets;
    float meanScore = 0.0f;
    bool cancelled = false;
    std::string error;

    bool ok() const {
        return error.empty() && !cancelled && width > 0 && height > 0;
    }
};

// Monotonic 0..1 progress. Returns false to cancel the job.
using PanoProgress = std::function<bool(float)>;

// Recover the translation of `b` relative to `a`: placing `b` at
// (dx, dy) in `a`'s coordinate frame maximises the zero-mean gradient
// correlation. `score` is 0..1 (0.5 = unrelated, 1 = identical edges).
// False + `error` on flat/featureless input or when the best score is
// below `params.minScore`.
bool alignPanoramaPair(const PanoImage& a, const PanoImage& b,
                       const PanoParams& params, int& dx, int& dy,
                       float& score, std::string& error);

// Stitch 2..32 images in order. On refusal/cancel `ok()` is false and
// `error` says why (empty when `cancelled`). Never partially writes
// files; callers own serialisation of the result.
PanoResult stitchPanorama(const std::vector<PanoImage>& images,
                          const PanoParams& params,
                          const PanoProgress& progress = nullptr);

// Load an image file (stb_image formats) into premultiplied RGBA8.
// `maxDimension` > 0 box-downscales the longest side (bounded memory);
// `maxPixels` > 0 refuses inputs past that many pixels AFTER scaling.
bool loadPanoramaImage(const std::string& path, int maxDimension,
                       int maxPixels, PanoImage& out, std::string& error);

// Write RGBA8 premultiplied pixels as a straight-alpha PNG.
bool writePanoramaPng(const std::string& path, const PanoImage& image,
                      std::string& error);

}  // namespace openclip

#endif
