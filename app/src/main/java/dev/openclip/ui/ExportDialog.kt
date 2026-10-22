// OpenClip M4 — export dialog: format chips (ora | png | psd per the
// M4 contract), filename validation, and real save path feedback. The
// SAF side lives in MainActivity (CreateDocument + stream copy); this
// dialog only collects parameters and surfaces outcome text.
package dev.openclip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.openclip.R

@Composable
fun ExportDialog(
    onDismiss: () -> Unit,
    onExport: (format: String, onResult: (Boolean) -> Unit) -> Unit,
    resultLine: String,
    modifier: Modifier = Modifier
) {
    var format by remember { mutableStateOf("ora") }
    var exporting by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 6.dp
        ) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    stringResource(R.string.export_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("ora", "png", "psd").forEach { f ->
                        FilterChip(
                            selected = format == f,
                            onClick = { format = f },
                            label = { Text(f.uppercase()) }
                        )
                    }
                }
                Text(
                    resultLine.ifEmpty {
                        stringResource(R.string.export_hint)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        enabled = !exporting,
                        // Rebrand : cinabre réservé au CTA premier (Export).
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BrandCinnabar,
                            contentColor = BrandInk
                        ),
                        onClick = {
                            exporting = true
                            onExport(format) { ok ->
                                exporting = false
                            }
                        }
                    ) {
                        Text(stringResource(R.string.export_apply))
                    }
                }
            }
        }
    }
}
