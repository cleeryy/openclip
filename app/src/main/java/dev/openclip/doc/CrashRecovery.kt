// OpenClip — crash recovery snapshots (issue #32 §2).
//
// Autosave writes a timestamped OpenRaster snapshot of a modified document
// into `filesDir/autosave`, local only, never uploaded. A snapshot only
// counts as recoverable when it is NEWER than that document's last explicit
// save (SAF export / save-as), so a clean session never nags the user at
// next launch. Snapshots are pruned (3 per document, 12 total) and a
// corrupt/truncated file is ignored (an ORA starts with the ZIP magic).
//
// The index is a small versioned JSON so the reader stays tolerant: a
// missing/garbled index only loses names and dimensions, never the pixels
// path parsing itself.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.doc

import org.json.JSONObject
import java.io.File

object CrashRecovery {
    /** One timestamped autosave snapshot waiting in `filesDir/autosave`. */
    data class RecoverySnapshot(
        val docId: String,
        val name: String,
        val w: Int,
        val h: Int,
        val dpi: Float,
        val path: String,
        val savedAtMs: Long
    )

    const val DIR = "autosave"
    const val INDEX = "recovery-index.json"
    const val KEEP_PER_DOC = 3
    const val KEEP_TOTAL = 12

    fun dir(filesDir: File): File = File(filesDir, DIR)

    private fun indexFile(filesDir: File): File = File(dir(filesDir), INDEX)

    /** Destination for a snapshot written at [nowMs] (unique per write). */
    fun snapshotFile(filesDir: File, docId: String, nowMs: Long): File {
        dir(filesDir).mkdirs()
        return File(dir(filesDir), "$docId-$nowMs.ora")
    }

    // ---- index (doc metadata + explicit-save watermark) ----

    private fun readIndex(filesDir: File): JSONObject {
        val f = indexFile(filesDir)
        return try {
            if (f.isFile) JSONObject(f.readText()) else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun writeIndex(filesDir: File, obj: JSONObject) {
        try {
            val f = indexFile(filesDir)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(obj.toString())
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) {
                f.writeText(obj.toString())
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun docsObject(idx: JSONObject): JSONObject =
        idx.optJSONObject("docs") ?: JSONObject()

    /** Remember how to restore [docId] (name + canvas geometry). */
    fun recordDoc(
        filesDir: File,
        docId: String,
        name: String,
        w: Int,
        h: Int,
        dpi: Float
    ) {
        val idx = readIndex(filesDir)
        val docs = docsObject(idx)
        val e = docs.optJSONObject(docId) ?: JSONObject()
        e.put("name", name)
        e.put("w", w)
        e.put("h", h)
        e.put("dpi", dpi.toDouble())
        docs.put(docId, e)
        idx.put("docs", docs)
        writeIndex(filesDir, idx)
    }

    /** Stamp the last explicit save of [docId] (watermark for `pending`). */
    fun markExplicitSave(filesDir: File, docId: String, nowMs: Long) {
        val idx = readIndex(filesDir)
        val docs = docsObject(idx)
        val e = docs.optJSONObject(docId) ?: JSONObject()
        e.put("saved", nowMs)
        docs.put(docId, e)
        idx.put("docs", docs)
        writeIndex(filesDir, idx)
    }

    fun explicitSaveMs(filesDir: File, docId: String): Long =
        docsObject(readIndex(filesDir))
            .optJSONObject(docId)?.optLong("saved", 0L) ?: 0L

    // ---- snapshot listing ----

    private fun isOra(f: File): Boolean = try {
        f.inputStream().use { input ->
            val magic = ByteArray(2)
            input.read(magic) == 2 && magic[0] == 'P'.code.toByte() &&
                magic[1] == 'K'.code.toByte()
        }
    } catch (_: Exception) {
        false
    }

    /** Every readable snapshot, newest first. Bad files are dropped. */
    fun list(filesDir: File): List<RecoverySnapshot> {
        val files = dir(filesDir).listFiles { f -> f.isFile && f.name.endsWith(".ora") }
            ?: return emptyList()
        val docs = docsObject(readIndex(filesDir))
        val out = ArrayList<RecoverySnapshot>(files.size)
        for (f in files) {
            val base = f.name.removeSuffix(".ora")
            val dash = base.lastIndexOf('-')
            if (dash <= 0) continue
            val docId = base.substring(0, dash)
            val stamp = base.substring(dash + 1).toLongOrNull() ?: continue
            val meta = docs.optJSONObject(docId) ?: continue
            val w = meta.optInt("w", 0)
            val h = meta.optInt("h", 0)
            if (w <= 0 || h <= 0 || !isOra(f)) continue
            out.add(
                RecoverySnapshot(
                    docId = docId,
                    name = meta.optString("name", docId),
                    w = w,
                    h = h,
                    dpi = meta.optDouble("dpi", 160.0).toFloat(),
                    path = f.absolutePath,
                    savedAtMs = stamp
                )
            )
        }
        return out.sortedByDescending { it.savedAtMs }
    }

    /** Newest snapshot of every document that has unsaved changes left. */
    fun pending(filesDir: File): List<RecoverySnapshot> {
        val newestPerDoc = LinkedHashMap<String, RecoverySnapshot>()
        for (s in list(filesDir)) {
            if (!newestPerDoc.containsKey(s.docId)) newestPerDoc[s.docId] = s
        }
        return newestPerDoc.values
            .filter { it.savedAtMs > explicitSaveMs(filesDir, it.docId) }
            .sortedByDescending { it.savedAtMs }
    }

    /** Keep the newest [KEEP_PER_DOC] per doc, [KEEP_TOTAL] overall. */
    fun prune(filesDir: File) {
        val all = list(filesDir)
        val keep = HashSet<String>()
        var total = 0
        for (group in all.groupBy { it.docId }.values) {
            for (s in group.take(KEEP_PER_DOC)) {
                if (total >= KEEP_TOTAL) break
                keep.add(s.path)
                total++
            }
            if (total >= KEEP_TOTAL) break
        }
        for (s in all) if (s.path !in keep) File(s.path).delete()
    }

    /**
     * Drop every snapshot of the documents currently offered for recovery
     * (all generations, so deleting the newest never resurfaces an older
     * one) and stamp the watermark **past** the newest snapshot, so a file
     * whose delete fails — or a clock that jumped backwards — cannot make
     * the prompt come back on the next launch.
     */
    fun discardPending(filesDir: File) {
        val offered = pending(filesDir)
        if (offered.isEmpty()) return
        val docIds = offered.map { it.docId }.toSet()
        val newest = offered.maxOf { it.savedAtMs }
        for (s in list(filesDir)) if (s.docId in docIds) File(s.path).delete()
        val stamp = maxOf(System.currentTimeMillis(), newest + 1)
        for (id in docIds) markExplicitSave(filesDir, id, stamp)
    }

    /** Forget [docId]'s snapshots after an explicit save. */
    fun clearDoc(filesDir: File, docId: String) {
        val snaps = list(filesDir).filter { it.docId == docId }
        for (s in snaps) File(s.path).delete()
        if (snaps.isNotEmpty()) {
            // The explicit save is at least as fresh as any snapshot (and
            // survives a delete failure), so stamp past the newest one.
            val stamp = maxOf(
                System.currentTimeMillis(), snaps.maxOf { it.savedAtMs } + 1
            )
            markExplicitSave(filesDir, docId, stamp)
        }
    }

    /** Remove everything (test cleanup, "clear cache" housekeeping). */
    fun clearAll(filesDir: File) {
        for (s in list(filesDir)) File(s.path).delete()
    }
}
