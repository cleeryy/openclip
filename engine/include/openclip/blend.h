// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip M1 engine — multi-layer canvas, composite to RGBA8.
#ifndef OPENCLIP_BLEND_H
#define OPENCLIP_BLEND_H

#include <cstdint>

namespace openclip {

enum class BlendMode : std::uint8_t {
    Normal = 0,
    Multiply,
    Screen,
    Overlay,
    Add,
    SoftLight,
    // V+2 additions (ARCHITECTURE.md "V+2 additions", clean-room).
    HardLight,
    Difference,
    Color,
    Luminosity,
    // T5 additions (ARCHITECTURE.md "T5 additions" §3, clean-room,
    // from scratch). linear-dodge is the documented twin of Add
    // (same clamped sum, own name for the open nomenclature).
    Darken,
    Lighten,
    LinearBurn,
    LinearDodge,
    VividLight,
    LinearLight,
    PinLight,
    Subtract,
    Divide,
    Exclusion
};

// Parse an OpenRaster / SVG blend name ("svg:multiply", "svg:screen", ...).
// Unknown names map to Normal. Never throws.
BlendMode blendFromName(const char* name);
// Canonical "svg:<name>" string for a mode. Never returns null.
const char* blendToName(BlendMode m);

// Blend one premultiplied src pixel over one premultiplied dst pixel,
// writing the premultiplied result to out.
// Rounding: all channels computed in float [0,1] and rounded to nearest
// with std::lround (halves away from zero), then clamped to [0,255].
struct Pixel;
void blendPixel(const Pixel& src, const Pixel& dst, BlendMode mode, Pixel& out);

}  // namespace openclip

#endif
