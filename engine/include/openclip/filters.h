// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip M3 engine — named filter registry operating on a Surface.
// Clean-room implementations, written from scratch (no third-party code).
#ifndef OPENCLIP_FILTERS_H
#define OPENCLIP_FILTERS_H

#include <string>
#include <vector>

namespace openclip {

class Surface;

// Registry lookup: true when `name` is a known filter (exact match,
// case-sensitive). Known names: "gaussianBlur", "sharpen", "motionBlur",
// "radialBlur", "posterize", "levels", "chromaticAberration", "addNoise",
// "mosaic", "gradientMap".
bool canApply(const std::string& name);

// Ordered registry names (same list as canApply accepts).
std::vector<std::string> filterNames();

// Apply one named filter to `s` (full surface, RGB channels only).
// paramsJson is a flat JSON object; unknown keys are ignored, missing keys
// fall back to the defaults below. Returns false (surface left byte-identical)
// on unknown name or invalid params, with a human-readable `error`.
//
// Defaults table:
//   gaussianBlur        {"radius":6}          separable Gaussian, sigma=radius,
//                                             kernel half-width ceil(3*sigma),
//                                             clamp-to-edge sampling
//   sharpen             {"amount":0.5}        unsharp mask against a sigma=1.0
//                                             blur: out = c + amount*(c-blur)
//   motionBlur          {"length":12, "angle":0}
//                                             mean of `length` samples along a
//                                             centered segment at `angle`
//                                             degrees (0 = +x axis)
//   radialBlur          {"strength":0.5, "cx":<center>, "cy":<center>}
//                                             zoom blur: mean of 8 samples along
//                                             the center->pixel ray scaled by
//                                             (1 + strength*(k-3.5)/8)
//   posterize           {"levels":4}          per-channel quantization into
//                                             `levels` steps (levels>=2)
//   levels              {"black":0, "white":255}
//                                             out = (c-black)*255/(white-black),
//                                             clamped; white<=black is an error
//   chromaticAberration {"shift":3}           R sampled at (x-shift,y), B at
//                                             (x+shift,y), G untouched; clamped
//   addNoise            {"amount":20, "monochrome":1}
//                                             uniform noise in [-amount,amount]
//                                             added per pixel (one value for all
//                                             channels when monochrome!=0);
//                                             deterministic LCG re-seeded to 0
//                                             on every call (reproducible)
//   mosaic              {"block":16}          per-block RGB mean over
//                                             block x block cells (block>=1)
//   gradientMap         {"stops":"[\"#000000:0\",\"#FFFFFF:1\"]"}
//                                             Rec.601 luminance selects a color
//                                             by linear interpolation between
//                                             "#RRGGBB:pos" stops (pos 0..1,
//                                             sorted by pos; >=1 stop required)
//
// Alpha contract (M3): alpha bytes are NEVER modified by any filter; RGB
// outputs are clamped to [0, alpha] afterwards so premultiplied (rgb <= a)
// data stays valid. Dimensions are never changed.
bool applyFilter(Surface& s, const std::string& name,
                 const std::string& paramsJson, std::string& error);

}  // namespace openclip

#endif
