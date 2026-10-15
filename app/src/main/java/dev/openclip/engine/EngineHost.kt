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
}
