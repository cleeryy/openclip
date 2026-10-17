// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip M1 engine — canvas of N layers over a Surface each.
#ifndef OPENCLIP_CANVAS_H
#define OPENCLIP_CANVAS_H

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "openclip/blend.h"
#include "openclip/surface.h"
#include "openclip/vector.h"

namespace openclip {

struct Layer {
    std::string name = "layer";
    float opacity = 1.0f;  // 0..1
    bool visible = true;
    BlendMode blend = BlendMode::Normal;
    Surface surface;

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

    // M6: vector layer state. isVector layers store resolution-
    // independent paths (vectorData) and rasterize them at composite
    // time into a temp surface that flows through the same
    // blend/opacity/mask/clip path as a normal raster layer.
    bool isVector = false;
    std::vector<VectorPath> vectorData;

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

    // True when the active top-level index paints (not a group).
    bool activeIsRaster() const;

private:
    int w_ = 0, h_ = 0;
    int active_ = -1;
    std::vector<Layer> layers_;
};

}  // namespace openclip

#endif
