// OpenClip T5 — échanges (spec §4, app-side 100 %, 0 JNI).
// GrdParser / AbrParser (Kotlin from scratch, aucune lib) : lisent le
// sous-ensemble ouvert documenté ci-dessous ; tout le reste (bruit,
// transparence complexe, enveloppes inconnues, RLE) = REFUSÉ avec
// message, jamais de donnée approchée silencieuse. Les fixtures de
// test sont générées in-code (jamais de binaires tiers).
package dev.openclip.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.openclip.R
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

// ---------------------------------------------------------------- .grd
//
// Sous-ensemble ouvert documenté (interops honnête, from scratch) :
//   u16BE version (1..6 tentés), u16BE count (<= 64),
//   par dégradé :
//     nom : v3+ = u32BE len + len×u16BE ; v1/v2 = u8 len + bytes
//     u16 type (0 = uni accepté ; 1 = bruit → dégradé refusé)
//     u16 interpolation (ignorée + annoncée)
//     u16 nColor, puis nColor × { u32 loc (0..4096), u32 milieu,
//       u16 espace (0 = RVB accepté ; autre → dégradé refusé),
//       4×u16 valeurs (RVB : r,v,b + bourre ; >255 ⇒ /257) }
//     u16 nTrans, puis nTrans × { u32 loc, u32 milieu, u16 opacité } —
//       opacité pleine (100, ou 255 par tolérance documentée) =
//       ignorée ; toute autre valeur = dégradé refusé (transparence
//       complexe) ; tout octet restant en fin de fichier = refusé
//       (champs d'extension inconnus).
// Résultat : (nom, stops "#RRGGBB:pos" 2..8 triés ; >8 ⇒
// sous-échantillonnés à 8, annoncé).

data class GrdGradient(val name: String, val stops: List<String>)

object GrdParser {
    fun parse(bytes: ByteArray): Pair<List<GrdGradient>, List<String>> {
        val out = mutableListOf<GrdGradient>()
        val notes = mutableListOf<String>()
        val errs = mutableListOf<String>()
        try {
            var p = 0
            fun u16(): Int {
                if (p + 2 > bytes.size) throw Break()
                val v = ((bytes[p].toInt() and 0xFF) shl 8) or
                    (bytes[p + 1].toInt() and 0xFF)
                p += 2
                return v
            }
            fun u32(): Long {
                if (p + 4 > bytes.size) throw Break()
                val v = ((bytes[p].toLong() and 0xFF) shl 24) or
                    ((bytes[p + 1].toLong() and 0xFF) shl 16) or
                    ((bytes[p + 2].toLong() and 0xFF) shl 8) or
                    (bytes[p + 3].toLong() and 0xFF)
                p += 4
                return v
            }
            if (bytes.size < 4) {
                errs.add("truncated")
                return Pair(out, errs)
            }
            val version = u16()
            if (version !in 1..6) {
                errs.add("version $version")
                return Pair(out, errs)
            }
            val count = u16()
            if (count > 64) {
                errs.add("count $count")
                return Pair(out, errs)
            }
            repeat(count) {
                val name = if (version >= 3) {
                    val n = u32().toInt()
                    if (n < 0 || n > 256 || p + n * 2 > bytes.size) throw Break()
                    val sb = StringBuilder()
                    repeat(n) { sb.append(u16().toChar()) }
                    sb.toString()
                } else {
                    if (p >= bytes.size) throw Break()
                    val n = bytes[p].toInt() and 0xFF
                    p++
                    if (n > 64 || p + n > bytes.size) throw Break()
                    val s = String(bytes, p, n, Charsets.US_ASCII)
                    p += n
                    s
                }
                val type = u16()
                u16() // interpolation (ignorée + annoncée)
                if (type != 0) {
                    notes.add("$name: noise")
                    // Sauter les stops sans les lire est impossible sans
                    // la géométrie : le dégradé est refusé (spec §4.1).
                    throw Break()
                }
                val nColor = u16()
                if (nColor > 64) throw Break()
                val stops = mutableListOf<Pair<Double, Int>>()
                var nonRgb = false
                repeat(nColor) {
                    val loc = u32()
                    u32() // milieu
                    val space = u16()
                    val v0 = u16()
                    val v1 = u16()
                    val v2 = u16()
                    u16() // bourre
                    if (space != 0) {
                        nonRgb = true
                        return@repeat
                    }
                    fun c(v: Int): Int {
                        val x = if (v > 255) v / 257 else v
                        return x.coerceIn(0, 255)
                    }
                    val pos = (loc.coerceIn(0, 4096)).toDouble() / 4096.0
                    stops.add(Pair(pos, (c(v0) shl 16) or (c(v1) shl 8) or c(v2)))
                }
                val nTrans = u16()
                if (nTrans > 64) throw Break()
                var badAlpha = false
                repeat(nTrans) {
                    u32() // loc
                    u32() // milieu
                    val op = u16()
                    if (op != 100 && op != 255 && op != 65535) badAlpha = true
                }
                if (nonRgb || badAlpha) {
                    notes.add(
                        "$name: " + if (nonRgb) "non-rgb" else "transparency"
                    )
                    return@repeat
                }
                if (stops.size < 2) {
                    notes.add("$name: <2 stops")
                    return@repeat
                }
                val sorted = stops.sortedBy { it.first }
                val kept = if (sorted.size > 8) {
                    notes.add("$name: ${sorted.size}→8")
                    val step = (sorted.size - 1).toDouble() / 7.0
                    (0 until 8).map { sorted[(it * step).toInt()] }
                } else sorted
                out.add(
                    GrdGradient(
                        name.ifEmpty { "gradient-${out.size + 1}" }.take(48),
                        kept.map { (pos, rgb) ->
                            "#%06X:%.4f".format(rgb, pos)
                        }
                    )
                )
            }
            if (p != bytes.size) {
                // Champs d'extension inconnus : refus honnête (spec §4.1).
                errs.add("trailing ${bytes.size - p}B")
                out.clear()
            }
        } catch (b: Break) {
            errs.add("truncated")
            out.clear()
        } catch (_: Exception) {
            errs.add("unreadable")
            out.clear()
        }
        return Pair(out, (errs + notes).distinct())
    }

    private class Break : Exception()

    /** Fixture in-code (jamais de binaire tiers) : 2 stops RVB. */
    fun fixtureTwoStops(): ByteArray {
        val b = mutableListOf<Byte>()
        fun u16(v: Int) {
            b.add(((v shr 8) and 0xFF).toByte())
            b.add((v and 0xFF).toByte())
        }
        fun u32(v: Long) {
            b.add(((v shr 24) and 0xFF).toByte())
            b.add(((v shr 16) and 0xFF).toByte())
            b.add(((v shr 8) and 0xFF).toByte())
            b.add((v and 0xFF).toByte())
        }
        u16(3) // version
        u16(1) // count
        u32(4) // namelen
        "test".forEach { u16(it.code) }
        u16(0) // type uni
        u16(0) // interpolation
        u16(2) // 2 stops
        u32(0); u32(2048); u16(0); u16(0); u16(0); u16(0); u16(0)
        u32(4096); u32(2048); u16(0); u16(65535); u16(65535); u16(65535); u16(0)
        u16(0) // 0 trans
        return b.toByteArray()
    }
}

// ---------------------------------------------------------------- .abr
//
// Sous-ensemble ouvert documenté (enveloppe v1/v2, from scratch) :
//   u16BE version (1|2 acceptés ; 6+ = descripteurs → refusé),
//   u16BE count (<= 64),
//   par pointe échantillonnée :
//     u16 type (2 = échantillonnée acceptée ; autre → refusée),
//     u32 taille (ignorée), u8[4] bornes ? — géométrie lue comme :
//     u32 top,left,bottom,right (<= 256), u16 profondeur (8 exigée),
//     u16 compression (0 = brut accepté ; 1 = RLE → refusé),
//     w×h octets.
// Résultat : pointes (nom, w, h, bitmap 8-bit). Inclinaison/rotation
// propriétaires : absentes de l'enveloppe lue = ignorées + annoncées.

data class AbrTip(val name: String, val w: Int, val h: Int, val bits: ByteArray)

object AbrParser {
    fun parse(bytes: ByteArray): Pair<List<AbrTip>, List<String>> {
        val out = mutableListOf<AbrTip>()
        val notes = mutableListOf("dynamics ignored")
        val errs = mutableListOf<String>()
        try {
            var p = 0
            fun u16(): Int {
                if (p + 2 > bytes.size) throw Break()
                val v = ((bytes[p].toInt() and 0xFF) shl 8) or
                    (bytes[p + 1].toInt() and 0xFF)
                p += 2
                return v
            }
            fun u32(): Long {
                if (p + 4 > bytes.size) throw Break()
                val v = ((bytes[p].toLong() and 0xFF) shl 24) or
                    ((bytes[p + 1].toLong() and 0xFF) shl 16) or
                    ((bytes[p + 2].toLong() and 0xFF) shl 8) or
                    (bytes[p + 3].toLong() and 0xFF)
                p += 4
                return v
            }
            if (bytes.size < 4) {
                errs.add("truncated")
                return Pair(out, errs)
            }
            val version = u16()
            if (version != 1 && version != 2) {
                errs.add("version $version")
                return Pair(out, errs)
            }
            val count = u16()
            if (count > 64) {
                errs.add("count $count")
                return Pair(out, errs)
            }
            repeat(count) { k ->
                val type = u16()
                u32() // taille (ignorée)
                val top = u32()
                val left = u32()
                val bottom = u32()
                val right = u32()
                val depth = u16()
                val comp = u16()
                val w = (right - left).toInt()
                val h = (bottom - top).toInt()
                if (type != 2 || depth != 8 || comp != 0 ||
                    w <= 0 || h <= 0 || w > 256 || h > 256
                ) {
                    errs.add("tip $k unsupported")
                    throw Break()
                }
                if (p + w * h > bytes.size) throw Break()
                val bits = bytes.copyOfRange(p, p + w * h)
                p += w * h
                out.add(AbrTip("tip-${k + 1}", w, h, bits))
            }
            if (p != bytes.size) {
                errs.add("trailing ${bytes.size - p}B")
                out.clear()
            }
        } catch (b: Break) {
            if (errs.isEmpty()) errs.add("truncated")
            out.clear()
        } catch (_: Exception) {
            errs.add("unreadable")
            out.clear()
        }
        return Pair(out, (errs + notes).distinct())
    }

    private class Break : Exception()

    /** Fixture in-code : pointe 4×4 épinglée au centre. */
    fun fixtureTip(): ByteArray {
        val b = mutableListOf<Byte>()
        fun u16(v: Int) {
            b.add(((v shr 8) and 0xFF).toByte())
            b.add((v and 0xFF).toByte())
        }
        fun u32(v: Long) {
            b.add(((v shr 24) and 0xFF).toByte())
            b.add(((v shr 16) and 0xFF).toByte())
            b.add(((v shr 8) and 0xFF).toByte())
            b.add((v and 0xFF).toByte())
        }
        u16(1) // version
        u16(1) // count
        u16(2) // type échantillonnée
        u32(16 + 16) // taille (ignorée)
        u32(0); u32(0); u32(4); u32(4) // bornes 4×4
        u16(8) // profondeur
        u16(0) // brut
        for (y in 0 until 4) for (x in 0 until 4)
            b.add(if (x in 1..2 && y in 1..2) 255.toByte() else 0.toByte())
        return b.toByteArray()
    }
}

// ------------------------------------------------------- gradients.json
// Bibliothèque de dégradés app-side v1 (même pattern que
// color-sets.json §1.8) : [{name, stops:[...]}].

data class GradientEntry(val name: String, val stops: List<String>)

object GradientStore {
    fun file(dir: File): File = File(dir, "gradients.json")

    fun load(dir: File): List<GradientEntry> {
        return try {
            val f = file(dir)
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val stops = mutableListOf<String>()
                val ja = o.optJSONArray("stops") ?: return@mapNotNull null
                for (k in 0 until ja.length()) stops.add(ja.optString(k))
                if (stops.size < 2) return@mapNotNull null
                GradientEntry(
                    o.optString("name", "").take(48).ifEmpty { "gradient-$i" },
                    stops
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(dir: File, entries: List<GradientEntry>) {
        try {
            val arr = JSONArray()
            for (e in entries) {
                val ja = JSONArray()
                for (s in e.stops) ja.put(s)
                arr.put(JSONObject().put("name", e.name).put("stops", ja))
            }
            file(dir).writeText(arr.toString())
        } catch (_: Exception) {
        }
    }
}

// ------------------------------------------------------- accord (6.12)
// Transfert statistique source→cible (moyenne/écart-type par canal,
// Reinhard simplifié, from scratch) produisant une LUT appliquée via
// le canal ajustement `curves` existant (V+2) — 0 moteur neuf.

object ColorMatch {
    data class Stats(val mean: DoubleArray, val std: DoubleArray)

    fun stats(bmp: Bitmap): Stats {
        val w = bmp.width
        val h = bmp.height
        val n = IntArray(3)
        val sum = DoubleArray(3)
        val sq = DoubleArray(3)
        for (y in 0 until h step 2) {
            for (x in 0 until w step 2) {
                val px = bmp.getPixel(x, y)
                val a = (px ushr 24) and 0xFF
                if (a == 0) continue
                val ia = 255.0 / a
                val c = doubleArrayOf(
                    ((px shr 16) and 0xFF) * ia,
                    ((px shr 8) and 0xFF) * ia,
                    (px and 0xFF) * ia
                )
                for (k in 0..2) {
                    sum[k] += c[k]
                    sq[k] += c[k] * c[k]
                    n[k]++
                }
            }
        }
        val mean = DoubleArray(3)
        val std = DoubleArray(3)
        for (k in 0..2) {
            if (n[k] == 0) {
                mean[k] = 0.0
                std[k] = 1.0
            } else {
                mean[k] = sum[k] / n[k]
                val v = sq[k] / n[k] - mean[k] * mean[k]
                std[k] = sqrt(if (v > 0) v else 0.0).coerceAtLeast(1e-3)
            }
        }
        return Stats(mean, std)
    }

    /** LUT 256 par canal : (i − mS)·(σT/σS) + mT, clampée. */
    fun lut(src: Stats, dst: Stats): Array<IntArray> {
        return Array(3) { k ->
            val ratio = (dst.std[k] / src.std[k].coerceAtLeast(1e-3))
                .coerceIn(0.05, 20.0)
            IntArray(256) { i ->
                ((i - src.mean[k]) * ratio + dst.mean[k]).toInt()
                    .coerceIn(0, 255)
            }
        }
    }

    /** Distance moyenne par canal entre deux aplats (test §4.7). */
    fun meanDistance(a: Bitmap, b: Bitmap): Double {
        val w = minOf(a.width, b.width)
        val h = minOf(a.height, b.height)
        var sum = 0.0
        var n = 0
        for (y in 0 until h step 4) {
            for (x in 0 until w step 4) {
                val pa = a.getPixel(x, y)
                val pb = b.getPixel(x, y)
                sum += kotlin.math.abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF)) +
                    kotlin.math.abs(((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF)) +
                    kotlin.math.abs((pa and 0xFF) - (pb and 0xFF))
                n += 3
            }
        }
        return if (n == 0) 0.0 else sum / n
    }

    /** Fiche curves 16 points depuis une LUT (canal r|g|b). */
    fun curvesJson(lut: IntArray, channel: String): String {
        val pts = (0 until 16).joinToString(",") { k ->
            val i = (k * 255) / 15
            "[$i,${lut[i]}]"
        }
        return "{\"type\":\"curves\",\"points\":[$pts],\"channel\":\"$channel\"}"
    }
}

@Composable
fun ImportPanel(
    surface: EngineSurface,
    filesDir: File,
    cacheDir: File,
    onPickFile: (mime: String, cb: (File?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onStatus: (String) -> Unit,
    onChanged: () -> Unit
) {
    var library by remember { mutableStateOf(GradientStore.load(filesDir)) }
    // B2 §3: installed tips (persisted preset library).
    var tips by remember { mutableStateOf(AbrTipStore.load(filesDir)) }
    var note by remember { mutableStateOf("") }
    // Hoisted (stringResource is @Composable: never call it from the
    // file-picker callbacks below).
    val importOk = stringResource(R.string.import_ok)
    val importRefused = stringResource(R.string.import_refused)
    val importApprox = stringResource(R.string.import_approx)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.import_grd).substringBefore(" ("),
                    style = MaterialTheme.typography.titleMedium
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            onPickFile("*/*") { f ->
                                if (f == null) return@onPickFile
                                try {
                                    val (grads, msgs) =
                                        GrdParser.parse(f.readBytes())
                                    val approx = msgs.filter {
                                        it != "dynamics ignored"
                                    }
                                    note = if (grads.isEmpty()) {
                                        "✕ " + importRefused +
                                            msgs.joinToString(",")
                                    } else {
                                        "≈ " + importApprox +
                                            approx.joinToString(",")
                                    }
                                    if (grads.isNotEmpty()) {
                                        library = library + grads.map {
                                            GradientEntry(it.name, it.stops)
                                        }
                                        GradientStore.save(filesDir, library)
                                        onStatus(importOk)
                                    }
                                } catch (_: Exception) {
                                    note = "✕ " + importRefused
                                }
                            }
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("import-grd")
                    ) { Text(stringResource(R.string.import_grd)) }
                    Button(
                        onClick = {
                            onPickFile("*/*") { f ->
                                if (f == null) return@onPickFile
                                try {
                                    val (parsed, msgs) =
                                        AbrParser.parse(f.readBytes())
                                    if (parsed.isEmpty()) {
                                        note = "✕ " + importRefused +
                                            msgs.joinToString(",")
                                    } else {
                                        // B2 §3: install = usable presets.
                                        val installed = AbrTipStore.install(
                                            filesDir, parsed,
                                            f.name.substringBeforeLast('.')
                                        )
                                        tips = AbrTipStore.load(filesDir)
                                        note = "≈ " + importApprox +
                                            msgs.joinToString(",") +
                                            " · " + installed.size + "/" +
                                            parsed.size
                                        onStatus(importOk)
                                    }
                                } catch (_: Exception) {
                                    note = "✕ " + importRefused
                                }
                            }
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("import-abr")
                    ) { Text(stringResource(R.string.import_abr)) }
                }
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .testTag("import-note")
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .testTag("gradient-library")
                ) {
                    items(library, key = { it.name }) { g ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "${g.name} (${g.stops.size})",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    val idx = try {
                                        JSONObject(surface.layerState())
                                            .optInt("active", 0)
                                    } catch (_: Exception) {
                                        0
                                    }
                                    val stopsJson = g.stops.joinToString(",") { "\"$it\"" }
                                    val (cw, _) = surface.canvasDims()
                                    surface.setLayerFill(
                                        idx,
                                        "{\"kind\":\"gradient\",\"color\":\"#FFFFFF\"," +
                                            "\"mode\":\"linear\",\"x0\":0,\"y0\":0," +
                                            "\"x1\":$cw,\"y1\":0," +
                                            "\"stops\":[$stopsJson]}"
                                    )
                                    onChanged()
                                },
                                modifier = Modifier.testTag("gradient-use-${g.name}")
                            ) { Text(stringResource(R.string.fill_validate)) }
                        }
                    }
                }
                if (tips.isNotEmpty()) {
                    Text(
                        stringResource(R.string.abr_installed, tips.size),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .testTag("abr-presets")
                    ) {
                        items(tips, key = { it.name }) { t ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                InstalledTipPreview(t)
                                Text(
                                    "${t.name} (${t.w}×${t.h})",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = {
                                        if (surface.setBrushTip(t.path)) {
                                            onStatus(importOk)
                                        } else {
                                            onStatus(importRefused)
                                        }
                                    },
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("abr-use-${t.name}")
                                ) { Text(stringResource(R.string.abr_use)) }
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.abr_fidelity),
                        style = MaterialTheme.typography.labelSmall
                    )
                    TextButton(
                        onClick = { surface.setBrushTip(null) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("abr-tip-clear")
                    ) { Text(stringResource(R.string.abr_clear_tip)) }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

/** Aperçu d'une pointe installée (PNG alpha déjà sur disque). */
@Composable
fun InstalledTipPreview(tip: InstalledTip) {
    val bmp = remember(tip.path) { AbrTipStore.previewBitmap(tip) }
    androidx.compose.runtime.DisposableEffect(bmp) {
        onDispose { if (bmp != null && !bmp.isRecycled) bmp.recycle() }
    }
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = tip.name,
            modifier = Modifier
                .padding(end = 4.dp)
                .testTag("abr-preview-${tip.name}")
        )
    }
}

@Composable
fun ColorMatchDialog(
    surface: EngineSurface,
    onDismiss: () -> Unit,
    onStatus: (String) -> Unit,
    onChanged: () -> Unit
) {
    val layers = remember {
        try {
            val arr = JSONObject(surface.layerState()).optJSONArray("layers")
            (0 until (arr?.length() ?: 0)).map { i ->
                val e = arr!!.optJSONObject(i)
                val s = e?.optJSONObject("self") ?: e
                Pair(i, s?.optString("name", "Layer $i") ?: "Layer $i")
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
    var srcIdx by remember { mutableStateOf(layers.firstOrNull()?.first ?: 0) }
    var report by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.match_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "${stringResource(R.string.match_source)}: " +
                        (layers.firstOrNull { it.first == srcIdx }?.second ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                ) {
                    items(layers, key = { it.first }) { (i, name) ->
                        TextButton(
                            onClick = { srcIdx = i },
                            modifier = Modifier.testTag("match-src-$i")
                        ) {
                            Text(
                                (if (i == srcIdx) "[✓] " else "") + name,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                Text(
                    report,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("match-report")
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        onClick = {
                            try {
                                val srcBmp = surface.soloRenderFull(srcIdx)
                                val tgtIdx = try {
                                    JSONObject(surface.layerState())
                                        .optInt("active", 0)
                                } catch (_: Exception) {
                                    0
                                }
                                val tgtBmp = surface.soloRenderFull(tgtIdx)
                                if (srcBmp == null || tgtBmp == null) {
                                    report = "✕"
                                    return@Button
                                }
                                val before =
                                    ColorMatch.meanDistance(srcBmp, tgtBmp)
                                val luts = ColorMatch.lut(
                                    ColorMatch.stats(tgtBmp),
                                    ColorMatch.stats(srcBmp)
                                )
                                // 3 fiches curves (r|g|b) sur 3 calques
                                // d'ajustement — rééditables (spec §4.1).
                                val chans = arrayOf("r", "g", "b")
                                var okCount = 0
                                for (k in 0..2) {
                                    val idx = surface.addLayerNamed("match-${chans[k]}")
                                    if (idx < 0) continue
                                    if (surface.setLayerAdjustment(
                                            idx,
                                            ColorMatch.curvesJson(luts[k], chans[k])
                                        )
                                    ) okCount++
                                }
                                report = "Δ ${before.toInt()} → curves×$okCount"
                                srcBmp.recycle()
                                tgtBmp.recycle()
                                onChanged()
                            } catch (_: Exception) {
                                report = "✕"
                            }
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("match-apply")
                    ) { Text(stringResource(R.string.match_apply)) }
                }
            }
        }
    }
}

// ------------------------------------------------- installable tips
//
// B2 §3 (ARCHITECTURE.md "B2 additions") : les pointes lues deviennent
// des presets UTILISABLES. Chaque masque 8-bit est écrit en PNG RGBA
// (rgb=0, alpha=échantillon) dans filesDir/abr-tips + un index.json ;
// le moteur (nSetBrushTip) décode le PNG et estampille son alpha.
// Fidélité documentée : enveloppe v1/v2 échantillonnée brute seulement
// (dual brush / dynamics / texture / scatter ignorés et annoncés).

data class InstalledTip(
    val name: String,
    val w: Int,
    val h: Int,
    val path: String
)

object AbrTipStore {
    private const val INDEX = "index.json"

    fun dir(filesDir: File): File = File(filesDir, "abr-tips")

    private fun sanitize(s: String): String =
        s.take(40).map {
            if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_'
        }.joinToString("").ifEmpty { "tip" }

    /** Write every parsed tip as an RGBA PNG preset; returns the list. */
    fun install(
        filesDir: File,
        tips: List<AbrTip>,
        base: String
    ): List<InstalledTip> {
        val d = dir(filesDir)
        if (!d.exists()) d.mkdirs()
        val current = load(filesDir).toMutableList()
        val out = mutableListOf<InstalledTip>()
        for ((i, tip) in tips.withIndex()) {
            if (tip.w <= 0 || tip.h <= 0 ||
                tip.bits.size < tip.w * tip.h
            ) continue
            var name = "${sanitize(base)}-${i + 1}"
            var n = 2
            while (current.any { it.name == name }) {
                name = "${sanitize(base)}-${i + 1}-$n"
                n++
            }
            val bmp = android.graphics.Bitmap.createBitmap(
                tip.w, tip.h, android.graphics.Bitmap.Config.ARGB_8888
            )
            val px = IntArray(tip.w * tip.h) { k ->
                ((tip.bits[k].toInt() and 0xFF) shl 24) or 0x202020
            }
            bmp.setPixels(px, 0, tip.w, 0, 0, tip.w, tip.h)
            val f = File(d, "$name.png")
            try {
                f.outputStream().use {
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            } catch (_: Exception) {
                bmp.recycle()
                continue
            }
            bmp.recycle()
            val entry = InstalledTip(name, tip.w, tip.h, f.absolutePath)
            current.add(entry)
            out.add(entry)
        }
        save(filesDir, current)
        return out
    }

    fun load(filesDir: File): List<InstalledTip> {
        val idx = File(dir(filesDir), INDEX)
        if (!idx.isFile) return emptyList()
        return try {
            val arr = org.json.JSONArray(idx.readText())
            val out = mutableListOf<InstalledTip>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val p = o.optString("path", "")
                if (p.isEmpty() || !File(p).isFile) continue
                out.add(
                    InstalledTip(
                        o.optString("name", "tip"),
                        o.optInt("w", 0), o.optInt("h", 0), p
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(filesDir: File, tips: List<InstalledTip>) {
        val d = dir(filesDir)
        if (!d.exists()) d.mkdirs()
        val arr = org.json.JSONArray()
        for (t in tips) {
            arr.put(
                org.json.JSONObject()
                    .put("name", t.name)
                    .put("w", t.w)
                    .put("h", t.h)
                    .put("path", t.path)
            )
        }
        try {
            File(d, INDEX).writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    fun previewBitmap(tip: InstalledTip): android.graphics.Bitmap? = try {
        android.graphics.BitmapFactory.decodeFile(tip.path)
    } catch (_: Exception) {
        null
    }
}

/** Aperçu tampon d'une pointe .abr (boîte de dialogue d'import). */
@Composable
fun AbrTipPreview(tip: AbrTip) {
    val bmp = remember(tip) { abrTipBitmap(tip) }
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = tip.name,
            modifier = Modifier.testTag("abr-tip-${tip.name}")
        )
    }
}

private fun abrTipBitmap(tip: AbrTip): Bitmap? {
    return try {
        val b = Bitmap.createBitmap(tip.w, tip.h, Bitmap.Config.ALPHA_8)
        val px = IntArray(tip.w * tip.h) { i ->
            (tip.bits[i].toInt() and 0xFF) shl 24
        }
        b.setPixels(px, 0, tip.w, 0, 0, tip.w, tip.h)
        b
    } catch (_: Exception) {
        null
    }
}
