// OpenClip — main activity: dark studio workspace with a dominant central
// canvas (EngineSurface), compact top toolbar, collapsible right inspector
// dock (Layers / Color & Brushes / 3D & Tones), and collapsible bottom
// animation timeline drawer.
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.openclip.media.AiUpscaler
import dev.openclip.media.ClipAudio
import dev.openclip.media.ExportWebm
import dev.openclip.media.WebdavClient
import dev.openclip.ui.ColorPicker
import dev.openclip.ui.EngineSurface
import dev.openclip.ui.ExportDialog
import dev.openclip.ui.LayerPanel
import dev.openclip.ui.MaterialsRow
import dev.openclip.ui.PresetGallery
import dev.openclip.ui.ReferencePanel
import dev.openclip.ui.TimelineStrip
import dev.openclip.ui.ToolPalette
import dev.openclip.ui.WebdavDialog

private val StudioColorScheme = darkColorScheme(
    primary = Color(0xFF5C9DFF),
    onPrimary = Color(0xFF0B1426),
    primaryContainer = Color(0xFF284678),
    onPrimaryContainer = Color(0xFFDCE8FF),
    secondary = Color(0xFF7E8CE0),
    secondaryContainer = Color(0xFF2E3452),
    onSecondaryContainer = Color(0xFFE0E4FF),
    background = Color(0xFF141418),
    onBackground = Color(0xFFECECF1),
    surface = Color(0xFF1E1E24),
    onSurface = Color(0xFFECECF1),
    surfaceVariant = Color(0xFF282832),
    onSurfaceVariant = Color(0xFFC4C4D0)
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dpi = resources.displayMetrics.density * 160f

        setContent {
            MaterialTheme(colorScheme = StudioColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
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
        val clipAudio = remember { ClipAudio() }
        val audioName = remember { mutableStateOf<String?>(null) }
        val exporting = remember { mutableStateOf(false) }
        val clipFps = remember { mutableStateOf(12f) }
        val mainHandler = remember { Handler(Looper.getMainLooper()) }
        val refModelName = remember { mutableStateOf<String?>(null) }
        val showWebdav = remember { mutableStateOf(false) }
        val webdavUrl = remember { mutableStateOf("") }

        // Studio workspace state: right dock open + selected tab (0 = Draw/Layers, 1 = 3D & Tones)
        // and bottom timeline drawer collapsed by default so the canvas gets maximum space.
        val dockOpen = remember { mutableStateOf(true) }
        val dockTab = remember { mutableStateOf(0) }
        val showTimeline = remember { mutableStateOf(false) }

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

        val openGlb = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            val surface = canvas.value
            if (uri != null && surface != null) {
                val name = queryDisplayName(ctx, uri) ?: "reference.glb"
                val dst = java.io.File(
                    ctx.cacheDir, "ref-${System.currentTimeMillis()}-$name"
                )
                copyTo(ctx, uri, dst)
                val edges = surface.loadGlbReference(dst.absolutePath)
                if (edges > 0) {
                    refModelName.value = name
                    surface.setRefVisible(true)
                    status.value = ctx.getString(R.string.ref_loaded, edges)
                } else {
                    dst.delete()
                    refModelName.value = null
                    status.value = ctx.getString(R.string.ref_failed)
                }
            }
        }

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
                .background(Color(0xFF141418))
                .systemBarsPadding()
        ) {
            // 1. Top Studio Toolbar
            canvas.value?.let { surface ->
                DisposableEffect(surface) {
                    onDispose { clipAudio.clear() }
                }
                ToolPalette(
                    surface = surface,
                    initialColor = inkColor.value,
                    onFileClicked = { showExport.value = true },
                    onImportClicked = { openDoc.launch(arrayOf("*/*")) }
                )
                HorizontalDivider(color = Color(0xFF2E2E38), thickness = 1.dp)
            }

            // 2. Central Workspace Row: Dominant Canvas on the left, Collapsible Studio Dock on the right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color(0xFF141418))
                ) {
                    AndroidView(
                        factory = { c ->
                            EngineSurface(c).also { cv ->
                                cv.start(dpi)
                                cv.onStrokeChanged = { refreshKey.value++ }
                                canvas.value = cv
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                canvas.value?.let { surface ->
                    if (dockOpen.value) {
                        VerticalDivider(color = Color(0xFF2E2E38), thickness = 1.dp)
                        Column(
                            modifier = Modifier
                                .width(290.dp)
                                .fillMaxHeight()
                                .background(Color(0xFF1C1C22))
                        ) {
                            // Studio Dock Header Tabs
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF22222A))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilterChip(
                                    selected = dockTab.value == 0,
                                    onClick = { dockTab.value = 0 },
                                    label = { Text("Studio", style = MaterialTheme.typography.labelSmall) }
                                )
                                FilterChip(
                                    selected = dockTab.value == 1,
                                    onClick = { dockTab.value = 1 },
                                    label = { Text("3D & Tones", style = MaterialTheme.typography.labelSmall) }
                                )
                                Box(Modifier.weight(1f))
                                TextButton(
                                    onClick = { dockOpen.value = false },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("⟩", color = Color(0xFFB8B8C8))
                                }
                            }
                            HorizontalDivider(color = Color(0xFF2E2E38), thickness = 1.dp)

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (dockTab.value == 0) {
                                    LayerPanel(
                                        surface = surface,
                                        refreshKey = refreshKey.value,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    ColorPicker(
                                        initialColor = inkColor.value,
                                        onColorChanged = { hex ->
                                            inkColor.value = hex
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    PresetGallery(
                                        surface = surface,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                } else {
                                    ReferencePanel(
                                        surface = surface,
                                        onImportGlb = { openGlb.launch(arrayOf("*/*")) },
                                        modelName = refModelName.value,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    MaterialsRow(
                                        surface = surface,
                                        onWebdav = { showWebdav.value = true },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Collapsible Bottom Timeline Drawer & Footer Bar
            canvas.value?.let { surface ->
                HorizontalDivider(color = Color(0xFF2E2E38), thickness = 1.dp)
                if (showTimeline.value) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 175.dp)
                            .background(Color(0xFF1C1C22))
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        TimelineStrip(
                            surface = surface,
                            refreshKey = refreshKey.value,
                            modifier = Modifier.fillMaxWidth(),
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
                    }
                    HorizontalDivider(color = Color(0xFF2E2E38), thickness = 1.dp)
                }

                // Slim Studio Status & Drawer Toggle Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .background(Color(0xFF1E1E24))
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = { showTimeline.value = !showTimeline.value },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(
                            (if (showTimeline.value) "▾ " else "▸ ") +
                                stringResource(R.string.timeline),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF90B8FF)
                        )
                    }
                    TextButton(
                        onClick = { showWebdav.value = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(
                            stringResource(R.string.webdav),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFC4C4D0)
                        )
                    }
                    Text(
                        "2048×1536",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF787888)
                    )
                    if (status.value.isNotEmpty()) {
                        Text(
                            status.value,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF80E27E)
                        )
                    }
                    Box(Modifier.weight(1f))
                    if (!dockOpen.value) {
                        TextButton(
                            onClick = { dockOpen.value = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(
                                "⟨ " + stringResource(R.string.layers),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF90B8FF)
                            )
                        }
                    }
                }

                if (showWebdav.value) {
                    WebdavDialog(
                        onDismiss = { showWebdav.value = false },
                        initialUrl = webdavUrl.value,
                        onAction = { push, url, user, pass ->
                            webdavUrl.value = url
                            showWebdav.value = false
                            if (url.isBlank()) {
                                status.value =
                                    ctx.getString(R.string.webdav_failed)
                                return@WebdavDialog
                            }
                            Thread {
                                var ok: Boolean
                                val doc = java.io.File(
                                    ctx.cacheDir, "webdav-${System.currentTimeMillis()}.ora"
                                )
                                if (push) {
                                    var saved = false
                                    val lock = Object()
                                    surface.saveTo(doc.absolutePath, "ora") { s ->
                                        saved = s
                                        synchronized(lock) { lock.notifyAll() }
                                    }
                                    synchronized(lock) {
                                        while (!saved) lock.wait(20000)
                                    }
                                    ok = saved && WebdavClient.push(
                                        doc, url.trim(), user, pass
                                    )
                                } else {
                                    ok = WebdavClient.pull(
                                        url.trim(), user, pass, doc
                                    )
                                    if (ok) {
                                        val dst = doc.absolutePath
                                        var loaded = false
                                        val lock2 = Object()
                                        surface.loadFrom(dst) { l ->
                                            loaded = l
                                            synchronized(lock2) { lock2.notifyAll() }
                                        }
                                        synchronized(lock2) {
                                            while (!loaded) lock2.wait(20000)
                                        }
                                        ok = loaded
                                        refreshKey.value++
                                    }
                                }
                                doc.delete()
                                val result = ok
                                mainHandler.post {
                                    status.value = if (result)
                                        ctx.getString(R.string.webdav_ok)
                                    else ctx.getString(R.string.webdav_failed)
                                }
                            }.start()
                        }
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
