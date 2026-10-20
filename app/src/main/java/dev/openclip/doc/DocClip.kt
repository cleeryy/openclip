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
        val approximations: List<String>,
        val vectorLayers: Int = 0,
        val textLayers: Int = 0
    ) {
        /** Visible-degradation counter shown in the import banner. */
        val degradedCount: Int get() = approximations.size
    }

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

        // Raster layers first, then house-rasterized vector/text
        // (palier b, degraded-explicit); preview fallback when nothing decodes.
        val layers = readLayers(db, tables, approx, w, h)
        if (layers.layers.isNotEmpty()) {
            if (w <= 0 || h <= 0) {
                val ext = layerExtents(layers.layers)
                w = ext.first
                h = ext.second
            }
            if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE) {
                return Outcome.Err("canvas size out of range")
            }
            approx.add(0, "layer order assumed bottom-first (table order)")
            val ora = File(workDir, "clip-${System.nanoTime()}.ora")
            val oraLayers = layers.layers.map {
                DocOra.OraLayer(it.name, it.bitmap, it.opacity, it.visible, it.blendSvg)
            }
            if (!DocOra.writeLayersOra(ora, w, h, oraLayers)) {
                layers.layers.forEach { safeRecycle(it.bitmap) }
                return Outcome.Err("ORA conversion failed")
            }
            layers.layers.forEach { safeRecycle(it.bitmap) }
            return Outcome.Ok(
                ClipImport(
                    ora,
                    ClipReport(
                        layers.layers.size, w, h, false, approx.toList(),
                        layers.vectorLayers, layers.textLayers
                    )
                )
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

    private data class LayersResult(
        val layers: List<RawLayer>,
        val vectorLayers: Int,
        val textLayers: Int
    )

    private fun readLayers(
        db: SQLiteDatabase,
        tables: Set<String>,
        approx: MutableList<String>,
        canvasW: Int,
        canvasH: Int
    ): LayersResult {
        val layerTable = listOf("Layer", "Layers").firstOrNull { tables.contains(it) }
        val tileTable = listOf("ExternalChunk", "Offscreen", "Tile", "LayerTile", "Chunk")
            .firstOrNull { tables.contains(it) }

        data class Meta(
            val key: String, val name: String,
            val opacity: Float, val visible: Boolean, val blendSvg: String,
            val order: Long, val kind: LayerKind
        )

        // Declared layer kinds: a "type"-ish column classifies raster vs
        // vector vs text when present; unknown values stay UNKNOWN and
        // every meta still gets a raster shot first (tolerant order).
        val metas = mutableListOf<Meta>()
        if (layerTable != null) {
            try {
                db.rawQuery("SELECT rowid, * FROM $layerTable LIMIT 256", null).use { c ->
                    val names = (0 until c.columnCount).map { c.getColumnName(it) }
                    val idCol = pickCol(names, listOf("id", "layerId", "uuid"))
                    val nameCol = pickCol(names, listOf("name", "layerName", "title"))
                    val opCol = pickCol(names, listOf("opacity", "layerOpacity"))
                    val visCol = pickCol(names, listOf("visible", "visibility", "isVisible", "hidden"))
                    val blendCol = pickCol(names, listOf("blend", "composite", "blendMode", "mode", "layerComposite"))
                    val orderCol = pickCol(names, listOf("order", "zIndex", "sortOrder", "index"))
                    val typeCol = pickCol(names, listOf("type", "kind", "layerType", "layerKind", "category"))
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
                        val kind = if (typeCol != null) {
                            val t = try {
                                c.getString(typeCol)
                            } catch (_: Exception) {
                                null
                            }
                            classifyKind(t)
                        } else LayerKind.UNKNOWN
                        metas.add(Meta(key ?: "row$row", name, opacity, visible, blend, order, kind))
                    }
                }
            } catch (_: Exception) {
                return LayersResult(emptyList(), 0, 0)
            }
        }
        // No Layer table but vector/text side tables exist: synthesize one
        // meta per distinct side-table key so orphan geometry still imports.
        if (metas.isEmpty()) {
            for ((i, key) in collectOrphanKeys(db, tables).take(256).withIndex()) {
                metas.add(Meta(key, "Layer", 1f, true, "svg:normal", (i + 1).toLong(), LayerKind.UNKNOWN))
            }
        }
        if (metas.isEmpty()) return LayersResult(emptyList(), 0, 0)

        val byLayer: Map<String, List<TileRow>> = if (tileTable != null) {
            readTiles(db, tileTable).groupBy { it.layerKey }
        } else emptyMap()

        val out = mutableListOf<RawLayer>()
        var vecCount = 0
        var txtCount = 0
        // Bottom-first: ascending explicit order, else table order.
        val ordered = metas.sortedBy { it.order }
        val singleKey = metas.size == 1
        for (m in ordered) {
            val rows = byLayer[m.key]
                ?: if (singleKey) byLayer.values.firstOrNull() else null
            val bmp = if (rows != null) assembleLayer(rows, approx) else null
            if (bmp != null) {
                out.add(RawLayer(m.name, bmp, m.opacity, m.visible, m.blendSvg))
                continue
            }
            // Palier (b): house rasterization of validated geometry.
            // Text first would shadow vector when both claim a key; try
            // vector first only when the kind says so, else text first.
            val vecFirst = m.kind == LayerKind.VECTOR
            val tried = if (vecFirst) {
                tryVector(
                    m.key, m.name, m.opacity, m.visible, m.blendSvg,
                    db, tables, singleKey, canvasW, canvasH, approx, out
                )?.also { vecCount++ }
                    ?: tryText(
                        m.key, m.name, m.opacity, m.visible, m.blendSvg,
                        db, tables, singleKey, canvasW, canvasH, approx, out
                    )?.also { txtCount++ }
            } else {
                tryText(
                    m.key, m.name, m.opacity, m.visible, m.blendSvg,
                    db, tables, singleKey, canvasW, canvasH, approx, out
                )?.also { txtCount++ }
                    ?: tryVector(
                        m.key, m.name, m.opacity, m.visible, m.blendSvg,
                        db, tables, singleKey, canvasW, canvasH, approx, out
                    )?.also { vecCount++ }
            }
            if (tried != null) continue
            if (m.kind == LayerKind.VECTOR) {
                noteOnce(approx, "vector layer '${m.name.take(32)}' uninterpretable: skipped")
            } else if (m.kind == LayerKind.TEXT) {
                noteOnce(approx, "text layer '${m.name.take(32)}' uninterpretable: skipped")
            }
        }
        return LayersResult(out, vecCount, txtCount)
    }

    private enum class LayerKind { RASTER, VECTOR, TEXT, UNKNOWN }

    private fun classifyKind(raw: String?): LayerKind {
        if (raw == null) return LayerKind.UNKNOWN
        val t = raw.lowercase(Locale.US)
        return when {
            t.contains("vector") || t.contains("line") || t.contains("curve") ||
                t.contains("path") || t.contains("bezier") -> LayerKind.VECTOR
            t.contains("text") || t.contains("font") || t.contains("type") -> LayerKind.TEXT
            t.contains("raster") || t.contains("pixel") || t.contains("bitmap") -> LayerKind.RASTER
            else -> LayerKind.UNKNOWN
        }
    }

    /** Distinct layer keys referenced only by vector/text side tables. */
    private fun collectOrphanKeys(db: SQLiteDatabase, tables: Set<String>): List<String> {
        val keys = linkedSetOf<String>()
        val sideTables = tables.filter {
            it.contains("vector", ignoreCase = true) || it.contains("text", ignoreCase = true)
        }.take(8)
        for (t in sideTables) {
            try {
                db.rawQuery("SELECT rowid, * FROM $t LIMIT 512", null).use { c ->
                    val names = (0 until c.columnCount).map { c.getColumnName(it) }
                    val keyCol = pickCol(
                        names,
                        listOf("layerId", "layer", "ownerId", "parentId", "layerKey", "id")
                    )
                    if (keyCol == null) return@use
                    while (c.moveToNext() && keys.size < 256) {
                        val k = try {
                            c.getString(keyCol)
                        } catch (_: Exception) {
                            null
                        }
                        if (!k.isNullOrBlank() && k.length <= 128) keys.add(k)
                    }
                }
            } catch (_: Exception) {
            }
        }
        return keys.toList()
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

    // ---- palier (b): vector/text in visible degradation ----
    //
    // Geometry comes from the public-shape side tables (VectorObjectList
    // and text tables observed in clipfile/docs): strokes as validated
    // point lists, text as plain UTF-8 strings. Everything Celsys-owned
    // that we cannot reproduce (brush texture, bezier handles, fonts,
    // paragraph/transform attributes) is ignored and COUNTED in approx.
    // Brush rendering is ours: polylines on a platform Canvas (like M6),
    // text via platform Paint with the system font. Never throws.

    private const val MAX_PATHS = 256
    private const val MAX_PTS = 512
    private const val MAX_TEXT_LEN = 4096
    private const val MAX_TEXT_LINES = 32
    private const val COORD_LIMIT = 65536f

    private data class VecPath(
        val pts: List<Pair<Float, Float>>,
        val color: Int,
        val widthPx: Float
    )

    private data class TextRun(
        val content: String,
        val x: Float, val y: Float,
        val sizePx: Float,
        val color: Int
    )

    private fun tryVector(
        key: String, name: String, opacity: Float, visible: Boolean, blend: String,
        db: SQLiteDatabase, tables: Set<String>, singleKey: Boolean,
        canvasW: Int, canvasH: Int,
        approx: MutableList<String>, out: MutableList<RawLayer>
    ): RawLayer? {
        val paths = readVectorPaths(db, tables, key, singleKey)
        if (paths.isNullOrEmpty()) return null
        val bmp = rasterizePaths(paths, canvasW, canvasH, approx, name) ?: return null
        noteOnce(approx, "vector brush texture ignored: flat strokes")
        noteOnce(approx, "bezier controls flattened to polyline segments")
        val layer = RawLayer(name, bmp, opacity, visible, blend)
        out.add(layer)
        return layer
    }

    private fun tryText(
        key: String, name: String, opacity: Float, visible: Boolean, blend: String,
        db: SQLiteDatabase, tables: Set<String>, singleKey: Boolean,
        canvasW: Int, canvasH: Int,
        approx: MutableList<String>, out: MutableList<RawLayer>
    ): RawLayer? {
        val runs = readTextRuns(db, tables, key, singleKey)
        if (runs.isNullOrEmpty()) return null
        val bmp = rasterizeText(runs, canvasW, canvasH, approx, name) ?: return null
        noteOnce(approx, "text style ignored: system font, plain UTF-8")
        noteOnce(approx, "text paragraph/transform attributes ignored")
        val layer = RawLayer(name, bmp, opacity, visible, blend)
        out.add(layer)
        return layer
    }

    /** Validated stroke list for one layer key. Null when none decodable. */
    private fun readVectorPaths(
        db: SQLiteDatabase, tables: Set<String>, layerKey: String, singleKey: Boolean
    ): List<VecPath>? {
        val vTables = tables.filter { it.contains("vector", ignoreCase = true) }.take(8)
        if (vTables.isEmpty()) return null
        val out = mutableListOf<VecPath>()
        for (t in vTables) {
            if (out.size >= MAX_PATHS) break
            try {
                db.rawQuery("SELECT rowid, * FROM $t LIMIT 512", null).use { c ->
                    val names = (0 until c.columnCount).map { c.getColumnName(it) }
                    val keyCol = pickCol(
                        names,
                        listOf("layerId", "layer", "ownerId", "parentId", "layerKey", "id", "uuid")
                    )
                    val ptsCol = pickCol(
                        names,
                        listOf("points", "path", "vertices", "coords", "data", "d", "stroke", "polyline")
                    )
                    val colorCol = pickCol(
                        names, listOf("color", "colour", "strokeColor", "rgba", "ink", "inkColor")
                    )
                    val wCol = pickCol(
                        names,
                        listOf("width", "lineWidth", "radius", "size", "thickness", "brushSize", "penWidth")
                    )
                    if (ptsCol == null) return@use
                    while (c.moveToNext() && out.size < MAX_PATHS) {
                        if (keyCol != null && !singleKey) {
                            val k = try {
                                c.getString(keyCol)
                            } catch (_: Exception) {
                                null
                            }
                            if (k != layerKey) continue
                        }
                        val pts = readPointsCell(c, ptsCol) ?: continue
                        if (pts.size < 2 || pts.size > MAX_PTS) continue
                        if (!pts.all { it.first.isFinite() && it.second.isFinite() }) continue
                        if (!pts.all { kotlin.math.abs(it.first) <= COORD_LIMIT && kotlin.math.abs(it.second) <= COORD_LIMIT }) {
                            continue
                        }
                        val color = if (colorCol != null) {
                            readColorCell(c, colorCol, 0xFF000000.toInt())
                        } else 0xFF000000.toInt()
                        val width = if (wCol != null) {
                            readWidthCell(c, wCol)
                        } else 3f
                        out.add(VecPath(pts, color, width))
                    }
                }
            } catch (_: Exception) {
            }
        }
        return if (out.isEmpty()) null else out
    }

    /** One cell -> point list. TEXT: number-pair scan (JSON or "x,y;..."). */
    private fun readPointsCell(c: android.database.Cursor, col: Int): List<Pair<Float, Float>>? {
        val asText: String? = try {
            c.getString(col)
        } catch (_: Exception) {
            null
        }
        if (asText != null) return parsePointPairs(asText)
        val blob: ByteArray? = try {
            c.getBlob(col)
        } catch (_: Exception) {
            null
        }
        if (blob == null || blob.isEmpty() || blob.size > MAX_PTS * 8 + 16) return null
        // Little-endian float32 pairs (our own fixture encoding).
        if (blob.size % 8 != 0) return null
        val n = blob.size / 8
        if (n < 2 || n > MAX_PTS) return null
        val pts = ArrayList<Pair<Float, Float>>(n)
        for (i in 0 until n) {
            val o = i * 8
            val x = leFloat(blob, o) ?: return null
            val y = leFloat(blob, o + 4) ?: return null
            pts.add(Pair(x, y))
        }
        return pts
    }

    private val NUMBER_RE = Regex("-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?")

    private fun parsePointPairs(s: String): List<Pair<Float, Float>>? {
        if (s.isEmpty() || s.length > 65536) return null
        val nums = NUMBER_RE.findAll(s).mapNotNull {
            try {
                it.value.toFloat()
            } catch (_: Exception) {
                null
            }
        }.toList()
        if (nums.size < 4 || nums.size > MAX_PTS * 2 || nums.size % 2 != 0) return null
        val pts = ArrayList<Pair<Float, Float>>(nums.size / 2)
        var i = 0
        while (i < nums.size) {
            pts.add(Pair(nums[i], nums[i + 1]))
            i += 2
        }
        return pts
    }

    private fun leFloat(b: ByteArray, o: Int): Float? {
        if (o < 0 || o + 4 > b.size) return null
        val bits = (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)
        return Float.fromBits(bits)
    }

    private fun readColorCell(c: android.database.Cursor, col: Int, def: Int): Int {
        return try {
            val s = c.getString(col)
            if (s != null) parseColor(s) ?: def else {
                // Integer cell: trust only opaque RGB range, else default.
                val v = c.getInt(col)
                if (v == 0) def else v
            }
        } catch (_: Exception) {
            def
        }
    }

    private fun parseColor(s: String): Int? {
        val t = s.trim()
        if (t.isEmpty() || t.length > 32) return null
        if (t.startsWith("#")) {
            val hex = t.drop(1)
            return try {
                when (hex.length) {
                    6 -> (0xFF000000.toInt() or hex.toLong(16).toInt())
                    8 -> hex.toLong(16).toInt()
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }
        // "r,g,b" or "r,g,b,a" decimal triplets, or a plain packed int.
        val parts = t.split(",", ";", " ").mapNotNull { it.toIntOrNull() }
        if (parts.size == 3 && parts.all { it in 0..255 }) {
            return (0xFF shl 24) or (parts[0] shl 16) or (parts[1] shl 8) or parts[2]
        }
        if (parts.size == 4 && parts.all { it in 0..255 }) {
            return (parts[3] shl 24) or (parts[0] shl 16) or (parts[1] shl 8) or parts[2]
        }
        // Plain packed int (e.g. SQLite INTEGER read back as text).
        val packed = t.toIntOrNull()
        if (packed != null && packed != 0) return packed
        return null
    }

    private fun readWidthCell(c: android.database.Cursor, col: Int): Float {
        return try {
            val v = c.getDouble(col)
            if (!v.isFinite()) 3f
            else v.toFloat().coerceIn(0.5f, 128f)
        } catch (_: Exception) {
            3f
        }
    }

    /** House rasterization: flat polylines on a transparent canvas bitmap. */
    private fun rasterizePaths(
        paths: List<VecPath>, canvasW: Int, canvasH: Int,
        approx: MutableList<String>, layerName: String
    ): android.graphics.Bitmap? {
        var w = canvasW
        var h = canvasH
        if (w !in 1..MAX_SIDE || h !in 1..MAX_SIDE) {
            var ew = 0
            var eh = 0
            for (p in paths) {
                for ((x, y) in p.pts) {
                    if (x.isFinite() && y.isFinite() &&
                        kotlin.math.abs(x) <= COORD_LIMIT && kotlin.math.abs(y) <= COORD_LIMIT
                    ) {
                        ew = maxOf(ew, (x + p.widthPx).toInt())
                        eh = maxOf(eh, (y + p.widthPx).toInt())
                    }
                }
            }
            if (ew !in 1..MAX_SIDE || eh !in 1..MAX_SIDE) {
                noteOnce(approx, "vector layer '${layerName.take(32)}' extents out of range: skipped")
                return null
            }
            w = ew
            h = eh
        }
        val bmp: android.graphics.Bitmap = try {
            android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        } catch (_: Exception) {
            return null
        }
        // Transparent base: erase to 0 explicitly (createBitmap is zeroed
        // on the platform, but be explicit for hostile-size safety).
        try {
            bmp.eraseColor(0)
            val cv = android.graphics.Canvas(bmp)
            val margin = 4096f
            var drawn = 0
            for (p in paths) {
                // Bbox reject: fully off-canvas paths cost nothing.
                var minX = Float.MAX_VALUE
                var minY = Float.MAX_VALUE
                var maxX = -Float.MAX_VALUE
                var maxY = -Float.MAX_VALUE
                for ((x, y) in p.pts) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                val grow = p.widthPx
                if (maxX < -margin - grow || minX > w + margin + grow ||
                    maxY < -margin - grow || minY > h + margin + grow
                ) {
                    continue
                }
                val paint = android.graphics.Paint()
                paint.isAntiAlias = true
                paint.color = p.color
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = p.widthPx.coerceIn(0.5f, 128f)
                paint.strokeCap = android.graphics.Paint.Cap.ROUND
                paint.strokeJoin = android.graphics.Paint.Join.ROUND
                val path = android.graphics.Path()
                path.moveTo(p.pts[0].first, p.pts[0].second)
                for (i in 1 until p.pts.size) {
                    path.lineTo(p.pts[i].first, p.pts[i].second)
                }
                cv.drawPath(path, paint)
                drawn++
            }
            if (drawn == 0) {
                noteOnce(approx, "vector layer '${layerName.take(32)}' has no on-canvas geometry: skipped")
                safeRecycle(bmp)
                return null
            }
        } catch (_: Exception) {
            safeRecycle(bmp)
            return null
        }
        return bmp
    }

    /** Validated text runs for one layer key. Null when none decodable. */
    private fun readTextRuns(
        db: SQLiteDatabase, tables: Set<String>, layerKey: String, singleKey: Boolean
    ): List<TextRun>? {
        val tTables = tables.filter {
            it.contains("text", ignoreCase = true) && !it.contains("texture", ignoreCase = true)
        }.take(8)
        if (tTables.isEmpty()) return null
        val out = mutableListOf<TextRun>()
        for (t in tTables) {
            if (out.size >= MAX_PATHS) break
            try {
                db.rawQuery("SELECT rowid, * FROM $t LIMIT 512", null).use { c ->
                    val names = (0 until c.columnCount).map { c.getColumnName(it) }
                    val keyCol = pickCol(
                        names,
                        listOf("layerId", "layer", "ownerId", "parentId", "layerKey", "id", "uuid")
                    )
                    val txtCol = pickCol(
                        names,
                        listOf("content", "text", "string", "value", "characters", "body", "caption")
                    ) ?: return@use
                    val xCol = pickCol(names, listOf("x", "posX", "left", "originX"))
                    val yCol = pickCol(names, listOf("y", "posY", "top", "originY", "baseline"))
                    val sizeCol = pickCol(names, listOf("size", "fontSize", "height", "px", "pointSize"))
                    val colorCol = pickCol(
                        names, listOf("color", "colour", "fillColor", "rgba", "ink", "inkColor")
                    )
                    while (c.moveToNext() && out.size < MAX_PATHS) {
                        if (keyCol != null && !singleKey) {
                            val k = try {
                                c.getString(keyCol)
                            } catch (_: Exception) {
                                null
                            }
                            if (k != layerKey) continue
                        }
                        val raw: String? = try {
                            c.getString(txtCol)
                        } catch (_: Exception) {
                            null
                        }
                        if (raw.isNullOrEmpty() || raw.length > MAX_TEXT_LEN) continue
                        // Plain UTF-8: drop NULs, keep the rest verbatim.
                        val clean = raw.replace("\u0000", "")
                        if (clean.isBlank()) continue
                        fun coord(col: Int?, def: Float): Float {
                            if (col == null) return def
                            return try {
                                val v = c.getFloat(col).let { it.toDouble() }
                                if (!v.isFinite()) def
                                else v.toFloat().coerceIn(-COORD_LIMIT, COORD_LIMIT)
                            } catch (_: Exception) {
                                def
                            }
                        }
                        val x = coord(xCol, 0f)
                        val y = coord(yCol, 16f)
                        val size = if (sizeCol != null) {
                            try {
                                val v = c.getDouble(sizeCol)
                                if (!v.isFinite()) 16f else v.toFloat().coerceIn(8f, 256f)
                            } catch (_: Exception) {
                                16f
                            }
                        } else 16f
                        val color = if (colorCol != null) {
                            readColorCell(c, colorCol, 0xFF000000.toInt())
                        } else 0xFF000000.toInt()
                        out.add(TextRun(clean.take(MAX_TEXT_LEN), x, y, size, color))
                    }
                }
            } catch (_: Exception) {
            }
        }
        return if (out.isEmpty()) null else out
    }

    /** House rasterization: plain UTF-8 lines with the system font. */
    private fun rasterizeText(
        runs: List<TextRun>, canvasW: Int, canvasH: Int,
        approx: MutableList<String>, layerName: String
    ): android.graphics.Bitmap? {
        val w = if (canvasW in 1..MAX_SIDE) canvasW else null
        val h = if (canvasH in 1..MAX_SIDE) canvasH else null
        if (w == null || h == null) {
            noteOnce(approx, "text layer '${layerName.take(32)}' without canvas size: skipped")
            return null
        }
        val bmp: android.graphics.Bitmap = try {
            android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        } catch (_: Exception) {
            return null
        }
        try {
            bmp.eraseColor(0)
            val cv = android.graphics.Canvas(bmp)
            var drawn = 0
            for (r in runs) {
                val paint = android.graphics.Paint()
                paint.isAntiAlias = true
                paint.color = r.color
                paint.textSize = r.sizePx.coerceIn(8f, 256f)
                // System default typeface: fonts are deliberately NOT matched.
                val lines = r.content.split("\n").take(MAX_TEXT_LINES)
                var ly = r.y
                for (line in lines) {
                    if (line.isEmpty()) {
                        ly += r.sizePx * 1.2f
                        continue
                    }
                    // Clamp the draw origin; Canvas clips the rest.
                    val lx = r.x.coerceIn(-COORD_LIMIT, COORD_LIMIT)
                    ly = ly.coerceIn(-COORD_LIMIT, COORD_LIMIT)
                    cv.drawText(line.take(512), lx, ly, paint)
                    drawn++
                    ly += r.sizePx * 1.2f
                    if (ly > h + 1024) break
                }
            }
            if (drawn == 0) {
                noteOnce(approx, "text layer '${layerName.take(32)}' drew nothing: skipped")
                safeRecycle(bmp)
                return null
            }
        } catch (_: Exception) {
            safeRecycle(bmp)
            return null
        }
        return bmp
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
