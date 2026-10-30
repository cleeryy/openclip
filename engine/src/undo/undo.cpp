// SPDX-License-Identifier: GPL-3.0-or-later
#include "openclip/undo.h"

#include <utility>

namespace openclip {

namespace {

std::size_t layerMemoryBytes(const Layer& l) {
    std::size_t b = l.surface.bytes().size();
    if (l.hasMask) b += l.mask.bytes().size();
    for (const auto& child : l.children) b += layerMemoryBytes(child);
    return b;
}

}  // namespace

std::size_t canvasMemoryBytes(const Canvas& c) {
    std::size_t b = 0;
    for (std::size_t i = 0; i < c.layerCount(); ++i)
        b += layerMemoryBytes(c.layer(i));
    return b;
}

void History::beginStroke() {
    cur_.tiles.clear();
    open_ = true;
    clearRedo();  // new stroke invalidates redo
}

void History::touchTile(Canvas& c, std::size_t layer, int tx, int ty,
                        bool mask) {
    if (!open_ || layer >= c.layerCount()) return;
    for (const auto& t : cur_.tiles)
        if (t.layer == layer && t.tx == tx && t.ty == ty && t.mask == mask)
            return;  // once per stroke
    TileSnap s;
    s.layer = layer;
    s.tx = tx;
    s.ty = ty;
    s.mask = mask;
    if (mask) {
        if (!c.layer(layer).hasMask) return;
        c.layer(layer).mask.tileCopy(tx, ty, s.before);
    } else {
        c.layer(layer).surface.tileCopy(tx, ty, s.before);
    }
    cur_.tiles.push_back(std::move(s));
}

void History::endStroke(Canvas& c) {
    if (!open_) return;
    open_ = false;
    if (cur_.tiles.empty()) return;
    for (auto& t : cur_.tiles) {
        if (t.mask)
            c.layer(t.layer).mask.tileCopy(t.tx, t.ty, t.after);
        else
            c.layer(t.layer).surface.tileCopy(t.tx, t.ty, t.after);
    }
    HistoryCommand cmd;
    cmd.tiles = std::move(cur_.tiles);
    cur_.tiles.clear();
    push(std::move(cmd));
}

void History::cancelStroke(Canvas& c) {
    if (!open_) return;
    open_ = false;
    for (auto& t : cur_.tiles) {
        if (t.mask)
            c.layer(t.layer).mask.tileRestore(t.tx, t.ty, t.before);
        else
            c.layer(t.layer).surface.tileRestore(t.tx, t.ty, t.before);
    }
    cur_.tiles.clear();
}

bool History::beginSnapshot(const Canvas& c) {
    // A structural op never rides an open stroke: drop the accumulator
    // (the caller has already committed or cancelled its stroke).
    open_ = false;
    cur_.tiles.clear();
    // Issue #32 §3: the command keeps a deep copy of the document before
    // AND after the op (2x canvasMemoryBytes). Refuse up front when that
    // pair cannot fit the budget: the caller refuses the structural op
    // instead of mutating a document it could never restore.
    const std::size_t one = canvasMemoryBytes(c);
    if (one == 0 || one > maxBytes_ / 2) {
        ++dropped_;
        pendBefore_.reset();
        return false;
    }
    pendBefore_ = std::make_unique<Canvas>(c);
    return true;
}

void History::cancelSnapshot() { pendBefore_.reset(); }

void History::endSnapshot(const Canvas& c) {
    if (!pendBefore_) return;
    HistoryCommand cmd;
    cmd.docBefore = std::move(pendBefore_);
    cmd.docAfter = std::make_unique<Canvas>(c);
    Surface before, after;
    cmd.docBefore->composite(before);
    cmd.docAfter->composite(after);
    const bool changed = before.bytes() != after.bytes() ||
                         cmd.docBefore->layerCount() != cmd.docAfter->layerCount();
    if (!changed) return;  // refused/no-op op records nothing
    push(std::move(cmd));
}

std::size_t History::commandBytes(const HistoryCommand& cmd) {
    if (cmd.isDocument()) {
        std::size_t b = 0;
        if (cmd.docBefore) b += canvasMemoryBytes(*cmd.docBefore);
        if (cmd.docAfter) b += canvasMemoryBytes(*cmd.docAfter);
        return b;
    }
    std::size_t b = 0;
    for (const auto& t : cmd.tiles) b += t.before.size() + t.after.size();
    return b;
}

void History::clearRedo() {
    for (const auto& cmd : undone_) bytes_ -= commandBytes(cmd);
    undone_.clear();
}

void History::push(HistoryCommand cmd) {
    clearRedo();
    const std::size_t b = commandBytes(cmd);
    // A single command bigger than the whole budget is not retained:
    // keeping it would blow the cap by itself (surface cap = never OOM).
    if (b > maxBytes_) {
        ++dropped_;
        return;
    }
    done_.push_back(std::move(cmd));
    bytes_ += b;
    // Evict the OLDEST entries first; the newest command always stays
    // (so the action just performed is always undoable when it fit).
    while (bytes_ > maxBytes_ && done_.size() > 1) {
        bytes_ -= commandBytes(done_.front());
        done_.erase(done_.begin());
    }
    while (done_.size() > max_) {
        bytes_ -= commandBytes(done_.front());
        done_.erase(done_.begin());
    }
}

std::size_t History::setMaxBytes(std::size_t bytes) {
    const std::size_t kMin = static_cast<std::size_t>(1) * 1024 * 1024;
    const std::size_t kMax = static_cast<std::size_t>(4) * 1024 * 1024 * 1024;
    maxBytes_ = bytes < kMin ? kMin : (bytes > kMax ? kMax : bytes);
    while (bytes_ > maxBytes_ && !done_.empty()) {
        bytes_ -= commandBytes(done_.front());
        done_.erase(done_.begin());
    }
    while (bytes_ > maxBytes_ && !undone_.empty()) {
        bytes_ -= commandBytes(undone_.back());
        undone_.pop_back();
    }
    return maxBytes_;
}


bool History::undo(Canvas& c) {
    if (done_.empty()) return false;
    HistoryCommand s = std::move(done_.back());
    done_.pop_back();
    if (s.isDocument()) {
        if (s.docBefore) c = *s.docBefore;
    } else {
        for (auto& t : s.tiles) {
            if (t.layer >= c.layerCount()) continue;
            if (t.mask) {
                if (c.layer(t.layer).hasMask)
                    c.layer(t.layer).mask.tileRestore(t.tx, t.ty, t.before);
            } else {
                c.layer(t.layer).surface.tileRestore(t.tx, t.ty, t.before);
            }
        }
    }
    undone_.push_back(std::move(s));
    return true;
}

bool History::redo(Canvas& c) {
    if (undone_.empty()) return false;
    HistoryCommand s = std::move(undone_.back());
    undone_.pop_back();
    if (s.isDocument()) {
        if (s.docAfter) c = *s.docAfter;
    } else {
        for (auto& t : s.tiles) {
            if (t.layer >= c.layerCount()) continue;
            if (t.mask) {
                if (c.layer(t.layer).hasMask)
                    c.layer(t.layer).mask.tileRestore(t.tx, t.ty, t.after);
            } else {
                c.layer(t.layer).surface.tileRestore(t.tx, t.ty, t.after);
            }
        }
    }
    done_.push_back(std::move(s));
    return true;
}

void History::clear() {
    cur_.tiles.clear();
    pendBefore_.reset();
    done_.clear();
    undone_.clear();
    bytes_ = 0;
    open_ = false;
    // dropped_ is a lifetime counter (policy visibility), not state.
}

}  // namespace openclip
