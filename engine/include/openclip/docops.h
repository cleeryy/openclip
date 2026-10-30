// SPDX-License-Identifier: GPL-3.0-or-later
// OpenClip W1 (issue #28 slice A) — structural document operations:
// merge down / merge visible / flatten / rasterize. Pure Canvas logic
// (no JNI, no Android types), ARCHITECTURE.md "W1 additions".
//
// Every operation is RENDER-PRESERVING: the composite before and after
// is compared byte-for-byte; a difference reverts the mutation and
// returns false with a human-readable reason. Refusal is always safer
// than a silent pixel change (clip/translucent cases that cannot be
// baked honestly are refused, never approximated).
#ifndef OPENCLIP_DOCOPS_H
#define OPENCLIP_DOCOPS_H

#include <cstddef>
#include <string>

#include "openclip/canvas.h"

namespace openclip {

// Merge layer `index` into the raster layer directly below it. A group
// at `index` is flattened first (its children rendered into one raster),
// then merged down. Merging a raster ONTO a group is refused. Both layer
// opacity, the layer mask and the blend of the source are baked into the
// result (the merged layer is a plain Normal raster at the destination
// slot, keeping the destination's name/visibility/flags).
bool mergeDown(Canvas& c, std::size_t index, std::string& err);

// Collapse every VISIBLE top-level layer into a single raster layer at
// the topmost visible slot. Hidden layers are preserved untouched (order
// kept). Fewer than two visible layers = refused (nothing to merge).
bool mergeVisible(Canvas& c, std::string& err);

// Flatten the whole document into one raster layer using the EXPORT
// composite: reference/draft layers are editor-only aids and are dropped
// (same rule as every export), so the editor composite may change by
// design when such layers exist.
bool flattenDocument(Canvas& c, std::string& err);

// Rasterize one layer: vector paths, fill/text/bubble fiches, layer
// opacity and layer mask become plain pixels (the clip flag survives).
// A group becomes one raster in place. Rasterizing an ADJUSTMENT layer
// consumes every layer below it (an adjustment transforms their
// composite by definition) and replaces them with the adjusted raster.
// False + intact when the layer is already a plain raster layer.
bool rasterizeLayer(Canvas& c, std::size_t index, std::string& err);

}  // namespace openclip

#endif
