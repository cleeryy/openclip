// OpenClip slice 5 — BrushPrefs: hoisted brush store. ToolPalette, the
// pinned bottom-canvas bar and the pressure-curve editor write through
// this single instance (a single pushBrush path, never two concurrent
// setBrush calls). App-side only: hand-rolled JSON in filesDir (same
// pattern as ColorStore), debounced 500 ms writes + explicit flush on
// onPause. Missing or corrupt files fall back silently.
// Slice 5 additions: tool PINCEAU/GOMME (persisted), separate eraser
// size/opacity memory (eRadius/eOpaque), curve scalar + 4 curve points
// (curve/curvePts). The curve graph maps to the existing engine "curve"
// field (exponent 1+3*curve) by least-squares fit — no new JNI.
// The raster eraser stays a light-paint hack at engine level (mode
// "eraser" paints toward canvas background); TODO(alpha-erase): a real
// dest-out erase needs ARCHITECTURE.md first.
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
import kotlin.math.pow

const val BRUSH_PREFS_FILE = "brush-defaults.json"
const val BRUSH_FALLBACK_RADIUS = 10f
const val BRUSH_FALLBACK_OPAQUE = 1f
const val BRUSH_FALLBACK_STABILIZE = 0.15f
const val BRUSH_FALLBACK_ERASER_RADIUS = 48f
const val BRUSH_FALLBACK_ERASER_OPAQUE = 1f
const val BRUSH_FALLBACK_CURVE = 0f
val BRUSH_FALLBACK_CURVE_PTS = floatArrayOf(0f, 1f / 3f, 2f / 3f, 1f)

/**
 * Least-squares fit of the 4-point user curve onto the engine scalar:
 * picks curve in [0,1] minimizing Σ (t^(1+3*curve) − user(t))² over 16
 * samples. App-side, ~µs (101 × 16 pow calls).
 */
fun approxCurveForPts(pts: FloatArray): Float {
    fun userAt(t: Float): Float {
        val x = floatArrayOf(0f, 1f / 3f, 2f / 3f, 1f)
        if (t <= x[1]) {
            val f = (t - x[0]) / (x[1] - x[0])
            return pts[0] + f * (pts[1] - pts[0])
        }
        if (t <= x[2]) {
            val f = (t - x[1]) / (x[2] - x[1])
            return pts[1] + f * (pts[2] - pts[1])
        }
        val f = ((t - x[2]) / (x[3] - x[2])).coerceIn(0f, 1f)
        return pts[2] + f * (pts[3] - pts[2])
    }
    var best = 0f
    var bestErr = Float.MAX_VALUE
    for (i in 0..100) {
        val c = i / 100f
        val e = 1f + 3f * c
        var err = 0f
        for (k in 0 until 16) {
            val t = k / 15f
            val d = t.toDouble().pow(e.toDouble()).toFloat() - userAt(t)
            err += d * d
        }
        if (err < bestErr) {
            bestErr = err
            best = c
        }
    }
    return best
}

/** Scalar curve back to 4 display points (pow sampling, exact round trip). */
fun curveScalarToPts(curve: Float): FloatArray {
    val e = 1.0 + 3.0 * curve.coerceIn(0f, 1f)
    fun p(t: Double) = t.pow(e).toFloat()
    return floatArrayOf(0f, p(1.0 / 3.0), p(2.0 / 3.0), 1f)
}

class BrushPrefs(appContext: Context) {
    private val file: File = File(appContext.applicationContext.filesDir, BRUSH_PREFS_FILE)

    var radius by mutableStateOf(BRUSH_FALLBACK_RADIUS)
        private set
    var opaque by mutableStateOf(BRUSH_FALLBACK_OPAQUE)
        private set
    var stabilize by mutableStateOf(BRUSH_FALLBACK_STABILIZE)
        private set

    // Slice 5: dedicated eraser memory (changing tool never breaks the
    // brush settings) + persisted tool + persisted pressure curve.
    var eraserRadius by mutableStateOf(BRUSH_FALLBACK_ERASER_RADIUS)
        private set
    var eraserOpaque by mutableStateOf(BRUSH_FALLBACK_ERASER_OPAQUE)
        private set
    var isEraser by mutableStateOf(false)
        private set
    var curve by mutableStateOf(BRUSH_FALLBACK_CURVE)
        private set
    var curvePts by mutableStateOf(BRUSH_FALLBACK_CURVE_PTS.copyOf())
        private set

    // V+3 mixer knife (§1.1): mix amount 0..1 (0 = plain brush) +
    // pickup-window factor 0.5..2.0. Persisted like radius/opacity,
    // pushed through the same single toBrushJson path.
    var mix by mutableStateOf(0f)
        private set
    var mixRadius by mutableStateOf(1f)
        private set

    // B1 (ARCHITECTURE.md "B1 additions" §2): real retouch tool mode
    // (brush|smudge|dodge|burn|clone|pattern) + smudge strength. The mode
    // rides the single toBrushJson path so radius/opacity pushes never
    // reset it; pattern/clone parameters are session state (ToolPalette).
    var retouchMode by mutableStateOf("brush")
        private set
    var smudgeStrength by mutableStateOf(0.5f)
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

    // V+1: one-shot eraser migration note (the raster eraser now
    // retrenches alpha instead of painting light). Persisted next to
    // the tool so a reinstall re-announces; missing key = show once.
    var eraserAlphaNoteShown by mutableStateOf(false)
        private set

    fun markEraserAlphaNoteShown() {
        eraserAlphaNoteShown = true
        schedule()
    }

    // Slice 7: true when the file was missing-shaped/corrupt at read
    // (factory fallbacks kept). The host posts prefs_reset_hint once.
    var readFallback = false
        private set

    fun bindTool(color: String, eraser: Boolean, curve: Float) {
        toolColor = color
        toolEraser = eraser
        toolCurve = curve.coerceIn(0f, 1f)
    }

    fun chooseRetouchMode(mode: String) {
        retouchMode = when (mode) {
            "smudge", "dodge", "burn", "clone", "pattern" -> mode
            else -> "brush"
        }
        schedule()
    }

    fun updateSmudgeStrength(v: Float) {
        smudgeStrength = v.coerceIn(0f, 1f)
        schedule()
    }

    fun activeRadius(): Float = if (isEraser) eraserRadius else radius
    fun activeOpaque(): Float = if (isEraser) eraserOpaque else opaque

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

    fun updateEraserRadius(v: Float) {
        eraserRadius = v.coerceIn(1f, 128f)
        schedule()
    }

    fun updateEraserOpaque(v: Float) {
        eraserOpaque = v.coerceIn(0f, 1f)
        schedule()
    }

    fun setTool(eraser: Boolean) {
        isEraser = eraser
        toolEraser = eraser
        schedule()
    }

    /** Drag on a graph handle: clamp, refit the scalar, persist. */
    fun updateCurvePt(index: Int, v: Float) {
        if (index !in 0..3) return
        val next = curvePts.copyOf()
        next[index] = v.coerceIn(0f, 1f)
        curvePts = next
        curve = approxCurveForPts(next)
        toolCurve = curve
        schedule()
    }

    /** Whole-graph replace (presets): refit the scalar, persist. */
    fun updateCurvePts(pts: FloatArray) {
        if (pts.size != 4) return
        val next = FloatArray(4) { pts[it].coerceIn(0f, 1f) }
        curvePts = next
        curve = approxCurveForPts(next)
        toolCurve = curve
        schedule()
    }

    /** Fallback slider: scalar set, display points follow the pow curve. */
    fun updateCurveScalar(v: Float) {
        curve = v.coerceIn(0f, 1f)
        curvePts = curveScalarToPts(curve)
        toolCurve = curve
        schedule()
    }

    /** V+3 knife amount (0 = plain brush, 1 = pure knife). */
    fun updateMix(v: Float) {
        mix = v.coerceIn(0f, 1f)
        schedule()
    }

    /** V+3 pickup-window factor (× dab radius). */
    fun updateMixRadius(v: Float) {
        mixRadius = v.coerceIn(0.5f, 2.0f)
        schedule()
    }

    fun toBrushJson(color: String, isBrush: Boolean, curve: Float): String = String.format(
        Locale.US,
        """{"radius":%.2f,"hardness":%.2f,"opaque":%.2f,"spacing":0.15,"color":"%s","stabilize":%.2f,"curve":%.2f,"mode":"%s","mix":%.2f,"mixRadius":%.2f,"smudgeStrength":%.2f}""",
        if (isBrush) radius else eraserRadius,
        if (isBrush) 0.7f else 0.9f,
        if (isBrush) opaque else eraserOpaque,
        color,
        stabilize,
        curve.coerceIn(0f, 1f),
        // V+1: the raster eraser is a real dest-out erase
        // ("alpha-erase"); the legacy "eraser" white-paint alias stays
        // frozen engine-side for old presets only. B1: the brush mode
        // (smudge/dodge/burn/clone/pattern) replaces "brush" while the
        // eraser keeps its dest-out mode.
        if (!isBrush) "alpha-erase" else retouchMode,
        // V+3 mixer knife (§1.1): 0 = plain brush. Eraser strokes ignore
        // mix engine-side (dest-out has no ink to blend).
        if (isBrush) mix else 0f,
        if (isBrush) mixRadius else 1f,
        smudgeStrength
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
                    """{"radius":%.2f,"opaque":%.3f,"stabilize":%.3f,"curve":%.3f,"curvePts":[%.3f,%.3f,%.3f,%.3f],"eRadius":%.2f,"eOpaque":%.3f,"tool":"%s","eraserNote":%s,"mix":%.3f,"mixRadius":%.3f,"retouch":"%s","smudge":%.3f}""",
                    radius, opaque, stabilize, curve,
                    curvePts[0], curvePts[1], curvePts[2], curvePts[3],
                    eraserRadius, eraserOpaque,
                    if (isEraser) "eraser" else "brush",
                    if (eraserAlphaNoteShown) "true" else "false",
                    mix, mixRadius, retouchMode, smudgeStrength
                )
            )
        } catch (_: Exception) {
        }
    }

    private val numRe = Regex("\"(radius|opaque|stabilize|curve|eRadius|eOpaque|mix|mixRadius|smudge)\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)")
    private val ptsRe = Regex("\"curvePts\"\\s*:\\s*\\[([^\\]]*)\\]")
    private val toolRe = Regex("\"tool\"\\s*:\\s*\"([^\"]*)\"")
    private val retouchRe = Regex("\"retouch\"\\s*:\\s*\"([^\"]*)\"")
    private val noteRe = Regex("\"eraserNote\"\\s*:\\s*(true|false)")

    private fun readFile() {
        try {
            if (!file.exists()) return
            val t = file.readText()
            val m = numRe.findAll(t).associate { it.groupValues[1] to it.groupValues[2].toFloat() }
            if (m.isEmpty()) throw IllegalArgumentException("shape")
            radius = (m["radius"] ?: BRUSH_FALLBACK_RADIUS).coerceIn(1f, 128f)
            opaque = (m["opaque"] ?: BRUSH_FALLBACK_OPAQUE).coerceIn(0f, 1f)
            stabilize = (m["stabilize"] ?: BRUSH_FALLBACK_STABILIZE).coerceIn(0f, 1f)
            curve = (m["curve"] ?: BRUSH_FALLBACK_CURVE).coerceIn(0f, 1f)
            eraserRadius = (m["eRadius"] ?: BRUSH_FALLBACK_ERASER_RADIUS).coerceIn(1f, 128f)
            eraserOpaque = (m["eOpaque"] ?: BRUSH_FALLBACK_ERASER_OPAQUE).coerceIn(0f, 1f)
            mix = (m["mix"] ?: 0f).coerceIn(0f, 1f)
            mixRadius = (m["mixRadius"] ?: 1f).coerceIn(0.5f, 2.0f)
            smudgeStrength = (m["smudge"] ?: 0.5f).coerceIn(0f, 1f)
            retouchMode = when (val rm = retouchRe.find(t)?.groupValues?.get(1)) {
                "smudge", "dodge", "burn", "clone", "pattern" -> rm
                else -> "brush"
            }
            ptsRe.find(t)?.groupValues?.get(1)?.split(",")?.mapNotNull {
                it.trim().toFloatOrNull()?.coerceIn(0f, 1f)
            }?.takeIf { it.size == 4 }?.let { curvePts = it.toFloatArray() }
            isEraser = toolRe.find(t)?.groupValues?.get(1) == "eraser"
            toolEraser = isEraser
            toolCurve = curve
            eraserAlphaNoteShown = noteRe.find(t)?.groupValues?.get(1) == "true"
        } catch (_: Exception) {
            readFallback = true
            radius = BRUSH_FALLBACK_RADIUS
            opaque = BRUSH_FALLBACK_OPAQUE
            stabilize = BRUSH_FALLBACK_STABILIZE
            eraserRadius = BRUSH_FALLBACK_ERASER_RADIUS
            eraserOpaque = BRUSH_FALLBACK_ERASER_OPAQUE
            mix = 0f
            mixRadius = 1f
            isEraser = false
            toolEraser = false
            curve = BRUSH_FALLBACK_CURVE
            curvePts = BRUSH_FALLBACK_CURVE_PTS.copyOf()
            toolCurve = curve
        }
    }

    init {
        readFile()
    }
}
