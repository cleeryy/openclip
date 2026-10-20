// OpenClip M10 — materials row: procedural clean-room tones (engine
// nApplyTone, ARCHITECTURE.md "M10 additions") + the WebDAV dialog
// entry point. No assets, no telemetry.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.openclip.R

@Composable
fun MaterialsRow(
    surface: EngineSurface,
    onWebdav: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf("") }

    fun applyMaskedTone(tone: String) {
        // V+1: under an active selection the tone fills masked
        // (flatten + history reset); on adjustment layers refused.
        val masked = surface.hasSelection()
        surface.applyToneInSelection(tone) { ok ->
            status = when {
                !ok && surface.activeLayerIsAdjustment() ->
                    ctx.getString(R.string.selection_adjust_guard)
                ok && masked -> ctx.getString(R.string.selection_flattened)
                ok -> ctx.getString(R.string.filter_applied)
                else -> ctx.getString(R.string.filter_failed)
            }
        }
    }

    Column(modifier = modifier) {
        Text(
            stringResource(R.string.materials),
            style = MaterialTheme.typography.titleSmall
        )
        if (status.isNotEmpty()) {
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.testTag("tone-status")
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            TextButton(onClick = { applyMaskedTone("hatch") }) {
                Text(stringResource(R.string.tone_hatch))
            }
            TextButton(onClick = { applyMaskedTone("crosshatch") }) {
                Text(stringResource(R.string.tone_cross))
            }
            TextButton(onClick = { applyMaskedTone("dots") }) {
                Text(stringResource(R.string.tone_dots))
            }
            TextButton(onClick = { applyMaskedTone("grid") }) {
                Text(stringResource(R.string.tone_grid))
            }
            TextButton(onClick = { applyMaskedTone("checker") }) {
                Text(stringResource(R.string.tone_checker))
            }
            TextButton(onClick = { applyMaskedTone("stripes") }) {
                Text(stringResource(R.string.tone_stripes))
            }
            TextButton(onClick = onWebdav) {
                Text(stringResource(R.string.webdav))
            }
        }
    }
}
