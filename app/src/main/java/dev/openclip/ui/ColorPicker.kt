// OpenClip — studio color picker: quick palette swatches + labeled RGB
// sliders + live swatch & hex readout.
package dev.openclip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.openclip.R
import java.util.Locale
import kotlin.math.roundToInt

private val QUICK_SWATCHES = listOf(
    "#141414", "#FFFFFF", "#E53935", "#FB8C00",
    "#FDD835", "#43A047", "#1E88E5", "#8E24AA",
    "#F48FB1", "#8D6E63"
)

@Composable
fun ColorPicker(
    initialColor: String = "#141414",
    onColorChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    fun parse(s: String): Color = try {
        Color(android.graphics.Color.parseColor(s))
    } catch (_: Exception) {
        Color(0xFF141414)
    }

    var red by remember(initialColor) { mutableStateOf(parse(initialColor).red) }
    var green by remember(initialColor) { mutableStateOf(parse(initialColor).green) }
    var blue by remember(initialColor) { mutableStateOf(parse(initialColor).blue) }

    fun hex(): String = String.format(
        Locale.US,
        "#%02X%02X%02X",
        (red * 255).roundToInt().coerceIn(0, 255),
        (green * 255).roundToInt().coerceIn(0, 255),
        (blue * 255).roundToInt().coerceIn(0, 255)
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF25252D), RoundedCornerShape(6.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        Color(
                            (red * 255).roundToInt().coerceIn(0, 255),
                            (green * 255).roundToInt().coerceIn(0, 255),
                            (blue * 255).roundToInt().coerceIn(0, 255)
                        )
                    )
                    .border(1.dp, Color(0xFF555566), RoundedCornerShape(4.dp))
            )
            Text(
                hex(),
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFECECF1)
            )
        }

        // Quick studio color swatches
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            QUICK_SWATCHES.forEach { swatchHex ->
                val c = parse(swatchHex)
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(c)
                        .border(1.dp, Color(0xFF4A4A58), RoundedCornerShape(3.dp))
                        .clickable {
                            red = c.red
                            green = c.green
                            blue = c.blue
                            onColorChanged(swatchHex)
                        }
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.red, (red * 255).roundToInt()),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFFF8A80),
                modifier = Modifier.width(64.dp)
            )
            Slider(
                value = red,
                onValueChange = { red = it; onColorChanged(hex()) },
                modifier = Modifier
                    .weight(1f)
                    .height(24.dp)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.green, (green * 255).roundToInt()),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF80E27E),
                modifier = Modifier.width(64.dp)
            )
            Slider(
                value = green,
                onValueChange = { green = it; onColorChanged(hex()) },
                modifier = Modifier
                    .weight(1f)
                    .height(24.dp)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.blue, (blue * 255).roundToInt()),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF82B1FF),
                modifier = Modifier.width(64.dp)
            )
            Slider(
                value = blue,
                onValueChange = { blue = it; onColorChanged(hex()) },
                modifier = Modifier
                    .weight(1f)
                    .height(24.dp)
            )
        }
    }
}
