// SPDX-License-Identifier: GPL-3.0-or-later
#include "openclip/surface.h"

#include <algorithm>
#include <cstring>

namespace openclip {

void Surface::resize(int w, int h) {
    w_ = w < 0 ? 0 : w;
    h_ = h < 0 ? 0 : h;
    px_.assign(static_cast<std::size_t>(w_) * h_ * 4, 0);
}

void Surface::clear() { std::fill(px_.begin(), px_.end(), 0); }

Pixel Surface::get(int x, int y) const {
    Pixel p;
    if (x < 0 || y < 0 || x >= w_ || y >= h_) return p;
    const std::size_t i = (static_cast<std::size_t>(y) * w_ + x) * 4;
    p.r = px_[i];
    p.g = px_[i + 1];
    p.b = px_[i + 2];
    p.a = px_[i + 3];
    return p;
}

void Surface::set(int x, int y, Pixel p) {
    if (x < 0 || y < 0 || x >= w_ || y >= h_) return;
    const std::size_t i = (static_cast<std::size_t>(y) * w_ + x) * 4;
    px_[i] = p.r;
    px_[i + 1] = p.g;
    px_[i + 2] = p.b;
    px_[i + 3] = p.a;
}

void Surface::tileCopy(int tx, int ty, std::vector<std::uint8_t>& out) const {
    out.assign(static_cast<std::size_t>(kTileSize) * kTileSize * 4, 0);
    const int x0 = tx * kTileSize, y0 = ty * kTileSize;
    for (int y = 0; y < kTileSize; ++y) {
        const int sy = y0 + y;
        if (sy >= h_) break;
        for (int x = 0; x < kTileSize; ++x) {
            const int sx = x0 + x;
            if (sx >= w_) break;
            const std::size_t si = (static_cast<std::size_t>(sy) * w_ + sx) * 4;
            const std::size_t di = (static_cast<std::size_t>(y) * kTileSize + x) * 4;
            std::memcpy(&out[di], &px_[si], 4);
        }
    }
}

void Surface::tileRestore(int tx, int ty, const std::vector<std::uint8_t>& data) {
    const int x0 = tx * kTileSize, y0 = ty * kTileSize;
    for (int y = 0; y < kTileSize; ++y) {
        const int dy = y0 + y;
        if (dy >= h_) break;
        for (int x = 0; x < kTileSize; ++x) {
            const int dx = x0 + x;
            if (dx >= w_) break;
            const std::size_t si = (static_cast<std::size_t>(y) * kTileSize + x) * 4;
            const std::size_t di = (static_cast<std::size_t>(dy) * w_ + dx) * 4;
            if (si + 4 <= data.size()) std::memcpy(&px_[di], &data[si], 4);
        }
    }
}

}  // namespace openclip
