// OpenClip V+3 — naive CMYK soft-proof (§1.2, app-side, 0 JNI) + text
// helpers live in their own files; this unit is the color math.
//
// Scope: PROOF ONLY (never destructive). The naive sRGB→CMYK round trip
// (K=1-max, from scratch, documented approximate) drives a paper-tinted
// preview bitmap plus an out-of-gamut overlay. Gamut flag heuristic:
// highly saturated bright pixels (S>0.75, V>0.5) — pure cyan flags,
// neutral gray never does. Help text says "indicative, not a printer
// contract" (rule V+1: no lure entries, no promises).
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

object CmykProof {
    /** Naive sRGB→CMYK in [0,1] (K=1-max). Approximate by design. */
    fun rgbToCmyk(r: Int, g: Int, b: Int): FloatArray {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val k = 1f - maxOf(rf, gf, bf)
        if (k >= 1f) return floatArrayOf(0f, 0f, 0f, 1f)
        val c = (1f - rf - k) / (1f - k)
        val m = (1f - gf - k) / (1f - k)
        val y = (1f - bf - k) / (1f - k)
        return floatArrayOf(c, m, y, k)
    }

    /** CMYK back to naive sRGB (proof simulation). */
    fun cmykToRgb(c: Float, m: Float, y: Float, k: Float): IntArray {
        fun ch(v: Float): Int =
            (255f * (1f - minOf(1f, v * (1f - k) + k)) + 0.5f)
                .toInt().coerceIn(0, 255)
        return intArrayOf(ch(c), ch(m), ch(y))
    }

    /** Out-of-gamut heuristic: saturated AND bright (pure cyan flags). */
    fun isOutOfGamut(r: Int, g: Int, b: Int): Boolean {
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        if (mx == 0) return false
        val s = (mx - mn).toFloat() / mx
        val v = mx / 255f
        return s > 0.75f && v > 0.5f
    }

    /**
     * Proof preview: simulate CMYK round trip, multiply toward paper
     * white, optionally tint out-of-gamut pixels magenta-ish overlay.
     * Pure function on ARGB ints — the caller's buffer is never mutated
     * (proofCmykOverlayOnly gate).
     */
    fun preview(
        argb: IntArray,
        paperWhite: Int = 0xFFF6EFDF.toInt(),
        gamutWarning: Boolean = false,
    ): IntArray {
        val pr = (paperWhite shr 16) and 0xFF
        val pg = (paperWhite shr 8) and 0xFF
        val pb = paperWhite and 0xFF
        return IntArray(argb.size) { i ->
            val px = argb[i]
            val a = (px ushr 24) and 0xFF
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            val (c, m, y, k) = rgbToCmyk(r, g, b).let {
                Quad(it[0], it[1], it[2], it[3])
            }
            val sim = cmykToRgb(c, m, y, k)
            var or = sim[0] * pr / 255
            var og = sim[1] * pg / 255
            var ob = sim[2] * pb / 255
            if (gamutWarning && isOutOfGamut(r, g, b)) {
                or = (or + 255) / 2
                og = og / 2
                ob = (ob + 255) / 2
            }
            (a shl 24) or (or shl 16) or (og shl 8) or ob
        }
    }

    private data class Quad(val a: Float, val b: Float, val c: Float, val d: Float)
}
