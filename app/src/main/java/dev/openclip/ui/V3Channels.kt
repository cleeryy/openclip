// OpenClip V+3 — named selection channels (§4, 0 JNI).
//
// The V+1 session mask stays session-only; V+3 adds PERSISTED named
// channels: PNG 1-bit files + channels.json (v1) next to the document
// key (same cacheDir pattern as guides). Activating a channel reloads
// it into the session mask; saving overwrites from the session mask.
// Boolean ops run app-side on the bit buffers (pure CPU).
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import java.io.File

enum class ChannelOp { REPLACE, ADD, SUBTRACT, INTERSECT }

object ChannelOps {
    /**
     * Pure ops on soft-alpha plane buffers (T1: 0..255, nonzero =
     * selected — same convention as SelectionMask). Output is binary
     * 255/0 (boolean result, no feather synthesis).
     */
    fun apply(op: ChannelOp, base: ByteArray, over: ByteArray): ByteArray {
        require(base.size == over.size) { "channel size mismatch" }
        return ByteArray(base.size) { i ->
            val a = base[i] != 0.toByte()
            val b = over[i] != 0.toByte()
            val r = when (op) {
                ChannelOp.REPLACE -> b
                ChannelOp.ADD -> a || b
                ChannelOp.SUBTRACT -> a && !b
                ChannelOp.INTERSECT -> a && b
            }
            if (r) 255.toByte() else 0
        }
    }

    /** Soft plane (0..255, T1) → grayscale PNG bytes (value-preserving). */
    fun bitsToPng(w: Int, h: Int, bits: ByteArray): ByteArray? = try {
        require(bits.size == w * h)
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h) { i ->
            val g = bits[i].toInt() and 0xFF
            -16777216 or (g * 0x10101)
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        val bos = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
        bmp.recycle()
        bos.toByteArray()
    } catch (_: Exception) {
        null
    }

    /** Grayscale PNG bytes → soft plane (0..255), or null on mismatch. */
    fun pngToBits(w: Int, h: Int, png: ByteArray): ByteArray? {
        return try {
            val bmp = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
                ?: return null
            if (bmp.width != w || bmp.height != h) {
                bmp.recycle()
                return null
            }
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            bmp.recycle()
            ByteArray(w * h) { i ->
                ((px[i] shr 16) and 0xFF).toByte()
            }
        } catch (_: Exception) {
            null
        }
    }
}

object ChannelStore {
    const val FILE = "channels.json"

    fun dir(cacheDir: File, docKey: String): File =
        File(cacheDir, "channels-$docKey").also { it.mkdirs() }

    fun fileFor(dir: File, name: String): File {
        val safe = name.replace(Regex("[^A-Za-z0-9 _-]"), "_").take(64)
            .ifEmpty { "channel" }
        return File(dir, "$safe.png")
    }

    fun list(cacheDir: File, docKey: String): List<String> {
        return try {
            val f = File(dir(cacheDir, docKey), FILE)
            if (!f.exists()) return emptyList()
            val re = Regex("\"name\"\\s*:\\s*\"([^\"]*)\"")
            re.findAll(f.readText()).map { it.groupValues[1] }.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(cacheDir: File, docKey: String, names: List<String>): Boolean {
        return try {
            val d = dir(cacheDir, docKey)
            val body = names.joinToString(",") { "{\"name\":\"$it\"}" }
            File(d, FILE).writeText("{\"v\":1,\"channels\":[$body]}")
            true
        } catch (_: Exception) {
            false
        }
    }

    fun delete(cacheDir: File, docKey: String, name: String): Boolean {
        return try {
            val d = dir(cacheDir, docKey)
            fileFor(d, name).delete()
            save(cacheDir, docKey, list(cacheDir, docKey) - name)
        } catch (_: Exception) {
            false
        }
    }

    fun rename(
        cacheDir: File,
        docKey: String,
        old: String,
        new: String,
    ): Boolean {
        return try {
            val d = dir(cacheDir, docKey)
            val names = list(cacheDir, docKey)
            if (old !in names || new.isBlank()) return false
            fileFor(d, old).renameTo(fileFor(d, new))
            save(cacheDir, docKey, names.map { if (it == old) new else it })
        } catch (_: Exception) {
            false
        }
    }
}
