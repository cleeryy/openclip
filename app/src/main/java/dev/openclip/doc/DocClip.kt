// OpenClip .clip import palier (a) — app-side only, no new JNI symbols.
//
// Clean-room compatibility reader for Clip Studio Paint ".clip" files,
// written from scratch from publicly documented structure (clip-d SPEC,
// clipfile docs, opnd.cc viewer): a CSFCHUNK big-endian envelope holding
// a CHNKSQLi embedded-SQLite payload plus optional external chunks.
// No Celsys code, strings, assets or binaries are used or reproduced.
//
// Scope (honest-subset policy, same as the M4 PSD baseline): raster
// layers only. Tile blobs we can positively identify (zlib of straight
// RGBA8, optional explicit "bgra8" layout tag) become ORA layers with
// name/visibility/opacity/blend; anything else (unknown layouts, vector,
// text, corrections, animation) is skipped and counted in the report.
// When no raster tile decodes, the CanvasPreview PNG is imported as a
// single layer (previewOnly = true). Anything unparseable is refused
// with a reason string, never guessed, never a crash.
//
// Pipeline: .clip --(this object)--> multi-layer .ora --(existing
// nLoadDocument)--> engine canvas. SQLite comes from the Android
// platform (domain public, no new dependency); zlib from java.util.zip.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.doc

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.Inflater

object DocClip {

    // ---- public result ----

    data class ClipReport(
        val layerCount: Int,
        val width: Int,
        val height: Int,
        val previewOnly: Boolean,
        val approximations: List<String>
    )

    data class ClipImport(val oraFile: File, val report: ClipReport)

    /** Null-free: success carries the converted ORA, failure the reason. */
    sealed interface Outcome {
        data class Ok(val import: ClipImport) : Outcome
        data class Err(val reason: String) : Outcome
    }

    // ---- bounds (hostile-file policy) ----

    private const val MAX_FILE = 256L * 1024 * 1024
    private const val MAX_SIDE = 16384
    private const val MAX_CHUNKS = 8192
    private const val MAX_TILES = 4096

    private val ENVELOPE_MAGIC = "CSFCHUNK".toByteArray(Charsets.US_ASCII)
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    // Chunk tags observed in public docs (8 ASCII bytes each).
    private const val TAG_SQLI = "CHNKSQLi"
    private const val TAG_HEAD = "CHNKHead"
    private const val TAG_EXTA = "CHNKExta"
    private const val TAG_FOOT = "CHNKFoot"

    /**
     * Convert [clipFile] to a multi-layer ORA written under [workDir].
     * Never throws; every hostile input ends in [Outcome.Err].
     */
    fun importToOra(clipFile: File, workDir: File): Outcome {
        try {
            if (!clipFile.isFile) return Outcome.Err("not a file")
            if (clipFile.length() <= 0 || clipFile.length() > MAX_FILE) {
                return Outcome.Err("file size out of range")
            }
            val bytes = try {
                clipFile.readBytes()
            } catch (_: Exception) {
                return Outcome.Err("unreadable file")
            }
            val chunks = splitEnvelope(bytes) ?: return Outcome.Err(
                "not a CSFCHUNK container (unknown framing)"
            )
            val dbPayload = firstSqlitePayload(chunks)
                ?: return Outcome.Err("no embedded SQLite database found")
            if (!workDir.isDirectory && !workDir.mkdirs()) {
                return Outcome.Err("work dir unavailable")
            }
            val approx = mutableListOf<String>()

            // --- embedded SQLite, strictly read-only ---
            val dbFile = File(workDir, "clip-${System.nanoTime()}.db")
            try {
                dbFile.writeBytes(dbPayload)
            } catch (_: Exception) {
                return Outcome.Err("cannot stage database")
            }
            try {
                val db = try {
                    SQLiteDatabase.openDatabase(
                        dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
                    )
                } catch (_: Exception) {
                    return Outcome.Err("embedded database unreadable")
                }
                try {
                    return buildOra(db, workDir, approx)
                } finally {
                    try {
                        db.close()
                    } catch (_: Exception) {
                    }
                }
            } finally {
                try {
                    dbFile.delete()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
            return Outcome.Err("import failed safely")
        }
    }

    // ---- envelope ----

    /** tag -> payloads. Null when the framing is not recognized. */
    private fun splitEnvelope(bytes: ByteArray): Map<String, MutableList<ByteArray>>? {
        if (bytes.size < 8) return null
        if (!bytes.startsWith(ENVELOPE_MAGIC)) return null
        val out = mutableMapOf<String, MutableList<ByteArray>>()
        var off = 8
        var count = 0
        while (off < bytes.size) {
            if (count++ > MAX_CHUNKS) return null
            if (off + 12 > bytes.size) break // trailing padding tolerated
            val tag = bytes.sliceAscii(off, 8) ?: break
            val len = bytes.beU32(off + 8)
            if (len < 0 || off + 12 + len > bytes.size) return null
            out.getOrPut(tag) { mutableListOf() }
                .add(bytes.copyOfRange(off + 12, off + 12 + len))
            off += 12 + len
            if (tag == TAG_FOOT) break
        }
        if (out.isEmpty()) return null
        return out
    }

    /** First CHNKSQLi payload, else carve for the SQLite magic. */
    private fun firstSqlitePayload(
        chunks: Map<String, MutableList<ByteArray>>
    ): ByteArray? {
        val sqli = chunks[TAG_SQLI]?.firstOrNull()
        if (sqli != null) {
            val carved = carveSqlite(sqli) ?: if (sqli.startsWith(SQLITE_MAGIC)) sqli else null
            if (carved != null) return carved
        }
        // Tolerate real-world files that stash the DB in another chunk.
        val order = listOf(TAG_HEAD, TAG_EXTA) + chunks.keys
        for (tag in order.distinct()) {
            for (payload in chunks[tag].orEmpty()) {
                val carved = carveSqlite(payload)
                if (carved != null) return carved
            }
        }
        return null
    }

    private fun carveSqlite(payload: ByteArray): ByteArray? {
        if (payload.size < 100) return null
        if (payload.startsWith(SQLITE_MAGIC)) return payload
        // Bounded carve: magic hunt over the first 1 MiB, then SQLite page
        // size validation from the header (offsets 16..17, big-endian).
        val limit = minOf(payload.size - SQLITE_MAGIC.size, 1024 * 1024)
        var i = 0
        while (i < limit) {
            if (payload[i] == 'S'.code.toByte() && payload.startsWithAt(i, SQLITE_MAGIC)) {
                val page = ((payload[i + 16].toInt() and 0xFF) shl 8) or
                    (payload[i + 17].toInt() and 0xFF)
                val pageSize = if (page == 1) 65536 else page
                if (pageSize in listOf(512, 1024, 2048, 4096, 8192, 16384, 32768)) {
                    return payload.copyOfRange(i, payload.size)
                }
                return null
            }
            i++
        }
        return null
    }

    // ---- SQLite --> ORA ----

    private fun buildOra(
        db: SQLiteDatabase,
        workDir: File,
        approx: MutableList<String>
    ): Outcome {
        val tables: Set<String> = try {
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table'", null
            ).use { c ->
                val s = mutableSetOf<String>()
                while (c.moveToNext()) s.add(c.getString(0))
                s
            }
        } catch (_: Exception) {
            return Outcome.Err("database table list unreadable")
        }

        var w = 0
        var h = 0
        val size = readCanvasSize(db, tables)
        if (size != null) {
            w = size.first
            h = size.second
        }

        // Raster layers first; preview fallback when none decode.
        val layers = readLayers(db, tables, approx)
        if (layers.isNotEmpty()) {
            if (w <= 0 || h <= 0) {
                val ext = layerExtents(layers)
                w = ext.first
                h = ext.second
            }
            if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE) {
                return Outcome.Err("canvas size out of range")
            }
            approx.add(0, "layer order assumed bottom-first (table order)")
            val ora = File(workDir, "clip-${System.nanoTime()}.ora")
            val oraLayers = layers.map {
                DocOra.OraLayer(it.name, it.bitmap, it.opacity, it.visible, it.blendSvg)
            }
            if (!DocOra.writeLayersOra(ora, w, h, oraLayers)) {
                layers.forEach { safeRecycle(it.bitmap) }
                return Outcome.Err("ORA conversion failed")
            }
            layers.forEach { safeRecycle(it.bitmap) }
            return Outcome.Ok(
                ClipImport(ora, ClipReport(layers.size, w, h, false, approx.toList()))
            )
        }

        // Fallback: CanvasPreview PNG as a single layer.
        val preview = readPreview(db, tables, w, h)
            ?: return Outcome.Err("no decodable raster or preview found")
        w = preview.width
        h = preview.height
        if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE) {
            safeRecycle(preview.bitmap)
            return Outcome.Err("preview size out of range")
        }
        approx.add("preview only: raster tiles unreadable, CanvasPreview used")
        val ora = File(workDir, "clip-${System.nanoTime()}-preview.ora")
        val single = listOf(
            DocOra.OraLayer("Preview", preview.bitmap, 1f, true, "svg:normal")
        )
        if (!DocOra.writeLayersOra(ora, w, h, single)) {
            safeRecycle(preview.bitmap)
            return Outcome.Err("ORA conversion failed")
        }
        safeRecycle(preview.bitmap)
        return Outcome.Ok(ClipImport(ora, ClipReport(1, w, h, true, approx.toList())))
    }

    // ---- canvas size ----

    private fun readCanvasSize(db: SQLiteDatabase, tables: Set<String>): Pair<Int, Int>? {
        val tableCandidates = listOf("Canvas", "Project", "Document", "Image")
        val wCols = listOf("width", "canvasWidth", "imageWidth", "w")
        val hCols = listOf("height", "canvasHeight", "imageHeight", "h")
        for (t in tableCandidates) {
            if (!tables.contains(t)) continue
            for (wc in wCols) {
                for (hc in hCols) {
                    val v = try {
                        db.rawQuery("SELECT $wc, $hc FROM $t LIMIT 1", null).use { c ->
                            if (!c.moveToFirst()) null
                            else Pair(c.getInt(0), c.getInt(1))
                        }
                    } catch (_: Exception) {
                        null
                    }
                    if (v != null && v.first in 1..MAX_SIDE && v.second in 1..MAX_SIDE) {
                        return v
                    }
                }
            }
        }
        return null
    }

    // ---- layers ----

    private data class RawLayer(
        val name: String,
        val bitmap: Bitmap,
        val opacity: Float,
        val visible: Boolean,
        val blendSvg: String
    )

    private data class TileRow(
        val layerKey: String,
        val x: Int, val y: Int, val w: Int, val h: Int,
        val layout: String,
        val blob: ByteArray
    )

    private fun readLayers(
        db: SQLiteDatabase,
        tables: Set<String>,
        approx: MutableList<String>
    ): List<RawLayer> {
        val layerTable = listOf("Layer", "Layers").firstOrNull { tables.contains(it) }
            ?: return emptyList()
        val tileTable = listOf("ExternalChunk", "Offscreen", "Tile", "LayerTile", "Chunk")
            .firstOrNull { tables.contains(it) } ?: return emptyList()

        data class Meta(
            val key: String, val name: String,
            val opacity: Float, val visible: Boolean, val blendSvg: String,
            val order: Long
        )

        val metas = mutableListOf<Meta>()
        try {
            db.rawQuery("SELECT rowid, * FROM $layerTable LIMIT 256", null).use { c ->
                val names = (0 until c.columnCount).map { c.getColumnName(it) }
                val idCol = pickCol(names, listOf("id", "layerId", "uuid"))
                val nameCol = pickCol(names, listOf("name", "layerName", "title"))
                val opCol = pickCol(names, listOf("opacity", "layerOpacity"))
                val visCol = pickCol(names, listOf("visible", "visibility", "isVisible", "hidden"))
                val blendCol = pickCol(names, listOf("blend", "composite", "blendMode", "mode", "layerComposite"))
                val orderCol = pickCol(names, listOf("order", "zIndex", "sortOrder", "index"))
                var row = 0L
                while (c.moveToNext() && metas.size < 256) {
                    row++
                    val key = if (idCol != null) {
                        try {
                            c.getString(idCol)
                        } catch (_: Exception) {
                            "row$row"
                        }
                    } else "row$row"
                    val rawName = if (nameCol != null) {
                        try {
                            c.getString(nameCol)
                        } catch (_: Exception) {
                            null
                        }
                    } else null
                    val name = rawName?.take(128)?.ifBlank { "Layer" } ?: "Layer"
                    val opacity = readOpacity(c, opCol)
                    val visible = readVisible(c, visCol)
                    val blend = readBlend(c, blendCol, approx)
                    val order = if (orderCol != null) {
                        try {
                            c.getLong(orderCol)
                        } catch (_: Exception) {
                            row
                        }
                    } else row
                    metas.add(Meta(key ?: "row$row", name, opacity, visible, blend, order))
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        if (metas.isEmpty()) return emptyList()

        val tiles = readTiles(db, tileTable)
        if (tiles.isEmpty()) return emptyList()

        val byLayer = tiles.groupBy { it.layerKey }
        val out = mutableListOf<RawLayer>()
        // Bottom-first: ascending explicit order, else table order.
        val ordered = metas.sortedBy { it.order }
        for (m in ordered) {
            val rows = byLayer[m.key]
                ?: if (metas.size == 1) tiles else continue
            val bmp = assembleLayer(rows, approx) ?: continue
            out.add(RawLayer(m.name, bmp, m.opacity, m.visible, m.blendSvg))
        }
        return out
    }

    private fun readTiles(db: SQLiteDatabase, table: String): List<TileRow> {
        val out = mutableListOf<TileRow>()
        try {
            db.rawQuery("SELECT rowid, * FROM $table LIMIT $MAX_TILES", null).use { c ->
                val names = (0 until c.columnCount).map { c.getColumnName(it) }
                val layerCol = pickCol(names, listOf("layerId", "layer", "id", "ownerId"))
                val xCol = pickCol(names, listOf("x", "tileX", "left"))
                val yCol = pickCol(names, listOf("y", "tileY", "top"))
                val wCol = pickCol(names, listOf("w", "width", "tileWidth"))
                val hCol = pickCol(names, listOf("h", "height", "tileHeight"))
                val blobCol = pickCol(names, listOf("data", "blob", "pixels", "chunk", "image"))
                val layoutCol = pickCol(names, listOf("layout", "format", "pixelFormat"))
                if (blobCol == null) return emptyList()
                while (c.moveToNext() && out.size < MAX_TILES) {
                    val blob = try {
                        c.getBlob(blobCol)
                    } catch (_: Exception) {
                        null
                    }
                    if (blob == null || blob.isEmpty() || blob.size > 64 * 1024 * 1024) continue
                    fun col(col: Int?, def: Int): Int {
                        if (col == null) return def
                        return try {
                            c.getInt(col)
                        } catch (_: Exception) {
                            def
                        }
                    }
                    val layout = if (layoutCol != null) {
                        try {
                            c.getString(layoutCol)
                        } catch (_: Exception) {
                            null
                        }
                    } else null
                    val key = if (layerCol != null) {
                        try {
                            c.getString(layerCol)
                        } catch (_: Exception) {
                            null
                        }
                    } else null
                    out.add(
                        TileRow(
                            key ?: "",
                            col(xCol, 0), col(yCol, 0),
                            col(wCol, 0), col(hCol, 0),
                            (layout ?: "rgba8").lowercase(Locale.US),
                            blob
                        )
                    )
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return out
    }

    /** Inflate + paste tiles onto a transparent canvas-sized bitmap. */
    private fun assembleLayer(
        rows: List<TileRow>,
        approx: MutableList<String>
    ): Bitmap? {
        // Canvas size unknown here: infer from tile extents, capped.
        var extW = 0
        var extH = 0
        for (t in rows) {
            if (t.w in 1..MAX_SIDE && t.h in 1..MAX_SIDE &&
                t.x in 0..MAX_SIDE && t.y in 0..MAX_SIDE
            ) {
                extW = maxOf(extW, t.x + t.w)
                extH = maxOf(extH, t.y + t.h)
            }
        }
        if (extW <= 0 || extH <= 0 || extW > MAX_SIDE || extH > MAX_SIDE) return null
        val bmp: Bitmap = try {
            Bitmap.createBitmap(extW, extH, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) {
            return null
        }
        var painted = 0
        for (t in rows) {
            if (t.w <= 0 || t.h <= 0 || t.w > MAX_SIDE || t.h > MAX_SIDE) continue
            if (t.x < 0 || t.y < 0 || t.x >= extW || t.y >= extH) continue
            val px = decodeTile(t, approx) ?: continue
            val cw = minOf(t.w, extW - t.x)
            val ch = minOf(t.h, extH - t.y)
            try {
                // Later rows win on overlap (documented).
                val sub = if (cw == t.w && ch == t.h) px
                else IntArray(cw * ch) { i -> px[(i / cw) * t.w + (i % cw)] }
                bmp.setPixels(sub, 0, cw, t.x, t.y, cw, ch)
                painted++
            } catch (_: Exception) {
            }
        }
        if (painted == 0) {
            safeRecycle(bmp)
            return null
        }
        return bmp
    }

    /** zlib -> straight RGBA8 (or explicit bgra8). Null when unrecognized. */
    private fun decodeTile(t: TileRow, approx: MutableList<String>): IntArray? {
        val want = t.w.toLong() * t.h.toLong() * 4L
        if (want <= 0 || want > MAX_SIDE.toLong() * MAX_SIDE) return null
        val raw: ByteArray = try {
            inflateBounded(t.blob, want.toInt())
        } catch (_: Exception) {
            return null
        } ?: return null
        if (raw.size.toLong() != want) {
            noteOnce(approx, "tile layout ${raw.size}B != ${t.w}x${t.h} RGBA8: skipped")
            return null
        }
        val px = IntArray(t.w * t.h)
        val bgra = t.layout.contains("bgra")
        if (!t.layout.contains("rgba") && !bgra) {
            noteOnce(approx, "unknown tile layout '${t.layout}': skipped")
            return null
        }
        for (i in px.indices) {
            val r = raw[i * 4].toInt() and 0xFF
            val g = raw[i * 4 + 1].toInt() and 0xFF
            val b = raw[i * 4 + 2].toInt() and 0xFF
            val a = raw[i * 4 + 3].toInt() and 0xFF
            px[i] = if (!bgra) (a shl 24) or (r shl 16) or (g shl 8) or b
            else (a shl 24) or (b shl 16) or (g shl 8) or r
        }
        return px
    }

    private fun inflateBounded(deflated: ByteArray, want: Int): ByteArray? {
        val inf = Inflater()
        try {
            inf.setInput(deflated)
            val out = ByteArray(want)
            var total = 0
            while (!inf.finished() && total < want) {
                val n = try {
                    inf.inflate(out, total, want - total)
                } catch (_: Exception) {
                    return null
                }
                if (n <= 0) break
                total += n
            }
            if (total != want) return null
            return out
        } finally {
            try {
                inf.end()
            } catch (_: Exception) {
            }
        }
    }

    // ---- preview ----

    private data class Preview(val bitmap: Bitmap, val width: Int, val height: Int)

    private fun readPreview(
        db: SQLiteDatabase,
        tables: Set<String>,
        knownW: Int,
        knownH: Int
    ): Preview? {
        val table = listOf("CanvasPreview", "Preview", "Thumbnail", "CanvasImage")
            .firstOrNull { tables.contains(it) } ?: return null
        try {
            db.rawQuery("SELECT rowid, * FROM $table LIMIT 8", null).use { c ->
                val names = (0 until c.columnCount).map { c.getColumnName(it) }
                val blobCol = pickCol(names, listOf("png", "data", "image", "preview", "bytes", "blob"))
                    ?: return null
                while (c.moveToNext()) {
                    val blob = try {
                        c.getBlob(blobCol)
                    } catch (_: Exception) {
                        null
                    }
                    if (blob == null || blob.size < 8) continue
                    if (!(blob[0] == 0x89.toByte() && blob[1] == 'P'.code.toByte() &&
                            blob[2] == 'N'.code.toByte() && blob[3] == 'G'.code.toByte())
                    ) continue
                    if (blob.size > 64 * 1024 * 1024) continue
                    val opts = BitmapFactory.Options()
                    opts.inJustDecodeBounds = true
                    BitmapFactory.decodeByteArray(blob, 0, blob.size, opts)
                    val bw = if (knownW in 1..MAX_SIDE) knownW else opts.outWidth
                    val bh = if (knownH in 1..MAX_SIDE) knownH else opts.outHeight
                    if (bw !in 1..MAX_SIDE || bh !in 1..MAX_SIDE) continue
                    val bmp = try {
                        BitmapFactory.decodeByteArray(blob, 0, blob.size)
                    } catch (_: Exception) {
                        null
                    }
                    if (bmp == null || bmp.width <= 0 || bmp.height <= 0) continue
                    val sized = if (bmp.width == bw && bmp.height == bh) bmp
                    else try {
                        Bitmap.createScaledBitmap(bmp, bw, bh, true).also { safeRecycle(bmp) }
                    } catch (_: Exception) {
                        safeRecycle(bmp)
                        continue
                    }
                    return Preview(sized, bw, bh)
                }
            }
        } catch (_: Exception) {
            return null
        }
        return null
    }

    // ---- tolerant column readers ----

    private fun pickCol(names: List<String>, cands: List<String>): Int? {
        for (c in cands) {
            val i = names.indexOfFirst { it.equals(c, ignoreCase = true) }
            if (i >= 0) return i
        }
        return null
    }

    private fun readOpacity(c: android.database.Cursor, col: Int?): Float {
        if (col == null) return 1f
        return try {
            val v = c.getDouble(col)
            val f = when {
                v.isNaN() -> 1f
                v < 0 -> 0f
                v <= 1 -> v.toFloat()
                v <= 100 -> (v / 100).toFloat()
                v <= 255 -> (v / 255).toFloat()
                else -> 1f
            }
            f.coerceIn(0f, 1f)
        } catch (_: Exception) {
            1f
        }
    }

    private fun readVisible(c: android.database.Cursor, col: Int?): Boolean {
        if (col == null) return true
        return try {
            val s = c.getString(col)
            if (s != null) {
                !(s.equals("hidden", true) || s == "0" || s.equals("false", true))
            } else c.getInt(col) != 0
        } catch (_: Exception) {
            true
        }
    }

    private fun readBlend(
        c: android.database.Cursor,
        col: Int?,
        approx: MutableList<String>
    ): String {
        if (col == null) return "svg:normal"
        val raw: String? = try {
            c.getString(col)
        } catch (_: Exception) {
            null
        }
        if (raw != null) {
            val norm = raw.removePrefix("svg:").lowercase(Locale.US).trim()
            val known = setOf(
                "normal", "multiply", "screen", "overlay",
                "plus", "add", "soft-light", "soft_light", "softlight"
            )
            if (known.contains(norm)) {
                return if (norm.startsWith("soft")) "svg:soft-light"
                else "svg:$norm"
            }
            if (norm.isNotEmpty() && norm != "0") {
                noteOnce(approx, "blend '$raw' mapped to normal")
            }
            return "svg:normal"
        }
        // Integer composite code: only 0 (normal) is trusted.
        val code: Int = try {
            c.getInt(col)
        } catch (_: Exception) {
            return "svg:normal"
        }
        if (code != 0) noteOnce(approx, "blend code $code mapped to normal")
        return "svg:normal"
    }

    // ---- byte helpers ----

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    private fun ByteArray.startsWithAt(off: Int, prefix: ByteArray): Boolean {
        if (off < 0 || off + prefix.size > size) return false
        for (i in prefix.indices) if (this[off + i] != prefix[i]) return false
        return true
    }

    private fun ByteArray.sliceAscii(off: Int, len: Int): String? {
        if (off < 0 || off + len > size) return null
        for (i in 0 until len) {
            val b = this[off + i].toInt()
            if (b < 0x20 || b > 0x7E) return null
        }
        return String(copyOfRange(off, off + len), Charsets.US_ASCII)
    }

    private fun ByteArray.beU32(off: Int): Int {
        if (off < 0 || off + 4 > size) return -1
        val v = ((this[off].toLong() and 0xFF) shl 24) or
            ((this[off + 1].toLong() and 0xFF) shl 16) or
            ((this[off + 2].toLong() and 0xFF) shl 8) or
            (this[off + 3].toLong() and 0xFF)
        return if (v > Int.MAX_VALUE) -1 else v.toInt()
    }

    private fun layerExtents(layers: List<RawLayer>): Pair<Int, Int> {
        var w = 0
        var h = 0
        for (l in layers) {
            w = maxOf(w, l.bitmap.width)
            h = maxOf(h, l.bitmap.height)
        }
        return Pair(w, h)
    }

    private fun noteOnce(approx: MutableList<String>, note: String) {
        if (!approx.contains(note)) approx.add(note)
    }

    private fun safeRecycle(b: Bitmap) {
        try {
            if (!b.isRecycled) b.recycle()
        } catch (_: Exception) {
        }
    }

    /** PNG bytes of [bmp] for the ORA writer (Bitmap -> PNG stays app-side). */
    internal fun pngBytes(bmp: Bitmap): ByteArray? {
        return try {
            val bos = ByteArrayOutputStream()
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)) null
            else bos.toByteArray()
        } catch (_: Exception) {
            null
        }
    }
}
