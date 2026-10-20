// OpenClip V+1 — text layer fiche + renderer (app-side only).
//
// A text layer = a normal raster layer (pixels baked by the platform
// Canvas) + an app-side TextSpec fiche. Re-edit = re-render + replace
// the pixels (rasterized-editable, no engine glyphs). The fiche
// round-trips through ORA as x-openclip-* (engine nSetLayerText,
// ARCHITECTURE.md "V+1 additions"); unknown/absent = normal layer.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.json.JSONObject

/** Platform logical families offered (AOSP system fonts, never tierces). */
val TEXT_SYSTEM_FONTS = listOf(
    "sans-serif", "sans-serif-light", "sans-serif-condensed",
    "sans-serif-medium", "serif", "monospace",
    "serif-monospace", "casual", "cursive", "sans-serif-smallcaps"
)

data class TextSpec(
    val text: String,
    val font: String = "sans-serif",
    val sizePx: Int = 64,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val align: String = "left", // left|center|right
    val leading: Float = 1.0f, // 0.8..2.0
    val tracking: Float = 0f, // letter spacing px
    val colorHex: String = "#141414",
    val boxX: Float = 0f,
    val boxY: Float = 0f,
    val boxW: Float = 0f // 0 = full canvas width from boxX
) {
    val styleName: String
        get() = when {
            bold && italic -> "bold-italic"
            bold -> "bold"
            italic -> "italic"
            else -> "regular"
        }

    fun toJson(): String {
        val sb = StringBuilder("{")
        fun str(k: String, v: String) {
            sb.append("\"").append(k).append("\":\"")
            for (ch in v) {
                when (ch) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    else -> sb.append(ch)
                }
            }
            sb.append("\",")
        }
        str("text", text)
        str("font", font)
        sb.append("\"size\":").append(sizePx).append(",")
        str("style", styleName)
        str("align", align)
        sb.append("\"leading\":").append(leading).append(",")
        sb.append("\"tracking\":").append(tracking).append(",")
        str("color", colorHex)
        str("box", "$boxX,$boxY,$boxW")
        sb.append("\"end\":1}")
        return sb.toString()
    }

    companion object {
        fun fromJson(raw: String): TextSpec? {
            return try {
                val o = JSONObject(raw)
                val t = o.optString("text", "")
                if (t.isEmpty()) return null
                val style = o.optString("style", "regular")
                val box = o.optString("box", "").split(",")
                TextSpec(
                    text = t,
                    font = o.optString("font", "sans-serif"),
                    sizePx = o.optInt("size", 64).coerceIn(8, 1024),
                    bold = style.contains("bold"),
                    italic = style.contains("italic"),
                    align = o.optString("align", "left"),
                    leading = o.optDouble("leading", 1.0).toFloat()
                        .coerceIn(0.5f, 4f),
                    tracking = o.optDouble("tracking", 0.0).toFloat()
                        .coerceIn(-64f, 64f),
                    colorHex = o.optString("color", "#141414"),
                    boxX = box.getOrNull(0)?.toFloatOrNull() ?: 0f,
                    boxY = box.getOrNull(1)?.toFloatOrNull() ?: 0f,
                    boxW = box.getOrNull(2)?.toFloatOrNull() ?: 0f
                )
            } catch (_: Exception) {
                null
            }
        }

        /** Rebuild a fiche from an nLayerState "text" object. */
        fun fromState(o: JSONObject): TextSpec? {
            return try {
                val t = o.optString("content", "")
                if (t.isEmpty()) return null
                val style = o.optString("style", "regular")
                val box = o.optString("box", "").split(",")
                TextSpec(
                    text = t,
                    font = o.optString("font", "sans-serif"),
                    sizePx = o.optInt("size", 64).coerceIn(8, 1024),
                    bold = style.contains("bold"),
                    italic = style.contains("italic"),
                    align = o.optString("align", "left"),
                    leading = o.optDouble("leading", 1.0).toFloat()
                        .coerceIn(0.5f, 4f),
                    tracking = o.optDouble("tracking", 0.0).toFloat()
                        .coerceIn(-64f, 64f),
                    colorHex = o.optString("color", "#141414"),
                    boxX = box.getOrNull(0)?.toFloatOrNull() ?: 0f,
                    boxY = box.getOrNull(1)?.toFloatOrNull() ?: 0f,
                    boxW = box.getOrNull(2)?.toFloatOrNull() ?: 0f
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

object TextRenderer {
    data class Rendered(val bitmap: Bitmap, val fontSubstituted: Boolean)

    fun render(spec: TextSpec, canvasW: Int, canvasH: Int): Rendered {
        val known = TEXT_SYSTEM_FONTS.contains(spec.font)
        val family = if (known) spec.font else "sans-serif"
        val style = when {
            spec.bold && spec.italic -> Typeface.BOLD_ITALIC
            spec.bold -> Typeface.BOLD
            spec.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = parseColor(spec.colorHex)
            textSize = spec.sizePx.coerceIn(8, 1024).toFloat()
            typeface = Typeface.create(family, style)
            if (Build.VERSION.SDK_INT >= 21) letterSpacing = spec.tracking / textSize.coerceAtLeast(1f)
        }
        val align = when (spec.align) {
            "center" -> Layout.Alignment.ALIGN_CENTER
            "right" -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        val wrapW = if (spec.boxW > 1f) spec.boxW.toInt() else (canvasW - spec.boxX.toInt()).coerceAtLeast(8)
        val layout = StaticLayout.Builder.obtain(
            spec.text.ifEmpty { " " }, 0,
            spec.text.ifEmpty { " " }.length, paint, wrapW
        )
            .setAlignment(align)
            .setLineSpacing(0f, spec.leading.coerceIn(0.5f, 4f))
            .setIncludePad(true)
            .build()
        val bmp = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.save()
        c.translate(spec.boxX, spec.boxY)
        layout.draw(c)
        c.restore()
        return Rendered(bmp, !known)
    }

    private fun parseColor(hex: String): Int {
        return try {
            val h = hex.trimStart('#')
            (0xFF000000.toInt() or h.takeLast(6).toInt(16))
        } catch (_: Exception) {
            0xFF141414.toInt()
        }
    }
}
