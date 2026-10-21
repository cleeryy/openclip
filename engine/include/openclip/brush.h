// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip M1 engine — stamp-based brush + MyPaint .myb descriptor parser.
#ifndef OPENCLIP_BRUSH_H
#define OPENCLIP_BRUSH_H

#include <cstdint>
#include <string>
#include <vector>

namespace openclip {

class Surface;

struct BrushParams {
    float radius = 8.0f;    // base stamp radius in px (from .myb "radius")
    float hardness = 0.7f;  // 0 soft .. 1 hard (from .myb "hardness")
    float opaque = 1.0f;    // max stamp opacity (from .myb "opaque")
    float spacing = 0.15f;  // stamp spacing as fraction of radius
    // V+3 mixer knife (ARCHITECTURE.md "V+3 additions"): mix 0 = plain
    // brush (byte-identical), 1 = pure knife (pushes paint only);
    // mixRadius scales the local pickup window (× dab radius).
    float mix = 0.0f;        // 0..1, clamped shim-side
    float mixRadius = 1.0f;  // 0.5..2.0, clamped shim-side
};

struct Dab {
    float x = 0, y = 0;       // canvas coords
    float pressure = 1.0f;    // 0..1
    float radius = 8.0f;      // resolved stamp radius
    float opacity = 1.0f;     // resolved stamp opacity
    std::uint8_t r = 0, g = 0, b = 0;  // straight (non-premult) color
};

// Pressure mapping: size scales 0.25x..1x, opacity 0..opaque linearly.
Dab dabFromPressure(const BrushParams& p, float x, float y, float pressure,
                    std::uint8_t r, std::uint8_t g, std::uint8_t b);

// Supersampled interpolation between input points: emits stamps spaced
// at most spacing*radius apart along the segment.
std::vector<Dab> interpolateStamps(const BrushParams& p,
                                   float x0, float y0, float pr0,
                                   float x1, float y1, float pr1,
                                   std::uint8_t r, std::uint8_t g, std::uint8_t b);

// Paint one soft round stamp (source-over) onto a premultiplied surface.
void paintDab(Surface& s, const Dab& d, const BrushParams& p);

// V+1 alpha-erase (ARCHITECTURE.md "V+1 additions"): dest-out stamp —
// reduces surface alpha in place (a' = a*(1-m)), rgb rescaled to stay
// premultiplied (rgb <= a preserved). Same falloff sampling as
// paintDab; color channels of d are ignored.
void eraseDab(Surface& s, const Dab& d, const BrushParams& p);

// Mask stamp (M5 rule, moved here V+1 so make-test covers it): ink
// luminance drives mask alpha (white keeps, black hides), rgb follows
// alpha (straight mask convention Pixel{v,v,v,v}). Pixels are never
// touched — the caller routes to the mask surface.
void paintMaskDab(Surface& m, const Dab& d, const BrushParams& p);

// Parse a MyPaint .myb XML descriptor. Reads the <setting name="radius"|
// "hardness"|"opaque"> base_value attributes we support; unknown settings
// are ignored. Returns false on malformed XML. Brand-new clean-room parser.
bool parseMyb(const std::string& xml, BrushParams& out, std::string& error);

}  // namespace openclip

#endif
