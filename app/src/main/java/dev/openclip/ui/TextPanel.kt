// OpenClip T2 — text panel: multiline input + system/imported fonts,
// runs (apply-to-selection), outline, vertical, hyphen. Commits
// through EngineSurface.commitText (maison layout -> PNG ->
// documented V+1 blit). ≥48dp targets.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.openclip.R
import kotlin.math.roundToInt

@Composable
fun TextPanel(
    initial: TextSpec,
    inkHex: String,
    fonts: List<FontRegistry.Entry> = TEXT_SYSTEM_FONTS.map {
        FontRegistry.Entry(it, null, true)
    },
    onImportFont: () -> Unit = {},
    onCommit: (TextSpec) -> Unit,
    onDismiss: () -> Unit
) {
    var field by remember { mutableStateOf(TextFieldValue(initial.text)) }
    var font by remember { mutableStateOf(initial.font) }
    var size by remember { mutableStateOf(initial.sizePx.toFloat()) }
    var bold by remember { mutableStateOf(initial.bold) }
    var italic by remember { mutableStateOf(initial.italic) }
    var align by remember { mutableStateOf(initial.align) }
    var leading by remember { mutableStateOf(initial.leading) }
    var tracking by remember { mutableStateOf(initial.tracking) }
    var outlineOn by remember { mutableStateOf(initial.outline != null) }
    var outlineW by remember {
        mutableStateOf(initial.outline?.w ?: 2f)
    }
    var dir by remember { mutableStateOf(initial.dir) }
    var hyphen by remember { mutableStateOf(initial.hyphen) }
    var runs by remember { mutableStateOf(initial.runs) }
    var runSize by remember { mutableStateOf(initial.sizePx.toFloat()) }
    var fontMenu by remember { mutableStateOf(false) }

    fun selRange(): IntRange? {
        val s = field.selection.start
        val e = field.selection.end
        if (s == e || field.text.isEmpty()) return null
        return minOf(s, e) until maxOf(s, e)
    }

    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 6.dp,
            modifier = Modifier.testTag("text-panel")
        ) {
            Column(
                modifier = Modifier
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(R.string.text_title),
                    style = MaterialTheme.typography.titleMedium
                )
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    label = { Text(stringResource(R.string.text_hint)) },
                    minLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("text-field")
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.text_font),
                        style = MaterialTheme.typography.labelSmall
                    )
                    OutlinedButton(
                        onClick = { fontMenu = true },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("text-font-pick")
                    ) {
                        Text(font, maxLines = 1)
                    }
                    DropdownMenu(
                        expanded = fontMenu,
                        onDismissRequest = { fontMenu = false }
                    ) {
                        fonts.forEach { f ->
                            val label = if (f.system) f.family
                            else "${f.family} (${stringResource(R.string.font_home_made)})"
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    font = f.family
                                    fontMenu = false
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.font_import)) },
                            onClick = {
                                fontMenu = false
                                onImportFont()
                            }
                        )
                    }
                }
                Text(
                    stringResource(R.string.font_import_hint),
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    stringResource(R.string.text_size, size.roundToInt()),
                    style = MaterialTheme.typography.labelSmall
                )
                Slider(
                    value = size,
                    onValueChange = { size = it },
                    valueRange = 8f..256f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("text-size")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = bold,
                        onClick = { bold = !bold },
                        label = { Text(stringResource(R.string.text_bold)) },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("text-bold")
                    )
                    FilterChip(
                        selected = italic,
                        onClick = { italic = !italic },
                        label = { Text(stringResource(R.string.text_italic)) },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("text-italic")
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    listOf(
                        "left" to R.string.text_align_left,
                        "center" to R.string.text_align_center,
                        "right" to R.string.text_align_right
                    ).forEach { (v, label) ->
                        FilterChip(
                            selected = align == v,
                            onClick = { align = v },
                            label = { Text(stringResource(label)) },
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("text-align-$v")
                        )
                    }
                }
                // Runs editor (T2 §1): style the current selection.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            val r = selRange() ?: return@Button
                            val nr = TextRun(
                                start = r.first,
                                len = r.last - r.first + 1,
                                font = font,
                                style = when {
                                    bold && italic -> "bold-italic"
                                    bold -> "bold"
                                    italic -> "italic"
                                    else -> "regular"
                                },
                                size = runSize.roundToInt().coerceIn(8, 1024),
                                colorHex = inkHex
                            )
                            // Replace overlapping runs (first wins ->
                            // drop the covered ones).
                            runs = (runs.filter {
                                it.start + it.len <= nr.start ||
                                    it.start >= nr.start + nr.len
                            } + nr).sortedBy { it.start }
                        },
                        enabled = selRange() != null,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("text-run-apply")
                    ) {
                        Text(stringResource(R.string.text_run_apply))
                    }
                    if (runs.isNotEmpty()) {
                        TextButton(
                            onClick = { runs = emptyList() },
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("text-run-clear")
                        ) {
                            Text("×${runs.size}")
                        }
                    }
                }
                if (selRange() == null) {
                    Text(
                        stringResource(R.string.text_run_empty),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                // Outline (T2 §1): toggle + width (color = ink).
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.text_outline),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Switch(
                        checked = outlineOn,
                        onCheckedChange = { outlineOn = it },
                        modifier = Modifier.testTag("text-outline-toggle")
                    )
                }
                if (outlineOn) {
                    Text(
                        stringResource(R.string.text_outline_width, outlineW),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Slider(
                        value = outlineW,
                        onValueChange = { outlineW = it },
                        valueRange = 0.5f..8f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("text-outline-width")
                    )
                }
                // Direction + hyphen (T2 §2).
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    listOf(
                        "h" to R.string.text_dir_h,
                        "v" to R.string.text_dir_v
                    ).forEach { (v, label) ->
                        FilterChip(
                            selected = dir == v,
                            onClick = { dir = v },
                            label = { Text(stringResource(label)) },
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("text-dir-$v")
                        )
                    }
                    FilterChip(
                        selected = hyphen,
                        onClick = { hyphen = !hyphen },
                        label = { Text(stringResource(R.string.text_hyphen)) },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("text-hyphen")
                    )
                }
                Text(
                    stringResource(R.string.text_leading, leading),
                    style = MaterialTheme.typography.labelSmall
                )
                Slider(
                    value = leading,
                    onValueChange = { leading = it },
                    valueRange = 0.8f..2.0f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("text-leading")
                )
                Text(
                    stringResource(R.string.text_tracking, tracking),
                    style = MaterialTheme.typography.labelSmall
                )
                Slider(
                    value = tracking,
                    onValueChange = { tracking = it },
                    valueRange = -8f..8f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("text-tracking")
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("text-cancel")
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        onClick = {
                            onCommit(
                                initial.copy(
                                    text = field.text,
                                    font = font,
                                    sizePx = size.roundToInt().coerceIn(8, 1024),
                                    bold = bold,
                                    italic = italic,
                                    align = align,
                                    leading = leading.coerceIn(0.5f, 4f),
                                    tracking = tracking.coerceIn(-64f, 64f),
                                    colorHex = inkHex,
                                    runs = runs,
                                    outline = if (outlineOn) TextOutline(
                                        outlineW.coerceIn(0.5f, 8f),
                                        inkHex
                                    ) else null,
                                    dir = dir,
                                    hyphen = hyphen
                                )
                            )
                        },
                        enabled = field.text.isNotBlank(),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("text-validate")
                    ) {
                        Text(stringResource(R.string.text_validate))
                    }
                }
            }
        }
    }
}

/**
 * T2 §2 inline canvas editor: the field lives ON the canvas, anchored
 * to the text frame (engineToScreen space — the caller renders it in
 * the canvas-box overlay so it follows pan/pinch/rotate via view
 * ticks). Valider = 1-undo commit, Réglages = full TextPanel,
 * Annuler/Échap = drop (no pixels). Typing is overlay-only (hors
 * undo, like the T1 preview).
 */
@Composable
fun InlineTextBox(
    surface: EngineSurface,
    anchor: Pair<Float, Float>,
    initial: TextSpec,
    onCommit: (String) -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var field by remember(anchor, initial.text) {
        mutableStateOf(TextFieldValue(initial.text))
    }
    val (sx, sy) = remember(anchor) {
        surface.engineToScreen(anchor.first, anchor.second)
    }
    androidx.compose.foundation.layout.Box(
        modifier = modifier.fillMaxSize().testTag("text-inline")
    ) {
        androidx.compose.material3.Surface(
            shape = MaterialTheme.shapes.small,
            tonalElevation = 8.dp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offsetPx(sx, sy)
                .padding(4.dp)
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = field,
                    onValueChange = { field = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("text-inline-field"),
                    decorationBox = { inner ->
                        if (field.text.isEmpty()) {
                            Text(
                                stringResource(R.string.text_inline_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        inner()
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("text-inline-cancel")
                    ) {
                        Text(stringResource(R.string.text_inline_cancel))
                    }
                    TextButton(
                        onClick = onSettings,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("text-inline-settings")
                    ) {
                        Text(stringResource(R.string.text_inline_settings))
                    }
                    Button(
                        onClick = { onCommit(field.text) },
                        enabled = field.text.isNotBlank(),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("text-inline-validate")
                    ) {
                        Text(stringResource(R.string.text_inline_validate))
                    }
                }
            }
        }
    }
}

private fun Modifier.offsetPx(sx: Float, sy: Float): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.placeRelative(
                sx.toInt().coerceAtLeast(0), sy.toInt().coerceAtLeast(0)
            )
        }
    }
