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

namespace openclip {

struct Layer {
    std::string name = "layer";
    float opacity = 1.0f;  // 0..1
    bool visible = true;
    BlendMode blend = BlendMode::Normal;
    Surface surface;

    // Group support (M4, OpenRaster round trip).
    bool isGroup = false;
    std::vector<Layer> children;              // empty for raster layers
    bool passThrough = true;                  // groups only: children blend
                                              // straight into the target

    // Recursive size sync (groups: children surfaces get sized too).
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
