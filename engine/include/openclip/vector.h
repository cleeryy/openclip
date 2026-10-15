// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip M6 engine — vector path model: resolution-independent strokes
// stored per layer, rasterized at composite time (clean-room, no SVG).
#ifndef OPENCLIP_VECTOR_H
#define OPENCLIP_VECTOR_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace openclip {

struct Vec2 {
    float x = 0, y = 0;
};

// One vector stroke: resampled centerline points, per-point widths
// (diameter in px, pressure-mapped via PointCurve), and kind
// ("stroke" additive ink, "eraser-stroke" subtractive).
struct VectorPath {
    std::vector<Vec2> pts;
    std::vector<float> widthProfile;
    std::string kind = "stroke";  // "stroke" | "eraser-stroke"
};

// Per-vector-layer undo: each entry snapshots the full vectorData of
// one mutating op (append or erase); undo pops one snapshot.
struct VectorHistory {
    std::vector<std::vector<VectorPath>> stack;
    void push(const std::vector<VectorPath>& v);
    // Restore the most recent snapshot into v; false when empty.
    bool pop(std::vector<VectorPath>& v);
    void clear() { stack.clear(); }
    bool empty() const { return stack.empty(); }
};

class Surface;
class Canvas;

// Parse pathJson pts [{"x":..,"y":..,"p":..}] into a VectorPath with the
// polyline resampled to ~8px steps; pressure maps through curveExp
// (canonical exponent, see PointCurve::fromJsonCurve). kind selects
// "stroke" vs "eraser-stroke". Returns false (path untouched) on empty
// or malformed input.
bool parseVectorPathJson(const std::string& pathJson, float curveExp,
                         const std::string& kind, VectorPath& path,
                         std::string& error);

// Append one path to layer li (must be a vector-flagged layer of canvas).
// Snapshots history first. Returns the new path count, or -1 on bad input
// (layer unchanged, no snapshot pushed).
int appendVectorPath(Canvas& canvas, VectorHistory& history, std::size_t li,
                     const std::string& pathJson, float curveExp,
                     const std::string& kind, std::string& error);

// Eraser: remove every path of layer li intersecting the eraser polyline
// (parsed from pathJson the same way, width ignored). Snapshots history.
// Returns the number of removed paths, -1 on bad input.
int eraseVectorPaths(Canvas& canvas, VectorHistory& history, std::size_t li,
                     const std::string& pathJson, std::string& error);

// Pop one undo snapshot into layer li. False when history is empty.
bool undoVectorPath(Canvas& canvas, VectorHistory& history, std::size_t li);

// Rasterize all paths of a vector layer into out (w x h, cleared first).
// Stamps along path points at ~spacing px (default 2), radius =
// widthProfile/2 interpolated; hard round stamps, source-over, opaque ink
// color (straight black premultiplied). eraser-stroke paths stamp with
// destination-out (clear covered pixels).
void rasterizeVectorPaths(const std::vector<VectorPath>& paths, Surface& out,
                          std::uint8_t r, std::uint8_t g, std::uint8_t b);

}  // namespace openclip

#endif
