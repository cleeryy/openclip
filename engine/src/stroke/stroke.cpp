// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip M2 engine — stroke accumulator: stabilization, pressure curve,
// distance-based stamp placement (replaces per-segment interpolateStamps).
#include "openclip/stroke.h"

#include <cmath>

#include "openclip/canvas.h"
#include "openclip/surface.h"
#include "openclip/undo.h"

namespace openclip {
namespace {

inline float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

}  // namespace

float PointCurve::at(float t) const {
    const float c = clamp01(t);
    if (exponent <= 0.0f) return c;
    return std::pow(c, exponent);
}

PointCurve PointCurve::fromJsonCurve(float curve) {
    PointCurve pc;
    pc.exponent = 1.0f + 3.0f * clamp01(curve);
    return pc;
}

Stroke::Stroke(Surface& surface, BrushParams params, PointCurve curve,
               float stabilize, std::uint8_t r, std::uint8_t g,
               std::uint8_t b, History* history, Canvas* canvas,
               std::size_t layer)
    : surface_(&surface),
      params_(params),
      r_(r),
      g_(g),
      b_(b),
      history_(history),
      canvas_(canvas),
      layer_(layer) {
    state_.curve = curve;
    state_.stabilize = clamp01(stabilize);
}

void Stroke::begin(float x, float y, float pressure) {
    const float pr = clamp01(pressure);
    state_.open = true;
    sx_ = x;
    sy_ = y;
    sp_ = pr;
    rx_ = x;
    ry_ = y;
    state_.px = x;
    state_.py = y;
    state_.ppressure = pr;
    carry_ = 0;
    stamps_ = 0;
    emit(x, y, state_.curve.at(pr));
}

void Stroke::to(float x, float y, float pressure) {
    if (!state_.open) return;
    // Dupes (same raw input as previous) are ignored: no stamp.
    if (x == rx_ && y == ry_) return;
    rx_ = x;
    ry_ = y;
    const float pr = clamp01(pressure);
    // Stabilization: exponential moving average on position AND pressure.
    // k = mix(1.0, 0.25, stabilize): 0 => no lag, 1 => max lag.
    const float k = 1.0f - 0.75f * state_.stabilize;
    const float nx = sx_ + k * (x - sx_);
    const float ny = sy_ + k * (y - sy_);
    const float np = sp_ + k * (pr - sp_);

    const float dx = nx - sx_, dy = ny - sy_;
    const float dist = std::sqrt(dx * dx + dy * dy);
    const float dp = np - sp_;
    float step = params_.spacing * params_.radius;
    if (step < 0.5f) step = 0.5f;

    if (dist <= 0) {
        sx_ = nx;
        sy_ = ny;
        sp_ = np;
        state_.px = sx_;
        state_.py = sy_;
        state_.ppressure = sp_;
        return;
    }
    // Walk the smoothed segment with the distance accumulator so stamps
    // land exactly every `step` px across consecutive to() calls.
    float travelled = 0;
    while (carry_ + (dist - travelled) >= step) {
        const float need = step - carry_;
        travelled += need;
        const float t = travelled / dist;
        const float segPr = sp_ + dp * t;
        emit(sx_ + dx * t, sy_ + dy * t, state_.curve.at(segPr));
        carry_ = 0;
    }
    carry_ += dist - travelled;
    sx_ = nx;
    sy_ = ny;
    sp_ = np;
    state_.px = sx_;
    state_.py = sy_;
    state_.ppressure = sp_;
}

void Stroke::end() {
    if (!state_.open) return;
    state_.open = false;
    carry_ = 0;
}

void Stroke::emit(float x, float y, float pressure) {
    Dab d = dabFromPressure(params_, x, y, pressure, r_, g_, b_);
    touch(x, y, d.radius);
    paintDab(*surface_, d, params_);
    ++stamps_;
}

void Stroke::touch(float x, float y, float radius) {
    if (!history_ || !canvas_) return;
    const int x0 = static_cast<int>(std::floor(x - radius));
    const int x1 = static_cast<int>(std::ceil(x + radius));
    const int y0 = static_cast<int>(std::floor(y - radius));
    const int y1 = static_cast<int>(std::ceil(y + radius));
    const int tx0 = x0 / kTileSize, tx1 = x1 / kTileSize;
    const int ty0 = y0 / kTileSize, ty1 = y1 / kTileSize;
    for (int ty = ty0; ty <= ty1; ++ty)
        for (int tx = tx0; tx <= tx1; ++tx)
            history_->touchTile(*canvas_, layer_, tx, ty);
}

}  // namespace openclip
