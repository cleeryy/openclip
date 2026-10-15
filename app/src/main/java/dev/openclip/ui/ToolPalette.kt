// OpenClip M2 — top tool palette: brush/eraser mode, brush JSON push
// (radius/hardness/opaque/spacing + M2 keys stabilize/curve, mode), and
// undo/redo buttons whose enabled state is tracked from engine returns
// and refreshed after each stroke via EngineSurface.onStrokeChanged.
package dev.openclip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.openclip.R

data class BrushState(
    val color: String,
    val isBrush: Boolean,
    val radius: Float,
    val opaque: Float,
    val stabilize: Float = 0f,
    val curve: Float = 0f
)

private fun BrushState.toJson(): String =
    """{"radius":%f,"hardness":%f,"opaque":%f,"spacing":0.15,"color":"%s","stabilize":%f,"curve":%f,"mode":"%s"}"""
        .format(
            radius, if (isBrush) 0.7f else 0f, opaque, color,
            stabilize, curve, if (isBrush) "brush" else "eraser"
        )

private const val DEFAULT_INK = "#141414"

@Composable
fun ToolPalette(
    surface: EngineSurface,
    initialColor: String = DEFAULT_INK,
    modifier: Modifier = Modifier
) {
    var isBrush by remember { mutableStateOf(true) }
    var radius by remember { mutableStateOf(8f) }
    var opaque by remember { mutableStateOf(1f) }
    var stabilize by remember { mutableStateOf(0f) }
    var curve by remember { mutableStateOf(0f) }
    var inkColor by remember { mutableStateOf(initialColor) }
    // Real undo/redo state: a finished stroke makes undo available and
    // clears redo; tap results update both (false = stack exhausted).
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }

    fun pushBrush() = surface.setBrush(
        BrushState(inkColor, isBrush, radius, opaque, stabilize, curve).toJson()
    )

    LaunchedEffect(surface) {
        surface.onStrokeChanged = {
            canUndo = true
            canRedo = false
        }
    }

    Column(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = isBrush,
                onClick = { isBrush = true; pushBrush() },
                label = { Text(stringResource(R.string.brush)) }
            )
            FilterChip(
                selected = !isBrush,
                onClick = { isBrush = false; pushBrush() },
                label = { Text(stringResource(R.string.eraser)) }
            )
            Button(
                onClick = {
                    val ok = surface.undo()
                    if (ok) canRedo = true else canUndo = false
                },
                enabled = canUndo
            ) {
                Text(stringResource(R.string.undo), modifier = Modifier.padding(2.dp))
            }
            Button(
                onClick = {
                    val ok = surface.redo()
                    if (ok) canUndo = true else canRedo = false
                },
                enabled = canRedo
            ) {
                Text(stringResource(R.string.redo), modifier = Modifier.padding(2.dp))
            }
        }
        Text(
            stringResource(R.string.opacity, (opaque * 100).toInt()),
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall
        )
        Slider(
            value = opaque,
            onValueChange = { opaque = it; pushBrush() }
        )
        Slider(
            value = radius,
            onValueChange = { radius = it; pushBrush() },
            valueRange = 1f..128f
        )
        Text(
            stringResource(R.string.stabilize, (stabilize * 100).toInt()),
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall
        )
        Slider(
            value = stabilize,
            onValueChange = { stabilize = it; pushBrush() },
            valueRange = 0f..1f
        )
        Text(
            stringResource(R.string.curve, (curve * 100).toInt()),
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall
        )
        Slider(
            value = curve,
            onValueChange = { curve = it; pushBrush() },
            valueRange = 0f..1f
        )
    }
}
