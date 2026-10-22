// OpenClip T1 — fondations dessin côté app (0 JNI neuf ici).
//
// Ce fichier porte le code Kotlin pur de la tranche : miroir du noyau
// flood (baguette, overlay + pont V+1), flou séparable du contour
// progressif, et les constructeurs JSON des 4 ops moteur.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import java.util.Locale

/** Outils de peinture T1 (armés sur EngineSurface, usage unique). */
enum class PaintTool { BUCKET, GRADIENT, SHAPE, WAND }

/**
 * Miroir Kotlin du noyau flood moteur (fill.h walkRegion) : 4-connexité,
 * luma Rec.601, |lum-lum(seed)| <= tol*255. L'entrée est un composite
 * ARGB (straight, comme nCompositeInto) ; le moteur compare des octets
 * prémultipliés — identique sur contenu opaque (cas testé), écart
 * borné sur translucide (documenté, l'overlay seul est approximé, le
 * masque moteur reste l'autorité pour les ops).
 * Pile itérative (jamais de récursion), budget visites = W*H bits.
 */
fun floodRegionKotlin(
    pixels: IntArray,
    w: Int,
    h: Int,
    sx: Int,
    sy: Int,
    tol: Float
): BooleanArray {
    val inside = BooleanArray(w * h)
    if (w <= 0 || h <= 0 || sx !in 0 until w || sy !in 0 until h) return inside
    if (pixels.size < w * h) return inside
    fun lumAt(i: Int): Double {
        val px = pixels[i]
        val a = (px ushr 24) and 0xFF
        if (a == 0) return 0.0
        val r = ((px ushr 16) and 0xFF) * 255.0 / a
        val g = ((px ushr 8) and 0xFF) * 255.0 / a
        val b = (px and 0xFF) * 255.0 / a
        return 0.299 * r + 0.587 * g + 0.114 * b
    }
    val seed = lumAt(sy * w + sx)
    val tol255 = tol.coerceIn(0f, 1f) * 255.0
    val seen = BooleanArray(w * h)
    // Pile IntArray pré-allouée (pas de boxing : un flood 2048px tient
    // la sonde §4.4 ; chaque index n'est empilé qu'une fois max).
    val stack = IntArray(w * h)
    var top = 0
    stack[top++] = sy * w + sx
    seen[sy * w + sx] = true
    while (top > 0) {
        val at = stack[--top]
        val x = at % w
        val y = at / w
        if (kotlin.math.abs(lumAt(at) - seed) > tol255) continue
        inside[at] = true
        if (x > 0 && !seen[at - 1]) {
            seen[at - 1] = true
            stack[top++] = at - 1
        }
        if (x + 1 < w && !seen[at + 1]) {
            seen[at + 1] = true
            stack[top++] = at + 1
        }
        if (y > 0 && !seen[at - w]) {
            seen[at - w] = true
            stack[top++] = at - w
        }
        if (y + 1 < h && !seen[at + w]) {
            seen[at + w] = true
            stack[top++] = at + w
        }
    }
    return inside
}

/**
 * Flou gaussien séparable sur le plan alpha du masque (contour
 * progressif §5, rayon 0..32px, clamp-to-edge). Rayon 0 = copie
 * byte-identique (sortie précoce, verrouillée par test).
 */
fun featherBlurKotlin(src: ByteArray, w: Int, h: Int, radius: Float): ByteArray {
    if (radius <= 0f) return src.copyOf()
    if (w <= 0 || h <= 0 || src.size != w * h) return src.copyOf()
    val r = radius.coerceIn(0f, 32f)
    val sigma = (r / 2.0).coerceAtLeast(0.4)
    var half = kotlin.math.ceil(3.0 * sigma).toInt().coerceAtLeast(1)
    if (half > 128) half = 128
    val k = DoubleArray(half + 1)
    var sum = 0.0
    for (i in 0..half) {
        k[i] = kotlin.math.exp(-(i * i) / (2.0 * sigma * sigma))
        sum += if (i == 0) k[i] else 2.0 * k[i]
    }
    for (i in 0..half) k[i] /= sum
    fun at(x: Int, y: Int): Double {
        val cx = x.coerceIn(0, w - 1)
        val cy = y.coerceIn(0, h - 1)
        return (src[cy * w + cx].toInt() and 0xFF).toDouble()
    }
    val tmp = FloatArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            var acc = k[0] * at(x, y)
            for (i in 1..half) acc += k[i] * (at(x - i, y) + at(x + i, y))
            tmp[y * w + x] = acc.toFloat()
        }
    }
    val out = ByteArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            var acc = k[0] * tmp[y * w + x]
            for (i in 1..half) {
                val a = tmp[((y - i).coerceIn(0, h - 1)) * w + x]
                val b = tmp[((y + i).coerceIn(0, h - 1)) * w + x]
                acc += k[i] * (a + b)
            }
            out[y * w + x] = acc.toInt().coerceIn(0, 255).toByte()
        }
    }
    return out
}

/** Modes de cumul baguette (miroir des combine moteur 0/1/2). */
fun combineMaskKotlin(dst: ByteArray, src: BooleanArray, mode: Int) {
    require(dst.size == src.size)
    for (i in dst.indices) {
        when (mode) {
            0 -> dst[i] = if (src[i]) 255.toByte() else 0
            1 -> if (src[i]) dst[i] = 255.toByte()
            else -> if (src[i]) dst[i] = 0
        }
    }
}

/** Construit le JSON nFloodFill (clés M3 : inconnues ignorées moteur). */
fun bucketJson(tolerance: Float, antialias: Boolean, expandPx: Int, sampleMerged: Boolean): String =
    String.format(
        Locale.US,
        """{"tolerance":%.3f,"antialias":%s,"expandPx":%d,"sampleMerged":%s}""",
        tolerance.coerceIn(0f, 1f),
        if (antialias) "true" else "false",
        expandPx.coerceIn(0, 2),
        if (sampleMerged) "true" else "false"
    )

/** Construit le JSON nMagicWand. */
fun wandJson(tolerance: Float, source: String, combine: String): String =
    String.format(
        Locale.US,
        """{"tolerance":%.3f,"source":"%s","combine":"%s"}""",
        tolerance.coerceIn(0f, 1f),
        if (source == "merged") "merged" else "layer",
        when (combine) {
            "add" -> "add"
            "sub" -> "sub"
            else -> "set"
        }
    )

private fun hexOf(color: String): String {
    val c = color.trim()
    if (c.length == 7 && c[0] == '#') return c.uppercase()
    return "#000000"
}

private fun stopsJson(stops: List<Pair<String, Float>>): String {
    val body = stops.joinToString(",") { (c, p) ->
        "\"${hexOf(c)}:${String.format(Locale.US, "%.4f", p.coerceIn(0f, 1f))}\""
    }
    return "[$body]"
}

/** Construit le JSON nPaintGradient (stops = encodage gradientMap). */
fun gradientJson(
    kind: String,
    x0: Float, y0: Float, x1: Float, y1: Float,
    stops: List<Pair<String, Float>>,
    pts: List<Pair<Float, Float>> = emptyList(),
    dither: Boolean = true
): String {
    val k = when (kind) {
        "radial" -> "radial"
        "free" -> "free"
        else -> "linear"
    }
    val ptsJson = if (k == "free") {
        ",\"pts\":[" + pts.joinToString(",") { (x, y) ->
            String.format(Locale.US, "[%.2f,%.2f]", x, y)
        } + "]"
    } else ""
    return String.format(
        Locale.US,
        """{"kind":"%s","x0":%.2f,"y0":%.2f,"x1":%.2f,"y1":%.2f,"stops":%s%s,"dither":%s}""",
        k, x0, y0, x1, y1, stopsJson(stops), ptsJson,
        if (dither) "true" else "false"
    )
}

/** Construit le JSON nStampShape. */
fun shapeJson(
    kind: String,
    x0: Float, y0: Float, x1: Float, y1: Float,
    sides: Int = 4,
    rotation: Float = 0f,
    corner: Float = 0f,
    fillOn: Boolean = true,
    fill: String = "#000000",
    strokeOn: Boolean = false,
    stroke: String = "#000000",
    strokeW: Float = 1f
): String {
    val k = when (kind) {
        "ellipse" -> "ellipse"
        "polygon" -> "polygon"
        else -> "rect"
    }
    return String.format(
        Locale.US,
        """{"kind":"%s","x0":%.2f,"y0":%.2f,"x1":%.2f,"y1":%.2f,"sides":%d,"rotation":%.2f,"corner":%.2f,"fillOn":%s,"fill":"%s","strokeOn":%s,"stroke":"%s","strokeW":%.2f}""",
        k, x0, y0, x1, y1, sides.coerceIn(3, 12), rotation,
        corner.coerceAtLeast(0f),
        if (fillOn) "true" else "false", hexOf(fill),
        if (strokeOn) "true" else "false", hexOf(stroke), strokeW
    )
}
