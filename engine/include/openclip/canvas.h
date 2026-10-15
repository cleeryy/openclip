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
    void removeLayer(std::size_t i);
    void moveLayer(std::size_t from, std::size_t to);

    int activeLayer() const { return active_; }
    void setActiveLayer(int i) { active_ = i; }

    // Composite all visible layers bottom-to-top into out (resized as needed).
    void composite(Surface& out) const;

private:
    int w_ = 0, h_ = 0;
    int active_ = -1;
    std::vector<Layer> layers_;
};

}  // namespace openclip

#endif
