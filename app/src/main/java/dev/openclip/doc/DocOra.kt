// OpenClip slice 6 — minimal single-layer ORA writer (app-side only).
//
// Generates the initial paper of a new canvas: a solid white / ink-tint
// fill or a fully transparent layer 1, loaded through the existing
// nLoadDocument (no new JNI). stb_image decodes the Android-written PNG;
// the loader resolves layers by the `src` zip entry name.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.doc

import android.graphics.Bitmap
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DocOra {

    /**
     * Writes a 1-layer ORA file. [argb] null = transparent layer,
     * otherwise the opaque/alpha fill color.
     */
    fun writeSolidOra(dst: File, w: Int, h: Int, argb: Int?): Boolean {
        return try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(argb ?: 0x00000000)
            val png = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, png)
            bmp.recycle()
            val stack = String.format(
                Locale.US,
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<image w=\"%d\" h=\"%d\">\n<stack>\n" +
                    "<layer name=\"Background\" src=\"data/0.png\" " +
                    "opacity=\"1\" visibility=\"visible\" " +
                    "composite-op=\"svg:normal\"/>\n" +
                    "</stack>\n</image>\n",
                w, h
            )
            ZipOutputStream(dst.outputStream().buffered()).use { z ->
                z.putNextEntry(ZipEntry("mimetype"))
                z.write("image/openraster".toByteArray(Charsets.US_ASCII))
                z.closeEntry()
                z.putNextEntry(ZipEntry("stack.xml"))
                z.write(stack.toByteArray(Charsets.UTF_8))
                z.closeEntry()
                z.putNextEntry(ZipEntry("data/0.png"))
                z.write(png.toByteArray())
                z.closeEntry()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Rough per-doc RAM estimate in MB (1 layer x 4 B/px + undo margin). */
    fun estimateMb(w: Int, h: Int): Double =
        w.toDouble() * h.toDouble() * 4.0 * 1.5 / 1e6

    fun parseInkArgb(hex: String): Int {
        return try {
            val h = hex.trimStart('#')
            val rgb = h.takeLast(6).toInt(16)
            0xFF000000.toInt() or rgb
        } catch (_: Exception) {
            -1
        }
    }
}
