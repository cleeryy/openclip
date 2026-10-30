// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip B1 engine — layer texture height field + modulation
// (ARCHITECTURE.md "B1 additions" §5). Clean-room, deterministic.
#include "openclip/texture.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "openclip/canvas.h"
#include "openclip/surface.h"

namespace openclip {
namespace {

constexpr double kPi = 3.14159265358979323846;

inline float clamp01f(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

// Deterministic integer hash -> [0,1). Clean-room (no std::hash: its
// output is implementation-defined, the field must be stable everywhere).
inline float hash2(int x, int y, std::uint32_t seed) {
    std::uint32_t h = static_cast<std::uint32_t>(x) * 374761393u +
                      static_cast<std::uint32_t>(y) * 668265263u +
                      seed * 2246822519u;
    h = (h ^ (h >> 13)) * 1274126177u;
    h ^= h >> 16;
    return static_cast<float>(h & 0xFFFFFFu) / 16777216.0f;
}

inline float smooth(float t) { return t * t * (3.0f - 2.0f * t); }

// Bilinear value noise at (x,y).
float valueNoise(float x, float y, std::uint32_t seed) {
    const int xi = static_cast<int>(std::floor(x));
    const int yi = static_cast<int>(std::floor(y));
    const float fx = smooth(x - xi);
    const float fy = smooth(y - yi);
    const float a = hash2(xi, yi, seed);
    const float b = hash2(xi + 1, yi, seed);
    const float c = hash2(xi, yi + 1, seed);
    const float d = hash2(xi + 1, yi + 1, seed);
    return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy;
}

// Multi-octave value noise (amplitude 0.5, frequency x2 per octave).
float fbm(float x, float y, int octaves, std::uint32_t seed) {
    float sum = 0.0f, amp = 0.5f, norm = 0.0f;
    for (int i = 0; i < octaves; ++i) {
        sum += valueNoise(x, y, seed + static_cast<std::uint32_t>(i) * 17u) *
               amp;
        norm += amp;
        x *= 2.0f;
        y *= 2.0f;
        amp *= 0.5f;
    }
    return norm > 0 ? sum / norm : 0.0f;
}

std::uint8_t toByte(float v) {
    const long r = std::lround(clamp01f(v) * 255.0f);
    return static_cast<std::uint8_t>(r < 0 ? 0 : (r > 255 ? 255 : r));
}

// Minimal JSON readers (M3 rule: unknown keys ignored).
bool jsonNumber(const std::string& j, const char* key, double& out) {
    const std::string pat = "\"" + std::string(key) + "\"";
    const std::size_t at = j.find(pat);
    if (at == std::string::npos) return false;
    const std::size_t colon = j.find(':', at + pat.size());
    if (colon == std::string::npos) return false;
    const char* p = j.c_str() + colon + 1;
    char* endp = nullptr;
    const double v = std::strtod(p, &endp);
    if (endp == p) return false;
    out = v;
    return true;
}

bool jsonString(const std::string& j, const char* key, std::string& out) {
    const std::string pat = "\"" + std::string(key) + "\"";
    const std::size_t at = j.find(pat);
    if (at == std::string::npos) return false;
    const std::size_t colon = j.find(':', at + pat.size());
    if (colon == std::string::npos) return false;
    const std::size_t q1 = j.find('"', colon + 1);
    if (q1 == std::string::npos) return false;
    const std::size_t q2 = j.find('"', q1 + 1);
    if (q2 == std::string::npos) return false;
    out = j.substr(q1 + 1, q2 - q1 - 1);
    return true;
}

bool knownKind(const std::string& kind) {
    return kind == "paper" || kind == "canvas" || kind == "grain";
}

}  // namespace

bool parseTextureSpec(const std::string& json, TextureSpec& out,
                      std::string& error) {
    error.clear();
    if (json.find('{') == std::string::npos ||
        json.find('}') == std::string::npos) {
        error = "texture: malformed JSON";
        return false;
    }
    TextureSpec spec;
    std::string kind;
    if (jsonString(json, "kind", kind) && !kind.empty()) spec.kind = kind;
    if (!knownKind(spec.kind)) {
        error = "texture: unknown kind (paper|canvas|grain)";
        return false;
    }
    double v = 0;
    if (jsonNumber(json, "scale", v))
        spec.scale = static_cast<float>(std::max(0.25, std::min(8.0, v)));
    if (jsonNumber(json, "strength", v))
        spec.strength =
            static_cast<float>(std::max(0.0, std::min(1.0, v)));
    if (jsonNumber(json, "seed", v))
        spec.seed = static_cast<std::uint32_t>(
            std::lround(std::max(0.0, std::min(4294967295.0, v))));
    out = spec;
    return true;
}

bool parseTextureFiche(const Layer& l, TextureSpec& out,
                       std::string& error) {
    error.clear();
    if (l.textureKind.empty()) {
        error = "texture: no fiche";
        return false;
    }
    if (!knownKind(l.textureKind)) {
        error = "texture: unknown kind";
        return false;
    }
    TextureSpec spec;
    spec.kind = l.textureKind;
    // params span: "scale|strength|seed" (unparsable = defaults).
    const std::string& p = l.textureParams;
    const std::size_t b1 = p.find('|');
    const std::size_t b2 = b1 == std::string::npos ? std::string::npos
                                                   : p.find('|', b1 + 1);
    auto num = [](const std::string& s, double& v) {
        if (s.empty()) return false;
        char* endp = nullptr;
        const double d = std::strtod(s.c_str(), &endp);
        if (endp == s.c_str()) return false;
        v = d;
        return true;
    };
    double v = 0;
    if (b1 != std::string::npos && num(p.substr(0, b1), v))
        spec.scale = static_cast<float>(std::max(0.25, std::min(8.0, v)));
    if (b1 != std::string::npos && b2 != std::string::npos &&
        num(p.substr(b1 + 1, b2 - b1 - 1), v))
        spec.strength = static_cast<float>(std::max(0.0, std::min(1.0, v)));
    if (b2 != std::string::npos && num(p.substr(b2 + 1), v))
        spec.seed = static_cast<std::uint32_t>(
            std::lround(std::max(0.0, std::min(4294967295.0, v))));
    out = spec;
    return true;
}

bool renderTextureHeight(const TextureSpec& spec, int w, int h,
                         std::vector<std::uint8_t>& out,
                         std::string& error) {
    error.clear();
    if (w <= 0 || h <= 0) {
        error = "texture: empty canvas";
        return false;
    }
    if (!knownKind(spec.kind)) {
        error = "texture: unknown kind";
        return false;
    }
    try {
        out.assign(static_cast<std::size_t>(w) * static_cast<std::size_t>(h),
                   0);
    } catch (...) {
        error = "texture: out of memory";
        return false;
    }
    const float scale = std::max(0.25f, std::min(8.0f, spec.scale));
    const float strength = std::max(0.0f, std::min(1.0f, spec.strength));
    if (strength <= 0.0f) {
        std::fill(out.begin(), out.end(), static_cast<std::uint8_t>(128));
        return true;  // identity modulation (0.5 -> factor 1)
    }
    const float seedf = static_cast<float>(spec.seed & 0xFFFFu);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            float v = 0.5f;
            if (spec.kind == "paper") {
                // Fibre noise (two octave sets) + faint directional
                // streaks: matte drawing paper.
                const float nx = x / (6.0f * scale);
                const float ny = y / (6.0f * scale);
                v = 0.62f * fbm(nx, ny, 4, spec.seed + 3u);
                v += 0.23f * fbm(nx * 3.1f + seedf, ny * 3.1f, 2,
                                 spec.seed + 11u);
                const float streak =
                    std::sin((x + 6.0f * fbm(nx * 0.5f, ny * 4.0f, 2,
                                             spec.seed + 29u)) *
                             3.14159265f / (2.2f * scale));
                v += 0.15f * (0.5f + 0.5f * streak);
            } else if (spec.kind == "canvas") {
                // Woven grid: two sinus axes warped by low noise.
                const float warp =
                    (fbm(x / (24.0f * scale), y / (24.0f * scale), 2,
                         spec.seed + 5u) -
                     0.5f) *
                    4.0f * scale;
                const float wx = (x + warp) * 3.14159265f / (4.0f * scale);
                const float wy = (y + warp) * 3.14159265f / (4.0f * scale);
                const float weave = 0.5f + 0.25f * std::sin(wx) +
                                    0.25f * std::sin(wy);
                const float tooth =
                    fbm(x / (2.0f * scale), y / (2.0f * scale), 2,
                        spec.seed + 13u);
                v = 0.78f * weave + 0.22f * tooth;
            } else {  // grain
                v = 0.55f * fbm(x / (2.5f * scale), y / (2.5f * scale), 3,
                                spec.seed + 7u);
                v += 0.45f * hash2(x, y, spec.seed + 97u);
            }
            out[static_cast<std::size_t>(y) * w + x] = toByte(v);
        }
    }
    return true;
}

std::uint8_t textureShade(std::uint8_t channel, std::uint8_t alpha,
                          std::uint8_t height, float strength) {
    if (alpha == 0) return 0;
    const float a = alpha / 255.0f;
    // Premultiplied input -> straight colour.
    float c = (channel / 255.0f) / a;
    if (c > 1.0f) c = 1.0f;
    const float hnorm = height / 255.0f;
    const float f = 1.0f + strength * ((0.5f - hnorm) * 2.0f);
    float out = c * f;
    if (out < 0.0f) out = 0.0f;
    if (out > 1.0f) out = 1.0f;
    long v = std::lround(out * a * 255.0f);
    if (v < 0) v = 0;
    if (v > alpha) v = alpha;
    return static_cast<std::uint8_t>(v);
}

void applyTexture(Surface& s, const TextureSpec& spec) {
    if (spec.strength <= 0.0f || s.empty()) return;
    std::vector<std::uint8_t> height;
    std::string err;
    if (!renderTextureHeight(spec, s.width(), s.height(), height, err)) return;
    const float strength = std::max(0.0f, std::min(1.0f, spec.strength));
    std::vector<std::uint8_t>& px = s.bytes();
    const int w = s.width(), h = s.height();
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const std::size_t i =
                (static_cast<std::size_t>(y) * w + x) * 4;
            const std::uint8_t a = px[i + 3];
            if (a == 0) continue;
            const std::uint8_t hv = height[static_cast<std::size_t>(y) * w + x];
            px[i + 0] = textureShade(px[i + 0], a, hv, strength);
            px[i + 1] = textureShade(px[i + 1], a, hv, strength);
            px[i + 2] = textureShade(px[i + 2], a, hv, strength);
        }
    }
}

}  // namespace openclip
