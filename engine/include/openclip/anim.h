// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip T3 — keyframe interpolation + camera core (clean-room).
// Pure data logic (no Canvas/History dependency, same spirit as
// cels.h) so engine tests lock the semantics: easing, sampling,
// shortest-path angle lerp, bilinear resample, camera crop.
// JSON parsing lives here too (M3 rule: unknown fields ignored,
// missing = defaults, out-of-range = clamped) so the shim and the
// tests share one implementation (ARCHITECTURE.md "T3 additions").
#ifndef OPENCLIP_ANIM_H
#define OPENCLIP_ANIM_H

#include <cmath>
#include <cstdint>
#include <string>
#include <vector>

#include "openclip/surface.h"

namespace openclip {

// Interpolated per-layer transform at one cel (ARCHITECTURE.md §T3
// §1): translation px, scale about the layer center, clockwise
// rotation degrees about the center, opacity factor multiplying the
// layer opacity.
struct LayerKeys {
    float dx = 0.0f, dy = 0.0f;
    float scale = 1.0f;
    float angle = 0.0f;    // degrees, clockwise
    float opacity = 1.0f;  // 0..1
};

// One keyframe: cel index + held values + segment ease TOWARD the
// next key (last key's ease is unused; a lone key = hold everywhere).
struct AnimKey {
    int cel = 0;
    LayerKeys keys;
    std::string ease;  // linear|ease-in|ease-out|smooth|step
};

// Camera keyframe (ARCHITECTURE.md §T3 §2): watched center in engine
// px + zoom + optional clockwise angle. Same ease vocabulary.
struct CameraKey {
    int cel = 0;
    float x = 0.0f, y = 0.0f;
    float zoom = 1.0f;   // 0.25..8
    float angle = 0.0f;  // degrees, clockwise, default 0
    std::string ease;
};

// Bounds (spec §1.4/§2.1, clamped never rejected).
inline constexpr float kAnimScaleMin = 0.1f;
inline constexpr float kAnimScaleMax = 8.0f;
inline constexpr float kCamZoomMin = 0.25f;
inline constexpr float kCamZoomMax = 8.0f;

inline float clampAnimScale(float s) {
    if (!(s >= kAnimScaleMin)) return s < kAnimScaleMin ? kAnimScaleMin : 1.0f;
    if (s > kAnimScaleMax) return kAnimScaleMax;
    return s;
}

inline float clamp01(float v) {
    if (!(v >= 0.0f)) return 0.0f;
    if (v > 1.0f) return 1.0f;
    return v;
}

inline float clampCamZoom(float z) {
    if (!(z >= kCamZoomMin)) return z < kCamZoomMin ? kCamZoomMin : 1.0f;
    if (z > kCamZoomMax) return kCamZoomMax;
    return z;
}

// Ease application on segment t in [0,1]. Unknown names = linear
// (the M3 unknown-key rule applied to values too).
inline double applyEase(const std::string& ease, double t) {
    if (t <= 0.0) return 0.0;
    if (t >= 1.0) return 1.0;
    if (ease == "step") return 0.0;  // hold the left key whole segment
    if (ease == "ease-in") return t * t;
    if (ease == "ease-out") return 1.0 - (1.0 - t) * (1.0 - t);
    if (ease == "smooth") return t * t * (3.0 - 2.0 * t);
    return t;  // linear + unknown
}

inline bool validEase(const std::string& e) {
    return e == "linear" || e == "ease-in" || e == "ease-out" ||
           e == "smooth" || e == "step";
}

// Shortest-path angle delta in degrees, in (-180,180].
inline double angleDelta(double from, double to) {
    double d = std::fmod(to - from, 360.0);
    if (d <= -180.0) d += 360.0;
    if (d > 180.0) d -= 360.0;
    return d;
}

// Sample a sorted-by-cel key track at a fractional cel. Empty track =
// false (caller keeps the layer byte-identical). Single key or
// outside range = nearest hold (no extrapolation, spec §1.1).
inline bool sampleAnimTrack(const std::vector<AnimKey>& keys, double cel,
                            LayerKeys& out) {
    if (keys.empty()) return false;
    if (keys.size() == 1 || cel <= keys.front().cel) {
        out = keys.front().keys;
        return true;
    }
    if (cel >= keys.back().cel) {
        out = keys.back().keys;
        return true;
    }
    std::size_t i = 0;
    while (i + 1 < keys.size() && keys[i + 1].cel <= cel) ++i;
    const AnimKey& a = keys[i];
    const AnimKey& b = keys[i + 1];
    const double span = static_cast<double>(b.cel) - a.cel;
    const double t = span <= 0.0 ? 0.0 : (cel - a.cel) / span;
    const double e = applyEase(a.ease, t);
    out.dx = static_cast<float>(a.keys.dx + (b.keys.dx - a.keys.dx) * e);
    out.dy = static_cast<float>(a.keys.dy + (b.keys.dy - a.keys.dy) * e);
    out.scale = static_cast<float>(a.keys.scale + (b.keys.scale - a.keys.scale) * e);
    out.angle = static_cast<float>(a.keys.angle +
                                   angleDelta(a.keys.angle, b.keys.angle) * e);
    out.opacity =
        static_cast<float>(a.keys.opacity + (b.keys.opacity - a.keys.opacity) * e);
    return true;
}

struct CameraSample {
    float x = 0.0f, y = 0.0f, zoom = 1.0f, angle = 0.0f;
    bool on = false;
};

// Sample a camera track. Empty = {on:false} (caller keeps the manual
// view byte-identical). Same hold/outside semantics as §1.
inline CameraSample sampleCameraTrack(const std::vector<CameraKey>& keys,
                                      double cel) {
    CameraSample out;
    if (keys.empty()) return out;
    out.on = true;
    if (keys.size() == 1 || cel <= keys.front().cel) {
        const CameraKey& k = cel >= keys.back().cel ? keys.back() : keys.front();
        out.x = k.x;
        out.y = k.y;
        out.zoom = k.zoom;
        out.angle = k.angle;
        return out;
    }
    if (cel >= keys.back().cel) {
        out.x = keys.back().x;
        out.y = keys.back().y;
        out.zoom = keys.back().zoom;
        out.angle = keys.back().angle;
        return out;
    }
    std::size_t i = 0;
    while (i + 1 < keys.size() && keys[i + 1].cel <= cel) ++i;
    const CameraKey& a = keys[i];
    const CameraKey& b = keys[i + 1];
    const double span = static_cast<double>(b.cel) - a.cel;
    const double t = span <= 0.0 ? 0.0 : (cel - a.cel) / span;
    const double e = applyEase(a.ease, t);
    out.x = static_cast<float>(a.x + (b.x - a.x) * e);
    out.y = static_cast<float>(a.y + (b.y - a.y) * e);
    out.zoom = static_cast<float>(a.zoom + (b.zoom - a.zoom) * e);
    out.angle =
        static_cast<float>(a.angle + angleDelta(a.angle, b.angle) * e);
    return out;
}

// Bilinear sample of a premultiplied surface; outside = transparent.
// Exact integer coords return the exact pixel (identity resample is
// byte-identical, locked by test).
inline Pixel sampleBilinear(const Surface& s, double x, double y) {
    const int w = s.width(), h = s.height();
    if (w <= 0 || h <= 0) return Pixel{0, 0, 0, 0};
    if (x < 0.0 || y < 0.0 || x > w - 1 || y > h - 1) {
        // Edge-exact: integer coords on the border are exact.
        const int xi = static_cast<int>(std::lround(x));
        const int yi = static_cast<int>(std::lround(y));
        if (std::fabs(x - xi) < 1e-9 && std::fabs(y - yi) < 1e-9 &&
            xi >= 0 && yi >= 0 && xi < w && yi < h)
            return s.get(xi, yi);
        return Pixel{0, 0, 0, 0};
    }
    const int x0 = static_cast<int>(std::floor(x));
    const int y0 = static_cast<int>(std::floor(y));
    const double fx = x - x0, fy = y - y0;
    if (fx == 0.0 && fy == 0.0) return s.get(x0, y0);
    const int x1 = x0 + 1 < w ? x0 + 1 : x0;
    const int y1 = y0 + 1 < h ? y0 + 1 : y0;
    const Pixel p00 = s.get(x0, y0), p10 = s.get(x1, y0);
    const Pixel p01 = s.get(x0, y1), p11 = s.get(x1, y1);
    Pixel o;
    const double w00 = (1 - fx) * (1 - fy), w10 = fx * (1 - fy);
    const double w01 = (1 - fx) * fy, w11 = fx * fy;
    o.r = static_cast<std::uint8_t>(std::lround(
        p00.r * w00 + p10.r * w10 + p01.r * w01 + p11.r * w11));
    o.g = static_cast<std::uint8_t>(std::lround(
        p00.g * w00 + p10.g * w10 + p01.g * w01 + p11.g * w11));
    o.b = static_cast<std::uint8_t>(std::lround(
        p00.b * w00 + p10.b * w10 + p01.b * w01 + p11.b * w11));
    o.a = static_cast<std::uint8_t>(std::lround(
        p00.a * w00 + p10.a * w10 + p01.a * w01 + p11.a * w11));
    // Premultiplied safety: rgb never exceeds a after blending.
    if (o.r > o.a) o.r = o.a;
    if (o.g > o.a) o.g = o.a;
    if (o.b > o.a) o.b = o.a;
    return o;
}

// Inverse-map one destination pixel through a layer sample (about the
// surface center) into source coords. Identity sample maps (x,y)->(x,y)
// exactly (double arithmetic, no-op rotation/scale/translate).
inline void animInverseMap(double x, double y, double cx, double cy,
                           const LayerKeys& k, double& sx, double& sy) {
    double px = x - cx - k.dx;
    double py = y - cy - k.dy;
    if (k.angle != 0.0f) {
        const double rad = -static_cast<double>(k.angle) * M_PI / 180.0;
        const double c = std::cos(rad), s = std::sin(rad);
        const double rx = px * c - py * s;
        const double ry = px * s + py * c;
        px = rx;
        py = ry;
    }
    if (k.scale != 1.0f && k.scale > 0.0f) {
        px /= k.scale;
        py /= k.scale;
    }
    sx = px + cx;
    sy = py + cy;
}

// Camera crop (ARCHITECTURE.md §T3 §2): dst keeps src dimensions; the
// watched rect (w/zoom x h/zoom about (x,y), rotated by angle) is
// resampled over the full dst. Outside = transparent hors-champ
// fill (documented; stickers use matte instead).
inline void applyCameraCrop(const Surface& src, const CameraSample& cam,
                            Surface& dst) {
    const int w = src.width(), h = src.height();
    dst.resize(w, h);
    dst.clear();
    if (w <= 0 || h <= 0 || !cam.on) return;
    const double zoom = cam.zoom < kCamZoomMin ? kCamZoomMin : cam.zoom;
    const double rad = -static_cast<double>(cam.angle) * M_PI / 180.0;
    const double c = std::cos(rad), s = std::sin(rad);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            // Dest -> watched-rect coords (rect is w/zoom wide).
            double px = (x - w / 2.0) / zoom;
            double py = (y - h / 2.0) / zoom;
            const double rx = px * c - py * s;
            const double ry = px * s + py * c;
            const double sx = cam.x + rx;
            const double sy = cam.y + ry;
            const Pixel p = sampleBilinear(src, sx, sy);
            if (p.a != 0) dst.set(x, y, p);
        }
    }
}

// Parse helpers shared by shim + tests. Minimal fixed-schema JSON
// scan (no dependency): finds the "keys" array, reads per-object
// cel/dx/dy/scale/angle/opacity/ease (layer) or
// cel/x/y/zoom/angle/ease (camera). Unknown fields ignored, missing
// = defaults, out-of-range clamped; keys sorted by cel. False +
// untouched out on unparsable input (no "keys" array, malformed
// numbers) — never throws.
bool parseAnimTrack(const std::string& json, std::vector<AnimKey>& out);
bool parseCameraTrack(const std::string& json,
                      std::vector<CameraKey>& out);

// Serialize back (nLayerAnim / nCameraTrack read path).
std::string animTrackJson(const std::vector<AnimKey>& keys);
std::string cameraTrackJson(const std::vector<CameraKey>& keys);

}  // namespace openclip

#endif
