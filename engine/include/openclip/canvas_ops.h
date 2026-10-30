// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip W1 (issue #28 slice A) — canvas geometry: anchored resize
// (crop/pad or resample), crop to a rectangle, and the print-size
// computation from document DPI. Pure Canvas logic, ARCHITECTURE.md
// "W1 additions".
#ifndef OPENCLIP_CANVAS_OPS_H
#define OPENCLIP_CANVAS_OPS_H

#include <string>

#include "openclip/canvas.h"

namespace openclip {

// Nine-point anchor grid, row-major.
enum class CanvasAnchor : std::uint8_t {
    TopLeft = 0, Top, TopRight,
    Left, Center, Right,
    BottomLeft, Bottom, BottomRight
};

// "center", "top-left", "bottom-right", ... Unknown -> Center (M3 rule).
CanvasAnchor anchorFromName(const char* name);
const char* anchorToName(CanvasAnchor a);

// Resize the canvas to w x h.
//   resample = false: content is placed at the anchor offset (crop or
//     transparent/opaque padding, never scaled). Masks pad opaque.
//   resample = true: the full canvas content is stretched to w x h with
//     bilinear filtering on premultiplied RGBA (alpha-safe). The anchor
//     only positions non-resampled content, documented.
// Vector path points, bubble frames and fill spans follow the content so
// a resized doc keeps its art aligned (text fiche boxes are app-side
// layout and are left to the app, pixels were committed already).
// False + canvas untouched on a non-positive size.
bool resizeCanvas(Canvas& c, int w, int h, CanvasAnchor anchor, bool resample,
                  std::string& err);

// Crop to (x, y, w, h). Refused (false + untouched) when the rectangle is
// not strictly inside the canvas — never a silent clamp.
bool cropCanvas(Canvas& c, int x, int y, int w, int h, std::string& err);

// Physical print size in millimetres for a pixel box at `dpi`
// (px / dpi * 25.4). dpi <= 0 returns {0, 0} (never NaN).
struct PrintSize {
    double widthMm = 0.0;
    double heightMm = 0.0;
};
PrintSize printSizeMm(int pxW, int pxH, double dpi);

}  // namespace openclip

#endif
