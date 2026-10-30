// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip W1 (issue #28 slice A) — selection morphology implementation.
#include "openclip/selection.h"

#include <algorithm>
#include <cstdlib>
#include <deque>
#include <vector>

#include "openclip/surface.h"

namespace openclip {

void AlphaPlane::resize(int nw, int nh) {
    w = nw < 0 ? 0 : nw;
    h = nh < 0 ? 0 : nh;
    alpha.assign(static_cast<std::size_t>(w) * h, 0);
}

std::uint8_t AlphaPlane::at(int x, int y) const {
    if (x < 0 || y < 0 || x >= w || y >= h) return 0;
    return alpha[static_cast<std::size_t>(y) * w + x];
}

void AlphaPlane::set(int x, int y, std::uint8_t v) {
    if (x < 0 || y < 0 || x >= w || y >= h) return;
    alpha[static_cast<std::size_t>(y) * w + x] = v;
}

namespace {

int clampRadius(int r) {
    return std::clamp(r, 0, kSelectionRadiusMax);
}

// One-dimensional sliding extreme over a padded line (outside = pad).
std::vector<std::uint8_t> filter1D(const std::vector<std::uint8_t>& line,
                                   int r, bool maximum, std::uint8_t pad) {
    const int n = static_cast<int>(line.size());
    std::vector<std::uint8_t> out(static_cast<std::size_t>(n), 0);
    if (n == 0 || r <= 0) return line;
    std::vector<std::uint8_t> padded(static_cast<std::size_t>(n + 2 * r), pad);
    for (int i = 0; i < n; ++i) padded[static_cast<std::size_t>(i + r)] = line[static_cast<std::size_t>(i)];
    std::deque<int> dq;
    const int total = static_cast<int>(padded.size());
    for (int i = 0; i < total; ++i) {
        while (!dq.empty()) {
            const std::uint8_t back = padded[static_cast<std::size_t>(dq.back())];
            const std::uint8_t cur = padded[static_cast<std::size_t>(i)];
            if (maximum ? back <= cur : back >= cur)
                dq.pop_back();
            else
                break;
        }
        dq.push_back(i);
        if (i >= 2 * r) {
            const int outX = i - 2 * r;
            while (dq.front() < outX) dq.pop_front();
            out[static_cast<std::size_t>(outX)] =
                padded[static_cast<std::size_t>(dq.front())];
        }
    }
    return out;
}

void extreme(AlphaPlane& p, int r, bool maximum) {
    r = clampRadius(r);
    if (r <= 0 || p.empty()) return;
    const std::uint8_t pad = maximum ? 0 : 255;
    std::vector<std::uint8_t> line;
    // Horizontal pass.
    for (int y = 0; y < p.h; ++y) {
        line.assign(p.alpha.begin() + static_cast<std::size_t>(y) * p.w,
                    p.alpha.begin() + static_cast<std::size_t>(y + 1) * p.w);
        const std::vector<std::uint8_t> out = filter1D(line, r, maximum, pad);
        std::copy(out.begin(), out.end(),
                  p.alpha.begin() + static_cast<std::size_t>(y) * p.w);
    }
    // Vertical pass.
    line.assign(static_cast<std::size_t>(p.h), 0);
    for (int x = 0; x < p.w; ++x) {
        for (int y = 0; y < p.h; ++y)
            line[static_cast<std::size_t>(y)] =
                p.alpha[static_cast<std::size_t>(y) * p.w + x];
        const std::vector<std::uint8_t> out = filter1D(line, r, maximum, pad);
        for (int y = 0; y < p.h; ++y)
            p.alpha[static_cast<std::size_t>(y) * p.w + x] =
                out[static_cast<std::size_t>(y)];
    }
}

// One separable box-blur pass with a running window sum (radius r).
void boxPass(AlphaPlane& p, int r) {
    if (r <= 0) return;
    const int win = 2 * r + 1;
    std::vector<int> line;
    // Horizontal.
    line.assign(static_cast<std::size_t>(p.w + 2 * r), 0);
    for (int y = 0; y < p.h; ++y) {
        std::fill(line.begin(), line.end(), 0);
        for (int x = 0; x < p.w; ++x)
            line[static_cast<std::size_t>(x + r)] =
                p.alpha[static_cast<std::size_t>(y) * p.w + x];
        int sum = 0;
        for (int i = 0; i < win && i < static_cast<int>(line.size()); ++i)
            sum += line[static_cast<std::size_t>(i)];
        std::vector<std::uint8_t> out(static_cast<std::size_t>(p.w), 0);
        for (int x = 0; x < p.w; ++x) {
            out[static_cast<std::size_t>(x)] =
                static_cast<std::uint8_t>((sum + win / 2) / win);
            const int add = x + win;
            if (add < static_cast<int>(line.size())) sum += line[static_cast<std::size_t>(add)];
            sum -= line[static_cast<std::size_t>(x)];
        }
        std::copy(out.begin(), out.end(),
                  p.alpha.begin() + static_cast<std::size_t>(y) * p.w);
    }
    // Vertical.
    line.assign(static_cast<std::size_t>(p.h + 2 * r), 0);
    for (int x = 0; x < p.w; ++x) {
        std::fill(line.begin(), line.end(), 0);
        for (int y = 0; y < p.h; ++y)
            line[static_cast<std::size_t>(y + r)] =
                p.alpha[static_cast<std::size_t>(y) * p.w + x];
        int sum = 0;
        for (int i = 0; i < win && i < static_cast<int>(line.size()); ++i)
            sum += line[static_cast<std::size_t>(i)];
        std::vector<std::uint8_t> out(static_cast<std::size_t>(p.h), 0);
        for (int y = 0; y < p.h; ++y) {
            out[static_cast<std::size_t>(y)] =
                static_cast<std::uint8_t>((sum + win / 2) / win);
            const int add = y + win;
            if (add < static_cast<int>(line.size())) sum += line[static_cast<std::size_t>(add)];
            sum -= line[static_cast<std::size_t>(y)];
        }
        for (int y = 0; y < p.h; ++y)
            p.alpha[static_cast<std::size_t>(y) * p.w + x] =
                out[static_cast<std::size_t>(y)];
    }
}

}  // namespace

void growPlane(AlphaPlane& p, int radius) { extreme(p, radius, true); }

void shrinkPlane(AlphaPlane& p, int radius) { extreme(p, radius, false); }

void featherPlane(AlphaPlane& p, int radius) {
    const int r = clampRadius(radius);
    if (r <= 0 || p.empty()) return;
    boxPass(p, r);
    boxPass(p, r);
}

void fillPolygonPlane(AlphaPlane& p,
                      const std::vector<std::pair<float, float>>& points) {
    if (p.empty() || points.size() < 3) return;
    float minYf = points[0].second, maxYf = points[0].second;
    for (const auto& pt : points) {
        minYf = std::min(minYf, pt.second);
        maxYf = std::max(maxYf, pt.second);
    }
    const int minY = std::clamp(static_cast<int>(minYf), 0, p.h - 1);
    const int maxY = std::clamp(static_cast<int>(maxYf), 0, p.h - 1);
    std::vector<float> xs;
    for (int y = minY; y <= maxY; ++y) {
        const float cy = static_cast<float>(y) + 0.5f;
        xs.clear();
        std::size_t j = points.size() - 1;
        for (std::size_t i = 0; i < points.size(); ++i) {
            const float xi = points[i].first, yi = points[i].second;
            const float xj = points[j].first, yj = points[j].second;
            if ((yi > cy) != (yj > cy)) {
                xs.push_back(xi + (cy - yi) / (yj - yi) * (xj - xi));
            }
            j = i;
        }
        std::sort(xs.begin(), xs.end());
        for (std::size_t k = 0; k + 1 < xs.size(); k += 2) {
            const int l = std::clamp(static_cast<int>(xs[k]), 0, p.w - 1);
            const int r = std::clamp(static_cast<int>(xs[k + 1]), 0, p.w - 1);
            for (int x = l; x <= r; ++x)
                p.alpha[static_cast<std::size_t>(y) * p.w + x] = 255;
        }
    }
    // Outline: keeps 1px-thin polygons selectable.
    for (std::size_t i = 0; i < points.size(); ++i) {
        const std::size_t n = (i + 1) % points.size();
        const float x0 = points[i].first, y0 = points[i].second;
        const float x1 = points[n].first, y1 = points[n].second;
        const float dx = x1 - x0, dy = y1 - y0;
        const int steps = static_cast<int>(std::max(std::abs(dx), std::abs(dy))) + 1;
        for (int s = 0; s <= steps; ++s) {
            const float t = steps > 0 ? static_cast<float>(s) / steps : 0.0f;
            p.set(static_cast<int>(x0 + dx * t), static_cast<int>(y0 + dy * t), 255);
        }
    }
}

void alphaPlaneFromSurface(const Surface& s, int threshold, AlphaPlane& p) {
    const int th = std::clamp(threshold, 0, 255);
    p.resize(s.width(), s.height());
    for (int y = 0; y < p.h; ++y) {
        for (int x = 0; x < p.w; ++x) {
            const int a = s.get(x, y).a;
            p.alpha[static_cast<std::size_t>(y) * p.w + x] =
                a >= th ? static_cast<std::uint8_t>(a) : 0;
        }
    }
}

int planeCoverage(const AlphaPlane& p) {
    int n = 0;
    for (const std::uint8_t v : p.alpha)
        if (v != 0) ++n;
    return n;
}

}  // namespace openclip
