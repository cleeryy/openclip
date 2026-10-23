// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip T5 engine — composite-time fill-layer renderer
// (clean-room, from scratch). A pure kernel like bubble.h: the shim
// stores the fiche on the Layer (fillKind/fillColor/fillStops/
// fillParams), parseFillSpec validates it, renderFill paints the
// content into a composite-time temp that flows through the normal
// mask/clip/opacity/blend path. Deterministic (fixed seeds);
// opaque output (rgb == 255-scale where painted, alpha 255).
#ifndef OPENCLIP_FILL_LAYER_H
#define OPENCLIP_FILL_LAYER_H

#include <cstdint>
#include <string>
#include <vector>

namespace openclip {

class Surface;
struct Layer;

struct FillStop {
    std::uint8_t r = 0, g = 0, b = 0;
    float pos = 0.0f;  // 0..1
};

struct FillSpec {
    std::string kind;  // "solid" | "gradient" | "foil"
    std::uint8_t r = 255, g = 255, b = 255;  // solid color
    std::string mode = "linear";             // gradient: linear|radial
    float x0 = 0, y0 = 0, x1 = 1, y1 = 0;    // geometry px
    std::vector<FillStop> stops;             // gradient: 2..8, sorted
    float hue = 0.0f;                        // foil: 0..360
    float scale = 4.0f;                      // foil: 1..16
    float shine = 0.5f;                      // foil: 0..1
};

// Parse the Layer fill fiche fields (ARCHITECTURE.md "T5 additions"
// §2: kind/color/stops/params raw spans). Unknown kind, unparseable
// color, or gradient with < 2 valid stops = false + error (the layer
// then paints nothing — pixels intact, like a malformed bubble).
// params span: "mode|x0|y0|x1|y1|hue|scale|shine" (unparsable numbers
// fall back to defaults, same M3 spirit as the T1 stops rule).
bool parseFillSpec(const Layer& l, FillSpec& spec, std::string& error);

// Render the fill into out (resized to w x h, cleared first):
// solid = opaque flat; gradient = stops ramp over the linear
// projection (p0->p1) or radial distance (center + radius point —
// zero radius = false + cleared); foil = rotating-hue iridescent
// ramp x fixed-seed value-Perlin grain x diagonal clear bands
// (documented evocation, not a physical simulation). Deterministic
// call to call. Returns false + out cleared on degenerate spec.
bool renderFill(const FillSpec& spec, int w, int h, Surface& out,
                std::string& error);

}  // namespace openclip

#endif
