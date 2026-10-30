// OpenClip M1 — engine host: frozen JNI contract from ARCHITECTURE.md
// plus three explicitly accepted additions (nLayerCount, nSetActiveLayer,
// nAddLayer) documented in ARCHITECTURE.md. Single render-thread calls.
package dev.openclip.engine

object EngineHost {
    init {
        System.loadLibrary("openclip")
    }

    // Frozen contract (ARCHITECTURE.md)
    external fun nInit(width: Int, height: Int, dpi: Float): Long
    external fun nDispose(handle: Long)
    external fun nSetBrush(handle: Long, json: String)
    external fun nStrokeBegin(handle: Long, x: Float, y: Float, pressure: Float): Long
    external fun nStrokeMove(handle: Long, strokeId: Long, x: Float, y: Float, pressure: Float)
    external fun nStrokeEnd(handle: Long, strokeId: Long)
    external fun nCompositeInto(handle: Long, pixels: IntArray, width: Int, height: Int): Int
    external fun nUndo(handle: Long): Boolean
    external fun nRedo(handle: Long): Boolean

    // M2 additions (documented in ARCHITECTURE.md)
    external fun nLayerCount(handle: Long): Int
    external fun nSetActiveLayer(handle: Long, index: Int): Int
    external fun nAddLayer(handle: Long, name: String): Int
    external fun nLoadMyb(handle: Long, xml: String): Boolean

    // M3 additions (documented in ARCHITECTURE.md)
    external fun nApplyFilter(handle: Long, name: String, paramsJson: String): Boolean

    // M4 additions (documented in ARCHITECTURE.md)
    external fun nLoadDocument(handle: Long, path: String): Boolean
    external fun nSaveDocument(handle: Long, path: String, format: String): Boolean

    // M5 additions (documented in ARCHITECTURE.md): layer tree, clipping, masks
    external fun nSetLayerVisibility(handle: Long, index: Int, visible: Boolean): Boolean
    external fun nSetLayerOpacity(handle: Long, index: Int, opacity: Float): Boolean
    external fun nSetLayerBlend(handle: Long, index: Int, name: String): Boolean
    external fun nSetLayerName(handle: Long, index: Int, name: String): Boolean
    external fun nAddGroup(handle: Long, name: String): Int
    external fun nMoveLayer(handle: Long, from: Int, to: Int): Boolean
    external fun nAppendChild(handle: Long, parent: Int, name: String): Int
    external fun nSetLayerClipped(handle: Long, index: Int, clipped: Boolean): Boolean
    external fun nAddLayerMask(handle: Long, index: Int): Boolean
    external fun nMaskTarget(handle: Long, index: Int, onMask: Boolean): Boolean
    // Panel introspection: compact JSON snapshot of the top-level layer
    // list (names, groups, visibility, opacity, blend, mask, clipped).
    external fun nLayerState(handle: Long): String

    // M6 additions (ARCHITECTURE.md): vector layers + rulers.
    external fun nAddVectorLayer(handle: Long, name: String): Int
    external fun nSetVectorMode(handle: Long, index: Int, on: Boolean): Boolean
    external fun nAppendVectorPath(handle: Long, index: Int, pathJson: String): Int
    external fun nVectorEraser(handle: Long, index: Int, on: Boolean, pathJson: String): Boolean
    external fun nUndoVector(handle: Long, index: Int): Boolean
    external fun nSetRulers(handle: Long, kindsJson: String): Boolean

    // M7 additions (ARCHITECTURE.md): frames + onion skin. fps stays
    // app-side (default 12, no engine symbol); timeline thumbnails and
    // playback pull nFrameCompositeInto per frame index.
    external fun nAddFrame(handle: Long): Int
    external fun nDuplicateFrame(handle: Long): Int
    external fun nDeleteFrame(handle: Long, index: Int): Boolean
    external fun nSetFrame(handle: Long, index: Int): Boolean
    external fun nFrameCount(handle: Long): Int
    external fun nFrameCompositeInto(handle: Long, index: Int, pixels: IntArray, width: Int, height: Int): Int
    external fun nSetOnion(handle: Long, previous: Boolean, next: Boolean, strength: Float): Boolean

    // M8 additions (ARCHITECTURE.md): adjustment layers — null/empty
    // json clears the descriptor.
    external fun nSetLayerAdjustment(handle: Long, index: Int, json: String?): Boolean

    // V+1 additions (ARCHITECTURE.md): text fiche (null/empty clears)
    // + PNG pixel commit into a layer (exact size match, undoable).
    external fun nSetLayerText(handle: Long, index: Int, json: String?): Boolean
    external fun nBlitPngIntoLayer(handle: Long, index: Int, path: String): Boolean

    // B2 additions (ARCHITECTURE.md "B2 additions"): atomic text commit
    // (pixels + fiche = ONE undo; index < 0 creates the layer) and
    // installable brush tips (RGBA PNG, alpha = mask; empty clears).
    external fun nCommitTextLayer(
        handle: Long, index: Int, path: String, json: String, name: String
    ): Boolean
    external fun nSetBrushTip(handle: Long, path: String?): Boolean

    // V+2 additions (ARCHITECTURE.md): cel rhythm + onion counts,
    // selection mask/transform, GIF export, generic fiche, PSD count.
    external fun nMoveCel(handle: Long, from: Int, to: Int): Boolean
    external fun nVectorPaths(handle: Long, index: Int): String
    external fun nSetCelHold(handle: Long, index: Int, frames: Int): Boolean
    external fun nSetOnionCount(handle: Long, before: Int, after: Int): Boolean
    external fun nSetSelectionMask(handle: Long, path: String?): Boolean
    external fun nSelectionBlit(handle: Long, json: String): Boolean
    external fun nExportGif(handle: Long, path: String, json: String): Boolean
    external fun nSetLayerMeta(handle: Long, index: Int, key: String, value: String?): Boolean
    external fun nPsdApproxCount(handle: Long): Int

    // M9 additions (ARCHITECTURE.md): glTF reference model overlay.
    external fun nLoadGlbReference(handle: Long, path: String): Int
    external fun nSetRefView(handle: Long, json: String): Boolean
    external fun nSetRefVisible(handle: Long, on: Boolean): Boolean

    // V+3 additions (ARCHITECTURE.md): pose rig — joints introspection
    // (M9 nVectorPaths pattern) + pose merge. Overlay-only, no pixels.
    external fun nRefJoints(handle: Long): String
    external fun nSetRefPose(handle: Long, json: String): Boolean

    // M10 additions (ARCHITECTURE.md): procedural material tones.
    external fun nApplyTone(handle: Long, name: String): Boolean

    // T1 additions (ARCHITECTURE.md): bucket fill + magic wand (shared
    // flood kernel), gradient paint, figure stamps. All undoable via
    // full-layer tile snapshots (filter pattern); false + layer
    // untouched on bad layer/params/seed.
    external fun nFloodFill(handle: Long, x: Int, y: Int, colorHex: String, json: String): Boolean
    external fun nMagicWand(handle: Long, x: Int, y: Int, json: String): Boolean
    external fun nPaintGradient(handle: Long, json: String): Boolean
    external fun nStampShape(handle: Long, json: String): Boolean

    // T3 additions (ARCHITECTURE.md): indexed vector ops (retrace,
    // connect, widths) + bubble fiche. Text runs/outline/fonts/dir/
    // hyphen ride the existing nSetLayerText JSON (0 new symbols).
    external fun nReplaceVectorPath(handle: Long, index: Int, id: Int, pathJson: String): Boolean
    external fun nConnectVectorPaths(handle: Long, index: Int, a: Int, b: Int, tolJson: String?): Boolean
    external fun nSetVectorWidths(handle: Long, index: Int, id: Int, widthsJson: String): Boolean
    external fun nSetLayerBubble(handle: Long, index: Int, json: String?): Boolean

    // T3 additions (ARCHITECTURE.md): images cles (2 symboles),
    // camera (3), multi-pistes (8), sequences + APNG (2).
    external fun nSetLayerAnim(handle: Long, index: Int, json: String?): Boolean
    external fun nLayerAnim(handle: Long, index: Int): String
    external fun nSetCameraTrack(handle: Long, json: String?): Boolean
    external fun nCameraTrack(handle: Long): String
    external fun nSetCameraEnabled(handle: Long, on: Boolean): Boolean
    external fun nTrackCount(handle: Long): Int
    external fun nAddTrack(handle: Long, name: String): Int
    external fun nSetActiveTrack(handle: Long, i: Int): Boolean
    external fun nActiveTrack(handle: Long): Int
    external fun nSetTrackVisible(handle: Long, i: Int, on: Boolean): Boolean
    external fun nSetTrackName(handle: Long, i: Int, name: String): Boolean
    external fun nDeleteTrack(handle: Long, i: Int): Boolean
    external fun nTrackState(handle: Long): String
    external fun nExportPngSeq(handle: Long, dirPath: String, json: String): Boolean
    external fun nExportApng(handle: Long, path: String, json: String): Boolean

    // T4 additions (ARCHITECTURE.md): puppet warp (whole raster layer),
    // decor scene + 3D paint. Perspective/skew/bulge ride the existing
    // nSelectionBlit JSON (0 new symbols); smart shape is app-side.
    external fun nPuppetWarp(handle: Long, index: Int, json: String): Boolean
    external fun nSetRefScene(handle: Long, json: String): Boolean
    external fun nPaintRefMap(handle: Long, item: Int, jsonDabs: String): Boolean

    // T5 additions (ARCHITECTURE.md): layer lock flags (meta hors undo;
    // false + intact on bad index/unparsable JSON) + fill fiche
    // (null/empty clears; {"bake":true} pixelizes through the
    // blit-into-layer machinery, 1 undo step).
    external fun nSetLayerFlags(handle: Long, index: Int, json: String): Boolean
    external fun nSetLayerFill(handle: Long, index: Int, json: String?): Boolean

    // W1 additions (ARCHITECTURE.md "W1 additions"): merge/flatten/
    // rasterize, canvas geometry (resize/crop) and selection refinement.
    // All three are ONE undo step per call (whole-document snapshot).
    external fun nMergeLayers(handle: Long, json: String): Boolean
    external fun nCanvasOp(handle: Long, json: String): Boolean
    external fun nSelectionOp(handle: Long, json: String, outPath: String): Boolean

    // B1 additions (ARCHITECTURE.md "B1 additions", issue #30 slice B):
    // clone source anchor (long press), pattern brush tile/spec and the
    // non-destructive layer texture fiche ({"bake":true} pixelizes).
    external fun nSetCloneSource(handle: Long, json: String): Boolean
    external fun nSetPatternBrush(handle: Long, json: String): Boolean
    external fun nSetLayerTexture(handle: Long, index: Int, json: String?): Boolean

    // Issue #32 §3 additions (ARCHITECTURE.md "Issue #32 additions"):
    // explicit memory policy. docBytes bounds the document surfaces
    // (layer add / vector add / mask add / child add / canvas resize
    // refuse politely past it), undoBytes caps every frame's History
    // (oldest-first eviction, never an OOM). nBudgetState returns the
    // live JSON accounting used by the settings panel and the tests.
    external fun nSetBudget(handle: Long, docBytes: Long, undoBytes: Long): Boolean
    external fun nBudgetState(handle: Long): String
}
