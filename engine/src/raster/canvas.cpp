// SPDX-License-Identifier: GPL-3.0-or-later
#include "openclip/canvas.h"

#include "openclip/blend.h"

#include <utility>

namespace openclip {

namespace {

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

// Composite `layers` (document order, bottom-to-top) onto `out` (w x h).
// M5 rules: a clipped layer skips any pixel where the running target
// (already composited output = the stencil of everything below it) has
// zero alpha; a layer with a mask multiplies its alpha by mask.a/255.
void compositeInto(const std::vector<Layer>& layers, Surface& out, int w,
                   int h) {
    for (std::size_t li = 0; li < layers.size(); ++li) {
        const auto& l = layers[li];
        if (!l.visible || l.opacity <= 0.0f) continue;
        if (l.isGroup) {
            if (l.passThrough) {
                // Children blend directly into the target with their own
                // modes; the group's opacity multiplies each child note
                // (OpenRaster pass-through semantics approximation).
                for (const auto& child : l.children) {
                    if (!child.visible || child.opacity <= 0.0f) continue;
                    Surface tmp;
                    tmp.resize(w, h);
                    tmp.clear();
                    // Empty workspace for one child's premultiplied data,
                    // then blend onto out so order stays correct.
                    for (int y = 0; y < h; ++y) {
                        for (int x = 0; x < w; ++x) {
                            if (child.clipped && out.get(x, y).a == 0) continue;
                            Pixel s = child.surface.get(x, y);
                            if (s.a == 0) continue;
                            if (child.hasMask) {
                                const std::uint8_t ma = child.mask.get(x, y).a;
                                if (ma == 0) continue;
                                if (ma != 255) {
                                    s.r = static_cast<std::uint8_t>(
                                        (int)s.r * ma / 255);
                                    s.g = static_cast<std::uint8_t>(
                                        (int)s.g * ma / 255);
                                    s.b = static_cast<std::uint8_t>(
                                        (int)s.b * ma / 255);
                                    s.a = static_cast<std::uint8_t>(
                                        (int)s.a * ma / 255);
                                }
                                if (s.a == 0) continue;
                            }
                            s = scalePixel(s, l.opacity * child.opacity);
                            Pixel d = out.get(x, y);
                            Pixel r;
                            blendPixel(s, d, child.blend, r);
                            out.set(x, y, r);
                        }
                    }
                }
            } else {
                // Isolated group: children composite into a clean buffer
                // with their own modes, then the buffer blends onto out
                // using the group's blend/opacity.
                Surface tmp;
                tmp.resize(w, h);
                tmp.clear();
                compositeInto(l.children, tmp, w, h);
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
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                if (l.clipped && out.get(x, y).a == 0) continue;
                Pixel s = l.surface.get(x, y);
                if (s.a == 0) continue;
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

}  // namespace openclip
