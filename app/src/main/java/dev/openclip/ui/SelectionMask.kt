// OpenClip V+1 — selection mask (app-side only, session-only).
//
// The mask is a 1-bit full-resolution buffer in ENGINE coordinates,
// stored per document (the owning EngineSurface is per-tab: pause()/
// resume() conserve it, dispose() releases it — exactly the §1.3
// lifecycle). No JNI symbol exists for selection (ARCHITECTURE.md
// "V+1 additions"): strokes split at the boundary, filters/tones mix
// under the mask, the overlay is composite-only.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

enum class SelectionTool { RECT, ELLIPSE, LASSO }

class SelectionMask(val w: Int, val h: Int) {
    private val bits = ByteArray(w * h)

    /** Marching-ants outline in engine coordinates (closed loop). */
    var outline: List<Pair<Float, Float>> = emptyList()
        private set

    fun isEmpty(): Boolean = bits.none { it != 0.toByte() }

    fun clear() {
        bits.fill(0)
        outline = emptyList()
    }

    fun selectAll() {
        bits.fill(1)
        outline = listOf(
            Pair(0f, 0f), Pair(w.toFloat(), 0f),
            Pair(w.toFloat(), h.toFloat()), Pair(0f, h.toFloat())
        )
    }

    fun setRect(x0: Float, y0: Float, x1: Float, y1: Float) {
        clear()
        val xa = x0.coerceIn(0f, w.toFloat()).toInt()
        val xb = x1.coerceIn(0f, w.toFloat()).toInt()
        val ya = y0.coerceIn(0f, h.toFloat()).toInt()
        val yb = y1.coerceIn(0f, h.toFloat()).toInt()
        val l = minOf(xa, xb)
        val r = maxOf(xa, xb)
        val t = minOf(ya, yb)
        val b = maxOf(ya, yb)
        for (y in t until b) {
            val base = y * w
            for (x in l until r) bits[base + x] = 1
        }
        outline = listOf(
            Pair(l.toFloat(), t.toFloat()), Pair(r.toFloat(), t.toFloat()),
            Pair(r.toFloat(), b.toFloat()), Pair(l.toFloat(), b.toFloat())
        )
    }

    fun setEllipse(x0: Float, y0: Float, x1: Float, y1: Float) {
        clear()
        val cx = (x0 + x1) / 2f
        val cy = (y0 + y1) / 2f
        val rx = kotlin.math.abs(x1 - x0) / 2f
        val ry = kotlin.math.abs(y1 - y0) / 2f
        if (rx < 0.5f || ry < 0.5f) return
        val l = (cx - rx).coerceIn(0f, w.toFloat()).toInt()
        val r = (cx + rx).coerceIn(0f, w.toFloat()).toInt()
        val t = (cy - ry).coerceIn(0f, h.toFloat()).toInt()
        val b = (cy + ry).coerceIn(0f, h.toFloat()).toInt()
        for (y in t until b) {
            for (x in l until r) {
                val nx = (x + 0.5f - cx) / rx
                val ny = (y + 0.5f - cy) / ry
                if (nx * nx + ny * ny <= 1f) bits[y * w + x] = 1
            }
        }
        // Polygon outline (32-gon) for the overlay.
        outline = (0 until 32).map { i ->
            val a = i * 2f * kotlin.math.PI.toFloat() / 32f
            Pair(cx + rx * kotlin.math.cos(a), cy + ry * kotlin.math.sin(a))
        }
    }

    /** Closed polygon fill (even-odd scanline) in engine coordinates. */
    fun setPolygon(pts: List<Pair<Float, Float>>) {
        clear()
        if (pts.size < 3) return
        outline = pts.toList()
        var minY = h - 1
        var maxY = 0
        for ((_, y) in pts) {
            minY = minOf(minY, y.toInt().coerceIn(0, h - 1))
            maxY = maxOf(maxY, y.toInt().coerceIn(0, h - 1))
        }
        for (y in minY..maxY) {
            val xs = mutableListOf<Float>()
            var j = pts.size - 1
            for (i in pts.indices) {
                val (xi, yi) = pts[i]
                val (xj, yj) = pts[j]
                if ((yi > y + 0.5f) != (yj > y + 0.5f)) {
                    xs.add(xi + (y + 0.5f - yi) / (yj - yi) * (xj - xi))
                }
                j = i
            }
            xs.sort()
            var k = 0
            while (k + 1 < xs.size) {
                val l = xs[k].toInt().coerceIn(0, w - 1)
                val r = xs[k + 1].toInt().coerceIn(0, w - 1)
                for (x in l..r) bits[y * w + x] = 1
                k += 2
            }
        }
    }

    fun invert() {
        for (i in bits.indices) bits[i] = if (bits[i] != 0.toByte()) 0 else 1
    }

    /** Feather +1px: dilate by one pixel (softens the edge outward). */
    fun featherPlus1() {
        val src = bits.copyOf()
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (src[y * w + x] != 0.toByte()) continue
                var near = false
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx in 0 until w && ny in 0 until h &&
                            src[ny * w + nx] != 0.toByte()
                        ) {
                            near = true
                            break
                        }
                    }
                    if (near) break
                }
                if (near) bits[y * w + x] = 1
            }
        }
    }

    /** V+2 tone density: ordered Bayer-4 dropout keeps keepPct % of
     * the masked pixels (spec §1.1: density app-side, no JNI). */
    fun applyDensityBayer(keepPct: Int) {
        if (keepPct >= 100) return
        if (keepPct <= 0) {
            clear()
            return
        }
        val bayer = arrayOf(
            intArrayOf(0, 8, 2, 10),
            intArrayOf(12, 4, 14, 6),
            intArrayOf(3, 11, 1, 9),
            intArrayOf(15, 7, 13, 5)
        )
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (bits[y * w + x] == 0.toByte()) continue
                val threshold = bayer[y and 3][x and 3] * 100 / 16
                if (threshold >= keepPct) bits[y * w + x] = 0
            }
        }
        outline = emptyList()
    }

    fun contains(x: Float, y: Float): Boolean {
        val xi = x.toInt()
        val yi = y.toInt()
        if (xi !in 0 until w || yi !in 0 until h) return false
        return bits[yi * w + xi] != 0.toByte()
    }

    /** FNV-1a hash (tab-switch survival probe). */
    fun hash(): Long {
        var acc = -3750763034362895579L
        for (b in bits) {
            acc = acc xor (b.toLong() and 0xFF)
            acc *= 1099511628211L
        }
        return acc
    }
}
