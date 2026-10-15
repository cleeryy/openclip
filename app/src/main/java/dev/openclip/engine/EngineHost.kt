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
}
