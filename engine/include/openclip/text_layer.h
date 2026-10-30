// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip B2 (issue #31 slice B) — atomic text-layer commit.
//
// A text layer is a normal raster layer plus the opaque text fiche
// (V+1/T2). The app renders the glyphs itself and hands the full
// canvas-size RGBA surface here; the engine replaces (or creates) the
// layer pixels AND applies the fiche inside ONE document snapshot, so
// a text transform commit undoes with exactly one nUndo.
//
// Pure Canvas + History logic: no JNI, no Android types, host
// testable. ARCHITECTURE.md "B2 additions" §1.
#ifndef OPENCLIP_TEXT_LAYER_H
#define OPENCLIP_TEXT_LAYER_H

#include <string>

#include "openclip/canvas.h"
#include "openclip/surface.h"
#include "openclip/undo.h"

namespace openclip {

// Engine-side mirror of the V+1/T2 text fiche. B2 adds `xform`
// ("rot,sx,sy,skew", opaque to the engine like the runs JSON).
struct TextLayerFiche {
    std::string content;  // empty = no fiche
    std::string font = "sans-serif";
    std::string style = "regular";
    std::string align = "left";
    std::string color = "#141414";
    std::string box;
    std::string runs;
    std::string outline;
    std::string fontFile;
    std::string dir;
    std::string hyphen;
    std::string xform;  // B2: "rot,sx,sy,skew" (empty = identity)
    int sizePx = 0;
    double leading = 1.0;
    double tracking = 0.0;

    bool hasContent() const { return !content.empty(); }
};

// Copy every field onto the layer (identity fields normalised, the
// same defaults nSetLayerText uses).
void applyTextFiche(Layer& l, const TextLayerFiche& f);

// Drop the whole fiche (nSetLayerText null/empty behaviour).
void clearTextFiche(Layer& l);

// One atomic text commit.
struct TextLayerCommit {
    int index = -1;        // < 0 = append a new raster layer
    std::string name;      // new layer name (empty = "Text")
    const Surface* pixels = nullptr;  // full canvas-size premultiplied RGBA
    TextLayerFiche fiche;
};

// Commit text pixels + fiche as ONE undo step.
// Refused (false + `err` + document/history untouched) when:
//   * `pixels` is null or not exactly the canvas size,
//   * the fiche has no content,
//   * `index` addresses a group / vector / adjustment / fill / locked /
//     alpha-locked layer (no committable pixels or write refused).
bool commitTextLayer(Canvas& c, History& h, const TextLayerCommit& spec,
                     std::string& err);

}  // namespace openclip

#endif
