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

// V+3 classic denoise (ARCHITECTURE.md "V+3 additions", clean-room):
// median 3x3 or naive bilateral 3x3, blended src*(1-s)+filtered*s.
// strength 0 = byte-identical early-out (no-op success). Operates on
// premultiplied RGB; the registry clamps to [0,alpha] afterwards.
static bool fDenoise(const std::vector<std::uint8_t>& in, int w, int h,
                     double strength, const std::string& mode,
                     std::vector<std::uint8_t>& out, std::string& error) {
    if (!(strength >= 0.0) || strength > 1.0) {
        error = "denoise: strength must be in [0,1]";
        return false;
    }
    const bool bilateral = (mode == "bilateral");
    if (mode != "median" && !bilateral) {
        error = "denoise: mode must be \"median\" or \"bilateral\"";
        return false;
    }
    out = in;
    if (strength <= 0.0) return true;
    const double s = strength;
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            for (int c = 0; c < 3; ++c) {
                double fv = 0;
                if (!bilateral) {
                    std::uint8_t win[9];
                    int n = 0;
                    for (int dy = -1; dy <= 1; ++dy)
                        for (int dx = -1; dx <= 1; ++dx)
                            win[n++] = pxAt(in, w, h, x + dx, y + dy, c);
                    std::sort(win, win + 9);
                    fv = win[4];
                } else {
                    // Naive bilateral: spatial 3x3, range weight from
                    // the RGB distance to the center pixel (sigma=32).
                    const double cr = pxAt(in, w, h, x, y, 0);
                    const double cg = pxAt(in, w, h, x, y, 1);
                    const double cb = pxAt(in, w, h, x, y, 2);
                    double acc = 0, wsum = 0;
                    for (int dy = -1; dy <= 1; ++dy) {
                        for (int dx = -1; dx <= 1; ++dx) {
                            const double vr = pxAt(in, w, h, x + dx, y + dy, 0);
                            const double vg = pxAt(in, w, h, x + dx, y + dy, 1);
                            const double vb = pxAt(in, w, h, x + dx, y + dy, 2);
                            const double dr = vr - cr, dg = vg - cg, db = vb - cb;
                            const double dist2 = dr * dr + dg * dg + db * db;
                            const double wt = std::exp(-dist2 / (2.0 * 32.0 * 32.0));
                            const double vc = (c == 0) ? vr : (c == 1 ? vg : vb);
                            acc += wt * vc;
                            wsum += wt;
                        }
                    }
                    fv = (wsum > 0) ? acc / wsum : pxAt(in, w, h, x, y, c);
                }
                const double src =
                    static_cast<double>(pxAt(in, w, h, x, y, c));
                out[(static_cast<std::size_t>(y) * w + x) * 4 + c] =
                    clamp255(src * (1.0 - s) + fv * s);
            }
        }
    }
    return true;
}

// T1 unsharp mask (ARCHITECTURE.md "T1 additions", clean-room): true
// parameterizable-radius unsharp mask. out = c + amount*(c-blur) per
// channel when |c-blur| > threshold, else c (threshold calms flat
// areas against noise). radius bounds match gaussianBlur; amount 0 =
// byte-identical early-out (no-op success).
static bool fUnsharp(const std::vector<std::uint8_t>& in, int w, int h,
                     double radius, double amount, double threshD,
                     std::vector<std::uint8_t>& out, std::string& error) {
    if (!(radius > 0.0) || radius > 64.0) {
        error = "unsharp: radius must be in (0,64]";
        return false;
    }
    if (!(amount >= 0.0) || amount > 8.0) {
        error = "unsharp: amount must be in [0,8]";
        return false;
    }
    const double threshold =
        threshD < 0.0 ? 0.0 : (threshD > 255.0 ? 255.0 : threshD);
    out = in;
    if (amount <= 0.0) return true;
    std::vector<float> bl;
    blurSeparable(in, w, h, radius, bl);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x)
            for (int c = 0; c < 3; ++c) {
                const std::size_t i4 =
                    (static_cast<std::size_t>(y) * w + x) * 4;
                const std::size_t i3 =
                    (static_cast<std::size_t>(y) * w + x) * 3;
                const double diff =
                    static_cast<double>(in[i4 + c]) - bl[i3 + c];
                if (std::fabs(diff) <= threshold) continue;
                out[i4 + c] = clamp255(in[i4 + c] + amount * diff);
            }
    return true;
}

// T1 color balance (ARCHITECTURE.md "T1 additions", clean-room):
// "shadows|midtones|highlights":"cr,mv,jb" triplets, each -100..+100.
// Zone weights are soft luma ramps (shadows peak at black,
// highlights at white, midtones at mid gray). Adversary channels:
// cr>0 adds red / removes green+blue (cyan does the reverse),
// mv>0 adds magenta (red+blue) / removes green, jb>0 adds yellow
// (red+green) / removes blue. Deltas are in raw px units (triplet
// value * zone weight); the registry clamps to [0,alpha] after.
static bool parseTriplet(const std::string& s, double v[3],
                         std::string& error) {
    char* e = nullptr;
    const char* p = s.c_str();
    for (int k = 0; k < 3; ++k) {
        while (*p == ' ' || *p == '\t') ++p;
        const double d = std::strtod(p, &e);
        if (e == p || !(d >= -100.0) || !(d <= 100.0)) {
            error = "colorBalance: triplets must be \"cr,mv,jb\" in [-100,100]";
            return false;
        }
        v[k] = d;
        p = e;
        while (*p == ' ' || *p == '\t') ++p;
        if (k < 2) {
            if (*p != ',') {
                error = "colorBalance: triplets must be \"cr,mv,jb\" in [-100,100]";
                return false;
            }
            ++p;
        }
    }
    while (*p == ' ' || *p == '\t') ++p;
    if (*p != '\0') {
        error = "colorBalance: triplets must be \"cr,mv,jb\" in [-100,100]";
        return false;
    }
    return true;
}

static bool fColorBalance(const std::vector<std::uint8_t>& in,
                          const double sh[3], const double mid[3],
                          const double hi[3], std::vector<std::uint8_t>& out) {
    out = in;
    for (std::size_t i = 0; i + 3 < out.size(); i += 4) {
        const double lum =
            (0.299 * in[i] + 0.587 * in[i + 1] + 0.114 * in[i + 2]) / 255.0;
        // Soft QUADRATIC zone ramps (spec §6.2 "recouvrement doux"):
        // shadows peak at black, highlights at white, midtones at mid
        // gray; the square falloff keeps far zones within +/-1 px of
        // identity (locked by filters_balance_shadows_only_dark).
        const double wSh = (1.0 - lum) * (1.0 - lum);
        const double wHi = lum * lum;
        double midW = 1.0 - std::fabs(2.0 * lum - 1.0);
        if (midW < 0.0) midW = 0.0;
        midW *= midW;
        const double zones[3] = {wSh, midW, wHi};
        const double* trips[3] = {sh, mid, hi};
        double dr = 0, dg = 0, db = 0;
        for (int z = 0; z < 3; ++z) {
            const double w = zones[z];
            if (w <= 0.0) continue;
            const double cr = trips[z][0] * w;
            const double mv = trips[z][1] * w;
            const double jb = trips[z][2] * w;
            dr += cr + 0.5 * mv + 0.5 * jb;
            dg += -0.5 * cr - mv + 0.5 * jb;
            db += -0.5 * cr + 0.5 * mv - jb;
        }
        out[i] = clamp255(static_cast<double>(in[i]) + dr);
        out[i + 1] = clamp255(static_cast<double>(in[i + 1]) + dg);
        out[i + 2] = clamp255(static_cast<double>(in[i + 2]) + db);
    }
    return true;
}

// T4 hex color "#RRGGBB" -> rgb bytes. False on malformed input.
static bool parseHex6(const std::string& s, std::uint8_t rgb[3]) {
    if (s.size() != 7 || s[0] != '#') return false;
    auto hex = [](char ch) -> int {
        if (ch >= '0' && ch <= '9') return ch - '0';
        if (ch >= 'a' && ch <= 'f') return ch - 'a' + 10;
        if (ch >= 'A' && ch <= 'F') return ch - 'A' + 10;
        return -1;
    };
    int v[6];
    for (int k = 0; k < 6; ++k) {
        v[k] = hex(s[1 + k]);
        if (v[k] < 0) return false;
    }
    rgb[0] = static_cast<std::uint8_t>(v[0] * 16 + v[1]);
    rgb[1] = static_cast<std::uint8_t>(v[2] * 16 + v[3]);
    rgb[2] = static_cast<std::uint8_t>(v[4] * 16 + v[5]);
    return true;
}

// T4 glow (ARCHITECTURE.md "T4 additions" §2, clean-room): threshold
// the highlights (luma > 0.7), blur them with the existing separable
// gaussian, tint, screen over the source. amount 0 = byte-identical.
static bool fGlow(const std::vector<std::uint8_t>& in, int w, int h,
                  double radius, double amount, const std::string& tintStr,
                  std::vector<std::uint8_t>& out, std::string& error) {
    if (!(radius >= 1.0) || radius > 64.0) {
        error = "glow: radius must be in [1,64]";
        return false;
    }
    if (!(amount >= 0.0) || amount > 2.0) {
        error = "glow: amount must be in [0,2]";
        return false;
    }
    std::uint8_t tint[3] = {255, 255, 255};
    if (!tintStr.empty() && !parseHex6(tintStr, tint)) {
        error = "glow: tint must be \"#RRGGBB\"";
        return false;
    }
    out = in;
    if (amount <= 0.0) return true;
    std::vector<float> bl;
    blurSeparable(in, w, h, radius, bl);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const std::size_t i4 = (static_cast<std::size_t>(y) * w + x) * 4;
            const std::size_t i3 = (static_cast<std::size_t>(y) * w + x) * 3;
            const double lum =
                0.299 * in[i4] + 0.587 * in[i4 + 1] + 0.114 * in[i4 + 2];
            if (lum <= 0.7 * 255.0) continue;
            for (int c = 0; c < 3; ++c) {
                const double g =
                    bl[i3 + c] * tint[c] / 255.0 * amount;
                // screen: 255-(255-src)(255-glow)/255
                out[i4 + c] = clamp255(
                    255.0 - (255.0 - in[i4 + c]) * (255.0 - g) / 255.0);
            }
        }
    return true;
}

// T4 drop shadow (§2): the layer's alpha silhouette shifted by
// (dx,dy), blurred, tinted, composited BEHIND the source. The only
// pack filter reading alpha — and, by design, the only one writing
// it too: where the source is transparent the shadow lands with its
// own composited alpha (source-over of shadow color under source).
// radius 0 = hard shadow.
static bool fDropShadow(const std::vector<std::uint8_t>& in, int w, int h,
                        double dxD, double dyD, double radius,
                        const std::string& colorStr, double opacity,
                        std::vector<std::uint8_t>& out, std::string& error) {
    const long dx = std::lround(dxD), dy = std::lround(dyD);
    if (dx < -64 || dx > 64 || dy < -64 || dy > 64) {
        error = "dropShadow: dx/dy must be in [-64,64]";
        return false;
    }
    if (!(radius >= 0.0) || radius > 64.0) {
        error = "dropShadow: radius must be in [0,64]";
        return false;
    }
    if (!(opacity >= 0.0) || opacity > 1.0) {
        error = "dropShadow: opacity must be in [0,1]";
        return false;
    }
    std::uint8_t col[3] = {0, 0, 0};
    if (!colorStr.empty() && !parseHex6(colorStr, col)) {
        error = "dropShadow: color must be \"#RRGGBB\"";
        return false;
    }
    out = in;
    if (opacity <= 0.0) return true;
    // Shifted silhouette (alpha/255), then separable blur on the
    // single channel (radius 0 = copy).
    std::vector<float> sil(static_cast<std::size_t>(w) * h, 0.0f);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const int sx = x - static_cast<int>(dx);
            const int sy = y - static_cast<int>(dy);
            if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue;
            sil[(static_cast<std::size_t>(y) * w + x)] =
                in[(static_cast<std::size_t>(sy) * w + sx) * 4 + 3] / 255.0f;
        }
    if (radius > 0.0) {
        int half = 1;
        const std::vector<double> k = gaussKernel(radius, half);
        std::vector<float> tmp(sil.size(), 0.0f), row(sil.size(), 0.0f);
        auto at = [&](const std::vector<float>& b, int x, int y) {
            return b[static_cast<std::size_t>(clampI(y, 0, h - 1)) * w +
                     clampI(x, 0, w - 1)];
        };
        for (int y = 0; y < h; ++y)
            for (int x = 0; x < w; ++x) {
                double acc = k[0] * at(sil, x, y);
                for (int i = 1; i <= half; ++i)
                    acc += k[i] * (at(sil, x - i, y) + at(sil, x + i, y));
                tmp[static_cast<std::size_t>(y) * w + x] =
                    static_cast<float>(acc);
            }
        for (int y = 0; y < h; ++y)
            for (int x = 0; x < w; ++x) {
                double acc = k[0] * at(tmp, x, y);
                for (int i = 1; i <= half; ++i)
                    acc += k[i] * (at(tmp, x, y - i) + at(tmp, x, y + i));
                row[static_cast<std::size_t>(y) * w + x] =
                    static_cast<float>(acc);
            }
        sil.swap(row);
    }
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const std::size_t i = (static_cast<std::size_t>(y) * w + x) * 4;
            const double sa =
                sil[static_cast<std::size_t>(y) * w + x] * opacity;
            if (sa <= 0.0) continue;
            const double da = in[i + 3] / 255.0;
            const double oa = da + sa * (1.0 - da);
            if (oa <= 0.0) continue;
            for (int c = 0; c < 3; ++c) {
                // Shadow UNDER source: src over shadow, premultiplied.
                // Opaque source pixels stay byte-identical.
                out[i + c] =
                    clamp255(in[i + c] + col[c] * sa * (1.0 - da));
            }
            out[i + 3] = clamp255(oa * 255.0);
        }
    return true;
}

// T4 crystallize (§2): flat-top hexagonal tiling (axial rounding —
// the signature look vs square mosaic). Each cell takes its center
// pixel color. cell = hex size (center to corner) in px.
static bool fCrystallize(const std::vector<std::uint8_t>& in, int w, int h,
                         double cellD, std::vector<std::uint8_t>& out,
                         std::string& error) {
    const long cell = std::lround(cellD);
    if (cell < 3 || cell > 64) {
        error = "crystallize: cell must be in [3,64]";
        return false;
    }
    const double s = static_cast<double>(cell);
    const double sq3 = std::sqrt(3.0);
    out = in;
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            // Pixel -> axial (flat-top): q = 2x/3s, r = (-x/3 + y√3/3)/s.
            const double q = (2.0 * x / 3.0) / s;
            const double r = (-x / 3.0 + y * sq3 / 3.0) / s;
            // Cube rounding.
            double cx = q, cz = r, cy = -cx - cz;
            double rx = std::round(cx), ry = std::round(cy),
                   rz = std::round(cz);
            const double dx = std::fabs(rx - cx), dy = std::fabs(ry - cy),
                         dz = std::fabs(rz - cz);
            long aq = std::lround(rx), ar = std::lround(rz);
            if (dx > dy && dx > dz) {
                aq = std::lround(-ry - rz);
            } else if (dy > dz) {
                ar = std::lround(-rx - rz);
                aq = std::lround(rx);
            }
            // Axial -> center pixel (flat-top layout).
            const int ccx = static_cast<int>(
                std::lround(s * 3.0 / 2.0 * aq));
            const int ccy = static_cast<int>(
                std::lround(s * sq3 * (ar + aq / 2.0)));
            const std::size_t i = (static_cast<std::size_t>(y) * w + x) * 4;
            for (int c = 0; c < 3; ++c)
                out[i + c] = pxAt(in, w, h, ccx, ccy, c);
        }
    return true;
}

// T4 normal map (§2): height = straight (unpremultiplied) Rec.601
// luma — translucent edges must not skew the Sobel — 3x3 Sobel to
// normal RGB ((n*0.5+0.5)*255, B up). Flat = uniform #8080FF.
static bool fNormalMap(const std::vector<std::uint8_t>& in, int w, int h,
                       double strength, std::vector<std::uint8_t>& out,
                       std::string& error) {
    if (!(strength >= 0.0) || strength > 4.0) {
        error = "normalMap: strength must be in [0,4]";
        return false;
    }
    out = in;
    if (strength <= 0.0) return true;  // byte-identical no-op
    auto height = [&](int x, int y) -> double {
        x = clampI(x, 0, w - 1);
        y = clampI(y, 0, h - 1);
        const std::size_t i =
            (static_cast<std::size_t>(y) * w + x) * 4;
        const double a = in[i + 3];
        const double un = (a <= 0.0) ? 0.0 : 255.0 / a;
        return 0.299 * in[i] * un + 0.587 * in[i + 1] * un +
               0.114 * in[i + 2] * un;
    };
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const std::size_t i = (static_cast<std::size_t>(y) * w + x) * 4;
            const double dx = (height(x + 1, y - 1) + 2 * height(x + 1, y) +
                               height(x + 1, y + 1) - height(x - 1, y - 1) -
                               2 * height(x - 1, y) - height(x - 1, y + 1)) /
                              (4.0 * 255.0);
            const double dy = (height(x - 1, y + 1) + 2 * height(x, y + 1) +
                               height(x + 1, y + 1) - height(x - 1, y - 1) -
                               2 * height(x, y - 1) - height(x + 1, y - 1)) /
                              (4.0 * 255.0);
            double nx = -dx * strength, ny = -dy * strength, nz = 1.0;
            const double len = std::sqrt(nx * nx + ny * ny + nz * nz);
            nx /= len;
            ny /= len;
            nz /= len;
            out[i] = clamp255((nx * 0.5 + 0.5) * 255.0);
            out[i + 1] = clamp255((ny * 0.5 + 0.5) * 255.0);
            out[i + 2] = clamp255((nz * 0.5 + 0.5) * 255.0);
        }
    return true;
}

// T4 Perlin gradient noise (§2 perlinClouds + pencil paper grain,
// clean-room): quintic fade, LCG-shuffled permutation of `seed`.
struct Perlin2D {
    int p[512];
    explicit Perlin2D(std::uint32_t seed = 0) {
        int perm[256];
        for (int i = 0; i < 256; ++i) perm[i] = i;
        std::uint32_t st = seed ? seed : 0x9E3779B9u;
        for (int i = 255; i > 0; --i) {
            st = st * 1664525u + 1013904223u;
            const int j = static_cast<int>((st >> 16) % (i + 1));
            const int t = perm[i];
            perm[i] = perm[j];
            perm[j] = t;
        }
        for (int i = 0; i < 512; ++i) p[i] = perm[i & 255];
    }
    static double fade(double t) {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }
    static double grad(int h, double x, double y) {
        switch (h & 7) {
            case 0: return x + y;
            case 1: return x - y;
            case 2: return -x + y;
            case 3: return -x - y;
            case 4: return x;
            case 5: return -x;
            case 6: return y;
            default: return -y;
        }
    }
    double noise(double x, double y) const {
        const int X = static_cast<int>(std::floor(x)) & 255;
        const int Y = static_cast<int>(std::floor(y)) & 255;
        x -= std::floor(x);
        y -= std::floor(y);
        const double u = fade(x), v = fade(y);
        const int aa = p[X] + Y, ab = p[X] + Y + 1;
        const int ba = p[X + 1] + Y, bb = p[X + 1] + Y + 1;
        const double x1 =
            grad(p[aa & 511], x, y) * (1 - u) + grad(p[ba & 511], x - 1, y) * u;
        const double x2 = grad(p[ab & 511], x, y - 1) * (1 - u) +
                          grad(p[bb & 511], x - 1, y - 1) * u;
        return (x1 * (1 - v) + x2 * v) * 0.7071;  // ~[-1,1]
    }
    double fbm(double x, double y, int octaves) const {
        double sum = 0, amp = 0.5, norm = 0, fx = x, fy = y;
        for (int o = 0; o < octaves; ++o) {
            sum += amp * noise(fx, fy);
            norm += amp;
            amp *= 0.5;
            fx *= 2.0;
            fy *= 2.0;
        }
        return norm > 0 ? sum / norm : 0;
    }
};

// T4 retro (§2): warm lift (blacks lifted, whites compressed), LCG
// grain (same fixed seed as addNoise: reproducible), smooth radial
// vignette, black fade. All zero = byte-identical.
static bool fRetro(const std::vector<std::uint8_t>& in, int w, int h,
                   double warmth, double grainD, double vignette,
                   double fade, std::vector<std::uint8_t>& out,
                   std::string& error) {
    if (!(warmth >= 0.0) || warmth > 1.0) {
        error = "retro: warmth must be in [0,1]";
        return false;
    }
    const long grain = std::lround(grainD);
    if (grain < 0 || grain > 64) {
        error = "retro: grain must be in [0,64]";
        return false;
    }
    if (!(vignette >= 0.0) || vignette > 1.0) {
        error = "retro: vignette must be in [0,1]";
        return false;
    }
    if (!(fade >= 0.0) || fade > 1.0) {
        error = "retro: fade must be in [0,1]";
        return false;
    }
    out = in;
    if (warmth <= 0.0 && grain == 0 && vignette <= 0.0 && fade <= 0.0)
        return true;
    std::uint32_t st = 0;  // same fixed seed as addNoise
    auto next = [&](int span) -> int {
        st = st * 1664525u + 1013904223u;
        return span <= 1 ? 0
                         : static_cast<int>((st >> 16) % span) - span / 2;
    };
    const int gspan = static_cast<int>(2 * grain + 1);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const std::size_t i = (static_cast<std::size_t>(y) * w + x) * 4;
            const double lum = (0.299 * in[i] + 0.587 * in[i + 1] +
                                0.114 * in[i + 2]) /
                               255.0;
            double r = in[i], g = in[i + 1], b = in[i + 2];
            // Warm lift + white compression + black fade.
            r += warmth * 24.0 * (1.0 - lum) + fade * 30.0 * (1.0 - lum);
            g += warmth * 10.0 * (1.0 - lum) + fade * 26.0 * (1.0 - lum);
            b += fade * 22.0 * (1.0 - lum) - warmth * 8.0 * (1.0 - lum);
            r -= warmth * 14.0 * lum;
            g -= warmth * 10.0 * lum;
            b -= warmth * 6.0 * lum;
            // Grain (per channel, filmic).
            if (grain > 0) {
                r += next(gspan);
                g += next(gspan);
                b += next(gspan);
            }
            // Smooth radial vignette (cosine falloff, like bulge §1).
            if (vignette > 0.0) {
                const double nx = (2.0 * x / (w > 1 ? w - 1 : 1)) - 1.0;
                const double ny = (2.0 * y / (h > 1 ? h - 1 : 1)) - 1.0;
                double d = (nx * nx + ny * ny) / 2.0;  // 0 center .. 1 edge
                if (d < 0) d = 0;
                if (d > 1) d = 1;
                const double f =
                    1.0 - vignette * 0.55 * (1.0 - std::cos(d * 1.5707963));
                r *= f;
                g *= f;
                b *= f;
            }
            out[i] = clamp255(r);
            out[i + 1] = clamp255(g);
            out[i + 2] = clamp255(b);
        }
    return true;
}

// T4 pencil (§2): luma → inverted Sobel edges × fine procedural
// paper grain (single-octave gradient noise, fixed seed), warm
// near-monochrome output. strength 0 = byte-identical no-op.
static bool fPencil(const std::vector<std::uint8_t>& in, int w, int h,
                    double strength, std::vector<std::uint8_t>& out,
                    std::string& error) {
    if (!(strength >= 0.0) || strength > 1.0) {
        error = "pencil: strength must be in [0,1]";
        return false;
    }
    out = in;
    if (strength <= 0.0) return true;
    Perlin2D perl(0xC1A0u);
    auto lumAt = [&](int x, int y) -> double {
        x = clampI(x, 0, w - 1);
        y = clampI(y, 0, h - 1);
        const std::size_t i =
            (static_cast<std::size_t>(y) * w + x) * 4;
        return 0.299 * in[i] + 0.587 * in[i + 1] + 0.114 * in[i + 2];
    };
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const std::size_t i = (static_cast<std::size_t>(y) * w + x) * 4;
            const double dx =
                (lumAt(x + 1, y - 1) + 2 * lumAt(x + 1, y) +
                 lumAt(x + 1, y + 1) - lumAt(x - 1, y - 1) -
                 2 * lumAt(x - 1, y) - lumAt(x - 1, y + 1)) /
                1020.0;
            const double dy =
                (lumAt(x - 1, y + 1) + 2 * lumAt(x, y + 1) +
                 lumAt(x + 1, y + 1) - lumAt(x - 1, y - 1) -
                 2 * lumAt(x, y - 1) - lumAt(x + 1, y - 1)) /
                1020.0;
            double edge = std::sqrt(dx * dx + dy * dy);
            if (edge > 1.0) edge = 1.0;
            // Fine paper grain from single-octave gradient noise.
            const double n = perl.noise(x * 0.35, y * 0.35);  // ~[-1,1]
            const double paper = 0.92 + 0.08 * n;
            const double ink = (255.0 * (1.0 - edge * strength)) * paper;
            out[i] = clamp255(ink);
            out[i + 1] = clamp255(ink * 0.96);
            out[i + 2] = clamp255(ink * 0.88);
        }
    return true;
}

// T4 perlinClouds (§2): fBm gradient noise; mix = screen at 50% over
// source, overwrite = full-layer grayscale fill (nApplyTone pattern).
static bool fPerlin(const std::vector<std::uint8_t>& in, int w, int h,
                    double scaleD, double octD, double seedD,
                    const std::string& mode,
                    std::vector<std::uint8_t>& out, std::string& error) {
    const long scale = std::lround(scaleD);
    const long oct = std::lround(octD);
    if (scale < 1 || scale > 16) {
        error = "perlinClouds: scale must be in [1,16]";
        return false;
    }
    if (oct < 1 || oct > 6) {
        error = "perlinClouds: octaves must be in [1,6]";
        return false;
    }
    if (mode != "mix" && mode != "overwrite") {
        error = "perlinClouds: mode must be \"mix\"|\"overwrite\"";
        return false;
    }
    Perlin2D perl(static_cast<std::uint32_t>(std::lround(seedD)) ^
                  0x9E3779B9u);
    const double span = static_cast<double>(w > h ? w : h);
    out = in;
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const std::size_t i = (static_cast<std::size_t>(y) * w + x) * 4;
            const double n = perl.fbm(x / span * scale, y / span * scale,
                                      static_cast<int>(oct));  // ~[-1,1]
            const double c = clamp255((n * 0.5 + 0.5) * 255.0);
            if (mode == "overwrite") {
                out[i] = out[i + 1] = out[i + 2] =
                    static_cast<std::uint8_t>(c);
            } else {
                for (int ch = 0; ch < 3; ++ch) {
                    const double s = in[i + ch];
                    const double scr =
                        255.0 - (255.0 - s) * (255.0 - c) / 255.0;
                    out[i + ch] = clamp255((s + scr) / 2.0);
                }
            }
        }
    return true;
}

// Bilinear RGB sample, clamp-to-edge (shared by the §3 remap filters).
static void sampleBilinearRGB(const std::vector<std::uint8_t>& in, int w,
                              int h, double x, double y, double rgb[3]) {
    int x0 = static_cast<int>(std::floor(x));
    int y0 = static_cast<int>(std::floor(y));
    const double fx = x - x0, fy = y - y0;
    int x1 = x0 + 1, y1 = y0 + 1;
    x0 = clampI(x0, 0, w - 1);
    y0 = clampI(y0, 0, h - 1);
    x1 = clampI(x1, 0, w - 1);
    y1 = clampI(y1, 0, h - 1);
    const std::uint8_t* a = &in[(static_cast<std::size_t>(y0) * w + x0) * 4];
    const std::uint8_t* b = &in[(static_cast<std::size_t>(y0) * w + x1) * 4];
    const std::uint8_t* c = &in[(static_cast<std::size_t>(y1) * w + x0) * 4];
    const std::uint8_t* d = &in[(static_cast<std::size_t>(y1) * w + x1) * 4];
    for (int k = 0; k < 3; ++k)
        rgb[k] = a[k] * (1 - fx) * (1 - fy) + b[k] * fx * (1 - fy) +
                 c[k] * (1 - fx) * fy + d[k] * fx * fy;
}

// T4 lensBlur (§3): uniform-disk kernel (bokeh approximation, NOT
// gaussian). highlights spares bright luma (partial screen gain).
// radius 1 ≈ identity on flat areas (locked: uniform surface test).
static bool fLensBlur(const std::vector<std::uint8_t>& in, int w, int h,
                      double radiusD, double highlights,
                      std::vector<std::uint8_t>& out, std::string& error) {
    const long radius = std::lround(radiusD);
    if (radius < 1 || radius > 64) {
        error = "lensBlur: radius must be in [1,64]";
        return false;
    }
    if (!(highlights >= 0.0) || highlights > 1.0) {
        error = "lensBlur: highlights must be in [0,1]";
        return false;
    }
    out = in;
    if (radius <= 1 && highlights <= 0.0) return true;
    const double r2 = static_cast<double>(radius * radius);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const std::size_t i = (static_cast<std::size_t>(y) * w + x) * 4;
            double acc[3] = {0, 0, 0};
            long n = 0;
            for (long oy = -radius; oy <= radius; ++oy)
                for (long ox = -radius; ox <= radius; ++ox) {
                    if (ox * ox + oy * oy > r2) continue;
                    for (int c = 0; c < 3; ++c)
                        acc[c] += pxAt(in, w, h, x + static_cast<int>(ox),
                                       y + static_cast<int>(oy), c);
                    ++n;
                }
            for (int c = 0; c < 3; ++c) {
                double v = n > 0 ? acc[c] / n : in[i + c];
                if (highlights > 0.0) {
                    const double lum =
                        (0.299 * in[i] + 0.587 * in[i + 1] +
                         0.114 * in[i + 2]) / 255.0;
                    double hi = (lum - 0.7) / 0.3;
                    if (hi < 0) hi = 0;
                    if (hi > 1) hi = 1;
                    v += highlights * hi * (255.0 - v) * 0.5;
                }
                out[i + c] = clamp255(v);
            }
        }
    return true;
}

// T4 fisheyeFilter (§3): radial remap r' = r*(1+k*r²) on the
// normalized radius (same formula as the M9 camera, k = amount —
// dimensionless here, documented). Negative = pincushion.
// keepSize crops to canvas (clamp-to-edge fill, announced).
// amount 0 = byte-identical.
static bool fFisheye(const std::vector<std::uint8_t>& in, int w, int h,
                     double amount, double keepD,
                     std::vector<std::uint8_t>& out, std::string& error) {
    if (!(amount >= -1.0) || amount > 1.0) {
        error = "fisheyeFilter: amount must be in [-1,1]";
        return false;
    }
    out = in;
    if (amount == 0.0) return true;
    (void)keepD;  // keepSize: single behavior (crop + clamp), announced
    const double cx = (w - 1) / 2.0, cy = (h - 1) / 2.0;
    const double R = std::sqrt(cx * cx + cy * cy);  // half diagonal
    if (!(R > 0.0)) return true;
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const double dx = (x - cx) / R, dy = (y - cy) / R;
            const double rn = std::sqrt(dx * dx + dy * dy);
            const double s = 1.0 + amount * rn * rn;
            const double sx = cx + dx * s * R, sy = cy + dy * s * R;
            double rgb[3];
            sampleBilinearRGB(in, w, h, sx, sy, rgb);
            const std::size_t i = (static_cast<std::size_t>(y) * w + x) * 4;
            for (int c = 0; c < 3; ++c) out[i + c] = clamp255(rgb[c]);
        }
    return true;
}

// T4 panoramaRemap (§3): cyl = cylindrical projection (straightens
// wide bands, edges stretched + clamped), rect = inverse. No
// stitching (FUTURE, honest note in the spec).
static bool fPanorama(const std::vector<std::uint8_t>& in, int w, int h,
                      const std::string& mode, double fovD,
                      std::vector<std::uint8_t>& out, std::string& error) {
    if (mode != "cyl" && mode != "rect") {
        error = "panoramaRemap: mode must be \"cyl\"|\"rect\"";
        return false;
    }
    if (!(fovD >= 60.0) || fovD > 180.0) {
        error = "panoramaRemap: fov must be in [60,180]";
        return false;
    }
    out = in;
    const double half = (fovD * 3.141592653589793 / 180.0) / 2.0;
    const double tmax = std::tan(half);
    if (!(tmax > 0.0)) return false;
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const double u =
                (w > 1) ? (x / (w - 1.0) - 0.5) : 0.0;  // -0.5..0.5
            double su;
            if (mode == "cyl") {
                // Destination cylindrical: sample the rectilinear
                // source at tan(theta)/tan(half).
                su = std::tan(u * 2.0 * half) / tmax / 2.0 + 0.5;
            } else {
                // Inverse: destination rectilinear from cylindrical.
                const double th = std::atan(u * 2.0 * tmax) / (2.0 * half);
                su = th + 0.5;
            }
            const double sx = su * (w - 1.0);
            double rgb[3];
            sampleBilinearRGB(in, w, h, sx, y, rgb);
            const std::size_t i = (static_cast<std::size_t>(y) * w + x) * 4;
            for (int c = 0; c < 3; ++c) out[i + c] = clamp255(rgb[c]);
        }
    return true;
}

// Ordered Bayer 8x8 (0..63) — same table as the V+2 GIF writer
// (clean-room from scratch, duplicated here: single-header rule, no
// cross-io dependency from the filter kernel).
static const int kThreshBayer8[8][8] = {
    {0, 32, 8, 40, 2, 34, 10, 42},   {48, 16, 56, 24, 50, 18, 58, 26},
    {12, 44, 4, 36, 14, 46, 6, 38},  {60, 28, 52, 20, 62, 30, 54, 22},
    {3, 35, 11, 43, 1, 33, 9, 41},   {51, 19, 59, 27, 49, 17, 57, 25},
    {15, 47, 7, 39, 13, 45, 5, 37},  {63, 31, 55, 23, 61, 29, 53, 21},
};

// T1 threshold (ARCHITECTURE.md "T1 additions", clean-room): binary
// black/white by Rec.601 luma. dither != 0 = ordered Bayer-8 around
// the level (mean-preserving: +/-31.5 luma units).
static bool fThreshold(const std::vector<std::uint8_t>& in, int w, int h,
                       double levelD, bool dither,
                       std::vector<std::uint8_t>& out) {
    double level = levelD;
    if (level < 0.0) level = 0.0;
    if (level > 255.0) level = 255.0;
    out = in;
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const std::size_t i = (static_cast<std::size_t>(y) * w + x) * 4;
            double lum = 0.299 * in[i] + 0.587 * in[i + 1] + 0.114 * in[i + 2];
            if (dither)
                lum += kThreshBayer8[y & 7][x & 7] - 31.5;
            const std::uint8_t v = (lum >= level) ? 255 : 0;
            out[i] = out[i + 1] = out[i + 2] = v;
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
            "gradientMap", "denoise", "unsharp", "colorBalance", "threshold",
            "glow", "dropShadow", "crystallize", "normalMap", "retro",
            "pencil", "perlinClouds", "lensBlur", "fisheyeFilter",
            "panoramaRemap"};
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
    } else if (name == "denoise") {
        double strength = 0.5;
        std::string mode = "median";
        if (!paramsJson.empty()) {
            if (jsonNum(paramsJson, "strength", d)) strength = d;
            std::string mv;
            if (jsonStr(paramsJson, "mode", mv) && !mv.empty()) mode = mv;
        }
        ok = fDenoise(in, w, h, strength, mode, out, error);
    } else if (name == "unsharp") {
        double radius = 2.0, amount = 0.6, threshold = 2.0;
        if (!paramsJson.empty()) {
            if (jsonNum(paramsJson, "radius", d)) radius = d;
            if (jsonNum(paramsJson, "amount", d)) amount = d;
            if (jsonNum(paramsJson, "threshold", d)) threshold = d;
        }
        ok = fUnsharp(in, w, h, radius, amount, threshold, out, error);
    } else if (name == "colorBalance") {
        double sh[3] = {0, 0, 0}, mid[3] = {0, 0, 0}, hi[3] = {0, 0, 0};
        ok = true;
        if (!paramsJson.empty()) {
            std::string sv;
            if (jsonStr(paramsJson, "shadows", sv) && !sv.empty() &&
                !parseTriplet(sv, sh, error))
                ok = false;
            if (ok && jsonStr(paramsJson, "midtones", sv) && !sv.empty() &&
                !parseTriplet(sv, mid, error))
                ok = false;
            if (ok && jsonStr(paramsJson, "highlights", sv) && !sv.empty() &&
                !parseTriplet(sv, hi, error))
                ok = false;
        }
        if (ok) ok = fColorBalance(in, sh, mid, hi, out);
    } else if (name == "threshold") {
        double level = 128.0, dither = 0.0;
        if (!paramsJson.empty()) {
            if (jsonNum(paramsJson, "level", d)) level = d;
            if (jsonNum(paramsJson, "dither", d)) dither = d;
        }
        ok = fThreshold(in, w, h, level, dither != 0.0, out);
    } else if (name == "glow") {
        double radius = 8.0, amount = 0.6;
        std::string tint = "#FFFFFF";
        if (!paramsJson.empty()) {
            if (jsonNum(paramsJson, "radius", d)) radius = d;
            if (jsonNum(paramsJson, "amount", d)) amount = d;
            std::string sv;
            if (jsonStr(paramsJson, "tint", sv)) tint = sv;
        }
        ok = fGlow(in, w, h, radius, amount, tint, out, error);
    } else if (name == "dropShadow") {
        double dx = 8.0, dy = 8.0, radius = 6.0, opacity = 0.5;
        std::string color = "#000000";
        if (!paramsJson.empty()) {
            if (jsonNum(paramsJson, "dx", d)) dx = d;
            if (jsonNum(paramsJson, "dy", d)) dy = d;
            if (jsonNum(paramsJson, "radius", d)) radius = d;
            if (jsonNum(paramsJson, "opacity", d)) opacity = d;
            std::string sv;
            if (jsonStr(paramsJson, "color", sv)) color = sv;
        }
        ok = fDropShadow(in, w, h, dx, dy, radius, color, opacity, out,
                         error);
    } else if (name == "crystallize") {
        double cell = 16.0;
        if (!paramsJson.empty() && jsonNum(paramsJson, "cell", d)) cell = d;
        ok = fCrystallize(in, w, h, cell, out, error);
    } else if (name == "normalMap") {
        double strength = 1.0;
        if (!paramsJson.empty() && jsonNum(paramsJson, "strength", d))
            strength = d;
        ok = fNormalMap(in, w, h, strength, out, error);
    } else if (name == "retro") {
        double warmth = 0.5, grain = 10.0, vignette = 0.3, fade = 0.2;
        if (!paramsJson.empty()) {
            if (jsonNum(paramsJson, "warmth", d)) warmth = d;
            if (jsonNum(paramsJson, "grain", d)) grain = d;
            if (jsonNum(paramsJson, "vignette", d)) vignette = d;
            if (jsonNum(paramsJson, "fade", d)) fade = d;
        }
        ok = fRetro(in, w, h, warmth, grain, vignette, fade, out, error);
    } else if (name == "pencil") {
        double strength = 0.7;
        if (!paramsJson.empty() && jsonNum(paramsJson, "strength", d))
            strength = d;
        ok = fPencil(in, w, h, strength, out, error);
    } else if (name == "perlinClouds") {
        double scale = 6.0, octaves = 4.0, seed = 0.0;
        std::string mode = "mix";
        if (!paramsJson.empty()) {
            if (jsonNum(paramsJson, "scale", d)) scale = d;
            if (jsonNum(paramsJson, "octaves", d)) octaves = d;
            if (jsonNum(paramsJson, "seed", d)) seed = d;
            std::string sv;
            if (jsonStr(paramsJson, "mode", sv)) mode = sv;
        }
        ok = fPerlin(in, w, h, scale, octaves, seed, mode, out, error);
    } else if (name == "lensBlur") {
        double radius = 8.0, highlights = 0.5;
        if (!paramsJson.empty()) {
            if (jsonNum(paramsJson, "radius", d)) radius = d;
            if (jsonNum(paramsJson, "highlights", d)) highlights = d;
        }
        ok = fLensBlur(in, w, h, radius, highlights, out, error);
    } else if (name == "fisheyeFilter") {
        double amount = 0.5, keepSize = 1.0;
        if (!paramsJson.empty()) {
            if (jsonNum(paramsJson, "amount", d)) amount = d;
            if (jsonNum(paramsJson, "keepSize", d)) keepSize = d;
        }
        ok = fFisheye(in, w, h, amount, keepSize, out, error);
    } else if (name == "panoramaRemap") {
        double fov = 120.0;
        std::string mode = "cyl";
        if (!paramsJson.empty()) {
            if (jsonNum(paramsJson, "fov", d)) fov = d;
            std::string sv;
            if (jsonStr(paramsJson, "mode", sv)) mode = sv;
        }
        ok = fPanorama(in, w, h, mode, fov, out, error);
    }
    if (!ok) return false;  // surface untouched
    // Alpha contract: never modify alpha; clamp RGB to [0, alpha].
    // EXCEPTION (T4 §2, documented): dropShadow composites a shadow
    // layer BEHIND the source, so its alpha lands in `out` too —
    // copy it, then clamp RGB to the NEW alpha.
    std::vector<std::uint8_t>& dst = s.bytes();
    const bool shadowAlpha = (name == "dropShadow");
    for (std::size_t i = 0; i + 3 < dst.size() && i + 3 < out.size(); i += 4) {
        if (shadowAlpha) dst[i + 3] = out[i + 3];
        const std::uint8_t a = dst[i + 3];
        dst[i] = out[i] > a ? a : out[i];
        dst[i + 1] = out[i + 1] > a ? a : out[i + 1];
        dst[i + 2] = out[i + 2] > a ? a : out[i + 2];
    }
    return true;
}

bool applyTone(Surface& s, const std::string& name) {
    const std::vector<std::string> names = {"hatch", "crosshatch", "dots",
                                            "grid", "checker", "stripes"};
    if (std::find(names.begin(), names.end(), name) == names.end())
        return false;
    const int w = s.width(), h = s.height();
    const std::uint8_t ink = 96;
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            bool on = false;
            if (name == "hatch") {
                on = ((x + y) % 8) == 0;
            } else if (name == "crosshatch") {
                on = ((x + y) % 8) == 0 || ((x - y) % 8) == 0;
            } else if (name == "dots") {
                on = (x % 8 == 3 || x % 8 == 4) && (y % 8 == 3 || y % 8 == 4);
            } else if (name == "grid") {
                on = (x % 8) == 0 || (y % 8) == 0;
            } else if (name == "checker") {
                on = (((x / 4) + (y / 4)) % 2) == 0;
            } else if (name == "stripes") {
                on = (x % 8) < 4;
            }
            Pixel p = s.get(x, y);
            const std::uint8_t v = on ? ink : 0;
            p.r = v > p.a ? p.a : v;
            p.g = v > p.a ? p.a : v;
            p.b = v > p.a ? p.a : v;
            s.set(x, y, p);
        }
    }
    return true;
}

// --- M8 adjustment-layer ops (clean-room pixel math) ---

bool isKnownAdjustType(const std::string& type) {
    return type == "brightness" || type == "contrast" || type == "hue" ||
           type == "saturation" || type == "gradientMap" ||
           type == "curves";
}

void parseAdjustDescriptor(const std::string& json, std::string& type,
                           double& amount, std::string& stops) {
    type.clear();
    amount = 0.0;
    stops.clear();
    if (!json.empty()) {
        std::string t;
        if (jsonStr(json, "type", t)) type = t;
        double d = 0.0;
        if (jsonNum(json, "amount", d)) amount = d;
        std::string sv;
        if (jsonStr(json, "stops", sv)) stops = sv;
    }
    if (amount != amount) amount = 0.0;  // NaN -> default
    if (amount < -1.0) amount = -1.0;
    if (amount > 1.0) amount = 1.0;
}

namespace {

// Rec.601 luma weights (same as the gradientMap filter above).
inline double luma601(double r, double g, double b) {
    return 0.299 * r + 0.587 * g + 0.114 * b;
}

// HSL conversion (h in degrees [0,360), s/l in [0,1]); hue rotation only
// needs full round-trip fidelity, not gamut perfection — clean-room.
inline void rgbToHsl(double r, double g, double b, double& h, double& s,
                     double& l) {
    r /= 255.0;
    g /= 255.0;
    b /= 255.0;
    const double mx = r > g ? (r > b ? r : b) : (g > b ? g : b);
    const double mn = r < g ? (r < b ? r : b) : (g < b ? g : b);
    l = (mx + mn) / 2.0;
    if (mx == mn) {
        h = 0.0;
        s = 0.0;
        return;
    }
    const double d = mx - mn;
    s = l > 0.5 ? d / (2.0 - mx - mn) : d / (mx + mn);
    if (mx == r) {
        h = (g - b) / d + (g < b ? 6.0 : 0.0);
    } else if (mx == g) {
        h = (b - r) / d + 2.0;
    } else {
        h = (r - g) / d + 4.0;
    }
    h *= 60.0;
}

inline double hueToRgb(double p, double q, double t) {
    if (t < 0.0) t += 1.0;
    if (t > 1.0) t -= 1.0;
    if (t < 1.0 / 6.0) return p + (q - p) * 6.0 * t;
    if (t < 1.0 / 2.0) return q;
    if (t < 2.0 / 3.0) return p + (q - p) * (2.0 / 3.0 - t) * 6.0;
    return p;
}

inline void hslToRgb(double h, double s, double l, double& r, double& g,
                     double& b) {
    h = std::fmod(h, 360.0);
    if (h < 0.0) h += 360.0;
    h /= 360.0;
    double qr, qg, qb;
    if (s == 0.0) {
        qr = qg = qb = l;
    } else {
        const double q = l < 0.5 ? l * (1.0 + s) : l + s - l * s;
        const double p = 2.0 * l - q;
        qr = hueToRgb(p, q, h + 1.0 / 3.0);
        qg = hueToRgb(p, q, h);
        qb = hueToRgb(p, q, h - 1.0 / 3.0);
    }
    r = qr * 255.0;
    g = qg * 255.0;
    b = qb * 255.0;
}

// Ramp one luminance value through the stops spec (same parse + interp as
// the M3 gradientMap filter: stop list must parse, else identity=false).
bool gradientRamp(const std::string& spec, double lum, double& r, double& g,
                  double& b) {
    std::string inner = spec;
    const auto lb = inner.find('[');
    const auto rb = inner.rfind(']');
    if (lb == std::string::npos || rb == std::string::npos || rb <= lb)
        return false;
    inner = inner.substr(lb + 1, rb - lb - 1);
    std::string flat;
    for (char ch : inner)
        if (ch != '"' && ch != '\'') flat.push_back(ch);
    std::vector<Stop> stops;
    std::string error;
    if (!parseStops(flat, stops, error)) return false;
    const Stop* lo = &stops.front();
    const Stop* hi = &stops.back();
    for (const auto& s : stops) {
        if (s.pos <= lum) lo = &s;
        if (s.pos >= lum) {
            hi = &s;
            break;
        }
    }
    const double t =
        (hi->pos > lo->pos) ? (lum - lo->pos) / (hi->pos - lo->pos) : 0.0;
    r = lo->r + (hi->r - lo->r) * t;
    g = lo->g + (hi->g - lo->g) * t;
    b = lo->b + (hi->b - lo->b) * t;
    return true;
}

}  // namespace

// V+2 curves descriptor: {"type":"curves","points":[[in,out]...],
// "channel":"rgb|r|g|b"} -> canonical "channel;i,o;i,o;...".
void parseCurvesDescriptor(const std::string& json, std::string& type,
                           std::string& channel, std::string& spec) {
    type.clear();
    channel = "rgb";
    spec.clear();
    std::string t;
    if (jsonStr(json, "type", t)) type = t;
    if (type != "curves") return;
    std::string ch;
    if (jsonStr(json, "channel", ch)) {
        if (ch == "r" || ch == "g" || ch == "b" || ch == "rgb")
            channel = ch;
    }
    // Flat number scan of the points array: pairs (in,out).
    const std::size_t pk = json.find("\"points\"");
    if (pk == std::string::npos) return;
    const std::size_t open = json.find('[', pk + 8);
    if (open == std::string::npos) return;
    std::vector<double> nums;
    std::size_t p = open;
    int depth = 0;
    while (p < json.size() && nums.size() < 64) {
        const char c = json[p];
        if (c == '[') ++depth;
        if (c == ']') {
            if (--depth <= 0) break;
        }
        if ((c >= '0' && c <= '9') || c == '-' || c == '.') {
            char* e = nullptr;
            const double v = std::strtod(json.c_str() + p, &e);
            if (e != json.c_str() + p) {
                nums.push_back(v);
                p = static_cast<std::size_t>(e - json.c_str());
                continue;
            }
        }
        ++p;
    }
    std::vector<std::pair<int, int>> pts;
    for (std::size_t i = 0; i + 1 < nums.size() && pts.size() < 16;
         i += 2) {
        int in = static_cast<int>(std::lround(nums[i]));
        int out = static_cast<int>(std::lround(nums[i + 1]));
        if (in < 0) in = 0;
        if (in > 255) in = 255;
        if (out < 0) out = 0;
        if (out > 255) out = 255;
        pts.emplace_back(in, out);
    }
    if (pts.empty()) return;  // honest identity, descriptor kept verbatim
    std::sort(pts.begin(), pts.end(),
              [](const auto& a, const auto& b) { return a.first < b.first; });
    spec = channel;
    for (auto& q : pts)
        spec += ";" + std::to_string(q.first) + "," +
                std::to_string(q.second);
}

void applyCurvesOp(const std::string& spec, double& r, double& g,
                   double& b) {
    // Thread-local LUT cache keyed by spec (composite is single
    // threaded; per-pixel cost stays one table lookup).
    thread_local std::string lastSpec;
    thread_local std::string lastChannel = "rgb";
    thread_local std::uint8_t lut[256] = {0};
    thread_local bool lutReady = false;
    if (!lutReady || spec != lastSpec) {
        lutReady = false;
        lastSpec = spec;
        const std::size_t semi = spec.find(';');
        lastChannel = (semi == std::string::npos) ? spec
                                                  : spec.substr(0, semi);
        if (lastChannel != "rgb" && lastChannel != "r" &&
            lastChannel != "g" && lastChannel != "b")
            return;  // identity: unknown channel
        std::vector<std::pair<int, int>> pts;
        std::size_t p = (semi == std::string::npos) ? spec.size() : semi;
        while (p < spec.size()) {
            if (spec[p] == ';') ++p;
            const std::size_t comma = spec.find(',', p);
            if (comma == std::string::npos) break;
            const std::size_t end = spec.find(';', comma);
            const std::string a =
                spec.substr(p, comma - p);
            const std::string bl =
                spec.substr(comma + 1, (end == std::string::npos
                                            ? spec.size()
                                            : end) -
                                           comma - 1);
            char* ea = nullptr;
            char* eb = nullptr;
            const long in = std::strtol(a.c_str(), &ea, 10);
            const long out = std::strtol(bl.c_str(), &eb, 10);
            if (ea != a.c_str() && eb != bl.c_str() && in >= 0 &&
                in <= 255 && out >= 0 && out <= 255)
                pts.emplace_back(static_cast<int>(in),
                                 static_cast<int>(out));
            if (end == std::string::npos) break;
            p = end;
        }
        if (pts.empty() || pts.size() > 16) return;  // identity
        std::sort(pts.begin(), pts.end(), [](const auto& a, const auto& b) {
            return a.first < b.first;
        });
        for (int i = 0; i < 256; ++i) {
            double v;
            if (i <= pts.front().first)
                v = pts.front().second;
            else if (i >= pts.back().first)
                v = pts.back().second;
            else {
                std::size_t k = 0;
                while (k + 1 < pts.size() && pts[k + 1].first <= i) ++k;
                const auto& lo = pts[k];
                const auto& hi = pts[k + 1];
                const double t = (hi.first > lo.first)
                                     ? (i - lo.first) /
                                           double(hi.first - lo.first)
                                     : 0.0;
                v = lo.second + (hi.second - lo.second) * t;
            }
            int q = static_cast<int>(std::lround(v));
            if (q < 0) q = 0;
            if (q > 255) q = 255;
            lut[i] = static_cast<std::uint8_t>(q);
        }
        lutReady = true;
    }
    auto map = [&](double v) -> double {
        int i = static_cast<int>(std::lround(v));
        if (i < 0) i = 0;
        if (i > 255) i = 255;
        return lut[i];
    };
    if (lastChannel == "rgb") {
        r = map(r);
        g = map(g);
        b = map(b);
    } else if (lastChannel == "r") {
        r = map(r);
    } else if (lastChannel == "g") {
        g = map(g);
    } else {
        b = map(b);
    }
}

void applyAdjustOp(const std::string& type, double amount,
                   const std::string& stops, double& r, double& g,
                   double& b) {
    if (type == "brightness") {
        const double d = amount * 128.0;
        r += d;
        g += d;
        b += d;
    } else if (type == "contrast") {
        const double k = 1.0 + amount;
        r = 128.0 + (r - 128.0) * k;
        g = 128.0 + (g - 128.0) * k;
        b = 128.0 + (b - 128.0) * k;
    } else if (type == "saturation") {
        const double k = 1.0 + amount;
        const double l = luma601(r, g, b);
        r = l + (r - l) * k;
        g = l + (g - l) * k;
        b = l + (b - l) * k;
    } else if (type == "hue") {
        if (amount == 0.0) return;
        double h = 0.0, s = 0.0, l = 0.0;
        rgbToHsl(r, g, b, h, s, l);
        if (s == 0.0) return;  // gray has no hue to rotate
        h += amount * 180.0;
        hslToRgb(h, s, l, r, g, b);
    } else if (type == "gradientMap") {
        double nr = r, ng = g, nb = b;
        if (gradientRamp(stops, luma601(r, g, b) / 255.0, nr, ng, nb)) {
            r = nr;
            g = ng;
            b = nb;
        }
        // Unparseable stops: identity (keeps the no-false-success rule).
    }
    // Unknown type: identity no-op.
}

}  // namespace openclip
