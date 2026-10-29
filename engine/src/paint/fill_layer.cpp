// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip T5 engine — fill-layer parse + render (see fill_layer.h).
#include "openclip/fill_layer.h"

#include <algorithm>
#include <cmath>

#include "openclip/canvas.h"
#include "openclip/surface.h"

namespace openclip {
namespace {

bool hexByte(char hi, char lo, std::uint8_t& v) {
    auto hv = [](char c) -> int {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    };
    const int h = hv(hi), l = hv(lo);
    if (h < 0 || l < 0) return false;
    v = static_cast<std::uint8_t>(h * 16 + l);
    return true;
}

bool parseHex(const std::string& s, std::uint8_t& r, std::uint8_t& g,
              std::uint8_t& b) {
    if (s.size() != 7 || s[0] != '#') return false;
    return hexByte(s[1], s[2], r) && hexByte(s[3], s[4], g) &&
           hexByte(s[5], s[6], b);
}

double numField(const std::string& span, std::size_t field, double fallback) {
    std::size_t start = 0;
    for (std::size_t i = 0; i < field; ++i) {
        const std::size_t bar = span.find('|', start);
        if (bar == std::string::npos) return fallback;
        start = bar + 1;
    }
    const std::size_t bar = span.find('|', start);
    const std::string tok =
        bar == std::string::npos ? span.substr(start) : span.substr(start, bar - start);
    if (tok.empty()) return fallback;
    try {
        return std::stod(tok);
    } catch (...) {
        return fallback;
    }
}

std::string strField(const std::string& span, std::size_t field,
                     const std::string& fallback) {
    std::size_t start = 0;
    for (std::size_t i = 0; i < field; ++i) {
        const std::size_t bar = span.find('|', start);
        if (bar == std::string::npos) return fallback;
        start = bar + 1;
    }
    const std::size_t bar = span.find('|', start);
    const std::string tok =
        bar == std::string::npos ? span.substr(start) : span.substr(start, bar - start);
    return tok.empty() ? fallback : tok;
}

// Fixed-seed value-Perlin (from scratch, same spirit as the T4
// perlinClouds grain): integer lattice hash -> quintic fade -> fBm.
// Deterministic: same (x, y) always yields the same value.
float latticeHash(int x, int y) {
    // Fixed constants (no seed parameter: the fiche has no seed key —
    // the grain is a fixed texture, documented in ARCHITECTURE.md).
    // Unsigned lattice mixing on purpose: keeping the products in `int`
    // would be signed overflow (UB) even though GCC wraps in practice.
    unsigned h = static_cast<unsigned>(x) * 374761393u +
                 static_cast<unsigned>(y) * 668265263u;
    h = (h ^ (h >> 13)) * 1274126177u;
    h ^= h >> 16;
    return (h & 0xffffffu) / static_cast<float>(0x1000000u);
}

float valueNoise(float x, float y) {
    const int xi = static_cast<int>(std::floor(x));
    const int yi = static_cast<int>(std::floor(y));
    const float xf = x - xi, yf = y - yi;
    const float u = xf * xf * xf * (xf * (xf * 6 - 15) + 10);
    const float v = yf * yf * yf * (yf * (yf * 6 - 15) + 10);
    const float a = latticeHash(xi, yi), b = latticeHash(xi + 1, yi);
    const float c = latticeHash(xi, yi + 1), d = latticeHash(xi + 1, yi + 1);
    return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v;
}

// Hue (0..360) -> straight RGB (0..255), full saturation/value.
void hueToRgb(float hue, float& r, float& g, float& b) {
    float h = std::fmod(hue, 360.0f);
    if (h < 0) h += 360.0f;
    h /= 60.0f;
    const int sector = static_cast<int>(h) % 6;
    const float f = h - std::floor(h);
    const float q = 1.0f - f;
    switch (sector) {
        case 0: r = 1; g = f; b = 0; break;
        case 1: r = q; g = 1; b = 0; break;
        case 2: r = 0; g = 1; b = f; break;
        case 3: r = 0; g = q; b = 1; break;
        case 4: r = f; g = 0; b = 1; break;
        default: r = 1; g = 0; b = q; break;
    }
    r *= 255.0f;
    g *= 255.0f;
    b *= 255.0f;
}

}  // namespace

bool parseFillSpec(const Layer& l, FillSpec& spec, std::string& error) {
    spec = FillSpec();
    if (l.fillKind != "solid" && l.fillKind != "gradient" &&
        l.fillKind != "foil") {
        error = "fill: unknown kind '" + l.fillKind + "'";
        return false;
    }
    spec.kind = l.fillKind;
    if (!l.fillColor.empty()) {
        std::uint8_t r, g, b;
        if (!parseHex(l.fillColor, r, g, b)) {
            error = "fill: bad color '" + l.fillColor + "'";
            return false;
        }
        spec.r = r;
        spec.g = g;
        spec.b = b;
    }
    // params span: mode|x0|y0|x1|y1|hue|scale|shine
    spec.mode = strField(l.fillParams, 0, "linear");
    if (spec.mode != "linear" && spec.mode != "radial") {
        error = "fill: bad gradient mode '" + spec.mode + "'";
        return false;
    }
    spec.x0 = static_cast<float>(numField(l.fillParams, 1, 0.0));
    spec.y0 = static_cast<float>(numField(l.fillParams, 2, 0.0));
    spec.x1 = static_cast<float>(numField(l.fillParams, 3, 1.0));
    spec.y1 = static_cast<float>(numField(l.fillParams, 4, 0.0));
    spec.hue = static_cast<float>(numField(l.fillParams, 5, 0.0));
    if (spec.hue < 0) spec.hue = 0;
    if (spec.hue > 360) spec.hue = 360;
    spec.scale = static_cast<float>(numField(l.fillParams, 6, 4.0));
    if (spec.scale < 1) spec.scale = 1;
    if (spec.scale > 16) spec.scale = 16;
    spec.shine = static_cast<float>(numField(l.fillParams, 7, 0.5));
    if (spec.shine < 0) spec.shine = 0;
    if (spec.shine > 1) spec.shine = 1;
    if (spec.kind == "gradient") {
        // Canonical span "#RRGGBB:pos,..." (the shim writes it sorted;
        // the engine re-sorts defensively and validates 2..8).
        std::size_t start = 0;
        while (start < l.fillStops.size()) {
            const std::size_t comma = l.fillStops.find(',', start);
            const std::string tok = l.fillStops.substr(
                start, comma == std::string::npos ? std::string::npos
                                                  : comma - start);
            const std::size_t colon = tok.find(':');
            if (colon == std::string::npos || colon != 7) {
                error = "fill: bad stop '" + tok + "'";
                return false;
            }
            std::uint8_t r, g, b;
            if (!parseHex(tok.substr(0, 7), r, g, b)) {
                error = "fill: bad stop color '" + tok + "'";
                return false;
            }
            float pos = 0;
            try {
                pos = std::stof(tok.substr(8));
            } catch (...) {
                error = "fill: bad stop pos '" + tok + "'";
                return false;
            }
            if (pos < 0) pos = 0;
            if (pos > 1) pos = 1;
            spec.stops.push_back(FillStop{r, g, b, pos});
            if (comma == std::string::npos) break;
            start = comma + 1;
        }
        if (spec.stops.size() < 2 || spec.stops.size() > 8) {
            error = "fill: gradient needs 2..8 stops";
            return false;
        }
        std::sort(spec.stops.begin(), spec.stops.end(),
                  [](const FillStop& a, const FillStop& b) {
                      return a.pos < b.pos;
                  });
    }
    return true;
}

bool renderFill(const FillSpec& spec, int w, int h, Surface& out,
                std::string& error) {
    out.resize(w, h);
    out.clear();
    if (w <= 0 || h <= 0) {
        error = "fill: empty surface";
        return false;
    }
    if (spec.kind == "solid") {
        for (int y = 0; y < h; ++y)
            for (int x = 0; x < w; ++x)
                out.set(x, y, Pixel{spec.r, spec.g, spec.b, 255});
        return true;
    }
    if (spec.kind == "gradient") {
        if (spec.stops.size() < 2) {
            error = "fill: gradient needs 2..8 stops";
            return false;
        }
        float dx = spec.x1 - spec.x0, dy = spec.y1 - spec.y0;
        const float len2 = dx * dx + dy * dy;
        const bool radial = spec.mode == "radial";
        if ((radial && len2 <= 0) || (!radial && len2 <= 0)) {
            error = "fill: degenerate gradient geometry";
            return false;
        }
        const float radius = radial ? std::sqrt(len2) : 0.0f;
        if (radial) {
            dx /= radius;
            dy /= radius;
        }
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                float t;
                if (radial) {
                    const float px = (x + 0.5f) - spec.x0;
                    const float py = (y + 0.5f) - spec.y0;
                    t = (px * dx + py * dy) / radius;
                } else {
                    const float px = (x + 0.5f) - spec.x0;
                    const float py = (y + 0.5f) - spec.y0;
                    t = (px * dx + py * dy) / len2;
                }
                if (t < 0) t = 0;
                if (t > 1) t = 1;
                const FillStop* lo = &spec.stops.front();
                const FillStop* hi = &spec.stops.back();
                for (const auto& s : spec.stops) {
                    if (s.pos <= t) lo = &s;
                    if (s.pos >= t) {
                        hi = &s;
                        break;
                    }
                }
                float r = lo->r, g = lo->g, b = lo->b;
                if (hi != lo && hi->pos > lo->pos) {
                    const float k = (t - lo->pos) / (hi->pos - lo->pos);
                    r = lo->r + (hi->r - lo->r) * k;
                    g = lo->g + (hi->g - lo->g) * k;
                    b = lo->b + (hi->b - lo->b) * k;
                }
                out.set(x, y,
                        Pixel{static_cast<std::uint8_t>(std::lround(r)),
                              static_cast<std::uint8_t>(std::lround(g)),
                              static_cast<std::uint8_t>(std::lround(b)),
                              255});
            }
        }
        return true;
    }
    if (spec.kind == "foil") {
        // Iridescent ramp (hue rotates along the diagonal) x grain
        // (2-octave value noise, fixed seed) x diagonal clear bands.
        const float diag = static_cast<float>(w + h);
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                const float u = diag > 0 ? (x + y) / diag : 0;
                float hr, hg, hb;
                hueToRgb(spec.hue + u * 360.0f, hr, hg, hb);
                const float n =
                    valueNoise(x / spec.scale, y / spec.scale) * 0.65f +
                    valueNoise(x / (spec.scale * 0.37f) + 7.3f,
                               y / (spec.scale * 0.37f) + 3.1f) * 0.35f;
                const float band =
                    0.5f + 0.5f * std::sin((x - y) * 0.35f + u * 6.2831f);
                const float k = (1.0f - spec.shine * 0.5f) +
                                spec.shine * (0.35f * n + 0.65f * band);
                auto qc = [](float v) -> std::uint8_t {
                    long q = std::lround(v);
                    if (q < 0) q = 0;
                    if (q > 255) q = 255;
                    return static_cast<std::uint8_t>(q);
                };
                out.set(x, y, Pixel{qc(hr * k), qc(hg * k), qc(hb * k),
                                    255});
            }
        }
        return true;
    }
    error = "fill: unknown kind '" + spec.kind + "'";
    return false;
}

}  // namespace openclip
