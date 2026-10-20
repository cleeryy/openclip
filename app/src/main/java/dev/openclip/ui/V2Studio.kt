// OpenClip V+2 — BD/manga + long-export app-side logic (0 JNI neuf).
//
// Pure Kotlin (no Compose): panel/bubble/speed fiches (nSetLayerMeta
// channel), speed-line generator, Catmull-Rom densification (Bézier
// app-side), SVG serialization, webtoon planning/concat, multipage
// project.json, guides persistence, navigator math, curves JSON,
// session clipboard, selection-transform JSON, autosave policy.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import android.graphics.Bitmap
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ---- Panel / bubble / speed fiches (spec §1.4, nSetLayerMeta keys) ----

data class PanelFiche(
    val shape: String, // "rect" | "poly"
    val rect: FloatArray? = null, // x,y,w,h for rect
    val poly: List<Pair<Float, Float>> = emptyList(), // for poly
    val order: Int = 0,
    val bgWhite: Boolean = false,
    val tone: String = "none",
    val density: Int = 0 // 0..100 app-side dither threshold
)

fun PanelFiche.toMeta(): Map<String, String> {
    val shapeStr = if (shape == "poly") {
        "poly:" + poly.joinToString(";") { (x, y) ->
            String.format(Locale.US, "%.1f,%.1f", x, y)
        }
    } else {
        val r = rect ?: floatArrayOf(0f, 0f, 0f, 0f)
        String.format(
            Locale.US, "rect:%.1f,%.1f,%.1f,%.1f",
            r[0], r[1], r[2], r[3]
        )
    }
    return mapOf(
        "x-openclip-panel" to shapeStr,
        "x-openclip-panel-order" to order.toString(),
        "x-openclip-panel-bg" to if (bgWhite) "white" else "none",
        "x-openclip-tone" to tone,
        "x-openclip-tone-density" to density.coerceIn(0, 100).toString()
    )
}

fun parsePanelMeta(meta: Map<String, String>): PanelFiche? {
    val raw = meta["x-openclip-panel"] ?: return null
    val order = meta["x-openclip-panel-order"]?.toIntOrNull() ?: 0
    val bg = meta["x-openclip-panel-bg"] == "white"
    val tone = meta["x-openclip-tone"] ?: "none"
    val density = meta["x-openclip-tone-density"]?.toIntOrNull() ?: 0
    return if (raw.startsWith("rect:")) {
        val p = raw.removePrefix("rect:").split(",").mapNotNull { it.toFloatOrNull() }
        if (p.size != 4) return null
        PanelFiche("rect", floatArrayOf(p[0], p[1], p[2], p[3]), order = order, bgWhite = bg, tone = tone, density = density)
    } else if (raw.startsWith("poly:")) {
        val pts = raw.removePrefix("poly:").split(";").mapNotNull { s ->
            val q = s.split(",").mapNotNull { it.toFloatOrNull() }
            if (q.size == 2) Pair(q[0], q[1]) else null
        }
        if (pts.size < 3) return null
        PanelFiche("poly", poly = pts, order = order, bgWhite = bg, tone = tone, density = density)
    } else {
        null
    }
}

data class BubbleFiche(
    val shape: String, // "ellipse" | "rounded"
    val frame: FloatArray, // x,y,w,h drag frame
    val tailBase: Pair<Float, Float>,
    val tailTip: Pair<Float, Float>,
    val textLayer: Int = -1
)

fun BubbleFiche.toMeta(): Map<String, String> {
    val f = frame
    val frameStr = String.format(Locale.US, "%.1f,%.1f,%.1f,%.1f", f[0], f[1], f[2], f[3])
    val tailStr = String.format(
        Locale.US, "%.1f,%.1f,%.1f,%.1f",
        tailBase.first, tailBase.second, tailTip.first, tailTip.second
    )
    return mapOf(
        "x-openclip-bubble" to shape,
        "x-openclip-bubble-frame" to frameStr,
        "x-openclip-bubble-tail" to tailStr,
        "x-openclip-bubble-text" to textLayer.toString()
    )
}

fun parseBubbleMeta(meta: Map<String, String>): BubbleFiche? {
    val shape = meta["x-openclip-bubble"] ?: return null
    if (shape != "ellipse" && shape != "rounded") return null
    val f = (meta["x-openclip-bubble-frame"] ?: return null)
        .split(",").mapNotNull { it.toFloatOrNull() }
    if (f.size != 4) return null
    val t = (meta["x-openclip-bubble-tail"] ?: return null)
        .split(",").mapNotNull { it.toFloatOrNull() }
    if (t.size != 4) return null
    return BubbleFiche(
        shape, floatArrayOf(f[0], f[1], f[2], f[3]),
        Pair(t[0], t[1]), Pair(t[2], t[3]),
        meta["x-openclip-bubble-text"]?.toIntOrNull() ?: -1
    )
}

data class SpeedFiche(
    val fx: Float, val fy: Float,
    val angleDeg: Float, val len: Float,
    val count: Int, val seed: Long
)

fun SpeedFiche.toMeta(): String = String.format(
    Locale.US, "%.1f,%.1f,%.1f,%.1f,%d,%d",
    fx, fy, angleDeg, len, count, seed
)

fun parseSpeedMeta(raw: String?): SpeedFiche? {
    if (raw == null) return null
    val p = raw.split(",")
    if (p.size != 6) return null
    val v = p.mapNotNull { it.toFloatOrNull() }
    if (v.size != 6) return null
    return SpeedFiche(v[0], v[1], v[2], v[3], v[4].toInt(), v[5].toLong())
}

// ---- Speed-line generator (spec §1.1, pur trigonométrique) ----

object SpeedBurst {
    /**
     * N segments from a vanishing point: each segment starts on a ray
     * (angleDeg + jitter) at radius len*0.35 and extends outward len.
     * Seeded LCG (reproducible, regenerable from the fiche).
     */
    fun generate(f: SpeedFiche): List<List<Triple<Float, Float, Float>>> {
        var s = f.seed
        fun rnd(): Float {
            s = (s * 6364136223846793005L + 1442695040888963407L)
            return ((s ushr 33) and 0xFFFFFF).toFloat() / 0xFFFFFF.toFloat()
        }
        val out = mutableListOf<List<Triple<Float, Float, Float>>>()
        val n = f.count.coerceIn(1, 256)
        for (i in 0 until n) {
            val a = (f.angleDeg + (rnd() - 0.5f) * 60f) * Math.PI.toFloat() / 180f
            val jitter = 0.85f + rnd() * 0.3f
            val r0 = f.len * 0.35f * (0.7f + rnd() * 0.6f)
            val rl = f.len * jitter
            val x0 = f.fx + cos(a) * r0
            val y0 = f.fy + sin(a) * r0
            val x1 = f.fx + cos(a) * (r0 + rl)
            val y1 = f.fy + sin(a) * (r0 + rl)
            out.add(
                listOf(
                    Triple(x0, y0, 1f),
                    Triple((x0 + x1) / 2, (y0 + y1) / 2, 0.9f),
                    Triple(x1, y1, 0.7f)
                )
            )
        }
        return out
    }

    /** Vector-path JSON (engine parseVectorPathJson schema). */
    fun pathJson(pts: List<Triple<Float, Float, Float>>): String {
        val sb = StringBuilder("{\"pts\":[")
        pts.forEachIndexed { i, (x, y, p) ->
            if (i > 0) sb.append(',')
            sb.append(
                String.format(Locale.US, "{\"x\":%.1f,\"y\":%.1f,\"p\":%.2f}", x, y, p)
            )
        }
        return sb.append("]}").toString()
    }
}

// ---- Bézier app-side (spec §4.3): Catmull-Rom densification ----

object BezierEdit {
    /** Insert N interpolated points along a Catmull-Rom spline. */
    fun densify(
        pts: List<Pair<Float, Float>>,
        tension: Float = 0.5f
    ): List<Pair<Float, Float>> {
        if (pts.size < 2) return pts
        val out = mutableListOf<Pair<Float, Float>>()
        for (i in 0 until pts.size - 1) {
            val p0 = pts.getOrElse(i - 1) { pts[i] }
            val p1 = pts[i]
            val p2 = pts[i + 1]
            val p3 = pts.getOrElse(i + 2) { pts[i + 1] }
            out.add(p1)
            // 3 inserted points per span (handle drag = more spans).
            for (k in 1..3) {
                val t = k / 4f
                val t2 = t * t
                val t3 = t2 * t
                fun cr(a: Float, b: Float, c: Float, d: Float): Float =
                    0.5f * (2f * b + (-a + c) * t +
                        (2f * a - 5f * b + 4f * c - d) * t2 +
                        (-a + 3f * b - 3f * c + d) * t3) * tension +
                        (1f - tension) * (b + (c - b) * t)
                out.add(Pair(cr(p0.first, p1.first, p2.first, p3.first),
                    cr(p0.second, p1.second, p2.second, p3.second)))
            }
        }
        out.add(pts.last())
        return out
    }
}

// ---- SVG export (spec §3.3, app-side pur) ----

object SvgExport {
    fun serialize(
        w: Int, h: Int,
        paths: List<Pair<List<Triple<Float, Float, Float>>, Float>>
    ): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append(
            String.format(
                Locale.US,
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\">\n",
                w, h, w, h
            )
        )
        for ((pts, width) in paths) {
            if (pts.isEmpty()) continue
            sb.append("<path d=\"M")
            pts.forEachIndexed { i, (x, y, _) ->
                if (i > 0) sb.append('L')
                sb.append(String.format(Locale.US, "%.1f %.1f", x, y))
            }
            // Stroke width = mean profile (pressure-varying widths are
            // approximated — documented honest limit, spec §3.3).
            sb.append(
                String.format(
                    Locale.US,
                    "\" fill=\"none\" stroke=\"#000000\" stroke-width=\"%.1f\"/>\n",
                    width
                )
            )
        }
        sb.append("</svg>\n")
        return sb.toString()
    }
}

// ---- Webtoon (spec §3.1): planning + concat ----

object WebtoonPlan {
    data class Slice(val srcY: Int, val h: Int)

    /** Split totalH into slices of maxH (no overlap, exact cover). */
    fun slices(totalH: Int, maxH: Int): List<Slice> {
        if (totalH <= 0 || maxH <= 0) return emptyList()
        val out = mutableListOf<Slice>()
        var y = 0
        while (y < totalH) {
            val h = minOf(maxH, totalH - y)
            out.add(Slice(y, h))
            y += h
        }
        return out
    }

    /** Vertical concat (all bitmaps same width; gutter px, bg color). */
    fun concat(bitmaps: List<Bitmap>, gutter: Int, bg: Int): Bitmap {
        val w = bitmaps.maxOf { it.width }
        val totalH = bitmaps.sumOf { it.height } + gutter * (bitmaps.size - 1).coerceAtLeast(0)
        val out = Bitmap.createBitmap(w, totalH, Bitmap.Config.ARGB_8888)
        out.eraseColor(bg)
        var y = 0
        val px = IntArray(w * 1024)
        for (b in bitmaps) {
            var yy = 0
            while (yy < b.height) {
                val take = minOf(1024, b.height - yy)
                b.getPixels(px, 0, b.width, 0, yy, b.width, take)
                // Left-aligned (page widths are uniform in practice).
                out.setPixels(px, 0, b.width, 0, y + yy, b.width, take)
                yy += take
            }
            y += b.height + gutter
        }
        return out
    }
}

// ---- Multipage maison (spec §2.2): dossier .ocpages/ ----

data class OcpPage(
    val file: String, // "001.ora"
    val title: String,
    val width: Int,
    val height: Int
)

object MultipageProject {
    const val VERSION = 1

    fun toJson(reading: String, pages: List<OcpPage>): String {
        val sb = StringBuilder("{\"version\":1,\"reading\":")
        sb.append('"').append(if (reading == "rtl") "rtl" else "ltr").append('"')
        sb.append(",\"pages\":[")
        pages.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.append("{\"file\":").append('"').append(p.file).append('"')
            sb.append(",\"title\":").append('"')
            sb.append(p.title.replace("\"", "")).append('"')
            sb.append(",\"width\":").append(p.width)
            sb.append(",\"height\":").append(p.height).append('}')
        }
        return sb.append("]}").toString()
    }

    fun parse(json: String): Pair<String, List<OcpPage>> {
        val reading = if (json.contains("\"rtl\"")) "rtl" else "ltr"
        val pages = mutableListOf<OcpPage>()
        // Minimal tolerant scan of {"file":..,"title":..,"width":..,"height":..}.
        var p = 0
        while (true) {
            val f = json.indexOf("\"file\"", p)
            if (f < 0) break
            fun str(key: String, from: Int): String {
                val k = json.indexOf("\"$key\"", from)
                if (k < 0) return ""
                val q1 = json.indexOf('"', k + key.length + 2)
                if (q1 < 0) return ""
                val q2 = json.indexOf('"', q1 + 1)
                return if (q2 < 0) "" else json.substring(q1 + 1, q2)
            }
            fun num(key: String, from: Int): Int {
                val k = json.indexOf("\"$key\"", from)
                if (k < 0) return 0
                val c = json.indexOf(':', k)
                if (c < 0) return 0
                var e = c + 1
                while (e < json.length && (json[e].isDigit() || json[e] == '-')) e++
                return json.substring(c + 1, e).toIntOrNull() ?: 0
            }
            val file = str("file", f)
            val title = str("title", f)
            val w = num("width", f)
            val h = num("height", f)
            if (file.isNotEmpty()) pages.add(OcpPage(file, title, w, h))
            p = f + 6
        }
        return Pair(reading, pages)
    }

    fun pageFile(n: Int): String = String.format(Locale.US, "%03d.ora", n)

    fun insertAfter(pages: MutableList<OcpPage>, index: Int): OcpPage {
        // New blank page after index; filenames stay dense (001.ora…).
        val np = OcpPage("", "Page ${pages.size + 1}", 0, 0)
        pages.add(index + 1, np)
        renumber(pages)
        return pages[index + 1]
    }

    fun deleteAt(pages: MutableList<OcpPage>, index: Int): Boolean {
        if (pages.size <= 1) return false // garde: jamais 0 pages
        if (index !in pages.indices) return false
        pages.removeAt(index)
        renumber(pages)
        return true
    }

    fun move(pages: MutableList<OcpPage>, from: Int, to: Int): Boolean {
        if (from !in pages.indices || to !in pages.indices) return false
        if (from == to) return true
        val e = pages.removeAt(from)
        pages.add(to, e)
        renumber(pages)
        return true
    }

    private fun renumber(pages: MutableList<OcpPage>) {
        for (i in pages.indices) {
            val p = pages[i]
            pages[i] = p.copy(file = pageFile(i + 1))
        }
    }
}

// ---- Guides persistants (spec §4.4): JSON + fichier ----

sealed class DocGuide {
    data class V(val x: Float) : DocGuide()
    data class H(val y: Float) : DocGuide()
    data class C(val cx: Float, val cy: Float, val r: Float) : DocGuide()
}

object GuidesIo {
    fun toJson(guides: List<DocGuide>): String {
        val sb = StringBuilder("{\"guides\":[")
        guides.forEachIndexed { i, g ->
            if (i > 0) sb.append(',')
            when (g) {
                is DocGuide.V -> sb.append(
                    String.format(Locale.US, "{\"x\":%.1f}", g.x)
                )
                is DocGuide.H -> sb.append(
                    String.format(Locale.US, "{\"y\":%.1f}", g.y)
                )
                is DocGuide.C -> sb.append(
                    String.format(
                        Locale.US, "{\"cx\":%.1f,\"cy\":%.1f,\"r\":%.1f}",
                        g.cx, g.cy, g.r
                    )
                )
            }
        }
        return sb.append("]}").toString()
    }

    /** Same grammar as the nSetRulers "guides" key (engine-tested snap). */
    fun parse(json: String): List<DocGuide> {
        val out = mutableListOf<DocGuide>()
        var p = 0
        repeat(256) {
            val ob = json.indexOf('{', p)
            if (ob < 0) return out
            val cb = json.indexOf('}', ob + 1)
            if (cb < 0) return out
            val o = json.substring(ob, cb + 1)
            fun num(key: String): Float? {
                val k = o.indexOf("\"$key\"")
                if (k < 0) return null
                val c = o.indexOf(':', k)
                if (c < 0) return null
                var e = c + 1
                while (e < o.length && (o[e].isDigit() || o[e] == '-' || o[e] == '.' || o[e] == 'e' || o[e] == 'E' || o[e] == '+')) e++
                return o.substring(c + 1, e).toFloatOrNull()
            }
            val x = num("x")
            val y = num("y")
            val cx = num("cx")
            val cy = num("cy")
            val r = num("r")
            if (x != null && y == null && cx == null) {
                out.add(DocGuide.V(x))
            } else if (y != null && x == null && cx == null) {
                out.add(DocGuide.H(y))
            } else if (cx != null && cy != null && r != null && r > 0) {
                out.add(DocGuide.C(cx, cy, r))
            }
            p = cb + 1
        }
        return out
    }

    fun save(dir: File, guides: List<DocGuide>) {
        try {
            File(dir, "guides.json").writeText(toJson(guides))
        } catch (_: Exception) {
        }
    }

    fun load(dir: File): List<DocGuide> = try {
        val f = File(dir, "guides.json")
        if (f.exists()) parse(f.readText()) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }
}

// ---- Navigateur miniature (spec §5.3): tap-seek math ----

object NavigatorMath {
    /**
     * Tap at (tx, ty) in a thumbW×thumbH thumbnail of a canvasW×canvasH
     * canvas recenters the view on that canvas point: returns the
     * engine-space center the view should adopt.
     */
    fun seekCenter(
        tx: Float, ty: Float, thumbW: Float, thumbH: Float
    ): Pair<Float, Float> = Pair(tx / thumbW, ty / thumbH)
}

// ---- Courbes (spec §5.2): descripteur JSON ----

object CurvesJson {
    fun build(points: List<Pair<Int, Int>>, channel: String): String {
        val ch = if (channel in listOf("rgb", "r", "g", "b")) channel else "rgb"
        val sb = StringBuilder("{\"type\":\"curves\",\"channel\":\"")
        sb.append(ch).append("\",\"points\":[")
        points.take(16).forEachIndexed { i, (a, b) ->
            if (i > 0) sb.append(',')
            sb.append('[').append(a.coerceIn(0, 255)).append(',')
                .append(b.coerceIn(0, 255)).append(']')
        }
        return sb.append("]}").toString()
    }
}

// ---- Presse-papier session (spec §4.2, inter-docs via registre) ----

object V2Clipboard {
    var bitmap: Bitmap? = null
        private set
    var srcW: Int = 0
        private set
    var srcH: Int = 0
        private set

    fun store(b: Bitmap) {
        try {
            bitmap?.recycle()
        } catch (_: Exception) {
        }
        bitmap = b.copy(Bitmap.Config.ARGB_8888, false)
        srcW = b.width
        srcH = b.height
    }

    fun clear() {
        try {
            bitmap?.recycle()
        } catch (_: Exception) {
        }
        bitmap = null
        srcW = 0
        srcH = 0
    }
}

// ---- Transfo de sélection (spec §4.1): JSON bridge ----

object SelectionTransform {
    fun json(
        dx: Float, dy: Float, scale: Float, angleDeg: Float,
        mode: String, mesh: FloatArray? = null
    ): String {
        val sb = StringBuilder("{")
        sb.append(String.format(Locale.US, "\"dx\":%.2f,\"dy\":%.2f,", dx, dy))
        sb.append(String.format(Locale.US, "\"scale\":%.3f,\"angle\":%.2f,", scale, angleDeg))
        sb.append("\"mode\":\"").append(mode).append('"')
        if (mode == "mesh" && mesh != null && mesh.size == 18) {
            sb.append(",\"mesh\":[")
            mesh.forEachIndexed { i, v ->
                if (i > 0) sb.append(',')
                sb.append(String.format(Locale.US, "%.2f", v))
            }
            sb.append(']')
        }
        return sb.append('}').toString()
    }
}

// ---- Autosave (spec §5.4): politique opt-in ----

object AutosavePolicy {
    /** True when a tick should write (enabled, interval elapsed, no open stroke). */
    fun shouldSave(
        enabled: Boolean, intervalMin: Int, lastWriteMs: Long,
        nowMs: Long, strokeOpen: Boolean
    ): Boolean {
        if (!enabled || strokeOpen) return false
        if (intervalMin <= 0) return false
        return nowMs - lastWriteMs >= intervalMin * 60_000L
    }
}

// ---- Sondes de test (overlay/panneaux, jamais de pixels seuls) ----

object V2TestHooks {
    /** Current panel/bubble overlay polylines in engine coords. */
    var panelOverlay: List<List<Pair<Float, Float>>> = emptyList()

    /** Last navigator seek center (0..1 canvas fractions). */
    var lastSeek: Pair<Float, Float>? = null

    /** Last webtoon plan (page count + slice count). */
    var lastWebtoonPlan: Pair<Int, Int>? = null

    /** Last GIF export outcome (path + frame count). */
    var lastGif: Pair<String, Int>? = null

    /** Last SVG export outcome (path + path count). */
    var lastSvg: Pair<String, Int>? = null

    /** Last multipage op outcome (op + page count). */
    var lastPages: Pair<String, Int>? = null

    /** Last autosave tick outcome (wrote/skipped). */
    var lastAutosave: String? = null
}
