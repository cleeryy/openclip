// OpenClip — compact studio top toolbar: brush/eraser tool, inline size &
// opacity sliders, collapsible stabilization/pressure-curve/symmetry bar,
// and undo/redo/filter/file actions. Slice 5: the eraser is a dedicated
// tool with its own size/opacity memory (BrushPrefs), and the pressure
// curve is an editable graph fitted onto the engine scalar.
package dev.openclip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.openclip.R
import java.util.Locale

data class BrushState(
    val color: String,
    val isBrush: Boolean,
    val radius: Float,
    val opaque: Float,
    val stabilize: Float = 0f,
    val curve: Float = 0f
)

private fun BrushState.toJson(): String = String.format(
    Locale.US,
    """{"radius":%.2f,"hardness":%.2f,"opaque":%.2f,"spacing":0.15,"color":"%s","stabilize":%.2f,"curve":%.2f,"mode":"%s"}""",
    radius,
    if (isBrush) 0.7f else 0.9f,
    opaque,
    color,
    stabilize,
    curve,
    // V+1: real dest-out erase; legacy "eraser" stays engine-side only.
    if (isBrush) "brush" else "alpha-erase"
)

private const val DEFAULT_INK = "#141414"

@Composable
fun ToolPalette(
    surface: EngineSurface,
    prefs: BrushPrefs,
    initialColor: String = DEFAULT_INK,
    onFileClicked: () -> Unit = {},
    onImportClicked: () -> Unit = {},
    navigateMode: Boolean = false,
    onNavigateToggle: () -> Unit = {},
    onStatus: (String) -> Unit = {},
    // V10: hoisted undo/redo availability shared with the command bar.
    undoAvail: UndoAvailability? = null,
    modifier: Modifier = Modifier
) {
    // Slice 5: the tool lives in the hoisted BrushPrefs (persisted);
    // isBrush here is a read-through, never a second source of truth.
    val isBrush = !prefs.isEraser
    var inkColor by remember { mutableStateOf(initialColor) }
    // V10: shared availability when the host provides it, else a local
    // fallback (same behavior as before for standalone previews).
    val avail = undoAvail ?: remember { UndoAvailability() }
    var showFilters by remember { mutableStateOf(false) }
    var showT1 by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var symX by remember { mutableStateOf(false) }
    var symY by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // V+1: armed overlay tools (selection/text) live on the surface
    // (per-doc); toolTick resubscribes the chips after each toggle.
    var toolTick by remember { mutableStateOf(0) }
    @Suppress("UNUSED_EXPRESSION")
    toolTick
    val selArmed = surface.selectionTool != null
    val textArmed = surface.textArmed

    fun pushBrush() {
        prefs.bindTool(inkColor, !isBrush, prefs.curve)
        surface.setBrush(prefs.currentJson())
    }
    // V10: each (doc-bound) ToolPalette instance resets the hoisted
    // availability — the engine undo pile lives per surface, so a fresh
    // surface starts with both actions unavailable.
    LaunchedEffect(Unit) {
        avail.canUndo = false
        avail.canRedo = false
    }
    // V14: drag ticks preview locally (prefs) while the engine push is
    // throttled (~33ms) + forced on release; discrete taps push at once.
    val brushThrottle = remember(surface) {
        BrushPushThrottler(push = { pushBrush() })
    }
    fun pushTick(update: () -> Unit) {
        update()
        brushThrottle.onDragTick()
    }
    fun pushEnd() {
        brushThrottle.onDragEnd()
    }

    fun pushSymmetry() {
        val sx = if (symX) surface.canvasWidth() / 2f else -1f
        val sy = if (symY) surface.canvasHeight() / 2f else -1f
        // V+2: guides survive symmetry toggles (single rulers source
        // shared with GuidesPanel: symmetry fragment + guides merged
        // on both sides).
        val sym = String.format(Locale.US, "\"symX\":%.1f,\"symY\":%.1f", sx, sy)
        surface.symmetryJson = sym
        val rulers = if (surface.guideJson.isEmpty()) {
            "{$sym}"
        } else {
            "{$sym,\"guides\":${surface.guideJson}}"
        }
        surface.setRulers(rulers)
    }

    LaunchedEffect(initialColor) {
        if (inkColor != initialColor) {
            inkColor = initialColor
            pushBrush()
        }
    }

    LaunchedEffect(surface) {
        val prev = surface.onStrokeChanged
        surface.onStrokeChanged = {
            prev?.invoke()
            avail.canUndo = true
            avail.canRedo = false
        }
        pushBrush()
    }

    val compactPad = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
    val shownRadius = prefs.activeRadius()
    val shownOpaque = prefs.activeOpaque()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF202026))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        val toolScroll = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(toolScroll)
                .scrollFade("tool-row", toolScroll)
                .testTag("tool-row"),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = isBrush,
                onClick = { prefs.setTool(false); pushBrush() },
                label = {
                    Text(
                        stringResource(R.string.brush),
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                },
                modifier = Modifier
                    .height(48.dp)
                    .testTag("tool-brush")
            )
            FilterChip(
                selected = !isBrush,
                onClick = { prefs.setTool(true); pushBrush() },
                label = {
                    Text(
                        stringResource(R.string.eraser),
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF4A3520),
                    selectedLabelColor = Color(0xFFE0A050)
                ),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("tool-eraser")
            )
            FilterChip(
                selected = navigateMode,
                onClick = onNavigateToggle,
                label = {
                    Text(
                        stringResource(
                            if (navigateMode) R.string.nav_mode_move
                            else R.string.nav_mode_draw
                        ),
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                },
                modifier = Modifier
                    .height(48.dp)
                    .testTag("tool-nav")
            )
            // V+1: selection tool (rect/ellipse/lasso, app-side mask).
            FilterChip(
                selected = selArmed,
                onClick = {
                    if (surface.selectionTool == null) {
                        surface.textArmed = false
                        surface.paintTool = null
                        surface.selectionTool = SelectionTool.RECT
                    } else {
                        surface.selectionTool = null
                    }
                    toolTick++
                },
                label = {
                    Text(
                        stringResource(R.string.menu_selection),
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                },
                modifier = Modifier
                    .height(48.dp)
                    .testTag("tool-select")
            )
            // V+1: text tool (one-shot anchor, platform layout).
            FilterChip(
                selected = textArmed,
                onClick = {
                    if (!surface.textArmed) {
                        surface.selectionTool = null
                        surface.paintTool = null
                        surface.textArmed = true
                        onStatus(ctx.getString(R.string.text_hint))
                    } else {
                        surface.textArmed = false
                    }
                    toolTick++
                },
                label = {
                    Text(
                        stringResource(R.string.text_tool),
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                },
                modifier = Modifier
                    .height(48.dp)
                    .testTag("tool-text")
            )
            FilterChip(
                selected = showT1 || surface.paintTool != null,
                onClick = {
                    if (surface.paintTool != null) {
                        surface.paintTool = null
                        showT1 = false
                    } else {
                        showT1 = !showT1
                    }
                    toolTick++
                },
                label = {
                    Text(
                        stringResource(R.string.t1_tools),
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                },
                modifier = Modifier
                    .height(48.dp)
                    .testTag("tool-t1-toggle")
            )

            // Compact inline size slider (active tool memory).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp)
            ) {
                Text(
                    "${shownRadius.toInt()}px",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFD0D0DC),
                    modifier = Modifier.width(38.dp)
                )
                Slider(
                    value = shownRadius,
                    onValueChange = {
                        pushTick {
                            if (isBrush) prefs.updateRadius(it)
                            else prefs.updateEraserRadius(it)
                        }
                    },
                    onValueChangeFinished = { pushEnd() },
                    valueRange = 1f..128f,
                    modifier = Modifier
                        .width(110.dp)
                        .height(28.dp)
                )
            }

            // Compact inline opacity slider (active tool memory).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp)
            ) {
                Text(
                    stringResource(R.string.opacity, (shownOpaque * 100).toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFD0D0DC)
                )
                Slider(
                    value = shownOpaque,
                    onValueChange = {
                        pushTick {
                            if (isBrush) prefs.updateOpaque(it)
                            else prefs.updateEraserOpaque(it)
                        }
                    },
                    onValueChangeFinished = { pushEnd() },
                    modifier = Modifier
                        .width(96.dp)
                        .height(28.dp)
                )
            }

            FilterChip(
                selected = showAdvanced,
                onClick = { showAdvanced = !showAdvanced },
                label = {
                    Text(
                        "⚙",
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                },
                modifier = Modifier
                    .height(48.dp)
                    .testTag("advanced-toggle")
            )

            OutlinedButton(
                onClick = {
                    val ok = surface.undo()
                    if (ok) avail.canRedo = true else avail.canUndo = false
                },
                enabled = avail.canUndo,
                contentPadding = compactPad,
                modifier = Modifier.height(48.dp)
            ) {
                Text(
                    stringResource(R.string.undo),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            OutlinedButton(
                onClick = {
                    val ok = surface.redo()
                    if (ok) avail.canUndo = true else avail.canRedo = false
                },
                enabled = avail.canRedo,
                contentPadding = compactPad,
                modifier = Modifier.height(48.dp)
            ) {
                Text(
                    stringResource(R.string.redo),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            Button(
                onClick = { showFilters = true },
                contentPadding = compactPad,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF32323D),
                    contentColor = Color(0xFFECECF1)
                ),
                modifier = Modifier.height(48.dp)
            ) {
                Text(
                    stringResource(R.string.filters),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            Button(
                onClick = onImportClicked,
                contentPadding = compactPad,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF32323D),
                    contentColor = Color(0xFFECECF1)
                ),
                modifier = Modifier.height(48.dp)
            ) {
                Text(
                    stringResource(R.string.import_doc),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            Button(
                onClick = onFileClicked,
                contentPadding = compactPad,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("tool-file")
            ) {
                Text(
                    stringResource(R.string.file_menu),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }

        // T1 : rangee dediee aux fondations dessin (seau/degrade/figure/
        // baguette, spec docs/t1-fill.md). Repliable (toggle ci-dessus,
        // comme la rangee avancee) : fermee par defaut pour ne pas
        // grever le budget chrome (slice7_chromeBudgetAndCollapse) ;
        // la rangee principale garde tool-file visible sans scroll sur
        // tablette (slice7_toolRowNeverClipsWithoutScroll). Armement
        // exclusif (desarme selection/texte et reciproquement).
        if (showT1 || surface.paintTool != null) run {
            val t1Scroll = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(t1Scroll)
                    .scrollFade("tool-t1-row", t1Scroll)
                    .padding(top = 2.dp)
                    .testTag("tool-t1-row"),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(
                    PaintTool.BUCKET to R.string.t1_bucket,
                    PaintTool.GRADIENT to R.string.t1_gradient,
                    PaintTool.SHAPE to R.string.t1_shape,
                    PaintTool.WAND to R.string.t1_wand
                ).forEach { (tool, label) ->
                    FilterChip(
                        selected = surface.paintTool == tool,
                        onClick = {
                            if (surface.paintTool == tool) {
                                surface.paintTool = null
                            } else {
                                surface.selectionTool = null
                                surface.textArmed = false
                                surface.paintTool = tool
                                onStatus(ctx.getString(label))
                            }
                            toolTick++
                        },
                        label = {
                            Text(
                                stringResource(label),
                                maxLines = 1,
                                softWrap = false,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("tool-t1-" + tool.name.lowercase())
                    )
                }
            }
        }

        // V+1: selection mode selector (shown while armed) + the
        // session-only note (no persistence — closing the doc drops it).
        // T1: extended with quick mask, feather radius and mask undo.
        var showFeather by remember { mutableStateOf(false) }
        val quickMask = surface.quickMask
        if (selArmed || quickMask || surface.paintTool == PaintTool.WAND) {
            val selScroll = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(selScroll)
                    .scrollFade("selection-modes", selScroll)
                    .padding(top = 2.dp)
                    .testTag("selection-modes"),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(
                    SelectionTool.RECT to R.string.selection_rect,
                    SelectionTool.ELLIPSE to R.string.selection_ellipse,
                    SelectionTool.LASSO to R.string.selection_lasso
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = surface.selectionTool == mode,
                        onClick = {
                            surface.selectionTool = mode
                            toolTick++
                        },
                        label = {
                            Text(
                                stringResource(label),
                                maxLines = 1,
                                softWrap = false,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("sel-mode-${mode.name.lowercase()}")
                    )
                }
                Text(
                    stringResource(R.string.selection_session_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFB8B8C8),
                    maxLines = 2,
                    modifier = Modifier.padding(start = 4.dp)
                )
                // T1 §5: quick mask toggle + feather radius + mask undo.
                FilterChip(
                    selected = quickMask,
                    onClick = {
                        surface.quickMask = !surface.quickMask
                        if (surface.quickMask) {
                            surface.paintTool = null
                            surface.selectionTool = null
                            onStatus(ctx.getString(R.string.t1_quickmask_hint))
                        }
                        toolTick++
                    },
                    label = {
                        Text(
                            stringResource(R.string.t1_quickmask),
                            maxLines = 1,
                            softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("t1-quickmask-toggle")
                )
                if (quickMask) {
                    FilterChip(
                        selected = surface.maskStrokeAdd,
                        onClick = {
                            surface.maskStrokeAdd = true
                            toolTick++
                        },
                        label = { Text(stringResource(R.string.t1_quickmask_add)) },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("t1-quickmask-add")
                    )
                    FilterChip(
                        selected = !surface.maskStrokeAdd,
                        onClick = {
                            surface.maskStrokeAdd = false
                            toolTick++
                        },
                        label = { Text(stringResource(R.string.t1_quickmask_sub)) },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("t1-quickmask-sub")
                    )
                }
                OutlinedButton(
                    onClick = { showFeather = true },
                    contentPadding = compactPad,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("t1-feather-open")
                ) {
                    Text(
                        stringResource(R.string.t1_contour),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(
                    onClick = {
                        if (!surface.undoSelection()) {
                            onStatus(ctx.getString(R.string.v2_no_selection))
                        }
                        toolTick++
                    },
                    contentPadding = compactPad,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("t1-selection-undo")
                ) {
                    Text(
                        stringResource(R.string.t1_selection_undo),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (showFeather) {
            FeatherDialog(surface = surface, onDismiss = { showFeather = false })
        }

        // T1: panels of the armed paint tool (bucket/gradient/shape/wand).
        @Suppress("UNUSED_EXPRESSION")
        toolTick
        if (surface.paintTool != null) {
            T1PanelHost(
                surface = surface,
                inkColor = inkColor,
                onStatus = onStatus
            )
        }

        if (showAdvanced) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.stabilize, (prefs.stabilize * 100).toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFB8B8C8),
                    maxLines = 1,
                    softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Slider(
                    value = prefs.stabilize,
                    onValueChange = {
                        pushTick { prefs.updateStabilize(it) }
                    },
                    onValueChangeFinished = { pushEnd() },
                    valueRange = 0f..1f,
                    modifier = Modifier
                        .width(110.dp)
                        .height(26.dp)
                )
                FilterChip(
                    selected = symX,
                    onClick = { symX = !symX; pushSymmetry() },
                    label = {
                        Text(
                            "Sym X",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                )
                FilterChip(
                    selected = symY,
                    onClick = { symY = !symY; pushSymmetry() },
                    label = {
                        Text(
                            "Sym Y",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                )
            }
            PressureCurveEditor(
                prefs = prefs,
                onCommit = { pushBrush() },
                onTick = { pushTick {} },
                onEnd = { pushEnd() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            )
        }

        if (showFilters) {
            FilterDialog(surface = surface, onDismiss = { showFilters = false })
        }
    }
}
