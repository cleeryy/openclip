// OpenClip — main activity: Compose shell hosting the canvas SurfaceView
// plus tool palette, color picker and layer panel. Phase 1 layout keeps
// the canvas dominant with slim panels below; a tablet sidebar layout
// comes with the M5 tool work.
package dev.openclip

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.openclip.R
import dev.openclip.media.AiUpscaler
import dev.openclip.media.ClipAudio
import dev.openclip.media.ExportWebm
import dev.openclip.ui.ColorPicker
import dev.openclip.ui.EngineSurface
import dev.openclip.ui.ExportDialog
import dev.openclip.ui.LayerPanel
import dev.openclip.ui.PresetGallery
import dev.openclip.ui.TimelineStrip
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
        // M7b: clip audio (session-only) + webm export state. fps mirrors
        // the strip slider (default 12, contract: no engine symbol).
        val clipAudio = remember { ClipAudio() }
        val audioName = remember { mutableStateOf<String?>(null) }
        val exporting = remember { mutableStateOf(false) }
        val clipFps = remember { mutableStateOf(12f) }
        val mainHandler = remember { Handler(Looper.getMainLooper()) }

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

        // M7b: audio import (audio/* → cache copy → ClipAudio attach).
        val openAudio = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null) {
                val name = queryDisplayName(ctx, uri) ?: "audio"
                val dst = java.io.File(
                    ctx.cacheDir, "audio-${System.currentTimeMillis()}-$name"
                )
                copyTo(ctx, uri, dst)
                if (clipAudio.attach(dst.absolutePath, name)) {
                    audioName.value = name
                    status.value = ctx.getString(R.string.import_ok) + " " + name
                } else {
                    dst.delete()
                    status.value = ctx.getString(R.string.audio_failed)
                }
            }
        }

        // M7b: webm export. Frames pull full-res off the render thread in
        // index order; the mux runs on a worker thread, status posts back
        // on the UI thread (M4 cache-file bridge for the SAF uri).
        fun exportClipFrames(
            surface: EngineSurface,
            count: Int,
            fps: Float,
            dst: java.io.File,
            done: (Boolean) -> Unit
        ) {
            if (count <= 0) {
                done(false)
                return
            }
            val acc = mutableListOf<Bitmap>()
            fun pull(i: Int) {
                if (i >= count) {
                    Thread {
                        var ok = false
                        try {
                            ok = ExportWebm.export(acc.toList(), fps, dst)
                        } catch (_: Exception) {
                            ok = false
                        } finally {
                            acc.forEach { bmp ->
                                if (!bmp.isRecycled) bmp.recycle()
                            }
                            acc.clear()
                        }
                        val result = ok
                        mainHandler.post { done(result) }
                    }.start()
                    return
                }
                surface.pullFrameFull(i) { bmp ->
                    if (bmp == null) {
                        acc.forEach { b ->
                            if (!b.isRecycled) b.recycle()
                        }
                        acc.clear()
                        done(false)
                        return@pullFrameFull
                    }
                    acc.add(bmp)
                    pull(i + 1)
                }
            }
            pull(0)
        }

        // M8: AI x3 super-resolution of the current frame (ONNX, app-side;
        // result loads back into the frame via the M7 load contract).
        val aiBusy = remember { mutableStateOf(false) }
        fun aiUpscaleFrame(surface: EngineSurface, frameIdx: Int) {
            if (aiBusy.value || exporting.value) return
            aiBusy.value = true
            surface.pullFrameFull(frameIdx) { bmp ->
                if (bmp == null) {
                    aiBusy.value = false
                    status.value = ctx.getString(R.string.ai_failed)
                    return@pullFrameFull
                }
                Thread {
                    var ok = false
                    try {
                        val up = AiUpscaler.upscale(ctx, bmp)
                        if (up != null) {
                            val png = java.io.File(
                                ctx.cacheDir, "ai-${System.currentTimeMillis()}.png"
                            )
                            val wrote = png.outputStream().use { o ->
                                up.compress(
                                    android.graphics.Bitmap.CompressFormat.PNG, 100, o
                                )
                            }
                            up.recycle()
                            if (wrote) {
                                ok = true
                                val p = png.absolutePath
                                surface.loadFrom(p) { loaded ->
                                    java.io.File(p).delete()
                                    aiBusy.value = false
                                    status.value = if (loaded)
                                        ctx.getString(R.string.ai_upscaled)
                                    else ctx.getString(R.string.ai_failed)
                                    refreshKey.value++
                                }
                            } else {
                                java.io.File(png.absolutePath).delete()
                            }
                        }
                    } catch (_: Exception) {
                        ok = false
                    } finally {
                        if (!bmp.isRecycled) bmp.recycle()
                    }
                    if (!ok) {
                        aiBusy.value = false
                        status.value = ctx.getString(R.string.ai_failed)
                    }
                }.start()
            }
        }

        val createWebm = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("video/webm")
        ) { uri: Uri? ->
            val surface = canvas.value
            if (uri != null && surface != null && !exporting.value) {
                exporting.value = true
                val count = surface.frameCount()
                val dst = java.io.File(
                    ctx.cacheDir, "openclip-${System.currentTimeMillis()}.webm"
                )
                exportClipFrames(surface, count, clipFps.value, dst) { ok ->
                    if (ok) {
                        copyFromTo(ctx, dst, uri)
                        status.value = ctx.getString(R.string.export_ok)
                    } else {
                        status.value = ctx.getString(R.string.export_failed)
                    }
                    dst.delete()
                    exporting.value = false
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
                // M7: timeline strip — full width under the panels so the
                // thumbnails stay tappable on small screens. M7b wires
                // audio + webm export through it (state lives here).
                DisposableEffect(surface) {
                    onDispose { clipAudio.clear() }
                }
                TimelineStrip(
                    surface = surface,
                    refreshKey = refreshKey.value,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    audioName = audioName.value,
                    exporting = exporting.value,
                    onPlayingChange = { playing ->
                        if (playing) clipAudio.play() else clipAudio.pause()
                    },
                    onFpsChange = { clipFps.value = it },
                    onImportAudio = { openAudio.launch(arrayOf("audio/*")) },
                    onClearAudio = {
                        clipAudio.clear()
                        audioName.value = null
                    },
                    onExportWebm = {
                        createWebm.launch(
                            "openclip-${System.currentTimeMillis()}.webm"
                        )
                    },
                    onAiUpscale = { frameIdx ->
                        aiUpscaleFrame(surface, frameIdx)
                    }
                )
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
