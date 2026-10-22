// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip M6 engine — vector path store: JSON parse + resample,
// rasterize-at-composite, eraser intersection, per-layer path undo
// (clean-room, no SVG dependency).
#include "openclip/vector.h"

#include <algorithm>
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

// T2: extract a top-level or style-wrapped string key ("tip", "color").
// Looks for "style":{...} first, then the top level; M3 rule.
bool styleStr(const std::string& json, const char* key, std::string& out) {
    const std::string pat = std::string("\"") + key + "\"";
    const std::size_t st = json.find("\"style\"");
    if (st != std::string::npos) {
        const std::size_t so = json.find('{', st + 7);
        if (so != std::string::npos) {
            // Bound the style object (brace-depth walk): only keys
            // strictly inside it count as style keys.
            int dep = 0;
            std::size_t se = std::string::npos;
            bool inStr = false;
            for (std::size_t k = so;
                 k < std::min(json.size(), so + 4096); ++k) {
                const char c = json[k];
                if (c == '"' && (k == 0 || json[k - 1] != '\\'))
                    inStr = !inStr;
                if (inStr) continue;
                if (c == '{') ++dep;
                if (c == '}') {
                    --dep;
                    if (dep == 0) {
                        se = k;
                        break;
                    }
                }
            }
            const std::size_t at = json.find(pat, so);
            if (at != std::string::npos && se != std::string::npos &&
                at < se) {
                const std::size_t colon = json.find(':', at + pat.size());
                if (colon != std::string::npos && colon < se) {
                    const std::size_t q1 = json.find('"', colon + 1);
                    if (q1 != std::string::npos && q1 < se) {
                        const std::size_t q2 = json.find('"', q1 + 1);
                        if (q2 != std::string::npos && q2 < se) {
                            out = json.substr(q1 + 1, q2 - q1 - 1);
                            return true;
                        }
                    }
                }
            }
        }
    }
    const std::size_t at = json.find(pat);
    if (at == std::string::npos) return false;
    const std::size_t colon = json.find(':', at + pat.size());
    if (colon == std::string::npos) return false;
    const std::size_t q1 = json.find('"', colon + 1);
    if (q1 == std::string::npos) return false;
    const std::size_t q2 = json.find('"', q1 + 1);
    if (q2 == std::string::npos) return false;
    out = json.substr(q1 + 1, q2 - q1 - 1);
    return true;
}

bool styleNum(const std::string& json, const char* key, double& out) {
    const std::string pat = std::string("\"") + key + "\"";
    const std::size_t at = json.find(pat);
    if (at == std::string::npos) {
        // Try inside "style".
        const std::size_t st = json.find("\"style\"");
        if (st == std::string::npos) return false;
        const std::size_t at2 = json.find(pat, st);
        if (at2 == std::string::npos) return false;
        return parseDoubleList(json, at2, key, out);
    }
    return parseDoubleList(json, at, key, out);
}

// Parse "#RRGGBB" (tolerant: leading '#', short "#RGB" expanded).
// False = unparsable (caller keeps session ink).
bool parseHexColor(const std::string& s, std::uint8_t& r, std::uint8_t& g,
                   std::uint8_t& b) {
    std::string h = s;
    if (!h.empty() && h[0] == '#') h = h.substr(1);
    auto hex = [](char c) -> int {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    };
    if (h.size() == 3) {
        int v[3];
        for (int i = 0; i < 3; ++i) {
            v[i] = hex(h[i]);
            if (v[i] < 0) return false;
        }
        r = static_cast<std::uint8_t>(v[0] * 17);
        g = static_cast<std::uint8_t>(v[1] * 17);
        b = static_cast<std::uint8_t>(v[2] * 17);
        return true;
    }
    if (h.size() == 6 || h.size() == 8) {
        int v[3] = {0, 0, 0};
        for (int i = 0; i < 3; ++i) {
            const int hi = hex(h[i * 2]), lo = hex(h[i * 2 + 1]);
            if (hi < 0 || lo < 0) return false;
            v[i] = hi * 16 + lo;
        }
        r = static_cast<std::uint8_t>(v[0]);
        g = static_cast<std::uint8_t>(v[1]);
        b = static_cast<std::uint8_t>(v[2]);
        return true;
    }
    return false;
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
    // T2 style keys (M3 rule — unknown ignored, bad = defaults).
    std::string tip;
    if (styleStr(pathJson, "tip", tip))
        p.tip = (tip == "flat") ? "flat" : "round";
    double sz = 1.0;
    if (styleNum(pathJson, "size", sz))
        p.sizeMul = (sz >= 0.25 && sz <= 4.0) ? static_cast<float>(sz)
                                             : 1.0f;
    std::string col;
    if (styleStr(pathJson, "color", col)) {
        std::uint8_t cr = 0, cg = 0, cb = 0;
        if (parseHexColor(col, cr, cg, cb)) {
            p.r = cr;
            p.g = cg;
            p.b = cb;
        }
    }
    // Corners: "corners":[0/1...] sized to the RAW points when present
    // (resampling below spreads each raw corner over its span).
    std::vector<char> rawCorners;
    {
        const std::size_t at = pathJson.find("\"corners\"");
        if (at != std::string::npos) {
            const std::size_t ob = pathJson.find('[', at);
            const std::size_t cb =
                (ob == std::string::npos)
                    ? std::string::npos
                    : pathJson.find(']', ob);
            if (ob != std::string::npos && cb != std::string::npos) {
                const std::string arr = pathJson.substr(ob + 1, cb - ob - 1);
                std::size_t q = 0;
                while (q < arr.size() && rawCorners.size() < raw.size()) {
                    while (q < arr.size() &&
                           arr[q] != '0' && arr[q] != '1')
                        ++q;
                    if (q >= arr.size()) break;
                    rawCorners.push_back(arr[q] == '1' ? 1 : 0);
                    ++q;
                }
            }
        }
    }
    p.corners.assign(p.pts.size(), 0);
    if (rawCorners.size() == raw.size() && raw.size() > 1) {
        // Spread: resampled point k of span i inherits raw corner i+1
        // (span starts inherit the span start, endpoints exact).
        p.corners[0] = rawCorners[0];
        std::size_t dst = 1;
        for (std::size_t i = 1; i < raw.size() && dst < p.pts.size();
             ++i) {
            const float dx = raw[i].x - raw[i - 1].x;
            const float dy = raw[i].y - raw[i - 1].y;
            int n = static_cast<int>(std::ceil(
                std::sqrt(dx * dx + dy * dy) / kStep));
            if (n < 1) n = 1;
            if (n > 4096) n = 4096;
            for (int k = 1; k <= n && dst < p.pts.size(); ++k, ++dst)
                p.corners[dst] = rawCorners[i];
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

// Resample an already-parsed polyline to ~8px steps (shared by the
// connect op: seam-joined raw points go through the same M6 grid).
void resampleRaw(const std::vector<Vec2>& raw, const std::vector<float>& rawW,
                 VectorPath& out) {
    out.pts.clear();
    out.widthProfile.clear();
    if (raw.empty()) return;
    constexpr float kStep = 8.0f;
    out.pts.push_back(raw[0]);
    out.widthProfile.push_back(rawW.empty() ? 8.0f : rawW[0]);
    for (std::size_t i = 1; i < raw.size(); ++i) {
        const float dx = raw[i].x - raw[i - 1].x;
        const float dy = raw[i].y - raw[i - 1].y;
        const float dist = std::sqrt(dx * dx + dy * dy);
        int n = static_cast<int>(std::ceil(dist / kStep));
        if (n < 1) n = 1;
        if (n > 4096) n = 4096;
        const float w0 = (i - 1 < rawW.size()) ? rawW[i - 1] : 8.0f;
        const float w1 = (i < rawW.size()) ? rawW[i] : 8.0f;
        for (int k = 1; k <= n; ++k) {
            const float t = static_cast<float>(k) / n;
            out.pts.push_back(
                Vec2{raw[i - 1].x + dx * t, raw[i - 1].y + dy * t});
            out.widthProfile.push_back(w0 + (w1 - w0) * t);
        }
    }
}

bool replaceVectorPath(Canvas& canvas, VectorHistory& history, std::size_t li,
                       std::size_t id, const std::string& pathJson,
                       float curveExp, std::string& error) {
    if (li >= canvas.layerCount()) {
        error = "vector: bad layer";
        return false;
    }
    std::vector<VectorPath>& v = canvas.layer(li).vectorData;
    if (id >= v.size()) {
        error = "vector: bad id";
        return false;
    }
    VectorPath p;
    if (!parseVectorPathJson(pathJson, curveExp, "stroke", p, error))
        return false;
    // Keep the old style unless the JSON re-applies it (M3 keys).
    const VectorPath& old = v[id];
    std::string tip;
    const bool hasTip = styleStr(pathJson, "tip", tip);
    double sz = 1.0;
    const bool hasSize = styleNum(pathJson, "size", sz);
    std::string col;
    const bool hasColor = styleStr(pathJson, "color", col);
    if (!hasTip) p.tip = old.tip;
    if (!hasSize) p.sizeMul = old.sizeMul;
    if (!hasColor) {
        p.r = old.r;
        p.g = old.g;
        p.b = old.b;
    }
    p.kind = old.kind;  // replace never converts ink<->eraser
    if (p.corners.size() != p.pts.size()) p.corners.assign(p.pts.size(), 0);
    history.push(v);
    v[id] = std::move(p);
    return true;
}

bool connectVectorPaths(Canvas& canvas, VectorHistory& history, std::size_t li,
                        std::size_t a, std::size_t b, float tol,
                        std::string& error) {
    if (li >= canvas.layerCount()) {
        error = "vector: bad layer";
        return false;
    }
    std::vector<VectorPath>& v = canvas.layer(li).vectorData;
    if (a >= v.size() || b >= v.size() || a == b) {
        error = "vector: bad id";
        return false;
    }
    if (!(tol > 0)) tol = 12.0f;
    const VectorPath& pa = v[a];
    const VectorPath& pb = v[b];
    if (pa.pts.empty() || pb.pts.empty()) {
        error = "vector: empty path";
        return false;
    }
    const Vec2 endsA[2] = {pa.pts.front(), pa.pts.back()};
    const Vec2 endsB[2] = {pb.pts.front(), pb.pts.back()};
    // Seam-minimizing orientation: pick the closest endpoint pair.
    float best = -1;
    int bestA = 1, bestB = 0;  // default: a.end -> b.start
    for (int i = 0; i < 2; ++i)
        for (int j = 0; j < 2; ++j) {
            const float dx = endsA[i].x - endsB[j].x;
            const float dy = endsA[i].y - endsB[j].y;
            const float d2 = dx * dx + dy * dy;
            if (best < 0 || d2 < best) {
                best = d2;
                bestA = i;
                bestB = j;
            }
        }
    if (best < 0 || std::sqrt(best) > tol) {
        error = "vector: endpoints too far";
        return false;
    }
    // Raw chains (un-resample: width = profile, pressure inverted
    // through widthAt — resampleRaw re-lays the M6 grid below).
    auto rawOf = [](const VectorPath& p, bool rev, std::vector<Vec2>& pts,
                    std::vector<float>& w) {
        pts.clear();
        w.clear();
        if (!rev) {
            pts = p.pts;
            w = p.widthProfile;
        } else {
            pts.assign(p.pts.rbegin(), p.pts.rend());
            w.assign(p.widthProfile.rbegin(), p.widthProfile.rend());
        }
    };
    std::vector<Vec2> ra, rb;
    std::vector<float> wa, wb;
    rawOf(pa, bestA == 0, ra, wa);
    rawOf(pb, bestB == 1, rb, wb);
    ra.insert(ra.end(), rb.begin(), rb.end());
    wa.insert(wa.end(), wb.begin(), wb.end());
    VectorPath joined;
    joined.kind = pa.kind;
    joined.tip = pa.tip;
    joined.sizeMul = pa.sizeMul;
    joined.r = pa.r;
    joined.g = pa.g;
    joined.b = pa.b;
    resampleRaw(ra, wa, joined);
    joined.corners.assign(joined.pts.size(), 0);
    history.push(v);
    v[a] = std::move(joined);
    // Erasing b shifts later siblings down (positional ids, documented).
    v.erase(v.begin() + static_cast<long>(b));
    return true;
}

bool setVectorWidths(Canvas& canvas, VectorHistory& history, std::size_t li,
                     std::size_t id, const std::vector<float>& widths,
                     std::string& error) {
    if (li >= canvas.layerCount()) {
        error = "vector: bad layer";
        return false;
    }
    std::vector<VectorPath>& v = canvas.layer(li).vectorData;
    if (id >= v.size()) {
        error = "vector: bad id";
        return false;
    }
    VectorPath& p = v[id];
    if (widths.size() != p.pts.size() || p.pts.empty()) {
        error = "vector: widths size mismatch";
        return false;
    }
    history.push(v);
    p.widthProfile = widths;
    for (float& w : p.widthProfile) {
        if (!(w >= 0.5f)) w = 0.5f;
        if (w > 128.0f) w = 128.0f;
    }
    return true;
}

// Square stamp (flat tip): same hard coverage as the disc, square
// footprint (corners, not demi-discs at path ends).
void stampSquare(Surface& out, float cx, float cy, float half,
                 std::uint8_t r, std::uint8_t g, std::uint8_t b) {
    if (half < 0.5f) half = 0.5f;
    const int x0 = static_cast<int>(std::floor(cx - half));
    const int x1 = static_cast<int>(std::ceil(cx + half));
    const int y0 = static_cast<int>(std::floor(cy - half));
    const int y1 = static_cast<int>(std::ceil(cy + half));
    for (int y = y0; y <= y1; ++y)
        for (int x = x0; x <= x1; ++x) out.set(x, y, Pixel{r, g, b, 255});
}

void rasterizeVectorPaths(const std::vector<VectorPath>& paths,
                          Surface& out) {
    out.clear();
    constexpr float kSpacing = 2.0f;
    for (const auto& p : paths) {
        if (p.pts.empty()) continue;
        const bool eraser = (p.kind == "eraser-stroke");
        const bool flat = (!eraser && p.tip == "flat");
        const float mul =
            (p.sizeMul >= 0.25f && p.sizeMul <= 4.0f) ? p.sizeMul : 1.0f;
        auto stamp = [&](float x, float y, float w) {
            if (flat)
                stampSquare(out, x, y, w * 0.5f * mul, p.r, p.g, p.b);
            else
                stampDisc(out, x, y, w * 0.5f * mul, p.r, p.g, p.b);
        };
        if (p.pts.size() == 1) {
            const float w = p.widthProfile.empty() ? 8.0f : p.widthProfile[0];
            if (eraser) {
                // Destination-out single stamp: clear the disc.
                const float rad = std::max(0.5f, w * 0.5f * mul);
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
                stamp(p.pts[0].x, p.pts[0].y, w);
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
                    const float rad = std::max(0.5f, w * 0.5f * mul);
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
                    stamp(x, y, w);
                }
            }
        }
    }
}

}  // namespace openclip
