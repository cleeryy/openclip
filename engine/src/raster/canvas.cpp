// SPDX-License-Identifier: GPL-3.0-or-later
#include "openclip/canvas.h"

namespace openclip {

void Canvas::resize(int w, int h) {
    w_ = w < 0 ? 0 : w;
    h_ = h < 0 ? 0 : h;
    for (auto& l : layers_) l.surface.resize(w_, h_);
}

std::size_t Canvas::addLayer(const std::string& name) {
    Layer l;
    l.name = name;
    l.surface.resize(w_, h_);
    layers_.push_back(std::move(l));
    if (active_ < 0) active_ = 0;
    return layers_.size() - 1;
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

void Canvas::composite(Surface& out) const {
    out.resize(w_, h_);
    out.clear();
    for (const auto& l : layers_) {
        if (!l.visible || l.opacity <= 0.0f) continue;
        for (int y = 0; y < h_; ++y) {
            for (int x = 0; x < w_; ++x) {
                Pixel s = l.surface.get(x, y);
                if (s.a == 0) continue;
                if (l.opacity < 1.0f) {
                    s.r = static_cast<std::uint8_t>(s.r * l.opacity + 0.5f);
                    s.g = static_cast<std::uint8_t>(s.g * l.opacity + 0.5f);
                    s.b = static_cast<std::uint8_t>(s.b * l.opacity + 0.5f);
                    s.a = static_cast<std::uint8_t>(s.a * l.opacity + 0.5f);
                }
                Pixel d = out.get(x, y);
                Pixel r;
                blendPixel(s, d, l.blend, r);
                out.set(x, y, r);
            }
        }
    }
}

}  // namespace openclip
