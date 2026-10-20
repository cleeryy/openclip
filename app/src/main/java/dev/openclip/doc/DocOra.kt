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

    /** One raster layer for [writeLayersOra] (bottom-first order in the list). */
    data class OraLayer(
        val name: String,
        val bitmap: Bitmap,
        val opacity: Float = 1f,
        val visible: Boolean = true,
        /** Engine blend name, e.g. "svg:normal" (see blend.h). */
        val blendSvg: String = "svg:normal"
    )

    /**
     * Writes a multi-layer ORA file. Layers are stored bottom-first, with
     * name/opacity/visibility/blend so the engine loader restores them.
     * Used by DocClip (.clip import); the solid-paper path below stays
     * the new-canvas default.
     */
    fun writeLayersOra(dst: File, w: Int, h: Int, layers: List<OraLayer>): Boolean {
        if (w <= 0 || h <= 0 || layers.isEmpty()) return false
        return try {
            val pngs = layers.map { layer ->
                val bos = java.io.ByteArrayOutputStream()
                if (!layer.bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)) {
                    return false
                }
                bos.toByteArray()
            }
            val xml = StringBuilder()
            xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            xml.append(String.format(Locale.US, "<image w=\"%d\" h=\"%d\">\n<stack>\n", w, h))
            layers.forEachIndexed { i, layer ->
                val op = layer.opacity.coerceIn(0f, 1f)
                xml.append(
                    String.format(
                        Locale.US,
                        "<layer name=\"%s\" src=\"data/layer%03d.png\" " +
                            "opacity=\"%.4f\" visibility=\"%s\" composite-op=\"%s\"/>\n",
                        xmlEscape(layer.name.ifBlank { "Layer" }.take(128)),
                        i,
                        op,
                        if (layer.visible) "visible" else "hidden",
                        xmlEscape(layer.blendSvg.ifBlank { "svg:normal" })
                    )
                )
            }
            xml.append("</stack>\n</image>\n")
            ZipOutputStream(dst.outputStream().buffered()).use { z ->
                z.putNextEntry(ZipEntry("mimetype"))
                z.write("image/openraster".toByteArray(Charsets.US_ASCII))
                z.closeEntry()
                z.putNextEntry(ZipEntry("stack.xml"))
                z.write(xml.toString().toByteArray(Charsets.UTF_8))
                z.closeEntry()
                pngs.forEachIndexed { i, png ->
                    z.putNextEntry(ZipEntry(String.format(Locale.US, "data/layer%03d.png", i)))
                    z.write(png)
                    z.closeEntry()
                }
            }
            true
        } catch (_: Exception) {
            try {
                dst.delete()
            } catch (_: Exception) {
            }
            false
        }
    }

    private fun xmlEscape(s: String): String {
        val b = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                '&' -> b.append("&amp;")
                '<' -> b.append("&lt;")
                '>' -> b.append("&gt;")
                '"' -> b.append("&quot;")
                else -> b.append(ch)
            }
        }
        return b.toString()
    }

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
