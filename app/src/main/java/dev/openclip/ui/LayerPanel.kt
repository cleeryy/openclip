// OpenClip M1 — layer panel: lists engine layers via the M1 jackpot
// additions (nLayerCount/nAddLayer/nSetActiveLayer), plus add/remove
// actions wired to the active layer index. Refresh matches strokes when
// the composite invalidate triggers a re-render (see EngineSurface).
package dev.openclip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.openclip.R

@Composable
fun LayerPanel(
    surface: EngineSurface,
    refreshKey: Int,
    modifier: Modifier = Modifier
) {
    var layers by remember { mutableStateOf(listOf<String>()) }
    var active by remember { mutableStateOf(0) }

    LaunchedEffect(refreshKey) {
        val n = surface.layerCount()
        layers = (0 until n).map { "Layer ${it + 1}" }
        if (layers.isEmpty()) layers = listOf("Layer 1")
        active = n - 1
    }

    Column(modifier = modifier) {
        Text(
            stringResource(R.string.layers),
            style = MaterialTheme.typography.titleSmall
        )
        layers.forEachIndexed { index, name ->
            val selected = index == active
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        active = index
                        surface.setActiveLayer(index)
                    }
                    .background(
                        if (selected) MaterialTheme.colorScheme.secondaryContainer
                        else Color.Transparent
                    )
                    .padding(4.dp)
            ) {
                Text(name, style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { surface.addLayer(); active = surface.layerCount() - 1 }) {
                Text("+")
            }
            Button(onClick = {
                if (active > 0) {
                    surface.setActiveLayer(active - 1)
                    active -= 1
                }
            }) {
                Text("-")
            }
        }
    }
}
