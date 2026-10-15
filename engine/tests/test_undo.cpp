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
