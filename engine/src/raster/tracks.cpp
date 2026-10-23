// SPDX-License-Identifier: GPL-3.0-or-later
#include "openclip/tracks.h"

namespace openclip {

void compositeTrackStack(
    const std::vector<std::pair<const Surface*, bool>>& tracks, int w,
    int h, Surface& out) {
    out.resize(w, h);
    out.clear();
    if (w <= 0 || h <= 0) return;
    for (const auto& t : tracks) {
        if (!t.second || !t.first) continue;  // muted or missing
        const Surface& s = *t.first;
        if (s.width() != w || s.height() != h) continue;
        const std::vector<std::uint8_t>& px = s.bytes();
        std::vector<std::uint8_t>& dst = out.bytes();
        for (std::size_t i = 0; i < static_cast<std::size_t>(w) * h; ++i) {
            const int sa = px[i * 4 + 3];
            if (sa == 0) continue;
            if (sa == 255) {
                dst[i * 4 + 0] = px[i * 4 + 0];
                dst[i * 4 + 1] = px[i * 4 + 1];
                dst[i * 4 + 2] = px[i * 4 + 2];
                dst[i * 4 + 3] = 255;
                continue;
            }
            const int ia = 255 - sa;
            dst[i * 4 + 0] = static_cast<std::uint8_t>(
                px[i * 4 + 0] + (dst[i * 4 + 0] * ia + 127) / 255);
            dst[i * 4 + 1] = static_cast<std::uint8_t>(
                px[i * 4 + 1] + (dst[i * 4 + 1] * ia + 127) / 255);
            dst[i * 4 + 2] = static_cast<std::uint8_t>(
                px[i * 4 + 2] + (dst[i * 4 + 2] * ia + 127) / 255);
            dst[i * 4 + 3] = static_cast<std::uint8_t>(
                sa + (dst[i * 4 + 3] * ia + 127) / 255);
        }
    }
}

}  // namespace openclip
