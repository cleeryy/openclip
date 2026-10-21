// OpenClip V+3 — color harmonies (§1.3, ARCHITECTURE.md "V+3 additions").
//
// Pure hue math on the current ink color (0 JNI): complementary,
// analogous, triadic and square guides drawn as an overlay on the HSV
// wheel; tap-to-adopt writes inkHex through the unchanged single source.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import kotlin.math.abs

object Harmony {
    val MODES = listOf("complement", "analogous", "triadic", "square")

    /** Hue offsets (degrees) for each mode, base hue included first. */
    fun offsetsFor(mode: String): List<Float> = when (mode) {
        "complement" -> listOf(0f, 180f)
        "analogous" -> listOf(0f, -30f, 30f)
        "triadic" -> listOf(0f, 120f, 240f)
        "square" -> listOf(0f, 90f, 180f, 270f)
        else -> listOf(0f)
    }

    /** Full harmony hues for a base hue in [0,360). */
    fun huesFor(baseDeg: Float, mode: String): List<Float> {
        val b = ((baseDeg % 360f) + 360f) % 360f
        return offsetsFor(mode).map { ((b + it) % 360f + 360f) % 360f }
    }

    fun hexToHsv(hex: String): FloatArray {
        val c = hex.removePrefix("#")
        val r = c.substring(0, 2).toInt(16) / 255f
        val g = c.substring(2, 4).toInt(16) / 255f
        val b = c.substring(4, 6).toInt(16) / 255f
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        val s = if (mx == 0f) 0f else d / mx
        return floatArrayOf(((h % 360f) + 360f) % 360f, s, mx)
    }

    fun hsvToHex(hDeg: Float, s: Float, v: Float): String {
        val h = ((hDeg % 360f) + 360f) % 360f
        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun q(f: Float): String {
            val i = ((f + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
            return i.toString(16).padStart(2, '0').uppercase()
        }
        return "#${q(r)}${q(g)}${q(b)}"
    }

    /** Adopt: move the base hue to `targetDeg`, keep S/V of the ink. */
    fun adopt(inkHex: String, targetDeg: Float): String {
        return try {
            val (_, s, v) = hexToHsv(inkHex).let { Triple(it[0], it[1], it[2]) }
            hsvToHex(targetDeg, s, v)
        } catch (_: Exception) {
            inkHex
        }
    }
}
