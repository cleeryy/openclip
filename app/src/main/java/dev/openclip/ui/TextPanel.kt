// OpenClip V+1 — text panel: multiline input + system-font layout
// controls (size 8–256px, bold/italic, alignment, leading, tracking,
// ink color). Commits through EngineSurface.commitText (platform
// StaticLayout -> PNG -> documented V+1 blit). ≥48dp targets.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import kotlin.math.roundToInt

@Composable
fun TextPanel(
    initial: TextSpec,
    inkHex: String,
    onCommit: (TextSpec) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial.text) }
    var font by remember { mutableStateOf(initial.font) }
    var size by remember { mutableStateOf(initial.sizePx.toFloat()) }
    var bold by remember { mutableStateOf(initial.bold) }
    var italic by remember { mutableStateOf(initial.italic) }
    var align by remember { mutableStateOf(initial.align) }
    var leading by remember { mutableStateOf(initial.leading) }
    var tracking by remember { mutableStateOf(initial.tracking) }
    var fontMenu by remember { mutableStateOf(false) }

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
                    value = text,
                    onValueChange = { text = it },
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
                        TEXT_SYSTEM_FONTS.forEach { f ->
                            DropdownMenuItem(
                                text = { Text(f) },
                                onClick = {
                                    font = f
                                    fontMenu = false
                                }
                            )
                        }
                    }
                }
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
                                    text = text,
                                    font = font,
                                    sizePx = size.roundToInt().coerceIn(8, 1024),
                                    bold = bold,
                                    italic = italic,
                                    align = align,
                                    leading = leading.coerceIn(0.5f, 4f),
                                    tracking = tracking.coerceIn(-64f, 64f),
                                    colorHex = inkHex
                                )
                            )
                        },
                        enabled = text.isNotBlank(),
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
