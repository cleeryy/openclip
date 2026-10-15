// OpenClip — main activity: Compose shell hosting the canvas SurfaceView
// plus tool palette, color picker and layer panel. Phase 1 layout keeps
// the canvas dominant with slim panels below; a tablet sidebar layout
// comes with the M5 tool work.
package dev.openclip

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.openclip.R
import dev.openclip.ui.ColorPicker
import dev.openclip.ui.EngineSurface
import dev.openclip.ui.ExportDialog
import dev.openclip.ui.LayerPanel
import dev.openclip.ui.PresetGallery
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
        val status = remember { mutableStateOf("") }
        val showExport = remember { mutableStateOf(false) }
        val exportFormat = remember { mutableStateOf("ora") }
        val ctx = LocalContext.current

        // M4: SAF bridge. Files go through cacheDir copies so the engine
        // keeps seeing plain filesystem paths only (no Android IO inside
        // the engine).
        val openDoc = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null && canvas.value != null) {
                val name = queryDisplayName(ctx, uri) ?: "imported.ora"
                val dst = java.io.File(ctx.cacheDir, name)
                copyTo(ctx, uri, dst)
                canvas.value?.loadFrom(dst.absolutePath) { ok ->
                    status.value = if (ok)
                        ctx.getString(R.string.import_ok) + " " + name
                    else ctx.getString(R.string.import_failed)
                    refreshKey.value++
                }
            }
        }

        val createDoc = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri: Uri? ->
            if (uri != null && canvas.value != null) {
                val fmt = exportFormat.value
                val ext = if (fmt == "ora") ".ora" else if (fmt == "psd") ".psd" else ".png"
                val dst = java.io.File(
                    ctx.cacheDir, "export${System.currentTimeMillis()}$ext"
                )
                canvas.value?.saveTo(dst.absolutePath, fmt) { ok ->
                    if (ok) {
                        copyFromTo(ctx, dst, uri)
                        dst.delete()
                        status.value = ctx.getString(R.string.export_ok)
                    } else {
                        status.value = ctx.getString(R.string.export_failed)
                    }
                }
            } else if (uri != null) {
                status.value = ctx.getString(R.string.export_failed)
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            AndroidView(
                factory = { c ->
                    EngineSurface(c).also { cv ->
                        cv.start(dpi)
                        cv.onStrokeChanged = { refreshKey.value++ }
                        canvas.value = cv
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
                    onFileClicked = {
                        showExport.value = true
                    },
                    onImportClicked = { openDoc.launch(arrayOf("*/*")) },
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
                    PresetGallery(
                        surface = surface,
                        modifier = Modifier
                            .requiredWidth(220.dp)
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
                if (status.value.isNotEmpty()) {
                    androidx.compose.material3.Text(
                        status.value,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(4.dp)
                    )
                }
                if (showExport.value) {
                    ExportDialog(
                        onDismiss = { showExport.value = false },
                        onExport = { fmt, _ ->
                            exportFormat.value = fmt
                            showExport.value = false
                            createDoc.launch(
                                "openclip-${System.currentTimeMillis()}"
                            )
                        },
                        resultLine = status.value
                    )
                }
            }
        }
    }

    private fun queryDisplayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(
                android.provider.OpenableColumns.DISPLAY_NAME
            )
            (if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null)
        } ?: uri.lastPathSegment
    } catch (_: Exception) {
        null
    }

    private fun copyTo(ctx: Context, uri: Uri, dst: java.io.File) {
        ctx.contentResolver.openInputStream(uri)?.use { i ->
            dst.outputStream().use { o -> i.copyTo(o) }
        }
    }

    private fun copyFromTo(ctx: Context, src: java.io.File, uri: Uri) {
        ctx.contentResolver.openOutputStream(uri)?.use { o ->
            src.inputStream().use { i -> i.copyTo(o) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
