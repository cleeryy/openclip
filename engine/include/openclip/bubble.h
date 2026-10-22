// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip T2 engine — composite-time speech-balloon renderer
// (clean-room, from scratch). A dedicated pure kernel (not T1
// stampShape: that one stamps on an ACTIVE layer with undo and a mask
// contract — here we render into a composite-time temp; same 4x4
// coverage supersampling PATTERN, documented in ARCHITECTURE.md §T2).
#ifndef OPENCLIP_BUBBLE_H
#define OPENCLIP_BUBBLE_H

#include <cstdint>
#include <string>

namespace openclip {

class Surface;

struct BubbleSpec {
    std::string shape = "ellipse";  // "ellipse" | "rounded"
    float x = 0, y = 0, w = 0, h = 0;  // frame px
    bool hasTail = false;
    float bx = 0, by = 0, tx = 0, ty = 0;  // tail base + tip px
    std::uint8_t fillR = 255, fillG = 255, fillB = 255;
    std::uint8_t strokeR = 0, strokeG = 0, strokeB = 0;
    float strokeW = 4.0f;  // 1..32, centered on the edge
};

// Parse the Layer bubble fiche fields into spec (defaults per
// ARCHITECTURE.md §T2; unknown shape = false).
bool parseBubbleSpec(const std::string& shape, const std::string& frame,
                     const std::string& tail, const std::string& fill,
                     const std::string& stroke, float strokeW,
                     BubbleSpec& spec, std::string& error);

// Render the balloon into out (cleared first, out-sized frame):
// filled body + tail, then the centered stroke band. Coverage from
// 4x4 supersampled point-in-shape tests (antialiased,
// premultiplied-safe, rgb <= alpha). Tail = triangle
// (base midpoint, base half-width, tip). Rounded body corner radius
// = min(w,h)/6. Returns false + out cleared on degenerate spec.
bool renderBubble(const BubbleSpec& spec, Surface& out, std::string& error);

}  // namespace openclip

#endif
