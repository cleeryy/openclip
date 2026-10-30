// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip M1 engine — stroke-level command history (tile snapshots).
// W1 (issue #28 slice A): whole-document snapshots for structural ops
// (merge / flatten / rasterize / canvas resize / crop) — one undo step
// per action, LIFO-safe against stroke commands (ARCHITECTURE.md "W1
// additions").
#ifndef OPENCLIP_UNDO_H
#define OPENCLIP_UNDO_H

#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

#include "openclip/canvas.h"

namespace openclip {

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

// One history entry: either a tile-level stroke command or a
// whole-document structural snapshot (never both).
struct HistoryCommand {
    std::vector<TileSnap> tiles;
    std::unique_ptr<Canvas> docBefore;
    std::unique_ptr<Canvas> docAfter;
    bool isDocument() const { return docBefore != nullptr; }
};

// Issue #32 §3: deep RGBA8 byte estimate of one canvas (every layer
// surface, attached masks, nested group children — the memory a
// whole-document snapshot would duplicate). 0 for an empty canvas.
std::size_t canvasMemoryBytes(const Canvas& c);

// Bounded stroke-level undo/redo stack over Canvas tile snapshots.
//
// Two bounds apply: a stroke count (maxStrokes, legacy) and an explicit
// memory budget (maxBytes, issue #32 §3). The byte accounting is an
// estimate of the retained snapshot payload — tile before/after vectors
// for strokes, deep canvas copies for structural snapshots. When the
// budget is exceeded the OLDEST entries are evicted; a single command
// bigger than the whole budget is not retained at all (the document
// mutation itself still happens — only its undo record is dropped) and
// beginSnapshot() refuses up front so a structural op never allocates
// a twin copy that cannot fit. Undo/redo never allocate a full document
// beyond what was already retained, so a capped history cannot OOM.
class History {
public:
    // 256 MiB is a safe tablet default; the app sets the real budget
    // from the device RAM (nSetBudget).
    static constexpr std::size_t kDefaultMaxBytes =
        static_cast<std::size_t>(256) * 1024 * 1024;

    explicit History(std::size_t maxStrokes = 64,
                     std::size_t maxBytes = kDefaultMaxBytes)
        : max_(maxStrokes), maxBytes_(maxBytes) {}

    void beginStroke();
    // Record one tile touched by the in-progress stroke. Captures "before".
    // mask=true snapshots the layer's mask tile (M5 mask strokes).
    void touchTile(Canvas& c, std::size_t layer, int tx, int ty,
                   bool mask = false);
    void endStroke(Canvas& c);
    void cancelStroke(Canvas& c);  // restore "before", drop stroke

    // W1: whole-document snapshot around a structural mutation.
    // beginSnapshot() captures the "before" state, endSnapshot() captures
    // the "after" state and pushes ONE command when the document actually
    // changed (no entry for a refused/no-op op). cancelSnapshot() drops
    // the pending capture. A structural op never rides an open stroke.
    // Issue #32 §3: beginSnapshot() returns false (nothing captured) when
    // the two-copy snapshot would exceed the byte budget — callers must
    // refuse the structural op rather than leave it unrecoverable.
    bool beginSnapshot(const Canvas& c);
    void cancelSnapshot();
    void endSnapshot(const Canvas& c);
    bool snapshotPending() const { return pendBefore_ != nullptr; }

    bool canUndo() const { return !done_.empty(); }
    bool canRedo() const { return !undone_.empty(); }
    // V+2: committed-stroke depth (lets the shim pair a pending cel move
    // with the exact history depth it was recorded at — exact LIFO
    // against interleaved pixel ops, ARCHITECTURE.md "V+2 additions").
    std::size_t undoDepth() const { return done_.size(); }
    bool undo(Canvas& c);
    bool redo(Canvas& c);
    void clear();

    // Issue #32 §3: memory policy introspection + live budget update.
    // setMaxBytes() clamps to [1 MiB, 4 GiB] and evicts immediately so
    // the stack never sits above the new budget.
    std::size_t setMaxBytes(std::size_t bytes);
    std::size_t maxBytes() const { return maxBytes_; }
    std::size_t bytes() const { return bytes_; }
    std::size_t redoDepth() const { return undone_.size(); }
    // Commands discarded because they alone exceeded the budget (or a
    // structural snapshot refused up front).
    std::size_t dropped() const { return dropped_; }

private:
    void push(HistoryCommand cmd);
    void clearRedo();
    static std::size_t commandBytes(const HistoryCommand& cmd);

    std::size_t max_;
    std::size_t maxBytes_;
    std::size_t bytes_ = 0;
    std::size_t dropped_ = 0;
    StrokeCommand cur_;
    bool open_ = false;
    std::unique_ptr<Canvas> pendBefore_;
    std::vector<HistoryCommand> done_, undone_;
};

}  // namespace openclip

#endif
