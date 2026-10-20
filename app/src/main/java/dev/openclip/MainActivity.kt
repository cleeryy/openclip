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
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.openclip.media.AiUpscaler
import dev.openclip.media.ClipAudio
import dev.openclip.media.ExportWebm
import dev.openclip.media.WebdavClient
import dev.openclip.doc.DocOra
import dev.openclip.doc.DocRegistry
import dev.openclip.ui.CollapsibleDrawer
import dev.openclip.ui.ColorPanel
import dev.openclip.ui.ColorStore
import dev.openclip.ui.BrushPrefs
import dev.openclip.ui.CommandBar
import dev.openclip.ui.CommandBarCollapsed
import dev.openclip.ui.CommandBarStore
import dev.openclip.ui.DocTabs
import dev.openclip.ui.DocTitleBar
import dev.openclip.ui.EngineSurface
import dev.openclip.ui.NewCanvasBg
import dev.openclip.ui.NewCanvasDialog
import dev.openclip.ui.NewCanvasSpec
import dev.openclip.ui.NEWCANVAS_MAX_DIM
import dev.openclip.ui.WorkspaceDialog
import dev.openclip.ui.WorkspacePose
import dev.openclip.ui.WorkspaceStore
import dev.openclip.ui.parseHexColor
import dev.openclip.ui.ExportDialog
import dev.openclip.ui.BrushHelpDialog
import dev.openclip.ui.GesturesHelpDialog
import dev.openclip.ui.LayerPanel
import dev.openclip.ui.MaterialsRow
import dev.openclip.ui.PinnedBrushBar
import dev.openclip.ui.ChromeMenuBar
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

    // Slice 2 color single source of truth (app-side only, persisted JSON).
    lateinit var colorStore: ColorStore

    // Slice 3 brush prefs single source of truth (size/opacity/stabilize,
    // shared by ToolPalette and the pinned bottom-canvas bar).
    lateinit var brushPrefs: BrushPrefs

    // Slice 6 stores (app-side only, filesDir JSON, single writers).
    lateinit var commandStore: CommandBarStore
    lateinit var workspaceStore: WorkspaceStore

    // Slice 6 test-only hooks: wired by OpenClipScreen to the hoisted doc
    // state (all calls must run on the UI thread, e.g. via
    // scenario.onActivity).
    class ActivityTestHooks {
        var openDoc: ((String, Int, Int, Float, Int?) -> String?)? = null
        var closeDoc: ((String) -> Unit)? = null
        var activateDoc: ((String) -> Unit)? = null
        var docIds: (() -> List<String>)? = null
        var activeDocId: (() -> String?)? = null
        var surfaceFor: ((String) -> EngineSurface?)? = null
        var dirtyOf: ((String) -> Boolean)? = null
        var setSavePath: ((String, String) -> Unit)? = null
        var saveDoc: ((String) -> Unit)? = null
        var resetDocs: (() -> String)? = null
        var applyWorkspace: ((String) -> Boolean)? = null
        var currentPose: (() -> WorkspacePose)? = null
        var reloadWorkspaces: (() -> Unit)? = null
        var commandSetVisible: ((String, Boolean) -> Unit)? = null
        var commandOrder: (() -> List<String>)? = null
        var poke: (() -> Unit)? = null
        var paperReady: ((String) -> Boolean)? = null
        var clearLastWorkspace: (() -> Unit)? = null
        var pendingTab: (() -> String?)? = null
        var requestActivate: ((String) -> Unit)? = null
    }
    val activityTestHooks = ActivityTestHooks()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        colorStore = ColorStore(applicationContext)
        brushPrefs = BrushPrefs(applicationContext)
        commandStore = CommandBarStore(applicationContext)
        workspaceStore = WorkspaceStore(applicationContext)
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

    override fun onPause() {
        super.onPause()
        if (::colorStore.isInitialized) colorStore.flush()
        if (::brushPrefs.isInitialized) brushPrefs.flush()
        if (::commandStore.isInitialized) commandStore.flush()
        if (::workspaceStore.isInitialized) workspaceStore.flush()
    }

    // Slice 6 multi-doc tab record (app-side; pixels live in handles).
    data class DocTab(
        val docId: String,
        var name: String,
        val w: Int,
        val h: Int,
        val dpi: Float
    )

    @Composable
    private fun OpenClipScreen(dpi: Float) {
        val refreshKey = remember { mutableStateOf(0) }
        val store = colorStore
        val inkHex = store.inkHex
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

        // Slice 6 multi-doc state: N tabs, 1 live handle each, 1 visible.
        val docs = remember { mutableStateListOf<DocTab>() }
        val surfaces = remember { mutableStateMapOf<String, EngineSurface>() }
        val activeDocId = remember { mutableStateOf<String?>(null) }
        val docSeq = remember { mutableStateOf(0) }
        val untitledSeq = remember { mutableStateOf(0) }
        val saveUris = remember { mutableStateMapOf<String, String>() }
        val paperDone = remember { mutableStateMapOf<String, Boolean>() }
        val testSavePaths = remember { mutableStateMapOf<String, String>() }
        val showNewCanvas = remember { mutableStateOf(false) }
        val newCanvasDefault = remember { mutableStateOf("") }
        val closeConfirmId = remember { mutableStateOf<String?>(null) }
        val docsFullShow = remember { mutableStateOf(false) }
        val pendingTabId = remember { mutableStateOf<String?>(null) }
        val preselectId = remember { mutableStateOf<String?>(null) }
        val pendingSince = remember { mutableStateOf(0L) }
        val fullscreen = remember { mutableStateOf(false) }
        val showWorkspaces = remember { mutableStateOf(false) }
        val showMultidocHelp = remember { mutableStateOf(false) }
        val cmdbarVisible = remember { mutableStateOf(true) }
        // Slice 7: command bar folds to a pastille (phone default
        // posture); expanded by default so 1-tap save stays 1-tap.
        val cmdbarCollapsed = remember { mutableStateOf(false) }
        // Slice 7 one-shot session hints (status only, never dialogs).
        val prefsHintShown = remember { mutableStateOf(false) }
        val savePersistFailed = remember { mutableStateOf(false) }
        val savePersistHintShown = remember { mutableStateOf(false) }
        val pendingSaveDoc = remember { mutableStateOf<String?>(null) }
        val savePickRef = remember { mutableStateOf<((String) -> Unit)?>(null) }
        val pendingCloseAfterSave = remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
        val viewZoomTick = remember { mutableStateOf(0) }

        fun activeSurface(): EngineSurface? =
            activeDocId.value?.let { surfaces[it] }

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
        // Slice 5: brush-feeling HUD tick (render thread posts), eraser
        // banner help sheet.
        val hudTick = remember { mutableStateOf(0) }
        val showBrushHelp = remember { mutableStateOf(false) }

        fun toggleNav() {
            navMode.value = !navMode.value
            activeSurface()?.navigateMode = navMode.value
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

        // Slice 6 multi-doc registry: one entry per tab, explicit dispose
        // per handle (anti-leak DisposableEffect per surface below).
        val docRegistry = remember { DocRegistry() }
        // Deferred-switch poller holder (assigned with the doc funs below;
        // cancelled here so no callback outlives the composition).
        var deferCheck: Runnable? = null
        DisposableEffect(Unit) {
            onDispose {
                deferCheck?.let { mainHandler.removeCallbacks(it) }
                for (s in surfaces.values) {
                    try {
                        s.dispose()
                    } catch (_: Exception) {
                    }
                }
                docRegistry.dispose()
            }
        }


        val openDoc = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            val surface = activeSurface()
            if (uri != null && surface != null) {
                val name = queryDisplayName(ctx, uri) ?: "imported.ora"
                val dst = java.io.File(ctx.cacheDir, name)
                copyTo(ctx, uri, dst)
                surface.loadFrom(dst.absolutePath) { ok ->
                    if (ok) {
                        val id = activeDocId.value
                        if (id != null) {
                            docRegistry.open(id, name)
                            docs.firstOrNull { it.docId == id }?.name = name
                            docRegistry.markDirty(id, false)
                        }
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
            val surface = activeSurface()
            if (uri != null && surface != null) {
                val fmt = exportFormat.value
                val ext = if (fmt == "ora") ".ora" else if (fmt == "psd") ".psd" else ".png"
                val dst = java.io.File(
                    ctx.cacheDir, "export${System.currentTimeMillis()}$ext"
                )
                surface.saveTo(dst.absolutePath, fmt) { ok ->
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
            val surface = activeSurface()
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
            val surface = activeSurface()
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

        fun currentPose(): WorkspacePose = WorkspacePose(
            dockOpen = dockOpen.value,
            dockTab = dockTab.value,
            timelineOpen = showTimeline.value,
            navMode = navMode.value,
            commandBarVisible = cmdbarVisible.value,
            commandBarOrder = commandStore.order.toList()
        )

        fun applyPose(name: String, pose: WorkspacePose) {
            dockOpen.value = pose.dockOpen
            dockTab.value = pose.dockTab.coerceIn(0, 1)
            showTimeline.value = pose.timelineOpen
            navMode.value = pose.navMode
            activeSurface()?.navigateMode = pose.navMode
            cmdbarVisible.value = pose.commandBarVisible
            commandStore.order.clear()
            commandStore.order.addAll(pose.commandBarOrder)
            workspaceStore.lastWorkspace.value = name
            workspaceStore.flush()
            status.value = ctx.getString(R.string.workspace_applied, name)
            refreshKey.value++
        }

        fun wireSurface(cv: EngineSurface, docId: String) {
            cv.navigateMode = navMode.value
            cv.edgeBottomEnabled = compact
            cv.onGestureUndo = {
                docRegistry.markDirty(docId, true)
                refreshKey.value++
                status.value = ctx.getString(R.string.undo)
            }
            cv.onGestureRedo = {
                docRegistry.markDirty(docId, true)
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
            cv.onViewChanged = {
                loupeTick.value++
                viewZoomTick.value++
            }
            cv.onHudChanged = { hudTick.value++ }
            cv.onStrokeChanged = {
                refreshKey.value++
                docRegistry.markDirty(docId, true)
                store.pushRecent(store.inkHex)
            }
            cv.onPipetteDown = { vx, vy ->
                cv.sampleCompositeAt(vx, vy) { hex, onPaper ->
                    if (!onPaper) {
                        status.value = ctx.getString(R.string.pipette_missed)
                        store.disarmPipette()
                        cv.pipetteArmed = false
                        cv.pipetteLoupe = false
                    } else if (hex != null) {
                        if (store.pipetteLoupe) {
                            store.preview(hex)
                        } else {
                            store.pick(hex)
                            store.pushRecent(hex)
                            status.value = ctx.getString(
                                R.string.pipette_picked, hex
                            )
                            store.disarmPipette()
                            cv.pipetteArmed = false
                            cv.pipetteLoupe = false
                        }
                    }
                }
            }
            cv.onPipetteMove = { vx, vy ->
                cv.sampleCompositeAt(vx, vy) { hex, onPaper ->
                    if (hex != null && onPaper) store.preview(hex)
                }
            }
            cv.onPipetteUp = {
                val live = store.pipetteLive
                if (store.pipetteLoupe && live != null) {
                    store.pick(live)
                    store.pushRecent(live)
                    status.value = ctx.getString(
                        R.string.pipette_picked, live
                    )
                }
                store.disarmPipette()
                cv.pipetteArmed = false
                cv.pipetteLoupe = false
            }
        }

        // Plain status + refreshKey write that reliably schedules a
        // recomposition after doc-state mutations.
        fun poke() {
            status.value = "POKE"
            refreshKey.value++
        }

        fun doActivate(docId: String) {
            val cur = activeDocId.value
            if (cur != null && cur != docId) {
                surfaces[cur]?.pause()
            }
            pendingTabId.value = null
            preselectId.value = null
            docRegistry.activate(docId)
            activeDocId.value = docId
            surfaces[docId]?.let { cv ->
                cv.resume()
                cv.setBrush(brushPrefs.currentJson())
            }
            // Slice 7: tab switches post no message — the active tab
            // (select blue) + the title already carry name + dims.
            // Status green stays reserved for action feedback.
            refreshKey.value++
        }

        // Deferred stroke-guard switch inspector (Handler-driven so it
        // never depends on recomposition timing): polls the pending tab
        // until the stroke closes (ACTION_UP) or 5 s elapse. Assigned once
        // (recompositions must not replace a runnable with posted callbacks).
        if (deferCheck == null) {
            deferCheck = object : Runnable {
                override fun run() {
                    val target = pendingTabId.value ?: return
                    val cur = activeSurface()
                    if (cur?.isDrawingNow() != true) {
                        doActivate(target)
                    } else if (SystemClock.uptimeMillis() - pendingSince.value > 5000) {
                        pendingTabId.value = null
                        preselectId.value = null
                        status.value = ctx.getString(R.string.finish_stroke_hint)
                    } else {
                        mainHandler.postDelayed(this, 150)
                    }
                }
            }
        }

        fun requestActivate(docId: String) {
            if (docId == activeDocId.value) return
            val cur = activeSurface()
            if (cur?.isDrawingNow() == true) {
                preselectId.value = docId
                pendingTabId.value = docId
                pendingSince.value = SystemClock.uptimeMillis()
                status.value = ctx.getString(R.string.finish_stroke_hint)
                poke()
                deferCheck?.let {
                    mainHandler.removeCallbacks(it)
                    mainHandler.postDelayed(it, 150)
                }
            } else {
                doActivate(docId)
            }
        }

        fun bgArgbFor(specBg: NewCanvasBg): Int? = when (specBg) {
            NewCanvasBg.WHITE -> -1
            NewCanvasBg.TRANSPARENT -> null
            NewCanvasBg.INK -> DocOra.parseInkArgb(store.inkHex)
        }

        fun openDocTab(
            name: String,
            w: Int,
            h: Int,
            dpi: Float,
            bgArgb: Int?
        ): String? {
            if (!docRegistry.canOpen()) {
                docsFullShow.value = true
                status.value = ctx.getString(R.string.docs_full_hint)
                return null
            }
            docSeq.value++
            val id = "doc-" + docSeq.value
            val cv = EngineSurface(ctx, w, h)
            wireSurface(cv, id)
            cv.start(dpi)
            surfaces[id] = cv
            docs.add(DocTab(id, name, w, h, dpi))
            docRegistry.open(id, name)
            cv.setBrush(brushPrefs.currentJson())
            if (bgArgb == -1) {
                // Engine default base is already opaque white: loading a
                // white paper would be a no-op that races user strokes, so
                // skip the ORA round trip entirely.
                paperDone[id] = true
            } else {
                paperDone[id] = false
                val dst = java.io.File(ctx.cacheDir, "paper-$id.ora")
                Thread {
                    var ok = false
                    var paperApplied = false
                    try {
                        for (k in 0 until 100) {
                            if (cv.isReady()) break
                            Thread.sleep(100)
                        }
                        // Never clobber user ink: if a stroke landed (or is
                        // still open) before the paper arrived, keep it.
                        val entry = docRegistry.all().firstOrNull { it.docId == id }
                        val userInk = (entry?.dirty == true) || cv.isDrawingNow()
                        if (!userInk) {
                            ok = DocOra.writeSolidOra(dst, w, h, bgArgb)
                        }
                        if (ok && !userInk) {
                            val lock = Object()
                            var done = false
                            var res = false
                            cv.loadFrom(dst.absolutePath) { r ->
                                synchronized(lock) {
                                    done = true
                                    res = r
                                    lock.notifyAll()
                                }
                            }
                            synchronized(lock) {
                                var waits = 0
                                while (!done && waits < 200) {
                                    lock.wait(100)
                                    waits++
                                }
                            }
                            ok = res
                            paperApplied = res
                        }
                    } catch (_: Exception) {
                        ok = false
                    } finally {
                        try {
                            dst.delete()
                        } catch (_: Exception) {
                        }
                    }
                    val wasApplied = paperApplied
                    mainHandler.post {
                        if (wasApplied) docRegistry.markDirty(id, false)
                        paperDone[id] = true
                        refreshKey.value++
                    }
                }.start()
            }
            doActivate(id)
            return id
        }

        fun defaultDocName(): String {
            untitledSeq.value++
            return ctx.getString(R.string.doc_untitled) + " " + untitledSeq.value
        }

        fun nextUntitled(): String {
            val base = ctx.getString(R.string.doc_untitled)
            var n = untitledSeq.value + 1
            val taken = docs.map { it.name }.toSet()
            while (taken.contains("$base $n")) n++
            untitledSeq.value = n
            return "$base $n"
        }

        fun confirmSpec(spec: NewCanvasSpec): Boolean {
            if (spec.w > NEWCANVAS_MAX_DIM || spec.h > NEWCANVAS_MAX_DIM) {
                status.value = ctx.getString(R.string.newcanvas_too_big)
                return false
            }
            showNewCanvas.value = false
            openDocTab(spec.name, spec.w, spec.h, spec.dpi, bgArgbFor(spec.bg))
            return true
        }

        fun saveDocAt(
            id: String,
            uri: android.net.Uri,
            onDone: ((Boolean) -> Unit)? = null
        ) {
            val surf = surfaces[id] ?: run { onDone?.invoke(false); return }
            val dst = java.io.File(
                ctx.cacheDir, "save-${id}-${System.currentTimeMillis()}.ora"
            )
            surf.saveTo(dst.absolutePath, "ora") { ok ->
                if (ok) {
                    var wrote = false
                    try {
                        ctx.contentResolver.openOutputStream(uri)?.use { o ->
                            dst.inputStream().use { i -> i.copyTo(o) }
                            wrote = true
                        }
                    } catch (_: Exception) {
                        wrote = false
                    } finally {
                        try {
                            dst.delete()
                        } catch (_: Exception) {
                        }
                    }
                    if (wrote) {
                        docRegistry.markDirty(id, false)
                        status.value = if (savePersistFailed.value &&
                            !savePersistHintShown.value
                        ) {
                            // Slice 7: announced once instead of save_ok.
                            savePersistHintShown.value = true
                            ctx.getString(R.string.save_no_persist_hint)
                        } else {
                            ctx.getString(R.string.save_ok)
                        }
                    } else {
                        status.value = ctx.getString(R.string.save_failed)
                    }
                    onDone?.invoke(wrote)
                } else {
                    try {
                        dst.delete()
                    } catch (_: Exception) {
                    }
                    status.value = ctx.getString(R.string.save_failed)
                    onDone?.invoke(false)
                }
                refreshKey.value++
            }
        }

        fun saveDoc(id: String, onDone: ((Boolean) -> Unit)? = null) {
            val surf = surfaces[id] ?: run { onDone?.invoke(false); return }
            testSavePaths[id]?.let { path ->
                surf.saveTo(path, "ora") { ok ->
                    if (ok) docRegistry.markDirty(id, false)
                    status.value = if (ok) ctx.getString(R.string.save_ok)
                    else ctx.getString(R.string.save_failed)
                    refreshKey.value++
                    onDone?.invoke(ok)
                }
                return
            }
            val known = saveUris[id]
            if (known != null) {
                try {
                    saveDocAt(id, android.net.Uri.parse(known), onDone)
                } catch (_: Exception) {
                    status.value = ctx.getString(R.string.save_failed)
                    onDone?.invoke(false)
                }
                return
            }
            val tab = docs.firstOrNull { it.docId == id }
                ?: run { onDone?.invoke(false); return }
            pendingSaveDoc.value = id
            pendingCloseAfterSave.value = onDone
            savePickRef.value?.invoke(tab.name + ".ora")
        }

        fun closeNow(id: String) {
            if (pendingTabId.value == id) pendingTabId.value = null
            if (preselectId.value == id) preselectId.value = null
            try {
                surfaces[id]?.pause()
            } catch (_: Exception) {
            }
            try {
                surfaces[id]?.dispose()
            } catch (_: Exception) {
            }
            surfaces.remove(id)
            docs.removeAll { it.docId == id }
            docRegistry.close(id)
            saveUris.remove(id)
            testSavePaths.remove(id)
            paperDone.remove(id)
            if (activeDocId.value == id) {
                val next = docRegistry.active()?.docId
                if (next != null && surfaces.containsKey(next)) {
                    doActivate(next)
                } else {
                    activeDocId.value = null
                    refreshKey.value++
                }
            } else {
                refreshKey.value++
            }
        }

        fun requestClose(id: String) {
            if (docRegistry.all().firstOrNull { it.docId == id }?.dirty == true) {
                closeConfirmId.value = id
            } else {
                closeNow(id)
            }
        }

        // Slice 6 1-tap save target picker: remembers the doc, persists
        // the SAF permission, then overwrites silently on later taps.
        // Declared after the doc funs (saveDocAt lives above); saveDoc
        // reaches it through savePickRef.
        val createSaveLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri: Uri? ->
            val id = pendingSaveDoc.value
            pendingSaveDoc.value = null
            if (uri != null && id != null && surfaces.containsKey(id)) {
                try {
                    ctx.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // Slice 7: SAF without persistable permission falls
                    // back to asking at every save — announced once.
                    savePersistFailed.value = true
                }
                saveUris[id] = uri.toString()
                val after = pendingCloseAfterSave.value
                pendingCloseAfterSave.value = null
                saveDocAt(id, uri) { ok -> after?.invoke(ok) }
            } else if (id != null) {
                status.value = ctx.getString(R.string.save_failed)
            }
        }
        if (savePickRef.value == null) {
            savePickRef.value = { name -> createSaveLauncher.launch(name) }
        }

        // The deferred switch is driven by deferCheck (Handler, see
        // requestActivate) so it never depends on recomposition timing.
        // This effect stays as a belt-and-suspenders resync: if a pending
        // tab is present when composition runs, kick the checker.
        LaunchedEffect(pendingTabId.value) {
            if (pendingTabId.value == null) return@LaunchedEffect
            deferCheck?.let {
                mainHandler.removeCallbacks(it)
                mainHandler.postDelayed(it, 150)
            }
        }

        // Slice 6 first doc: opened synchronously on first composition so
        // the shell (and the instrumented gate) always has a live surface,
        // like the old factory-created canvas. Later docs go through +.
        val booted = remember { mutableStateOf(false) }
        if (!booted.value) {
            booted.value = true
            if (docs.isEmpty()) {
                openDocTab(defaultDocName(), 2048, 1536, dpi, -1)
            }
        }

        LaunchedEffect(Unit) {
            val drawName = ctx.getString(R.string.workspace_draw)
            val animName = ctx.getString(R.string.workspace_anim)
            workspaceStore.load(drawName, animName)
            // Slice 7: corrupt prefs JSON fell back to presets — say so
            // once (status only, never a dialog).
            if (!prefsHintShown.value && (commandStore.loadFallback ||
                brushPrefs.readFallback || workspaceStore.loadFallback)
            ) {
                prefsHintShown.value = true
                status.value = ctx.getString(R.string.prefs_reset_hint)
            }
            val last = workspaceStore.lastWorkspace.value
            if (last != null) {
                val pose = workspaceStore.spaces[last]
                if (pose != null) applyPose(last, pose)
            }
            if (docs.isEmpty()) {
                openDocTab(defaultDocName(), 2048, 1536, dpi, -1)
            }
            activityTestHooks.openDoc =
                { name: String, w: Int, h: Int, d: Float, bg: Int? ->
                    val nid = openDocTab(name, w, h, d, bg)
                    poke()
                    nid
                }
            activityTestHooks.closeDoc = { id: String ->
                closeNow(id)
                poke()
            }
            activityTestHooks.activateDoc = { id: String ->
                doActivate(id)
                poke()
            }
            activityTestHooks.docIds = { docs.map { it.docId } }
            activityTestHooks.activeDocId = { activeDocId.value }
            activityTestHooks.surfaceFor = { id: String -> surfaces[id] }
            activityTestHooks.dirtyOf = { id: String ->
                docRegistry.all().firstOrNull { it.docId == id }?.dirty == true
            }
            activityTestHooks.setSavePath = { id: String, path: String ->
                testSavePaths[id] = path
            }
            activityTestHooks.saveDoc = { id: String -> saveDoc(id) }
            activityTestHooks.poke = { poke() }
            activityTestHooks.pendingTab = { pendingTabId.value }
            activityTestHooks.requestActivate = { id: String -> requestActivate(id) }
            activityTestHooks.paperReady = { id: String -> paperDone[id] == true }
            activityTestHooks.clearLastWorkspace = {
                workspaceStore.lastWorkspace.value = null
                workspaceStore.flush()
            }
            activityTestHooks.resetDocs = {
                for (id in docs.map { it.docId }) {
                    try {
                        surfaces[id]?.dispose()
                    } catch (_: Exception) {
                    }
                }
                surfaces.clear()
                docs.clear()
                docRegistry.dispose()
                saveUris.clear()
                testSavePaths.clear()
                paperDone.clear()
                activeDocId.value = null
                pendingTabId.value = null
                preselectId.value = null
                val nid = openDocTab(defaultDocName(), 2048, 1536, dpi, -1)!!
                poke()
                nid
            }
            activityTestHooks.applyWorkspace = { wsName: String ->
                val pose = workspaceStore.spaces[wsName]
                if (pose != null) {
                    applyPose(wsName, pose)
                    true
                } else false
            }
            activityTestHooks.currentPose = { currentPose() }
            activityTestHooks.reloadWorkspaces = {
                workspaceStore.load(
                    ctx.getString(R.string.workspace_draw),
                    ctx.getString(R.string.workspace_anim)
                )
            }
            activityTestHooks.commandSetVisible = { a: String, v: Boolean ->
                commandStore.setVisible(a, v)
            }
            activityTestHooks.commandOrder = { commandStore.order.toList() }
        }

        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF141418))
                .systemBarsPadding()
        ) {
            // 1. Top Studio Menu Bar + Toolbar (slice 0 shell: every
            // entry reuses an existing SAF/export/engine action).
            // Slice 6: chrome hides in fullscreen (canvas + exit only).
            // Window + Help posture needs no surface: shared by the live
            // menu bar and the 0-doc fallback bar (never a dead shell).
            val windowMenu = StudioMenu(
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
                    ),
                    StudioMenuItem(
                        label = stringResource(R.string.workspace_draw),
                        onClick = {
                            val pose = workspaceStore.spaces[
                                ctx.getString(R.string.workspace_draw)]
                            if (pose != null) applyPose(
                                ctx.getString(R.string.workspace_draw), pose)
                        }
                    ),
                    StudioMenuItem(
                        label = stringResource(R.string.workspace_anim),
                        onClick = {
                            val pose = workspaceStore.spaces[
                                ctx.getString(R.string.workspace_anim)]
                            if (pose != null) applyPose(
                                ctx.getString(R.string.workspace_anim), pose)
                        }
                    ),
                    StudioMenuItem(
                        label = stringResource(R.string.workspace_save_title),
                        onClick = { showWorkspaces.value = true }
                    )
                )
            )
            val helpMenu = StudioMenu(
                title = stringResource(R.string.menu_help),
                items = listOf(
                    StudioMenuItem(
                        label = stringResource(R.string.gestures_help),
                        onClick = { showHelp.value = true }
                    ),
                    StudioMenuItem(
                        label = stringResource(R.string.multidoc_help),
                        onClick = { showMultidocHelp.value = true }
                    )
                )
            )
            if (!fullscreen.value) {
            val menuSurface = activeSurface()
            if (menuSurface == null) {
                ChromeMenuBar(
                    menus = listOf(windowMenu, helpMenu),
                    compact = compact,
                    docTitle = null,
                    docDirty = false,
                    onSave = null
                )
                HorizontalDivider(color = Color(0xFF2E2E38), thickness = 1.dp)
            }
            menuSurface?.let { surface ->
                val activeId = activeDocId.value
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
                                    val aid = activeDocId.value
                                    if (aid != null) docRegistry.markDirty(aid, true)
                                    refreshKey.value++
                                }
                            }
                        }
                    )
                }
                ChromeMenuBar(
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
                                            val aid = activeDocId.value
                                            if (aid != null) docRegistry.markDirty(aid, true)
                                            refreshKey.value++
                                        }
                                    }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.redo),
                                    onClick = {
                                        if (surface.redo()) {
                                            val aid = activeDocId.value
                                            if (aid != null) docRegistry.markDirty(aid, true)
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
                                        val aid = activeDocId.value
                                        if (aid != null) docRegistry.markDirty(aid, true)
                                        refreshKey.value++
                                    }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.layer_add_group),
                                    onClick = {
                                        surface.addGroup(
                                            ctx.getString(R.string.layer_group_default)
                                        )
                                        val aid = activeDocId.value
                                        if (aid != null) docRegistry.markDirty(aid, true)
                                        refreshKey.value++
                                    }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.layer_add_vector),
                                    onClick = {
                                        surface.addVectorLayer(
                                            ctx.getString(R.string.vector_layer_default)
                                        )
                                        val aid = activeDocId.value
                                        if (aid != null) docRegistry.markDirty(aid, true)
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
                        windowMenu,
                        helpMenu
                    ),
                    compact = compact,
                    docTitle = docs.firstOrNull { it.docId == activeId }?.name,
                    docDirty = docRegistry.all().firstOrNull {
                        it.docId == activeId
                    }?.dirty == true,
                    onSave = {
                        val aid = activeDocId.value
                        if (aid != null) saveDoc(aid)
                    }
                )
                HorizontalDivider(color = Color(0xFF2E2E38), thickness = 1.dp)
                androidx.compose.runtime.key(activeDocId.value) {
                    ToolPalette(
                        surface = surface,
                        prefs = brushPrefs,
                        initialColor = inkHex,
                        navigateMode = navMode.value,
                        onNavigateToggle = { toggleNav() },
                        onFileClicked = { showExport.value = true },
                        onImportClicked = { openDoc.launch(arrayOf("*/*")) }
                    )
                }
                HorizontalDivider(color = Color(0xFF2E2E38), thickness = 1.dp)
            }
            // Slice 6 tabs + title + command bar (overlay chrome, never
            // engine pixels). Tabs stay visible with 0 docs so the "+" can
            // recreate a canvas.
            val tabEntries = docRegistry.all()
            DocTabs(
                docs = tabEntries,
                activeDocId = activeDocId.value,
                preselectedDocId = preselectId.value,
                compact = compact,
                onActivate = { requestActivate(it) },
                onClose = { requestClose(it) },
                onAdd = {
                    if (!docRegistry.canOpen()) {
                        docsFullShow.value = true
                        status.value = ctx.getString(R.string.docs_full_hint)
                    } else {
                        newCanvasDefault.value = nextUntitled()
                        showNewCanvas.value = true
                    }
                }
            )
            HorizontalDivider(color = Color(0xFF2E2E38), thickness = 1.dp)
            activeSurface()?.let { asurface ->
                val atab = docs.firstOrNull { it.docId == activeDocId.value }
                if (atab != null) {
                    viewZoomTick.value
                    DocTitleBar(
                        name = atab.name,
                        dirty = tabEntries.firstOrNull {
                            it.docId == activeDocId.value
                        }?.dirty == true,
                        w = atab.w,
                        h = atab.h,
                        zoomPct = asurface.viewZoomPercent(),
                        compact = compact,
                        showSave = !compact,
                        onSave = {
                            val aid = activeDocId.value
                            if (aid != null) saveDoc(aid)
                        }
                    )
                    HorizontalDivider(color = Color(0xFF2E2E38), thickness = 1.dp)
                }
            }
            val cmdLabels = mapOf(
                "save" to stringResource(R.string.doc_save),
                "undo" to stringResource(R.string.undo),
                "redo" to stringResource(R.string.redo),
                "export" to stringResource(R.string.export_title),
                "fullscreen" to stringResource(R.string.cmdbar_fullscreen)
            )
            if (cmdbarVisible.value) {
                activeSurface()?.let { asurface ->
                    if (cmdbarCollapsed.value) {
                        CommandBarCollapsed(
                            onExpand = { cmdbarCollapsed.value = false }
                        )
                    } else {
                        CommandBar(
                            store = commandStore,
                            labels = cmdLabels,
                            onAction = { action ->
                                val aid = activeDocId.value
                                when (action) {
                                    "save" -> if (aid != null) saveDoc(aid)
                                    "undo" -> if (asurface.undo()) {
                                        if (aid != null) docRegistry.markDirty(aid, true)
                                        refreshKey.value++
                                    }
                                    "redo" -> if (asurface.redo()) {
                                        if (aid != null) docRegistry.markDirty(aid, true)
                                        refreshKey.value++
                                    }
                                    "export" -> showExport.value = true
                                    "fullscreen" -> {
                                        fullscreen.value = true
                                        status.value = ctx.getString(
                                            R.string.cmdbar_fullscreen)
                                    }
                                }
                            },
                            onEditHint = {
                                status.value = ctx.getString(R.string.cmdbar_edit)
                            },
                            onCollapse = { cmdbarCollapsed.value = true }
                        )
                    }
                    HorizontalDivider(color = Color(0xFF2E2E38), thickness = 1.dp)
                }
            }
            } // fullscreen chrome gate

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
                    // Slice 6 N surfaces, 1 visible: the active tab composites
                    // on top; inactives keep full size (surface stays valid)
                    // but render parked (alpha 0, no touch — active is last).
                    val orderedDocs = docs.sortedBy {
                        if (it.docId == activeDocId.value) 1 else 0
                    }
                    for (doc in orderedDocs) {
                        androidx.compose.runtime.key(doc.docId) {
                            val surf = surfaces[doc.docId]
                            if (surf != null) {
                                val isActive = doc.docId == activeDocId.value
                                DisposableEffect(doc.docId) {
                                    onDispose {
                                        try {
                                            surf.dispose()
                                        } catch (_: Exception) {
                                        }
                                    }
                                }
                                AndroidView(
                                    factory = { surf },
                                    update = { cv ->
                                        cv.navigateMode = navMode.value
                                        cv.edgeBottomEnabled = compact
                                        cv.pipetteArmed = store.pipetteArmed
                                        cv.pipetteLoupe = store.pipetteLoupe
                                    },
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .alpha(if (isActive) 1f else 0f)
                                )
                            }
                        }
                    }
                    if (docs.isEmpty()) {
                        androidx.compose.foundation.layout.Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Slice 7 monumental empty CTA: home glyph +
                            // title + hint + 48dp action (no dead screen).
                            Text(
                                "＋",
                                style = MaterialTheme.typography.displayMedium,
                                color = Color(0xFF787888),
                                fontSize = 64.sp,
                                maxLines = 1
                            )
                            Text(
                                stringResource(R.string.newcanvas_empty_cta),
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFFECECF1),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                stringResource(R.string.newcanvas_empty_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF787888),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            TextButton(
                                onClick = {
                                    newCanvasDefault.value = nextUntitled()
                                    showNewCanvas.value = true
                                },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("newcanvas-empty-cta")
                            ) {
                                Text(
                                    stringResource(R.string.newcanvas_empty_cta),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    // Slice 1 loupe chip: zoom % + angle + 1-tap view reset,
                    // visible while the canvas reports navigation.
                    val surf = activeSurface()
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
                    // Slice 5 HUD pastille: live dab size + pressure while
                    // stroking (and 800 ms after UP). Composite-only
                    // overlay, never engine pixels. The surface posts a
                    // Compose state (10 Hz throttle), so this recomposes
                    // on its own.
                    val hud = surf?.hudUi
                    if (hud != null) {
                        val hudLabel = if (compact) stringResource(
                            R.string.cursor_brush, hud.diamPx
                        ) else stringResource(
                            R.string.brush_hud, hud.diamPx, hud.pct
                        )
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .background(Color(0xCC101014))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                .testTag("brush-hud"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                hudLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFECECF1),
                                maxLines = 1,
                                modifier = Modifier.testTag("brush-hud-text")
                            )
                        }
                    }
                    // Slice 2 pipette loupe (composite-only Compose overlay,
                    // never engine pixels): enlarged witness + live hex while
                    // armed. Anchored top-center (never under the finger);
                    // the bottom-bar pastille follows the same live hex.
                    if (store.pipetteArmed) {
                        val liveHex = store.pipetteLive ?: inkHex
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(8.dp)
                                .background(Color(0xCC101014))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .testTag("pipette-loupe"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(parseHexColor(liveHex))
                            )
                            Column {
                                Text(
                                    liveHex,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color(0xFFECECF1),
                                    maxLines = 1
                                )
                                Text(
                                    stringResource(
                                        if (store.pipetteLoupe) R.string.pipette_hold
                                        else R.string.pipette_armed
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF90B8FF),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    // Slice 6 fullscreen: overlay exit only (posture untouched,
                    // the workspace JSON is never modified by this toggle).
                    if (fullscreen.value) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .background(Color(0xCC101014))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (status.value.isNotEmpty()) {
                                Text(
                                    status.value,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF80E27E),
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                            }
                            TextButton(
                                onClick = { fullscreen.value = false },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("fullscreen-exit")
                            ) {
                                Text(
                                    "✕ " + stringResource(
                                        R.string.cmdbar_exit_fullscreen),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color(0xFFECECF1),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    // Slice 5 honest-eraser banner: the raster eraser paints
                    // light (engine limitation, no alpha-erase yet) — never
                    // silent white. Shown when the eraser is armed on a
                    // multi-layer stack; a single opaque layer hides it.
                    activeSurface()?.let { surface ->
                        if (brushPrefs.isEraser && surface.layerCount() > 1) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(
                                        start = 8.dp, end = 8.dp, bottom = 64.dp
                                    )
                                    .background(Color(0xEB3A2A18))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                                    .testTag("eraser-banner"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    stringResource(R.string.eraser_covers_hint),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFE0A050),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = { showBrushHelp.value = true },
                                    contentPadding = PaddingValues(
                                        horizontal = 8.dp, vertical = 0.dp
                                    ),
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("eraser-learn-more")
                                ) {
                                    Text(
                                        stringResource(R.string.eraser_learn_more),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF90B8FF),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        PinnedBrushBar(
                            surface = surface,
                            prefs = brushPrefs,
                            hidden = showTimeline.value,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(
                                    start = 8.dp, end = 8.dp, bottom = 8.dp
                                )
                        )
                    }
                }

                if (!fullscreen.value) {
                activeSurface()?.let { surface ->
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
                        toggleTag = "dock-toggle",
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
                                .testTag("dock-scroll")
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (dockTab.value == 0) {
                                LayerPanel(
                                    surface = surface,
                                    refreshKey = refreshKey.value,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                // Slice 2: controlled color panel (single
                                // inkHex source of truth, pipette included).
                                ColorPanel(
                                    store = store,
                                    onStatus = { status.value = it },
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
            } // central Row (canvas + dock)

            // 3. Collapsible Bottom Timeline Drawer + Slim Status Footer
            // (hidden in fullscreen — canvas + exit only).
            if (!fullscreen.value) {
            activeSurface()?.let { surface ->
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
                            .testTag("timeline-drawer")
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

                // Slim Studio Status Bar (slice 7: single source — WebDAV
                // button + the one status message, never dims/name).
                // Tap the message to clear it (content-description
                // status_dismiss); errors show in err red, all else ok.
                val dismissLabel = stringResource(R.string.status_dismiss)
                val errMsgs = setOf(
                    stringResource(R.string.save_failed),
                    stringResource(R.string.export_failed),
                    stringResource(R.string.import_failed),
                    stringResource(R.string.filter_failed),
                    stringResource(R.string.webdav_failed),
                    stringResource(R.string.ai_failed),
                    stringResource(R.string.ref_failed),
                    stringResource(R.string.audio_failed)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .background(Color(0xFF1E1E24))
                        .padding(horizontal = 8.dp)
                        .testTag("status-bar"),
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
                            color = Color(0xFFC4C4D0),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        status.value,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (status.value in errMsgs) Color(0xFFE08080)
                        else Color(0xFF80E27E),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("status-line")
                            .clickable(
                                onClickLabel = dismissLabel,
                                role = Role.Button,
                                onClick = { status.value = "" }
                            )
                    )
                }
            }

                if (showWebdav.value) {
                    activeSurface()?.let { wsurf ->
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
                                    wsurf.saveTo(doc.absolutePath, "ora") { s ->
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
                                        wsurf.loadFrom(dst) { l ->
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
                }
                if (showHelp.value) {
                    GesturesHelpDialog(onDismiss = { showHelp.value = false })
                }
                if (showBrushHelp.value) {
                    BrushHelpDialog(onDismiss = { showBrushHelp.value = false })
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

            // ---- Slice 6 dialogs (outside the surface lets so the "+"
            // and the empty-canvas CTA work with 0 docs) ----
            if (showNewCanvas.value) {
                NewCanvasDialog(
                    defaultName = newCanvasDefault.value.ifBlank {
                        ctx.getString(R.string.doc_untitled)
                    },
                    onConfirm = { confirmSpec(it) },
                    onDismiss = { showNewCanvas.value = false }
                )
            }
            if (docsFullShow.value) {
                AlertDialog(
                    onDismissRequest = { docsFullShow.value = false },
                    title = {
                        Text(
                            stringResource(R.string.docs_full_hint),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2
                        )
                    },
                    text = {
                        Text(
                            stringResource(R.string.docs_full_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("docs-full-hint")
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = { docsFullShow.value = false },
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("docs-full-ok")
                        ) {
                            Text(stringResource(R.string.ok))
                        }
                    }
                )
            }
            val confirmId = closeConfirmId.value
            if (confirmId != null) {
                val confirmEntry = docRegistry.all()
                    .firstOrNull { it.docId == confirmId }
                AlertDialog(
                    onDismissRequest = { closeConfirmId.value = null },
                    title = {
                        Text(
                            ctx.getString(
                                R.string.doc_close_dirty,
                                confirmEntry?.name ?: confirmId
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            modifier = Modifier.testTag("doc-close-dialog")
                        )
                    },
                    text = {
                        Text(
                            ctx.getString(
                                R.string.doc_close_dirty,
                                confirmEntry?.name ?: confirmId
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                closeConfirmId.value = null
                                saveDoc(confirmId) { ok ->
                                    if (ok) closeNow(confirmId)
                                }
                            },
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("doc-close-save")
                        ) {
                            Text(stringResource(R.string.doc_close_save))
                        }
                    },
                    dismissButton = {
                        Row {
                            TextButton(
                                onClick = {
                                    closeConfirmId.value = null
                                    closeNow(confirmId)
                                },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("doc-close-discard")
                            ) {
                                Text(stringResource(R.string.doc_close_discard))
                            }
                            TextButton(
                                onClick = { closeConfirmId.value = null },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("doc-close-cancel")
                            ) {
                                Text(stringResource(R.string.doc_close_cancel))
                            }
                        }
                    }
                )
            }
            if (showWorkspaces.value) {
                WorkspaceDialog(
                    store = workspaceStore,
                    currentPose = currentPose(),
                    drawFallback = ctx.getString(R.string.workspace_draw),
                    onApply = { name, pose -> applyPose(name, pose) },
                    onDismiss = { showWorkspaces.value = false }
                )
            }
            if (showMultidocHelp.value) {
                AlertDialog(
                    onDismissRequest = { showMultidocHelp.value = false },
                    title = {
                        Text(
                            stringResource(R.string.multidoc_help),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                    },
                    text = {
                        Text(
                            stringResource(R.string.multidoc_help_body),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("multidoc-help-dialog")
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = { showMultidocHelp.value = false },
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text(stringResource(R.string.ok))
                        }
                    }
                )
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
