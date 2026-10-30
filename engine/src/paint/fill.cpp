// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip T1 engine — bucket/wand flood kernel (see fill.h).
#include "openclip/fill.h"

#include <cmath>
#include <queue>
#include <utility>
#include <vector>

#include "openclip/surface.h"

namespace openclip {
namespace {

// Rec.601 luma, same weights as the gradientMap filter.
inline double luma(const std::uint8_t* px) {
    return 0.299 * px[0] + 0.587 * px[1] + 0.114 * px[2];
}

// Core region walk: 4-connected pixels around (sx,sy) whose luma is
// within `tol` of the SEED luma. Runs on raw premultiplied bytes
// (alpha ignored by the metric, like the seed sampling). Writes the
// region into `inside` (1 = member). Returns false (inside cleared)
// on empty surface, seed outside, or allocation failure.
bool walkRegion(const std::uint8_t* bytes, int w, int h, int sx, int sy,
                double seedLum, double tol, std::vector<char>& inside) {
    inside.clear();
    if (w <= 0 || h <= 0) return false;
    if (sx < 0 || sy < 0 || sx >= w || sy >= h) return false;
    std::vector<char> seen;
    try {
        seen.assign(static_cast<std::size_t>(w) * h, 0);
        inside.assign(static_cast<std::size_t>(w) * h, 0);
    } catch (...) {
        inside.clear();
        return false;  // OOM: caller leaves its surface untouched
    }
    std::vector<int> stack;
    stack.reserve(1024);
    stack.push_back(sy * w + sx);
    seen[sy * w + sx] = 1;
    while (!stack.empty()) {
        const int at = stack.back();
        stack.pop_back();
        const int x = at % w, y = at / w;
        const double d = std::fabs(luma(bytes + at * 4) - seedLum);
        if (d > tol) continue;
        inside[at] = 1;
        if (x > 0 && !seen[at - 1]) {
            seen[at - 1] = 1;
            stack.push_back(at - 1);
        }
        if (x + 1 < w && !seen[at + 1]) {
            seen[at + 1] = 1;
            stack.push_back(at + 1);
        }
        if (y > 0 && !seen[at - w]) {
            seen[at - w] = 1;
            stack.push_back(at - w);
        }
        if (y + 1 < h && !seen[at + w]) {
            seen[at + w] = 1;
            stack.push_back(at + w);
        }
    }
    return true;
}

// B1 (ARCHITECTURE.md "B1 additions" §1): gap closing + surround.
// member = every pixel within `tol` of the seed luma (global metric,
// same Rec.601 luma as walkRegion). The barrier (non-member) is dilated
// by `gapPx` (square), then:
//   normal  : 4-connected free component containing the seed;
//   surround: every enclosed cavity (free space not reachable from the
//             canvas border), the seed must sit inside one.
// With gapPx > 0 the region is grown back by gapPx limited to original
// member pixels (the closing eats a rim; the grow restores it), so the
// painted set never covers the barrier. Returns false + region cleared
// on seed outside / seed on the barrier / surround seed not enclosed /
// allocation failure.
bool closedRegion(const std::uint8_t* bytes, int w, int h, int sx, int sy,
                  double seedLum, double tol, int gapPx, bool surround,
                  std::vector<char>& region) {
    region.clear();
    if (w <= 0 || h <= 0) return false;
    if (sx < 0 || sy < 0 || sx >= w || sy >= h) return false;
    const std::size_t n = static_cast<std::size_t>(w) * h;
    std::vector<char> member;
    try {
        member.resize(n, 0);
    } catch (...) {
        return false;
    }
    for (std::size_t i = 0; i < n; ++i)
        member[i] = std::fabs(luma(bytes + i * 4) - seedLum) <= tol ? 1 : 0;
    // Square dilation of the barrier (separable, early-exit windows).
    std::vector<char> dil(n, 0);
    if (gapPx > 0) {
        std::vector<char> tmp(n, 0);
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                char hit = 0;
                for (int dx = -gapPx; dx <= gapPx && !hit; ++dx) {
                    const int xx = x + dx;
                    if (xx < 0 || xx >= w) continue;
                    if (!member[static_cast<std::size_t>(y) * w + xx]) hit = 1;
                }
                tmp[static_cast<std::size_t>(y) * w + x] = hit;
            }
        }
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                char hit = 0;
                for (int dy = -gapPx; dy <= gapPx && !hit; ++dy) {
                    const int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    if (tmp[static_cast<std::size_t>(yy) * w + x]) hit = 1;
                }
                dil[static_cast<std::size_t>(y) * w + x] = hit;
            }
        }
    } else {
        for (std::size_t i = 0; i < n; ++i) dil[i] = member[i] ? 0 : 1;
    }
    std::vector<char> freePx;
    try {
        freePx.resize(n, 0);
    } catch (...) {
        return false;
    }
    for (std::size_t i = 0; i < n; ++i) freePx[i] = dil[i] ? 0 : 1;
    const std::size_t seedAt = static_cast<std::size_t>(sy) * w + sx;
    if (!freePx[seedAt]) return false;  // seed lies on the closed barrier
    std::vector<char> work;
    try {
        work.assign(n, 0);
    } catch (...) {
        return false;
    }
    std::vector<int> stack;
    stack.reserve(4096);
    if (surround) {
        // Border flood (all border free pixels as seeds) = outside.
        for (int x = 0; x < w; ++x) {
            const int top = x, bot = (h - 1) * w + x;
            if (freePx[static_cast<std::size_t>(top)] && !work[static_cast<std::size_t>(top)]) {
                work[static_cast<std::size_t>(top)] = 1;
                stack.push_back(top);
            }
            if (freePx[static_cast<std::size_t>(bot)] && !work[static_cast<std::size_t>(bot)]) {
                work[static_cast<std::size_t>(bot)] = 1;
                stack.push_back(bot);
            }
        }
        for (int y = 1; y + 1 < h; ++y) {
            const int l = y * w, r = y * w + (w - 1);
            if (freePx[static_cast<std::size_t>(l)] && !work[static_cast<std::size_t>(l)]) {
                work[static_cast<std::size_t>(l)] = 1;
                stack.push_back(l);
            }
            if (freePx[static_cast<std::size_t>(r)] && !work[static_cast<std::size_t>(r)]) {
                work[static_cast<std::size_t>(r)] = 1;
                stack.push_back(r);
            }
        }
        while (!stack.empty()) {
            const int at = stack.back();
            stack.pop_back();
            const int x = at % w, y = at / w;
            auto push = [&](int nb) {
                if (!work[static_cast<std::size_t>(nb)] &&
                    freePx[static_cast<std::size_t>(nb)]) {
                    work[static_cast<std::size_t>(nb)] = 1;
                    stack.push_back(nb);
                }
            };
            if (x > 0) push(at - 1);
            if (x + 1 < w) push(at + 1);
            if (y > 0) push(at - w);
            if (y + 1 < h) push(at + w);
        }
        try {
            region.resize(n, 0);
        } catch (...) {
            return false;
        }
        for (std::size_t i = 0; i < n; ++i)
            region[i] = (freePx[i] && !work[i]) ? 1 : 0;
        if (!region[seedAt]) {
            region.clear();  // seed not inside an enclosed cavity
            return false;
        }
    } else {
        std::vector<char> seen;
        try {
            seen.assign(n, 0);
        } catch (...) {
            return false;
        }
        try {
            region.assign(n, 0);
        } catch (...) {
            return false;
        }
        stack.push_back(static_cast<int>(seedAt));
        seen[seedAt] = 1;
        while (!stack.empty()) {
            const int at = stack.back();
            stack.pop_back();
            if (!freePx[static_cast<std::size_t>(at)]) continue;
            region[static_cast<std::size_t>(at)] = 1;
            const int x = at % w, y = at / w;
            auto push = [&](int nb) {
                if (!seen[static_cast<std::size_t>(nb)]) {
                    seen[static_cast<std::size_t>(nb)] = 1;
                    stack.push_back(nb);
                }
            };
            if (x > 0) push(at - 1);
            if (x + 1 < w) push(at + 1);
            if (y > 0) push(at - w);
            if (y + 1 < h) push(at + w);
        }
    }
    // Grow back by gapPx limited to original member pixels (BFS depth).
    if (gapPx > 0) {
        std::vector<int> frontier;
        for (std::size_t i = 0; i < n; ++i)
            if (region[i]) frontier.push_back(static_cast<int>(i));
        for (int step = 0; step < gapPx && !frontier.empty(); ++step) {
            std::vector<int> next;
            for (int at : frontier) {
                const int x = at % w, y = at / w;
                auto grow = [&](int nb) {
                    const std::size_t u = static_cast<std::size_t>(nb);
                    if (region[u] || !member[u]) return;
                    region[u] = 1;
                    next.push_back(nb);
                };
                if (x > 0) grow(at - 1);
                if (x + 1 < w) grow(at + 1);
                if (y > 0) grow(at - w);
                if (y + 1 < h) grow(at + w);
            }
            frontier.swap(next);
        }
    }
    return true;
}

inline std::uint8_t clamp255(double v) {
    const long r = std::lround(v);
    return static_cast<std::uint8_t>(r < 0 ? 0 : (r > 255 ? 255 : r));
}

}  // namespace

bool floodFillSeeded(Surface& s, int x, int y, double seedLum,
                     std::uint8_t r, std::uint8_t g, std::uint8_t b,
                     const FillParams& p, const std::uint8_t* mask,
                     int maskStride, std::string& error, bool alphaLock) {
    error.clear();
    const int w = s.width(), h = s.height();
    if (w <= 0 || h <= 0 || x < 0 || y < 0 || x >= w || y >= h) {
        error = "floodFill: seed outside the surface";
        return false;
    }
    if (!(p.tolerance >= 0.0f) || p.tolerance > 1.0f || p.expandPx < 0 ||
        p.expandPx > 2) {
        error = "floodFill: tolerance must be in [0,1], expandPx in [0,2]";
        return false;
    }
    if (p.gapPx < 0 || p.gapPx > 16) {
        error = "floodFill: gapPx must be in [0,16]";
        return false;
    }
    std::vector<std::uint8_t>& bytes = s.bytes();
    const double tol = p.tolerance * 255.0;
    std::vector<char> inside;
    // B1: gap closing + surround ride the same kernel. The legacy path
    // (gapPx 0, surround false) is byte-identical (walkRegion).
    if (p.gapPx == 0 && !p.surround) {
        if (!walkRegion(bytes.data(), w, h, x, y, seedLum, tol, inside)) {
            error = "floodFill: region walk failed";
            return false;
        }
    } else if (!closedRegion(bytes.data(), w, h, x, y, seedLum, tol,
                             p.gapPx, p.surround, inside)) {
        error = p.surround
                    ? "floodFill: seed not inside an enclosed region"
                    : "floodFill: seed lies on the closed barrier";
        return false;
    }
    // Optional outward dilation (anti-halo): paint region OR-grown by
    // expandPx pixels (4-connected steps, mask still applies after).
    std::vector<char> grown;
    if (p.expandPx > 0) {
        grown = inside;
        for (int step = 0; step < p.expandPx; ++step) {
            const std::vector<char> prev = grown;
            for (int yy = 0; yy < h; ++yy)
                for (int xx = 0; xx < w; ++xx) {
                    const int at = yy * w + xx;
                    if (prev[at]) continue;
                    if ((xx > 0 && prev[at - 1]) ||
                        (xx + 1 < w && prev[at + 1]) ||
                        (yy > 0 && prev[at - w]) ||
                        (yy + 1 < h && prev[at + w]))
                        grown[at] = 1;
                }
        }
    }
    const std::vector<char>& paint = p.expandPx > 0 ? grown : inside;
    const double inkR = r, inkG = g, inkB = b;
    for (int yy = 0; yy < h; ++yy)
        for (int xx = 0; xx < w; ++xx) {
            const int at = yy * w + xx;
            if (!paint[at]) continue;
            if (mask && mask[static_cast<std::size_t>(yy) * maskStride + xx] == 0)
                continue;  // outside the selection: untouched
            std::size_t i = static_cast<std::size_t>(at) * 4;
            // T5 §1 verrou alpha: transparent destination pixels are
            // skipped (ink is kept where the layer is transparent).
            if (alphaLock && bytes[i + 3] == 0) continue;
            if (p.antialias && !inside[at]) {
                // Expanded fringe: half-strength source-over (soft rim,
                // premultiplied-safe: dst*(1-sa) + ink*sa, alpha up).
                const double a0 = bytes[i + 3] / 255.0;
                const double sa = 0.5;
                const double oa = sa + a0 * (1.0 - sa);
                bytes[i] = clamp255(bytes[i] * (1.0 - sa) + inkR * sa);
                bytes[i + 1] = clamp255(bytes[i + 1] * (1.0 - sa) + inkG * sa);
                bytes[i + 2] = clamp255(bytes[i + 2] * (1.0 - sa) + inkB * sa);
                bytes[i + 3] = clamp255(oa * 255.0);
            } else if (!p.antialias || inside[at]) {
                double coverage = 1.0;
                if (p.antialias) {
                    // 1px frontier softening: a member pixel touching a
                    // non-member 4-neighbor paints at half coverage (a
                    // documented 1px approximation — no subpixel
                    // geometry; flat fills still soften their rim,
                    // gradients keep full interior coverage).
                    if ((xx > 0 && !inside[at - 1]) ||
                        (xx + 1 < w && !inside[at + 1]) ||
                        (yy > 0 && !inside[at - w]) ||
                        (yy + 1 < h && !inside[at + w]))
                        coverage = 0.5;
                }
                const double a0 = bytes[i + 3] / 255.0;
                const double sa = coverage;
                const double oa = sa + a0 * (1.0 - sa);
                bytes[i] = clamp255(bytes[i] * (1.0 - sa) + inkR * sa);
                bytes[i + 1] = clamp255(bytes[i + 1] * (1.0 - sa) + inkG * sa);
                bytes[i + 2] = clamp255(bytes[i + 2] * (1.0 - sa) + inkB * sa);
                bytes[i + 3] = clamp255(oa * 255.0);
            }
        }
    return true;
}

bool magicWand(const Surface& src, int x, int y, float tolerance,
               int combine, std::uint8_t* maskOut, int maskStride,
               std::string& error) {
    error.clear();
    const int w = src.width(), h = src.height();
    if (!maskOut || w <= 0 || h <= 0 || x < 0 || y < 0 || x >= w || y >= h) {
        error = "magicWand: seed outside the surface";
        return false;
    }
    if (!(tolerance >= 0.0f) || tolerance > 1.0f || combine < 0 ||
        combine > 2) {
        error = "magicWand: tolerance must be in [0,1], combine in [0,2]";
        return false;
    }
    const std::vector<std::uint8_t>& bytes = src.bytes();
    const double seedLum = luma(&bytes[(static_cast<std::size_t>(y) * w + x) * 4]);
    std::vector<char> inside;
    if (!walkRegion(bytes.data(), w, h, x, y, seedLum, tolerance * 255.0,
                    inside)) {
        error = "magicWand: region walk failed";
        return false;
    }
    for (int yy = 0; yy < h; ++yy)
        for (int xx = 0; xx < w; ++xx) {
            std::uint8_t& m =
                maskOut[static_cast<std::size_t>(yy) * maskStride + xx];
            const bool hit = inside[yy * w + xx] != 0;
            if (combine == 0)
                m = hit ? 255 : 0;
            else if (combine == 1) {
                if (hit) m = 255;
            } else {
                if (hit) m = 0;
            }
        }
    return true;
}

bool floodFill(Surface& s, int x, int y, std::uint8_t r, std::uint8_t g,
               std::uint8_t b, const FillParams& p,
               const std::uint8_t* mask, int maskStride, std::string& error,
               bool alphaLock) {
    const int w = s.width(), h = s.height();
    if (w <= 0 || h <= 0 || x < 0 || y < 0 || x >= w || y >= h) {
        error = "floodFill: seed outside the surface";
        return false;
    }
    const std::vector<std::uint8_t>& bytes = s.bytes();
    const double seedLum =
        luma(&bytes[(static_cast<std::size_t>(y) * w + x) * 4]);
    return floodFillSeeded(s, x, y, seedLum, r, g, b, p, mask, maskStride,
                           error, alphaLock);
}

}  // namespace openclip
