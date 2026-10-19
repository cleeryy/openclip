// OpenClip slice 3 — BrushPrefs: hoisted brush size/opacity/stabilize
// store. ToolPalette and the pinned bottom-canvas bar read/write the same
// instance (a single pushBrush path, never two concurrent setBrush calls).
// App-side only: hand-rolled JSON in filesDir (same pattern as ColorStore),
// debounced 500 ms writes + explicit flush on onPause. Missing or corrupt
// files fall back silently to 10px / 100% / 15%.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.Locale

const val BRUSH_PREFS_FILE = "brush-defaults.json"
const val BRUSH_FALLBACK_RADIUS = 10f
const val BRUSH_FALLBACK_OPAQUE = 1f
const val BRUSH_FALLBACK_STABILIZE = 0.15f

class BrushPrefs(appContext: Context) {
    private val file: File = File(appContext.applicationContext.filesDir, BRUSH_PREFS_FILE)

    var radius by mutableStateOf(BRUSH_FALLBACK_RADIUS)
        private set
    var opaque by mutableStateOf(BRUSH_FALLBACK_OPAQUE)
        private set
    var stabilize by mutableStateOf(BRUSH_FALLBACK_STABILIZE)
        private set

    // Live tool mirrors (not persisted): ToolPalette syncs these on every
    // push so the pinned bar can rebuild the identical brush JSON through
    // the single hoisted store (never two concurrent setBrush callers).
    var toolColor: String = "#141414"
        private set
    var toolEraser: Boolean = false
        private set
    var toolCurve: Float = 0f
        private set

    fun bindTool(color: String, eraser: Boolean, curve: Float) {
        toolColor = color
        toolEraser = eraser
        toolCurve = curve.coerceIn(0f, 1f)
    }

    fun currentJson(): String = toBrushJson(toolColor, !toolEraser, toolCurve)

    private val handler = Handler(Looper.getMainLooper())
    private var savePending = false
    private val saveRun = Runnable {
        savePending = false
        writeFile()
    }

    fun updateRadius(v: Float) {
        radius = v.coerceIn(1f, 128f)
        schedule()
    }

    fun updateOpaque(v: Float) {
        opaque = v.coerceIn(0f, 1f)
        schedule()
    }

    fun updateStabilize(v: Float) {
        stabilize = v.coerceIn(0f, 1f)
        schedule()
    }

    fun toBrushJson(color: String, isBrush: Boolean, curve: Float): String = String.format(
        Locale.US,
        """{"radius":%.2f,"hardness":%.2f,"opaque":%.2f,"spacing":0.15,"color":"%s","stabilize":%.2f,"curve":%.2f,"mode":"%s"}""",
        radius,
        if (isBrush) 0.7f else 0.9f,
        opaque,
        color,
        stabilize,
        curve.coerceIn(0f, 1f),
        if (isBrush) "brush" else "eraser"
    )

    /** Debounced write (500 ms) + explicit flush on onPause. */
    fun flush() {
        handler.removeCallbacks(saveRun)
        if (savePending) {
            savePending = false
            writeFile()
        }
    }

    private fun schedule() {
        if (!savePending) {
            savePending = true
            handler.postDelayed(saveRun, 500)
        }
    }

    private fun writeFile() {
        try {
            file.writeText(
                String.format(
                    Locale.US,
                    """{"radius":%.2f,"opaque":%.3f,"stabilize":%.3f}""",
                    radius, opaque, stabilize
                )
            )
        } catch (_: Exception) {
        }
    }

    private val numRe = Regex("\"(radius|opaque|stabilize)\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)")

    private fun readFile() {
        try {
            if (!file.exists()) return
            val t = file.readText()
            val m = numRe.findAll(t).associate { it.groupValues[1] to it.groupValues[2].toFloat() }
            if (m.isEmpty()) throw IllegalArgumentException("shape")
            radius = (m["radius"] ?: BRUSH_FALLBACK_RADIUS).coerceIn(1f, 128f)
            opaque = (m["opaque"] ?: BRUSH_FALLBACK_OPAQUE).coerceIn(0f, 1f)
            stabilize = (m["stabilize"] ?: BRUSH_FALLBACK_STABILIZE).coerceIn(0f, 1f)
        } catch (_: Exception) {
            radius = BRUSH_FALLBACK_RADIUS
            opaque = BRUSH_FALLBACK_OPAQUE
            stabilize = BRUSH_FALLBACK_STABILIZE
        }
    }

    init {
        readFile()
    }
}
