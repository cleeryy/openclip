// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip M6 engine — vector path store: JSON parse + resample,
// rasterize-at-composite, eraser intersection, per-layer path undo
// (clean-room, no SVG dependency).
#include "openclip/vector.h"

#include <cmath>
#include <cstdlib>

#include "openclip/canvas.h"
#include "openclip/surface.h"

namespace openclip {
namespace {

inline float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

// Width model shared with the brush lane: base full width 16px at
// pressure 1 (radius 8), 0.25x at pressure 0, modulated by the canonical
// pressure-curve exponent.
inline float widthAt(float pressure, float curveExp) {
    const float t = clamp01(pressure);
    const float m = (curveExp <= 0.0f) ? t : std::pow(t, curveExp);
    return 16.0f * (0.25f + 0.75f * m);
}

bool parseDoubleList(const std::string& json, std::size_t from,
                     const char* key, double& out) {
    const std::string pat = std::string("\"") + key + "\"";
    const std::size_t at = json.find(pat, from);
    if (at == std::string::npos) return false;
    const std::size_t colon = json.find(':', at + pat.size());
    if (colon == std::string::npos) return false;
    const char* p = json.c_str() + colon + 1;
    char* endp = nullptr;
    const double v = std::strtod(p, &endp);
    if (endp == p) return false;
    out = v;
    return true;
}

// Squared distance between segments ab and cd (2D).
float segSegDist2(Vec2 a, Vec2 b, Vec2 c, Vec2 d) {
    const float ux = b.x - a.x, uy = b.y - a.y;
    const float vx = d.x - c.x, vy = d.y - c.y;
    const float wx = a.x - c.x, wy = a.y - c.y;
    const float uu = ux * ux + uy * uy, vv = vx * vx + vy * vy;
    const float uv = ux * vx + uy * vy, uw = ux * wx + uy * wy;
    const float vw = vx * wx + vy * wy;
    const float den = uu * vv - uv * uv;
    float s = 0, t = 0;
    if (den > 1e-9f) {
        s = (uv * vw - vv * uw) / den;
        t = (uu * vw - uv * uw) / den;
        if (s < 0) s = 0; else if (s > 1) s = 1;
        if (t < 0) t = 0; else if (t > 1) t = 1;
    } else {
        // Parallel: sample endpoints.
        s = 0; t = 0;
    }
    const float px = (a.x + ux * s) - (c.x + vx * t);
    const float py = (a.y + uy * s) - (c.y + vy * t);
    return px * px + py * py;
}

bool pathsIntersect(const VectorPath& a, const VectorPath& b, float pad) {
    if (a.pts.size() < 1 || b.pts.size() < 1) return false;
    const float pad2 = pad * pad;
    // Single-point degenerate cases: point-to-point distance.
    for (std::size_t i = 0; i + 1 < a.pts.size() || i == 0; ++i) {
        const Vec2 a0 = a.pts[i];
        const Vec2 a1 = (i + 1 < a.pts.size()) ? a.pts[i + 1] : a.pts[i];
        const bool aDeg = (a0.x == a1.x && a0.y == a1.y);
        for (std::size_t j = 0; j + 1 < b.pts.size() || j == 0; ++j) {
            const Vec2 b0 = b.pts[j];
            const Vec2 b1 = (j + 1 < b.pts.size()) ? b.pts[j + 1] : b.pts[j];
            float d2;
            if (aDeg && b0.x == b1.x && b0.y == b1.y) {
                const float dx = a0.x - b0.x, dy = a0.y - b0.y;
                d2 = dx * dx + dy * dy;
            } else {
                d2 = segSegDist2(a0, a1, b0, b1);
            }
            if (d2 <= pad2) return true;
            if (aDeg) break;
            if (i + 1 >= a.pts.size()) break;
        }
        if (i + 1 >= a.pts.size()) break;
    }
    return false;
}

// Hard round stamp, source-over, opaque straight color premultiplied.
void stampDisc(Surface& out, float cx, float cy, float radius,
               std::uint8_t r, std::uint8_t g, std::uint8_t b) {
    if (radius < 0.5f) radius = 0.5f;
    const int x0 = static_cast<int>(std::floor(cx - radius));
    const int x1 = static_cast<int>(std::ceil(cx + radius));
    const int y0 = static_cast<int>(std::floor(cy - radius));
    const int y1 = static_cast<int>(std::ceil(cy + radius));
    for (int y = y0; y <= y1; ++y) {
        for (int x = x0; x <= x1; ++x) {
            const float dx = (x + 0.5f) - cx;
            const float dy = (y + 0.5f) - cy;
            if (std::sqrt(dx * dx + dy * dy) > radius) continue;
            out.set(x, y, Pixel{r, g, b, 255});
        }
    }
}

}  // namespace

void VectorHistory::push(const std::vector<VectorPath>& v) {
    stack.push_back(v);
    if (stack.size() > 64) stack.erase(stack.begin());
}

bool VectorHistory::pop(std::vector<VectorPath>& v) {
    if (stack.empty()) return false;
    v = std::move(stack.back());
    stack.pop_back();
    return true;
}

bool parseVectorPathJson(const std::string& pathJson, float curveExp,
                         const std::string& kind, VectorPath& path,
                         std::string& error) {
    VectorPath p;
    p.kind = (kind == "eraser-stroke") ? "eraser-stroke" : "stroke";
    // Collect raw pts: scan for {"x":..,"y":..,"p":..} objects.
    std::vector<Vec2> raw;
    std::vector<float> rawP;
    std::size_t pos = 0;
    while (true) {
        const std::size_t ob = pathJson.find('{', pos);
        if (ob == std::string::npos) break;
        const std::size_t cb = pathJson.find('}', ob);
        if (cb == std::string::npos) break;
        const std::string obj = pathJson.substr(ob, cb - ob + 1);
        double x = 0, y = 0, pr = 1;
        if (parseDoubleList(obj, 0, "x", x) && parseDoubleList(obj, 0, "y", y)) {
            parseDoubleList(obj, 0, "p", pr);  // default pressure 1
            raw.push_back(Vec2{static_cast<float>(x), static_cast<float>(y)});
            rawP.push_back(clamp01(static_cast<float>(pr)));
        }
        pos = cb + 1;
    }
    if (raw.empty()) {
        error = "vector: no pts parsed";
        return false;
    }
    // Resample polyline to ~8px steps (M6 contract), widths interpolated.
    constexpr float kStep = 8.0f;
    p.pts.push_back(raw[0]);
    p.widthProfile.push_back(widthAt(rawP[0], curveExp));
    for (std::size_t i = 1; i < raw.size(); ++i) {
        const float dx = raw[i].x - raw[i - 1].x;
        const float dy = raw[i].y - raw[i - 1].y;
        const float dist = std::sqrt(dx * dx + dy * dy);
        int n = static_cast<int>(std::ceil(dist / kStep));
        if (n < 1) n = 1;
        if (n > 4096) n = 4096;
        for (int k = 1; k <= n; ++k) {
            const float t = static_cast<float>(k) / n;
            p.pts.push_back(
                Vec2{raw[i - 1].x + dx * t, raw[i - 1].y + dy * t});
            const float pr = rawP[i - 1] + (rawP[i] - rawP[i - 1]) * t;
            p.widthProfile.push_back(widthAt(pr, curveExp));
        }
    }
    path = std::move(p);
    return true;
}

int appendVectorPath(Canvas& canvas, VectorHistory& history, std::size_t li,
                     const std::string& pathJson, float curveExp,
                     const std::string& kind, std::string& error) {
    if (li >= canvas.layerCount()) {
        error = "vector: bad layer";
        return -1;
    }
    VectorPath p;
    if (!parseVectorPathJson(pathJson, curveExp, kind, p, error)) return -1;
    history.push(canvas.layer(li).vectorData);
    canvas.layer(li).vectorData.push_back(std::move(p));
    canvas.layer(li).isVector = true;
    return static_cast<int>(canvas.layer(li).vectorData.size());
}

int eraseVectorPaths(Canvas& canvas, VectorHistory& history, std::size_t li,
                     const std::string& pathJson, std::string& error) {
    if (li >= canvas.layerCount()) {
        error = "vector: bad layer";
        return -1;
    }
    VectorPath eraser;
    std::string perr;
    if (!parseVectorPathJson(pathJson, 1.0f, "stroke", eraser, perr)) {
        error = perr;
        return -1;
    }
    std::vector<VectorPath>& v = canvas.layer(li).vectorData;
    // Eraser pad: half of widest path width + 2px tolerance.
    float pad = 2.0f;
    for (const auto& q : v)
        for (float w : q.widthProfile) pad = std::max(pad, w * 0.5f + 2.0f);
    std::vector<VectorPath> kept;
    int removed = 0;
    for (const auto& q : v) {
        if (pathsIntersect(q, eraser, pad)) {
            ++removed;
        } else {
            kept.push_back(q);
        }
    }
    if (removed > 0) {
        history.push(v);
        v = std::move(kept);
    }
    return removed;
}

bool undoVectorPath(Canvas& canvas, VectorHistory& history, std::size_t li) {
    if (li >= canvas.layerCount()) return false;
    std::vector<VectorPath> prev;
    if (!history.pop(prev)) return false;
    canvas.layer(li).vectorData = std::move(prev);
    return true;
}

void rasterizeVectorPaths(const std::vector<VectorPath>& paths, Surface& out,
                          std::uint8_t r, std::uint8_t g, std::uint8_t b) {
    out.clear();
    constexpr float kSpacing = 2.0f;
    for (const auto& p : paths) {
        if (p.pts.empty()) continue;
        const bool eraser = (p.kind == "eraser-stroke");
        if (p.pts.size() == 1) {
            const float w = p.widthProfile.empty() ? 8.0f : p.widthProfile[0];
            if (eraser) {
                // Destination-out single stamp: clear the disc.
                const float rad = std::max(0.5f, w * 0.5f);
                const int x0 = static_cast<int>(std::floor(p.pts[0].x - rad));
                const int x1 = static_cast<int>(std::ceil(p.pts[0].x + rad));
                const int y0 = static_cast<int>(std::floor(p.pts[0].y - rad));
                const int y1 = static_cast<int>(std::ceil(p.pts[0].y + rad));
                for (int y = y0; y <= y1; ++y)
                    for (int x = x0; x <= x1; ++x) {
                        const float dx = (x + 0.5f) - p.pts[0].x;
                        const float dy = (y + 0.5f) - p.pts[0].y;
                        if (std::sqrt(dx * dx + dy * dy) <= rad)
                            out.set(x, y, Pixel{0, 0, 0, 0});
                    }
            } else {
                stampDisc(out, p.pts[0].x, p.pts[0].y, w * 0.5f, r, g, b);
            }
            continue;
        }
        // Walk segments, stamping every ~2px with interpolated width.
        for (std::size_t i = 1; i < p.pts.size(); ++i) {
            const Vec2 a = p.pts[i - 1], bpt = p.pts[i];
            const float wa = (i - 1 < p.widthProfile.size())
                                 ? p.widthProfile[i - 1]
                                 : 8.0f;
            const float wb =
                (i < p.widthProfile.size()) ? p.widthProfile[i] : 8.0f;
            const float dx = bpt.x - a.x, dy = bpt.y - a.y;
            const float dist = std::sqrt(dx * dx + dy * dy);
            int n = static_cast<int>(std::ceil(dist / kSpacing));
            if (n < 1) n = 1;
            if (n > 4096) n = 4096;
            for (int k = 0; k <= n; ++k) {
                const float t = (n == 0) ? 0.0f : static_cast<float>(k) / n;
                const float x = a.x + dx * t, y = a.y + dy * t;
                const float w = wa + (wb - wa) * t;
                if (eraser) {
                    const float rad = std::max(0.5f, w * 0.5f);
                    const int x0 = static_cast<int>(std::floor(x - rad));
                    const int x1 = static_cast<int>(std::ceil(x + rad));
                    const int y0 = static_cast<int>(std::floor(y - rad));
                    const int y1 = static_cast<int>(std::ceil(y + rad));
                    for (int yy = y0; yy <= y1; ++yy)
                        for (int xx = x0; xx <= x1; ++xx) {
                            const float ddx = (xx + 0.5f) - x;
                            const float ddy = (yy + 0.5f) - y;
                            if (std::sqrt(ddx * ddx + ddy * ddy) <= rad)
                                out.set(xx, yy, Pixel{0, 0, 0, 0});
                        }
                } else {
                    stampDisc(out, x, y, w * 0.5f, r, g, b);
                }
            }
        }
    }
}

}  // namespace openclip
