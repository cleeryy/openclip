// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip T3 — multi-track helpers (clean-room). Pure index/name
// rules plus the visible-stack merge (source-over of pre-composited
// track surfaces), so engine tests lock the semantics while the shim
// owns the per-track frame stores (ARCHITECTURE.md "T3 additions").
#ifndef OPENCLIP_TRACKS_H
#define OPENCLIP_TRACKS_H

#include <cstdint>
#include <string>
#include <utility>
#include <vector>

#include "openclip/surface.h"

namespace openclip {

inline constexpr int kMaxTracks = 4;
inline constexpr int kMaxTrackNameLen = 24;

// Add/delete guards: cap at 4, always keep >= 1 (spec §3.4/§3.6).
inline bool canAddTrack(int n) { return n >= 1 && n < kMaxTracks; }
inline bool canDeleteTrack(int n) { return n > 1 && n <= kMaxTracks; }
inline bool validTrackIndex(int n, int i) {
    return n >= 1 && i >= 0 && i < n;
}

// Other-track cel addressing: the requested cel clamped into the
// track's range (ARCHITECTURE.md §T3 §3).
inline int resolveStackIndex(int count, int want) {
    if (count <= 0) return 0;
    if (want < 0) return 0;
    if (want >= count) return count - 1;
    return want;
}

// Track names: truncated to 24 chars (spec §3.4), empty stays empty
// (the app falls back to P1..P4 display names).
inline std::string sanitizeTrackName(const std::string& name) {
    if (name.size() <= static_cast<std::size_t>(kMaxTrackNameLen))
        return name;
    return name.substr(0, kMaxTrackNameLen);
}

// Merge pre-composited track surfaces bottom-to-top (premultiplied
// source-over). `visible[i]` false = track skipped (mute). All
// surfaces must share dimensions; mismatched entries are skipped.
// Empty/fully-hidden input = transparent surface of `w x h`.
void compositeTrackStack(
    const std::vector<std::pair<const Surface*, bool>>& tracks, int w,
    int h, Surface& out);

}  // namespace openclip

#endif
