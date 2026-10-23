// OpenClip T5 — EPUB + export PDF + séquences (spec §6, app-side
// 100 %, 0 JNI). EPUB = ZIP from scratch via java.util.zip
// (mimetype STORED en premier — contrainte verrouillée par test) ;
// PDF = android.graphics.pdf.PdfDocument (API plateforme) ; séquences
// = nExportPngSeq T3 + ZIP app-side au niveau projet multipage.
package dev.openclip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.openclip.R
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** Charge la page n (1-based) dans la surface live (bloquant, testable). */
fun t5LoadPageBlocking(es: EngineSurface, dir: File, n: Int): Boolean {
    val f = File(dir, String.format(java.util.Locale.US, "%03d.ora", n))
    if (!f.exists()) return false
    var ok = false
    val latch = CountDownLatch(1)
    es.loadFrom(f.absolutePath) { loaded ->
        ok = loaded
        latch.countDown()
    }
    latch.await(30, TimeUnit.SECONDS)
    return ok
}

/** Sauvegarde la page live courante vers un ORA temporaire (restauration). */
fun t5StashLive(es: EngineSurface, cacheDir: File): File? {
    return try {
        val f = File.createTempFile("t5-live-", ".ora", cacheDir)
        var ok = false
        val latch = CountDownLatch(1)
        es.saveTo(f.absolutePath, "ora") { saved ->
            ok = saved
            latch.countDown()
        }
        latch.await(30, TimeUnit.SECONDS)
        if (ok) f else null
    } catch (_: Exception) {
        null
    }
}

private fun t5PageCount(dir: File): Int {
    return try {
        val (_, pages) = V2Pages.readProject(dir)
        if (pages.isNotEmpty()) pages.size
        else dir.listFiles { f -> f.name.matches(Regex("\\d{3}\\.ora")) }?.size ?: 0
    } catch (_: Exception) {
        0
    }
}

private fun t5CollectPages(
    es: EngineSurface,
    dir: File,
    cacheDir: File,
    folio: T5Folio,
    crop: T5Crop?,
    dpi: Float,
    total: Int
): List<Bitmap> {
    val stash = t5StashLive(es, cacheDir)
    val out = mutableListOf<Bitmap>()
    try {
        for (n in 1..total) {
            if (!t5LoadPageBlocking(es, dir, n)) continue
            Thread.sleep(300)
            var bmp = es.pullExportBitmap() ?: continue
            if (folio.style != "off") {
                val f = t5RenderFolio(bmp, n, total, folio)
                if (f !== bmp) {
                    bmp.recycle()
                    bmp = f
                }
            }
            if (crop != null) {
                val c = t5RenderCrop(bmp, dpi, crop, folio, n, total)
                bmp.recycle()
                bmp = c
            }
            out.add(bmp)
        }
    } finally {
        if (stash != null) {
            val latch = CountDownLatch(1)
            es.loadFrom(stash.absolutePath) { latch.countDown() }
            latch.await(30, TimeUnit.SECONDS)
            try {
                stash.delete()
            } catch (_: Exception) {
            }
        }
    }
    return out
}

private fun t5XmlEscape(s: String): String {
    return s.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")
}

/**
 * EPUB (spec §6.1) : ZIP from scratch — mimetype NON-compressé en
 * PREMIER, container.xml + OPF (métadonnées §5 + spine dans l'ordre +
 * page-progression-direction depuis reading) + 1 XHTML par page + PNG.
 */
fun t5ExportEpub(
    es: EngineSurface,
    dir: File,
    cacheDir: File,
    dst: File,
    folio: T5Folio,
    crop: T5Crop?,
    work: T5Work,
    reading: String,
    dpi: Float
): Boolean {
    val total = t5PageCount(dir)
    if (total <= 0) return false
    val pages = t5CollectPages(es, dir, cacheDir, folio, crop, dpi, total)
    if (pages.isEmpty()) return false
    try {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(dst))).use { zip ->
            // mimetype premier + STORED (contrainte du format).
            val mime = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val crc = CRC32()
            crc.update(mime)
            val me = ZipEntry("mimetype")
            me.method = ZipEntry.STORED
            me.size = mime.size.toLong()
            me.crc = crc.value
            zip.putNextEntry(me)
            zip.write(mime)
            zip.closeEntry()
            fun stored(name: String, data: ByteArray) {
                val e = ZipEntry(name)
                zip.putNextEntry(e)
                zip.write(data)
                zip.closeEntry()
            }
            stored(
                "META-INF/container.xml",
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<container version=\"1.0\" " +
                    "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">" +
                    "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" " +
                    "media-type=\"application/oebps-package+xml\"/>" +
                    "</rootfiles></container>")
                    .toByteArray(Charsets.UTF_8)
            )
            val manifest = StringBuilder()
            val spine = StringBuilder()
            for (n in 1..pages.size) {
                manifest.append("<item id=\"p$n\" href=\"page$n.xhtml\" " +
                    "media-type=\"application/xhtml+xml\"/>")
                manifest.append("<item id=\"img$n\" href=\"img$n.png\" " +
                    "media-type=\"image/png\"/>")
                spine.append("<itemref idref=\"p$n\"/>")
            }
            val rtl = if (reading == "rtl") "rtl" else "ltr"
            stored(
                "OEBPS/content.opf",
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<package version=\"2.0\" " +
                    "xmlns=\"http://www.idpf.org/2007/opf\" " +
                    "unique-identifier=\"bookid\">" +
                    "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
                    "<dc:title>${t5XmlEscape(work.titre.ifEmpty { "openclip" })}</dc:title>" +
                    "<dc:creator>${t5XmlEscape(work.auteur)}</dc:creator>" +
                    "<dc:language>${t5XmlEscape(work.langue.ifEmpty { "fr" })}</dc:language>" +
                    "<dc:description>${t5XmlEscape(work.resume)}</dc:description>" +
                    "</metadata><manifest>$manifest</manifest>" +
                    "<spine page-progression-direction=\"$rtl\">$spine</spine>" +
                    "</package>").toByteArray(Charsets.UTF_8)
            )
            for (n in 1..pages.size) {
                val folioTxt = t5FolioText(n, pages.size, folio.style)
                stored(
                    "OEBPS/page$n.xhtml",
                    ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\" " +
                        "\"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd\">\n" +
                        "<html xmlns=\"http://www.w3.org/1999/xhtml\">" +
                        "<head><title>Page $n</title></head><body>" +
                        "<div><img src=\"img$n.png\" alt=\"Page $n\"/>" +
                        (if (folioTxt != null) "<p>$folioTxt</p>" else "") +
                        "</div></body></html>")
                        .toByteArray(Charsets.UTF_8)
                )
                val img = File.createTempFile("t5-epub-$n-", ".png", cacheDir)
                try {
                    img.outputStream().use { pages[n - 1].compress(Bitmap.CompressFormat.PNG, 100, it) }
                    stored("OEBPS/img$n.png", img.readBytes())
                } finally {
                    img.delete()
                }
            }
        }
        return true
    } catch (_: Exception) {
        return false
    } finally {
        for (b in pages) {
            try {
                b.recycle()
            } catch (_: Exception) {
            }
        }
    }
}

/** Validation EPUB (test §6.7) : ZIP lisible + OPF spine = n. */
fun t5ValidateEpub(
    f: File
): Triple<Boolean, Boolean, Int> {
    // (zipOk, mimetypeFirstStored, spineCount)
    return try {
        ZipFile(f).use { zip ->
            val entries = zip.entries().toList()
            if (entries.isEmpty()) return Triple(false, false, 0)
            val first = entries[0]
            val mimeOk = first.name == "mimetype" &&
                first.method == ZipEntry.STORED
            val opf = zip.getEntry("OEBPS/content.opf") ?: return Triple(true, mimeOk, 0)
            val txt = zip.getInputStream(opf).bufferedReader().readText()
            val spine = Regex("<itemref").findAll(txt).count()
            // XHTML bien-formés (parse strict).
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val builder = factory.newDocumentBuilder()
            for (e in entries) {
                if (!e.name.endsWith(".xhtml")) continue
                zip.getInputStream(e).use { builder.parse(it) }
            }
            Triple(true, mimeOk, spine)
        }
    } catch (_: Exception) {
        Triple(false, false, 0)
    }
}

/**
 * Export PDF (spec §6.1) : android.graphics.pdf.PdfDocument, 1 page
 * PDF par page .ocpages/, taille depuis dims canvas + dpi (points =
 * px × 72/dpi), folio/repères selon opt-ins. Raster par page
 * (annoncé) ; l'import reste page 1.
 */
fun t5ExportPdf(
    es: EngineSurface,
    dir: File,
    cacheDir: File,
    dst: File,
    folio: T5Folio,
    crop: T5Crop?,
    dpi: Float
): Boolean {
    val total = t5PageCount(dir)
    if (total <= 0) return false
    val pages = t5CollectPages(es, dir, cacheDir, folio, crop, dpi, total)
    if (pages.isEmpty()) return false
    val doc = PdfDocument()
    try {
        val safeDpi = if (dpi > 0) dpi else 160f
        for (b in pages) {
            val wPt = (b.width * 72f / safeDpi).toInt().coerceAtLeast(1)
            val hPt = (b.height * 72f / safeDpi).toInt().coerceAtLeast(1)
            val info = PdfDocument.PageInfo.Builder(wPt, hPt, 1).create()
            val page = doc.startPage(info)
            page.canvas.drawBitmap(
                Bitmap.createScaledBitmap(b, wPt, hPt, true),
                0f, 0f, null
            )
            doc.finishPage(page)
        }
        FileOutputStream(dst).use { doc.writeTo(it) }
        return true
    } catch (_: Exception) {
        return false
    } finally {
        doc.close()
        for (b in pages) {
            try {
                b.recycle()
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * Séquence multipage (spec §6.1) : nExportPngSeq par page (holds
 * éclatés, caméra T3 incluse) + ZIP app-side
 * (préfixe pNN_frame_MMMM.png).
 */
fun t5ExportSequence(
    es: EngineSurface,
    dir: File,
    cacheDir: File,
    dstZip: File
): Boolean {
    val total = t5PageCount(dir)
    if (total <= 0) return false
    val stash = t5StashLive(es, cacheDir)
    val collected = mutableListOf<Pair<String, File>>()
    try {
        for (n in 1..total) {
            if (!t5LoadPageBlocking(es, dir, n)) continue
            Thread.sleep(300)
            val seqDir = File(cacheDir, "t5-seq-$n")
            seqDir.mkdirs()
            if (!es.exportPngSeq(
                    seqDir.absolutePath,
                    "{\"expandHolds\":true,\"prefix\":\"frame\"}"
                )
            ) {
                seqDir.deleteRecursively()
                continue
            }
            val files = seqDir.listFiles { f ->
                f.name.matches(Regex("frame_\\d+\\.png"))
            }?.sortedBy { it.name } ?: emptyList()
            for ((k, f) in files.withIndex()) {
                val name = String.format(
                    java.util.Locale.US, "p%02d_frame_%04d.png", n, k + 1
                )
                collected.add(Pair(name, f))
            }
        }
        if (collected.isEmpty()) return false
        ZipOutputStream(BufferedOutputStream(FileOutputStream(dstZip))).use { zip ->
            for ((name, f) in collected) {
                zip.putNextEntry(ZipEntry(name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return true
    } catch (_: Exception) {
        return false
    } finally {
        for ((_, f) in collected) {
            try {
                f.parentFile?.deleteRecursively()
            } catch (_: Exception) {
            }
        }
        if (stash != null) {
            val latch = CountDownLatch(1)
            es.loadFrom(stash.absolutePath) { latch.countDown() }
            latch.await(30, TimeUnit.SECONDS)
            try {
                stash.delete()
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * Stickers APNG (spec §6.1) : 1 APNG par page (matte + ≤512px,
 * preset T3 §4).
 */
fun t5ExportStickers(
    es: EngineSurface,
    dir: File,
    cacheDir: File,
    outDir: File
): Int {
    val total = t5PageCount(dir)
    if (total <= 0) return 0
    outDir.mkdirs()
    val stash = t5StashLive(es, cacheDir)
    var n = 0
    try {
        for (p in 1..total) {
            if (!t5LoadPageBlocking(es, dir, p)) continue
            Thread.sleep(300)
            val dst = File(
                outDir,
                String.format(java.util.Locale.US, "p%02d.apng", p)
            )
            if (es.exportApng(
                    dst.absolutePath,
                    "{\"loop\":-1,\"maxWidth\":512,\"matte\":\"#FFFFFF\"}"
                )
            ) n++
        }
    } finally {
        if (stash != null) {
            val latch = CountDownLatch(1)
            es.loadFrom(stash.absolutePath) { latch.countDown() }
            latch.await(30, TimeUnit.SECONDS)
            try {
                stash.delete()
            } catch (_: Exception) {
            }
        }
    }
    return n
}

/** Fanzine (spec §5.1) : imposition → PDF (1 page PDF par côté). */
fun t5ExportFanzine(
    es: EngineSurface,
    dir: File,
    cacheDir: File,
    dpi: Float
): String {
    val total = t5PageCount(dir)
    if (total <= 0) return "empty"
    val (work, folio, crop) = T5Project.read(dir)
    val (sheets, pad) = t5Impose(total)
    val stash = t5StashLive(es, cacheDir)
    // Render each page once (folio ON for books, crop OFF inside
    // signatures — trim happens on the imposed sheet).
    val rendered = mutableMapOf<Int, Bitmap>()
    try {
        for (n in 1..total) {
            if (!t5LoadPageBlocking(es, dir, n)) continue
            Thread.sleep(300)
            var bmp = es.pullExportBitmap() ?: continue
            if (folio.style != "off") {
                val f = t5RenderFolio(bmp, n, total, folio)
                if (f !== bmp) {
                    bmp.recycle()
                    bmp = f
                }
            }
            rendered[n] = bmp
        }
        fun side(a: Int, b: Int): Bitmap {
            val w = es.canvasDims().first
            val h = es.canvasDims().second
            val out = Bitmap.createBitmap(w * 2, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawColor(0xFFFFFFFF.toInt())
            val pa = Paint()
            val ba = rendered[a]
            if (ba != null && !ba.isRecycled) c.drawBitmap(ba, 0f, 0f, pa)
            val bb = rendered[b]
            if (bb != null && !bb.isRecycled) c.drawBitmap(bb, w.toFloat(), 0f, pa)
            return out
        }
        val doc = PdfDocument()
        try {
            val safeDpi = if (dpi > 0) dpi else 160f
            for (sh in sheets) {
                for ((a, b) in arrayOf(
                    Pair(sh.frontLeft, sh.frontRight),
                    Pair(sh.backRight, sh.backLeft)
                )
                ) {
                    val bmp = side(a, b)
                    val wPt = (bmp.width * 72f / safeDpi).toInt().coerceAtLeast(1)
                    val hPt = (bmp.height * 72f / safeDpi).toInt().coerceAtLeast(1)
                    val info = PdfDocument.PageInfo.Builder(wPt, hPt, 1).create()
                    val page = doc.startPage(info)
                    page.canvas.drawBitmap(
                        Bitmap.createScaledBitmap(bmp, wPt, hPt, true),
                        0f, 0f, null
                    )
                    doc.finishPage(page)
                    bmp.recycle()
                }
            }
            val dst = File(
                cacheDir,
                "fanzine-${System.currentTimeMillis()}.pdf"
            )
            FileOutputStream(dst).use { doc.writeTo(it) }
            doc.close()
            return "ok:${sheets.size}+$pad:${dst.absolutePath}"
        } catch (_: Exception) {
            doc.close()
            return "failed"
        }
    } finally {
        for (b in rendered.values) {
            try {
                b.recycle()
            } catch (_: Exception) {
            }
        }
        if (stash != null) {
            val latch = CountDownLatch(1)
            es.loadFrom(stash.absolutePath) { latch.countDown() }
            latch.await(30, TimeUnit.SECONDS)
            try {
                stash.delete()
            } catch (_: Exception) {
            }
        }
    }
}

@Composable
fun T5ExportDialog(
    surface: EngineSurface,
    docKey: String,
    cacheDir: File,
    dpi: Float,
    onDismiss: () -> Unit,
    onExportFile: (kind: String, cacheFile: File, suggestedName: String) -> Unit,
    onStatus: (String) -> Unit
) {
    var format by remember { mutableStateOf("epub") }
    var folioOn by remember { mutableStateOf(true) }
    var cropOn by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "EPUB / PDF / Séquence",
                    style = MaterialTheme.typography.titleMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        "epub" to R.string.export_epub,
                        "pdf" to R.string.export_pdf,
                        "sequence" to R.string.export_sequence,
                        "stickers" to R.string.export_sticker_pages
                    ).forEach { (f, res) ->
                        androidx.compose.material3.FilterChip(
                            selected = format == f,
                            onClick = { format = f },
                            label = { Text(stringResource(res)) },
                            modifier = Modifier.testTag("t5export-$f")
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = folioOn,
                        onCheckedChange = { folioOn = it },
                        modifier = Modifier.testTag("t5export-folio")
                    )
                    Text(
                        stringResource(R.string.export_folio_opt),
                        style = MaterialTheme.typography.bodySmall
                    )
                    androidx.compose.material3.Checkbox(
                        checked = cropOn,
                        onCheckedChange = { cropOn = it },
                        modifier = Modifier.testTag("t5export-crop")
                    )
                    Text(
                        stringResource(R.string.export_crop_opt),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    progress,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("t5export-progress")
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(onClick = { if (!busy) onDismiss() }) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            progress = "…"
                            Thread {
                                try {
                                    val dir = V2Pages.projectDir(cacheDir, docKey)
                                    val (work, folio, crop) = T5Project.read(dir)
                                    val folioEff = if (folioOn) folio
                                    else folio.copy(style = "off")
                                    val cropEff = if (cropOn) crop else null
                                    val stamp = System.currentTimeMillis()
                                    when (format) {
                                        "epub" -> {
                                            val dst = File(
                                                cacheDir,
                                                "book-$stamp.epub"
                                            )
                                            val ok = t5ExportEpub(
                                                surface, dir, cacheDir, dst,
                                                folioEff, cropEff, work,
                                                work.sens, dpi
                                            )
                                            if (ok) onExportFile(
                                                "epub", dst, "book-$stamp.epub"
                                            )
                                            else onStatus("EPUB ✕")
                                        }
                                        "pdf" -> {
                                            val dst = File(
                                                cacheDir, "book-$stamp.pdf"
                                            )
                                            val ok = t5ExportPdf(
                                                surface, dir, cacheDir, dst,
                                                folioEff, cropEff, dpi
                                            )
                                            if (ok) onExportFile(
                                                "pdf", dst, "book-$stamp.pdf"
                                            )
                                            else onStatus("PDF ✕")
                                        }
                                        "sequence" -> {
                                            val dst = File(
                                                cacheDir, "seq-$stamp.zip"
                                            )
                                            val ok = t5ExportSequence(
                                                surface, dir, cacheDir, dst
                                            )
                                            if (ok) onExportFile(
                                                "sequence", dst, "seq-$stamp.zip"
                                            )
                                            else onStatus("ZIP ✕")
                                        }
                                        "stickers" -> {
                                            val outDir = File(
                                                cacheDir, "stickers-$stamp"
                                            )
                                            val n = t5ExportStickers(
                                                surface, dir, cacheDir, outDir
                                            )
                                            if (n > 0) {
                                                val dst = File(
                                                    cacheDir,
                                                    "stickers-$stamp.zip"
                                                )
                                                ZipOutputStream(
                                                    BufferedOutputStream(
                                                        FileOutputStream(dst)
                                                    )
                                                ).use { zip ->
                                                    outDir.listFiles()?.forEach { f ->
                                                        zip.putNextEntry(
                                                            ZipEntry(f.name)
                                                        )
                                                        f.inputStream().use {
                                                            it.copyTo(zip)
                                                        }
                                                        zip.closeEntry()
                                                    }
                                                }
                                                outDir.deleteRecursively()
                                                onExportFile(
                                                    "stickers", dst,
                                                    "stickers-$stamp.zip"
                                                )
                                            } else onStatus("APNG ✕")
                                        }
                                    }
                                } catch (_: Exception) {
                                    onStatus("✕")
                                } finally {
                                    busy = false
                                }
                            }.start()
                        },
                        modifier = Modifier.testTag("t5export-go")
                    ) { Text(stringResource(R.string.export_apply)) }
                }
            }
        }
    }
}
