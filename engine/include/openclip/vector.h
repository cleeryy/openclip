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
// ("stroke" additive ink, "eraser-stroke" subtractive). T2: each path
// carries its own style — tip ("round"|"flat"), sizeMul (0.25..4
// factor on the pressure width profile), ink r,g,b (default session
// ink), and corners (1 = hard vertex cutting the app-side Catmull-Rom
// densification segment, smooth by default). eraser-stroke ignores
// color (dest-out, documented in ARCHITECTURE.md §T2).
struct VectorPath {
    std::vector<Vec2> pts;
    std::vector<float> widthProfile;  // × sizeMul at raster time
    std::string kind = "stroke";      // "stroke" | "eraser-stroke"
    std::string tip = "round";        // "round" | "flat"
    float sizeMul = 1.0f;             // 0.25..4
    std::uint8_t r = 0, g = 0, b = 0;  // per-path ink
    std::vector<char> corners;         // 1 = hard vertex
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
// "stroke" vs "eraser-stroke". T2 style keys (ARCHITECTURE.md §T2,
// M3 rule): optional "tip":"round"|"flat" (unknown = round),
// "size":0.25..4 (else 1), "color":"#RRGGBB" (unparsable = session
// ink 0,0,0 here — the shim passes its ink), "style":{...} wrapper
// accepted as an alias, "corners":[...] (0/1 per point, smoothed to
// pts size). Returns false (path untouched) on empty or malformed
// input.
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

// T2 indexed ops (ARCHITECTURE.md §T2 §3): snapshot history first
// (1 undo step each), false + layer intact on bad input. id is the
// positional index in vectorData (documented as such — the app
// re-reads nVectorPaths before every op, no id caching).
//
// replaceVectorPath swaps path id's geometry (parsed + resampled like
// append, same curveExp) while KEEPING tip/size/color/corners, unless
// pathJson carries an optional "style":{tip?,size?,color?} object
// re-applying style (M3 rule on its keys).
bool replaceVectorPath(Canvas& canvas, VectorHistory& history, std::size_t li,
                       std::size_t id, const std::string& pathJson,
                       float curveExp, std::string& error);
// connectVectorPaths fuses paths a+b when an endpoint pair is within
// tol (default 12px): concatenates in the seam-minimizing
// orientation, resamples to ~8px, keeps a's style, removes b.
bool connectVectorPaths(Canvas& canvas, VectorHistory& history, std::size_t li,
                        std::size_t a, std::size_t b, float tol,
                        std::string& error);
// setVectorWidths replaces path id's width profile (must match pts
// size, values clamped 0.5..128px). The falloff gesture math is
// app-side; the engine poses.
bool setVectorWidths(Canvas& canvas, VectorHistory& history, std::size_t li,
                     std::size_t id, const std::vector<float>& widths,
                     std::string& error);

// Rasterize all paths of a vector layer into out (w x h, cleared first).
// Stamps along path points at ~spacing px (default 2), radius =
// widthProfile*sizeMul/2 interpolated; per-path ink (T2 — the M6
// "black per layer" ends here); flat tip = square stamps, same AA;
// eraser-stroke paths stamp with destination-out (clear covered
// pixels, color ignored).
void rasterizeVectorPaths(const std::vector<VectorPath>& paths,
                          Surface& out);

}  // namespace openclip

#endif
