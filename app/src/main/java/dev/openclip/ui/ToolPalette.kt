// OpenClip M1 — top tool palette: brush mode buttons plus brush JSON push
// to the engine via EngineSurface.setBrush via the frozen JNI contract.
package dev.openclip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.layout_padding_placeholder
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    val opaque: Float
)

private fun BrushState.toJson(): String =
    """{"radius":%f,"hardness":%f,"opaque":%f,"spacing":0.15,"color":"%s"}"""
        .format(radius, if (isBrush) 0.7f else 0f, opaque, color)

private const val DEFAULT_INK = "#141414"

@Composable
fun ToolPalette(surface: EngineSurface, initialColor: String = DEFAULT_INK, modifier: Modifier = Modifier) {
    var isBrush by remember { mutableStateOf(true) }
    var radius by remember { mutableStateOf(8f) }
    var opaque by remember { mutableStateOf(1f) }
    var inkColor by remember { mutableStateOf(initialColor) }

    fun pushBrush() = surface.setBrush(
        BrushState(inkColor, isBrush, radius, opaque).toJson()
    )

    Row(
        modifier = modifier,
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
        Button(onClick = { surface.undo() }) {
            Text(stringResource(R.string.undo), modifier = Modifier.padding(2.dp))
        }
        Button(onClick = { surface.redo() }) {
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
}
