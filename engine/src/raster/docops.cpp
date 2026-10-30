// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip W1 (issue #28 slice A) — merge / flatten / rasterize.
// ARCHITECTURE.md "W1 additions": every op is verified against the
// composite before and after; a difference reverts and refuses.
#include "openclip/docops.h"

#include <cstring>
#include <utility>
#include <vector>

namespace openclip {

namespace {

bool samePixels(const Surface& a, const Surface& b) {
    return a.width() == b.width() && a.height() == b.height() &&
           a.bytes() == b.bytes();
}

// Editor composite + export composite equality (a merge must never move
// a pixel on screen nor in an export).
bool sameRender(const Canvas& a, const Canvas& b) {
    Surface ea, eb;
    a.composite(ea);
    b.composite(eb);
    if (!samePixels(ea, eb)) return false;
    a.compositeExport(ea);
    b.compositeExport(eb);
    return samePixels(ea, eb);
}

bool sameExportRender(const Canvas& a, const Canvas& b) {
    Surface ea, eb;
    a.compositeExport(ea);
    b.compositeExport(eb);
    return samePixels(ea, eb);
}

// Isolated composite of the listed top-level indexes, in list order,
// over a transparent backdrop (same size as `c`).
void compositeSubset(const Canvas& c, const std::vector<std::size_t>& idx,
                     Surface& out) {
    Canvas tmp;
    tmp.resize(c.width(), c.height());
    for (const std::size_t i : idx) {
        if (i < c.layerCount()) tmp.adoptLayer(c.layer(i));
    }
    tmp.composite(out);
}

// A plain Normal raster layer keeping only the identity-ish metadata of
// `like` (name, visibility, lock flags, reference/draft export flags).
Layer plainLayer(const Layer& like, Surface px) {
    Layer l;
    l.name = like.name;
    l.visible = like.visible;
    l.locked = like.locked;
    l.alphaLock = like.alphaLock;
    l.reference = like.reference;
    l.draft = like.draft;
    l.surface = std::move(px);
    return l;
}

// Commit `work` into `c` when it renders the same, else refuse.
bool commitIfIdentical(Canvas& c, Canvas work, const std::string& err,
                       std::string& out) {
    if (!sameRender(c, work)) {
        out = err;
        return false;
    }
    c = std::move(work);
    return true;
}

}  // namespace

bool rasterizeLayer(Canvas& c, std::size_t index, std::string& err) {
    if (index >= c.layerCount()) {
        err = "bad layer index";
        return false;
    }
    const Layer& l0 = c.layer(index);
    const bool isAdjust = !l0.adjustType.empty();
    const bool isGroup = l0.isGroup;
    const bool hasFiche = l0.isVector || l0.hasFill() || l0.hasText() ||
                          l0.hasBubble();
    const bool baked = l0.hasMask || l0.opacity < 1.0f ||
                       l0.blend != BlendMode::Normal;
    if (!isAdjust && !isGroup && !hasFiche && !baked) {
        err = "layer is already a plain raster layer";
        return false;
    }
    if (!isAdjust && l0.clipped) {
        // A clipped layer renders only where the running target is
        // painted; baking that stencil into its own pixels would need the
        // whole stack below — refuse rather than approximate.
        err = "clipped layer cannot be rasterized in place";
        return false;
    }

    Surface px;
    if (isAdjust) {
        // An adjustment transforms the composite of everything below it:
        // the honest raster is that composite, adjusted.
        std::vector<std::size_t> below;
        for (std::size_t i = 0; i <= index; ++i) below.push_back(i);
        compositeSubset(c, below, px);
        Layer merged = plainLayer(c.layer(index), std::move(px));
        Canvas next;
        next.resize(c.width(), c.height());
        next.adoptLayer(merged);
        for (std::size_t i = index + 1; i < c.layerCount(); ++i)
            next.adoptLayer(c.layer(i));
        if (!sameRender(c, next)) {
            err = "adjustment cannot be baked without changing the render";
            return false;
        }
        c = std::move(next);
        c.setActiveLayer(0);
        return true;
    }

    // Group or fiche layer: its isolated composite is exactly what it
    // contributes to the stack, so baking it into pixels is exact.
    compositeSubset(c, {index}, px);
    Layer merged = plainLayer(c.layer(index), std::move(px));
    Canvas work = c;
    work.layer(index) = std::move(merged);
    if (!commitIfIdentical(c, std::move(work),
                           "rasterize would change the render", err))
        return false;
    c.setActiveLayer(static_cast<int>(index));
    return true;
}

bool mergeDown(Canvas& c, std::size_t index, std::string& err) {
    if (index == 0 || index >= c.layerCount()) {
        err = "bad layer index";
        return false;
    }
    Canvas work = c;
    if (work.layer(index).isGroup) {
        std::string e;
        if (!rasterizeLayer(work, index, e)) {
            err = e;
            return false;
        }
    }
    const Layer& src = work.layer(index);
    const Layer& dst = work.layer(index - 1);
    if (dst.isGroup) {
        err = "cannot merge onto a group";
        return false;
    }
    if (!src.visible) {
        err = "hidden layer: show it or delete it before merging";
        return false;
    }
    if (dst.clipped) {
        err = "clipped destination layer cannot absorb a merge";
        return false;
    }

    Surface px;
    compositeSubset(work, {index - 1, index}, px);
    Layer merged = plainLayer(dst, std::move(px));
    // The source's export/editor-only nature survives the bake.
    merged.reference = dst.reference || src.reference;
    merged.draft = dst.draft || src.draft;

    Canvas next;
    next.resize(c.width(), c.height());
    for (std::size_t i = 0; i < work.layerCount(); ++i) {
        if (i == index - 1)
            next.adoptLayer(merged);
        else if (i != index)
            next.adoptLayer(work.layer(i));
    }
    if (!commitIfIdentical(c, std::move(next), "merge down would change the render",
                           err))
        return false;
    c.setActiveLayer(static_cast<int>(index - 1));
    return true;
}

bool mergeVisible(Canvas& c, std::string& err) {
    std::vector<std::size_t> vis;
    for (std::size_t i = 0; i < c.layerCount(); ++i)
        if (c.layer(i).visible) vis.push_back(i);
    if (vis.size() < 2) {
        err = "need at least two visible layers";
        return false;
    }
    const std::size_t top = vis.back();
    Surface px;
    compositeSubset(c, vis, px);
    Layer merged;
    merged.name = "Merged";
    merged.surface = std::move(px);
    bool anyReference = false, anyDraft = false;
    for (const std::size_t i : vis) {
        anyReference = anyReference || c.layer(i).reference;
        anyDraft = anyDraft || c.layer(i).draft;
    }
    merged.reference = anyReference;
    merged.draft = anyDraft;

    Canvas next;
    next.resize(c.width(), c.height());
    for (std::size_t i = 0; i < c.layerCount(); ++i) {
        if (i == top)
            next.adoptLayer(merged);
        else if (!c.layer(i).visible)
            next.adoptLayer(c.layer(i));
    }
    if (!commitIfIdentical(c, std::move(next),
                           "merge visible would change the render", err))
        return false;
    c.setActiveLayer(0);
    return true;
}

bool flattenDocument(Canvas& c, std::string& err) {
    if (c.layerCount() == 0) {
        err = "nothing to flatten";
        return false;
    }
    Surface px;
    c.compositeExport(px);
    Layer merged;
    merged.name = "Flattened";
    merged.surface = std::move(px);
    Canvas next;
    next.resize(c.width(), c.height());
    next.adoptLayer(merged);
    if (!sameExportRender(c, next)) {
        err = "flatten would change the export render";
        return false;
    }
    c = std::move(next);
    c.setActiveLayer(0);
    return true;
}

}  // namespace openclip
