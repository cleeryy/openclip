// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip M1 engine — canvas of N layers over a Surface each.
#ifndef OPENCLIP_CANVAS_H
#define OPENCLIP_CANVAS_H

#include <cstdint>
#include <map>
#include <memory>
#include <string>
#include <vector>

#include "openclip/blend.h"
#include "openclip/surface.h"
#include "openclip/vector.h"

namespace openclip {

// T3: per-layer animation sample aligned with a top-level layer list
// (ARCHITECTURE.md "T3 additions" §1). on=false = paint normally;
// groups ignore samples (documented).
struct LayerAnimSample {
    bool on = false;
    float dx = 0.0f, dy = 0.0f;
    float scale = 1.0f;
    float angle = 0.0f;    // degrees, clockwise
    float opacity = 1.0f;  // extra factor over the layer opacity
};

struct Layer {
    std::string name = "layer";
    float opacity = 1.0f;  // 0..1
    bool visible = true;
    BlendMode blend = BlendMode::Normal;
    Surface surface;

    // T5: per-layer flags (ARCHITECTURE.md "T5 additions" §1). Plain
    // data like the M8 fiches: survive resize/copy/ORA. locked =
    // every write refused (false + intact); alphaLock = paint commits
    // only where dst alpha > 0; reference/draft = editor-visible but
    // excluded from every export composite.
    bool locked = false;
    bool alphaLock = false;
    bool reference = false;
    bool draft = false;

    // T5: fill fiche (ARCHITECTURE.md "T5 additions" §2). Empty
    // fillKind = normal layer. A set kind renders COMPOSITE-time
    // (never committed pixels): the surface is kept but ignored while
    // the fiche lives, restored untouched when it clears. Mask/clip/
    // opacity/visibility apply exactly like normal layers. Meta hors
    // undo. Opaque to the composite except through fill_layer.h.
    std::string fillKind;    // "" | "solid" | "gradient" | "foil"
    std::string fillColor;   // #RRGGBB (solid; gradient/foil tint)
    std::string fillStops;   // canonical "#RRGGBB:pos,..." (gradient)
    std::string fillParams;  // raw span "mode|x0|y0|x1|y1|hue|scale|shine"
    bool hasFill() const { return !fillKind.empty(); }

    // B1: non-destructive layer texture (ARCHITECTURE.md "B1
    // additions" §5). Empty kind = no texture. The fiche paints no
    // pixels: compositeInto modulates each pixel's straight colour
    // through a deterministic height field (alpha untouched). Plain
    // data: survives resize/copy/ORA like the M8/T5 fiches.
    std::string textureKind;    // "" | "paper" | "canvas" | "grain"
    std::string textureParams;  // "scale|strength|seed"
    bool hasTexture() const { return !textureKind.empty(); }

    // M5: per-layer alpha mask (straight alpha in mask.a; rgb ignored).
    // Composite multiplies layer alpha by mask.a/255 per pixel when set.
    Surface mask;
    bool hasMask = false;
    // M5: clip to the stencil below (composites only where the running
    // target already has painted alpha; see compositeInto).
    bool clipped = false;

    // M8: adjustment layer descriptor (ARCHITECTURE.md "M8 additions").
    // Empty adjustType = normal layer. A set type means the layer paints
    // no pixels; at composite time its op transforms the accumulated
    // output of everything below (unpremultiplied, repremultiplied,
    // clamped to [0,alpha], alpha untouched). Unknown types are stored
    // but behave as identity. Plain data: survives resize/copy/ORA.
    std::string adjustType;
    double adjustAmount = 0.0;
    std::string adjustStops;
    // V+2: curves descriptor canonical spec ("channel;i,o;...", see
    // filters.h parseCurvesDescriptor). Only read when adjustType is
    // "curves". Plain data like the M8 fields.
    std::string adjustCurve;

    // V+1: text layer fiche (ARCHITECTURE.md "V+1 additions"). Opaque
    // to the engine: stored from nSetLayerText JSON, round-tripped
    // through ORA as x-openclip-* attrs, surfaced read-only in
    // nLayerState. Empty textContent = normal layer (no fiche). The
    // engine never rasterizes text: layout is app-side (platform
    // Canvas) and pixels are committed via nBlitPngIntoLayer.
    // Plain data: survives resize/copy/ORA like the M8 descriptor.
    std::string textContent;  // x-openclip-text (empty = no fiche)
    std::string textFont;     // x-openclip-font, default sans-serif
    int textSizePx = 0;       // x-openclip-size (px, 0 = unset)
    std::string textStyle;    // regular|bold|italic|bold-italic
    std::string textAlign;    // left|center|right
    double textLeading = 1.0;   // x-openclip-leading factor
    double textTracking = 0.0;  // x-openclip-tracking px
    std::string textColor;    // x-openclip-color #RRGGBB
    std::string textBox;      // x-openclip-box "x,y,w"
    // T2 text extensions (ARCHITECTURE.md §T2 §1–§2, opaque keys like
    // the V+1 fiche above): runs JSON, outline JSON, imported font
    // file name (REPRO repli: bytes live app-side, absent = fallback),
    // direction "h"|"v", hyphen flag ("1"|"" ).
    std::string textRuns;     // x-openclip-text-runs
    std::string textOutline;  // x-openclip-text-outline
    std::string textFontFile;  // x-openclip-text-fontfile
    std::string textDir;      // x-openclip-text-dir ("v" = vertical)
    std::string textHyphen;   // x-openclip-text-hyphen ("1" = on)
    // B2 text transform (ARCHITECTURE.md "B2 additions" §1): opaque
    // "rot,sx,sy,skew" tuple (empty = identity), persisted as
    // x-openclip-text-xform and read back by nLayerState "xform".
    std::string textXform;
    bool hasText() const { return !textContent.empty(); }

    // T2 bubble fiche (ARCHITECTURE.md §T2 §5, opaque like M8/V+1):
    // a layer carrying a bubble renders it at COMPOSITE time (never
    // committed pixels). Empty shape = normal layer. Meta, NOT in
    // undo (like nSetLayerMeta). Text link = top-level layer index
    // (-1 = none), resolved app-side for inline re-edit.
    std::string bubbleShape;  // "" | "ellipse" | "rounded"
    std::string bubbleFrame;  // "x,y,w,h" px
    std::string bubbleTail;   // "bx,by,tx,ty" (empty = no tail)
    std::string bubbleFill;   // #RRGGBB, default #FFFFFF
    std::string bubbleStroke;  // #RRGGBB, default #000000
    float bubbleStrokeW = 4.0f;  // 1..32 px, centered on the edge
    int bubbleTextLayer = -1;
    bool hasBubble() const { return !bubbleShape.empty(); }

    // V+2: generic opaque fiche map (ARCHITECTURE.md "V+2 additions",
    // nSetLayerMeta). Keys are restricted shim-side to
    // "x-openclip-[a-z0-9-]*"; values are opaque strings (<= 4 KiB).
    // Persisted verbatim as ORA <layer> attrs, reloaded the same way.
    // Plain data: survives resize/copy/ORA like the M8/V+1 fiches.
    std::map<std::string, std::string> meta;

    // M6: vector layer state. isVector layers store resolution-
    // independent paths (vectorData) and rasterize them at composite
    // time into a temp surface that flows through the same
    // blend/opacity/mask/clip path as a normal raster layer.
    bool isVector = false;
    std::vector<VectorPath> vectorData;
    // T2: set by the ORA writer (sibling within the 256 KiB cap) and
    // by the ORA loader (sibling present and parsed); surfaced in
    // nLayerState as "vectorStored" (the visible over-cap counter).
    bool vectorStored = false;

    // Group support (M4, OpenRaster round trip).
    bool isGroup = false;
    std::vector<Layer> children;              // empty for raster layers
    bool passThrough = true;                  // groups only: children blend
                                              // straight into the target

    // Attach a full-white (fully showing) mask sized w x h.
    void addMask(int w, int h);
    void clearMask();

    // Recursive size sync (groups: children surfaces get sized too;
    // an attached mask is resized alongside the layer surface).
    void resize(int w, int h);
};

class Canvas {
public:
    Canvas() = default;
    Canvas(int w, int h) { resize(w, h); }

    void resize(int w, int h);
    int width() const { return w_; }
    int height() const { return h_; }

    std::size_t layerCount() const { return layers_.size(); }
    Layer& layer(std::size_t i) { return layers_[i]; }
    const Layer& layer(std::size_t i) const { return layers_[i]; }
    std::size_t addLayer(const std::string& name);
    // Adds a group layer; children must be added via the returned Layer&.
    std::size_t addGroup(const std::string& name);
    // Adopts a fully built layer (used by document loaders). Appends at
    // the top level, resizing its surfaces to the canvas size.
    void adoptLayer(Layer l);
    void removeLayer(std::size_t i);
    void moveLayer(std::size_t from, std::size_t to);

    int activeLayer() const { return active_; }
    void setActiveLayer(int i) { active_ = i; }

    // Composite all visible layers bottom-to-top into out (resized as
    // needed). Groups composite recursively: pass-through groups blend
    // children with their own blend modes; non-pass-through groups
    // composite children into a temp surface first, then blend the temp
    // with the group's blend mode and opacity.
    void composite(Surface& out) const;
    // Issue #32 §3 perf: incremental composite. Paints only the top-level
    // layers [firstLayer, lastLayer) into `out`, which the caller
    // pre-filled with the composite of [0, firstLayer) (obtained from an
    // earlier compositeRange(out, 0, firstLayer)). The result is exactly
    // the same bytes as composite(out) — locked by test. Used by the shim
    // to reuse the layers-below cache while a stroke is open, so a frame
    // never recomposites layers that did not change. `out` must already
    // be w x h (resize() is the caller's job for firstLayer == 0).
    void compositeRange(Surface& out, std::size_t firstLayer,
                        std::size_t lastLayer) const;
    // T5: export walk — like composite() but top-level reference/draft
    // layers are skipped (ARCHITECTURE.md "T5 additions" §1). ORA keeps
    // everything (working round trip); PNG/PSD/file exports use this.
    void compositeExport(Surface& out) const;
    // T3: same walk with per-top-level-layer animation samples
    // (samples.size() < layerCount() = missing entries are off).
    // Identity samples paint exactly like composite() (locked by
    // test: no keys = byte-identical). Groups ignore samples.
    void compositeAnim(Surface& out,
                       const std::vector<LayerAnimSample>& samples) const;
    // T5: compositeAnim with the §1 export exclusion (reference/draft
    // top-level layers skipped — the export composite).
    void compositeAnimExport(
        Surface& out, const std::vector<LayerAnimSample>& samples) const;

    // True when the active top-level index paints (not a group).
    bool activeIsRaster() const;

private:
    int w_ = 0, h_ = 0;
    int active_ = -1;
    std::vector<Layer> layers_;
};

}  // namespace openclip

#endif
