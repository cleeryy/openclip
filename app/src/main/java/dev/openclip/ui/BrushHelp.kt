// OpenClip slice 5 — brush-feeling help sheet (home-grown text-only
// dialog; no third-party diagram or capture).
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.openclip.R

@Composable
fun BrushHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ok))
            }
        },
        title = { Text(stringResource(R.string.brush_help)) },
        text = { Text(stringResource(R.string.brush_help_body)) }
    )
}
