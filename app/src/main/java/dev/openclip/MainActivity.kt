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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.openclip.media.AiUpscaler
import dev.openclip.media.ClipAudio
import dev.openclip.media.ExportWebm
import dev.openclip.media.WebdavClient
import dev.openclip.doc.DocRegistry
import dev.openclip.ui.CollapsibleDrawer
import dev.openclip.ui.ColorPicker
import dev.openclip.ui.EngineSurface
import dev.openclip.ui.ExportDialog
import dev.openclip.ui.GesturesHelpDialog
import dev.openclip.ui.LayerPanel
import dev.openclip.ui.MaterialsRow
import dev.openclip.ui.MenuBar
import dev.openclip.ui.PresetGallery
import dev.openclip.ui.ReferencePanel
import dev.openclip.ui.StudioMenu
import dev.openclip.ui.StudioMenuItem
import dev.openclip.ui.TimelineStrip
import dev.openclip.ui.ToolPalette
import dev.openclip.ui.WebdavDialog
import kotlinx.coroutines.delay
import kotlin.math.abs

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
        // Phone fallback (slice 0 §3 / slice 1 §4): everything folded by default on compact widths.
        val compact = LocalConfiguration.current.screenWidthDp < 600
        val dockOpen = remember { mutableStateOf(!compact) }
        val dockTab = remember { mutableStateOf(0) }
        val showTimeline = remember { mutableStateOf(false) }

        // Slice 1 canvas gestures: 1-finger draw vs navigate mode (default
        // draw), loupe overlay tick, gestures help sheet.
        val navMode = remember { mutableStateOf(false) }
        val loupeTick = remember { mutableStateOf(0) }
        val loupeOn = remember { mutableStateOf(false) }
        val showHelp = remember { mutableStateOf(false) }

        fun toggleNav() {
            navMode.value = !navMode.value
            canvas.value?.navigateMode = navMode.value
            status.value = ctx.getString(
                if (navMode.value) R.string.status_nav_move else R.string.status_nav_draw
            )
        }
        LaunchedEffect(loupeTick.value) {
            if (loupeTick.value == 0) return@LaunchedEffect
            loupeOn.value = true
            delay(850)
            loupeOn.value = false
        }

        // Slice 0 single-doc registry: one entry ("doc-1") bound to the
        // live EngineSurface; ready for N docs, explicit dispose.
        val docRegistry = remember { DocRegistry() }
        DisposableEffect(Unit) {
            onDispose {
                canvas.value?.dispose()
                docRegistry.dispose()
            }
        }

        val openDoc = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null && canvas.value != null) {
                val name = queryDisplayName(ctx, uri) ?: "imported.ora"
                val dst = java.io.File(ctx.cacheDir, name)
                copyTo(ctx, uri, dst)
                canvas.value?.loadFrom(dst.absolutePath) { ok ->
                    if (ok) {
                        docRegistry.open("doc-1", name)
                        docRegistry.markActiveDirty(false)
                    }
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
                        docRegistry.markActiveDirty(false)
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
            // 1. Top Studio Menu Bar + Toolbar (slice 0 shell: every
            // entry reuses an existing SAF/export/engine action).
            canvas.value?.let { surface ->
                DisposableEffect(surface) {
                    onDispose { clipAudio.clear() }
                }
                val filterMenuItems = listOf(
                    "gaussianBlur" to stringResource(R.string.filter_gaussian_blur),
                    "sharpen" to stringResource(R.string.filter_sharpen),
                    "motionBlur" to stringResource(R.string.filter_motion_blur),
                    "radialBlur" to stringResource(R.string.filter_radial_blur),
                    "posterize" to stringResource(R.string.filter_posterize),
                    "levels" to stringResource(R.string.filter_levels),
                    "chromaticAberration" to stringResource(R.string.filter_chromatic_aberration),
                    "addNoise" to stringResource(R.string.filter_add_noise),
                    "mosaic" to stringResource(R.string.filter_mosaic),
                    "gradientMap" to stringResource(R.string.filter_gradient_map)
                ).map { (name, label) ->
                    StudioMenuItem(
                        label = label,
                        onClick = {
                            surface.applyFilter(name, "{}") { ok ->
                                status.value = if (ok)
                                    ctx.getString(R.string.filter_applied)
                                else ctx.getString(R.string.filter_failed)
                                if (ok) {
                                    docRegistry.markActiveDirty(true)
                                    refreshKey.value++
                                }
                            }
                        }
                    )
                }
                MenuBar(
                    menus = listOf(
                        StudioMenu(
                            title = stringResource(R.string.file_menu),
                            items = listOf(
                                StudioMenuItem(
                                    label = stringResource(R.string.import_doc),
                                    onClick = { openDoc.launch(arrayOf("*/*")) }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.export_title),
                                    onClick = { showExport.value = true }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.export_webm),
                                    onClick = {
                                        createWebm.launch(
                                            "openclip-${System.currentTimeMillis()}.webm"
                                        )
                                    }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.webdav),
                                    onClick = { showWebdav.value = true }
                                )
                            )
                        ),
                        StudioMenu(
                            title = stringResource(R.string.menu_edit),
                            items = listOf(
                                StudioMenuItem(
                                    label = stringResource(R.string.undo),
                                    onClick = {
                                        if (surface.undo()) {
                                            docRegistry.markActiveDirty(true)
                                            refreshKey.value++
                                        }
                                    }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.redo),
                                    onClick = {
                                        if (surface.redo()) {
                                            docRegistry.markActiveDirty(true)
                                            refreshKey.value++
                                        }
                                    }
                                )
                            )
                        ),
                        StudioMenu(
                            title = stringResource(R.string.menu_layer),
                            items = listOf(
                                StudioMenuItem(
                                    label = stringResource(R.string.add_layer),
                                    onClick = {
                                        surface.addLayer()
                                        docRegistry.markActiveDirty(true)
                                        refreshKey.value++
                                    }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.layer_add_group),
                                    onClick = {
                                        surface.addGroup(
                                            ctx.getString(R.string.layer_group_default)
                                        )
                                        docRegistry.markActiveDirty(true)
                                        refreshKey.value++
                                    }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.layer_add_vector),
                                    onClick = {
                                        surface.addVectorLayer(
                                            ctx.getString(R.string.vector_layer_default)
                                        )
                                        docRegistry.markActiveDirty(true)
                                        refreshKey.value++
                                    }
                                )
                            )
                        ),
                        StudioMenu(
                            title = stringResource(R.string.menu_selection),
                            items = listOf(
                                StudioMenuItem(
                                    label = stringResource(R.string.select_all),
                                    enabled = false
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.deselect),
                                    enabled = false
                                )
                            )
                        ),
                        StudioMenu(
                            title = stringResource(R.string.menu_filter),
                            items = filterMenuItems
                        ),
                        StudioMenu(
                            title = stringResource(R.string.menu_display),
                            items = listOf(
                                StudioMenuItem(
                                    label = stringResource(R.string.view_reset),
                                    onClick = { surface.resetView() }
                                ),
                                StudioMenuItem(
                                    label = stringResource(
                                        if (navMode.value) R.string.nav_mode_move
                                        else R.string.nav_mode_draw
                                    ),
                                    onClick = { toggleNav() }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.gestures_help),
                                    onClick = { showHelp.value = true }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.reference_show),
                                    enabled = refModelName.value != null,
                                    onClick = { surface.setRefVisible(true) }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.reference_clear),
                                    enabled = refModelName.value != null,
                                    onClick = { surface.setRefVisible(false) }
                                )
                            )
                        ),
                        StudioMenu(
                            title = stringResource(R.string.menu_window),
                            items = listOf(
                                StudioMenuItem(
                                    label = stringResource(R.string.window_dock),
                                    onClick = { dockOpen.value = !dockOpen.value }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.timeline),
                                    onClick = { showTimeline.value = !showTimeline.value }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.window_studio),
                                    onClick = {
                                        dockTab.value = 0
                                        dockOpen.value = true
                                    }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.window_tones),
                                    onClick = {
                                        dockTab.value = 1
                                        dockOpen.value = true
                                    }
                                )
                            )
                        ),
                        StudioMenu(
                            title = stringResource(R.string.menu_help),
                            items = listOf(
                                StudioMenuItem(
                                    label = stringResource(R.string.gestures_help),
                                    onClick = { showHelp.value = true }
                                )
                            )
                        )
                    )
                )
                HorizontalDivider(color = Color(0xFF2E2E38), thickness = 1.dp)
                ToolPalette(
                    surface = surface,
                    initialColor = inkColor.value,
                    navigateMode = navMode.value,
                    onNavigateToggle = { toggleNav() },
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
                                cv.navigateMode = navMode.value
                                cv.edgeBottomEnabled = compact
                                cv.onGestureUndo = {
                                    docRegistry.markActiveDirty(true)
                                    refreshKey.value++
                                    status.value = ctx.getString(R.string.undo)
                                }
                                cv.onGestureRedo = {
                                    docRegistry.markActiveDirty(true)
                                    refreshKey.value++
                                    status.value = ctx.getString(R.string.redo)
                                }
                                cv.onEdgeSwipe = { edge ->
                                    if (edge == EngineSurface.EDGE_BOTTOM) {
                                        showTimeline.value = true
                                    } else {
                                        dockOpen.value = true
                                    }
                                }
                                cv.onViewChanged = { loupeTick.value++ }
                                cv.onStrokeChanged = {
                                    refreshKey.value++
                                    docRegistry.markActiveDirty(true)
                                }
                                canvas.value = cv
                                if (docRegistry.size == 0) {
                                    docRegistry.open(
                                        "doc-1",
                                        c.getString(R.string.doc_untitled)
                                    )
                                }
                            }
                        },
                        update = { cv ->
                            cv.navigateMode = navMode.value
                            cv.edgeBottomEnabled = compact
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    // Slice 1 loupe chip: zoom % + angle + 1-tap view reset,
                    // visible while the canvas reports navigation.
                    val surf = canvas.value
                    if (surf != null && loupeOn.value) {
                        var loupeLabel = stringResource(
                            R.string.view_zoom_fit, surf.viewZoomPercent()
                        )
                        if (abs(surf.viewAngle()) >= 0.5f) {
                            loupeLabel += " " + stringResource(
                                R.string.view_angle, surf.viewAngle().toInt()
                            )
                        }
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                                .background(Color(0xCC101014))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                loupeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFECECF1),
                                maxLines = 1
                            )
                            TextButton(
                                onClick = { surf.resetView() },
                                contentPadding = PaddingValues(
                                    horizontal = 8.dp, vertical = 0.dp
                                ),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text(
                                    stringResource(R.string.view_reset),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF90B8FF),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                canvas.value?.let { surface ->
                    VerticalDivider(color = Color(0xFF2E2E38), thickness = 1.dp)
                    CollapsibleDrawer(
                        title = stringResource(R.string.window_dock),
                        open = dockOpen.value,
                        onToggle = { dockOpen.value = !dockOpen.value },
                        modifier = Modifier
                            .width(if (dockOpen.value) 290.dp else 56.dp)
                            .fillMaxHeight(),
                        expandGlyph = "⟨",
                        collapseGlyph = "⟩",
                        showTitleWhenCollapsed = false,
                        headerExtra = {
                            FilterChip(
                                selected = dockTab.value == 0,
                                onClick = { dockTab.value = 0 },
                                label = {
                                    Text(
                                        stringResource(R.string.window_studio),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            )
                            FilterChip(
                                selected = dockTab.value == 1,
                                onClick = { dockTab.value = 1 },
                                label = {
                                    Text(
                                        stringResource(R.string.window_tones),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            )
                        }
                    ) {
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

            // 3. Collapsible Bottom Timeline Drawer + Slim Status Footer
            canvas.value?.let { surface ->
                HorizontalDivider(color = Color(0xFF2E2E38), thickness = 1.dp)
                CollapsibleDrawer(
                    title = stringResource(R.string.timeline),
                    open = showTimeline.value,
                    onToggle = { showTimeline.value = !showTimeline.value },
                    modifier = Modifier.fillMaxWidth(),
                    expandGlyph = "▸",
                    collapseGlyph = "▾"
                ) {
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
                }
                HorizontalDivider(color = Color(0xFF2E2E38), thickness = 1.dp)

                // Slim Studio Status Bar (drawer toggles live in the
                // drawer headers above — one tap each).
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
                        "${surface.canvasWidth()}×${surface.canvasHeight()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF787888)
                    )
                    val doc = docRegistry.active()
                    if (doc != null) {
                        Text(
                            doc.name + if (doc.dirty) " •" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF787888)
                        )
                    }
                    if (status.value.isNotEmpty()) {
                        Text(
                            status.value,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF80E27E)
                        )
                    }
                    Box(Modifier.weight(1f))
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
                if (showHelp.value) {
                    GesturesHelpDialog(onDismiss = { showHelp.value = false })
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
