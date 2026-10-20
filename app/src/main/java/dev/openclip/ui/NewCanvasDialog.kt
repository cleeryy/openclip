// OpenClip slice 6 — new-canvas dialog (app-side only, no JNI).
//
// Width/height steppers (±128) + numeric fields, dpi choices (72/150/300/
// 600, passed to nInit only), background chips (white / transparent / ink
// tint from the current ink), portrait/landscape swap, 1-tap presets,
// non-blocking small-canvas hint (< 2000 px), RAM estimate, hard clamp at
// 4096 px (polite refusal). Cancel creates nothing (no orphan handle).
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.openclip.R
import dev.openclip.doc.DocOra

const val NEWCANVAS_MIN_PRESET_HINT = 2000
const val NEWCANVAS_MAX_DIM = 4096
const val NEWCANVAS_STEP = 128

enum class NewCanvasBg { WHITE, TRANSPARENT, INK }

data class NewCanvasSpec(
    val name: String,
    val w: Int,
    val h: Int,
    val dpi: Float,
    val bg: NewCanvasBg
)

@Composable
fun NewCanvasDialog(
    defaultName: String,
    defaultW: Int = 2048,
    defaultH: Int = 1536,
    onConfirm: (NewCanvasSpec) -> Unit,
    onDismiss: () -> Unit
) {
    val name = remember { mutableStateOf(defaultName) }
    val wText = remember { mutableStateOf(defaultW.toString()) }
    val hText = remember { mutableStateOf(defaultH.toString()) }
    val dpi = remember { mutableStateOf(300) }
    val bg = remember { mutableStateOf(NewCanvasBg.WHITE) }

    fun intOf(t: String, fallback: Int): Int =
        t.toIntOrNull()?.coerceIn(1, 99999) ?: fallback
    val w = intOf(wText.value, defaultW)
    val h = intOf(hText.value, defaultH)
    val estimate = DocOra.estimateMb(w.coerceAtLeast(1), h.coerceAtLeast(1))

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Text(
                stringResource(R.string.newcanvas_title),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name.value,
                    onValueChange = { name.value = it.take(48) },
                    label = { Text(stringResource(R.string.newcanvas_name)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("newcanvas-name")
                )
                DimRow(
                    label = "W",
                    valueText = wText.value,
                    tag = "newcanvas-width",
                    onMinus = {
                        wText.value = (intOf(wText.value, defaultW) - NEWCANVAS_STEP)
                            .coerceAtLeast(1).toString()
                    },
                    onPlus = {
                        wText.value = (intOf(wText.value, defaultW) + NEWCANVAS_STEP).toString()
                    },
                    onEdit = { wText.value = it.filter { c -> c.isDigit() }.take(4) }
                )
                DimRow(
                    label = "H",
                    valueText = hText.value,
                    tag = "newcanvas-height",
                    onMinus = {
                        hText.value = (intOf(hText.value, defaultH) - NEWCANVAS_STEP)
                            .coerceAtLeast(1).toString()
                    },
                    onPlus = {
                        hText.value = (intOf(hText.value, defaultH) + NEWCANVAS_STEP).toString()
                    },
                    onEdit = { hText.value = it.filter { c -> c.isDigit() }.take(4) }
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            val tw = wText.value
                            wText.value = hText.value
                            hText.value = tw
                        },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("newcanvas-orient")
                    ) {
                        Text("⇄", style = MaterialTheme.typography.titleMedium)
                    }
                    TextButton(
                        onClick = {
                            wText.value = "2048"
                            hText.value = "1536"
                        },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("newcanvas-preset-2048")
                    ) {
                        Text(
                            "2048×1536",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    TextButton(
                        onClick = {
                            wText.value = "3000"
                            hText.value = "2000"
                        },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("newcanvas-preset-3000")
                    ) {
                        Text(
                            "3000×2000",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (d in listOf(72, 150, 300, 600)) {
                        FilterChip(
                            selected = dpi.value == d,
                            onClick = { dpi.value = d },
                            label = {
                                Text(
                                    "$d",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            modifier = Modifier.testTag("newcanvas-dpi-$d")
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BgChip(
                        selected = bg.value == NewCanvasBg.WHITE,
                        tag = "newcanvas-bg-white",
                        labelRes = R.string.newcanvas_bg_white,
                        onClick = { bg.value = NewCanvasBg.WHITE }
                    )
                    BgChip(
                        selected = bg.value == NewCanvasBg.TRANSPARENT,
                        tag = "newcanvas-bg-transparent",
                        labelRes = R.string.newcanvas_bg_transparent,
                        onClick = { bg.value = NewCanvasBg.TRANSPARENT }
                    )
                    BgChip(
                        selected = bg.value == NewCanvasBg.INK,
                        tag = "newcanvas-bg-ink",
                        labelRes = R.string.newcanvas_bg_ink,
                        onClick = { bg.value = NewCanvasBg.INK }
                    )
                }
                Text(
                    String.format(java.util.Locale.US, "≈ %.0f MB", estimate),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF787888),
                    modifier = Modifier.testTag("newcanvas-estimate")
                )
                if (maxOf(w, h) < NEWCANVAS_MIN_PRESET_HINT) {
                    Text(
                        stringResource(R.string.newcanvas_small_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFE0A050),
                        modifier = Modifier.testTag("newcanvas-small-hint")
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        NewCanvasSpec(
                            name = name.value.ifBlank { defaultName },
                            w = w, h = h,
                            dpi = dpi.value.toFloat(),
                            bg = bg.value
                        )
                    )
                },
                modifier = Modifier
                    .height(48.dp)
                    .testTag("newcanvas-confirm")
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("newcanvas-cancel")
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun DimRow(
    label: String,
    valueText: String,
    tag: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onEdit: (String) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.size(20.dp)
        )
        TextButton(
            onClick = onMinus,
            modifier = Modifier
                .size(48.dp)
                .testTag("$tag-minus")
        ) {
            Text("−", style = MaterialTheme.typography.titleMedium)
        }
        OutlinedTextField(
            value = valueText,
            onValueChange = onEdit,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .weight(1f)
                .testTag(tag)
        )
        TextButton(
            onClick = onPlus,
            modifier = Modifier
                .size(48.dp)
                .testTag("$tag-plus")
        ) {
            Text("+", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun BgChip(selected: Boolean, tag: String, labelRes: Int, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                stringResource(labelRes),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        },
        modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag(tag)
    )
}
