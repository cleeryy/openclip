// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip W1 (issue #28 slice A) — selection refinement kernel:
// grow / shrink (contract) / feather morphology on a 0..255 alpha plane,
// polyline (polygon) fill and "select from layer alpha". Pure data logic
// (no Canvas dependency), ARCHITECTURE.md "W1 additions".
#ifndef OPENCLIP_SELECTION_H
#define OPENCLIP_SELECTION_H

#include <cstdint>
#include <utility>
#include <vector>

namespace openclip {

class Surface;

// Full-resolution selection plane: w*h alpha values, 0 = unselected,
// 255 = fully selected, intermediate = soft edge (feather).
struct AlphaPlane {
    int w = 0;
    int h = 0;
    std::vector<std::uint8_t> alpha;

    void resize(int nw, int nh);
    bool empty() const { return w <= 0 || h <= 0 || alpha.empty(); }
    std::uint8_t at(int x, int y) const;
    void set(int x, int y, std::uint8_t v);
};

inline constexpr int kSelectionRadiusMax = 256;

// Max filter over a (2r+1) square window: grows the selected area by
// `radius` px, soft levels are preserved (a graded edge stays graded).
// radius <= 0 is a no-op.
void growPlane(AlphaPlane& p, int radius);

// Min filter over the same window: shrinks / contracts the selection by
// `radius` px.
void shrinkPlane(AlphaPlane& p, int radius);

// Separable box blur, two passes: softens the selection edge by `radius`.
void featherPlane(AlphaPlane& p, int radius);

// Even-odd scanline fill of a closed polygon (polyline selection) in
// plane coordinates; the outline itself is written as fully selected so
// a 1px-thin polygon still selects. Fewer than 3 points = no-op.
void fillPolygonPlane(AlphaPlane& p,
                      const std::vector<std::pair<float, float>>& points);

// Selection from a layer's alpha: every pixel whose alpha reaches
// `threshold` (0..255) keeps its alpha value (soft), the rest is cleared.
void alphaPlaneFromSurface(const Surface& s, int threshold, AlphaPlane& p);

// Number of selected pixels (alpha > 0).
int planeCoverage(const AlphaPlane& p);

}  // namespace openclip

#endif
