// SPDX-License-Identifier: GPL-3.0-or-later
#include "test.h"

#include "openclip/brush.h"
#include "openclip/canvas.h"
#include "openclip/undo.h"

using openclip::BrushParams;
using openclip::Canvas;
using openclip::dabFromPressure;
using openclip::History;
using openclip::paintDab;

TEST(undo_stroke_roundtrip) {
    Canvas c(300, 300);  // spans 2x2 tiles
    c.addLayer("l0");
    History h;
    auto before = c.layer(0).surface.get(10, 10);
    CHECK_EQ(before.a, 0);

    h.beginStroke();
    h.touchTile(c, 0, 0, 0);
    BrushParams p;
    paintDab(c.layer(0).surface,
             dabFromPressure(p, 10, 10, 1.0f, 255, 0, 0), p);
    h.endStroke(c);
    CHECK(c.layer(0).surface.get(10, 10).a != 0);

    CHECK(h.canUndo());
    CHECK(h.undo(c));
    CHECK_EQ(c.layer(0).surface.get(10, 10).a, 0);
    CHECK(!h.canUndo());
    CHECK(h.canRedo());
    CHECK(h.redo(c));
    CHECK(c.layer(0).surface.get(10, 10).a != 0);
}

TEST(undo_cancel_restores) {
    Canvas c(64, 64);
    c.addLayer("l0");
    History h;
    h.beginStroke();
    h.touchTile(c, 0, 0, 0);
    BrushParams p;
    paintDab(c.layer(0).surface,
             dabFromPressure(p, 10, 10, 1.0f, 255, 0, 0), p);
    h.cancelStroke(c);
    CHECK_EQ(c.layer(0).surface.get(10, 10).a, 0);
    CHECK(!h.canUndo());
}

TEST(undo_new_stroke_clears_redo) {
    Canvas c(64, 64);
    c.addLayer("l0");
    History h;
    BrushParams p;
    h.beginStroke();
    h.touchTile(c, 0, 0, 0);
    paintDab(c.layer(0).surface,
             dabFromPressure(p, 8, 8, 1.0f, 255, 0, 0), p);
    h.endStroke(c);
    CHECK(h.undo(c));
    CHECK(h.canRedo());
    h.beginStroke();
    h.touchTile(c, 0, 0, 0);
    paintDab(c.layer(0).surface,
             dabFromPressure(p, 20, 20, 1.0f, 0, 255, 0), p);
    h.endStroke(c);
    CHECK(!h.canRedo());
}

// ---- Issue #32 §3: explicit undo memory budget ----

TEST(undo_memory_bytes_counts_layers_and_masks) {
    Canvas c(300, 300);
    c.addLayer("l0");
    c.addLayer("l1");
    // 2 full RGBA8 surfaces.
    CHECK_EQ(openclip::canvasMemoryBytes(c),
             2u * 300u * 300u * 4u);
    c.layer(1).addMask(300, 300);
    CHECK_EQ(openclip::canvasMemoryBytes(c),
             3u * 300u * 300u * 4u);
}

TEST(undo_budget_evicts_oldest_commands) {
    Canvas c(300, 300);  // tile 0,0 = 256x256 = 256 KiB per before/after
    c.addLayer("l0");
    // One stroke on this tile costs 2 * 256 KiB = 512 KiB of snapshots.
    History h(64, 1100u * 1024u);
    BrushParams p;
    for (int i = 0; i < 3; ++i) {
        h.beginStroke();
        h.touchTile(c, 0, 0, 0);
        paintDab(c.layer(0).surface,
                 dabFromPressure(p, 10 + i, 10, 1.0f, 255, 0, 0), p);
        h.endStroke(c);
    }
    CHECK_EQ(h.undoDepth(), 2u);          // oldest evicted, not OOM
    CHECK(h.bytes() <= h.maxBytes());
    CHECK_EQ(h.dropped(), 0u);
    // The newest step is still undoable and the document stays intact.
    CHECK(h.undo(c));
    CHECK_EQ(h.undoDepth(), 1u);
    CHECK_EQ(h.redoDepth(), 1u);
    CHECK(h.redo(c));
}

TEST(undo_budget_drops_oversized_command) {
    Canvas c(300, 300);
    c.addLayer("l0");
    History h(64, 100u * 1024u);  // smaller than one 512 KiB tile snapshot
    BrushParams p;
    h.beginStroke();
    h.touchTile(c, 0, 0, 0);
    paintDab(c.layer(0).surface,
             dabFromPressure(p, 10, 10, 1.0f, 255, 0, 0), p);
    h.endStroke(c);
    CHECK(!h.canUndo());
    CHECK_EQ(h.dropped(), 1u);
    CHECK_EQ(h.bytes(), 0u);
    CHECK_EQ(h.undoDepth(), 0u);
}

TEST(undo_budget_refuses_oversized_snapshot) {
    Canvas small(64, 64);
    small.addLayer("l0");
    History fits(64, 1024u * 1024u);
    CHECK(fits.beginSnapshot(small));  // 2 * 32 KiB payload
    fits.cancelSnapshot();

    Canvas big(300, 300);
    big.addLayer("l0");
    big.addLayer("l1");
    History tight(64, 1024u * 1024u);  // 2 copies = 1.44 MiB > budget
    CHECK(!tight.beginSnapshot(big));
    CHECK(!tight.snapshotPending());
    CHECK_EQ(tight.dropped(), 1u);
}

TEST(undo_budget_setter_evicts_and_clamps) {
    Canvas c(300, 300);
    c.addLayer("l0");
    History h(64, 2000u * 1024u);
    BrushParams p;
    for (int i = 0; i < 3; ++i) {
        h.beginStroke();
        h.touchTile(c, 0, 0, 0);
        paintDab(c.layer(0).surface,
                 dabFromPressure(p, 10, 10, 1.0f, 255, 0, 0), p);
        h.endStroke(c);
    }
    CHECK_EQ(h.undoDepth(), 3u);  // 3 x 512 KiB under the 2000 KiB cap
    const std::size_t before = h.bytes();
    CHECK(before > 0u);
    // Shrinking the budget evicts the oldest entries immediately; the
    // 1 MiB floor keeps the policy sane even on a bogus request.
    CHECK_EQ(h.setMaxBytes(600u * 1024u), 1024u * 1024u);
    CHECK_EQ(h.undoDepth(), 2u);
    CHECK(h.bytes() <= h.maxBytes());
    CHECK(h.setMaxBytes(0u) >= 1024u * 1024u);  // clamped up, never zero
}

TEST(undo_snapshot_roundtrip_keeps_bytes_accounting) {
    Canvas c(64, 64);
    c.addLayer("l0");
    History h;
    CHECK(h.beginSnapshot(c));
    c.layer(0).surface.set(1, 1, openclip::Pixel{9, 8, 7, 255});
    h.endSnapshot(c);
    CHECK_EQ(h.undoDepth(), 1u);
    CHECK(h.bytes() >= 2u * 64u * 64u * 4u);
    CHECK(h.undo(c));
    CHECK_EQ(c.layer(0).surface.get(1, 1).a, 0);
    CHECK(h.redo(c));
    CHECK_EQ(c.layer(0).surface.get(1, 1).r, 9);
}
