// OpenClip T4 — smart shape (ARCHITECTURE.md "T4 additions" §5):
// stroke straightening, 100% app-side. Pure Kotlin functions
// (from-scratch documented algorithms: arclength resampling, RDP,
// isoperimetric circularity, second-order moments) — no engine
// symbol on the nominal path. The recognizer classifies a captured
// stroke (segment|ellipse|circle|rect|triangle) with a confidence;
// below the tolerance the stroke is kept as-is (never a surprise
// replace). Replacement commits ride T1/T2 symbols.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Straightened-shape hypothesis in engine coords. */
data class ShapeFit(
    /** segment|ellipse|circle|rect|triangle */
    val kind: String,
    /** 0..1 confidence (caller compares to the tolerance). */
    val confidence: Float,
    /**
     * Fitted geometry: segment [a,b]; ellipse/circle
     * [center, axisX-end, axisY-end]; rect [4 corners];
     * triangle [3 corners].
     */
    val geom: List<Pair<Float, Float>>
)

object SmartShape {
    /** Default confidence gate (spec §5.1: 0.7). */
    const val DEFAULT_TOLERANCE = 0.7f

    /**
     * Classify a captured stroke (engine coords). Returns null when no
     * shape reaches `tolerance` — the stroke is kept as-is.
     * Pure function (emu-tested, prototype bound: 2048 pts < 300 ms).
     */
    fun classify(
        pts: List<Pair<Float, Float>>,
        tolerance: Float = DEFAULT_TOLERANCE
    ): ShapeFit? {
        if (pts.size < 8) return null
        val tol = tolerance.coerceIn(0.5f, 0.95f)
        val rs = resample(pts, 64)
        val len = pathLength(rs)
        if (len <= 1e-6f) return null
        val closed = hypot(
            rs.last().first - rs.first().first,
            rs.last().second - rs.first().second
        ) < len * 0.08f
        // Open stroke: segment test (RDP straightness).
        if (!closed) {
            val dev = maxChordDev(rs)
            val conf = (1f - dev / (len * 0.05f)).coerceIn(0f, 1f)
            if (conf >= tol) {
                return ShapeFit("segment", conf, listOf(rs.first(), rs.last()))
            }
            return null
        }
        // Closed: circularity first (circle/ellipse), else polygon.
        val area = abs(shoelace(rs))
        val circ = (4f * Math.PI.toFloat() * area / (len * len)).coerceIn(0f, 1f)
        if (circ > 0.82f) {
            val e = fitEllipse(rs)
            val ratio = if (e.third > 1e-6f) e.fourth / e.third else 0f
            return if (ratio > 0.85f) {
                val c = e.first
                val r = (e.third + e.fourth) / 2f
                val conf = circ.coerceIn(0f, 1f)
                if (conf < tol) return null
                ShapeFit(
                    "circle", conf,
                    listOf(c, Pair(c.first + r, c.second), Pair(c.first, c.second + r))
                )
            } else {
                val conf = (circ * (1f - abs(ratio - 0.55f))).coerceIn(0f, 1f)
                if (conf < tol) return null
                ShapeFit("ellipse", conf, ellipseGeom(e))
            }
        }
        // Polygon: RDP vertex count (3 = triangle, 4 = rect).
        val simp = rdp(rs, len * 0.015f)
        // RDP keeps first+last (same point when closed): distinct count.
        val distinct = simp.distinct()
        if (distinct.size == 3 || distinct.size == 4) {
            val kind = if (distinct.size == 3) "triangle" else "rect"
            // Confidence: mean distance of samples to the fitted edges.
            val edges = distinct + distinct.first()
            var dev = 0f
            for (p in rs) {
                var best = Float.MAX_VALUE
                for (i in 0 until distinct.size) {
                    best = minOf(best, distToSeg(p, edges[i], edges[i + 1]))
                }
                dev += best
            }
            dev /= rs.size
            val conf = (1f - dev / (len * 0.02f)).coerceIn(0f, 1f)
            if (conf < tol) return null
            return ShapeFit(kind, conf, distinct)
        }
        return null
    }

    /** Ghost polyline in engine coords (overlay-only, never pixels). */
    fun ghostPoly(fit: ShapeFit): List<Pair<Float, Float>> {
        return when (fit.kind) {
            "segment" -> fit.geom
            "circle" -> {
                val (c, ax, ay) = fit.geom
                val r = (hypot(ax.first - c.first, ax.second - c.second) +
                    hypot(ay.first - c.first, ay.second - c.second)) / 2f
                (0..48).map { i ->
                    val a = 2f * Math.PI.toFloat() * i / 48f
                    Pair(c.first + r * cos(a), c.second + r * sin(a))
                }
            }
            "ellipse" -> {
                val (c, ax, ay) = fit.geom
                val rx = hypot(ax.first - c.first, ax.second - c.second)
                val ry = hypot(ay.first - c.first, ay.second - c.second)
                val rot = atan2(ax.second - c.second, ax.first - c.first)
                (0..48).map { i ->
                    val a = 2f * Math.PI.toFloat() * i / 48f
                    val ex = rx * cos(a)
                    val ey = ry * sin(a)
                    Pair(
                        c.first + ex * cos(rot) - ey * sin(rot),
                        c.second + ex * sin(rot) + ey * cos(rot)
                    )
                }
            }
            else -> fit.geom + fit.geom.first()  // rect/triangle: close
        }
    }

    private fun pathLength(pts: List<Pair<Float, Float>>): Float {
        var l = 0f
        for (i in 1 until pts.size) {
            l += hypot(pts[i].first - pts[i - 1].first, pts[i].second - pts[i - 1].second)
        }
        return l
    }

    private fun resample(
        pts: List<Pair<Float, Float>>,
        n: Int
    ): List<Pair<Float, Float>> {
        val total = pathLength(pts)
        if (total <= 1e-6f) return List(n) { pts.first() }
        val out = mutableListOf<Pair<Float, Float>>()
        // acc = consumed distance up to (px,py); the next sample lands
        // at `target`. Emitting consumes exactly `target` (the leftover
        // segment stays ahead and is re-measured — never dropped).
        var acc = 0f
        var target = total / (n - 1)
        val step = total / (n - 1)
        out.add(pts.first())
        var px = pts[0].first
        var py = pts[0].second
        var i = 1
        var guard = 0
        while (out.size < n - 1 && i < pts.size && guard < pts.size * 8 + n * 8) {
            guard++
            val seg = hypot(pts[i].first - px, pts[i].second - py)
            if (seg <= 1e-9f) {
                i++
                continue
            }
            if (acc + seg >= target) {
                val t = (target - acc) / seg
                px += (pts[i].first - px) * t
                py += (pts[i].second - py) * t
                out.add(Pair(px, py))
                acc = target
                target += step
                continue
            }
            acc += seg
            px = pts[i].first
            py = pts[i].second
            i++
        }
        while (out.size < n) out.add(pts.last())
        return out
    }

    private fun maxChordDev(pts: List<Pair<Float, Float>>): Float {
        val a = pts.first()
        val b = pts.last()
        var m = 0f
        for (p in pts) m = maxOf(m, distToSeg(p, a, b))
        return m
    }

    private fun distToSeg(
        p: Pair<Float, Float>,
        a: Pair<Float, Float>,
        b: Pair<Float, Float>
    ): Float {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val l2 = dx * dx + dy * dy
        if (l2 <= 1e-9f) return hypot(p.first - a.first, p.second - a.second)
        var t = ((p.first - a.first) * dx + (p.second - a.second) * dy) / l2
        t = t.coerceIn(0f, 1f)
        return hypot(p.first - (a.first + t * dx), p.second - (a.second + t * dy))
    }

    private fun shoelace(pts: List<Pair<Float, Float>>): Float {
        var s = 0f
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            s += a.first * b.second - b.first * a.second
        }
        return s / 2f
    }

    /** Ramer-Douglas-Peucker (spec §5.1: RDP + side count). */
    private fun rdp(pts: List<Pair<Float, Float>>, eps: Float): List<Pair<Float, Float>> {
        if (pts.size <= 2) return pts
        val a = pts.first()
        val b = pts.last()
        var dmax = 0f
        var idx = 0
        for (i in 1 until pts.size - 1) {
            val d = distToSeg(pts[i], a, b)
            if (d > dmax) {
                dmax = d
                idx = i
            }
        }
        if (dmax > eps) {
            val left = rdp(pts.subList(0, idx + 1), eps)
            val right = rdp(pts.subList(idx, pts.size), eps)
            return left.dropLast(1) + right
        }
        return listOf(a, b)
    }

    /**
     * Ellipse by second-order moments: (center, angle, semiMajor,
     * semiMinor). Classical fit, documented.
     */
    private fun fitEllipse(
        pts: List<Pair<Float, Float>>
    ): Quadruple<Pair<Float, Float>, Float, Float, Float> {
        var mx = 0f
        var my = 0f
        for (p in pts) {
            mx += p.first
            my += p.second
        }
        mx /= pts.size
        my /= pts.size
        var sxx = 0f
        var syy = 0f
        var sxy = 0f
        for (p in pts) {
            val dx = p.first - mx
            val dy = p.second - my
            sxx += dx * dx
            syy += dy * dy
            sxy += dx * dy
        }
        sxx /= pts.size
        syy /= pts.size
        sxy /= pts.size
        // Eigen of [[sxx,sxy],[sxy,syy]]; uniform ring -> var = r²/2.
        val tr = sxx + syy
        val det = sxx * syy - sxy * sxy
        val disc = sqrt(maxOf(0f, tr * tr / 4f - det))
        val l1 = tr / 2f + disc
        val l2 = maxOf(0f, tr / 2f - disc)
        val ang = 0.5f * atan2(2f * sxy, sxx - syy)
        val major = sqrt(2f * l1)
        val minor = sqrt(2f * l2)
        return Quadruple(Pair(mx, my), ang, major, minor)
    }

    private fun ellipseGeom(
        e: Quadruple<Pair<Float, Float>, Float, Float, Float>
    ): List<Pair<Float, Float>> {
        val (c, ang, major, minor) = e
        return listOf(
            c,
            Pair(c.first + major * cos(ang), c.second + major * sin(ang)),
            Pair(c.first - minor * sin(ang), c.second + minor * cos(ang))
        )
    }

    private data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )
}
