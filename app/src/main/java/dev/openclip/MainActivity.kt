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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.openclip.media.AiUpscaler
import dev.openclip.media.ClipAudio
import dev.openclip.media.ExportWebm
import dev.openclip.media.Timelapse
import dev.openclip.media.WebdavClient
import dev.openclip.doc.CrashRecovery
import dev.openclip.doc.DocClip
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
import dev.openclip.ui.FillPanel
import dev.openclip.ui.TexturePanel
import dev.openclip.ui.ImportPanel
import dev.openclip.ui.ColorMatchDialog
import dev.openclip.ui.T5PagesPanel
import dev.openclip.ui.T5ExportDialog
import dev.openclip.ui.MacroPanel
import dev.openclip.ui.AlignPanel
import dev.openclip.ui.HistoryPanel
import dev.openclip.ui.InfoPanel
import dev.openclip.ui.BrushHelpDialog
import dev.openclip.ui.FeatherDialog
import dev.openclip.ui.GesturesHelpDialog
import dev.openclip.ui.LayerPanel
import dev.openclip.ui.AutosavePanel
import dev.openclip.ui.BdToolsPanel
import dev.openclip.ui.BezierPanel
import dev.openclip.ui.CopyPastePanel
import dev.openclip.ui.CurvesPanel
import dev.openclip.ui.GifPanel
import dev.openclip.ui.AnimSidecar
import dev.openclip.ui.T3ExportPanel
import dev.openclip.ui.applyT3Doc
import dev.openclip.ui.collectT3Doc
import dev.openclip.ui.GuidesPanel
import dev.openclip.ui.NavigatorPanel
import dev.openclip.ui.PagesPanel
import dev.openclip.ui.SvgPanel
import dev.openclip.ui.TransformPanel
import dev.openclip.ui.WarpPanel
import dev.openclip.ui.CanvasSizeDialog
import dev.openclip.ui.RefPhoto
import dev.openclip.ui.RefPhotoPanel
import dev.openclip.ui.SelectionRadiusDialog
import dev.openclip.ui.SelectionTool
import dev.openclip.ui.SmartShapePanel
import dev.openclip.ui.WebtoonPanel
import dev.openclip.ui.MaterialsRow
import dev.openclip.ui.PinnedBrushBar
import dev.openclip.ui.ChromeMenuBar
import dev.openclip.ui.MenuBar
import dev.openclip.ui.PresetGallery
import dev.openclip.ui.ReferencePanel
import dev.openclip.ui.StudioMenu
import dev.openclip.ui.StudioMenuItem
import dev.openclip.ui.BubblePanel
import dev.openclip.ui.FontRegistry
import dev.openclip.ui.InlineTextBox
import dev.openclip.ui.clearTextFiches
import dev.openclip.ui.restoreTextFiches
import dev.openclip.ui.stashTextFiches
import dev.openclip.ui.TextPanel
import dev.openclip.ui.TextSpec
import dev.openclip.ui.TextTransformOverlay
import dev.openclip.ui.VectorOpsPanel
import dev.openclip.ui.VectorStylePanel
import dev.openclip.ui.RecomposeTestHooks
import dev.openclip.ui.UndoAvailability
import dev.openclip.ui.scrollFade
import dev.openclip.ui.TimelineStrip
import dev.openclip.ui.ToolPalette
import dev.openclip.ui.WebdavDialog
// V+3 confort pro (§1.2, §1.3, §3, §4, §5, §6, §7): panels + pure units.
import dev.openclip.ui.ChannelsPanel
import dev.openclip.ui.HarmonyPanel
import dev.openclip.ui.ImportPanel
import dev.openclip.ui.PdfImport
import dev.openclip.ui.PosePanel
import dev.openclip.ui.ProofPanel
import dev.openclip.ui.ShortcutAction
import dev.openclip.ui.ShortcutHelpDialog
import dev.openclip.ui.ShortcutMap
import dev.openclip.ui.ShortcutToolState
import dev.openclip.ui.TextOnPath
import dev.openclip.ui.TextPathPanel
import dev.openclip.ui.v2ActiveLayer
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
        // B2 §1: open the transform handles for a text layer (test hook).
        var openTextTransform: ((Int, TextSpec) -> Unit)? = null
        // Issue #32 §2: crash-recovery probes (snapshot write/restore).
        var markDirty: ((String, Boolean) -> Unit)? = null
        var snapshotDoc: ((String) -> Boolean)? = null
        var pendingSnapshots: (() -> Int)? = null
        var recoveryPromptVisible: (() -> Boolean)? = null
        var discardSnapshots: (() -> Unit)? = null
        var docNameOf: ((String) -> String?)? = null
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
            // Rebrand : fonte display maison (Space Grotesk OFL, assets/fonts)
            // pour titleSmall/titleMedium (chrome + nom du doc) ; corps =
            // système. Titres en casse phrase, jamais de capitales forcées.
            val appCtx = LocalContext.current.applicationContext
            val displayTypography = remember {
                val medium = FontFamily(
                    android.graphics.Typeface.createFromAsset(
                        appCtx.assets, "fonts/space_grotesk_medium.ttf"
                    )
                )
                val bold = FontFamily(
                    android.graphics.Typeface.createFromAsset(
                        appCtx.assets, "fonts/space_grotesk_bold.ttf"
                    )
                )
                val base = Typography()
                base.copy(
                    titleSmall = base.titleSmall.copy(fontFamily = medium),
                    titleMedium = base.titleMedium.copy(fontFamily = bold)
                )
            }
            MaterialTheme(colorScheme = StudioColorScheme, typography = displayTypography) {
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
        // V15: isolated poke tick — read by nothing structural, so the
        // poke hook never recomposes chrome or dock.
        val pokeTick = remember { mutableStateOf(0) }
        // V10: hoisted undo/redo availability (ToolPalette writes,
        // command bar reads).
        val undoAvail = remember { UndoAvailability() }
        val store = colorStore
        val inkHex = store.inkHex
        val status = remember { mutableStateOf("") }
        val showExport = remember { mutableStateOf(false) }
        val exportFormat = remember { mutableStateOf("ora") }
        // T5 échanges + pages + productivité (spec docs/t5-exchange.md).
        val showT5Fill = remember { mutableStateOf(false) }
        // B1 (ARCHITECTURE.md "B1 additions" §5): layer texture dialog.
        val showB1Texture = remember { mutableStateOf(false) }
        val showT5Import = remember { mutableStateOf(false) }
        val showT5Match = remember { mutableStateOf(false) }
        val showT5Pages = remember { mutableStateOf(false) }
        val showT5Export = remember { mutableStateOf(false) }
        val showT5Macro = remember { mutableStateOf(false) }
        val showT5Align = remember { mutableStateOf(false) }
        val showT5History = remember { mutableStateOf(false) }
        val showT5Info = remember { mutableStateOf(false) }
        val exchangePick = remember { mutableStateOf<((java.io.File?) -> Unit)?>(null) }
        val pendingShare = remember { mutableStateOf<java.io.File?>(null) }
        val ctx = LocalContext.current
        // T2 §1: user font library lives in filesDir/fonts (session
        // setup, once per composition root — rescan on import).
        remember(FontRegistry) {
            FontRegistry.dir = java.io.File(ctx.filesDir, "fonts")
            FontRegistry.rescan()
        }
        val clipAudio = remember { ClipAudio() }
        val audioName = remember { mutableStateOf<String?>(null) }
        // B2 §5: session timelapse recorder (every N committed strokes).
        val timelapse = remember { Timelapse.Recorder() }
        val timelapseOn = remember { mutableStateOf(false) }
        val timelapseFrames = remember { mutableStateOf(0) }
        val timelapseEvery = remember {
            mutableStateOf(Timelapse.DEFAULT_EVERY)
        }
        // T3 §4 : chemin du cache audio (export audio seul = copie
        // byte-identique, 0 JNI).
        val audioPath = remember { mutableStateOf<String?>(null) }

        // T3 §3.5 : sidecar anim.json v1 par document (filesDir,
        // 64 Ko cap ; absent = 1 piste + pas d'etiquettes).
        fun sidecarFile(id: String): java.io.File =
            java.io.File(ctx.filesDir, "anim-${id}.json")

        fun pushSidecar(surf: EngineSurface, id: String) {
            try {
                val doc = AnimSidecar.load(sidecarFile(id))
                applyT3Doc(surf, doc)
            } catch (_: Exception) {
            }
        }

        fun pullSidecar(surf: EngineSurface, id: String) {
            try {
                val doc = collectT3Doc(surf, surf.layerCount())
                AnimSidecar.save(sidecarFile(id), doc)
            } catch (_: Exception) {
            }
        }
        val exporting = remember { mutableStateOf(false) }
        val clipFps = remember { mutableStateOf(12f) }
        val mainHandler = remember { Handler(Looper.getMainLooper()) }
        val refModelName = remember { mutableStateOf<String?>(null) }
        val refGlbPath = remember { mutableStateOf<String?>(null) }
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
        // V+3 §7: physical-keyboard shortcuts (fixed table, help dialog)
        // + external play-toggle requests for the timeline.
        val showShortcuts = remember { mutableStateOf(false) }
        // T1 §5 : dialogue contour progressif (remplace le +1px fixe).
        val showFeather = remember { mutableStateOf(false) }
        // W1 (issue #28 slice A): canvas size, selection radius op,
        // reference photo panel.
        val showW1Canvas = remember { mutableStateOf(false) }
        val w1SelOp = remember { mutableStateOf("") }
        val showW1Ref = remember { mutableStateOf(false) }
        val playTickExt = remember { mutableStateOf(0) }
        val toolState = remember { ShortcutToolState() }
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
        // V+1: selection menu liveness + text panel state (per anchor
        // tap or double-tap re-edit; layer >= 0 = re-edit).
        val selectionTick = remember { mutableStateOf(0) }
        val showTextPanel = remember { mutableStateOf(false) }
        val textAnchor = remember { mutableStateOf<Pair<Float, Float>?>(null) }
        val textEditLayer = remember { mutableStateOf(-1) }
        val textInitial = remember { mutableStateOf(TextSpec(text = "")) }
        // B2 §1: transform handles over the committed text frame.
        val showTextXform = remember { mutableStateOf(false) }
        val textXformLayer = remember { mutableStateOf(-1) }
        val textXformSpec = remember { mutableStateOf(TextSpec(text = "")) }
        // T2: inline canvas editor (Valider = commit, Réglages = panel),
        // font library liveness, PSD text inclusion.
        val showInline = remember { mutableStateOf(false) }
        val fontsTick = remember { mutableStateOf(0) }
        val includeText = remember { mutableStateOf(true) }
        // V+1: one-shot eraser migration dialog (persisted in prefs).
        val eraserNoteShown = remember { mutableStateOf(false) }
        val showEraserNote = remember { mutableStateOf(false) }

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

        fun markActiveDirty() {
            val aid = activeDocId.value
            if (aid != null) docRegistry.markDirty(aid, true)
            refreshKey.value++
        }

        fun pushBrushPrefs() {
            val s = activeSurface() ?: return
            brushPrefs.bindTool(
                store.inkHex, brushPrefs.toolEraser, brushPrefs.toolCurve
            )
            s.setBrush(brushPrefs.currentJson())
        }

        // V+3 §7: fixed-table keyboard dispatch (B/E/M tool switch,
        // Ctrl+Z/Y undo/redo, +/- size, I pipette, [] layers, PgUp/PgDn
        // cels, Space play, F fullscreen, Shift+? help). Priority over
        // gestures while a physical keyboard is visible (documented).
        fun handleShortcut(action: ShortcutAction): Boolean {
            val s = activeSurface()
            return when (action) {
                ShortcutAction.UNDO -> {
                    if (s?.undo() == true) {
                        markActiveDirty()
                        true
                    } else false
                }
                ShortcutAction.REDO -> {
                    if (s?.redo() == true) {
                        markActiveDirty()
                        true
                    } else false
                }
                ShortcutAction.TOOL_BRUSH -> {
                    toolState.dispatchTool(action)
                    brushPrefs.setTool(false)
                    pushBrushPrefs()
                    true
                }
                ShortcutAction.TOOL_ERASER -> {
                    toolState.dispatchTool(action)
                    brushPrefs.setTool(true)
                    pushBrushPrefs()
                    true
                }
                ShortcutAction.TOOL_KNIFE -> {
                    toolState.dispatchTool(action)
                    brushPrefs.setTool(false)
                    brushPrefs.updateMix(0.5f)
                    pushBrushPrefs()
                    true
                }
                ShortcutAction.SIZE_UP, ShortcutAction.SIZE_DOWN -> {
                    val d = if (action == ShortcutAction.SIZE_UP) 2f else -2f
                    if (brushPrefs.isEraser) {
                        brushPrefs.updateEraserRadius(brushPrefs.eraserRadius + d)
                    } else {
                        brushPrefs.updateRadius(brushPrefs.radius + d)
                    }
                    pushBrushPrefs()
                    true
                }
                ShortcutAction.PICKER -> {
                    store.pipetteArmed = true
                    true
                }
                ShortcutAction.LAYER_UP, ShortcutAction.LAYER_DOWN -> {
                    if (s == null) return false
                    val n = s.layerCount()
                    if (n <= 0) return false
                    val cur = v2ActiveLayer(s).coerceIn(0, n - 1)
                    val next = if (action == ShortcutAction.LAYER_UP) {
                        (cur + 1).coerceAtMost(n - 1)
                    } else {
                        (cur - 1).coerceAtLeast(0)
                    }
                    s.setActiveLayer(next) >= 0
                }
                ShortcutAction.FRAME_NEXT, ShortcutAction.FRAME_PREV -> {
                    if (s == null) return false
                    val n = s.frameCount()
                    if (n <= 1) return false
                    val cur = s.currentFrameIndex().coerceIn(0, n - 1)
                    val next = if (action == ShortcutAction.FRAME_NEXT) {
                        (cur + 1).coerceAtMost(n - 1)
                    } else {
                        (cur - 1).coerceAtLeast(0)
                    }
                    s.setFrame(next)
                }
                ShortcutAction.PLAY_TOGGLE -> {
                    playTickExt.value++
                    true
                }
                ShortcutAction.FULLSCREEN -> {
                    fullscreen.value = !fullscreen.value
                    true
                }
                ShortcutAction.HELP -> {
                    showShortcuts.value = true
                    true
                }
                // T1 §5: Q = bascule du masque rapide (session, overlay).
                ShortcutAction.QUICK_MASK -> {
                    if (s == null) return false
                    s.quickMask = !s.quickMask
                    if (s.quickMask) {
                        s.paintTool = null
                        s.selectionTool = null
                    }
                    true
                }
            }
        }
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
                // .clip (Clip Studio Paint) goes through the app-side
                // DocClip converter first: CSFCHUNK envelope + embedded
                // SQLite -> multi-layer ORA (or preview-only ORA), then
                // the existing nLoadDocument flow. No new JNI symbol.
                if (name.endsWith(".clip", ignoreCase = true)) {
                    Thread {
                        val converted = DocClip.importToOra(dst, ctx.cacheDir)
                        val okImport = converted as? DocClip.Outcome.Ok
                        mainHandler.post {
                            val oraPath = okImport?.import?.oraFile
                            val report = okImport?.import?.report
                            if (oraPath != null && report != null) {
                                surface.loadFrom(oraPath.absolutePath) { ok ->
                                    try {
                                        oraPath.delete()
                                    } catch (_: Exception) {
                                    }
                                    if (ok) {
                                        val id = activeDocId.value
                                        if (id != null) {
                                            docRegistry.open(id, name)
                                            docs.firstOrNull { it.docId == id }?.name = name
                                            docRegistry.markDirty(id, false)
                                        }
                                    }
                                    status.value = when {
                                        !ok -> ctx.getString(R.string.clip_failed)
                                        report.previewOnly ->
                                            ctx.getString(R.string.clip_preview_only, name)
                                        report.approximations.isNotEmpty() ->
                                            ctx.getString(
                                                R.string.clip_degraded,
                                                name, report.approximations.size
                                            )
                                        else -> ctx.getString(R.string.import_ok) + " " + name
                                    }
                                    refreshKey.value++
                                }
                            } else {
                                try {
                                    dst.delete()
                                } catch (_: Exception) {
                                }
                                status.value = ctx.getString(R.string.clip_failed)
                                refreshKey.value++
                            }
                        }
                    }.start()
                    return@rememberLauncherForActivityResult
                }
                surface.loadFrom(dst.absolutePath) { ok ->
                    if (ok) {
                        val id = activeDocId.value
                        if (id != null) {
                            docRegistry.open(id, name)
                            docs.firstOrNull { it.docId == id }?.name = name
                            docRegistry.markDirty(id, false)
                            // T3 §3.5 : repousse le sidecar du doc.
                            pushSidecar(surface, id)
                        }
                    }
                    status.value = if (ok)
                        ctx.getString(R.string.import_ok) + " " + name
                    else ctx.getString(R.string.import_failed)
                    refreshKey.value++
                }
            }
        }

        // W1: reference photo import (session-only, never layer pixels).
        val openRefPhoto = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null) {
                try {
                    val bmp = ctx.contentResolver.openInputStream(uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it)
                    }
                    if (bmp != null) RefPhoto.setPhoto(bmp)
                } catch (_: Exception) {
                }
                showW1Ref.value = true
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
                // T2 §5: OFF = text flattened to pixels (fiches stashed,
                // cleared, save, restored in finally — meta-only, the
                // pixels and undo stacks are untouched).
                val stashed = if (fmt == "psd" && !includeText.value) {
                    val m = stashTextFiches(surface)
                    clearTextFiches(surface)
                    m
                } else null
                surface.saveTo(dst.absolutePath, fmt) { ok ->
                    if (stashed != null) restoreTextFiches(surface, stashed)
                    if (ok) {
                        copyFromTo(ctx, dst, uri)
                        dst.delete()
                        // V+2: layered PSD reports its approximation
                        // count (0 = pixel-faithful, status stays clean).
                        val approx = if (fmt == "psd") surface.psdApproxCount() else 0
                        status.value = if (approx > 0) {
                            ctx.getString(R.string.v2_psd_approx, approx)
                        } else {
                            ctx.getString(R.string.export_ok)
                        }
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
                    audioPath.value = dst.absolutePath
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
                    refGlbPath.value = dst.absolutePath
                    surface.setRefVisible(true)
                    status.value = ctx.getString(R.string.ref_loaded, edges)
                } else {
                    dst.delete()
                    refModelName.value = null
                    status.value = ctx.getString(R.string.ref_failed)
                }
            }
        }

        // T5 §4/§6: exchange file pick (grd/abr) + share-out for
        // EPUB/PDF/sequence/sticker/fanzine cache files (SAF).
        val openExchange = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            val cb = exchangePick.value
            exchangePick.value = null
            if (uri != null && cb != null) {
                val dst = java.io.File(
                    ctx.cacheDir, "t5-in-${System.currentTimeMillis()}"
                )
                copyTo(ctx, uri, dst)
                cb(dst)
            } else {
                cb?.invoke(null)
            }
        }
        val createT5 = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri: Uri? ->
            val f = pendingShare.value
            pendingShare.value = null
            if (uri != null && f != null) {
                copyFromTo(ctx, f, uri)
                try {
                    f.delete()
                } catch (_: Exception) {
                }
                status.value = ctx.getString(R.string.export_ok)
            } else if (uri != null) {
                status.value = ctx.getString(R.string.export_failed)
            }
        }

        // T2 §1: user font import (SAF font/* -> fonts/ library;
        // libre files only — confirmed by the user at import, origin
        // logged like third_party/README.md; honest refusals, no crash).
        val openFont = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null) {
                val name = queryDisplayName(ctx, uri) ?: "font.ttf"
                val dst = java.io.File(
                    ctx.cacheDir, "font-${System.currentTimeMillis()}-$name"
                )
                copyTo(ctx, uri, dst)
                when (val r = FontRegistry.importFrom(dst, name)) {
                    is FontRegistry.ImportResult.Ok -> {
                        status.value = ctx.getString(R.string.import_ok) + " " + r.family
                        fontsTick.value++
                    }
                    is FontRegistry.ImportResult.Refused -> {
                        status.value = ctx.getString(
                            if (r.reason == "too_large") R.string.font_too_large
                            else R.string.import_failed
                        )
                    }
                }
                dst.delete()
            }
        }

        // V+3 §6.2: PDF first page via the platform renderer (150 dpi),
        // then the PNG bridge (nLoadDocument). Page 1 only, honest.
        val openPdf = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            val surface = activeSurface()
            if (uri != null && surface != null) {
                val name = queryDisplayName(ctx, uri) ?: "import.pdf"
                val dst = java.io.File(
                    ctx.cacheDir, "pdf-${System.currentTimeMillis()}-$name"
                )
                copyTo(ctx, uri, dst)
                Thread {
                    val bmp = PdfImport.renderFirstPage(dst, 150)
                    if (bmp == null) {
                        mainHandler.post {
                            status.value = ctx.getString(R.string.import_failed)
                        }
                        return@Thread
                    }
                    val png = java.io.File(
                        ctx.cacheDir,
                        "pdf-page-${System.currentTimeMillis()}.png"
                    )
                    if (!TextOnPath.bitmapToPng(bmp, png)) {
                        bmp.recycle()
                        mainHandler.post {
                            status.value = ctx.getString(R.string.import_failed)
                        }
                        return@Thread
                    }
                    bmp.recycle()
                    mainHandler.post {
                        surface.loadFrom(png.absolutePath) { ok ->
                            try {
                                png.delete()
                                dst.delete()
                            } catch (_: Exception) {
                            }
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
                }.start()
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
                    ctx.cacheDir, "calque-${System.currentTimeMillis()}.webm"
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

        // B2 §5: timelapse WebM export (same SAF + cache pattern as the
        // clip WebM lane; frames are the recorder's PNG stills).
        val createTimelapse = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("video/webm")
        ) { uri: Uri? ->
            if (uri != null && !exporting.value) {
                exporting.value = true
                val dst = java.io.File(
                    ctx.cacheDir,
                    "timelapse-${System.currentTimeMillis()}.webm"
                )
                Thread {
                    val ok = try {
                        timelapse.exportWebm(clipFps.value, dst)
                    } catch (_: Exception) {
                        false
                    }
                    if (ok) copyFromTo(ctx, dst, uri)
                    dst.delete()
                    Handler(Looper.getMainLooper()).post {
                        status.value = ctx.getString(
                            if (ok) R.string.export_ok else R.string.export_failed
                        )
                        exporting.value = false
                    }
                }.start()
            } else if (uri != null) {
                status.value = ctx.getString(R.string.export_failed)
            }
        }

        // V+2 long exports (GIF/webtoon/SVG): panels render to cache,
        // one SAF hop per file (mime + suggested name hoisted here).
        val pendingExportMime = remember { mutableStateOf("image/gif") }
        val pendingExportFile = remember { mutableStateOf<java.io.File?>(null) }
        val createExportFile = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri: Uri? ->
            val f = pendingExportFile.value
            pendingExportFile.value = null
            if (uri != null && f != null && f.exists()) {
                try {
                    ctx.contentResolver.openOutputStream(uri)?.use { o ->
                        f.inputStream().use { i -> i.copyTo(o) }
                        status.value = ctx.getString(R.string.export_ok)
                    } ?: run {
                        status.value = ctx.getString(R.string.export_failed)
                    }
                } catch (_: Exception) {
                    status.value = ctx.getString(R.string.export_failed)
                } finally {
                    try {
                        f.delete()
                    } catch (_: Exception) {
                    }
                }
            } else if (uri != null) {
                status.value = ctx.getString(R.string.export_failed)
            }
        }
        fun offerExportFile(f: java.io.File, mime: String) {
            pendingExportMime.value = mime
            pendingExportFile.value = f
            createExportFile.launch("calque-${System.currentTimeMillis()}")
        }

        // T3 §3 palier a + §4 : CSV de la feuille + audio seul (SAF,
        // un hop par fichier comme V+2 ; l'audio = copie byte-identique
        // du cache, 0 JNI).
        val pendingCsv = remember { mutableStateOf<String?>(null) }
        val createCsv = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/csv")
        ) { uri: Uri? ->
            val csv = pendingCsv.value
            pendingCsv.value = null
            if (uri != null && csv != null) {
                try {
                    ctx.contentResolver.openOutputStream(uri)?.use { o ->
                        o.write(csv.toByteArray())
                        status.value = ctx.getString(R.string.export_ok)
                    } ?: run {
                        status.value = ctx.getString(R.string.export_failed)
                    }
                } catch (_: Exception) {
                    status.value = ctx.getString(R.string.export_failed)
                }
            } else if (uri != null) {
                status.value = ctx.getString(R.string.export_failed)
            }
        }
        val createAudioOnly = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri: Uri? ->
            val src = audioPath.value?.let { java.io.File(it) }
            if (uri != null && src != null && src.exists()) {
                try {
                    ctx.contentResolver.openOutputStream(uri)?.use { o ->
                        src.inputStream().use { i -> i.copyTo(o) }
                        status.value = ctx.getString(R.string.export_ok)
                    } ?: run {
                        status.value = ctx.getString(R.string.export_failed)
                    }
                } catch (_: Exception) {
                    status.value = ctx.getString(R.string.export_failed)
                }
            } else if (uri != null) {
                status.value = if (src == null || !src.exists()) {
                    ctx.getString(R.string.export_no_audio)
                } else {
                    ctx.getString(R.string.export_failed)
                }
            }
        }


        // Issue #32 §2 crash recovery: autosave is ON by default and writes
        // timestamped OpenRaster snapshots (local only, filesDir/autosave)
        // every N minutes and whenever the app goes to the background. At
        // launch, a snapshot newer than the last explicit save of a document
        // raises a Restore / Ignore prompt.
        val autosaveOn = remember { mutableStateOf(true) }
        val autosaveMin = remember { mutableStateOf(5) }
        val autosaveLast = remember { mutableStateOf("") }
        // Session start counts as the last autosave point: the first
        // periodic snapshot lands one full interval after launch, never 15 s
        // in (that would snapshot half-drawn test/opening states).
        val autosaveLastMs = remember { mutableStateOf(SystemClock.uptimeMillis()) }
        val lastSnapshotAt = remember { mutableStateMapOf<String, Long>() }
        // Computed once per composition root: after `recreate()` this reads
        // the snapshots the dead process left behind.
        val recoveryPending = remember {
            mutableStateListOf<CrashRecovery.RecoverySnapshot>().apply {
                addAll(CrashRecovery.pending(ctx.filesDir))
            }
        }
        val showRecovery = remember { mutableStateOf(recoveryPending.isNotEmpty()) }

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
            dockTab.value = pose.dockTab.coerceIn(0, 3)
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

        // T2 §1–§2: shared text commit outcome (inline + panel).
        // B2 §1: the committed layer opens its transform handles (one
        // undo per transform commit).
        fun textCommitDone(surf: EngineSurface, ok: Boolean, spec: TextSpec) {
            status.value = if (ok) {
                ctx.getString(R.string.text_committed)
            } else {
                ctx.getString(R.string.filter_failed)
            }
            if (ok) {
                val aid = activeDocId.value
                if (aid != null) {
                    docRegistry.markDirty(aid, true)
                }
                val idx = surf.lastTextIndex
                if (idx >= 0) {
                    textXformLayer.value = idx
                    textXformSpec.value = spec
                    showTextXform.value = true
                }
            }
            if (surf.lastFontFallback) {
                status.value = ctx.getString(
                    R.string.text_font_fallback
                )
            }
            refreshKey.value++
        }

        // B2 §5: pull the current composite and store one timelapse
        // frame (background thread; failures never surface as crashes).
        fun captureTimelapse(surf: EngineSurface) {
            Thread {
                val bmp = try {
                    // The CURRENT cel (multi-frame docs), not frame 0.
                    surf.pullExportBitmap(surf.currentFrameIndex())
                } catch (_: Exception) {
                    null
                }
                if (bmp != null) {
                    try {
                        timelapse.capture(
                            bmp, java.io.File(ctx.cacheDir, "timelapse")
                        )
                    } finally {
                        if (!bmp.isRecycled) bmp.recycle()
                    }
                }
                Handler(Looper.getMainLooper()).post {
                    timelapseFrames.value = timelapse.frames.size
                }
            }.start()
        }

        fun wireSurface(cv: EngineSurface, docId: String) {
            cv.navigateMode = navMode.value
            cv.edgeBottomEnabled = compact
            cv.onSelectionChanged = {
                selectionTick.value++
                refreshKey.value++
                val s = surfaces[docId]
                status.value = if (s?.hasSelection() == true) {
                    ctx.getString(R.string.selection_active_hint)
                } else {
                    ""
                }
            }
            // T5 §1: refused-write guard hint (barred ring + status).
            cv.onGuardHint = { key ->
                status.value = when (key) {
                    "fill_locked_hint" -> ctx.getString(R.string.fill_locked_hint)
                    "selection_adjust_guard" ->
                        ctx.getString(R.string.selection_adjust_guard)
                    else -> ctx.getString(R.string.layer_locked_hint)
                }
            }
            // T2 §2: inline canvas editor (anchored to the text
            // frame, follows the view); the TextPanel stays for
            // settings (runs, outline, vertical, hyphen).
            cv.onTextAnchor = { ex, ey ->
                textAnchor.value = Pair(ex, ey)
                textEditLayer.value = -1
                textInitial.value = TextSpec(
                    text = "",
                    boxX = ex.coerceIn(0f, cv.canvasWidth().toFloat()),
                    boxY = ey.coerceIn(0f, cv.canvasHeight().toFloat()),
                    boxW = (cv.canvasWidth() - ex).coerceAtLeast(64f)
                )
                showInline.value = true
            }
            cv.onTextDoubleTap = { layer ->
                val fiche = cv.layerTextForTest(layer)
                if (fiche != null) {
                    textAnchor.value = Pair(fiche.boxX, fiche.boxY)
                    textEditLayer.value = layer
                    textInitial.value = fiche
                    showInline.value = true
                }
            }
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
            // B2 §5: one frame every N PAINT strokes (layer/flag edits
            // never count); the label gate keeps milestones meaningful.
            val tlLabels = setOf(
                "stroke", "vector", "smart", "refpaint", "blit",
                "fill", "gradient", "shape", "text"
            )
            cv.onCommittedStroke = { label ->
                if (timelapseOn.value && label in tlLabels &&
                    timelapse.onStroke()
                ) {
                    captureTimelapse(cv)
                }
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

        // V2/V15: poke() no longer writes the user-visible status
        // (POKE leaked into the footer) nor the panels' refreshKey
        // (every poke recomposed the whole dock). It bumps an isolated
        // tick as a test hook; the surrounding state mutations already
        // schedule the recompositions they need.
        fun poke() {
            pokeTick.value++
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
            bgArgb: Int?,
            loadPath: String? = null,
            onLoaded: ((Boolean) -> Unit)? = null
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
            if (bgArgb == -1 && loadPath == null) {
                // Engine default base is already opaque white: loading a
                // white paper would be a no-op that races user strokes, so
                // skip the ORA round trip entirely.
                paperDone[id] = true
            } else if (loadPath != null) {
                // Issue #32 §2 crash recovery: the snapshot already carries
                // the pixels, so no paper fill is queued (it could land
                // after the restore and wipe it). The tab is marked dirty
                // once the load lands: that content is not on disk in any
                // document file yet.
                paperDone[id] = true
                Thread {
                    var restored = false
                    try {
                        for (k in 0 until 100) {
                            if (cv.isReady()) break
                            Thread.sleep(100)
                        }
                        val lock = Object()
                        var done = false
                        var res = false
                        cv.loadFrom(loadPath) { r ->
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
                        restored = res
                    } catch (_: Exception) {
                        restored = false
                    }
                    val ok = restored
                    mainHandler.post {
                        if (ok) {
                            docRegistry.markDirty(id, true)
                            try {
                                java.io.File(loadPath).delete()
                            } catch (_: Exception) {
                            }
                        }
                        onLoaded?.invoke(ok)
                        refreshKey.value++
                    }
                }.start()
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
            // Issue #32 §3: refuse past the measured device limit (the
            // dialog already greys the button; this is the JNI-side
            // safety net for callers that bypass the dialog).
            val docBudget = dev.openclip.engine.MemoryBudget.fromContext(ctx).first
            val needBytes = spec.w.toLong() * spec.h.toLong() * 4L
            if (needBytes > docBudget) {
                status.value = ctx.getString(
                    R.string.newcanvas_mem_refuse,
                    dev.openclip.engine.MemoryBudget.mb(docBudget).toString()
                )
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
                        // Issue #32 §2: an explicit save supersedes every
                        // recovery snapshot of this document.
                        CrashRecovery.markExplicitSave(
                            ctx.filesDir, id, System.currentTimeMillis()
                        )
                        CrashRecovery.clearDoc(ctx.filesDir, id)
                        // T3 §3.5 : persiste le sidecar avec le doc.
                        pullSidecar(surf, id)
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
                    if (ok) {
                        docRegistry.markDirty(id, false)
                        // Issue #32 §2: explicit save clears the watermark.
                        CrashRecovery.markExplicitSave(
                            ctx.filesDir, id, System.currentTimeMillis()
                        )
                        CrashRecovery.clearDoc(ctx.filesDir, id)
                        // T3 §3.5 : persiste le sidecar avec le doc.
                        pullSidecar(surf, id)
                    }
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

        // ---- Issue #32 §2: crash-recovery snapshots ----
        // Enqueue a timestamped ORA snapshot of one DIRTY document into
        // filesDir/autosave. Never runs mid-stroke, never prompts for a
        // destination (local only) and drops duplicate writes inside
        // [minIntervalMs]. The engine write is async: this only enqueues.
        fun writeSnapshot(id: String, minIntervalMs: Long = 0L): Boolean {
            val surf = surfaces[id] ?: return false
            val tab = docs.firstOrNull { it.docId == id } ?: return false
            val entry = docRegistry.all().firstOrNull { it.docId == id } ?: return false
            if (!entry.dirty) return false
            if (surf.isDrawingNow()) return false
            val now = System.currentTimeMillis()
            val prev = lastSnapshotAt[id] ?: 0L
            if (minIntervalMs > 0L && now - prev < minIntervalMs) return false
            lastSnapshotAt[id] = now
            val dst = CrashRecovery.snapshotFile(ctx.filesDir, id, now)
            CrashRecovery.recordDoc(ctx.filesDir, id, tab.name, tab.w, tab.h, tab.dpi)
            surf.saveTo(dst.absolutePath, "ora") { ok ->
                mainHandler.post {
                    if (ok) {
                        CrashRecovery.prune(ctx.filesDir)
                        autosaveLastMs.value = SystemClock.uptimeMillis()
                        val stamp = java.text.SimpleDateFormat(
                            "HH:mm", java.util.Locale.getDefault()
                        ).format(java.util.Date())
                        autosaveLast.value =
                            ctx.getString(R.string.v2_autosave_saved, stamp)
                        dev.openclip.ui.V2TestHooks.lastAutosave = "wrote"
                    } else {
                        try {
                            dst.delete()
                        } catch (_: Exception) {
                        }
                        dev.openclip.ui.V2TestHooks.lastAutosave = "failed"
                    }
                }
            }
            return true
        }

        /** Background pass: every dirty document, de-duplicated per pause. */
        fun snapshotAllDirty(minIntervalMs: Long) {
            for (tab in docs.toList()) writeSnapshot(tab.docId, minIntervalMs)
        }

        // Restore one snapshot. When the crashed document id maps to a
        // still-empty tab of the same geometry (the usual single-document
        // case after a restart), the pixels are loaded in place instead of
        // stacking a duplicate tab; otherwise a new tab is opened.
        fun restoreSnapshot(snap: CrashRecovery.RecoverySnapshot): Boolean {
            if (!java.io.File(snap.path).isFile) return false
            val existing = docs.firstOrNull { it.docId == snap.docId }
            if (existing != null && existing.w == snap.w && existing.h == snap.h) {
                val surf = surfaces[snap.docId] ?: return false
                paperDone[snap.docId] = true
                surf.loadFrom(snap.path) { ok ->
                    mainHandler.post {
                        if (ok) {
                            existing.name = snap.name
                            docRegistry.all()
                                .firstOrNull { it.docId == snap.docId }
                                ?.name = snap.name
                            docRegistry.markDirty(snap.docId, true)
                            try {
                                java.io.File(snap.path).delete()
                            } catch (_: Exception) {
                            }
                            // Consumed: never offer this file again even if
                            // the delete above failed.
                            CrashRecovery.markExplicitSave(
                                ctx.filesDir, snap.docId,
                                maxOf(
                                    System.currentTimeMillis(),
                                    snap.savedAtMs + 1
                                )
                            )
                        }
                        refreshKey.value++
                    }
                }
                return true
            }
            return openDocTab(
                snap.name, snap.w, snap.h, snap.dpi, null, snap.path
            ) { ok ->
                if (ok) {
                    // Consumed: never offer this snapshot again, even if
                    // the file delete inside openDocTab failed.
                    CrashRecovery.markExplicitSave(
                        ctx.filesDir, snap.docId,
                        maxOf(System.currentTimeMillis(), snap.savedAtMs + 1)
                    )
                }
            } != null
        }

        // Autosave tick (15 s poll): writes a timestamped snapshot of the
        // active document when the policy passes — enabled, interval
        // elapsed, no open stroke, document dirty. Never touches the user's
        // saved file: that stays an explicit action.
        androidx.compose.runtime.LaunchedEffect(autosaveOn.value, autosaveMin.value) {
            while (true) {
                kotlinx.coroutines.delay(15_000)
                try {
                    val id = activeDocId.value ?: continue
                    if (!autosaveOn.value) continue
                    if (surfaces[id] == null) continue
                    val now = SystemClock.uptimeMillis()
                    if (!dev.openclip.ui.AutosavePolicy.shouldSave(
                            autosaveOn.value, autosaveMin.value,
                            autosaveLastMs.value, now, false
                        )
                    ) continue
                    if (!writeSnapshot(id)) continue
                    // Back off one interval even if the async write fails.
                    autosaveLastMs.value = now
                } catch (_: Exception) {
                }
            }
        }

        // Issue #32 §2 background pass: ON_PAUSE + ON_STOP snapshot every
        // dirty document. The 5 s gate makes the second event a no-op so a
        // single background transition writes each document once. A
        // configuration change (rotation, recreate) is NOT a background
        // transition: it would snapshot on every rotation for nothing.
        val lifecycleOwner = LocalLifecycleOwner.current
        val hostActivity = LocalContext.current as? android.app.Activity
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event != Lifecycle.Event.ON_PAUSE &&
                    event != Lifecycle.Event.ON_STOP
                ) return@LifecycleEventObserver
                if (hostActivity?.isChangingConfigurations == true) {
                    return@LifecycleEventObserver
                }
                snapshotAllDirty(5_000L)
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        fun closeNow(id: String) {
            if (pendingTabId.value == id) pendingTabId.value = null
            if (preselectId.value == id) preselectId.value = null
            // Issue #32 §2: a tab closed on purpose (saved, or discarded by
            // the user) must not resurface as a crash snapshot next launch.
            try {
                CrashRecovery.clearDoc(ctx.filesDir, id)
                CrashRecovery.markExplicitSave(
                    ctx.filesDir, id, System.currentTimeMillis()
                )
            } catch (_: Exception) {
            }
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
        // Issue #32 §2: when a crash snapshot is waiting, the workspace
        // stays empty until the recovery prompt is answered.
        val booted = remember { mutableStateOf(false) }
        if (!booted.value) {
            booted.value = true
            if (docs.isEmpty() && recoveryPending.isEmpty()) {
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
            if (docs.isEmpty() && recoveryPending.isEmpty()) {
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
            activityTestHooks.openTextTransform = { layer, spec ->
                textXformLayer.value = layer
                textXformSpec.value = spec
                showTextXform.value = true
                poke()
            }
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
            // Issue #32 §2: crash-recovery probes.
            activityTestHooks.markDirty = { id: String, d: Boolean ->
                docRegistry.markDirty(id, d)
                refreshKey.value++
                poke()
            }
            activityTestHooks.snapshotDoc = { id: String -> writeSnapshot(id) }
            activityTestHooks.pendingSnapshots = {
                CrashRecovery.pending(ctx.filesDir).size
            }
            activityTestHooks.recoveryPromptVisible = { showRecovery.value }
            activityTestHooks.discardSnapshots = {
                CrashRecovery.discardPending(ctx.filesDir)
                recoveryPending.clear()
                showRecovery.value = false
                refreshKey.value++
                poke()
            }
            activityTestHooks.docNameOf = { id: String ->
                docs.firstOrNull { it.docId == id }?.name
            }
        }

        // V16: short viewports (landscape phones) can't stack 8 chrome
        // rows — the column scrolls and the canvas keeps a fixed half
        // of the viewport instead of collapsing to zero.
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF141418))
                .systemBarsPadding()
                // V+3 §7: physical-keyboard shortcuts (single hook).
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val code = try {
                        event.nativeKeyEvent.keyCode
                    } catch (_: Exception) {
                        return@onPreviewKeyEvent false
                    }
                    val action = ShortcutMap.actionFor(
                        code, event.isCtrlPressed, event.isShiftPressed
                    ) ?: return@onPreviewKeyEvent false
                    handleShortcut(action)
                }
        ) {
            val outerMaxH = maxHeight
            val shortH = maxHeight < 600.dp
            val outerScroll = rememberScrollState()
            Column(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (shortH) Modifier.verticalScroll(outerScroll)
                        else Modifier
                    )
                    .testTag("studio-column")
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
                        label = stringResource(R.string.window_bd),
                        onClick = {
                            dockTab.value = 2
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
                    ),
                    // V+3 §7: fixed-table keyboard shortcuts (?).
                    StudioMenuItem(
                        label = stringResource(R.string.shortcut_title),
                        onClick = { showShortcuts.value = true }
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
                            // V+1: under an active selection the filter
                            // applies masked (flatten + history reset,
                            // announced); on an adjustment layer it is
                            // refused with the guard hint.
                            val masked = surface.hasSelection()
                            surface.applyFilterInSelection(name, "{}") { ok ->
                                status.value = when {
                                    !ok && surface.activeLayerIsAdjustment() ->
                                        ctx.getString(R.string.selection_adjust_guard)
                                    ok && masked ->
                                        ctx.getString(R.string.selection_flattened)
                                    ok -> ctx.getString(R.string.filter_applied)
                                    else -> ctx.getString(R.string.filter_failed)
                                }
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
                                            "calque-${System.currentTimeMillis()}.webm"
                                        )
                                    }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.webdav),
                                    onClick = { showWebdav.value = true }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.doc_close),
                                    onClick = {
                                        val aid = activeDocId.value
                                        if (aid != null) requestClose(aid)
                                    }
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
                                ),
                                // W1 (issue #28): merge / flatten / rasterize
                                // + canvas size (1 engine undo step each).
                                StudioMenuItem(
                                    label = stringResource(R.string.w1_merge_down),
                                    enabled = surface.layerCount() > 1,
                                    onClick = {
                                        if (surface.mergeLayers(
                                                "down", surface.activeLayerIndex()
                                            )
                                        ) {
                                            refreshKey.value++
                                        }
                                    }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.w1_merge_visible),
                                    onClick = {
                                        if (surface.mergeLayers("visible")) refreshKey.value++
                                    }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.w1_flatten),
                                    onClick = {
                                        if (surface.mergeLayers("flatten")) refreshKey.value++
                                    }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.w1_rasterize),
                                    onClick = {
                                        if (surface.mergeLayers(
                                                "rasterize", surface.activeLayerIndex()
                                            )
                                        ) {
                                            refreshKey.value++
                                        }
                                    }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.w1_canvas_size),
                                    onClick = { showW1Canvas.value = true }
                                )
                            )
                        ),
                        StudioMenu(
                            title = stringResource(R.string.menu_selection),
                            items = listOf(
                                StudioMenuItem(
                                    label = stringResource(R.string.select_all),
                                    onClick = {
                                        surface.selectAll()
                                        refreshKey.value++
                                    }
                                ),
                                StudioMenuItem(
                                    // selectionTick resubscribes the menu
                                    // when the mask changes (surface-side).
                                    label = stringResource(R.string.deselect) +
                                        if (selectionTick.value < 0) "" else "",
                                    enabled = surface.hasSelection(),
                                    onClick = {
                                        surface.clearSelection()
                                        refreshKey.value++
                                    }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.select_invert),
                                    enabled = surface.hasSelection(),
                                    onClick = {
                                        surface.invertSelection()
                                        refreshKey.value++
                                    }
                                ),
                                StudioMenuItem(
                                    // T1 §5 : l'ancien +1px fixe est
                                    // remplace par le dialogue a rayon
                                    // (migration documentee, pas de code
                                    // mort : featherSelection() reste pour
                                    // le toggle baguette "adoucir 1px").
                                    label = stringResource(R.string.select_feather_radius),
                                    enabled = surface.hasSelection(),
                                    onClick = { showFeather.value = true }
                                ),
                                // W1 (issue #28): grow / shrink / feather N px,
                                // select from layer alpha, polyline tool.
                                StudioMenuItem(
                                    label = stringResource(R.string.w1_sel_grow),
                                    enabled = surface.hasSelection(),
                                    onClick = { w1SelOp.value = "grow" }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.w1_sel_shrink),
                                    enabled = surface.hasSelection(),
                                    onClick = { w1SelOp.value = "shrink" }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.w1_sel_featherpx),
                                    enabled = surface.hasSelection(),
                                    onClick = { w1SelOp.value = "feather" }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.w1_sel_from_layer),
                                    onClick = {
                                        if (surface.selectFromLayerAlpha(
                                                surface.activeLayerIndex(), 1, ctx.cacheDir
                                            )
                                        ) {
                                            refreshKey.value++
                                        }
                                    }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.selection_polyline),
                                    onClick = {
                                        surface.selectionTool = SelectionTool.POLYLINE
                                        refreshKey.value++
                                    }
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
                                // W1 §3: view-only mirror (never pixels,
                                // never coordinates, never an export).
                                StudioMenuItem(
                                    label = stringResource(
                                        if (surface.viewFlipHorizontal()) R.string.w1_flip_h_on
                                        else R.string.w1_flip_h
                                    ),
                                    onClick = {
                                        surface.toggleViewFlipH()
                                        refreshKey.value++
                                    }
                                ),
                                StudioMenuItem(
                                    label = stringResource(
                                        if (surface.viewFlipVertical()) R.string.w1_flip_v_on
                                        else R.string.w1_flip_v
                                    ),
                                    onClick = {
                                        surface.toggleViewFlipV()
                                        refreshKey.value++
                                    }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.w1_ref_title),
                                    onClick = { showW1Ref.value = true }
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
                        helpMenu,
                        // T5 échanges + pages + productivité (spec
                        // docs/t5-exchange.md) : dialogs, session-only
                        // where specified.
                        StudioMenu(
                            title = stringResource(R.string.menu_t5),
                            items = listOf(
                                StudioMenuItem(
                                    label = stringResource(R.string.fill_title),
                                    onClick = { showT5Fill.value = true }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.b1_texture_title),
                                    onClick = { showB1Texture.value = true }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.import_grd),
                                    onClick = { showT5Import.value = true }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.match_title),
                                    onClick = { showT5Match.value = true }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.pages_work),
                                    onClick = { showT5Pages.value = true }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.export_epub),
                                    onClick = { showT5Export.value = true }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.macro_title),
                                    onClick = { showT5Macro.value = true }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.align_title),
                                    onClick = { showT5Align.value = true }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.history_title),
                                    onClick = { showT5History.value = true }
                                ),
                                StudioMenuItem(
                                    label = stringResource(R.string.info_title),
                                    onClick = { showT5Info.value = true }
                                )
                            )
                        ),
                    ),
                    compact = compact,
                    docTitle = docs.firstOrNull { it.docId == activeId }?.name,
                    docDirty = docRegistry.all().firstOrNull {
                        it.docId == activeId
                    }?.dirty == true,
                    // V3: compact never shows its own title — the title
                    // bar (1 doc) or the tabs (≥ 2 docs) are the single
                    // name source; the bar keeps only ☰ + Save.
                    showTitle = false,
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
                        onStatus = { status.value = it },
                        undoAvail = undoAvail,
                        onFileClicked = { showExport.value = true },
                        onImportClicked = { openDoc.launch(arrayOf("*/*")) }
                    )
                }
                HorizontalDivider(color = Color(0xFF2E2E38), thickness = 1.dp)
            }
            // Slice 6 tabs + title + command bar (overlay chrome, never
            // engine pixels). Tabs stay visible with 0 docs so the "+" can
            // recreate a canvas.
            // V3: compact + exactly 1 doc shows a "+"-only row (same 48dp
            // height, no name chip — the title bar stays the single name
            // source). The height never moves, so opening/closing a tab
            // never resizes the canvas (the engine resets the view zoom
            // on surface size changes).
            val tabEntries = docRegistry.all()
            DocTabs(
                docs = tabEntries,
                activeDocId = activeDocId.value,
                preselectedDocId = preselectId.value,
                compact = compact,
                hideNames = compact && tabEntries.size == 1,
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
                            undoAvail = undoAvail,
                            onDisabledHint = { action ->
                                status.value = ctx.getString(
                                    if (action == "redo") R.string.redo_empty
                                    else R.string.undo_empty
                                )
                            },
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

            // 2. Central area: dominant canvas. Wide windows keep the
            // side-by-side dock; phones (< 600dp, V5) get a bottom-sheet
            // overlay + scrim so the canvas keeps its full width.
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (shortH) Modifier.height(outerMaxH * 0.55f)
                        else Modifier.weight(1f)
                    )
                    .testTag("central-area")
            ) {
                val phoneDock = maxWidth < 600.dp
                // Side dock steals canvas width (wide only): keep every
                // canvas pixel visible instead of painting under the dock.
                val sideDockW = if (phoneDock || fullscreen.value) 0.dp
                else if (dockOpen.value) 290.dp else 56.dp
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF141418))
                        .padding(end = sideDockW)
                        .testTag("canvas-box")
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
                    // V15: the status read lives in the overlay scope, not
                    // in the screen root.
                    if (fullscreen.value) {
                        FullscreenTopBar(
                            status = status,
                            onExit = { fullscreen.value = false },
                            modifier = Modifier.align(Alignment.TopEnd)
                        )
                    }
                    // T2 §2: inline text field (anchored to the text
                    // frame in canvas-box coords = engineToScreen space;
                    // follows pan/pinch/rotate via view ticks; Valider =
                    // 1-undo commit, Réglages = full panel, Échap = drop).
                    if (showInline.value) {
                        val surf = activeSurface()
                        val anchor = textAnchor.value
                        if (surf != null && anchor != null) {
                            key(fontsTick.value + viewZoomTick.value + loupeTick.value) {
                                InlineTextBox(
                                    surface = surf,
                                    anchor = anchor,
                                    initial = textInitial.value,
                                    onCommit = { text ->
                                        showInline.value = false
                                        val layer = textEditLayer.value
                                        val spec =
                                            textInitial.value.copy(text = text)
                                        val done: (Boolean) -> Unit = { ok ->
                                            textCommitDone(surf, ok, spec)
                                        }
                                        if (layer >= 0) surf.reeditText(
                                            layer, spec, done
                                        )
                                        else surf.commitText(spec, done)
                                    },
                                    onSettings = {
                                        showInline.value = false
                                        showTextPanel.value = true
                                    },
                                    onDismiss = { showInline.value = false },
                                    modifier = Modifier.align(Alignment.TopStart)
                                )
                            }
                        } else {
                            showInline.value = false
                        }
                    }
                    // B2 §1: transform handles over the text frame
                    // (auto-opened after a text commit; 48dp targets).
                    if (showTextXform.value) {
                        val surf = activeSurface()
                        val layer = textXformLayer.value
                        if (surf != null && layer >= 0) {
                            key(viewZoomTick.value + loupeTick.value) {
                                TextTransformOverlay(
                                    surface = surf,
                                    layer = layer,
                                    initial = textXformSpec.value,
                                    onCommit = { spec ->
                                        showTextXform.value = false
                                        surf.reeditText(layer, spec) { ok ->
                                            status.value = if (ok) {
                                                ctx.getString(
                                                    R.string.text_xform_committed
                                                )
                                            } else {
                                                ctx.getString(
                                                    R.string.filter_failed
                                                )
                                            }
                                            if (ok) {
                                                val aid = activeDocId.value
                                                if (aid != null) {
                                                    docRegistry.markDirty(
                                                        aid, true
                                                    )
                                                }
                                            }
                                            refreshKey.value++
                                        }
                                    },
                                    onDismiss = { showTextXform.value = false },
                                    modifier = Modifier.align(Alignment.TopStart)
                                )
                            }
                        } else {
                            showTextXform.value = false
                        }
                    }
                    // V+1: the raster eraser is a real dest-out erase
                    // (alpha-erase) — the honest banner is gone. A
                    // one-shot migration note announces the new
                    // behavior (persisted in BrushPrefs).
                    activeSurface()?.let { surface ->
                        if (brushPrefs.isEraser && !brushPrefs.eraserAlphaNoteShown &&
                            !eraserNoteShown.value
                        ) {
                            eraserNoteShown.value = true
                            showEraserNote.value = true
                        }
                        PinnedBrushBar(
                            surface = surface,
                            prefs = brushPrefs,
                            // B2 §1: the transform bar owns the canvas
                            // bottom while the handles are open.
                            hidden = showTimeline.value || showTextXform.value,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(
                                    start = 8.dp, end = 8.dp,
                                    // V5: the folded phone rail (28dp)
                                    // overlays the canvas foot — lift the
                                    // bar above it.
                                    bottom = if (phoneDock && !dockOpen.value) 40.dp
                                    else 8.dp
                                )
                        )
                    }
                }

                if (!fullscreen.value) {
                activeSurface()?.let { surface ->
                    // V5/V8: shared dock pieces — a right side sheet on
                    // wide windows, a bottom-sheet overlay + scrim on
                    // phones (the canvas keeps its full width).
                    val dockTabsScroll = rememberScrollState()
                    val dockTabsExtra: @Composable RowScope.() -> Unit = {
                            // V+2: chips overflow the header — the tab row
                            // scrolls (V4 fade, V8 end padding + tag) so
                            // the collapse toggle is never pushed
                            // off-screen (silent no-op clicks wedged the
                            // dock open).
                            androidx.compose.foundation.layout.Row(
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .horizontalScroll(dockTabsScroll)
                                    .scrollFade("dock-tabs-row", dockTabsScroll)
                                    .padding(end = 12.dp)
                                    .testTag("dock-tabs-row"),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilterChip(
                                    selected = dockTab.value == 0,
                                    onClick = { dockTab.value = 0 },
                                    label = {
                                        Text(
                                            stringResource(R.string.window_studio),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                )
                                FilterChip(
                                    selected = dockTab.value == 1,
                                    onClick = { dockTab.value = 1 },
                                    label = {
                                        Text(
                                            stringResource(R.string.window_tones),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                )
                                FilterChip(
                                    selected = dockTab.value == 2,
                                    onClick = { dockTab.value = 2 },
                                    label = {
                                        Text(
                                            stringResource(R.string.v2_bd_tab),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    modifier = Modifier.testTag("dock-tab-bd")
                                )
                                FilterChip(
                                    selected = dockTab.value == 3,
                                    onClick = { dockTab.value = 3 },
                                    label = {
                                        Text(
                                            stringResource(R.string.v2_confort_tab),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    modifier = Modifier.testTag("dock-tab-confort")
                                )
                            }
                    }
                    // Shared dock body (ColumnScope for the weight fill).
                    val dockPanels: @Composable ColumnScope.() -> Unit = {
                        // V15: composition counter — an isolated poke
                        // must leave this at zero.
                        RecomposeTestHooks.dockCompositions++
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .testTag("dock-scroll")
                                // V9: 8dp on every edge except the bottom,
                                // which gets an explicit end spacer.
                                .padding(start = 8.dp, end = 8.dp, top = 8.dp),
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
                            } else if (dockTab.value == 1) {
                                ReferencePanel(
                                    surface = surface,
                                    onImportGlb = { openGlb.launch(arrayOf("*/*")) },
                                    modelName = refModelName.value,
                                    docKey = activeDocId.value ?: "solo",
                                    inkHex = brushPrefs.toolColor,
                                    glbCachePath = refGlbPath.value,
                                    onStatus = { status.value = it },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                MaterialsRow(
                                    surface = surface,
                                    onWebdav = { showWebdav.value = true },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else if (dockTab.value == 2) {
                                // V+2 Comic tab: cases/bulles/vitesse,
                                // transfo, collage, Bézier, guides,
                                // exports longs, pages, navigateur,
                                // autosave, courbes (spec docs/v2-bd-export).
                                val v2key = activeDocId.value ?: "solo"
                                BdToolsPanel(
                                    surface = surface,
                                    docKey = v2key,
                                    cacheDir = ctx.cacheDir,
                                    onStatus = { status.value = it },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                TransformPanel(
                                    surface = surface,
                                    cacheDir = ctx.cacheDir,
                                    onStatus = { status.value = it },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                WarpPanel(
                                    surface = surface,
                                    cacheDir = ctx.cacheDir,
                                    onStatus = { status.value = it },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                SmartShapePanel(
                                    surface = surface,
                                    onStatus = { status.value = it },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                CopyPastePanel(
                                    surface = surface,
                                    cacheDir = ctx.cacheDir,
                                    onStatus = { status.value = it },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                BezierPanel(
                                    surface = surface,
                                    onStatus = { status.value = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    cacheDir = ctx.cacheDir,
                                    docKey = v2key
                                )
                                GuidesPanel(
                                    surface = surface,
                                    docKey = v2key,
                                    cacheDir = ctx.cacheDir,
                                    onStatus = { status.value = it },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                CurvesPanel(
                                    surface = surface,
                                    onStatus = { status.value = it },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                GifPanel(
                                    surface = surface,
                                    cacheDir = ctx.cacheDir,
                                    fps = clipFps.value,
                                    onExportFile = { f, mime ->
                                        offerExportFile(f, mime)
                                    },
                                    onStatus = { status.value = it },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                // T3 animation productive (spec
                                // docs/t3-animation.md §4) : sequences
                                // PNG (ZIP), APNG, stickers, audio seul.
                                T3ExportPanel(
                                    surface = surface,
                                    cacheDir = ctx.cacheDir,
                                    fps = clipFps.value,
                                    hasAudio = audioName.value != null,
                                    onExportFile = { f, mime ->
                                        offerExportFile(f, mime)
                                    },
                                    onExportAudioOnly = {
                                        createAudioOnly.launch(
                                            "calque-audio-${System.currentTimeMillis()}"
                                        )
                                    },
                                    onStatus = { status.value = it },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                WebtoonPanel(
                                    surface = surface,
                                    cacheDir = ctx.cacheDir,
                                    reading = "ltr",
                                    onExportFile = { f, mime ->
                                        offerExportFile(f, mime)
                                    },
                                    onStatus = { status.value = it },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                SvgPanel(
                                    surface = surface,
                                    cacheDir = ctx.cacheDir,
                                    onExportFile = { f, mime ->
                                        offerExportFile(f, mime)
                                    },
                                    onStatus = { status.value = it },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                // T2 text + vector avancé (spec
                                // docs/t2-text-vector.md): style par
                                // tracé, retracer/connecter/épaisseur +
                                // trajectoires, sommets, bulle dédiée.
                                VectorStylePanel(
                                    surface = surface,
                                    inkHex = inkHex,
                                    onStatus = { status.value = it },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                VectorOpsPanel(
                                    surface = surface,
                                    onStatus = { status.value = it },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                BubblePanel(
                                    surface = surface,
                                    onStatus = { status.value = it },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                PagesPanel(
                                    surface = surface,
                                    docKey = v2key,
                                    cacheDir = ctx.cacheDir,
                                    onStatus = { status.value = it },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                NavigatorPanel(
                                    surface = surface,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                AutosavePanel(
                                    enabled = autosaveOn.value,
                                    onEnabled = { autosaveOn.value = it },
                                    intervalMin = autosaveMin.value,
                                    onInterval = { autosaveMin.value = it },
                                    lastWrite = autosaveLast.value,
                                    // Issue #32 §3: the cap is a policy the
                                    // user can read, not a hidden limit.
                                    policyText = ctx.getString(
                                        R.string.memory_policy,
                                        dev.openclip.engine.MemoryBudget.mb(
                                            dev.openclip.engine.MemoryBudget
                                                .fromContext(ctx).first
                                        ).toString(),
                                        dev.openclip.engine.MemoryBudget.mb(
                                            dev.openclip.engine.MemoryBudget
                                                .fromContext(ctx).second
                                        ).toString()
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                // V+3 Confort tab: harmonies, channels,
                                // text-on-path, pose, proof, imports
                                // (spec docs/v3-confort.md).
                                val v3key = activeDocId.value ?: "solo"
                                HarmonyPanel(
                                    store = store,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                ChannelsPanel(
                                    surface = surface,
                                    docKey = v3key,
                                    cacheDir = ctx.cacheDir,
                                    onStatus = { status.value = it },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                TextPathPanel(
                                    surface = surface,
                                    cacheDir = ctx.cacheDir,
                                    docKey = v3key,
                                    onStatus = { status.value = it },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                PosePanel(
                                    surface = surface,
                                    docKey = v3key,
                                    cacheDir = ctx.cacheDir,
                                    onStatus = { status.value = it },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                ProofPanel(
                                    surface = surface,
                                    onStatus = { status.value = it },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                ImportPanel(
                                    surface = surface,
                                    cacheDir = ctx.cacheDir,
                                    onStatus = { status.value = it },
                                    onPickTiff = { openDoc.launch(arrayOf("*/*")) },
                                    onPickPdf = { openPdf.launch(arrayOf("application/pdf")) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            // V9: bottom-edge breathing room (+ test hook).
                            Spacer(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .testTag("dock-scroll-endpad")
                            )
                        }
                    }
                    val dockTitle = stringResource(R.string.window_dock)
                    if (!phoneDock) {
                        // Wide: right side sheet (the canvas is already
                        // end-padded to the same width).
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = sideDockW - 1.dp)
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(Color(0xFF2E2E38))
                        ) {}
                        CollapsibleDrawer(
                            title = dockTitle,
                            open = dockOpen.value,
                            onToggle = { dockOpen.value = !dockOpen.value },
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .width(
                                    if (dockOpen.value) 290.dp else 56.dp
                                )
                                .fillMaxHeight(),
                            expandGlyph = "⟨",
                            collapseGlyph = "⟩",
                            showTitleWhenCollapsed = false,
                            toggleTag = "dock-toggle",
                            headerExtra = dockTabsExtra
                        ) {
                            dockPanels()
                        }
                    } else if (dockOpen.value) {
                        // V5: phone bottom sheet over the full-width canvas.
                        // Exact 70% height (not just a max): the inner
                        // dock-scroll column needs a BOUNDED height for its
                        // weight(1f) to resolve, otherwise the content
                        // wraps and never scrolls (V9).
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0x73000000))
                                .clickable { dockOpen.value = false }
                                .testTag("dock-scrim")
                        ) {}
                        CollapsibleDrawer(
                            title = dockTitle,
                            open = true,
                            onToggle = { dockOpen.value = false },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(maxHeight * 0.7f),
                            expandGlyph = "⟨",
                            collapseGlyph = "⟩",
                            showTitleWhenCollapsed = false,
                            toggleTag = "dock-toggle",
                            headerExtra = dockTabsExtra
                        ) {
                            dockPanels()
                        }
                    } else {
                        // Phone, dock folded: slim bottom rail (same
                        // toggle tag + expand glyph as the side rail, so
                        // the dock stays one tap away with zero canvas
                        // cost beyond 28dp). No title — the folded rail
                        // must read as chrome, not content (slice 7).
                        CollapsibleDrawer(
                            title = dockTitle,
                            open = false,
                            onToggle = { dockOpen.value = true },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth(),
                            expandGlyph = "⟨",
                            collapseGlyph = "⟩",
                            showTitleWhenCollapsed = false,
                            toggleTag = "dock-toggle",
                            headerExtra = {},
                            content = {}
                        )
                    }
                }
            }
            } // central area (canvas + dock)

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
                            // V9: 8dp on every edge so the first/last item
                            // never hides under the drawer borders.
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                            .testTag("timeline-drawer")
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
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
                                audioPath.value = null
                            },
                            onExportWebm = {
                                createWebm.launch(
                                    "calque-${System.currentTimeMillis()}.webm"
                                )
                            },
                            // B2 §4: audio follows the scrub drag.
                            onScrubSeek = { frame ->
                                if (clipAudio.hasAudio) {
                                    clipAudio.seekTo(
                                        ClipAudio.celToMs(
                                            frame, clipFps.value
                                        )
                                    )
                                }
                            },
                            // B2 §5: timelapse recorder controls.
                            timelapseOn = timelapseOn.value,
                            timelapseFrames = timelapseFrames.value,
                            timelapseEvery = timelapseEvery.value,
                            onToggleTimelapse = {
                                if (timelapseOn.value) {
                                    timelapseOn.value = false
                                } else {
                                    timelapse.reset()
                                    timelapse.everyN = timelapseEvery.value
                                    timelapseFrames.value = 0
                                    timelapseOn.value = true
                                    captureTimelapse(surface)
                                    status.value = ctx.getString(
                                        R.string.timelapse_started
                                    )
                                }
                            },
                            onTimelapseEvery = { n ->
                                timelapseEvery.value = n.coerceIn(1, 50)
                                timelapse.everyN = timelapseEvery.value
                            },
                            onExportTimelapse = {
                                createTimelapse.launch(
                                    "timelapse-${System.currentTimeMillis()}.webm"
                                )
                            },
                            onExportCsv = { csv ->
                                pendingCsv.value = csv
                                createCsv.launch(
                                    "calque-${System.currentTimeMillis()}.csv"
                                )
                            },
                            onAiUpscale = { frameIdx ->
                                aiUpscaleFrame(surface, frameIdx)
                            },
                            playToggle = playTickExt.value
                        )
                        // V9: bottom-edge breathing room (+ test hook).
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .testTag("timeline-endpad")
                        )
                        }
                    }
                }
                HorizontalDivider(color = Color(0xFF2E2E38), thickness = 1.dp)

                // Slim Studio Status Bar (slice 7: single source — WebDAV
                // button + the one status message, never dims/name).
                // V15: the status read lives in the footer scope, so a
                // status write no longer recomposes the whole screen.
                StatusFooter(
                    status = status,
                    onWebdav = { showWebdav.value = true }
                )
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
                // V+3 §7: keyboard shortcut table (? key or Help menu).
                if (showShortcuts.value) {
                    ShortcutHelpDialog(onDismiss = { showShortcuts.value = false })
                }
                // T1 §5 : contour progressif 0-32px.
                if (showFeather.value) {
                    activeSurface()?.let { surface ->
                        FeatherDialog(
                            surface = surface,
                            onDismiss = { showFeather.value = false }
                        )
                    }
                }
                // V+1: one-shot eraser migration note (dialog, persisted
                // in BrushPrefs so it shows once per install).
                if (showEraserNote.value) {
                    AlertDialog(
                        onDismissRequest = {
                            showEraserNote.value = false
                            brushPrefs.markEraserAlphaNoteShown()
                        },
                        title = {
                            Text(
                                stringResource(R.string.eraser_alpha_title),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2
                            )
                        },
                        text = {
                            Text(
                                stringResource(R.string.eraser_alpha_note),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.testTag("eraser-alpha-note")
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showEraserNote.value = false
                                    brushPrefs.markEraserAlphaNoteShown()
                                },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("eraser-alpha-ok")
                            ) {
                                Text(stringResource(R.string.ok))
                            }
                        }
                    )
                }
                // V+1: text layer panel (anchor tap = new layer, double-tap
                // = re-edit from the fiche; pixels replaced on commit).
                if (showTextPanel.value) {
                    val surf = activeSurface()
                    if (surf != null) {
                        key(fontsTick.value) {
                        TextPanel(
                            initial = textInitial.value,
                            inkHex = inkHex,
                            fonts = FontRegistry.families(),
                            onImportFont = { openFont.launch(arrayOf("font/*")) },
                            onTransform = { spec ->
                                showTextPanel.value = false
                                val layer = textEditLayer.value
                                if (layer >= 0) {
                                    textXformLayer.value = layer
                                    textXformSpec.value = spec
                                    showTextXform.value = true
                                } else {
                                    val done: (Boolean) -> Unit = { ok ->
                                        textCommitDone(surf, ok, spec)
                                    }
                                    surf.commitText(spec, done)
                                }
                            },
                            onCommit = { spec ->
                                showTextPanel.value = false
                                val layer = textEditLayer.value
                                val done: (Boolean) -> Unit = { ok ->
                                    textCommitDone(surf, ok, spec)
                                }
                                if (layer >= 0) surf.reeditText(layer, spec, done)
                                else surf.commitText(spec, done)
                            },
                            onDismiss = { showTextPanel.value = false }
                        )
                        }
                    } else {
                        showTextPanel.value = false
                    }
                }
                if (showExport.value) {
                    ExportDialog(
                        onDismiss = { showExport.value = false },
                        onExport = { fmt, _ ->
                            exportFormat.value = fmt
                            showExport.value = false
                            createDoc.launch(
                                "calque-${System.currentTimeMillis()}"
                            )
                        },
                        resultLine = status.value,
                        includeText = includeText.value,
                        onIncludeText = { includeText.value = it }
                    )
                }
                // ---- T5 dialogs (spec docs/t5-exchange.md) ----
                if (showT5Fill.value) {
                    activeSurface()?.let { s ->
                        FillPanel(
                            surface = s,
                            onDismiss = { showT5Fill.value = false },
                            onStatus = { status.value = it },
                            onChanged = {
                                val aid = activeDocId.value
                                if (aid != null) docRegistry.markDirty(aid, true)
                                refreshKey.value++
                            }
                        )
                    } ?: run { showT5Fill.value = false }
                }
                if (showB1Texture.value) {
                    activeSurface()?.let { s ->
                        TexturePanel(
                            surface = s,
                            onDismiss = { showB1Texture.value = false },
                            onStatus = { status.value = it },
                            onChanged = {
                                val aid = activeDocId.value
                                if (aid != null) docRegistry.markDirty(aid, true)
                                refreshKey.value++
                            }
                        )
                    } ?: run { showB1Texture.value = false }
                }
                if (showT5Import.value) {
                    activeSurface()?.let { s ->
                        ImportPanel(
                            surface = s,
                            filesDir = ctx.filesDir,
                            cacheDir = ctx.cacheDir,
                            onPickFile = { mime, cb ->
                                exchangePick.value = cb
                                openExchange.launch(arrayOf(mime))
                            },
                            onDismiss = { showT5Import.value = false },
                            onStatus = { status.value = it },
                            onChanged = {
                                val aid = activeDocId.value
                                if (aid != null) docRegistry.markDirty(aid, true)
                                refreshKey.value++
                            }
                        )
                    } ?: run { showT5Import.value = false }
                }
                if (showT5Match.value) {
                    activeSurface()?.let { s ->
                        ColorMatchDialog(
                            surface = s,
                            onDismiss = { showT5Match.value = false },
                            onStatus = { status.value = it },
                            onChanged = {
                                val aid = activeDocId.value
                                if (aid != null) docRegistry.markDirty(aid, true)
                                refreshKey.value++
                            }
                        )
                    } ?: run { showT5Match.value = false }
                }
                if (showT5Pages.value) {
                    activeSurface()?.let { s ->
                        T5PagesPanel(
                            surface = s,
                            docKey = activeDocId.value ?: "solo",
                            cacheDir = ctx.cacheDir,
                            dpi = dpi,
                            onDismiss = { showT5Pages.value = false },
                            onStatus = { status.value = it },
                            onChanged = { refreshKey.value++ },
                            onSharePdf = { f ->
                                pendingShare.value = f
                                createT5.launch("fanzine-${System.currentTimeMillis()}.pdf")
                            }
                        )
                    } ?: run { showT5Pages.value = false }
                }
                if (showT5Export.value) {
                    activeSurface()?.let { s ->
                        T5ExportDialog(
                            surface = s,
                            docKey = activeDocId.value ?: "solo",
                            cacheDir = ctx.cacheDir,
                            dpi = dpi,
                            onDismiss = { showT5Export.value = false },
                            onExportFile = { _, f, name ->
                                pendingShare.value = f
                                createT5.launch(name)
                            },
                            onStatus = { status.value = it }
                        )
                    } ?: run { showT5Export.value = false }
                }
                if (showT5Macro.value) {
                    activeSurface()?.let { s ->
                        MacroPanel(
                            surface = s,
                            cacheDir = ctx.cacheDir,
                            onDismiss = { showT5Macro.value = false },
                            onStatus = { status.value = it }
                        )
                    } ?: run { showT5Macro.value = false }
                }
                if (showT5Align.value) {
                    activeSurface()?.let { s ->
                        AlignPanel(
                            surface = s,
                            cacheDir = ctx.cacheDir,
                            onDismiss = { showT5Align.value = false },
                            onStatus = { status.value = it },
                            onChanged = {
                                val aid = activeDocId.value
                                if (aid != null) docRegistry.markDirty(aid, true)
                                refreshKey.value++
                            }
                        )
                    } ?: run { showT5Align.value = false }
                }
                if (showT5History.value) {
                    HistoryPanel(
                        onDismiss = { showT5History.value = false },
                        onJump = { steps ->
                            val s = activeSurface()
                            if (s != null) {
                                repeat(steps) { s.undo() }
                                refreshKey.value++
                            }
                        }
                    )
                }
                if (showT5Info.value) {
                    activeSurface()?.let { s ->
                        InfoPanel(
                            surface = s,
                            onDismiss = { showT5Info.value = false }
                        )
                    } ?: run { showT5Info.value = false }
                }
            }

            // ---- W1 (issue #28) dialogs ----
            if (showW1Canvas.value) {
                activeSurface()?.let { s ->
                    CanvasSizeDialog(
                        surface = s,
                        onClose = { showW1Canvas.value = false },
                        onApplied = { refreshKey.value++ }
                    )
                } ?: run { showW1Canvas.value = false }
            }
            if (w1SelOp.value.isNotEmpty()) {
                val op = w1SelOp.value
                SelectionRadiusDialog(
                    op = op,
                    onClose = { w1SelOp.value = "" },
                    onApply = { radius ->
                        activeSurface()?.let { s ->
                            if (s.refineSelection(op, radius, ctx.cacheDir)) {
                                refreshKey.value++
                            }
                        }
                    }
                )
            }
            if (showW1Ref.value) {
                RefPhotoPanel(
                    onClose = { showW1Ref.value = false },
                    onImport = { openRefPhoto.launch(arrayOf("image/*")) }
                )
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
            // Issue #32 §2: crash-recovery prompt. Modal on purpose — a
            // stray scrim tap must not throw away the only copy of unsaved
            // work; the two buttons are the only exits.
            if (showRecovery.value && recoveryPending.isNotEmpty()) {
                val first = recoveryPending.first()
                val whenText = java.text.SimpleDateFormat(
                    "HH:mm", java.util.Locale.getDefault()
                ).format(java.util.Date(first.savedAtMs))
                AlertDialog(
                    onDismissRequest = { },
                    title = {
                        Text(
                            stringResource(R.string.recovery_title),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            modifier = Modifier.testTag("crash-recovery-dialog")
                        )
                    },
                    text = {
                        Column {
                            Text(
                                ctx.getString(
                                    R.string.recovery_body, first.name, whenText
                                ),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (recoveryPending.size > 1) {
                                Text(
                                    ctx.getString(
                                        R.string.recovery_more,
                                        recoveryPending.size - 1
                                    ),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                var restored = 0
                                for (snap in recoveryPending.toList()) {
                                    if (restoreSnapshot(snap)) restored++
                                }
                                recoveryPending.clear()
                                showRecovery.value = false
                                if (docs.isEmpty()) {
                                    openDocTab(
                                        defaultDocName(), 2048, 1536, dpi, -1
                                    )
                                }
                                status.value = if (restored > 0) {
                                    ctx.getString(
                                        R.string.recovery_restored_count, restored
                                    )
                                } else {
                                    ctx.getString(R.string.recovery_failed)
                                }
                                refreshKey.value++
                                poke()
                            },
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("crash-recovery-restore")
                        ) {
                            Text(stringResource(R.string.recovery_restore))
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                CrashRecovery.discardPending(ctx.filesDir)
                                recoveryPending.clear()
                                showRecovery.value = false
                                if (docs.isEmpty()) {
                                    openDocTab(
                                        defaultDocName(), 2048, 1536, dpi, -1
                                    )
                                }
                                status.value =
                                    ctx.getString(R.string.recovery_discarded)
                                refreshKey.value++
                                poke()
                            },
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("crash-recovery-discard")
                        ) {
                            Text(stringResource(R.string.recovery_discard))
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
            } // studio column (scrollable when short)
        } // outer constraints (V16 short-viewport mode)
    }

/**
 * V15: fullscreen top overlay — owns the status read so the screen root
 * never recomposes on a status write while fullscreen.
 */
@Composable
private fun FullscreenTopBar(
    status: MutableState<String>,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
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
            onClick = onExit,
            modifier = Modifier
                .height(48.dp)
                .testTag("fullscreen-exit")
        ) {
            Text(
                "✕ " + stringResource(R.string.cmdbar_exit_fullscreen),
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFECECF1),
                maxLines = 1
            )
        }
    }
}

/**
 * V15: slim studio status bar — the single status read in the app
 * (WebDAV button + the one message, tap to clear). Errors in red,
 * everything else green; never name/dims.
 */
@Composable
private fun StatusFooter(
    status: MutableState<String>,
    onWebdav: () -> Unit,
    modifier: Modifier = Modifier
) {
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
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(Color(0xFF1E1E24))
            .padding(horizontal = 8.dp)
            .testTag("status-bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextButton(
            onClick = onWebdav,
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

    private fun queryDisplayName(ctx: Context, uri: Uri): String? = try {        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
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
