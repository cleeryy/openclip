// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip V+2 — persistent per-document guides + input snap
// (clean-room). Guides are overlay-only working aids: vertical (x),
// horizontal (y) and concentric (cx,cy,r) lines/circles. The pure snap
// runs here so engine tests lock it; JSON parsing stays shim-side
// (nSetRulers extension, ARCHITECTURE.md "V+2 additions").
#ifndef OPENCLIP_GUIDES_H
#define OPENCLIP_GUIDES_H

#include <cmath>
#include <cstddef>
#include <vector>

namespace openclip {

struct Guide {
    enum class Kind { Vertical, Horizontal, Circle };
    Kind kind = Kind::Vertical;
    float x = 0, y = 0;          // vertical: x; horizontal: y
    float cx = 0, cy = 0, r = 0;  // circle: center + radius
};

// Snap (x, y) onto the nearest guide within `tol` px (default 2, the
// spec §4.4 value). Vertical snaps x, horizontal snaps y, circle
// projects the point onto the circumference (no-op at exact center).
// Returns true when a snap applied (nearest guide wins).
inline bool snapGuides(const std::vector<Guide>& guides, float& x, float& y,
                       float tol = 2.0f) {
    if (tol < 0.0f) return false;
    float best = tol;
    float nx = x, ny = y;
    bool hit = false;
    for (const auto& g : guides) {
        if (g.kind == Guide::Kind::Vertical) {
            const float d = std::fabs(x - g.x);
            if (d <= best) {
                best = d;
                nx = g.x;
                ny = y;
                hit = true;
            }
        } else if (g.kind == Guide::Kind::Horizontal) {
            const float d = std::fabs(y - g.y);
            if (d <= best) {
                best = d;
                nx = x;
                ny = g.y;
                hit = true;
            }
        } else {
            if (g.r <= 0.0f) continue;
            const float dx = x - g.cx, dy = y - g.cy;
            const float dist = std::sqrt(dx * dx + dy * dy);
            if (dist <= 0.0f) continue;
            const float off = std::fabs(dist - g.r);
            if (off <= best) {
                best = off;
                const float k = g.r / dist;
                nx = g.cx + dx * k;
                ny = g.cy + dy * k;
                hit = true;
            }
        }
    }
    if (hit) {
        x = nx;
        y = ny;
        return true;
    }
    return false;
}

}  // namespace openclip

#endif
