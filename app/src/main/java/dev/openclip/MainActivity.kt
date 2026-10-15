// OpenClip — main activity: Compose shell hosting the canvas SurfaceView
// plus tool palette, color picker and layer panel. Phase 1 layout keeps
// the canvas dominant with slim panels below; a tablet sidebar layout
// comes with the M5 tool work.
package dev.openclip

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.openclip.ui.ColorPicker
import dev.openclip.ui.EngineSurface
import dev.openclip.ui.LayerPanel
import dev.openclip.ui.ToolPalette

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dpi = resources.displayMetrics.density * 160f

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    OpenClipScreen(dpi)
                }
            }
        }
    }

    @Composable
    private fun OpenClipScreen(dpi: Float) {
        val canvas = remember { mutableStateOf<EngineSurface?>(null) }
        val refreshKey = remember { mutableStateOf(0) }
        val inkColor = remember { mutableStateOf("#141414") }

        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            AndroidView(
                factory = { ctx ->
                    EngineSurface(ctx).also { s ->
                        s.start(dpi)
                        canvas.value = s
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            canvas.value?.let { surface ->
                ToolPalette(
                    surface = surface,
                    initialColor = inkColor.value,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp)
                )
                Row(Modifier.fillMaxWidth()) {
                    ColorPicker(
                        initialColor = inkColor.value,
                        onColorChanged = { hex ->
                            inkColor.value = hex
                            surface.setBrush("""{"color":"$hex"}""")
                        },
                        modifier = Modifier
                            .width(300.dp)
                            .padding(4.dp)
                    )
                    LayerPanel(
                        surface = surface,
                        refreshKey = refreshKey.value,
                        modifier = Modifier
                            .requiredWidth(170.dp)
                            .padding(4.dp)
                    )
                }
            }
        }
    }
}
