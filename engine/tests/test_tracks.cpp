// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip T3 — multi-track rules + stack merge (ARCHITECTURE.md
// "T3 additions" §3). Pure helpers in tracks.h; the shim owns the
// per-track frame stores.
#include "test.h"

#include "openclip/canvas.h"
#include "openclip/tracks.h"

using openclip::canAddTrack;
using openclip::canDeleteTrack;
using openclip::resolveStackIndex;
using openclip::sanitizeTrackName;
using openclip::validTrackIndex;

// §3: a new session starts with exactly 1 track (add allowed, delete
// refused — the >= 1 track guard).
TEST(tracksDefaultOne) {
    CHECK(!canDeleteTrack(1));
    CHECK(canAddTrack(1));
    CHECK(validTrackIndex(1, 0));
    CHECK(!validTrackIndex(1, 1));
}

// §3: cap at 4 tracks (5th refused).
TEST(trackAddCapFour) {
    CHECK(canAddTrack(3));
    CHECK(!canAddTrack(4));
    CHECK(!canAddTrack(5));
    CHECK_EQ(openclip::kMaxTracks, 4);
}

// §3: delete keeps >= 1 track.
TEST(trackDeleteKeepsOne) {
    CHECK(!canDeleteTrack(1));
    CHECK(canDeleteTrack(2));
    CHECK(canDeleteTrack(4));
}

// §3: muted track absent from the merge; solo visible = its pixels.
TEST(trackHiddenSkipped) {
    openclip::Surface red(8, 8), blue(8, 8);
    for (int y = 0; y < 8; ++y)
        for (int x = 0; x < 8; ++x) {
            red.set(x, y, openclip::Pixel{255, 0, 0, 255});
            blue.set(x, y, openclip::Pixel{0, 0, 255, 255});
        }
    openclip::Surface out;
    openclip::compositeTrackStack({{&red, true}, {&blue, false}}, 8, 8,
                                  out);
    CHECK_EQ(out.get(4, 4).r, 255);
    CHECK_EQ(out.get(4, 4).b, 0);
    openclip::compositeTrackStack({{&red, false}, {&blue, false}}, 8, 8,
                                  out);
    CHECK_EQ(out.get(4, 4).a, 0);  // all muted = transparent
    openclip::compositeTrackStack({{&red, true}, {&blue, true}}, 8, 8,
                                  out);
    CHECK_EQ(out.get(4, 4).b, 255);  // top track wins opaque
}

// §3: per-track canvases stay isolated (stroke on one never leaks).
TEST(trackFramesIsolated) {
    openclip::Canvas a(8, 8), b(8, 8);
    a.addLayer("a");
    b.addLayer("b");
    for (int y = 0; y < 8; ++y)
        for (int x = 0; x < 8; ++x) {
            a.layer(0).surface.set(x, y, openclip::Pixel{255, 0, 0, 255});
            b.layer(0).surface.set(x, y, openclip::Pixel{0, 0, 0, 0});
        }
    openclip::Surface sa, sb, out;
    a.composite(sa);
    b.composite(sb);
    openclip::compositeTrackStack({{&sa, true}, {&sb, true}}, 8, 8, out);
    CHECK_EQ(out.get(0, 0).r, 255);  // transparent top keeps bottom
    CHECK_EQ(sb.get(0, 0).a, 0);     // ...which stays empty on its own
}

// §3: cel addressing clamps into each track's range (other tracks
// follow the requested index, clamped — never out of range).
TEST(trackFrameOpsScopeActive) {
    CHECK_EQ(resolveStackIndex(3, 5), 2);
    CHECK_EQ(resolveStackIndex(3, -1), 0);
    CHECK_EQ(resolveStackIndex(1, 7), 0);
    CHECK_EQ(resolveStackIndex(4, 2), 2);
    // Names truncate at 24 chars (the nSetTrackName guard).
    CHECK_EQ(sanitizeTrackName("P1"), "P1");
    CHECK_EQ(sanitizeTrackName(std::string(40, 'x')).size(), 24u);
}
