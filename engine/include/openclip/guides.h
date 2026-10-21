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

#include "openclip/vector.h"

namespace openclip {

struct Guide {
    enum class Kind { Vertical, Horizontal, Circle };
    Kind kind = Kind::Vertical;
    float x = 0, y = 0;          // vertical: x; horizontal: y
    float cx = 0, cy = 0, r = 0;  // circle: center + radius
};

// V+3 snap grid (ARCHITECTURE.md "V+3 additions", clean-room): snap
// onto the nearest grid intersection when step > 0 and tol > 0.
// tol <= 0 disables (the shim skips the call entirely); step values
// other than 8/16/32 are filtered shim-side (0 = off).
inline bool snapGrid(float& x, float& y, float step, float tol) {
    if (step <= 0.0f || tol <= 0.0f) return false;
    const float nx = std::round(x / step) * step;
    const float ny = std::round(y / step) * step;
    if (std::fabs(nx - x) <= tol && std::fabs(ny - y) <= tol) {
        x = nx;
        y = ny;
        return true;
    }
    return false;
}

// V+3 neighboring-path snap: nearest vertex of `pts` within tol wins.
// tol <= 0 disables. Empty point list never snaps.
inline bool snapPathPoints(const std::vector<Vec2>& pts, float& x, float& y,
                           float tol) {
    if (tol <= 0.0f || pts.empty()) return false;
    float best = tol;
    float nx = x, ny = y;
    bool hit = false;
    for (const auto& q : pts) {
        const float dx = x - q.x, dy = y - q.y;
        const float d = std::sqrt(dx * dx + dy * dy);
        if (d <= best) {
            best = d;
            nx = q.x;
            ny = q.y;
            hit = true;
        }
    }
    if (hit) {
        x = nx;
        y = ny;
        return true;
    }
    return false;
}
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
