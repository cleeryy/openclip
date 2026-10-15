// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip M2 engine — stroke accumulator with stabilization,
// pressure curve mapping, and distance-based stamp placement.
#ifndef OPENCLIP_STROKE_H
#define OPENCLIP_STROKE_H

#include <cstddef>

#include "openclip/brush.h"

namespace openclip {

class Surface;
class Canvas;
class History;

// Pressure curve: maps normalized pressure 0..1 through pow(t, exponent).
// Canonical JSON "curve" 0..1 => exponent = 1 + 3*curve (linear..heavy).
struct PointCurve {
    float exponent = 1.0f;
    float at(float t) const;
    static PointCurve fromJsonCurve(float curve);
};

// Live stroke cursor. px/py/ppressure track the STABILIZED cursor (what
// actually gets painted), maintained from begin() through each to().
struct StrokeState {
    bool open = false;
    float px = 0, py = 0, ppressure = 1;
    PointCurve curve;
    float stabilize = 0;
};

// Stateful stroke painter: exponential moving-average stabilization on
// position AND pressure, distance accumulator placing stamps every
// spacing*radius along the smoothed polyline. Paints via paintDab.
// History (nullable, with its Canvas) snapshots tiles via touchTile before
// each dab; the beginStroke/endStroke lifecycle stays with the caller.
class Stroke {
public:
    Stroke(Surface& surface, BrushParams params, PointCurve curve,
           float stabilize, std::uint8_t r, std::uint8_t g, std::uint8_t b,
           History* history = nullptr, Canvas* canvas = nullptr,
           std::size_t layer = 0);
    void begin(float x, float y, float pressure);
    void to(float x, float y, float pressure);
    void end();
    bool open() const { return state_.open; }
    const StrokeState& state() const { return state_; }
    std::size_t stampCount() const { return stamps_; }

private:
    void emit(float x, float y, float pressure);
    void touch(float x, float y, float radius);

    Surface* surface_;
    BrushParams params_;
    std::uint8_t r_, g_, b_;
    History* history_;
    Canvas* canvas_;
    std::size_t layer_;
    StrokeState state_;
    // Stabilized (smoothed) cursor, segment-start pressure, raw previous
    // input (dupe detection), and distance accumulator.
    float sx_ = 0, sy_ = 0, sp_ = 1;
    float rx_ = 0, ry_ = 0;
    float carry_ = 0;  // leftover distance since last stamp
    std::size_t stamps_ = 0;
};

}  // namespace openclip

#endif
