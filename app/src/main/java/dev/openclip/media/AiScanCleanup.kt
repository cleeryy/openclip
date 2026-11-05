// Calque — issue #17: app-side "scan cleanup" (document denoise) via
// ONNX Runtime and the bundled MIT model (assets/models/docden_bc16_fp32.onnx,
// jere1882/DocDenoising). The model is trained on scanned documents, so the
// caller labels it honestly and exposes a mix factor; this object never
// silently applies it at full strength.
//
// Contract (ARCHITECTURE.md "Issue #17 additions"):
//  - input [1,3,h,w] float32 in [0,1] with h,w multiples of 8, tiled at 512 px;
//  - bounded memory, cooperative cancellation, null + reason on failure;
//  - the caller writes the result back through nBlitPngIntoLayer so the whole
//    operation is one undo step.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.media

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object AiScanCleanup {
    private const val MODEL = "models/docden_bc16_fp32.onnx"
    private const val TILE = 512
    private const val ALIGN = 8
    private const val MAX_PIXELS = 4096L * 4096L

    /** Last failure reason (diagnostics for tests and the status line). */
    @Volatile
    var lastError: String? = null

    @Volatile
    private var env: OrtEnvironment? = null

    @Volatile
    private var session: OrtSession? = null

    private val lock = Any()

    private fun ensure(context: Context): OrtSession? {
        session?.let { return it }
        synchronized(lock) {
            session?.let { return it }
            return try {
                val e = OrtEnvironment.getEnvironment()
                val bytes = context.assets.open(MODEL).use { it.readBytes() }
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                }
                val s = e.createSession(bytes, opts)
                env = e
                session = s
                lastError = null
                s
            } catch (ex: Exception) {
                lastError = "session: ${ex::class.simpleName}: ${ex.message}"
                null
            }
        }
    }

    /**
     * Denoise [src] and blend with the original at [mix] (0..1).
     * Returns null on failure (see [lastError]) or cancellation.
     * [progress] receives monotonic 0..1 values.
     */
    fun cleanup(
        context: Context,
        src: Bitmap,
        mix: Float = 1f,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): Bitmap? {
        val s = ensure(context) ?: return null
        val e = env ?: return null
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) {
            lastError = "empty bitmap"
            return null
        }
        if (w.toLong() * h.toLong() > MAX_PIXELS) {
            lastError = "canvas too large for AI cleanup (${w}x$h)"
            return null
        }
        val factor = mix.coerceIn(0f, 1f)
        val srcPixels = IntArray(w * h)
        src.getPixels(srcPixels, 0, w, 0, 0, w, h)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val outPixels = IntArray(w * h)

        val tilesX = (w + TILE - 1) / TILE
        val tilesY = (h + TILE - 1) / TILE
        val total = tilesX * tilesY
        var done = 0

        try {
            for (ty in 0 until tilesY) {
                for (tx in 0 until tilesX) {
                    if (isCancelled()) {
                        lastError = "cancelled"
                        out.recycle()
                        return null
                    }
                    val x0 = tx * TILE
                    val y0 = ty * TILE
                    val tw = min(TILE, w - x0)
                    val th = min(TILE, h - y0)
                    // Pad to a multiple of 8 by edge replication, then crop.
                    val pw = ((tw + ALIGN - 1) / ALIGN) * ALIGN
                    val ph = ((th + ALIGN - 1) / ALIGN) * ALIGN
                    val plane = FloatArray(3 * pw * ph)
                    for (yy in 0 until ph) {
                        val sy = y0 + min(yy, th - 1)
                        for (xx in 0 until pw) {
                            val sx = x0 + min(xx, tw - 1)
                            val c = srcPixels[sy * w + sx]
                            val idx = yy * pw + xx
                            plane[idx] = ((c shr 16) and 0xFF) / 255f
                            plane[pw * ph + idx] = ((c shr 8) and 0xFF) / 255f
                            plane[2 * pw * ph + idx] = (c and 0xFF) / 255f
                        }
                    }
                    val tensor = OnnxTensor.createTensor(
                        e,
                        FloatBuffer.wrap(plane),
                        longArrayOf(1, 3, ph.toLong(), pw.toLong())
                    )
                    val denoised = tensor.use { input ->
                        s.run(mapOf(s.inputNames.first() to input)).use { res ->
                            // Shape [1,3,ph,pw].
                            val arr = res[0].value as Array<Array<Array<FloatArray>>>
                            arr[0]
                        }
                    }
                    for (yy in 0 until th) {
                        for (xx in 0 until tw) {
                            val idx = yy * pw + xx
                            val orig = srcPixels[(y0 + yy) * w + (x0 + xx)]
                            val oa = (orig ushr 24) and 0xFF
                            val r = blend(((orig shr 16) and 0xFF) / 255f, denoised[0][yy][xx], factor)
                            val g = blend(((orig shr 8) and 0xFF) / 255f, denoised[1][yy][xx], factor)
                            val b = blend((orig and 0xFF) / 255f, denoised[2][yy][xx], factor)
                            outPixels[(y0 + yy) * w + (x0 + xx)] =
                                (oa shl 24) or
                                    (clamp8(r) shl 16) or
                                    (clamp8(g) shl 8) or
                                    clamp8(b)
                        }
                    }
                    done++
                    progress(done.toFloat() / total)
                }
            }
            out.setPixels(outPixels, 0, w, 0, 0, w, h)
            lastError = null
            return out
        } catch (ex: Exception) {
            lastError = "run: ${ex::class.simpleName}: ${ex.message}"
            if (!out.isRecycled) out.recycle()
            return null
        }
    }

    /** Test/diagnostic hook: forget the loaded session. */
    fun reset() {
        synchronized(lock) {
            try {
                session?.close()
            } catch (_: Exception) {
            }
            session = null
            env = null
            lastError = null
        }
    }

    private fun blend(original: Float, model: Float, factor: Float): Float =
        original * (1f - factor) + model.coerceIn(0f, 1f) * factor

    private fun clamp8(v: Float): Int = max(0, min(255, (v * 255f).roundToInt()))
}
