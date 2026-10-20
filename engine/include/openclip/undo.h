// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip M1 engine — stroke-level command history (tile snapshots).
#ifndef OPENCLIP_UNDO_H
#define OPENCLIP_UNDO_H

#include <cstddef>
#include <cstdint>
#include <vector>

namespace openclip {

class Canvas;

struct TileSnap {
    std::size_t layer = 0;
    int tx = 0, ty = 0;
    bool mask = false;  // M5: snapshot targets the layer mask, not pixels
    std::vector<std::uint8_t> before;
    std::vector<std::uint8_t> after;
};

struct StrokeCommand {
    std::vector<TileSnap> tiles;
};

// Bounded stroke-level undo/redo stack over Canvas tile snapshots.
class History {
public:
    explicit History(std::size_t maxStrokes = 64) : max_(maxStrokes) {}

    void beginStroke();
    // Record one tile touched by the in-progress stroke. Captures "before".
    // mask=true snapshots the layer's mask tile (M5 mask strokes).
    void touchTile(Canvas& c, std::size_t layer, int tx, int ty,
                   bool mask = false);
    void endStroke(Canvas& c);
    void cancelStroke(Canvas& c);  // restore "before", drop stroke

    bool canUndo() const { return !done_.empty(); }
    bool canRedo() const { return !undone_.empty(); }
    // V+2: committed-stroke depth (lets the shim pair a pending cel move
    // with the exact history depth it was recorded at — exact LIFO
    // against interleaved pixel ops, ARCHITECTURE.md "V+2 additions").
    std::size_t undoDepth() const { return done_.size(); }
    bool undo(Canvas& c);
    bool redo(Canvas& c);
    void clear();

private:
    std::size_t max_;
    StrokeCommand cur_;
    bool open_ = false;
    std::vector<StrokeCommand> done_, undone_;
};

}  // namespace openclip

#endif
