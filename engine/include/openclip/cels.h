// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip V+2 — cel rhythm + reorder + onion ranges (clean-room).
// Pure data logic (no Canvas/History dependency) so the shim owns the
// frames while engine tests lock the semantics: hold bounds, exact-LIFO
// move undo pairing, neighbor index ranges.
#ifndef OPENCLIP_CELS_H
#define OPENCLIP_CELS_H

#include <cstddef>
#include <utility>
#include <vector>

namespace openclip {

// Per-cel exposure multiplier. 1 = one slice of time, N = held N
// slices. Bounds 1..32 (ARCHITECTURE.md "V+2 additions"); out of range
// is rejected by the caller (rhythm unchanged).
inline constexpr int kCelHoldMin = 1;
inline constexpr int kCelHoldMax = 32;

inline bool validCelHold(int frames) {
    return frames >= kCelHoldMin && frames <= kCelHoldMax;
}

// Onion neighbor counts per side. Bounds 0..4 (same steppers as the
// former overlay-only counts).
inline constexpr int kOnionCountMin = 0;
inline constexpr int kOnionCountMax = 4;

inline bool validOnionCount(int n) {
    return n >= kOnionCountMin && n <= kOnionCountMax;
}

// Erase+insert permutation of [0..n): element at `from` moves to
// `to`. Returns the new index of the element that used to be `cur`
// (so the shim can keep `cur` following the moved cel). Precondition:
// 0 <= from,to < n, n > 0.
inline std::size_t moveCursor(std::size_t n, std::size_t from,
                              std::size_t to, std::size_t cur) {
    if (cur == from) return to;
    if (from < to) {
        if (cur > from && cur <= to) return cur - 1;
        return cur;
    }
    if (cur >= to && cur < from) return cur + 1;
    return cur;
    (void)n;
}

// Neighbor indexes for onion skin: up to `before` previous and `after`
// next indexes around `cur` in [0..count), nearest first.
inline std::vector<std::size_t> onionRange(std::size_t cur,
                                            std::size_t count, int before,
                                            int after) {
    std::vector<std::size_t> out;
    for (int k = 1; k <= before; ++k) {
        if (cur < static_cast<std::size_t>(k)) break;
        out.push_back(cur - static_cast<std::size_t>(k));
    }
    for (int k = 1; k <= after; ++k) {
        if (cur + static_cast<std::size_t>(k) >= count) break;
        out.push_back(cur + static_cast<std::size_t>(k));
    }
    return out;
}

// GCE delay (centiseconds) for a held cel: round(hold * 100 / fps).
// fps <= 0 falls back to 12 (the M7 app-side default).
inline int celDelayCs(int hold, double fps) {
    if (fps <= 0.0) fps = 12.0;
    if (!validCelHold(hold)) hold = 1;
    const double v = hold * 100.0 / fps;
    return static_cast<int>(v + 0.5);
}

}  // namespace openclip

#endif
