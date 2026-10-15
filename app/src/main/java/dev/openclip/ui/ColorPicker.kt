// OpenClip M1 — quick color picker: three RGB sliders, live swatch,
// pushes the resolved hex straight to the engine through ToolPalette.
package dev.openclip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import dev.openclip.R
import kotlin.math.roundToInt

@Composable
fun ColorPicker(
    initialColor: String = "#141414",
    onColorChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    fun parse(s: String) = Color(android.graphics.Color.parseColor(s))
    var red by remember { mutableStateOf(parse(initialColor).red) }
    var green by remember { mutableStateOf(parse(initialColor).green) }
    var blue by remember { mutableStateOf(parse(initialColor).blue) }

    fun hex(): String = "#%02X%02X%02X".format(
        (red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt()
    )

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(
                        Color(
                            (red * 255).roundToInt(),
                            (green * 255).roundToInt(),
                            (blue * 255).roundToInt()
                        )
                    )
            )
            Text(
                stringResource(R.string.red, (red * 255).roundToInt()),
                modifier = Modifier.padding(start = 8.dp),
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall
            )
        }
        Slider(value = red, onValueChange = { red = it; onColorChanged(hex()) })
        Slider(value = green, onValueChange = { green = it; onColorChanged(hex()) })
        Slider(value = blue, onValueChange = { blue = it; onColorChanged(hex()) })
    }
}
