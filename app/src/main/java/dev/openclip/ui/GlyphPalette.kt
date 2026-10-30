// OpenClip B2 §2 (ARCHITECTURE.md "B2 additions") — glyph palette:
// the character set of the LOADED typeface (Paint.hasGlyph over the
// open Unicode blocks), cached per family/style, inserted at the
// current selection. App-side only, no font file parsed by hand.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.openclip.R
import java.util.Locale

/** B2 §2 glyph catalog: codepoint blocks worth browsing for lettering. */
private val GLYPH_RANGES = listOf(
    0x20..0x7E,       // ASCII printable
    0xA0..0xFF,       // Latin-1 supplement
    0x100..0x17F,     // Latin Extended-A
    0x2010..0x203A,   // general punctuation
    0x20AC..0x20AC,   // euro
    0x2190..0x2195,   // arrows
    0x2260..0x2265    // not-equal / leq / geq
)

object GlyphCatalog {
    private val cache = mutableMapOf<String, List<String>>()

    /**
     * Characters the loaded typeface really has (hasGlyph), capped at
     * [limit] entries. Cached per family/style; thread-safe enough for
     * the UI (single main-thread reader).
     */
    fun glyphsFor(
        family: String,
        bold: Boolean,
        italic: Boolean,
        limit: Int = 320
    ): List<String> {
        val key = "$family|$bold|$italic"
        cache[key]?.let { return it }
        val (tf, _) = FontRegistry.typefaceFor(family, bold, italic)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf
            textSize = 24f
        }
        val out = mutableListOf<String>()
        for (range in GLYPH_RANGES) {
            for (cp in range) {
                if (out.size >= limit) break
                if (cp == 0x20) continue // space is not a visible glyph
                if (!paint.hasGlyph(String(Character.toChars(cp)))) continue
                out.add(String(Character.toChars(cp)))
            }
            if (out.size >= limit) break
        }
        cache[key] = out
        return out
    }

    fun clearCache() {
        cache.clear()
    }

    /** Insert `glyph` replacing [selStart, selEnd); returns text + caret. */
    fun insertAt(
        text: String,
        selStart: Int,
        selEnd: Int,
        glyph: String
    ): Pair<String, Int> {
        val a = minOf(selStart, selEnd).coerceIn(0, text.length)
        val b = maxOf(selStart, selEnd).coerceIn(0, text.length)
        val out = text.substring(0, a) + glyph + text.substring(b)
        return Pair(out, a + glyph.length)
    }

    /** Test/debug label for a character (stable test tags). */
    fun tagOf(glyph: String): String {
        val cp = glyph.codePointAt(0)
        return "glyph-u+" + String.format(Locale.ROOT, "%04X", cp)
    }
}

/**
 * B2 §2 dialog: scrollable grid of the loaded font's characters; a tap
 * inserts at the caller's selection and keeps the dialog open (lettering
 * needs several glyphs in a row).
 */
@Composable
fun GlyphPaletteDialog(
    family: String,
    bold: Boolean,
    italic: Boolean,
    onInsert: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val glyphs = remember(family, bold, italic) {
        GlyphCatalog.glyphsFor(family, bold, italic)
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 6.dp,
            modifier = Modifier.testTag("glyph-palette")
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    stringResource(R.string.glyph_title, family),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    stringResource(R.string.glyph_hint, glyphs.size),
                    style = MaterialTheme.typography.labelSmall
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(8),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(glyphs) { g ->
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color(0xFF2B2B34))
                                .clickable { onInsert(g) }
                                .testTag(GlyphCatalog.tagOf(g)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(g, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text(stringResource(R.string.close)) }
            }
        }
    }
}
