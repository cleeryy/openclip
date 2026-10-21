// OpenClip V+3 — text on path + text warp (§3, 0 JNI: existing bridges
// nBlitPngIntoLayer / nSelectionBlit / nSetLayerMeta only).
//
// (a) Layout is app-side on the platform Canvas: the vector polyline
// becomes an android.graphics.Path, drawTextOnPath commits glyphs with
// the system font (no native font stack — V+1 rule), hOffset = offset
// 0..1 along the path. Commit = PNG → nBlitPngIntoLayer (1 undo,
// V+1 "re-edit = replace" pattern).
// (b) Warp reuses the V+2 mesh path (nSelectionBlit mode mesh on the
// committed raster text): no vector-glyph deformation in V+3.
// Fiches flow through nSetLayerMeta: x-openclip-text-path (vector layer
// index) + x-openclip-text-offset (0..1).
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import java.io.File

const val TEXT_PATH_META = "x-openclip-text-path"
const val TEXT_OFFSET_META = "x-openclip-text-offset"

object TextOnPath {
    /** Polyline (engine coords) → platform Path. */
    fun toPlatformPath(pts: List<SnapPt>): Path {
        val p = Path()
        if (pts.isEmpty()) return p
        p.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) p.lineTo(pts[i].x, pts[i].y)
        return p
    }

    /**
     * Render [text] along [pathPts] onto a w×h bitmap (transparent
     * background). Returns null on empty input (caller refuses honnestly).
     */
    fun renderToBitmap(
        text: String,
        pathPts: List<SnapPt>,
        w: Int,
        h: Int,
        sizePx: Float,
        colorHex: String,
        offset: Float,
        bold: Boolean = false,
    ): Bitmap? {
        if (text.isEmpty() || pathPts.size < 2 || w <= 0 || h <= 0) return null
        if (sizePx < 1f) return null
        return try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            try {
                paint.color = android.graphics.Color.parseColor(colorHex)
            } catch (_: Exception) {
                paint.color = android.graphics.Color.parseColor("#141414")
            }
            paint.textSize = sizePx
            paint.isFakeBoldText = bold
            val path = toPlatformPath(pathPts)
            val hOff = try {
                offset.coerceIn(0f, 1f) *
                    android.graphics.PathMeasure(path, false).length
            } catch (_: Exception) {
                0f
            }
            cv.drawTextOnPath(text, path, hOff, 0f, paint)
            bmp
        } catch (_: Exception) {
            null
        }
    }

    /** Bitmap → PNG file for the nBlitPngIntoLayer bridge. */
    fun bitmapToPng(bmp: Bitmap, dst: File): Boolean = try {
        dst.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    } catch (_: Exception) {
        false
    }

    /** Meta fiches for the text-path link (via nSetLayerMeta). */
    fun metaFor(pathIndex: Int, offset: Float): Map<String, String> = mapOf(
        TEXT_PATH_META to pathIndex.toString(),
        TEXT_OFFSET_META to offset.coerceIn(0f, 1f).toString(),
    )

    fun parseMeta(meta: Map<String, String>): Pair<Int, Float>? = try {
        val p = meta[TEXT_PATH_META]?.toIntOrNull() ?: return null
        val o = meta[TEXT_OFFSET_META]?.toFloatOrNull() ?: 0f
        Pair(p, o.coerceIn(0f, 1f))
    } catch (_: Exception) {
        null
    }
}
