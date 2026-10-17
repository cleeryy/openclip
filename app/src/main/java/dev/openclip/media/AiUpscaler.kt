// OpenClip M8 — app-side AI super-resolution via ONNX Runtime (MIT AAR)
// and the bundled ESPCN x3 model (assets/models/super-resolution-10.onnx,
// Apache-2.0, https://huggingface.co/onnxmodelzoo/super-resolution-10).
// The model upsamples the Y (luma) plane x3; Cb/Cr are bicubic-upscaled
// app-side and merged back. Failure returns null, never throws — the
// caller surfaces a status string (ARCHITECTURE.md "M8 additions").
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.media

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

object AiUpscaler {
    /** Last failure reason (diagnostics for tests + the status line). */
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
                val bytes = context.assets
                    .open("models/super-resolution-10.onnx")
                    .use { it.readBytes() }
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

    /** x3 super-resolution. Returns null on any failure. */
    fun upscale(context: Context, src: Bitmap): Bitmap? {
        val s = ensure(context) ?: return null
        val e = env ?: return null
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return null
        val n = w * h
        val y = FloatArray(n)
        val cb = FloatArray(n)
        val cr = FloatArray(n)
        val px = IntArray(n)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in 0 until n) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val luma = 0.299f * r + 0.587f * g + 0.114f * b
            y[i] = luma / 255f
            cb[i] = (0.5643f * (b - luma)) / 255f
            cr[i] = (0.7133f * (r - luma)) / 255f
        }
        try {
            // The bundled model accepts a FIXED 224x224 input (not
            // dynamic, despite the card): the luma plane is bicubic-
            // resized to it and the x3 output is resized back to exactly
            // (3w,3h). Large canvases degrade gracefully (M8 note).
            val modelIn = 224
            val lumIn = resample(y, w, h, modelIn, modelIn)
            val input = OnnxTensor.createTensor(
                e,
                FloatBuffer.wrap(lumIn),
                longArrayOf(1, 1, modelIn.toLong(), modelIn.toLong())
            )
            val outLumaRows = input.use { tensor ->
                s.run(mapOf(s.inputNames.first() to tensor)).use { res ->
                    val arr = res[0].value as Array<Array<Array<FloatArray>>>
                    arr[0][0]  // (3*modelIn) rows of FloatArray
                }
            }
            val oh2 = outLumaRows.size
            val ow2 = if (oh2 > 0) outLumaRows[0].size else 0
            if (oh2 <= 0 || ow2 <= 0) return null
            val lumFlat = FloatArray(oh2 * ow2)
            for (yy in 0 until oh2) {
                val r = outLumaRows[yy]
                for (xx in 0 until ow2) lumFlat[yy * ow2 + xx] = r[xx]
            }
            val ow = w * 3
            val oh = h * 3
            val lum = resample(lumFlat, ow2, oh2, ow, oh)
            val upCb = resample(cb, w, h, ow, oh)
            val upCr = resample(cr, w, h, ow, oh)
            val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            val row = IntArray(ow)
            for (yy in 0 until oh) {
                for (xx in 0 until ow) {
                    val l = lum[yy * ow + xx].toDouble().coerceIn(0.0, 1.0)
                    val cbc = upCb[yy * ow + xx].toDouble()
                    val crc = upCr[yy * ow + xx].toDouble()
                    var r = l + 1.402 * crc
                    var g = l - 0.344136 * cbc - 0.714136 * crc
                    var b = l + 1.772 * cbc
                    r = r.coerceIn(0.0, 1.0)
                    g = g.coerceIn(0.0, 1.0)
                    b = b.coerceIn(0.0, 1.0)
                    row[xx] = (0xFF shl 24) or
                        ((r * 255).toInt() and 0xFF shl 16) or
                        ((g * 255).toInt() and 0xFF shl 8) or
                        ((b * 255).toInt() and 0xFF)
                }
                out.setPixels(row, 0, ow, 0, yy, ow, 1)
            }
            return out
        } catch (ex: Exception) {
            lastError = "run: ${ex::class.simpleName}: ${ex.message}"
            return null
        }
    }

    // Bicubic (Catmull-Rom) resample of a single plane. src dims (sw,sh),
    // dst dims (dw,dh); returns FloatArray(dw*dh), row-major.
    private fun resample(
        src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int
    ): FloatArray {
        val out = FloatArray(dw * dh)
        val sx = dw / sw.toDouble()
        val sy = dh / sh.toDouble()
        fun cubic(p0: Float, p1: Float, p2: Float, p3: Float, t: Double): Float {
            val a = -0.5 * p0 + 1.5 * p1 - 1.5 * p2 + 0.5 * p3
            val b = p0 - 2.5 * p1 + 2.0 * p2 - 0.5 * p3
            val c = -0.5 * p0 + 0.5 * p2
            val d = p1
            val v = (a * t + b) * t * t + c * t + d
            return v.toFloat()
        }
        fun at(x: Int, y: Int): Float {
            val cx = x.coerceIn(0, sw - 1)
            val cy = y.coerceIn(0, sh - 1)
            return src[cy * sw + cx]
        }
        for (yy in 0 until dh) {
            val fy = (yy + 0.5) / sy - 0.5
            val y0 = fy.toInt().coerceIn(0, sh - 1)
            val ty = fy - y0
            for (xx in 0 until dw) {
                val fx = (xx + 0.5) / sx - 0.5
                val x0 = fx.toInt().coerceIn(0, sw - 1)
                val tx = fx - x0
                val xm1 = x0 - 1; val x2 = x0 + 2
                val ym1 = y0 - 1; val y2 = y0 + 2
                val c0 = cubic(at(xm1, ym1), at(x0, ym1), at(x0 + 1, ym1), at(x2, ym1), tx)
                val c1 = cubic(at(xm1, y0), at(x0, y0), at(x0 + 1, y0), at(x2, y0), tx)
                val c2 = cubic(at(xm1, y0 + 1), at(x0, y0 + 1), at(x0 + 1, y0 + 1), at(x2, y0 + 1), tx)
                val c3 = cubic(at(xm1, y2), at(x0, y2), at(x0 + 1, y2), at(x2, y2), tx)
                val v = cubic(c0, c1, c2, c3, ty)
                out[yy * dw + xx] = max(-0.5f, min(0.5f, v))
            }
        }
        return out
    }
}
