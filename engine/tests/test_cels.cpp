// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip V+2 — cel rhythm / move / onion-range tests (pure logic,
// shim owns the frames; ARCHITECTURE.md "V+2 additions").
#include "test.h"

#include "openclip/cels.h"

using openclip::celDelayCs;
using openclip::moveCursor;
using openclip::onionRange;
using openclip::validCelHold;
using openclip::validOnionCount;

// Hold bounds 1..32, nothing else.
TEST(cels_hold_bounds) {
    CHECK(validCelHold(1));
    CHECK(validCelHold(32));
    CHECK(!validCelHold(0));
    CHECK(!validCelHold(33));
    CHECK(!validCelHold(-1));
    CHECK_EQ(celDelayCs(1, 12.0), 8);    // round(100/12)
    CHECK_EQ(celDelayCs(3, 12.0), 25);   // round(300/12)
    CHECK_EQ(celDelayCs(2, 0.0), 17);    // fps<=0 falls back to 12
    CHECK_EQ(celDelayCs(99, 12.0), 8);   // invalid hold -> 1
}

// Erase+insert permutation: moved cel lands on target, followers shift,
// cursor follows the moved cel.
TEST(cels_move_permute) {
    // 4 cels, move 0 -> 2: order becomes 1,2,0,3.
    CHECK_EQ(moveCursor(4, 0, 2, 0), 2u);
    CHECK_EQ(moveCursor(4, 0, 2, 1), 0u);
    CHECK_EQ(moveCursor(4, 0, 2, 2), 1u);
    CHECK_EQ(moveCursor(4, 0, 2, 3), 3u);
    // Move 3 -> 0: order becomes 3,0,1,2.
    CHECK_EQ(moveCursor(4, 3, 0, 3), 0u);
    CHECK_EQ(moveCursor(4, 3, 0, 0), 1u);
    // Identity move keeps the cursor.
    CHECK_EQ(moveCursor(4, 1, 1, 1), 1u);
    CHECK_EQ(moveCursor(4, 1, 1, 2), 2u);
}

// Onion neighbor ranges: nearest first, clamped at clip ends,
// empty when the count is 0.
TEST(cels_onion_range_empty_without_count) {
    CHECK(onionRange(2, 5, 0, 0).empty());
    auto r = onionRange(2, 5, 2, 1);
    CHECK_EQ(r.size(), 3u);
    CHECK_EQ(r[0], 1u);  // nearest previous first
    CHECK_EQ(r[1], 0u);
    CHECK_EQ(r[2], 3u);
    // Clamped at the clip start/end.
    auto s = onionRange(0, 3, 4, 4);
    CHECK_EQ(s.size(), 2u);
    CHECK_EQ(s[0], 1u);
    CHECK_EQ(s[1], 2u);
    auto e = onionRange(2, 3, 4, 4);
    CHECK_EQ(e.size(), 2u);
    CHECK_EQ(e[0], 1u);
    CHECK_EQ(e[1], 0u);
    CHECK(validOnionCount(0));
    CHECK(validOnionCount(4));
    CHECK(!validOnionCount(5));
    CHECK(!validOnionCount(-1));
}
