// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip M3 engine — named filters (clean-room, from scratch).
#include "openclip/filters.h"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <vector>

#include "openclip/surface.h"

namespace openclip {
namespace {

// --- Minimal flat-JSON param parsing (engine-side, no dependencies) ---

static const char* skipWs(const char* p, const char* end) {
    while (p < end && (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r'))
        ++p;
    return p;
}

// Locate the value start for a top-level "key" (exact match). Returns
// nullptr when absent or malformed. `quoted` is set true when the value
// begins with '"'.
static const char* findValue(const std::string& js, const std::string& key,
                             bool& quoted) {
    const std::string q = "\"" + key + "\"";
    std::size_t pos = js.find(q);
    if (pos == std::string::npos) return nullptr;
    const char* beg = js.data();
    const char* end = beg + js.size();
    const char* p = skipWs(beg + pos + q.size(), end);
    if (p >= end || *p != ':') return nullptr;
    p = skipWs(p + 1, end);
    if (p >= end) return nullptr;
    quoted = (*p == '"');
    return p;
}

static bool jsonNum(const std::string& js, const std::string& key, double& out) {
    bool quoted = false;
    const char* p = findValue(js, key, quoted);
    if (!p || quoted) return false;
    char* e = nullptr;
    const double v = std::strtod(p, &e);
    if (e == p) return false;
    out = v;
    return true;
}

static bool jsonStr(const std::string& js, const std::string& key,
                    std::string& out) {
    bool quoted = false;
    const char* p = findValue(js, key, quoted);
    if (!p || !quoted) return false;
    const char* end = js.data() + js.size();
    ++p;  // opening quote
    std::string r;
    while (p < end && *p != '"') {
        if (*p == '\\' && p + 1 < end) {
            ++p;
            r.push_back(*p == 'n' ? '\n' : *p);
        } else {
            r.push_back(*p);
        }
        ++p;
    }
    if (p >= end) return false;
    out = r;
    return true;
}

// --- Pixel helpers (RGBA bytes, row-major, premultiplied rgb <= a) ---

static inline int clampI(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

static inline std::uint8_t clamp255(double v) {
    const long r = std::lround(v);
    return static_cast<std::uint8_t>(r < 0 ? 0 : (r > 255 ? 255 : r));
}

// Clamp-to-edge channel read.
static inline std::uint8_t pxAt(const std::vector<std::uint8_t>& b, int w, int h,
                                int x, int y, int c) {
    x = clampI(x, 0, w - 1);
    y = clampI(y, 0, h - 1);
    return b[(static_cast<std::size_t>(y) * w + x) * 4 + c];
}

// Gaussian kernel weights for offsets 0..half (symmetric), sigma > 0.
static std::vector<double> gaussKernel(double sigma, int& half) {
    half = static_cast<int>(std::ceil(3.0 * sigma));
    if (half < 1) half = 1;
    if (half > 128) half = 128;
    std::vector<double> k(half + 1);
    double sum = 0;
    for (int i = 0; i <= half; ++i) {
        k[i] = std::exp(-(i * i) / (2.0 * sigma * sigma));
        sum += (i == 0) ? k[i] : 2.0 * k[i];
    }
    for (double& v : k) v /= sum;
    return k;
}

// Separable Gaussian blur of RGB planes into float buffer (clamp-to-edge).
static void blurSeparable(const std::vector<std::uint8_t>& in, int w, int h,
                          double sigma, std::vector<float>& out) {
    int half = 1;
    const std::vector<double> k = gaussKernel(sigma, half);
    std::vector<float> tmp(static_cast<std::size_t>(w) * h * 3, 0.0f);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x)
            for (int c = 0; c < 3; ++c) {
                double acc = k[0] * pxAt(in, w, h, x, y, c);
                for (int i = 1; i <= half; ++i)
                    acc += k[i] * (pxAt(in, w, h, x - i, y, c) +
                                   pxAt(in, w, h, x + i, y, c));
                tmp[(static_cast<std::size_t>(y) * w + x) * 3 + c] =
                    static_cast<float>(acc);
            }
    out.assign(static_cast<std::size_t>(w) * h * 3, 0.0f);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x)
            for (int c = 0; c < 3; ++c) {
                double acc = k[0] * tmp[(static_cast<std::size_t>(y) * w + x) * 3 + c];
                for (int i = 1; i <= half; ++i)
                    acc += k[i] *
                           (tmp[(static_cast<std::size_t>(clampI(y - i, 0, h - 1)) * w + x) * 3 + c] +
                            tmp[(static_cast<std::size_t>(clampI(y + i, 0, h - 1)) * w + x) * 3 + c]);
                out[(static_cast<std::size_t>(y) * w + x) * 3 + c] =
                    static_cast<float>(acc);
            }
}

static bool fGaussian(const std::vector<std::uint8_t>& in, int w, int h,
                      double radius, std::vector<std::uint8_t>& out,
                      std::string& error) {
    if (!(radius > 0.0) || radius > 64.0) {
        error = "gaussianBlur: radius must be in (0,64]";
        return false;
    }
    std::vector<float> bl;
    blurSeparable(in, w, h, radius, bl);
    out = in;
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x)
            for (int c = 0; c < 3; ++c)
                out[(static_cast<std::size_t>(y) * w + x) * 4 + c] =
                    clamp255(bl[(static_cast<std::size_t>(y) * w + x) * 3 + c]);
    return true;
}

static bool fSharpen(const std::vector<std::uint8_t>& in, int w, int h,
                     double amount, std::vector<std::uint8_t>& out,
                     std::string& error) {
    if (!(amount >= 0.0) || amount > 8.0) {
        error = "sharpen: amount must be in [0,8]";
        return false;
    }
    std::vector<float> bl;
    blurSeparable(in, w, h, 1.0, bl);
    out = in;
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x)
            for (int c = 0; c < 3; ++c) {
                const std::size_t i4 = (static_cast<std::size_t>(y) * w + x) * 4;
                const std::size_t i3 = (static_cast<std::size_t>(y) * w + x) * 3;
                out[i4 + c] = clamp255(in[i4 + c] + amount * (in[i4 + c] - bl[i3 + c]));
            }
    return true;
}

static bool fMotion(const std::vector<std::uint8_t>& in, int w, int h,
                    double lengthD, double angleD, std::vector<std::uint8_t>& out,
                    std::string& error) {
    const long n = std::lround(lengthD);
    if (n < 1 || n > 512) {
        error = "motionBlur: length must be in [1,512]";
        return false;
    }
    const double rad = angleD * 3.141592653589793 / 180.0;
    const double dx = std::cos(rad), dy = std::sin(rad);
    out = in;
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x)
            for (int c = 0; c < 3; ++c) {
                double acc = 0;
                for (long t = 0; t < n; ++t) {
                    const double off = t - (n - 1) / 2.0;
                    acc += pxAt(in, w, h,
                                static_cast<int>(std::lround(x + dx * off)),
                                static_cast<int>(std::lround(y + dy * off)), c);
                }
                out[(static_cast<std::size_t>(y) * w + x) * 4 + c] =
                    clamp255(acc / n);
            }
    return true;
}

static bool fRadial(const std::vector<std::uint8_t>& in, int w, int h,
                    double strength, double cx, double cy,
                    std::vector<std::uint8_t>& out, std::string& error) {
    if (!(strength >= 0.0) || strength > 4.0) {
        error = "radialBlur: strength must be in [0,4]";
        return false;
    }
    constexpr int kSamples = 8;
    out = in;
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x)
            for (int c = 0; c < 3; ++c) {
                double acc = 0;
                for (int k = 0; k < kSamples; ++k) {
                    const double s = 1.0 + strength * (k - 3.5) / kSamples;
                    acc += pxAt(in, w, h,
                                static_cast<int>(std::lround(cx + (x - cx) * s)),
                                static_cast<int>(std::lround(cy + (y - cy) * s)),
                                c);
                }
                out[(static_cast<std::size_t>(y) * w + x) * 4 + c] =
                    clamp255(acc / kSamples);
            }
    return true;
}

static bool fPosterize(const std::vector<std::uint8_t>& in, double levelsD,
                       std::vector<std::uint8_t>& out, std::string& error) {
    const long lv = std::lround(levelsD);
    if (lv < 2 || lv > 256) {
        error = "posterize: levels must be in [2,256]";
        return false;
    }
    const double L = static_cast<double>(lv);
    out = in;
    for (std::size_t i = 0; i < out.size(); i += 4)
        for (int c = 0; c < 3; ++c) {
            const double step = 255.0 / (L - 1.0);
            out[i + c] = clamp255(std::floor(in[i + c] / 255.0 * (L - 1.0) + 0.5) * step);
        }
    return true;
}

static bool fLevels(const std::vector<std::uint8_t>& in, double blackD,
                    double whiteD, std::vector<std::uint8_t>& out,
                    std::string& error) {
    const double black = std::min(std::max(blackD, 0.0), 255.0);
    const double white = std::min(std::max(whiteD, 0.0), 255.0);
    if (!(white > black)) {
        error = "levels: white must be greater than black";
        return false;
    }
    out = in;
    for (std::size_t i = 0; i < out.size(); i += 4)
        for (int c = 0; c < 3; ++c)
            out[i + c] = clamp255((in[i + c] - black) * 255.0 / (white - black));
    return true;
}

static bool fChroma(const std::vector<std::uint8_t>& in, int w, int h,
                    double shiftD, std::vector<std::uint8_t>& out,
                    std::string& error) {
    const long sh = std::lround(shiftD);
    if (sh < 0 || sh > 64) {
        error = "chromaticAberration: shift must be in [0,64]";
        return false;
    }
    out = in;
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const std::size_t i = (static_cast<std::size_t>(y) * w + x) * 4;
            out[i] = pxAt(in, w, h, x - static_cast<int>(sh), y, 0);
            out[i + 2] = pxAt(in, w, h, x + static_cast<int>(sh), y, 2);
        }
    return true;
}

static bool fNoise(const std::vector<std::uint8_t>& in, int w, int h,
                   double amountD, bool mono, std::vector<std::uint8_t>& out,
                   std::string& error) {
    if (!(amountD >= 0.0) || amountD > 255.0) {
        error = "addNoise: amount must be in [0,255]";
        return false;
    }
    const int amt = static_cast<int>(std::lround(amountD));
    std::uint32_t st = 0;  // fixed seed: identical noise on every call
    auto next = [&]() -> int {
        st = st * 1664525u + 1013904223u;
        const int span = 2 * amt + 1;
        return span <= 1 ? 0
                         : static_cast<int>((st >> 16) % static_cast<std::uint32_t>(span)) - amt;
    };
    out = in;
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const std::size_t i = (static_cast<std::size_t>(y) * w + x) * 4;
            if (mono) {
                const int n = next();
                for (int c = 0; c < 3; ++c)
                    out[i + c] = clamp255(static_cast<int>(in[i + c]) + n);
            } else {
                for (int c = 0; c < 3; ++c)
                    out[i + c] = clamp255(static_cast<int>(in[i + c]) + next());
            }
        }
    return true;
}

static bool fMosaic(const std::vector<std::uint8_t>& in, int w, int h,
                    double blockD, std::vector<std::uint8_t>& out,
                    std::string& error) {
    const long b = std::lround(blockD);
    if (b < 1 || b > 1024) {
        error = "mosaic: block must be in [1,1024]";
        return false;
    }
    out = in;
    for (int by = 0; by < h; by += static_cast<int>(b))
        for (int bx = 0; bx < w; bx += static_cast<int>(b)) {
            long sum[3] = {0, 0, 0};
            long n = 0;
            for (int y = by; y < by + b && y < h; ++y)
                for (int x = bx; x < bx + b && x < w; ++x) {
                    const std::size_t i = (static_cast<std::size_t>(y) * w + x) * 4;
                    for (int c = 0; c < 3; ++c) sum[c] += in[i + c];
                    ++n;
                }
            for (int y = by; y < by + b && y < h; ++y)
                for (int x = bx; x < bx + b && x < w; ++x) {
                    const std::size_t i = (static_cast<std::size_t>(y) * w + x) * 4;
                    for (int c = 0; c < 3; ++c)
                        out[i + c] = static_cast<std::uint8_t>(sum[c] / (n > 0 ? n : 1));
                }
        }
    return true;
}

struct Stop {
    std::uint8_t r = 0, g = 0, b = 0;
    double pos = 0;
};

static bool parseStops(const std::string& inner, std::vector<Stop>& stops,
                       std::string& error) {
    // Inner content of a JSON array string: e.g. "#000000:0","#FFFFFF:1".
    stops.clear();
    std::size_t p = 0;
    while (p < inner.size()) {
        while (p < inner.size() && (inner[p] == ' ' || inner[p] == ',')) ++p;
        if (p >= inner.size()) break;
        if (inner[p] != '#') {
            error = "gradientMap: each stop must look like \"#RRGGBB:pos\"";
            return false;
        }
        if (p + 7 >= inner.size()) {
            error = "gradientMap: truncated stop";
            return false;
        }
        Stop s;
        auto hex = [&](char ch) -> int {
            if (ch >= '0' && ch <= '9') return ch - '0';
            if (ch >= 'a' && ch <= 'f') return ch - 'a' + 10;
            if (ch >= 'A' && ch <= 'F') return ch - 'A' + 10;
            return -1;
        };
        int v[6];
        for (int k = 0; k < 6; ++k) {
            v[k] = hex(inner[p + 1 + k]);
            if (v[k] < 0) {
                error = "gradientMap: stop has non-hex color";
                return false;
            }
        }
        s.r = static_cast<std::uint8_t>(v[0] * 16 + v[1]);
        s.g = static_cast<std::uint8_t>(v[2] * 16 + v[3]);
        s.b = static_cast<std::uint8_t>(v[4] * 16 + v[5]);
        std::size_t q = p + 7;
        if (q >= inner.size() || inner[q] != ':') {
            error = "gradientMap: stop missing ':pos'";
            return false;
        }
        ++q;
        char* e = nullptr;
        s.pos = std::strtod(inner.c_str() + q, &e);
        if (e == inner.c_str() + q || !(s.pos >= 0.0) || !(s.pos <= 1.0)) {
            error = "gradientMap: stop pos must be in [0,1]";
            return false;
        }
        p = static_cast<std::size_t>(e - inner.c_str());
        stops.push_back(s);
    }
    if (stops.empty()) {
        error = "gradientMap: need at least one stop";
        return false;
    }
    std::sort(stops.begin(), stops.end(),
              [](const Stop& a, const Stop& b) { return a.pos < b.pos; });
    return true;
}

static bool fGradient(const std::vector<std::uint8_t>& in,
                      const std::string& spec, std::vector<std::uint8_t>& out,
                      std::string& error) {
    // spec is the decoded JSON string: ["#000000:0","#FFFFFF:1"].
    std::string inner = spec;
    const auto lb = inner.find('[');
    const auto rb = inner.rfind(']');
    if (lb == std::string::npos || rb == std::string::npos || rb <= lb) {
        error = "gradientMap: stops must be a \"[#hex:pos,...]\" array string";
        return false;
    }
    inner = inner.substr(lb + 1, rb - lb - 1);
    // Strip quote characters; stops themselves carry no quotes/commas.
    std::string flat;
    for (char ch : inner)
        if (ch != '"' && ch != '\'') flat.push_back(ch);
    std::vector<Stop> stops;
    if (!parseStops(flat, stops, error)) return false;
    out = in;
    for (std::size_t i = 0; i < out.size(); i += 4) {
        const double lum = (0.299 * in[i] + 0.587 * in[i + 1] + 0.114 * in[i + 2]) / 255.0;
        const Stop* lo = &stops.front();
        const Stop* hi = &stops.back();
        for (const auto& s : stops) {
            if (s.pos <= lum) lo = &s;
            if (s.pos >= lum) {
                hi = &s;
                break;
            }
        }
        double t = (hi->pos > lo->pos) ? (lum - lo->pos) / (hi->pos - lo->pos) : 0.0;
        out[i] = clamp255(lo->r + (hi->r - lo->r) * t);
        out[i + 1] = clamp255(lo->g + (hi->g - lo->g) * t);
        out[i + 2] = clamp255(lo->b + (hi->b - lo->b) * t);
    }
    return true;
}

}  // namespace (anonymous helpers)

// --- Registry ---

bool canApply(const std::string& name) {
    for (const auto& n : filterNames())
        if (n == name) return true;
    return false;
}

std::vector<std::string> filterNames() {
    return {"gaussianBlur", "sharpen", "motionBlur", "radialBlur", "posterize",
            "levels", "chromaticAberration", "addNoise", "mosaic",
            "gradientMap"};
}

bool applyFilter(Surface& s, const std::string& name,
                 const std::string& paramsJson, std::string& error) {
    error.clear();
    const int w = s.width(), h = s.height();
    if (w <= 0 || h <= 0) {
        error = "filter: empty surface";
        return false;
    }
    if (!canApply(name)) {
        error = "filter: unknown name '" + name + "'";
        return false;
    }
    const std::vector<std::uint8_t>& in = s.bytes();
    std::vector<std::uint8_t> out;
    double d = 0;
    bool ok = false;
    if (name == "gaussianBlur") {
        double radius = 6.0;
        if (!paramsJson.empty() && jsonNum(paramsJson, "radius", d)) radius = d;
        ok = fGaussian(in, w, h, radius, out, error);
    } else if (name == "sharpen") {
        double amount = 0.5;
        if (!paramsJson.empty() && jsonNum(paramsJson, "amount", d)) amount = d;
        ok = fSharpen(in, w, h, amount, out, error);
    } else if (name == "motionBlur") {
        double length = 12.0, angle = 0.0;
        if (!paramsJson.empty()) {
            if (jsonNum(paramsJson, "length", d)) length = d;
            if (jsonNum(paramsJson, "angle", d)) angle = d;
        }
        ok = fMotion(in, w, h, length, angle, out, error);
    } else if (name == "radialBlur") {
        double strength = 0.5, cx = w / 2.0, cy = h / 2.0;
        if (!paramsJson.empty()) {
            if (jsonNum(paramsJson, "strength", d)) strength = d;
            if (jsonNum(paramsJson, "cx", d)) cx = d;
            if (jsonNum(paramsJson, "cy", d)) cy = d;
        }
        ok = fRadial(in, w, h, strength, cx, cy, out, error);
    } else if (name == "posterize") {
        double levels = 4.0;
        if (!paramsJson.empty() && jsonNum(paramsJson, "levels", d)) levels = d;
        ok = fPosterize(in, levels, out, error);
    } else if (name == "levels") {
        double black = 0.0, white = 255.0;
        if (!paramsJson.empty()) {
            if (jsonNum(paramsJson, "black", d)) black = d;
            if (jsonNum(paramsJson, "white", d)) white = d;
        }
        ok = fLevels(in, black, white, out, error);
    } else if (name == "chromaticAberration") {
        double shift = 3.0;
        if (!paramsJson.empty() && jsonNum(paramsJson, "shift", d)) shift = d;
        ok = fChroma(in, w, h, shift, out, error);
    } else if (name == "addNoise") {
        double amount = 20.0, monochrome = 1.0;
        if (!paramsJson.empty()) {
            if (jsonNum(paramsJson, "amount", d)) amount = d;
            if (jsonNum(paramsJson, "monochrome", d)) monochrome = d;
        }
        ok = fNoise(in, w, h, amount, monochrome != 0.0, out, error);
    } else if (name == "mosaic") {
        double block = 16.0;
        if (!paramsJson.empty() && jsonNum(paramsJson, "block", d)) block = d;
        ok = fMosaic(in, w, h, block, out, error);
    } else if (name == "gradientMap") {
        std::string stops = "[\"#000000:0\",\"#FFFFFF:1\"]";
        if (!paramsJson.empty()) {
            std::string sv;
            if (jsonStr(paramsJson, "stops", sv) && !sv.empty()) stops = sv;
        }
        ok = fGradient(in, stops, out, error);
    }
    if (!ok) return false;  // surface untouched
    // Alpha contract: never modify alpha; clamp RGB to [0, alpha].
    std::vector<std::uint8_t>& dst = s.bytes();
    for (std::size_t i = 0; i + 3 < dst.size() && i + 3 < out.size(); i += 4) {
        const std::uint8_t a = dst[i + 3];
        dst[i] = out[i] > a ? a : out[i];
        dst[i + 1] = out[i + 1] > a ? a : out[i + 1];
        dst[i + 2] = out[i + 2] > a ? a : out[i + 2];
    }
    return true;
}

}  // namespace openclip
