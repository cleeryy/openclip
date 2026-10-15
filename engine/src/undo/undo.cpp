// SPDX-License-Identifier: GPL-3.0-or-later
#include "openclip/undo.h"

#include "openclip/canvas.h"

namespace openclip {

void History::beginStroke() {
    cur_.tiles.clear();
    open_ = true;
    undone_.clear();  // new stroke invalidates redo
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
    done_.push_back(std::move(cur_));
    cur_.tiles.clear();
    while (done_.size() > max_) done_.erase(done_.begin());
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

bool History::undo(Canvas& c) {
    if (done_.empty()) return false;
    StrokeCommand s = std::move(done_.back());
    done_.pop_back();
    for (auto& t : s.tiles) {
        if (t.layer >= c.layerCount()) continue;
        if (t.mask) {
            if (c.layer(t.layer).hasMask)
                c.layer(t.layer).mask.tileRestore(t.tx, t.ty, t.before);
        } else {
            c.layer(t.layer).surface.tileRestore(t.tx, t.ty, t.before);
        }
    }
    undone_.push_back(std::move(s));
    return true;
}

bool History::redo(Canvas& c) {
    if (undone_.empty()) return false;
    StrokeCommand s = std::move(undone_.back());
    undone_.pop_back();
    for (auto& t : s.tiles) {
        if (t.layer >= c.layerCount()) continue;
        if (t.mask) {
            if (c.layer(t.layer).hasMask)
                c.layer(t.layer).mask.tileRestore(t.tx, t.ty, t.after);
        } else {
            c.layer(t.layer).surface.tileRestore(t.tx, t.ty, t.after);
        }
    }
    done_.push_back(std::move(s));
    return true;
}

void History::clear() {
    cur_.tiles.clear();
    done_.clear();
    undone_.clear();
    open_ = false;
}

}  // namespace openclip
