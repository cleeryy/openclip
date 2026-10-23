// OpenClip T5 — pages (spec §5, app-side 100 %, 0 JNI).
// project.json v2 (v1-tolérant : champs absents = défauts), foliotation
// (rendue à l'export), infos œuvre, vue double (lecture seule), repères
// de coupe (overlay + opt-in export), fanzine (imposition 4 pages).
package dev.openclip.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import org.json.JSONObject

// ------------------------------------------------------- project.json v2
// v1 = {version,reading,pages} (MultipageProject). v2 ajoute work,
// folio, crop, startPage — absents = défauts, jamais cassé (spec §5.4).

data class T5Work(
    val titre: String = "",
    val auteur: String = "",
    val annee: String = "",
    val resume: String = "",
    val langue: String = "",
    val sens: String = "ltr"
)

data class T5Folio(
    val style: String = "off", // off|n|total|roman
    val corner: String = "bottom-center" // tl|tr|bl|br|bottom-center
)

data class T5Crop(val bleedMm: Float = 3f, val marginMm: Float = 10f)

object T5Project {
    fun read(dir: File): Triple<T5Work, T5Folio, T5Crop> {
        return try {
            val f = File(dir, "project.json")
            if (!f.exists()) return Triple(T5Work(), T5Folio(), T5Crop())
            val o = JSONObject(f.readText())
            val w = o.optJSONObject("work")
            val fl = o.optJSONObject("folio")
            val c = o.optJSONObject("crop")
            Triple(
                T5Work(
                    w?.optString("titre", "") ?: "",
                    w?.optString("auteur", "") ?: "",
                    w?.optString("annee", "") ?: "",
                    w?.optString("resume", "") ?: "",
                    w?.optString("langue", "") ?: "",
                    o.optString("reading", "ltr")
                ),
                T5Folio(
                    fl?.optString("style", "off") ?: "off",
                    fl?.optString("corner", "bottom-center")
                        ?: "bottom-center"
                ),
                T5Crop(
                    c?.optDouble("bleedMm", 3.0)?.toFloat() ?: 3f,
                    c?.optDouble("marginMm", 10.0)?.toFloat() ?: 10f
                )
            )
        } catch (_: Exception) {
            Triple(T5Work(), T5Folio(), T5Crop())
        }
    }

    fun startPage(dir: File): String {
        return try {
            JSONObject(File(dir, "project.json").readText())
                .optString("startPage", "left").takeIf {
                    it == "left" || it == "right"
                } ?: "left"
        } catch (_: Exception) {
            "left"
        }
    }

    /** Merge v2 keys into the existing project.json (v1 keys kept). */
    fun write(
        dir: File,
        work: T5Work,
        folio: T5Folio,
        crop: T5Crop,
        startPage: String
    ) {
        try {
            val f = File(dir, "project.json")
            val o = if (f.exists()) JSONObject(f.readText()) else JSONObject()
            o.put("version", 2)
            o.put(
                "work", JSONObject()
                    .put("titre", work.titre.take(120))
                    .put("auteur", work.auteur.take(120))
                    .put("annee", work.annee.take(16))
                    .put("resume", work.resume.take(2000))
                    .put("langue", work.langue.take(16))
            )
            o.put(
                "folio", JSONObject()
                    .put("style", folio.style)
                    .put("corner", folio.corner)
            )
            o.put(
                "crop", JSONObject()
                    .put("bleedMm", crop.bleedMm.toDouble())
                    .put("marginMm", crop.marginMm.toDouble())
            )
            o.put(
                "startPage",
                if (startPage == "right") "right" else "left"
            )
            f.writeText(o.toString())
        } catch (_: Exception) {
        }
    }
}

// ---------------------------------------------------------------- folio
fun t5Roman(n: Int): String {
    if (n <= 0) return ""
    val table = arrayOf(
        1000 to "M", 900 to "CM", 500 to "D", 400 to "CD",
        100 to "C", 90 to "XC", 50 to "L", 40 to "XL",
        10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I"
    )
    var v = n
    val sb = StringBuilder()
    for ((k, s) in table) {
        while (v >= k) {
            sb.append(s)
            v -= k
        }
    }
    return sb.toString()
}

fun t5FolioText(n: Int, total: Int, style: String): String? {
    return when (style) {
        "n" -> "$n"
        "total" -> "$n/$total"
        "roman" -> t5Roman(n)
        else -> null
    }
}

/** Render folio at export (never into ORA pixels — spec §5.1). */
fun t5RenderFolio(
    base: Bitmap,
    n: Int,
    total: Int,
    folio: T5Folio
): Bitmap {
    val txt = t5FolioText(n, total, folio.style) ?: return base
    val out = base.copy(Bitmap.Config.ARGB_8888, true)
    val c = Canvas(out)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = 0xFF000000.toInt()
    paint.textSize = (out.height / 24f).coerceAtLeast(12f)
    val w = paint.measureText(txt)
    val m = out.width / 40f
    val (x, y) = when (folio.corner) {
        "tl" -> Pair(m, m - paint.ascent())
        "tr" -> Pair(out.width - m - w, m - paint.ascent())
        "bl" -> Pair(m, out.height - m)
        "br" -> Pair(out.width - m - w, out.height - m)
        else -> Pair(
            (out.width - w) / 2f,
            out.height - m
        )
    }
    c.drawText(txt, x, y, paint)
    return out
}

// ---------------------------------------------------------------- crop
/**
 * Crop-mark overlays in engine px (bleed + trim + safe zone, from mm
 * via dpi). Preview via es.v2Overlays; rendered at export on opt-in.
 */
fun t5CropOverlays(
    w: Int,
    h: Int,
    dpi: Float,
    bleedMm: Float,
    marginMm: Float
): List<List<Pair<Float, Float>>> {
    val pxMm = dpi / 25.4f
    val bleed = bleedMm * pxMm
    val margin = marginMm * pxMm
    fun rect(x0: Float, y0: Float, x1: Float, y1: Float) =
        listOf(
            Pair(x0, y0), Pair(x1, y0), Pair(x1, y1),
            Pair(x0, y1), Pair(x0, y0)
        )
    // Trim (page box) + bleed (outer) + safe (inner).
    return listOf(
        rect(0f, 0f, w.toFloat(), h.toFloat()),
        rect(-bleed, -bleed, w + bleed, h + bleed),
        rect(margin, margin, w - margin, h - margin)
    )
}

/** Render opt-in crop marks onto an export bitmap (spec §5.1). */
fun t5RenderCrop(
    base: Bitmap,
    dpi: Float,
    crop: T5Crop,
    folio: T5Folio,
    n: Int,
    total: Int
): Bitmap {
    val pxMm = dpi / 25.4f
    val bleed = (crop.bleedMm * pxMm).toInt().coerceAtLeast(0)
    val out = Bitmap.createBitmap(
        base.width + bleed * 2, base.height + bleed * 2,
        Bitmap.Config.ARGB_8888
    )
    val c = Canvas(out)
    c.drawColor(0xFFFFFFFF.toInt())
    c.drawBitmap(base, bleed.toFloat(), bleed.toFloat(), null)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = 0xFF000000.toInt()
    paint.strokeWidth = 1f
    // Trim ticks at the four corners (length = 4× bleed).
    val len = (bleed * 4).toFloat()
    val x0 = bleed.toFloat()
    val y0 = bleed.toFloat()
    val x1 = (bleed + base.width).toFloat()
    val y1 = (bleed + base.height).toFloat()
    val ticks = arrayOf(
        floatArrayOf(x0, y0 - len, x0, y0),
        floatArrayOf(x0 - len, y0, x0, y0),
        floatArrayOf(x1, y0 - len, x1, y0),
        floatArrayOf(x1, y0, x1 + len, y0),
        floatArrayOf(x0, y1, x0, y1 + len),
        floatArrayOf(x0 - len, y1, x0, y1),
        floatArrayOf(x1, y1, x1, y1 + len),
        floatArrayOf(x1, y1, x1 + len, y1)
    )
    for (t in ticks) c.drawLine(t[0], t[1], t[2], t[3], paint)
    val txt = t5FolioText(n, total, folio.style)
    if (txt != null) {
        paint.textSize = (base.height / 24f).coerceAtLeast(12f)
        c.drawText(txt, x0, y1 + bleed / 2f + paint.textSize / 2, paint)
    }
    return out
}

// -------------------------------------------------------------- fanzine
// A5-sur-A4 / A6-sur-A5 imposition (spec §5.1) : signatures de 4 —
// recto [dernière, première], verso tourné à 180° [deuxième,
// avant-dernière] ; bourrage de pages blanches proposé (jamais
// silencieux : padWhite compte les blanches ajoutées).

data class T5Sheet(
    val frontLeft: Int, // 1-based, -1 = blanche
    val frontRight: Int,
    val backLeft: Int,
    val backRight: Int
)

fun t5Impose(total: Int): Pair<List<T5Sheet>, Int> {
    if (total <= 0) return Pair(emptyList(), 0)
    val padded = ((total + 3) / 4) * 4
    val pad = padded - total
    val sheets = mutableListOf<T5Sheet>()
    var s = 0
    while (s * 4 < padded) {
        fun pg(k: Int): Int = if (k in 1..total) k else -1
        sheets.add(
            T5Sheet(
                frontLeft = pg(padded - 2 * s),
                frontRight = pg(2 * s + 1),
                backLeft = pg(2 * s + 2),
                backRight = pg(padded - 2 * s - 1)
            )
        )
        s++
    }
    return Pair(sheets, pad)
}

@Composable
fun T5PagesPanel(
    surface: EngineSurface,
    docKey: String,
    cacheDir: File,
    dpi: Float,
    onDismiss: () -> Unit,
    onStatus: (String) -> Unit,
    onChanged: () -> Unit,
    onSharePdf: (File) -> Unit = {}
) {
    val dir = remember(docKey) { V2Pages.projectDir(cacheDir, docKey) }
    var work by remember { mutableStateOf(T5Project.read(dir).first) }
    var folio by remember { mutableStateOf(T5Project.read(dir).second) }
    var crop by remember { mutableStateOf(T5Project.read(dir).third) }
    var startPage by remember { mutableStateOf(T5Project.startPage(dir)) }
    var doubleView by remember { mutableStateOf(false) }
    var showFanzine by remember { mutableStateOf(false) }

    fun persist() {
        T5Project.write(dir, work, folio, crop, startPage)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp) {
            LazyColumn(modifier = Modifier.padding(16.dp)) {
                item {
                    Text(
                        stringResource(R.string.pages_work),
                        style = MaterialTheme.typography.titleMedium
                    )
                    val fields = listOf(
                        Triple(R.string.work_title, work.titre) { v: String -> work = work.copy(titre = v) },
                        Triple(R.string.work_author, work.auteur) { v: String -> work = work.copy(auteur = v) },
                        Triple(R.string.work_year, work.annee) { v: String -> work = work.copy(annee = v) },
                        Triple(R.string.work_language, work.langue) { v: String -> work = work.copy(langue = v) }
                    )
                    fields.forEach { (res, v, set) ->
                        OutlinedTextField(
                            value = v,
                            onValueChange = { set(it); persist() },
                            label = { Text(stringResource(res)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    OutlinedTextField(
                        value = work.resume,
                        onValueChange = { work = work.copy(resume = it); persist() },
                        label = { Text(stringResource(R.string.work_summary)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                }
                item {
                    Text(
                        stringResource(R.string.pages_folio),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("off", "n", "total", "roman").forEach { st ->
                            FilterChip(
                                selected = folio.style == st,
                                onClick = {
                                    folio = folio.copy(style = st)
                                    persist()
                                },
                                label = {
                                    Text(
                                        when (st) {
                                            "off" -> stringResource(R.string.folio_off)
                                            "n" -> "{n}"
                                            "total" -> "{n}/{total}"
                                            else -> stringResource(R.string.folio_roman)
                                        }
                                    )
                                },
                                modifier = Modifier.testTag("folio-$st")
                            )
                        }
                    }
                }
                item {
                    Text(
                        stringResource(R.string.pages_crop),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${crop.bleedMm}mm",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f)
                        )
                        Slider(
                            value = crop.bleedMm,
                            onValueChange = {
                                crop = crop.copy(bleedMm = it)
                                persist()
                            },
                            valueRange = 0f..10f,
                            modifier = Modifier.weight(2f)
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = doubleView,
                            onClick = {
                                doubleView = !doubleView
                                if (!doubleView) surface.v2Overlays = emptyList()
                            },
                            label = { Text(stringResource(R.string.pages_double)) },
                            modifier = Modifier.testTag("double-view-toggle")
                        )
                        FilterChip(
                            selected = startPage == "right",
                            onClick = {
                                startPage = if (startPage == "right") "left" else "right"
                                persist()
                            },
                            label = { Text("start:$startPage") }
                        )
                    }
                }
                if (doubleView) {
                    item {
                        T5DoubleView(surface, dir, dpi)
                    }
                }
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.cancel))
                        }
                        Button(
                            onClick = { showFanzine = true },
                            modifier = Modifier.testTag("fanzine-open")
                        ) { Text(stringResource(R.string.pages_fanzine)) }
                    }
                }
            }
        }
    }
    if (showFanzine) {
        FanzineDialog(
            surface = surface,
            docKey = docKey,
            cacheDir = cacheDir,
            dpi = dpi,
            onDismiss = { showFanzine = false },
            onStatus = onStatus,
            onChanged = onChanged,
            onSharePdf = onSharePdf
        )
    }
}

@Composable
private fun T5DoubleView(
    surface: EngineSurface,
    dir: File,
    dpi: Float
) {
    // Lecture seule : composite courant + vignette N+1 (thumbs/).
    // Zoom/pan synchrones (même surface) ; l'édition = retour simple
    // (annoncé — le dialogue se ferme à la moindre action).
    val (cw, _) = surface.canvasDims()
    val pages = remember {
        V2Pages.readProject(dir).second.size.coerceAtLeast(1)
    }
    val next = remember { surface.pullExportBitmap() }
    val nextThumb = remember {
        try {
            val f = File(File(dir, "thumbs"), "p2.png")
            if (f.exists()) android.graphics.BitmapFactory.decodeFile(f.absolutePath)
            else null
        } catch (_: Exception) {
            null
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("double-view")
    ) {
        if (next != null && !next.isRecycled) {
            Image(
                bitmap = next.asImageBitmap(),
                contentDescription = "N",
                modifier = Modifier
                    .weight(1f)
                    .height(160.dp)
            )
        }
        if (nextThumb != null && !nextThumb.isRecycled) {
            Image(
                bitmap = nextThumb.asImageBitmap(),
                contentDescription = "N+1",
                modifier = Modifier
                    .weight(1f)
                    .height(160.dp)
            )
        } else {
            Text(
                "N+1 —",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun FanzineDialog(
    surface: EngineSurface,
    docKey: String,
    cacheDir: File,
    dpi: Float,
    onDismiss: () -> Unit,
    onStatus: (String) -> Unit,
    onChanged: () -> Unit,
    onSharePdf: (File) -> Unit = {}
) {
    val dir = remember(docKey) { V2Pages.projectDir(cacheDir, docKey) }
    val total = remember { V2Pages.readProject(dir).second.size.coerceAtLeast(1) }
    val (sheets, pad) = remember(total) { t5Impose(total) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.pages_fanzine),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    if (pad > 0) "+$pad ⚪" else "${sheets.size} × 4",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("fanzine-map")
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                ) {
                    items(sheets, key = { "${it.frontLeft}-${it.frontRight}" }) { sh ->
                        Text(
                            "[${sh.frontLeft}|${sh.frontRight}] ↻ [${sh.backLeft}|${sh.backRight}]",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        onClick = {
                            val res = t5ExportFanzine(
                                surface, dir, cacheDir, dpi
                            )
                            onStatus(res)
                            if (res.startsWith("ok:")) {
                                val path = res.substringAfterLast(":")
                                val f = File(path)
                                if (f.exists()) onSharePdf(f)
                            }
                            onChanged()
                            onDismiss()
                        },
                        modifier = Modifier.testTag("fanzine-export")
                    ) { Text(stringResource(R.string.pages_impose)) }
                }
            }
        }
    }
}
