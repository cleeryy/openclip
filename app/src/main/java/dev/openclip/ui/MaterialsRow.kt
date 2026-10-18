// OpenClip M10 — materials row: procedural clean-room tones (engine
// nApplyTone, ARCHITECTURE.md "M10 additions") + the WebDAV dialog
// entry point. No assets, no telemetry.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.openclip.R

@Composable
fun MaterialsRow(
    surface: EngineSurface,
    onWebdav: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            stringResource(R.string.materials),
            style = MaterialTheme.typography.titleSmall
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            TextButton(onClick = { surface.applyTone("hatch") }) {
                Text(stringResource(R.string.tone_hatch))
            }
            TextButton(onClick = { surface.applyTone("crosshatch") }) {
                Text(stringResource(R.string.tone_cross))
            }
            TextButton(onClick = { surface.applyTone("dots") }) {
                Text(stringResource(R.string.tone_dots))
            }
            TextButton(onClick = { surface.applyTone("grid") }) {
                Text(stringResource(R.string.tone_grid))
            }
            TextButton(onClick = { surface.applyTone("checker") }) {
                Text(stringResource(R.string.tone_checker))
            }
            TextButton(onClick = { surface.applyTone("stripes") }) {
                Text(stringResource(R.string.tone_stripes))
            }
            TextButton(onClick = onWebdav) {
                Text(stringResource(R.string.webdav))
            }
        }
    }
}
