// SPDX-License-Identifier: GPL-3.0-or-later
#include "openclip/canvas.h"

#include "openclip/blend.h"
#include "openclip/bubble.h"
#include "openclip/filters.h"
#include "openclip/vector.h"

#include <cmath>
#include <utility>

#include "openclip/anim.h"
#include "openclip/fill_layer.h"
#include "openclip/texture.h"

namespace openclip {

namespace {

// M6: source pixel for one layer at (x, y): vector layers rasterize
// their paths into a pre-built cache (one entry per layer in the list
// being composited, nullptr for non-vector layers).
inline Pixel layerPixel(const Layer& l, const Surface* vecTmp, int x, int y) {
    if (l.isVector && !l.vectorData.empty() && vecTmp)
        return vecTmp->get(x, y);
    return l.surface.get(x, y);
}

// Rasterize one vector layer's paths into tmp when it contributes.
// Session ink is opaque black (M6 baseline; per-path color is M7+ work).
inline const Surface* rasterizeForComposite(const Layer& l, Surface& tmp,
                                            int w, int h) {
    if (!l.isVector || l.vectorData.empty()) return nullptr;
    tmp.resize(w, h);
    rasterizeVectorPaths(l.vectorData, tmp);
    return &tmp;
}

// M6: pre-rasterize every vector layer of a flat list into scratch
// (aligned 1:1 with layers; empty Surface for non-vector entries). The
// returned pointer vector stays valid while scratch is alive. This keeps
// the stencil query vector-aware without per-pixel rasterization.
inline std::vector<const Surface*> rasterizeAllVectors(
    const std::vector<Layer>& layers, std::vector<Surface>& scratch, int w,
    int h) {
    scratch.clear();
    scratch.resize(layers.size());
    std::vector<const Surface*> ptrs(layers.size(), nullptr);
    for (std::size_t i = 0; i < layers.size(); ++i) {
        ptrs[i] = rasterizeForComposite(layers[i], scratch[i], w, h);
    }
    return ptrs;
}

// T2: pre-render every bubble layer of a flat top-level list into
// scratch (nullptr for non-bubble entries). Composite-time only:
// the fiche never touches layer pixels (ARCHITECTURE.md §T2 §5).
inline std::vector<const Surface*> renderAllBubbles(
    const std::vector<Layer>& layers, std::vector<Surface>& scratch, int w,
    int h) {
    scratch.clear();
    scratch.resize(layers.size());
    std::vector<const Surface*> ptrs(layers.size(), nullptr);
    for (std::size_t i = 0; i < layers.size(); ++i) {
        const Layer& l = layers[i];
        if (l.isGroup || !l.hasBubble()) continue;
        BubbleSpec spec;
        std::string err;
        if (!parseBubbleSpec(l.bubbleShape, l.bubbleFrame, l.bubbleTail,
                             l.bubbleFill, l.bubbleStroke, l.bubbleStrokeW,
                             spec, err))
            continue;  // malformed fiche = no balloon, pixels intact
        scratch[i].resize(w, h);
        scratch[i].clear();
        if (!renderBubble(spec, scratch[i], err)) continue;
        ptrs[i] = &scratch[i];
    }
    return ptrs;
}

// T5: pre-render every fill layer of a flat list into scratch
// (nullptr for non-fill entries). Composite-time only: the fiche
// never commits pixels (ARCHITECTURE.md "T5 additions" §2).
inline std::vector<const Surface*> renderAllFills(
    const std::vector<Layer>& layers, std::vector<Surface>& scratch, int w,
    int h) {
    scratch.clear();
    scratch.resize(layers.size());
    std::vector<const Surface*> ptrs(layers.size(), nullptr);
    for (std::size_t i = 0; i < layers.size(); ++i) {
        const Layer& l = layers[i];
        if (l.isGroup || !l.hasFill()) continue;
        // A fiche is present: the surface is IGNORED while it lives
        // (ARCHITECTURE.md "T5 additions" §2). Malformed fiches paint
        // nothing (cleared scratch), never the stale surface.
        scratch[i].resize(w, h);
        scratch[i].clear();
        FillSpec spec;
        std::string err;
        if (!parseFillSpec(l, spec, err)) {
            ptrs[i] = &scratch[i];
            continue;
        }
        if (!renderFill(spec, w, h, scratch[i], err)) {
            scratch[i].clear();
        }
        ptrs[i] = &scratch[i];
    }
    return ptrs;
}

// B1: pre-render the texture height field of every textured layer of a
// flat list (empty vector = no texture, strength 0 = identity).
// Composite-time only: the fiche never commits pixels (ARCHITECTURE.md
// "B1 additions" §5).
struct TextureCache {
    std::vector<std::vector<std::uint8_t>> heights;
    std::vector<float> strength;
    int w = 0;
};

inline TextureCache renderAllTextures(const std::vector<Layer>& layers, int w,
                                      int h) {
    TextureCache c;
    c.w = w;
    c.heights.resize(layers.size());
    c.strength.assign(layers.size(), 0.0f);
    for (std::size_t i = 0; i < layers.size(); ++i) {
        const Layer& l = layers[i];
        if (l.isGroup || !l.hasTexture()) continue;
        TextureSpec spec;
        std::string err;
        if (!parseTextureFiche(l, spec, err)) continue;
        if (spec.strength <= 0.0f) continue;
        if (!renderTextureHeight(spec, w, h, c.heights[i], err)) continue;
        c.strength[i] = spec.strength;
    }
    return c;
}

// Modulate one premultiplied pixel through the cached height field
// (alpha untouched, rgb <= alpha preserved).
inline void shadeTexture(Pixel& s, const TextureCache& c, std::size_t li,
                         int x, int y) {
    const float st = c.strength[li];
    if (st <= 0.0f || s.a == 0) return;
    const std::vector<std::uint8_t>& hgt = c.heights[li];
    const std::size_t at = static_cast<std::size_t>(y) * c.w + x;
    if (at >= hgt.size()) return;
    const std::uint8_t hv = hgt[at];
    s.r = textureShade(s.r, s.a, hv, st);
    s.g = textureShade(s.g, s.a, hv, st);
    s.b = textureShade(s.b, s.a, hv, st);
}

// Scale a premultiplied pixel by a 0..1 factor (rounded).
inline Pixel scalePixel(const Pixel& p, float f) {
    if (f >= 1.0f) return p;
    Pixel q = p;
    q.r = static_cast<std::uint8_t>(p.r * f + 0.5f);
    q.g = static_cast<std::uint8_t>(p.g * f + 0.5f);
    q.b = static_cast<std::uint8_t>(p.b * f + 0.5f);
    q.a = static_cast<std::uint8_t>(p.a * f + 0.5f);
    return q;
}

// M5 clipping stencil (M6: vector-aware — the stencil layer's
// rasterized pixels are consulted when it is a vector layer).
inline bool stencilPainted(const std::vector<Layer>& layers,
                           const std::vector<const Surface*>& vecCache,
                           std::size_t li, const Surface& out, int x, int y) {
    if (li > 0 && !layers[li - 1].isGroup)
        return layerPixel(layers[li - 1], vecCache[li - 1], x, y).a != 0;
    return out.get(x, y).a != 0;
}

// M8: apply one adjustment layer's op to the accumulated pixel in `out`
// (premultiplied RGBA bytes). The pixel is unpremultiplied, transformed
// by applyAdjustOp, repremultiplied and clamped to [0,alpha] (alpha
// untouched — the M3 alpha contract). Unknown types are identity.
inline void applyAdjustmentTo(Surface& out, const Layer& adj, int x, int y) {
    Pixel p = out.get(x, y);
    if (p.a == 0) return;
    double r, g, b;
    if (p.a == 255) {
        r = p.r;
        g = p.g;
        b = p.b;
    } else {
        r = (static_cast<double>(p.r) * 255.0 + p.a / 2.0) / p.a;
        g = (static_cast<double>(p.g) * 255.0 + p.a / 2.0) / p.a;
        b = (static_cast<double>(p.b) * 255.0 + p.a / 2.0) / p.a;
    }
    applyAdjustOp(adj.adjustType, adj.adjustAmount, adj.adjustStops, r, g,
                  b);
    // V+2 curves: canonical spec lives in adjustCurve (identity when
    // empty/invalid — the descriptor is still kept verbatim).
    if (adj.adjustType == "curves" && !adj.adjustCurve.empty())
        applyCurvesOp(adj.adjustCurve, r, g, b);
    const double a = p.a;
    auto repack = [&](double v) -> std::uint8_t {
        long q = std::lround(v * a / 255.0);
        if (q < 0) q = 0;
        if (q > p.a) q = p.a;
        return static_cast<std::uint8_t>(q);
    };
    p.r = repack(r);
    p.g = repack(g);
    p.b = repack(b);
    out.set(x, y, p);
}

// M8: adjustment branch of the per-layer walk. Returns true when the
// layer was an adjustment layer (the normal pixel-paint path must be
// skipped). Mask multiplies the EFFECT by mask alpha (masked-out regions
// see no change), clip uses the same stencil rule as normal layers, and
// opacity blends pre/post (opacity 1 = full transform, 0.5 = halfway,
// 0 = no-op); invisible layers are skipped by the caller.
inline bool walkAdjustment(Surface& out, const Layer& l, int x, int y) {
    if (l.adjustType.empty()) return false;
    if (l.clipped && out.get(x, y).a == 0) return true;
    float maskK = 1.0f;
    if (l.hasMask) {
        maskK = l.mask.get(x, y).a / 255.0f;
        if (maskK <= 0.0f) return true;
    }
    const float op = l.opacity * maskK;
    if (op <= 0.0f) return true;
    Pixel before = out.get(x, y);
    if (before.a == 0) return true;
    applyAdjustmentTo(out, l, x, y);
    if (op < 1.0f) {
        const Pixel after = out.get(x, y);
        Pixel m;
        m.a = before.a;  // alpha never moves
        m.r = static_cast<std::uint8_t>(
            std::lround(before.r + (after.r - before.r) * op));
        m.g = static_cast<std::uint8_t>(
            std::lround(before.g + (after.g - before.g) * op));
        m.b = static_cast<std::uint8_t>(
            std::lround(before.b + (after.b - before.b) * op));
        // Repacked parts already satisfy rgb <= a; the lerp of two such
        // values stays in range (clamp defensively anyway).
        if (m.r > m.a) m.r = m.a;
        if (m.g > m.a) m.g = m.a;
        if (m.b > m.a) m.b = m.a;
        out.set(x, y, m);
    }
    return true;
}

// Group-child variant: same as walkAdjustment but the clip stencil may
// come from a sibling of this group level (sibVecCache) instead of the
// running output, and `groupOp` carries the enclosing group's opacity
// (pass-through groups scale each child by l.opacity * child.opacity,
// mirroring the normal-layer scalePixel path).
inline bool walkChildAdjustment(
    Surface& out, const std::vector<Layer>& siblings,
    const std::vector<const Surface*>& sibVecCache, std::size_t ci,
    const Layer& child, float groupOp, int x, int y) {
    if (child.adjustType.empty()) return false;
    if (child.clipped &&
        !stencilPainted(siblings, sibVecCache, ci, out, x, y))
        return true;
    float maskK = 1.0f;
    if (child.hasMask) {
        maskK = child.mask.get(x, y).a / 255.0f;
        if (maskK <= 0.0f) return true;
    }
    const float op = groupOp * child.opacity * maskK;
    if (op <= 0.0f) return true;
    Pixel before = out.get(x, y);
    if (before.a == 0) return true;
    // Reuse the top-level helper by folding group/child opacity into a
    // shim layer view (Layer is plain data; adjust fields copied).
    Layer view;
    view.adjustType = child.adjustType;
    view.adjustAmount = child.adjustAmount;
    view.adjustStops = child.adjustStops;
    view.adjustCurve = child.adjustCurve;
    view.opacity = op;
    view.clipped = false;
    return walkAdjustment(out, view, x, y);
}

// Composite `layers` (document order, bottom-to-top) onto `out` (w x h).
// Issue #32 §3: [firstLayer, lastLayer) restricts the painted range so a
// caller that already owns the composite below can skip it.
void compositeInto(const std::vector<Layer>& layers, Surface& out, int w,
                   int h,
                   const std::vector<LayerAnimSample>* animTop = nullptr,
                   bool topLevel = true, bool forExport = false,
                   std::size_t firstLayer = 0,
                   std::size_t lastLayer = static_cast<std::size_t>(-1));

// A2: clip gate for one group level. `clipped` follows the M5 stencil
// rule at the level that owns the group (previous sibling when it is a
// raster layer, else the running accumulator).
struct GroupClip {
    const std::vector<Layer>* peers = nullptr;
    const std::vector<const Surface*>* vec = nullptr;
    std::size_t index = 0;
    bool clipped = false;
    bool ok(const Surface& out, int x, int y) const {
        if (!clipped) return true;
        return stencilPainted(*peers, *vec, index, out, x, y);
    }
};

// A2: pass-through group painter. Children composite straight onto the
// parent accumulator `out` (no isolated buffer) with their own blend
// modes, exactly like top-level layers; nested groups recurse and
// isolated children buffer first (then blend with their own mode).
// `opMul` carries the accumulated ancestor pass-through opacity — a
// pass-through group's opacity applies to each child (the documented
// approximation, ARCHITECTURE.md "A2 additions"). `mask` is the
// accumulated ancestor mask; the group's own mask is folded in as the
// byte product (effective alpha, same truncating multiply as a single
// mask). The group's own clipped flag is enforced through `clip`.
void compositePassThroughGroup(const Layer& g, Surface& out, int w, int h,
                               float opMul, const Surface* mask,
                               const GroupClip& clip, bool forExport) {
    std::vector<Surface> vecScratch;
    const std::vector<const Surface*> vecCache =
        rasterizeAllVectors(g.children, vecScratch, w, h);
    std::vector<Surface> fillScratch;
    const std::vector<const Surface*> fillCache =
        renderAllFills(g.children, fillScratch, w, h);
    const TextureCache texCache = renderAllTextures(g.children, w, h);
    // Effective mask = ancestor mask x group mask (alpha-only product).
    Surface combined;
    const Surface* effMask = mask;
    if (g.hasMask) {
        if (!mask) {
            effMask = &g.mask;
        } else {
            combined.resize(w, h);
            for (int y = 0; y < h; ++y)
                for (int x = 0; x < w; ++x) {
                    const int a = static_cast<int>(mask->get(x, y).a) *
                                  g.mask.get(x, y).a / 255;
                    combined.set(x, y, Pixel{0, 0, 0,
                                             static_cast<std::uint8_t>(a)});
                }
            effMask = &combined;
        }
    }
    const float groupOp = opMul * g.opacity;
    for (std::size_t ci = 0; ci < g.children.size(); ++ci) {
        const auto& child = g.children[ci];
        if (!child.visible || child.opacity <= 0.0f) continue;
        const GroupClip childClip{&g.children, &vecCache, ci, child.clipped};
        if (child.isGroup) {
            if (child.passThrough) {
                compositePassThroughGroup(child, out, w, h,
                                          groupOp * child.opacity, effMask,
                                          childClip, forExport);
                continue;
            }
            // Isolated child inside a pass-through group: buffer its
            // children, then blend the buffer with the child's mode.
            Surface tmp;
            tmp.resize(w, h);
            tmp.clear();
            compositeInto(child.children, tmp, w, h, nullptr, false,
                          forExport);
            const float op = groupOp * child.opacity;
            for (int y = 0; y < h; ++y)
                for (int x = 0; x < w; ++x) {
                    if (!clip.ok(out, x, y) || !childClip.ok(out, x, y))
                        continue;
                    Pixel s = tmp.get(x, y);
                    if (s.a == 0) continue;
                    if (effMask) {
                        const std::uint8_t ma = effMask->get(x, y).a;
                        if (ma == 0) continue;
                        if (ma != 255) {
                            s.r = static_cast<std::uint8_t>((int)s.r * ma /
                                                           255);
                            s.g = static_cast<std::uint8_t>((int)s.g * ma /
                                                           255);
                            s.b = static_cast<std::uint8_t>((int)s.b * ma /
                                                           255);
                            s.a = static_cast<std::uint8_t>((int)s.a * ma /
                                                           255);
                        }
                        if (s.a == 0) continue;
                    }
                    s = scalePixel(s, op);
                    Pixel d = out.get(x, y);
                    Pixel r;
                    blendPixel(s, d, child.blend, r);
                    out.set(x, y, r);
                }
            continue;
        }
        const Surface* childVec = vecCache[ci];
        const Surface* childFill = fillCache[ci];
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                if (!clip.ok(out, x, y)) continue;
                // M8: adjustment children transform the accumulated
                // output; they paint no pixels.
                if (walkChildAdjustment(out, g.children, vecCache, ci,
                                        child, groupOp, x, y))
                    continue;
                if (child.clipped &&
                    !stencilPainted(g.children, vecCache, ci, out, x, y))
                    continue;
                // T5: fill children paint their rendered content
                // (surface ignored while the fiche lives).
                Pixel s = childFill ? childFill->get(x, y)
                                    : layerPixel(child, childVec, x, y);
                if (s.a == 0) continue;
                shadeTexture(s, texCache, ci, x, y);
                if (effMask) {
                    const std::uint8_t ma = effMask->get(x, y).a;
                    if (ma == 0) continue;
                    if (ma != 255) {
                        s.r = static_cast<std::uint8_t>((int)s.r * ma / 255);
                        s.g = static_cast<std::uint8_t>((int)s.g * ma / 255);
                        s.b = static_cast<std::uint8_t>((int)s.b * ma / 255);
                        s.a = static_cast<std::uint8_t>((int)s.a * ma / 255);
                    }
                    if (s.a == 0) continue;
                }
                if (child.hasMask) {
                    const std::uint8_t ma = child.mask.get(x, y).a;
                    if (ma == 0) continue;
                    if (ma != 255) {
                        s.r = static_cast<std::uint8_t>((int)s.r * ma / 255);
                        s.g = static_cast<std::uint8_t>((int)s.g * ma / 255);
                        s.b = static_cast<std::uint8_t>((int)s.b * ma / 255);
                        s.a = static_cast<std::uint8_t>((int)s.a * ma / 255);
                    }
                    if (s.a == 0) continue;
                }
                s = scalePixel(s, groupOp * child.opacity);
                Pixel d = out.get(x, y);
                Pixel r;
                blendPixel(s, d, child.blend, r);
                out.set(x, y, r);
            }
        }
    }
}

// Composite `layers` (document order, bottom-to-top) onto `out` (w x h).
// M5 rules: a clipped layer skips any pixel where its stencil (above)
// has zero painted alpha; a layer with a mask multiplies its alpha by
// mask.a/255.
// M6: vector layers rasterize once per compositeInto call into a temp
// surface and flow through the same blend/opacity/mask/clip path.
// T3: `animTop` carries per-TOP-LEVEL-layer samples (nullptr = off);
// recursive group levels always pass null (groups ignore keys,
// ARCHITECTURE.md "T3 additions" §1).
// T5: `forExport` skips top-level reference/draft layers
// (ARCHITECTURE.md "T5 additions" §1 — editor composites keep them).
void compositeInto(const std::vector<Layer>& layers, Surface& out, int w,
                   int h,
                   const std::vector<LayerAnimSample>* animTop,
                   bool topLevel, bool forExport,
                   std::size_t firstLayer,
                   std::size_t lastLayer) {
    std::vector<Surface> vecScratch;
    const std::vector<const Surface*> vecCache =
        rasterizeAllVectors(layers, vecScratch, w, h);
    std::vector<Surface> bubScratch;
    const std::vector<const Surface*> bubCache =
        renderAllBubbles(layers, bubScratch, w, h);
    std::vector<Surface> fillScratch;
    const std::vector<const Surface*> fillCache =
        renderAllFills(layers, fillScratch, w, h);
    const TextureCache texCache = renderAllTextures(layers, w, h);
    const std::size_t end = lastLayer < layers.size() ? lastLayer
                                                      : layers.size();
    for (std::size_t li = firstLayer; li < end; ++li) {
        const auto& l = layers[li];
        if (!l.visible || l.opacity <= 0.0f) continue;
        // T5 §1: reference/draft layers never reach an export — but
        // only at the top level (flags live on top-level layers).
        if (forExport && topLevel && (l.reference || l.draft)) continue;
        if (l.isGroup) {
            if (l.passThrough) {
                // A2: children composite straight onto the parent
                // accumulator (no isolated buffer); nested groups
                // recurse, the group's mask/clip apply, and the group
                // opacity multiplies each child (documented
                // approximation, ARCHITECTURE.md "A2 additions").
                // The group's own blend mode is inactive while
                // pass-through (switch to isolated to apply it).
                compositePassThroughGroup(
                    l, out, w, h, 1.0f, nullptr,
                    GroupClip{&layers, &vecCache, li, l.clipped}, forExport);
            } else {
                // Isolated group: children composite into a clean buffer
                // with their own modes, then the buffer blends onto out
                // using the group's blend/opacity.
                Surface tmp;
                tmp.resize(w, h);
                tmp.clear();
                compositeInto(l.children, tmp, w, h, nullptr, false,
                              forExport);
                for (int y = 0; y < h; ++y) {
                    for (int x = 0; x < w; ++x) {
                        Pixel s = tmp.get(x, y);
                        if (s.a == 0) continue;
                        s = scalePixel(s, l.opacity);
                        Pixel d = out.get(x, y);
                        Pixel r;
                        blendPixel(s, d, l.blend, r);
                        out.set(x, y, r);
                    }
                }
            }
            continue;
        }
        const Surface* vecTmp = vecCache[li];
        const Surface* bubTmp = bubCache[li];
        // T3: active animation sample for this top-level layer (groups
        // and untracked layers paint normally).
        const LayerAnimSample* samp = nullptr;
        if (topLevel && animTop && li < animTop->size() &&
            (*animTop)[li].on && !l.isGroup)
            samp = &(*animTop)[li];
        // Issue #32 §3 perf: the common layer (normal blend, full
        // opacity, no mask/texture/clip/fill/bubble/adjustment/anim)
        // takes a raw-bytes source-over loop. Same float math and same
        // rounding as blendPixel(Normal) — byte-identical, locked by
        // tests — without a bounds-checked get/set per pixel.
        const bool rawFast =
            !samp && !vecTmp && !bubTmp && fillCache[li] == nullptr &&
            !l.hasMask &&
            !l.clipped && l.adjustType.empty() &&
            l.blend == BlendMode::Normal && l.opacity >= 1.0f &&
            texCache.strength[li] <= 0.0f &&
            l.surface.bytes().size() >=
                static_cast<std::size_t>(w) * h * 4u &&
            out.bytes().size() >= static_cast<std::size_t>(w) * h * 4u;
        if (rawFast) {
            const std::uint8_t* src = l.surface.bytes().data();
            std::uint8_t* dst = out.bytes().data();
            const std::size_t n =
                static_cast<std::size_t>(w) * static_cast<std::size_t>(h);
            for (std::size_t i = 0; i < n; ++i) {
                const std::size_t o = i * 4;
                const std::uint8_t a = src[o + 3];
                if (a == 0) continue;
                if (a == 255) {
                    dst[o] = src[o];
                    dst[o + 1] = src[o + 1];
                    dst[o + 2] = src[o + 2];
                    dst[o + 3] = 255;
                    continue;
                }
                // Same normalisation and same operation order as
                // blendPixel(Normal): u8() is /255, so the straight
                // colours are (byte/255)/alpha (bit-identical test).
                const float sa = static_cast<float>(a) / 255.0f;
                const float da = static_cast<float>(dst[o + 3]) / 255.0f;
                const float ia = 1.0f - sa;
                const float oa = sa + da * ia;
                const float sr = (static_cast<float>(src[o]) / 255.0f) / sa;
                const float sg =
                    (static_cast<float>(src[o + 1]) / 255.0f) / sa;
                const float sb =
                    (static_cast<float>(src[o + 2]) / 255.0f) / sa;
                const float dr = da > 0.0f
                                     ? (static_cast<float>(dst[o]) / 255.0f) / da
                                     : 0.0f;
                const float dg = da > 0.0f
                                     ? (static_cast<float>(dst[o + 1]) / 255.0f) / da
                                     : 0.0f;
                const float db = da > 0.0f
                                     ? (static_cast<float>(dst[o + 2]) / 255.0f) / da
                                     : 0.0f;
                const float cr = sr * sa + dr * da * ia;
                const float cg = sg * sa + dg * da * ia;
                const float cb = sb * sa + db * da * ia;
                const long ra = std::lround(oa * 255.0f);
                const long rr = std::lround(cr * 255.0f);
                const long rg = std::lround(cg * 255.0f);
                const long rb = std::lround(cb * 255.0f);
                std::uint8_t va = static_cast<std::uint8_t>(
                    ra < 0 ? 0 : (ra > 255 ? 255 : ra));
                std::uint8_t vr = static_cast<std::uint8_t>(
                    rr < 0 ? 0 : (rr > 255 ? 255 : rr));
                std::uint8_t vg = static_cast<std::uint8_t>(
                    rg < 0 ? 0 : (rg > 255 ? 255 : rg));
                std::uint8_t vb = static_cast<std::uint8_t>(
                    rb < 0 ? 0 : (rb > 255 ? 255 : rb));
                if (vr > va) vr = va;
                if (vg > va) vg = va;
                if (vb > va) vb = va;
                dst[o] = vr;
                dst[o + 1] = vg;
                dst[o + 2] = vb;
                dst[o + 3] = va;
            }
            continue;
        }
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                // M8: adjustment layers paint no pixels; their op
                // transforms the accumulated output of everything below.
                if (walkAdjustment(out, l, x, y)) continue;
                if (l.clipped && !stencilPainted(layers, vecCache, li, out, x, y))
                    continue;
                // T2 bubble: composite-time balloon OVER the layer's own
                // pixels (the fiche never commits pixels).
                // T5: fill layers paint their rendered content (the
                // surface is ignored while the fiche lives).
                const Surface* fillTmp = fillCache[li];
                Pixel s;
                if (samp) {
                    // T3: inverse-map through the sampled transform
                    // about the layer center, bilinear resample.
                    double sx, sy;
                    animInverseMap(x, y, w / 2.0, h / 2.0,
                                   LayerKeys{samp->dx, samp->dy, samp->scale,
                                             samp->angle, samp->opacity},
                                   sx, sy);
                    const Surface* src =
                        fillTmp ? fillTmp : (vecTmp ? vecTmp : &l.surface);
                    s = sampleBilinear(*src, sx, sy);
                } else if (fillTmp) {
                    s = fillTmp->get(x, y);
                } else {
                    s = layerPixel(l, vecTmp, x, y);
                }
                if (bubTmp) {
                    const Pixel bpx = bubTmp->get(x, y);
                    if (bpx.a != 0) {
                        // Source-over balloon onto the layer pixel.
                        const int ia = 255 - bpx.a;
                        Pixel m;
                        m.r = static_cast<std::uint8_t>(
                            bpx.r + (s.r * ia + 127) / 255);
                        m.g = static_cast<std::uint8_t>(
                            bpx.g + (s.g * ia + 127) / 255);
                        m.b = static_cast<std::uint8_t>(
                            bpx.b + (s.b * ia + 127) / 255);
                        m.a = static_cast<std::uint8_t>(
                            bpx.a + (s.a * ia + 127) / 255);
                        s = m;
                    }
                }
                if (s.a == 0) continue;
                // B1: non-destructive texture modulation (before the
                // mask/opacity/blend path, alpha untouched).
                shadeTexture(s, texCache, li, x, y);
                if (l.hasMask) {
                    const std::uint8_t ma = l.mask.get(x, y).a;
                    if (ma == 0) continue;
                    if (ma != 255) {
                        s.r = static_cast<std::uint8_t>((int)s.r * ma / 255);
                        s.g = static_cast<std::uint8_t>((int)s.g * ma / 255);
                        s.b = static_cast<std::uint8_t>((int)s.b * ma / 255);
                        s.a = static_cast<std::uint8_t>((int)s.a * ma / 255);
                    }
                    if (s.a == 0) continue;
                }
                s = scalePixel(s, l.opacity);
                // T3: sampled opacity multiplies the layer opacity
                // (alpha-only path, rgb stays <= alpha).
                if (samp) s = scalePixel(s, samp->opacity);
                Pixel d = out.get(x, y);
                Pixel r;
                blendPixel(s, d, l.blend, r);
                out.set(x, y, r);
            }
        }
    }
}

}  // namespace

void Layer::addMask(int w, int h) {
    mask.resize(w, h);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x)
            mask.set(x, y, Pixel{255, 255, 255, 255});
    hasMask = true;
}

void Layer::clearMask() {
    hasMask = false;
    mask.resize(0, 0);
}

void Layer::resize(int w, int h) {
    if (surface.width() != w || surface.height() != h) surface.resize(w, h);
    if (hasMask && (mask.width() != w || mask.height() != h)) {
        // Keep the intersection, white-fill the rest (grow case).
        Surface grown(w, h);
        for (int y = 0; y < h; ++y)
            for (int x = 0; x < w; ++x)
                grown.set(x, y, Pixel{255, 255, 255, 255});
        const int cw = mask.width() < w ? mask.width() : w;
        const int ch = mask.height() < h ? mask.height() : h;
        for (int y = 0; y < ch; ++y)
            for (int x = 0; x < cw; ++x) grown.set(x, y, mask.get(x, y));
        mask = std::move(grown);
    }
    for (auto& ch : children) ch.resize(w, h);
}

void Canvas::resize(int w, int h) {
    w_ = w < 0 ? 0 : w;
    h_ = h < 0 ? 0 : h;
    for (auto& l : layers_) l.resize(w_, h_);
}

std::size_t Canvas::addLayer(const std::string& name) {
    Layer l;
    l.name = name;
    l.surface.resize(w_, h_);
    layers_.push_back(std::move(l));
    if (active_ < 0) active_ = 0;
    return layers_.size() - 1;
}

std::size_t Canvas::addGroup(const std::string& name) {
    Layer l;
    l.name = name;
    l.isGroup = true;
    layers_.push_back(std::move(l));
    if (active_ < 0) active_ = 0;
    return layers_.size() - 1;
}

void Canvas::adoptLayer(Layer l) {
    l.resize(w_, h_);
    layers_.push_back(std::move(l));
    if (active_ < 0) active_ = 0;
}

void Canvas::removeLayer(std::size_t i) {
    if (i >= layers_.size()) return;
    layers_.erase(layers_.begin() + i);
    if (layers_.empty()) {
        active_ = -1;
    } else if (active_ >= static_cast<int>(layers_.size())) {
        active_ = static_cast<int>(layers_.size()) - 1;
    }
}

void Canvas::moveLayer(std::size_t from, std::size_t to) {
    if (from >= layers_.size() || to >= layers_.size() || from == to) return;
    Layer l = std::move(layers_[from]);
    layers_.erase(layers_.begin() + from);
    layers_.insert(layers_.begin() + to, std::move(l));
}

bool Canvas::activeIsRaster() const {
    if (active_ < 0 || active_ >= static_cast<int>(layers_.size()))
        return false;
    return !layers_[active_].isGroup;
}

void Canvas::composite(Surface& out) const {
    out.resize(w_, h_);
    out.clear();
    compositeInto(layers_, out, w_, h_);
}

void Canvas::compositeRange(Surface& out, std::size_t firstLayer,
                            std::size_t lastLayer) const {
    if (firstLayer == 0) {
        out.resize(w_, h_);
        out.clear();
    }
    compositeInto(layers_, out, w_, h_, nullptr, true, false, firstLayer,
                  lastLayer);
}

void Canvas::compositeExport(Surface& out) const {
    out.resize(w_, h_);
    out.clear();
    compositeInto(layers_, out, w_, h_, nullptr, true, true);
}

void Canvas::compositeAnim(
    Surface& out, const std::vector<LayerAnimSample>& samples) const {
    out.resize(w_, h_);
    out.clear();
    compositeInto(layers_, out, w_, h_, &samples, true);
}

void Canvas::compositeAnimExport(
    Surface& out, const std::vector<LayerAnimSample>& samples) const {
    out.resize(w_, h_);
    out.clear();
    compositeInto(layers_, out, w_, h_, &samples, true, true);
}

}  // namespace openclip
