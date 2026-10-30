// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip B1 engine — retouch kernels (ARCHITECTURE.md "B1 additions").
// Clean-room: clone stamp, smudge, dodge/burn, pattern walker and the
// procedural motif tiles. Pure Surface code, deterministic.
#include "openclip/retouch.h"

#include <algorithm>
#include <cmath>
#include <utility>

#include "openclip/surface.h"

namespace openclip {
namespace {

constexpr float kPi = 3.14159265358979323846f;

inline float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

inline float clampF(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

// Same falloff as brush.cpp: 1 at centre -> 0 at edge, cosine rolloff
// from hardness..1 (h = 1 is a hard disc).
float falloff(float t, float hardness) {
    if (t >= 1.0f) return 0.0f;
    const float h = clamp01(hardness);
    if (t <= h) return 1.0f;
    const float span = 1.0f - h;
    if (span <= 1e-6f) return 0.0f;
    const float u = (t - h) / span;
    return 0.5f * (1.0f + std::cos(u * kPi));
}

inline std::uint8_t q8(float v) {
    const long r = std::lround(v);
    return static_cast<std::uint8_t>(r < 0 ? 0 : (r > 255 ? 255 : r));
}

inline void clampPremult(Pixel& p) {
    if (p.r > p.a) p.r = p.a;
    if (p.g > p.a) p.g = p.a;
    if (p.b > p.a) p.b = p.a;
}

}  // namespace

// ---- Clone stamp ---------------------------------------------------------

void cloneSetSource(CloneAnchor& a, float x, float y, bool aligned) {
    a.have = true;
    a.x = x;
    a.y = y;
    a.aligned = aligned;
    a.offValid = false;
    a.offX = 0;
    a.offY = 0;
}

void cloneStrokeBegin(CloneAnchor& a, float x, float y) {
    if (!a.have) {
        a.offValid = false;
        return;
    }
    if (a.aligned && a.offValid) return;  // sampling continues
    a.offX = a.x - x;
    a.offY = a.y - y;
    a.offValid = true;
}

bool cloneReady(const CloneAnchor& a) { return a.have && a.offValid; }

void cloneDab(Surface& dst, const Surface& src, const Dab& d,
              const BrushParams& p, float offX, float offY) {
    if (d.opacity <= 0.0f || d.radius <= 0.0f) return;
    const int x0 = static_cast<int>(std::floor(d.x - d.radius));
    const int x1 = static_cast<int>(std::ceil(d.x + d.radius));
    const int y0 = static_cast<int>(std::floor(d.y - d.radius));
    const int y1 = static_cast<int>(std::ceil(d.y + d.radius));
    // Integer sampling offset: a clone copy must stay pixel-exact.
    const int ox = static_cast<int>(std::lround(offX));
    const int oy = static_cast<int>(std::lround(offY));
    for (int y = y0; y <= y1; ++y) {
        for (int x = x0; x <= x1; ++x) {
            const float dx = (x + 0.5f) - d.x;
            const float dy = (y + 0.5f) - d.y;
            const float t = std::sqrt(dx * dx + dy * dy) / d.radius;
            const float m = falloff(t, p.hardness) * d.opacity;
            if (m <= 1e-4f) continue;
            const Pixel s = src.get(x + ox, y + oy);
            if (s.a == 0) continue;
            Pixel o = dst.get(x, y);
            const float sa = (s.a / 255.0f) * m;
            const float ia = 1.0f - sa;
            o.r = q8(s.r * m + o.r * ia);
            o.g = q8(s.g * m + o.g * ia);
            o.b = q8(s.b * m + o.b * ia);
            o.a = q8(s.a * m + o.a * ia);
            clampPremult(o);
            dst.set(x, y, o);
        }
    }
}

// ---- Smudge --------------------------------------------------------------

void smudgeDab(Surface& s, const Dab& d, const BrushParams& p,
               SmudgeState& state, float strength) {
    if (d.opacity <= 0.0f || d.radius <= 0.0f) return;
    // Pickup coupling: stronger smudge carries more paint (30% local
    // pickup per dab at strength 1, 60% at strength 0).
    const float k = clamp01(0.6f - 0.3f * clamp01(strength));
    const int x0 = static_cast<int>(std::floor(d.x - d.radius));
    const int x1 = static_cast<int>(std::ceil(d.x + d.radius));
    const int y0 = static_cast<int>(std::floor(d.y - d.radius));
    const int y1 = static_cast<int>(std::ceil(d.y + d.radius));
    // Pickup: falloff-weighted average of the local premultiplied
    // colour; straight colour = mean(premult) / mean(alpha).
    double sumW = 0, sumA = 0, sumR = 0, sumG = 0, sumB = 0;
    for (int y = y0; y <= y1; ++y) {
        for (int x = x0; x <= x1; ++x) {
            const float dx = (x + 0.5f) - d.x;
            const float dy = (y + 0.5f) - d.y;
            const float t = std::sqrt(dx * dx + dy * dy) / d.radius;
            const float w = falloff(t, p.hardness);
            if (w <= 1e-4f) continue;
            const Pixel q = s.get(x, y);
            sumW += w;
            sumA += w * (q.a / 255.0);
            sumR += w * (q.r / 255.0);
            sumG += w * (q.g / 255.0);
            sumB += w * (q.b / 255.0);
        }
    }
    if (sumW <= 0) return;
    const float pickA = static_cast<float>(sumA / sumW);
    float pickR = 0, pickG = 0, pickB = 0;
    if (sumA > 1e-6) {
        pickR = static_cast<float>(sumR / sumA);
        pickG = static_cast<float>(sumG / sumA);
        pickB = static_cast<float>(sumB / sumA);
    }
    if (!state.primed) {
        state.primed = true;
        state.a = pickA;
        state.r = pickR;
        state.g = pickG;
        state.b = pickB;
    } else {
        state.a += (pickA - state.a) * k;
        state.r += (pickR - state.r) * k;
        state.g += (pickG - state.g) * k;
        state.b += (pickB - state.b) * k;
    }
    // Deposit the carried paint at the new position: this is what makes
    // the colour travel along the stroke.
    const float deposit = clamp01(strength) * d.opacity;
    if (deposit <= 0.0f || state.a <= 0.0f) return;
    for (int y = y0; y <= y1; ++y) {
        for (int x = x0; x <= x1; ++x) {
            const float dx = (x + 0.5f) - d.x;
            const float dy = (y + 0.5f) - d.y;
            const float t = std::sqrt(dx * dx + dy * dy) / d.radius;
            const float m = falloff(t, p.hardness) * deposit;
            if (m <= 1e-4f) continue;
            const float sa = state.a * m;
            if (sa <= 1e-4f) continue;
            const float ia = 1.0f - sa;
            Pixel o = s.get(x, y);
            o.r = q8(state.r * sa * 255.0f + o.r * ia);
            o.g = q8(state.g * sa * 255.0f + o.g * ia);
            o.b = q8(state.b * sa * 255.0f + o.b * ia);
            o.a = q8(sa * 255.0f + o.a * ia);
            clampPremult(o);
            s.set(x, y, o);
        }
    }
}

// ---- Dodge / burn --------------------------------------------------------

void dodgeBurnDab(Surface& s, const Dab& d, const BrushParams& p, bool burn) {
    if (d.opacity <= 0.0f || d.radius <= 0.0f) return;
    const int x0 = static_cast<int>(std::floor(d.x - d.radius));
    const int x1 = static_cast<int>(std::ceil(d.x + d.radius));
    const int y0 = static_cast<int>(std::floor(d.y - d.radius));
    const int y1 = static_cast<int>(std::ceil(d.y + d.radius));
    for (int y = y0; y <= y1; ++y) {
        for (int x = x0; x <= x1; ++x) {
            const float dx = (x + 0.5f) - d.x;
            const float dy = (y + 0.5f) - d.y;
            const float t = std::sqrt(dx * dx + dy * dy) / d.radius;
            const float m = falloff(t, p.hardness) * d.opacity;
            if (m <= 1e-4f) continue;
            Pixel q = s.get(x, y);
            if (q.a == 0) continue;  // dodge/burn needs existing paint
            const float a = q.a / 255.0f;
            float r = q.r / 255.0f / a;
            float g = q.g / 255.0f / a;
            float b = q.b / 255.0f / a;
            if (burn) {
                r *= (1.0f - m);
                g *= (1.0f - m);
                b *= (1.0f - m);
            } else {
                r += (1.0f - r) * m;
                g += (1.0f - g) * m;
                b += (1.0f - b) * m;
            }
            Pixel o;
            o.a = q.a;
            o.r = q8(clamp01(r) * a * 255.0f);
            o.g = q8(clamp01(g) * a * 255.0f);
            o.b = q8(clamp01(b) * a * 255.0f);
            clampPremult(o);
            s.set(x, y, o);
        }
    }
}

// ---- Pattern walker ------------------------------------------------------

PatternWalker::PatternWalker(PatternSpec spec) : spec_(std::move(spec)) {
    spec_.spacing = clampF(spec_.spacing, 1.0f, 512.0f);
    spec_.scale = clampF(spec_.scale, 0.05f, 16.0f);
    spec_.rotationDeg = clampF(spec_.rotationDeg, -360.0f, 360.0f);
    spec_.jitterPos = clampF(spec_.jitterPos, 0.0f, 256.0f);
    spec_.jitterScale = clampF(spec_.jitterScale, 0.0f, 1.0f);
    spec_.jitterRot = clampF(spec_.jitterRot, 0.0f, 360.0f);
    rng_ = spec_.seed ? spec_.seed : 1u;
}

float PatternWalker::rand01() {
    // xorshift32: deterministic, no library/state dependency.
    std::uint32_t x = rng_;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    rng_ = x ? x : 0x9E3779B9u;
    return static_cast<float>((rng_ >> 8) & 0xFFFFFFu) / 16777216.0f;
}

void PatternWalker::emit(float x, float y, float pressure, float dirDeg) {
    PatternStamp st;
    st.x = x;
    st.y = y;
    if (spec_.jitterPos > 0.0f) {
        st.x += (rand01() * 2.0f - 1.0f) * spec_.jitterPos;
        st.y += (rand01() * 2.0f - 1.0f) * spec_.jitterPos;
    } else {
        (void)rand01();
        (void)rand01();
    }
    float scaleJitter = 1.0f;
    if (spec_.jitterScale > 0.0f)
        scaleJitter += (rand01() * 2.0f - 1.0f) * spec_.jitterScale;
    else
        (void)rand01();
    st.scale = spec_.scale * (0.5f + 0.5f * clamp01(pressure)) * scaleJitter;
    if (st.scale < 0.02f) st.scale = 0.02f;
    float rotJitter = 0.0f;
    if (spec_.jitterRot > 0.0f)
        rotJitter = (rand01() * 2.0f - 1.0f) * spec_.jitterRot;
    else
        (void)rand01();
    st.angleDeg = spec_.rotationDeg + (spec_.alongPath ? dirDeg : 0.0f) +
                  rotJitter;
    pending_.push_back(st);
    ++count_;
}

void PatternWalker::begin(float x, float y, float pressure) {
    px_ = x;
    py_ = y;
    pp_ = clamp01(pressure);
    open_ = true;
    carry_ = 0;
    dir_ = 0;
    emit(x, y, pp_, 0.0f);
}

void PatternWalker::to(float x, float y, float pressure) {
    if (!open_) {
        begin(x, y, pressure);
        return;
    }
    const float dx = x - px_;
    const float dy = y - py_;
    const float dist = std::sqrt(dx * dx + dy * dy);
    if (dist <= 1e-4f) {
        pp_ = clamp01(pressure);
        return;
    }
    dir_ = std::atan2(dy, dx) * 180.0f / kPi;
    const float p0 = pp_;
    const float p1 = clamp01(pressure);
    const float sx = px_, sy = py_;
    float walked = 0;
    while (carry_ + (dist - walked) >= spec_.spacing) {
        const float step = spec_.spacing - carry_;
        walked += step;
        const float t = walked / dist;
        emit(sx + dx * t, sy + dy * t, p0 + (p1 - p0) * t, dir_);
        carry_ = 0;
    }
    carry_ += dist - walked;
    px_ = x;
    py_ = y;
    pp_ = p1;
}

std::vector<PatternStamp> PatternWalker::take() {
    std::vector<PatternStamp> out;
    out.swap(pending_);
    return out;
}

// ---- Procedural motifs ---------------------------------------------------

namespace {

// Point-in-polygon (even-odd ray cast) on normalized coordinates.
bool inPolygon(const float* xs, const float* ys, int n, float px, float py) {
    bool inside = false;
    for (int i = 0, j = n - 1; i < n; j = i++) {
        const bool cross = ((ys[i] > py) != (ys[j] > py)) &&
                           (px < (xs[j] - xs[i]) * (py - ys[i]) /
                                         (ys[j] - ys[i]) +
                                     xs[i]);
        if (cross) inside = !inside;
    }
    return inside;
}

}  // namespace

bool proceduralMotif(const std::string& kind, int px, std::uint8_t r,
                     std::uint8_t g, std::uint8_t b, Surface& out,
                     std::string& error) {
    error.clear();
    if (kind != "dots" && kind != "ring" && kind != "star" &&
        kind != "cross") {
        error = "motif: unknown kind (dots|ring|star|cross)";
        return false;
    }
    if (px < 8 || px > 128) {
        error = "motif: size must be in [8,128]";
        return false;
    }
    out.resize(px, px);
    out.clear();
    // Ten-vertex star polygon (outer/inner alternating), normalized to
    // the [-0.5,0.5] tile square.
    float starX[10], starY[10];
    for (int i = 0; i < 10; ++i) {
        const float ang = -kPi / 2.0f + static_cast<float>(i) * kPi / 5.0f;
        const float rad = (i % 2 == 0) ? 0.47f : 0.20f;
        starX[i] = std::cos(ang) * rad;
        starY[i] = std::sin(ang) * rad;
    }
    const int kSamples = 4;  // 4x4 supersampling per pixel
    for (int y = 0; y < px; ++y) {
        for (int x = 0; x < px; ++x) {
            int hits = 0;
            for (int sy = 0; sy < kSamples; ++sy) {
                for (int sx = 0; sx < kSamples; ++sx) {
                    const float u = (x + (sx + 0.5f) / kSamples) / px - 0.5f;
                    const float v = (y + (sy + 0.5f) / kSamples) / px - 0.5f;
                    bool hit = false;
                    if (kind == "dots") {
                        hit = std::sqrt(u * u + v * v) <= 0.43f;
                    } else if (kind == "ring") {
                        const float d = std::sqrt(u * u + v * v);
                        hit = d >= 0.30f && d <= 0.45f;
                    } else if (kind == "star") {
                        hit = inPolygon(starX, starY, 10, u, v);
                    } else {
                        hit = (std::fabs(u) <= 0.12f && std::fabs(v) <= 0.46f) ||
                              (std::fabs(v) <= 0.12f && std::fabs(u) <= 0.46f);
                    }
                    if (hit) ++hits;
                }
            }
            if (hits == 0) continue;
            const float cov = static_cast<float>(hits) /
                              static_cast<float>(kSamples * kSamples);
            Pixel p;
            p.a = q8(cov * 255.0f);
            p.r = q8(cov * r);
            p.g = q8(cov * g);
            p.b = q8(cov * b);
            clampPremult(p);
            out.set(x, y, p);
        }
    }
    return true;
}

void patternStampBounds(const PatternStamp& st, int tileW, int tileH, int& x0,
                        int& y0, int& x1, int& y1) {
    const float side = static_cast<float>(std::max(tileW, tileH)) * st.scale;
    const float half = side * 0.5f;
    x0 = static_cast<int>(std::floor(st.x - half));
    y0 = static_cast<int>(std::floor(st.y - half));
    x1 = static_cast<int>(std::ceil(st.x + half));
    y1 = static_cast<int>(std::ceil(st.y + half));
}

void stampPattern(Surface& dst, const Surface& tile, const PatternStamp& st,
                  float opacity, bool alphaLock) {
    if (tile.empty() || st.scale <= 0.0f || opacity <= 0.0f) return;
    const int tw = tile.width(), th = tile.height();
    if (tw <= 0 || th <= 0) return;
    const float rad = st.angleDeg * kPi / 180.0f;
    const float ca = std::cos(rad), sa = std::sin(rad);
    const float cx = tw * 0.5f, cy = th * 0.5f;
    int x0, y0, x1, y1;
    patternStampBounds(st, tw, th, x0, y0, x1, y1);
    const float opacityClamped = clamp01(opacity);
    for (int y = y0; y <= y1; ++y) {
        for (int x = x0; x <= x1; ++x) {
            // Dest offset from the stamp centre, in tile pixels.
            const float du = (x + 0.5f - st.x) / st.scale;
            const float dv = (y + 0.5f - st.y) / st.scale;
            // Inverse rotation (positive angle = clockwise on screen).
            const float su = cx + du * ca + dv * sa;
            const float sv = cy - du * sa + dv * ca;
            const int tu = static_cast<int>(std::floor(su));
            const int tv = static_cast<int>(std::floor(sv));
            if (tu < 0 || tv < 0 || tu >= tw || tv >= th) continue;
            const Pixel t = tile.get(tu, tv);
            if (t.a == 0) continue;
            Pixel o = dst.get(x, y);
            if (alphaLock && o.a == 0) continue;  // T5 alpha lock
            const float sAlpha = (t.a / 255.0f) * opacityClamped;
            const float ia = 1.0f - sAlpha;
            o.r = q8(t.r * opacityClamped + o.r * ia);
            o.g = q8(t.g * opacityClamped + o.g * ia);
            o.b = q8(t.b * opacityClamped + o.b * ia);
            o.a = q8(t.a * opacityClamped + o.a * ia);
            clampPremult(o);
            dst.set(x, y, o);
        }
    }
}

}  // namespace openclip
