// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip B2 — atomic text-layer commit (Canvas + History only).
#include "openclip/text_layer.h"

namespace openclip {
namespace {

std::string capped(const std::string& v, std::size_t n) {
    return v.size() <= n ? v : v.substr(0, n);
}

}  // namespace

void applyTextFiche(Layer& l, const TextLayerFiche& f) {
    l.textContent = capped(f.content, 8192);
    l.textFont = f.font.empty() ? "sans-serif" : capped(f.font, 128);
    l.textSizePx = (f.sizePx >= 1 && f.sizePx <= 4096) ? f.sizePx : 0;
    l.textStyle = f.style.empty() ? "regular" : capped(f.style, 128);
    l.textAlign = f.align.empty() ? "left" : capped(f.align, 128);
    l.textLeading =
        (f.leading >= 0.5 && f.leading <= 4.0) ? f.leading : 1.0;
    l.textTracking =
        (f.tracking >= -64.0 && f.tracking <= 64.0) ? f.tracking : 0.0;
    l.textColor = f.color.empty() ? "#141414" : capped(f.color, 128);
    l.textBox = capped(f.box, 128);
    l.textRuns = capped(f.runs, 16384);
    l.textOutline = capped(f.outline, 256);
    l.textFontFile = capped(f.fontFile, 128);
    l.textDir = (f.dir == "v") ? "v" : "";
    l.textHyphen = (f.hyphen == "1" || f.hyphen == "true") ? "1" : "";
    // B2 transform tuple: kept opaque; an all-identity tuple clears it
    // (same clean-degrade rule as the T2 fiches).
    l.textXform = capped(f.xform, 96);
    if (l.textXform == "0,1,1,0" || l.textXform == "0.0,1.0,1.0,0.0")
        l.textXform.clear();
}

void clearTextFiche(Layer& l) {
    l.textContent.clear();
    l.textFont.clear();
    l.textSizePx = 0;
    l.textStyle.clear();
    l.textAlign.clear();
    l.textLeading = 1.0;
    l.textTracking = 0.0;
    l.textColor.clear();
    l.textBox.clear();
    l.textRuns.clear();
    l.textOutline.clear();
    l.textFontFile.clear();
    l.textDir.clear();
    l.textHyphen.clear();
    l.textXform.clear();
}

bool commitTextLayer(Canvas& c, History& h, const TextLayerCommit& spec,
                     std::string& err) {
    err.clear();
    if (!spec.pixels || spec.pixels->width() != c.width() ||
        spec.pixels->height() != c.height()) {
        err = "text_layer: pixels must match the canvas size";
        return false;
    }
    if (!spec.fiche.hasContent()) {
        err = "text_layer: empty text fiche";
        return false;
    }
    if (spec.index >= static_cast<int>(c.layerCount()) ||
        spec.index < -1) {
        err = "text_layer: bad layer index";
        return false;
    }
    if (spec.index >= 0) {
        const Layer& ly = c.layer(static_cast<std::size_t>(spec.index));
        if (ly.isGroup || ly.isVector || !ly.adjustType.empty() ||
            ly.hasFill() || ly.locked || ly.alphaLock) {
            err = "text_layer: target layer refuses pixel writes";
            return false;
        }
    }
    if (h.snapshotPending()) {
        err = "text_layer: another structural op is pending";
        return false;
    }
    h.beginSnapshot(c);
    int idx = spec.index;
    if (idx < 0) {
        idx = static_cast<int>(
            c.addLayer(spec.name.empty() ? "Text" : spec.name));
    }
    Layer& ly = c.layer(static_cast<std::size_t>(idx));
    ly.surface.bytes() = spec.pixels->bytes();
    applyTextFiche(ly, spec.fiche);
    c.setActiveLayer(idx);
    h.endSnapshot(c);
    return true;
}

}  // namespace openclip
