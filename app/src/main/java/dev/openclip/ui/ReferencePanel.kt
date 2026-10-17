// OpenClip M9 — reference pane: drives the glTF reference model overlay
// (mannequin tracing aid). Sliders push the full view JSON on release;
// the engine renders the wireframe OVER the frame in nCompositeInto
// only (never in thumbnails/export). State is owned here; the wrappers
// on EngineSurface are the only engine contact (ARCHITECTURE.md "M9
// additions").
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.openclip.R
import java.util.Locale

@Composable
fun ReferencePanel(
    surface: EngineSurface,
    onImportGlb: () -> Unit,
    modelName: String?,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(false) }
    var yaw by remember { mutableStateOf(0f) }
    var pitch by remember { mutableStateOf(0f) }
    var roll by remember { mutableStateOf(0f) }
    var dist by remember { mutableStateOf(3f) }
    var fov by remember { mutableStateOf(45f) }
    var fisheye by remember { mutableStateOf(0f) }

    fun push() {
        surface.setRefView(
            String.format(
                Locale.US,
                "{\"yaw\":%.2f,\"pitch\":%.2f,\"roll\":%.2f,\"dist\":%.2f," +
                    "\"fov\":%.2f,\"fisheye\":%.2f,\"offX\":0,\"offY\":0}",
                yaw, pitch, roll, dist, fov, fisheye
            )
        )
    }

    Column(modifier = modifier) {
        Text(
            stringResource(R.string.reference),
            style = MaterialTheme.typography.titleSmall
        )
        if (modelName == null) {
            TextButton(onClick = onImportGlb) {
                Text(stringResource(R.string.reference_import))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modelName,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 8.dp)
                )
                TextButton(onClick = { surface.setRefVisible(false) }) {
                    Text(stringResource(R.string.reference_clear))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = visible,
                onCheckedChange = {
                    visible = it
                    surface.setRefVisible(it)
                }
            )
            Text(
                stringResource(R.string.reference_show),
                style = MaterialTheme.typography.labelSmall
            )
        }
        @Composable
        fun sliderRow(
            label: String,
            value: Float,
            range: ClosedFloatingPointRange<Float>,
            onDone: (Float) -> Unit
        ) {
            var local by remember(value) { mutableStateOf(value) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Slider(
                    value = local,
                    onValueChange = { local = it },
                    onValueChangeFinished = { onDone(local) },
                    valueRange = range,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        sliderRow(
            stringResource(R.string.yaw, yaw.toInt()), yaw, 0f..360f
        ) { yaw = it; push() }
        sliderRow(
            stringResource(R.string.pitch, pitch.toInt()), pitch, 0f..360f
        ) { pitch = it; push() }
        sliderRow(
            stringResource(R.string.roll, roll.toInt()), roll, 0f..360f
        ) { roll = it; push() }
        sliderRow(
            stringResource(R.string.ref_dist, dist), dist, 0.5f..10f
        ) { dist = it; push() }
        sliderRow(
            stringResource(R.string.fov, fov.toInt()), fov, 10f..120f
        ) { fov = it; push() }
        sliderRow(
            stringResource(R.string.fisheye, (fisheye * 100).toInt()),
            fisheye, 0f..1f
        ) { fisheye = it; push() }
    }
}
