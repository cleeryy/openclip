// OpenClip slice 2 — ColorStore: single source of truth for brush ink.
//
// Hoisted in MainActivity (remembered field): ColorPanel tabs, ToolPalette
// and the pipette all read/write inkHex here. App-side only: persistence is
// a hand-rolled JSON file in filesDir (no new dependency, no DataStore),
// writes debounced 500 ms + flushed on onPause. Corrupt files fall back
// silently to the built-in Base set (never a crash at boot).
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

const val COLOR_FALLBACK = "#141414"
const val COLOR_SETS_VERSION = 1
const val COLOR_SETS_FILE = "color-sets.json"
const val COLOR_RECENT_FILE = "color-recent.json"
const val MAX_SETS = 12
const val MAX_SET_COLORS = 48
const val MAX_RECENTS = 12

// Former QUICK_SWATCHES from ColorPicker: now the built-in "Base" set.
val BASE_COLORS = listOf(
    "#141414", "#FFFFFF", "#E53935", "#FB8C00",
    "#FDD835", "#43A047", "#1E88E5", "#8E24AA",
    "#F48FB1", "#8D6E63"
)

data class NamedColorSet(val name: String, val colors: List<String>)

/** Uppercase #RRGGBB, clamped; COLOR_FALLBACK on anything invalid. */
fun normalizeHex(input: String): String {
    var s = input.trim().uppercase(Locale.US)
    if (!s.startsWith("#")) s = "#$s"
    val body = s.substring(1)
    if (body.length != 6) return COLOR_FALLBACK
    for (c in body) {
        if ((c < '0' || c > '9') && (c < 'A' || c > 'F')) return COLOR_FALLBACK
    }
    return "#$body"
}

fun hexToRgb(hex: String): Triple<Int, Int, Int> {
    val n = normalizeHex(hex)
    return Triple(
        n.substring(1, 3).toInt(16),
        n.substring(3, 5).toInt(16),
        n.substring(5, 7).toInt(16)
    )
}

fun rgbToHex(r: Int, g: Int, b: Int): String = String.format(
    Locale.US, "#%02X%02X%02X",
    r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255)
)

/** Returns floatArrayOf(h 0..360, s 0..1, v 0..1). Pure framework, no JNI. */
fun hexToHsv(hex: String): FloatArray {
    val (r, g, b) = hexToRgb(hex)
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(r, g, b, hsv)
    return hsv
}

fun hsvToHex(h: Float, s: Float, v: Float): String {
    val c = android.graphics.Color.HSVToColor(
        floatArrayOf(
            ((h % 360f) + 360f) % 360f,
            s.coerceIn(0f, 1f),
            v.coerceIn(0f, 1f)
        )
    )
    return rgbToHex((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
}

fun parseHexColor(hex: String): androidx.compose.ui.graphics.Color {
    return try {
        androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(normalizeHex(hex)))
    } catch (_: Exception) {
        androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(COLOR_FALLBACK))
    }
}

class ColorStore(appContext: Context) {
    private val dir: File = appContext.applicationContext.filesDir

    var inkHex by mutableStateOf(COLOR_FALLBACK)
        private set
    var sets by mutableStateOf(listOf(NamedColorSet("Base", BASE_COLORS)))
        private set
    var activeSet by mutableStateOf("Base")
        private set
    var recents by mutableStateOf(listOf<String>())
        private set
    var pipetteArmed by mutableStateOf(false)
    var pipetteLoupe by mutableStateOf(false)
    var pipetteLive by mutableStateOf<String?>(null)
        private set

    // NOTE: readFiles() runs in the init{} at the end of the class body
    // (properties first: Kotlin runs initializers in textual order, so the
    // Regex vals must exist before the first read).

    private val handler = Handler(Looper.getMainLooper())
    private var savePending = false
    private val saveRun = Runnable {
        savePending = false
        writeFiles()
    }

    fun pick(hex: String) {
        inkHex = normalizeHex(hex)
        pipetteLive = null
    }

    /** Loupe-drag live preview: pastille follows, engine push on release. */
    fun preview(hex: String) {
        pipetteLive = normalizeHex(hex)
    }

    fun finalizePreview() {
        pipetteLive?.let { inkHex = it }
        pipetteLive = null
    }

    fun pushRecent(hex: String) {
        val n = normalizeHex(hex)
        recents = (listOf(n) + recents.filter { it != n }).take(MAX_RECENTS)
        schedule()
    }

    fun clearRecents() {
        recents = emptyList()
        schedule()
    }

    fun activeColors(): List<String> =
        sets.find { it.name == activeSet }?.colors ?: emptyList()

    fun selectSet(name: String) {
        if (sets.any { it.name == name }) {
            activeSet = name
            schedule()
        }
    }

    fun newSet(baseName: String): Boolean {
        if (sets.size >= MAX_SETS) return false
        var name = baseName.ifBlank { "Set" }
        var i = 1
        var candidate = name
        while (sets.any { it.name == candidate }) {
            i++
            candidate = "$name $i"
        }
        sets = sets + NamedColorSet(candidate, emptyList())
        activeSet = candidate
        schedule()
        return true
    }

    fun renameSet(old: String, newName: String): Boolean {
        val clean = newName.trim().take(32)
        if (clean.isEmpty() || old == "Base") return false
        if (sets.any { it.name == clean }) return false
        sets = sets.map { if (it.name == old) it.copy(name = clean) else it }
        if (activeSet == old) activeSet = clean
        schedule()
        return true
    }

    fun deleteSet(name: String): Boolean {
        if (name == "Base") return false
        if (sets.none { it.name == name }) return false
        sets = sets.filter { it.name != name }
        if (activeSet == name) activeSet = "Base"
        schedule()
        return true
    }

    fun addCurrentToActive(): Boolean {
        val idx = sets.indexOfFirst { it.name == activeSet }
        if (idx < 0) return false
        val cur = sets[idx]
        if (cur.colors.size >= MAX_SET_COLORS) return false
        if (cur.colors.contains(inkHex)) return true
        sets = sets.toMutableList().also {
            it[idx] = cur.copy(colors = cur.colors + inkHex)
        }
        schedule()
        return true
    }

    fun replaceInSet(setName: String, oldHex: String, newHex: String): Boolean {
        val idx = sets.indexOfFirst { it.name == setName }
        if (idx < 0 || setName == "Base") return false
        val n = normalizeHex(newHex)
        val cur = sets[idx]
        sets = sets.toMutableList().also {
            it[idx] = cur.copy(colors = cur.colors.map { c -> if (c == oldHex) n else c })
        }
        schedule()
        return true
    }

    fun removeFromSet(setName: String, hex: String): Boolean {
        val idx = sets.indexOfFirst { it.name == setName }
        if (idx < 0 || setName == "Base") return false
        val cur = sets[idx]
        sets = sets.toMutableList().also {
            it[idx] = cur.copy(colors = cur.colors.filter { c -> c != hex })
        }
        schedule()
        return true
    }

    fun armPipette(loupe: Boolean) {
        pipetteArmed = true
        pipetteLoupe = loupe
        pipetteLive = null
    }

    fun disarmPipette() {
        pipetteArmed = false
        pipetteLoupe = false
        pipetteLive = null
    }

    /** Debounced write (500 ms) + explicit flush on onPause. */
    fun flush() {
        handler.removeCallbacks(saveRun)
        if (savePending) {
            savePending = false
            writeFiles()
        }
    }

    private fun schedule() {
        if (!savePending) {
            savePending = true
            handler.postDelayed(saveRun, 500)
        }
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun writeFiles() {
        try {
            val sb = StringBuilder("{\"version\":$COLOR_SETS_VERSION,\"sets\":[")
            sets.forEachIndexed { i, s ->
                if (i > 0) sb.append(',')
                sb.append("{\"name\":\"${esc(s.name)}\",\"colors\":[")
                s.colors.forEachIndexed { j, c ->
                    if (j > 0) sb.append(',')
                    sb.append("\"${normalizeHex(c)}\"")
                }
                sb.append("]}")
            }
            sb.append("],\"active\":\"${esc(activeSet)}\"}")
            File(dir, COLOR_SETS_FILE).writeText(sb.toString())
        } catch (_: Exception) {
        }
        try {
            val rb = StringBuilder("{\"version\":$COLOR_SETS_VERSION,\"recent\":[")
            recents.forEachIndexed { i, c ->
                if (i > 0) rb.append(',')
                rb.append("\"${normalizeHex(c)}\"")
            }
            rb.append("]}")
            File(dir, COLOR_RECENT_FILE).writeText(rb.toString())
        } catch (_: Exception) {
        }
    }

    private fun unesc(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                out.append(s[i + 1])
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private val hexRe = Regex("\"#[0-9A-Fa-f]{6}\"")
    private val nameRe = Regex("\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val activeRe = Regex("\"active\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val colorsRe = Regex("\"colors\"\\s*:\\s*\\[([^\\]]*)\\]")
    private val recentRe = Regex("\"recent\"\\s*:\\s*\\[([^\\]]*)\\]")

    private fun readFiles() {
        try {
            val f = File(dir, COLOR_SETS_FILE)
            if (f.exists()) {
                val t = f.readText()
                if (!t.contains("\"version\":$COLOR_SETS_VERSION")) throw IllegalArgumentException("version")
                val active = activeRe.find(t)?.groupValues?.get(1)?.let { unesc(it) } ?: "Base"
                val names = nameRe.findAll(t).map { unesc(it.groupValues[1]) }.toList()
                val colorBlocks = colorsRe.findAll(t).map { m ->
                    hexRe.findAll(m.groupValues[1]).map {
                        normalizeHex(it.value.trim('"'))
                    }.take(MAX_SET_COLORS).toList()
                }.toList()
                if (names.isEmpty() || names.size != colorBlocks.size) {
                    throw IllegalArgumentException("shape")
                }
                val loaded = names.zip(colorBlocks) { n, cs -> NamedColorSet(n, cs) }
                    .take(MAX_SETS)
                if (loaded.none { it.name == "Base" } || loaded.any { it.name.isBlank() }) {
                    throw IllegalArgumentException("base")
                }
                sets = loaded
                activeSet = if (loaded.any { it.name == active }) active else "Base"
            }
        } catch (_: Exception) {
            sets = listOf(NamedColorSet("Base", BASE_COLORS))
            activeSet = "Base"
        }
        try {
            val f = File(dir, COLOR_RECENT_FILE)
            if (f.exists()) {
                val t = f.readText()
                if (!t.contains("\"version\":$COLOR_SETS_VERSION")) throw IllegalArgumentException("version")
                val block = recentRe.find(t)?.groupValues?.get(1) ?: throw IllegalArgumentException("shape")
                recents = hexRe.findAll(block).map {
                    normalizeHex(it.value.trim('"'))
                }.distinct().take(MAX_RECENTS).toList()
            }
        } catch (_: Exception) {
            recents = emptyList()
        }
    }

    init {
        readFiles()
    }
}
