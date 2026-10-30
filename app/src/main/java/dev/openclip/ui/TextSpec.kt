// OpenClip T2 — text layer fiche + maison layout (app-side only).
//
// A text layer = a normal raster layer (pixels baked by the platform
// Canvas) + an app-side TextSpec fiche. Re-edit = re-render + replace
// the pixels (rasterized-editable, no engine glyphs). The fiche
// round-trips through ORA as x-openclip-* (engine nSetLayerText,
// ARCHITECTURE.md "V+1 additions" + "T2 additions"); unknown/absent =
// normal layer. T2: styled runs (multi-font spans), glyph outline,
// imported user fonts (licence libre confirmée à l'import, origine
// loggée), inline/vertical/hyphen — layout 100% maison (StaticLayout
// remplacé — contrôle total des runs, fonction pure testable).
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import java.io.File
import org.json.JSONObject

/** Platform logical families offered (AOSP system fonts, never tierces). */
val TEXT_SYSTEM_FONTS = listOf(
    "sans-serif", "sans-serif-light", "sans-serif-condensed",
    "sans-serif-medium", "serif", "monospace",
    "serif-monospace", "casual", "cursive", "sans-serif-smallcaps"
)

/** One styled run over the fiche text (start/len in chars). */
data class TextRun(
    val start: Int,
    val len: Int,
    val font: String = "sans-serif",
    val style: String = "regular", // regular|bold|italic|bold-italic
    val size: Int = 0, // 0 = fiche global size
    val colorHex: String = "" // "" = fiche global color
)

/** Glyph outline (contour): stroke width px + color, OFF when null. */
data class TextOutline(val w: Float, val colorHex: String)

/**
 * Rule-based hyphenation (from scratch, no dictionary — documented
 * approximatif). Cuts between V|CV, between consonant pairs (except
 * digraphs/mutes), and between identical doubles; never after the 1st
 * nor before the last 2 letters; words <= 5 chars untouched. Returns
 * cut offsets (prefix lengths), ascending, non-overlapping.
 */
fun hyphenate(word: String, lang: String): List<Int> {
    val w = word.lowercase()
    if (w.length <= 5) return emptyList()
    val vowels = "aeiouyàâäéèêëîïôöùûüœæ"
    fun isV(c: Char) = c in vowels
    fun isC(c: Char) = c.isLetter() && c !in vowels
    // Digraphs/mute+liquid onsets never split; `lang` ("fr"|"en",
    // spec §2 entry point) adds English clusters — a dictionary stays
    // FUTUR work (documented approximatif).
    val noSplit = setOf(
        "ch", "ph", "th", "sh", "wh", "gn", "qu", "bl", "cl", "gl",
        "pl", "br", "cr", "dr", "fr", "gr", "pr", "tr", "vr", "sc",
        "sm", "sn", "sp", "st", "str", "chr", "sch", "ck", "qu"
    ) + (if (lang == "en") setOf("gh", "ng", "nk", "tch") else emptySet())
    val cuts = mutableListOf<Int>()
    var i = 2
    while (i <= w.length - 2) {
        val prev = w[i - 1]
        val cur = w[i]
        val nxt = if (i + 1 < w.length) w[i + 1] else ' '
        var cut = false
        if (isC(prev) && isC(cur)) {
            val pair = "$prev$cur"
            cut = pair !in noSplit
        } else if (isV(prev) && isC(cur) && isV(nxt)) {
            cut = true // V|CV
        }
        if (cut) {
            cuts.add(i)
            i += 2 // non-overlapping: never stack cuts
        } else {
            i++
        }
    }
    return cuts
}

/** User font library (fonts/ cache): libre files imported via SAF. */
object FontRegistry {
    data class Entry(val family: String, val file: File?, val system: Boolean)

    var dir: File? = null
    private val cache = mutableMapOf<String, Typeface?>()
    private val lock = Any()

    /** System families + imported files (badge `maison` = !system). */
    fun families(): List<Entry> {
        val out = TEXT_SYSTEM_FONTS.map { Entry(it, null, true) }.toMutableList()
        try {
            dir?.listFiles()
                ?.filter { it.isFile && isFontFile(it.name) && it.length() in 1..(5L * 1024 * 1024) }
                ?.sortedBy { it.name }
                ?.forEach { out.add(Entry(it.nameWithoutExtension, it, false)) }
        } catch (_: Exception) {
        }
        return out
    }

    fun isFontFile(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".ttf") || n.endsWith(".otf")
    }

    /** Resolve a family to a Typeface; second = true when substituted. */
    fun typefaceFor(family: String, bold: Boolean, italic: Boolean): Pair<Typeface, Boolean> {
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        if (TEXT_SYSTEM_FONTS.contains(family)) {
            return Pair(Typeface.create(family, style), false)
        }
        synchronized(lock) {
            val key = "$family/$style"
            if (cache.containsKey(key)) {
                val t = cache[key]
                return if (t != null) Pair(t, false)
                else Pair(Typeface.create("sans-serif", style), true)
            }
            var loaded: Typeface? = null
            try {
                val f = dir?.listFiles()
                    ?.firstOrNull { it.isFile && it.nameWithoutExtension == family && isFontFile(it.name) }
                if (f != null && f.length() in 1..(5L * 1024 * 1024)) {
                    loaded = Typeface.createFromFile(f)
                }
            } catch (_: Exception) {
                loaded = null
            }
            cache[key] = loaded
            return if (loaded != null) Pair(loaded, false)
            else Pair(Typeface.create("sans-serif", style), true)
        }
    }

    sealed class ImportResult {
        data class Ok(val family: String) : ImportResult()
        data class Refused(val reason: String) : ImportResult() // too_large|bad_ext|unreadable
    }

    /** Copy a SAF font file into the library (honest refusals, no crash). */
    fun importFrom(src: File, displayName: String): ImportResult {
        if (!isFontFile(displayName)) return ImportResult.Refused("bad_ext")
        if (!src.exists() || !src.canRead()) return ImportResult.Refused("unreadable")
        if (src.length() > 5L * 1024 * 1024 || src.length() <= 0) return ImportResult.Refused("too_large")
        val d = dir ?: return ImportResult.Refused("unreadable")
        return try {
            if (!d.exists()) d.mkdirs()
            val ext = if (displayName.lowercase().endsWith(".otf")) ".otf" else ".ttf"
            val base = displayName.substringBeforeLast('.').take(64)
                .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
                .joinToString("").ifEmpty { "font" }
            val dst = File(d, base + ext)
            src.copyTo(dst, overwrite = true)
            // Validate the file parses as a typeface.
            val ok = try {
                Typeface.createFromFile(dst); true
            } catch (_: Exception) {
                false
            }
            if (!ok) {
                try {
                    dst.delete()
                } catch (_: Exception) {
                }
                ImportResult.Refused("unreadable")
            } else {
                synchronized(lock) { cache.clear() }
                ImportResult.Ok(dst.nameWithoutExtension)
            }
        } catch (_: Exception) {
            ImportResult.Refused("unreadable")
        }
    }

    /** Test/refresh hook (clears the typeface cache). */
    fun rescan() {
        synchronized(lock) { cache.clear() }
    }
}

data class TextSpec(
    val text: String,
    val font: String = "sans-serif",
    val sizePx: Int = 64,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val align: String = "left", // left|center|right
    val leading: Float = 1.0f, // 0.8..2.0
    val tracking: Float = 0f, // letter spacing px
    val colorHex: String = "#141414",
    val boxX: Float = 0f,
    val boxY: Float = 0f,
    val boxW: Float = 0f, // 0 = full canvas width from boxX
    // T2 (ARCHITECTURE.md §T2 §1–§2, opaque M3 keys engine-side).
    val runs: List<TextRun> = emptyList(),
    val outline: TextOutline? = null, // null = OFF
    val fontFile: String = "", // imported file name (REPRO repli)
    val dir: String = "h", // h|v (vertical = columns)
    val hyphen: Boolean = false,
    // B2 §1 (ARCHITECTURE.md "B2 additions"): transform of the text
    // block around (boxX, boxY). Identity = the legacy rendering.
    val rot: Float = 0f,      // degrees, -180..180
    val scaleX: Float = 1f,   // 0.1..8
    val scaleY: Float = 1f,   // 0.1..8
    val skew: Float = 0f      // degrees, -45..45
) {
    val styleName: String
        get() = when {
            bold && italic -> "bold-italic"
            bold -> "bold"
            italic -> "italic"
            else -> "regular"
        }

    /** Normalised transform tuple ("rot,sx,sy,skew"). */
    fun xformTuple(): String {
        val r = rot.coerceIn(-180f, 180f)
        val sx = scaleX.coerceIn(0.1f, 8f)
        val sy = scaleY.coerceIn(0.1f, 8f)
        val sk = skew.coerceIn(-45f, 45f)
        return "$r,$sx,$sy,$sk"
    }

    /** True when the block is rotated / scaled / skewed. */
    fun hasTransform(): Boolean =
        kotlin.math.abs(rot) > 0.01f ||
            kotlin.math.abs(scaleX - 1f) > 0.001f ||
            kotlin.math.abs(scaleY - 1f) > 0.001f ||
            kotlin.math.abs(skew) > 0.01f

    fun toJson(): String {
        val sb = StringBuilder("{")
        fun str(k: String, v: String) {
            sb.append("\"").append(k).append("\":\"")
            for (ch in v) {
                when (ch) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    else -> sb.append(ch)
                }
            }
            sb.append("\",")
        }
        str("text", text)
        str("font", font)
        sb.append("\"size\":").append(sizePx).append(",")
        str("style", styleName)
        str("align", align)
        sb.append("\"leading\":").append(leading).append(",")
        sb.append("\"tracking\":").append(tracking).append(",")
        str("color", colorHex)
        str("box", "$boxX,$boxY,$boxW")
        if (runs.isNotEmpty()) {
            sb.append("\"runs\":[")
            runs.forEachIndexed { i, r ->
                if (i > 0) sb.append(",")
                sb.append("{\"start\":").append(r.start)
                    .append(",\"len\":").append(r.len)
                sb.append(",\"font\":\"").append(r.font).append("\"")
                sb.append(",\"style\":\"").append(r.style).append("\"")
                sb.append(",\"size\":").append(r.size)
                sb.append(",\"color\":\"").append(r.colorHex).append("\"}")
            }
            sb.append("],")
        }
        if (outline != null) {
            sb.append("\"outline\":{\"w\":").append(outline.w)
            sb.append(",\"color\":\"").append(outline.colorHex).append("\"},")
        }
        if (fontFile.isNotEmpty()) str("fontfile", fontFile)
        if (dir == "v") str("dir", "v")
        if (hyphen) sb.append("\"hyphen\":true,")
        if (hasTransform()) str("xf", xformTuple())
        sb.append("\"end\":1}")
        return sb.toString()
    }

    companion object {
        private fun parseBox(o: JSONObject): Triple<Float, Float, Float> {
            val box = o.optString("box", "").split(",")
            return Triple(
                box.getOrNull(0)?.toFloatOrNull() ?: 0f,
                box.getOrNull(1)?.toFloatOrNull() ?: 0f,
                box.getOrNull(2)?.toFloatOrNull() ?: 0f
            )
        }

        private fun parseRuns(o: JSONObject, textLen: Int): List<TextRun> {
            val arr = o.optJSONArray("runs") ?: return emptyList()
            val out = mutableListOf<TextRun>()
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val st = r.optInt("start", -1)
                val ln = r.optInt("len", 0)
                if (st < 0 || ln <= 0 || st >= textLen) continue // hors-bornes = ignoré
                out.add(
                    TextRun(
                        start = st,
                        len = minOf(ln, textLen - st),
                        font = r.optString("font", "sans-serif"),
                        style = r.optString("style", "regular"),
                        size = r.optInt("size", 0),
                        colorHex = r.optString("color", "")
                    )
                )
            }
            return out.sortedBy { it.start }
        }

        private fun parseOutline(o: JSONObject): TextOutline? {
            val ob = o.optJSONObject("outline") ?: return null
            val w = ob.optDouble("w", 0.0).toFloat()
            if (w < 0.5f || w > 8f) return null // 0–8px, défaut OFF
            val c = ob.optString("color", "#000000").ifEmpty { "#000000" }
            return TextOutline(w.coerceIn(0.5f, 8f), c)
        }

        fun fromJson(raw: String): TextSpec? {
            return try {
                val o = JSONObject(raw)
                val t = o.optString("text", "")
                if (t.isEmpty()) return null
                val style = o.optString("style", "regular")
                val (bx, by, bw) = parseBox(o)
                val xf = parseXform(o.optString("xf", ""))
                TextSpec(
                    text = t,
                    font = o.optString("font", "sans-serif"),
                    sizePx = o.optInt("size", 64).coerceIn(8, 1024),
                    bold = style.contains("bold"),
                    italic = style.contains("italic"),
                    align = o.optString("align", "left"),
                    leading = o.optDouble("leading", 1.0).toFloat()
                        .coerceIn(0.5f, 4f),
                    tracking = o.optDouble("tracking", 0.0).toFloat()
                        .coerceIn(-64f, 64f),
                    colorHex = o.optString("color", "#141414"),
                    boxX = bx,
                    boxY = by,
                    boxW = bw,
                    runs = parseRuns(o, t.length),
                    outline = parseOutline(o),
                    fontFile = o.optString("fontfile", ""),
                    dir = if (o.optString("dir", "h") == "v") "v" else "h",
                    hyphen = o.optBoolean("hyphen", false),
                    rot = xf.getOrNull(0) ?: 0f,
                    scaleX = xf.getOrNull(1) ?: 1f,
                    scaleY = xf.getOrNull(2) ?: 1f,
                    skew = xf.getOrNull(3) ?: 0f
                )
            } catch (_: Exception) {
                null
            }
        }

        /** B2 §1: parse "rot,sx,sy,skew" with honest clamps. */
        fun parseXform(raw: String): List<Float> {
            if (raw.isEmpty()) return emptyList()
            val parts = raw.split(",")
            if (parts.size != 4) return emptyList()
            val v = parts.map { it.trim().toFloatOrNull() ?: return emptyList() }
            return listOf(
                v[0].coerceIn(-180f, 180f),
                v[1].coerceIn(0.1f, 8f),
                v[2].coerceIn(0.1f, 8f),
                v[3].coerceIn(-45f, 45f)
            )
        }

        /** Rebuild a fiche from an nLayerState "text" object. */
        fun fromState(o: JSONObject): TextSpec? {
            return try {
                val t = o.optString("content", "")
                if (t.isEmpty()) return null
                val style = o.optString("style", "regular")
                val box = o.optString("box", "").split(",")
                // runs arrives as a raw JSONArray (shim nLayerState).
                val runs = try {
                    val arr = o.optJSONArray("runs")
                    if (arr != null) {
                        parseRuns(
                            JSONObject().apply { put("runs", arr) },
                            t.length
                        )
                    } else emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
                val outline = try {
                    val ob = o.optJSONObject("outline")
                    if (ob != null) {
                        parseOutline(
                            JSONObject().apply { put("outline", ob) }
                        )
                    } else null
                } catch (_: Exception) {
                    null
                }
                val xf = parseXform(o.optString("xform", ""))
                TextSpec(
                    text = t,
                    font = o.optString("font", "sans-serif"),
                    sizePx = o.optInt("size", 64).coerceIn(8, 1024),
                    bold = style.contains("bold"),
                    italic = style.contains("italic"),
                    align = o.optString("align", "left"),
                    leading = o.optDouble("leading", 1.0).toFloat()
                        .coerceIn(0.5f, 4f),
                    tracking = o.optDouble("tracking", 0.0).toFloat()
                        .coerceIn(-64f, 64f),
                    colorHex = o.optString("color", "#141414"),
                    boxX = box.getOrNull(0)?.toFloatOrNull() ?: 0f,
                    boxY = box.getOrNull(1)?.toFloatOrNull() ?: 0f,
                    boxW = box.getOrNull(2)?.toFloatOrNull() ?: 0f,
                    runs = runs,
                    outline = outline,
                    fontFile = o.optString("fontfile", ""),
                    dir = if (o.optString("dir", "h") == "v") "v" else "h",
                    hyphen = o.optBoolean("hyphen", false),
                    rot = xf.getOrNull(0) ?: 0f,
                    scaleX = xf.getOrNull(1) ?: 1f,
                    scaleY = xf.getOrNull(2) ?: 1f,
                    skew = xf.getOrNull(3) ?: 0f
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** Maison line engine (fonction pure, testable — remplace StaticLayout). */
object TextLineLayout {
    data class Seg(
        val text: String,
        val font: String,
        val bold: Boolean,
        val italic: Boolean,
        val sizePx: Int,
        val colorHex: String
    )

    data class LaidRun(val seg: Seg, val x: Float)
    data class LaidLine(val runs: List<LaidRun>, val width: Float, val height: Float)

    /** Styled segments covering the whole text (gaps = global style). */
    fun segments(spec: TextSpec): List<Seg> {
        if (spec.runs.isEmpty()) {
            return listOf(
                Seg(
                    spec.text, spec.font, spec.bold, spec.italic,
                    spec.sizePx, spec.colorHex
                )
            )
        }
        val out = mutableListOf<Seg>()
        var pos = 0
        for (r in spec.runs.sortedBy { it.start }) {
            if (r.start >= spec.text.length) break
            if (r.start > pos) {
                out.add(
                    Seg(
                        spec.text.substring(pos, r.start), spec.font,
                        spec.bold, spec.italic, spec.sizePx, spec.colorHex
                    )
                )
                pos = r.start
            }
            if (r.start < pos) continue // overlap: first wins
            val end = minOf(pos + r.len, spec.text.length)
            if (end <= pos) continue
            out.add(
                Seg(
                    spec.text.substring(pos, end),
                    r.font.ifEmpty { spec.font },
                    r.style.contains("bold"),
                    r.style.contains("italic"),
                    if (r.size in 8..1024) r.size else spec.sizePx,
                    r.colorHex.ifEmpty { spec.colorHex }
                )
            )
            pos = end
        }
        if (pos < spec.text.length) {
            out.add(
                Seg(
                    spec.text.substring(pos), spec.font, spec.bold,
                    spec.italic, spec.sizePx, spec.colorHex
                )
            )
        }
        return out.ifEmpty {
            listOf(
                Seg(
                    spec.text, spec.font, spec.bold, spec.italic,
                    spec.sizePx, spec.colorHex
                )
            )
        }
    }

    private fun paintFor(seg: Seg, tracking: Float): Paint {
        val (tf, _) = FontRegistry.typefaceFor(seg.font, seg.bold, seg.italic)
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = parseColor(seg.colorHex)
            textSize = seg.sizePx.coerceIn(8, 1024).toFloat()
            typeface = tf
            if (Build.VERSION.SDK_INT >= 21) {
                letterSpacing = tracking / textSize.coerceAtLeast(1f)
            }
        }
    }

    private fun segWidth(seg: Seg, tracking: Float): Float {
        if (seg.text.isEmpty()) return 0f
        val p = paintFor(seg, tracking)
        val w = FloatArray(seg.text.length)
        p.getTextWidths(seg.text, w)
        var sum = 0f
        for (v in w) sum += v
        return sum + tracking * (seg.text.length - 1).coerceAtLeast(0)
    }

    /** Greedy word wrap over styled segments (spaces = glue). */
    fun layout(spec: TextSpec, wrapW: Float, lang: String = "fr"): List<LaidLine> {
        val lines = mutableListOf<LaidLine>()
        // Split segments into word tokens (keep spaces attached right).
        data class Tok(val seg: Seg, val word: String, val space: Boolean)
        val toks = mutableListOf<Tok>()
        for (seg in segments(spec)) {
            val parts = seg.text.split(' ')
            for ((i, part) in parts.withIndex()) {
                if (part.isNotEmpty()) toks.add(Tok(seg, part, false))
                if (i < parts.size - 1) {
                    toks.add(Tok(seg.copy(text = " "), " ", true))
                }
            }
        }
        var cur = mutableListOf<Pair<Seg, String>>()
        var curW = 0f
        var curH = 0f
        fun spaceW(): Float = segWidth(
            Seg(" ", spec.font, false, false, spec.sizePx, spec.colorHex),
            spec.tracking
        )
        fun flush() {
            if (cur.isEmpty()) return
            // Drop a trailing glue space (never renders line-final).
            if (cur.last().second == " ") cur.removeAt(cur.size - 1)
            var x = 0f
            val runs = cur.map { (s, w) ->
                LaidRun(s.copy(text = w), x).also {
                    x += if (w == " ") spaceW() else segWidth(s.copy(text = w), spec.tracking)
                }
            }
            val h = (cur.maxOfOrNull { it.first.sizePx.toFloat() } ?: spec.sizePx.toFloat()) *
                spec.leading.coerceIn(0.5f, 4f)
            lines.add(LaidLine(runs, x, h))
            cur = mutableListOf()
            curW = 0f
            curH = 0f
        }
        for (t in toks) {
            val w = if (t.space) spaceW()
            else segWidth(t.seg.copy(text = t.word), spec.tracking)
            if (curW + w <= wrapW || cur.isEmpty()) {
                cur.add(Pair(t.seg, if (t.space) " " else t.word))
                curW += w
                curH = maxOf(curH, t.seg.sizePx.toFloat())
            } else if (!t.space && spec.hyphen && t.word.length > 5) {
                // Hyphenate the overlong word (tiret affiché).
                var rest = t.word
                var first = true
                while (rest.isNotEmpty()) {
                    val room = wrapW - curW
                    val rw = segWidth(t.seg.copy(text = rest), spec.tracking)
                    if (rw <= room || cur.isEmpty() && rw <= wrapW) {
                        cur.add(Pair(t.seg, rest))
                        curW += rw
                        curH = maxOf(curH, t.seg.sizePx.toFloat())
                        rest = ""
                    } else {
                        val cuts = hyphenate(rest, lang)
                        var done = false
                        for (k in cuts.sortedDescending()) {
                            val pre = rest.substring(0, k) + "-"
                            val pw = segWidth(t.seg.copy(text = pre), spec.tracking)
                            if (pw <= (if (first) room else wrapW)) {
                                if (!first) flush()
                                cur.add(Pair(t.seg, pre))
                                curW += pw
                                curH = maxOf(curH, t.seg.sizePx.toFloat())
                                flush()
                                rest = rest.substring(k)
                                first = false
                                done = true
                                break
                            }
                        }
                        if (!done) {
                            // No cut fits: hard break (honnête dégradé).
                            if (!first) flush()
                            cur.add(Pair(t.seg, rest))
                            curW += rw
                            rest = ""
                        }
                    }
                }
            } else {
                flush()
                if (!t.space) {
                    cur.add(Pair(t.seg, t.word))
                    curW += w
                    curH = maxOf(curH, t.seg.sizePx.toFloat())
                } else {
                    curW = 0f // leading space dropped
                }
            }
        }
        flush()
        return lines.ifEmpty {
            listOf(
                LaidLine(
                    emptyList(),
                    0f,
                    spec.sizePx.toFloat() * spec.leading.coerceIn(0.5f, 4f)
                )
            )
        }
    }

    /** B2 §1: layout frame (wrap width, total line height) for the
     *  transform handles and the preview matrix. */
    fun frameSize(spec: TextSpec, canvasW: Int): Pair<Float, Float> {
        val wrapW = if (spec.boxW > 1f) spec.boxW
        else (canvasW - spec.boxX).coerceAtLeast(8f)
        val lines = layout(spec, wrapW)
        return Pair(wrapW, lines.sumOf { it.height.toDouble() }.toFloat())
    }

    private fun parseColor(hex: String): Int {
        return try {
            val h = hex.trimStart('#')
            (0xFF000000.toInt() or h.takeLast(6).toInt(16))
        } catch (_: Exception) {
            0xFF141414.toInt()
        }
    }
}

object TextRenderer {
    data class Rendered(val bitmap: Bitmap, val fontSubstituted: Boolean)

    fun render(spec: TextSpec, canvasW: Int, canvasH: Int): Rendered {
        var substituted = false
        // Resolve every family once (substitution flag when any falls
        // back — V+1 §2.4 pattern conservé).
        val fams = mutableSetOf(spec.font)
        spec.runs.forEach { fams.add(it.font) }
        for (f in fams) {
            val (_, sub) = FontRegistry.typefaceFor(
                f.ifEmpty { spec.font }, spec.bold, spec.italic
            )
            if (sub) substituted = true
        }
        if (spec.fontFile.isNotEmpty()) {
            val known = FontRegistry.families().any {
                !it.system && it.file?.name == spec.fontFile
            }
            if (!known) substituted = true
        }
        val wrapW = if (spec.boxW > 1f) spec.boxW
        else (canvasW - spec.boxX).coerceAtLeast(8f)
        val lines = TextLineLayout.layout(spec, wrapW)
        val bmp = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val align = spec.align
        // B2 §1: rotate / skew / scale around the text frame origin
        // (boxX, boxY), exactly the app-side matrix the overlay preview
        // uses. Identity = the legacy byte path.
        c.save()
        if (spec.hasTransform()) {
            c.translate(spec.boxX, spec.boxY)
            c.rotate(spec.rot.coerceIn(-180f, 180f))
            c.skew(
                kotlin.math.tan(
                    Math.toRadians(spec.skew.coerceIn(-45f, 45f).toDouble())
                ).toFloat(),
                0f
            )
            c.scale(
                spec.scaleX.coerceIn(0.1f, 8f),
                spec.scaleY.coerceIn(0.1f, 8f)
            )
            c.translate(-spec.boxX, -spec.boxY)
        }
        if (spec.dir == "v") {
            // Tategaki simplifié (honnête) : chaque ligne devient une
            // colonne haut→bas ; colonnes droite→gauche ; latin pivoté
            // 90° (rotate(90) = +x vers +y, le bas).
            val totalW = lines.sumOf { it.height.toDouble() }.toFloat()
            var colX = spec.boxX + totalW
            for (ln in lines) {
                colX -= ln.height
                c.save()
                // rotate(90) maps (x,y)->(-y,x): translate the column's
                // right edge so the line lands in [colX,colX+H].
                c.translate(colX + ln.height, spec.boxY)
                c.rotate(90f)
                drawLine(c, ln, 0f, 0f, spec, wrapW, align, true)
                c.restore()
            }
        } else {
            var y = spec.boxY
            for (ln in lines) {
                drawLine(c, ln, spec.boxX, y, spec, wrapW, align, false)
                y += ln.height
            }
        }
        c.restore()
        return Rendered(bmp, substituted)
    }

    private fun drawLine(
        c: android.graphics.Canvas,
        ln: TextLineLayout.LaidLine,
        ox: Float, oy: Float,
        spec: TextSpec, wrapW: Float, align: String, vertical: Boolean
    ) {
        val startX = when (align) {
            "center" -> ox + (wrapW - ln.width) / 2f
            "right" -> ox + (wrapW - ln.width)
            else -> ox
        }
        for (lr in ln.runs) {
            val seg = lr.seg
            val (tf, _) = FontRegistry.typefaceFor(seg.font, seg.bold, seg.italic)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = parseColor(seg.colorHex)
                textSize = seg.sizePx.coerceIn(8, 1024).toFloat()
                typeface = tf
                if (Build.VERSION.SDK_INT >= 21) {
                    letterSpacing = spec.tracking / textSize.coerceAtLeast(1f)
                }
            }
            val fm = fill.fontMetrics
            // Baseline from the line top (oy): oy - fm.top.
            val bx = startX + lr.x
            val by = oy - fm.top
            val ol = spec.outline
            if (ol != null) {
                val stroke = Paint(fill).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = ol.w
                    strokeJoin = Paint.Join.ROUND
                    color = parseColor(ol.colorHex)
                }
                c.drawText(seg.text, bx, by, stroke)
            }
            c.drawText(seg.text, bx, by, fill)
        }
        @Suppress("UNUSED_PARAMETER")
        val unusedV = vertical
    }

    private fun parseColor(hex: String): Int {
        return try {
            val h = hex.trimStart('#')
            (0xFF000000.toInt() or h.takeLast(6).toInt(16))
        } catch (_: Exception) {
            0xFF141414.toInt()
        }
    }
}
