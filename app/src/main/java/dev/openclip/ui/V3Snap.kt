// OpenClip V+3 — stroke precision: RDP simplification + snap settings
// (§2, app-side + nSetRulers JSON keys; 0 new symbols).
//
// (a) Ramer-Douglas-Peucker from scratch (~20 lines): applied to the
// polyline BEFORE nAppendVectorPath (vector) or before raster
// stamp-subdivision (the caller keeps the V+2 densification after).
// (b) Snap settings carried in the nSetRulers payload next to guides
// (snapTol px, grid 0|8|16|32, snapPaths bool), persisted per document
// in snap-<docKey>.json (same cacheDir pattern as guides).
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import java.io.File
import kotlin.math.abs

data class SnapPt(val x: Float, val y: Float, val p: Float = 1f)

object Rdp {
    /** Simplify a polyline; epsilon in px (spec: 0.5–2 adjustable). */
    fun simplify(pts: List<SnapPt>, eps: Float): List<SnapPt> {
        if (pts.size < 3 || eps <= 0f) return pts.toList()
        val keep = BooleanArray(pts.size)
        keep[0] = true
        keep[pts.size - 1] = true
        fun dist(i: Int, a: SnapPt, b: SnapPt): Float {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len2 = dx * dx + dy * dy
            if (len2 == 0f) {
                val ex = pts[i].x - a.x
                val ey = pts[i].y - a.y
                return kotlin.math.sqrt(ex * ex + ey * ey)
            }
            var t = ((pts[i].x - a.x) * dx + (pts[i].y - a.y) * dy) / len2
            t = t.coerceIn(0f, 1f)
            val ex = pts[i].x - (a.x + t * dx)
            val ey = pts[i].y - (a.y + t * dy)
            return kotlin.math.sqrt(ex * ex + ey * ey)
        }
        fun rec(first: Int, last: Int) {
            var dMax = 0f
            var idx = -1
            val a = pts[first]
            val b = pts[last]
            for (i in first + 1 until last) {
                val d = dist(i, a, b)
                if (d > dMax) {
                    dMax = d
                    idx = i
                }
            }
            if (dMax > eps && idx >= 0) {
                keep[idx] = true
                rec(first, idx)
                rec(idx, last)
            }
        }
        rec(0, pts.size - 1)
        return pts.filterIndexed { i, _ -> keep[i] }
    }
}

data class SnapSettings(
    val snapTol: Float = 2f,
    val grid: Int = 0,
    val snapPaths: Boolean = false,
    // V+3 RDP epsilon (px) for path simplification (BezierPanel).
    // Persisted with the snap settings; never sent to the engine.
    val simplifyEps: Float = 1f,
) {
    fun toJsonFragment(): String =
        "\"snapTol\":$snapTol,\"grid\":$grid,\"snapPaths\":$snapPaths"

    companion object {
        fun file(cacheDir: File, docKey: String): File =
            File(cacheDir, "snap-$docKey.json")

        fun load(cacheDir: File, docKey: String): SnapSettings {
            try {
                val f = file(cacheDir, docKey)
                if (!f.exists()) return SnapSettings()
                val t = f.readText()
                fun num(key: String, fb: Float): Float =
                    Regex("\"$key\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)")
                        .find(t)?.groupValues?.get(1)?.toFloatOrNull() ?: fb
                val grid = num("grid", 0f).toInt().let {
                    if (it == 8 || it == 16 || it == 32) it else 0
                }
                return SnapSettings(
                    snapTol = num("snapTol", 2f).coerceIn(0f, 64f),
                    grid = grid,
                    snapPaths = t.contains("\"snapPaths\":true"),
                    simplifyEps = num("simplifyEps", 1f).coerceIn(0f, 8f),
                )
            } catch (_: Exception) {
                return SnapSettings()
            }
        }

        fun save(cacheDir: File, docKey: String, s: SnapSettings): Boolean =
            try {
                file(cacheDir, docKey).writeText(
                    "{\"snapTol\":${s.snapTol},\"grid\":${s.grid}," +
                        "\"snapPaths\":${s.snapPaths}," +
                        "\"simplifyEps\":${s.simplifyEps}}"
                )
                true
            } catch (_: Exception) {
                false
            }
    }
}
