// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip issue #16 — panorama stitching core (see panorama.h).
//
// Alignment: gradient-magnitude maps + coarse-to-fine zero-mean
// normalized correlation (a.k.a. cosine similarity of edge maps).
// No FFT, no RNG, deterministic strided sampling. Bounded memory:
// analysis maps are uint8 and released pair by pair; the output canvas
// is refused before allocation when past PanoParams::maxPixels.
#include "openclip/panorama.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>
#include <new>

#include "stb_image.h"
#include "stb_image_write.h"

namespace openclip {
namespace {

constexpr int kMaxImages = 32;
constexpr int kMaxDimension = 8192;
constexpr int kCoarseSide = 192;

struct GrayMap {
    int w = 0, h = 0;
    std::vector<std::uint8_t> g;  // gradient magnitude
    double mean = 0.0;
    bool flat = true;
};

inline int clampInt(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

inline std::uint8_t lumaAt(const std::uint8_t* p) {
    // Premultiplied Rec.601. Alpha is tested by callers where relevant.
    return static_cast<std::uint8_t>(
        (static_cast<int>(p[0]) * 299 + static_cast<int>(p[1]) * 587 +
         static_cast<int>(p[2]) * 114) /
        1000);
}

// Box-sampled luma then central-difference gradient magnitude.
bool buildGray(const PanoImage& img, int step, GrayMap& out) {
    if (img.width <= 0 || img.height <= 0) return false;
    if (static_cast<long long>(img.width) * img.height * 4 !=
        static_cast<long long>(img.rgba.size()))
        return false;
    step = std::max(1, step);
    const int ow = (img.width + step - 1) / step;
    const int oh = (img.height + step - 1) / step;
    if (ow < 3 || oh < 3) return false;
    std::vector<std::uint8_t> lum(static_cast<std::size_t>(ow) * oh, 0);
    for (int oy = 0; oy < oh; ++oy) {
        const int y0 = oy * step;
        const int y1 = std::min(img.height, y0 + step);
        for (int ox = 0; ox < ow; ++ox) {
            const int x0 = ox * step;
            const int x1 = std::min(img.width, x0 + step);
            unsigned long acc = 0;
            int n = 0;
            for (int y = y0; y < y1; ++y) {
                const std::uint8_t* row =
                    img.rgba.data() + (static_cast<std::size_t>(y) *
                                           img.width +
                                       x0) *
                                          4;
                for (int x = x0; x < x1; ++x, row += 4) {
                    acc += lumaAt(row);
                    ++n;
                }
            }
            lum[static_cast<std::size_t>(oy) * ow + ox] =
                n ? static_cast<std::uint8_t>(acc / n) : 0;
        }
    }
    out.w = ow;
    out.h = oh;
    out.g.assign(static_cast<std::size_t>(ow) * oh, 0);
    double sum = 0.0, sumSq = 0.0;
    for (int y = 0; y < oh; ++y) {
        const int ym = y > 0 ? y - 1 : 0;
        const int yp = y + 1 < oh ? y + 1 : oh - 1;
        for (int x = 0; x < ow; ++x) {
            const int xm = x > 0 ? x - 1 : 0;
            const int xp = x + 1 < ow ? x + 1 : ow - 1;
            const int gx = static_cast<int>(lum[static_cast<std::size_t>(y) * ow + xp]) -
                           static_cast<int>(lum[static_cast<std::size_t>(y) * ow + xm]);
            const int gy = static_cast<int>(lum[static_cast<std::size_t>(yp) * ow + x]) -
                           static_cast<int>(lum[static_cast<std::size_t>(ym) * ow + x]);
            const double mag = std::sqrt(static_cast<double>(gx) * gx +
                                         static_cast<double>(gy) * gy);
            const std::uint8_t v = static_cast<std::uint8_t>(
                std::min(255.0, std::floor(mag)));
            out.g[static_cast<std::size_t>(y) * ow + x] = v;
            sum += v;
            sumSq += static_cast<double>(v) * v;
        }
    }
    const double n = static_cast<double>(ow) * oh;
    out.mean = sum / n;
    const double var = sumSq / n - out.mean * out.mean;
    out.flat = var < 1.0;  // textureless overlap: no reliable alignment
    return true;
}

struct Bounds {
    int x0 = 0, y0 = 0, x1 = 0, y1 = 0;
    bool valid() const { return x1 > x0 && y1 > y0; }
    long long area() const {
        return static_cast<long long>(x1 - x0) * (y1 - y0);
    }
};

Bounds overlapBounds(int aw, int ah, int bw, int bh, int dx, int dy) {
    Bounds b;
    b.x0 = std::max(0, dx);
    b.y0 = std::max(0, dy);
    b.x1 = std::min(aw, dx + bw);
    b.y1 = std::min(ah, dy + bh);
    return b;
}

// Cosine similarity of zero-mean gradient maps over the overlap.
// Returns <0 when the sample set is too small or one side is flat.
float gradientScore(const GrayMap& a, const GrayMap& b, int dx, int dy,
                    int stride, int minSamples) {
    const Bounds r = overlapBounds(a.w, a.h, b.w, b.h, dx, dy);
    if (!r.valid()) return -1.0f;
    stride = std::max(1, stride);
    double sab = 0.0, saa = 0.0, sbb = 0.0;
    long long n = 0;
    for (int y = r.y0; y < r.y1; y += stride) {
        const std::uint8_t* ra =
            a.g.data() + static_cast<std::size_t>(y) * a.w;
        const std::uint8_t* rb =
            b.g.data() + static_cast<std::size_t>(y - dy) * b.w;
        for (int x = r.x0; x < r.x1; x += stride) {
            const double va = static_cast<double>(ra[x]) - a.mean;
            const double vb =
                static_cast<double>(rb[x - dx]) - b.mean;
            sab += va * vb;
            saa += va * va;
            sbb += vb * vb;
            ++n;
        }
    }
    if (n < minSamples) return -1.0f;
    const double denom = std::sqrt(saa * sbb);
    if (denom < 1e-9) return -1.0f;
    double c = sab / denom;
    c = std::max(-1.0, std::min(1.0, c));
    return static_cast<float>(0.5 * (1.0 + c));
}

// Core alignment with pair-local progress (0..1). Public wrapper below
// passes no callback. `pairProgress` false = cancelled.
bool alignImpl(const PanoImage& a, const PanoImage& b,
               const PanoParams& params, int& dx, int& dy, float& score,
               std::string& error, const std::function<bool(float)>* pairProgress) {
    dx = dy = 0;
    score = 0.0f;
    if (a.width <= 0 || a.height <= 0 || b.width <= 0 || b.height <= 0) {
        error = "panorama: empty image";
        return false;
    }
    if (static_cast<long long>(a.width) * a.height * 4 !=
            static_cast<long long>(a.rgba.size()) ||
        static_cast<long long>(b.width) * b.height * 4 !=
            static_cast<long long>(b.rgba.size())) {
        error = "panorama: malformed image buffer";
        return false;
    }
    const int step = std::max(
        1, (std::max(std::max(a.width, a.height),
                     std::max(b.width, b.height)) +
            kCoarseSide - 1) /
               kCoarseSide);
    GrayMap ca, cb;
    if (!buildGray(a, step, ca) || !buildGray(b, step, cb)) {
        error = "panorama: image too small for analysis";
        return false;
    }
    if (ca.flat || cb.flat) {
        error = "panorama: featureless overlap (no gradient to align)";
        return false;
    }
    if (a.width < params.minOverlap || b.width < params.minOverlap ||
        a.height < params.minOverlap || b.height < params.minOverlap) {
        error = "panorama: insufficient overlap (image smaller than minOverlap)";
        return false;
    }
    const int minOverlapC = std::max(1, params.minOverlap / step);
    const int caw = ca.w, cah = ca.h, cbw = cb.w, cbh = cb.h;
    const int loX = std::max(-(cbw - minOverlapC), -(cbw - 1));
    const int hiX = std::min(caw - minOverlapC, caw - 1);
    const int loY = std::max(-(cbh - minOverlapC), -(cbh - 1));
    const int hiY = std::min(cah - minOverlapC, cah - 1);
    if (loX > hiX || loY > hiY) {
        error = "panorama: insufficient overlap (smaller than minOverlap)";
        return false;
    }
    const int cSample =
        std::max(1, std::min(caw, cah) / 48);
    const int cStride = 2;  // coarse px; full refinement covers +-step*2
    float localLast = 0.0f;
    auto report = [&](float t) -> bool {
        if (!pairProgress) return true;
        localLast = std::max(localLast, t);
        return (*pairProgress)(localLast);
    };
    int bestX = 0, bestY = 0;
    float bestS = -1.0f;
    const int rows = (hiY - loY) / cStride + 1;
    int rowIdx = 0;
    for (int cy = loY; cy <= hiY; cy += cStride, ++rowIdx) {
        for (int cx = loX; cx <= hiX; cx += cStride) {
            const float s =
                gradientScore(ca, cb, cx, cy, cSample, 96);
            if (s > bestS) {
                bestS = s;
                bestX = cx;
                bestY = cy;
            }
        }
        if (!report(0.45f * static_cast<float>(rowIdx + 1) / rows)) {
            error = "panorama: cancelled";
            return false;
        }
    }
    if (bestS < 0.0f) {
        error = "panorama: insufficient overlap (no valid candidate)";
        return false;
    }
    // Full-resolution refinement around the coarse peak.
    GrayMap fa, fb;
    if (!buildGray(a, 1, fa) || !buildGray(b, 1, fb)) {
        error = "panorama: image too small for refinement";
        return false;
    }
    const int radius = 2 * step + 2;
    const int fx = bestX * step, fy = bestY * step;
    const int sample =
        std::max(1, std::min(std::min(fa.w, fa.h), std::min(fb.w, fb.h)) / 128);
    const int rx0 = std::max(-(fb.w - params.minOverlap), fx - radius);
    const int rx1 = std::min(fa.w - params.minOverlap, fx + radius);
    const int ry0 = std::max(-(fb.h - params.minOverlap), fy - radius);
    const int ry1 = std::min(fa.h - params.minOverlap, fy + radius);
    int fineX = fx, fineY = fy;
    float fineS = -1.0f;
    const int fyRows = std::max(1, ry1 - ry0 + 1);
    int fRow = 0;
    for (int y = ry0; y <= ry1; ++y, ++fRow) {
        for (int x = rx0; x <= rx1; ++x) {
            const float s = gradientScore(fa, fb, x, y, sample, 64);
            if (s > fineS) {
                fineS = s;
                fineX = x;
                fineY = y;
            }
        }
        if (!report(0.45f + 0.45f * static_cast<float>(fRow) / fyRows)) {
            error = "panorama: cancelled";
            return false;
        }
    }
    const Bounds ov =
        overlapBounds(a.width, a.height, b.width, b.height, fineX, fineY);
    if (!ov.valid() || ov.x1 - ov.x0 < params.minOverlap ||
        ov.y1 - ov.y0 < params.minOverlap) {
        error = "panorama: insufficient overlap (< minOverlap px)";
        return false;
    }
    const long long smaller =
        std::min(static_cast<long long>(a.width) * a.height,
                 static_cast<long long>(b.width) * b.height);
    if (ov.area() * 20 < smaller) {  // < 5% of the smaller image
        error = "panorama: insufficient overlap (< 5% of the smaller image)";
        return false;
    }
    if (fineS < params.minScore) {
        char buf[160];
        std::snprintf(buf, sizeof buf,
                      "panorama: overlap too weak to align (score %.2f < %.2f)",
                      static_cast<double>(fineS),
                      static_cast<double>(params.minScore));
        error = buf;
        return false;
    }
    dx = fineX;
    dy = fineY;
    score = fineS;
    report(1.0f);
    return true;
}

struct ProgressState {
    float last = 0.0f;
};

bool reportOverall(const PanoProgress& cb, ProgressState& st, float t) {
    t = std::max(st.last, std::min(1.0f, t));
    st.last = t;
    return !cb || cb(t);
}

double meanLumaOverlap(const std::vector<std::uint8_t>& dst, int dw, int dh,
                       const PanoImage& src, int ox, int oy, int stride) {
    const Bounds r =
        overlapBounds(dw, dh, src.width, src.height, ox, oy);
    if (!r.valid()) return 0.0;
    stride = std::max(1, stride);
    double sumD = 0.0, sumS = 0.0;
    long long n = 0;
    for (int y = r.y0; y < r.y1; y += stride) {
        for (int x = r.x0; x < r.x1; x += stride) {
            const std::size_t di =
                (static_cast<std::size_t>(y) * dw + x) * 4;
            const std::size_t si =
                (static_cast<std::size_t>(y - oy) * src.width + (x - ox)) * 4;
            if (dst[di + 3] == 0 || src.rgba[si + 3] == 0) continue;
            sumD += lumaAt(dst.data() + di);
            sumS += lumaAt(src.rgba.data() + si);
            ++n;
        }
    }
    if (n == 0 || sumS < 1.0) return 0.0;
    return sumD / sumS;
}

}  // namespace

bool alignPanoramaPair(const PanoImage& a, const PanoImage& b,
                       const PanoParams& params, int& dx, int& dy,
                       float& score, std::string& error) {
    return alignImpl(a, b, params, dx, dy, score, error, nullptr);
}

PanoResult stitchPanorama(const std::vector<PanoImage>& images,
                          const PanoParams& params,
                          const PanoProgress& progress) {
    PanoResult out;
    if (images.size() < 2) {
        out.error = "panorama: at least two images are required";
        return out;
    }
    if (images.size() > kMaxImages) {
        out.error = "panorama: at most 32 images per stitch";
        return out;
    }
    // Clamp caller params to the documented ranges (JNI accepts raw JSON).
    PanoParams p = params;
    p.feather = clampInt(p.feather, 0, 512);
    p.minOverlap = std::max(1, p.minOverlap);
    if (p.maxPixels <= 0) p.maxPixels = 16 * 1024 * 1024;
    p.minScore = std::max(0.0f, std::min(1.0f, p.minScore));
    int maxW = 0, maxH = 0;
    double minAr = 1e9, maxAr = 0.0;
    for (const auto& im : images) {
        if (im.width <= 0 || im.height <= 0 ||
            im.width > kMaxDimension || im.height > kMaxDimension) {
            out.error = "panorama: invalid image dimensions (1..8192 px)";
            return out;
        }
        if (static_cast<long long>(im.width) * im.height * 4 !=
            static_cast<long long>(im.rgba.size())) {
            out.error = "panorama: malformed image buffer";
            return out;
        }
        maxW = std::max(maxW, im.width);
        maxH = std::max(maxH, im.height);
        const double ar = static_cast<double>(im.width) / im.height;
        minAr = std::min(minAr, ar);
        maxAr = std::max(maxAr, ar);
    }
    if (maxAr / std::max(minAr, 1e-9) > 4.0 ||
        static_cast<double>(std::max(maxW, maxH)) /
                std::max(1, std::min(maxW, maxH)) >
            4.0) {
        out.error = "panorama: inconsistent aspect ratios (max 4:1)";
        return out;
    }
    ProgressState ps;
    if (!reportOverall(progress, ps, 0.01f)) {
        out.cancelled = true;
        return out;
    }
    std::vector<PanoOffset> offs(images.size());
    offs[0].x = 0;
    offs[0].y = 0;
    offs[0].score = 1.0f;
    offs[0].gain = 1.0f;
    const int pairs = static_cast<int>(images.size()) - 1;
    for (int i = 1; i < static_cast<int>(images.size()); ++i) {
        int dx = 0, dy = 0;
        float score = 0.0f;
        std::string err;
        const float base = 0.02f + 0.55f * static_cast<float>(i - 1) / pairs;
        const float span = 0.55f / pairs;
        std::function<bool(float)> pairCb;
        if (progress) {
            pairCb = [&](float t) {
                return reportOverall(progress, ps, base + span * t);
            };
        }
        const bool ok = alignImpl(images[i - 1], images[i], p, dx, dy,
                                  score, err,
                                  progress ? &pairCb : nullptr);
        if (!ok) {
            if (err == "panorama: cancelled") {
                out.cancelled = true;
            } else {
                out.error = "image " + std::to_string(i + 1) + ": " + err;
            }
            return out;
        }
        offs[i].dx = dx;
        offs[i].dy = dy;
        offs[i].x = offs[i - 1].x + dx;
        offs[i].y = offs[i - 1].y + dy;
        offs[i].score = score;
        offs[i].gain = 1.0f;
    }
    int minX = 0, minY = 0, maxX = 0, maxY = 0;
    for (int i = 0; i < static_cast<int>(images.size()); ++i) {
        minX = std::min(minX, offs[i].x);
        minY = std::min(minY, offs[i].y);
        maxX = std::max(maxX, offs[i].x + images[i].width);
        maxY = std::max(maxY, offs[i].y + images[i].height);
    }
    const int W = maxX - minX, H = maxY - minY;
    if (W <= 0 || H <= 0 || W > 16384 || H > 16384) {
        out.error = "panorama: degenerate output dimensions";
        return out;
    }
    const long long pixels = static_cast<long long>(W) * H;
    if (pixels > p.maxPixels) {
        char buf[200];
        std::snprintf(buf, sizeof buf,
                      "panorama: output %dx%d exceeds the memory budget "
                      "(%lld px > %d px)",
                      W, H, pixels, p.maxPixels);
        out.error = buf;
        return out;
    }
    std::vector<std::uint8_t> canvas;
    try {
        canvas.assign(static_cast<std::size_t>(pixels) * 4, 0);
    } catch (const std::bad_alloc&) {
        out.error = "panorama: not enough memory for the output canvas";
        return out;
    }
    auto overall = [&](float t) { return reportOverall(progress, ps, t); };
    // Placement relative to the output origin.
    for (int i = 0; i < static_cast<int>(images.size()); ++i) {
        offs[i].x -= minX;
        offs[i].y -= minY;
    }
    const int feather = p.feather;
    for (int i = 0; i < static_cast<int>(images.size()); ++i) {
        const PanoImage& src = images[i];
        const int ox = offs[i].x, oy = offs[i].y;
        float gain = 1.0f;
        if (i > 0 && p.compensateExposure) {
            const int stride =
                std::max(1, std::min(std::min(src.width, src.height), W) / 96);
            const double g = meanLumaOverlap(canvas, W, H, src, ox, oy, stride);
            if (g > 0.0) {
                gain = static_cast<float>(
                    std::max(0.5, std::min(2.0, g)));
            }
        }
        offs[i].gain = gain;
        for (int sy = 0; sy < src.height; ++sy) {
            const int dy = oy + sy;
            if (dy < 0 || dy >= H) continue;
            for (int sx = 0; sx < src.width; ++sx) {
                const int dx = ox + sx;
                if (dx < 0 || dx >= W) continue;
                const std::size_t si =
                    (static_cast<std::size_t>(sy) * src.width + sx) * 4;
                const int sa = src.rgba[si + 3];
                if (sa == 0) continue;
                const std::size_t di =
                    (static_cast<std::size_t>(dy) * W + dx) * 4;
                const int da = canvas[di + 3];
                float w = sa / 255.0f;
                if (da > 0 && feather > 0) {
                    const int edge = std::min(
                        std::min(sx, sy),
                        std::min(src.width - 1 - sx, src.height - 1 - sy));
                    float t = static_cast<float>(edge) / feather;
                    if (t > 1.0f) t = 1.0f;
                    w *= t * t * (3.0f - 2.0f * t);
                }
                if (da == 0) w = 1.0f;  // no overlap: copy, never fade
                if (w <= 0.0f) continue;
                int sr = static_cast<int>(
                    std::lround(src.rgba[si] * static_cast<double>(gain)));
                int sg = static_cast<int>(
                    std::lround(src.rgba[si + 1] * static_cast<double>(gain)));
                int sb = static_cast<int>(
                    std::lround(src.rgba[si + 2] * static_cast<double>(gain)));
                sr = std::min(sr, sa);
                sg = std::min(sg, sa);
                sb = std::min(sb, sa);
                if (da == 0) {
                    canvas[di] = static_cast<std::uint8_t>(sr);
                    canvas[di + 1] = static_cast<std::uint8_t>(sg);
                    canvas[di + 2] = static_cast<std::uint8_t>(sb);
                    canvas[di + 3] = static_cast<std::uint8_t>(sa);
                } else {
                    const double inv = 1.0 - w;
                    canvas[di] = static_cast<std::uint8_t>(std::lround(
                        canvas[di] * inv + sr * w));
                    canvas[di + 1] = static_cast<std::uint8_t>(std::lround(
                        canvas[di + 1] * inv + sg * w));
                    canvas[di + 2] = static_cast<std::uint8_t>(std::lround(
                        canvas[di + 2] * inv + sb * w));
                    canvas[di + 3] = static_cast<std::uint8_t>(std::lround(
                        canvas[di + 3] * inv + sa * w));
                }
            }
            if ((sy & 63) == 0) {
                const float span = 0.40f /
                    static_cast<float>(std::max(1, src.height));
                const float baseT =
                    0.57f + 0.41f * static_cast<float>(i) /
                                static_cast<float>(images.size());
                if (!overall(baseT + span * static_cast<float>(sy))) {
                    out.cancelled = true;
                    return out;
                }
            }
        }
        if (!overall(0.57f + 0.41f * static_cast<float>(i + 1) /
                                  static_cast<float>(images.size()))) {
            out.cancelled = true;
            return out;
        }
    }
    out.width = W;
    out.height = H;
    out.rgba = std::move(canvas);
    out.offsets = std::move(offs);
    // Mean confidence over the aligned pairs (the first image is the
    // reference, not an alignment result).
    double sum = 0.0;
    for (std::size_t i = 1; i < out.offsets.size(); ++i)
        sum += out.offsets[i].score;
    out.meanScore = static_cast<float>(
        sum / std::max<std::size_t>(1, out.offsets.size() - 1));
    if (!overall(1.0f)) {
        out.cancelled = true;
        out.rgba.clear();
        out.width = out.height = 0;
        return out;
    }
    return out;
}

bool loadPanoramaImage(const std::string& path, int maxDimension,
                       int maxPixels, PanoImage& out, std::string& error) {
    int w = 0, h = 0, comp = 0;
    unsigned char* data = stbi_load(path.c_str(), &w, &h, &comp, 4);
    if (!data || w <= 0 || h <= 0) {
        if (data) stbi_image_free(data);
        error = "panorama: cannot decode " + path;
        return false;
    }
    int ow = w, oh = h;
    if (maxDimension > 0 && std::max(w, h) > maxDimension) {
        const double s = static_cast<double>(maxDimension) /
                         std::max(w, h);
        ow = std::max(1, static_cast<int>(std::lround(w * s)));
        oh = std::max(1, static_cast<int>(std::lround(h * s)));
    }
    if (maxPixels > 0 &&
        static_cast<long long>(ow) * oh > maxPixels) {
        stbi_image_free(data);
        error = "panorama: input image exceeds maxInputPixels";
        return false;
    }
    out.width = ow;
    out.height = oh;
    out.rgba.assign(static_cast<std::size_t>(ow) * oh * 4, 0);
    for (int oy = 0; oy < oh; ++oy) {
        const int y0 = static_cast<int>(
            static_cast<long long>(oy) * h / oh);
        const int y1 = std::max(
            y0 + 1, static_cast<int>(
                        static_cast<long long>(oy + 1) * h / oh));
        for (int ox = 0; ox < ow; ++ox) {
            const int x0 = static_cast<int>(
                static_cast<long long>(ox) * w / ow);
            const int x1 = std::max(
                x0 + 1, static_cast<int>(
                            static_cast<long long>(ox + 1) * w / ow));
            unsigned long acc[4] = {0, 0, 0, 0};
            int n = 0;
            for (int y = y0; y < y1 && y < h; ++y) {
                const unsigned char* row =
                    data + (static_cast<std::size_t>(y) * w + x0) * 4;
                for (int x = x0; x < x1 && x < w; ++x, row += 4) {
                    const int a = row[3];
                    acc[0] += static_cast<unsigned long>(row[0] * a / 255);
                    acc[1] += static_cast<unsigned long>(row[1] * a / 255);
                    acc[2] += static_cast<unsigned long>(row[2] * a / 255);
                    acc[3] += static_cast<unsigned long>(a);
                    ++n;
                }
            }
            if (!n) continue;
            const std::size_t di =
                (static_cast<std::size_t>(oy) * ow + ox) * 4;
            out.rgba[di] = static_cast<std::uint8_t>(acc[0] / n);
            out.rgba[di + 1] = static_cast<std::uint8_t>(acc[1] / n);
            out.rgba[di + 2] = static_cast<std::uint8_t>(acc[2] / n);
            out.rgba[di + 3] = static_cast<std::uint8_t>(acc[3] / n);
        }
    }
    stbi_image_free(data);
    return true;
}

bool writePanoramaPng(const std::string& path, const PanoImage& image,
                      std::string& error) {
    if (image.width <= 0 || image.height <= 0 ||
        static_cast<long long>(image.width) * image.height * 4 !=
            static_cast<long long>(image.rgba.size())) {
        error = "panorama: malformed output image";
        return false;
    }
    bool opaque = true;
    for (std::size_t k = 3; k < image.rgba.size(); k += 4)
        if (image.rgba[k] != 255) {
            opaque = false;
            break;
        }
    std::vector<std::uint8_t> straight;
    const std::uint8_t* src = image.rgba.data();
    if (!opaque) {
        straight.resize(image.rgba.size());
        for (std::size_t k = 0; k < image.rgba.size(); k += 4) {
            const int a = image.rgba[k + 3];
            if (a == 0) {
                straight[k] = straight[k + 1] = straight[k + 2] = 0;
            } else {
                straight[k] = static_cast<std::uint8_t>(
                    std::min(255, image.rgba[k] * 255 / a));
                straight[k + 1] = static_cast<std::uint8_t>(
                    std::min(255, image.rgba[k + 1] * 255 / a));
                straight[k + 2] = static_cast<std::uint8_t>(
                    std::min(255, image.rgba[k + 2] * 255 / a));
            }
            straight[k + 3] = static_cast<std::uint8_t>(a);
        }
        src = straight.data();
    }
    if (!stbi_write_png(path.c_str(), image.width, image.height, 4, src,
                        image.width * 4)) {
        error = "panorama: cannot write " + path;
        return false;
    }
    return true;
}

}  // namespace openclip
