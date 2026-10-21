// OpenClip V+3 — open-format import baselines (§6, 0 new JNI).
//
// TIFF rides the existing nLoadDocument bridge (engine baseline §6.1).
// PDF first page rasterizes via the platform PdfRenderer (150 dpi) then
// flows through the PNG bridge (export PDF deferred, spec §6.2).
// SVG imports the <path> subset (M/L/C/Q/Z absolute+relative,
// stroke-width, stroke) into nAppendVectorPath calls: curves are sampled
// from scratch (quadratic/cubic at ~8px steps, V+2 densification rule);
// text/gradients/filters/<image> are ignored with a visible counter.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt

object PdfImport {
    /** Page count of a PDF file (0 on failure — honest refusal). */
    fun pageCount(pdf: File): Int {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            renderer.pageCount
        } catch (_: Exception) {
            0
        } finally {
            try {
                renderer?.close()
            } catch (_: Exception) {
            }
            try {
                pfd?.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Render page 0 at [dpi] into a bitmap (white background). Null on
     * failure. Page 1 only — the counter stays honest (spec §6.2).
     */
    fun renderFirstPage(pdf: File, dpi: Int = 150): Bitmap? {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        return try {
            pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            if (renderer.pageCount < 1) return null
            page = renderer.openPage(0)
            val scale = dpi / 72f
            val w = (page.width * scale).toInt().coerceIn(1, 4096)
            val h = (page.height * scale).toInt().coerceIn(1, 4096)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        } catch (_: Exception) {
            null
        } finally {
            try {
                page?.close()
            } catch (_: Exception) {
            }
            try {
                renderer?.close()
            } catch (_: Exception) {
            }
            try {
                pfd?.close()
            } catch (_: Exception) {
            }
        }
    }
}

data class SvgStroke(val points: List<SnapPt>, val widthPx: Float)

data class SvgParseResult(
    val paths: List<SvgStroke>,
    val ignoredText: Int,
    val ignoredOther: Int,
)

object SvgImport {
    private val pathTag = Regex("<path\\b([^>]*)/?>", RegexOption.IGNORE_CASE)
    private val attrD = Regex("\\bd\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val attrStrokeW = Regex("\\bstroke-width\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val attrStyleW = Regex("stroke-width\\s*:\\s*([0-9.]+)")
    private val textTag = Regex("<text\\b", RegexOption.IGNORE_CASE)
    private val gradTag = Regex("<(linearGradient|radialGradient|filter|image)\\b", RegexOption.IGNORE_CASE)

    fun parse(svg: String): SvgParseResult {
        var ignoredText = textTag.findAll(svg).count()
        var ignoredOther = gradTag.findAll(svg).count()
        val paths = mutableListOf<SvgStroke>()
        for (m in pathTag.findAll(svg)) {
            val attrs = m.groupValues[1]
            val d = attrD.find(attrs)?.groupValues?.get(1) ?: continue
            val pts = parsePathData(d)
            if (pts.size < 2) continue
            val w = attrStrokeW.find(attrs)?.groupValues?.get(1)?.toFloatOrNull()
                ?: attrStyleW.find(attrs)?.groupValues?.get(1)?.toFloatOrNull()
                ?: 2f
            paths.add(SvgStroke(pts, w.coerceIn(0.5f, 256f)))
        }
        return SvgParseResult(paths, ignoredText, ignoredOther)
    }

    /** Minimal path-data parser: M/L/C/Q/Z + H/V, absolute+relative. */
    fun parsePathData(d: String): List<SnapPt> {
        val out = mutableListOf<SnapPt>()
        val tok = Regex("[MmLlCcQqZzHhVv]|-?[0-9]*\\.?[0-9]+(?:[eE][-+]?[0-9]+)?")
            .findAll(d).map { it.value }.toList()
        var i = 0
        var cmd = ' '
        var cx = 0f
        var cy = 0f
        var sx = 0f
        var sy = 0f
        fun num(): Float {
            if (i >= tok.size) return 0f
            return tok[i++].toFloatOrNull() ?: 0f
        }
        while (i < tok.size) {
            val t = tok[i]
            if (t.length == 1 && t[0].isLetter()) {
                cmd = t[0]
                i++
                if (cmd == 'Z' || cmd == 'z') {
                    cx = sx
                    cy = sy
                    if (out.isNotEmpty()) out.add(SnapPt(cx, cy))
                }
                continue
            }
            when (cmd) {
                'M', 'm' -> {
                    var x = num()
                    var y = num()
                    if (cmd == 'm') {
                        x += cx
                        y += cy
                    }
                    cx = x
                    cy = y
                    sx = x
                    sy = y
                    out.add(SnapPt(cx, cy))
                    cmd = if (cmd == 'M') 'L' else 'l'
                }
                'L', 'l' -> {
                    var x = num()
                    var y = num()
                    if (cmd == 'l') {
                        x += cx
                        y += cy
                    }
                    cx = x
                    cy = y
                    out.add(SnapPt(cx, cy))
                }
                'H', 'h' -> {
                    var x = num()
                    if (cmd == 'h') x += cx
                    cx = x
                    out.add(SnapPt(cx, cy))
                }
                'V', 'v' -> {
                    var y = num()
                    if (cmd == 'v') y += cy
                    cy = y
                    out.add(SnapPt(cx, cy))
                }
                'C', 'c' -> {
                    var x1 = num()
                    var y1 = num()
                    var x2 = num()
                    var y2 = num()
                    var x = num()
                    var y = num()
                    if (cmd == 'c') {
                        x1 += cx
                        y1 += cy
                        x2 += cx
                        y2 += cy
                        x += cx
                        y += cy
                    }
                    sampleCubic(out, cx, cy, x1, y1, x2, y2, x, y)
                    cx = x
                    cy = y
                }
                'Q', 'q' -> {
                    var x1 = num()
                    var y1 = num()
                    var x = num()
                    var y = num()
                    if (cmd == 'q') {
                        x1 += cx
                        y1 += cy
                        x += cx
                        y += cy
                    }
                    sampleQuad(out, cx, cy, x1, y1, x, y)
                    cx = x
                    cy = y
                }
                else -> i++ // unknown command: skip one token, stay honest
            }
        }
        return out
    }

    private fun sampleCubic(
        out: MutableList<SnapPt>,
        x0: Float, y0: Float, x1: Float, y1: Float,
        x2: Float, y2: Float, x3: Float, y3: Float,
    ) {
        val approx =
            sqrt((x3 - x0) * (x3 - x0) + (y3 - y0) * (y3 - y0)) +
                sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0)) +
                sqrt((x2 - x3) * (x2 - x3) + (y2 - y3) * (y2 - y3))
        val n = ceil(approx / 8.0).toInt().coerceIn(2, 256)
        for (k in 1..n) {
            val t = k / n.toFloat()
            val u = 1f - t
            out.add(
                SnapPt(
                    u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3,
                    u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3,
                )
            )
        }
    }

    private fun sampleQuad(
        out: MutableList<SnapPt>,
        x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float,
    ) {
        val approx = sqrt((x2 - x0) * (x2 - x0) + (y2 - y0) * (y2 - y0)) +
            sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
        val n = ceil(approx / 8.0).toInt().coerceIn(2, 256)
        for (k in 1..n) {
            val t = k / n.toFloat()
            val u = 1f - t
            out.add(
                SnapPt(
                    u * u * x0 + 2 * u * t * x1 + t * t * x2,
                    u * u * y0 + 2 * u * t * y1 + t * t * y2,
                )
            )
        }
    }
}
