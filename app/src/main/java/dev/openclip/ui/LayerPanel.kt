// OpenClip slice 3 — pro layer panel: LazyColumn two-rank rows, live
// solo-render thumbnails (app-side only, no new JNI), searchable blend
// dialog, clip/mask/fx badges, drag-reorder handle, compact mode, search.
// The engine stays the truth (M5 symbols); tagColor is app-side only.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.openclip.R
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.json.JSONObject

internal data class LayerInfo(
    val index: Int,
    val name: String,
    val visible: Boolean,
    val opacity: Float,
    val blend: String,
    val isGroup: Boolean,
    val hasMask: Boolean,
    val clipped: Boolean,
    val hasAdjust: Boolean,
    val hasText: Boolean = false,
    val hasBubble: Boolean = false,
    val animKeys: Int = 0,
    val tagColor: Int? = null,
    val children: List<LayerInfo> = emptyList()
)

// Blend list in a single place (spec §3: engine additions = 0 UI change).
internal val BLEND_NAMES = listOf(
    "svg:normal", "svg:multiply", "svg:screen", "svg:overlay",
    "svg:plus", "svg:soft-light",
    // V+2 additions (engine blend.h, ARCHITECTURE.md "V+2 additions").
    "svg:hard-light", "svg:difference", "svg:color", "svg:luminosity"
)

internal fun blendDisplay(svg: String): String = svg.removePrefix("svg:")
internal fun blendSvg(display: String): String = "svg:$display"

// M8 — adjustment descriptor types (engine contract: "gradientMap"
// carries stops; amount is ignored for it; V+2 "curves" carries the
// canonical adjust-curve spec edited in the Comic tab CurvesPanel).
private val ADJUST_TYPES = listOf(
    "brightness" to R.string.adjustment_brightness,
    "contrast" to R.string.adjustment_contrast,
    "hue" to R.string.adjustment_hue,
    "saturation" to R.string.adjustment_saturation,
    "gradientMap" to R.string.adjustment_gradient,
    "curves" to R.string.adjustment_curves
)

// Tag pastilles (maison, app-side only, §4).
private val TAG_COLORS = listOf(
    0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(),
    0xFF43A047.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt()
)

/** Test-only hooks (emutest asserts through these, never pixels alone). */
object LayerPanelTestHooks {
    /** Set by the panel: single engine moveLayer call used by DnD drop. */
    var moveOnce: ((from: Int, to: Int) -> Unit)? = null

    /** Live thumbnail cache snapshot (layerIndex -> 48px bitmap). */
    var thumbSnapshot: () -> Map<Int, Bitmap?> = { emptyMap() }

    /** Compact-mode threshold (spec §6: 20). */
    var compactThreshold = 20

    /** Debounce before solo-render (spec §1: 400 ms). */
    var thumbDebounceMs = 400L

    /** Last worker pass description (diagnostics when the cache is empty). */
    var lastInfo: String = "never"
}

private data class ThumbKey(
    val index: Int,
    val gen: Int,
    val opacityBits: Int,
    val blend: String,
    val visible: Boolean
)

private fun parseLayer(o: JSONObject, index: Int): LayerInfo {
    val kids = mutableListOf<LayerInfo>()
    val arr = o.optJSONArray("children")
    if (arr != null) {
        for (k in 0 until arr.length()) {
            val c = arr.optJSONObject(k) ?: continue
            kids.add(
                LayerInfo(
                    index = -1,
                    name = c.optString("name", "Layer"),
                    visible = c.optBoolean("visible", true),
                    opacity = c.optDouble("opacity", 1.0).toFloat(),
                    blend = c.optString("blend", "svg:normal"),
                    isGroup = false,
                    hasMask = c.optBoolean("mask", false),
                    clipped = c.optBoolean("clipped", false),
                    hasAdjust = c.optJSONObject("adjust") != null,
                hasText = c.optJSONObject("text") != null,
                hasBubble = c.optJSONObject("bubble") != null,
                animKeys = 0
                )
            )
        }
    }
    val s = o.optJSONObject("self") ?: o
    val adj = s.optJSONObject("adjust") ?: o.optJSONObject("adjust")
    return LayerInfo(
        index = index,
        name = s.optString("name", "Layer"),
        visible = s.optBoolean("visible", true),
        opacity = s.optDouble("opacity", 1.0).toFloat(),
        blend = s.optString("blend", "svg:normal"),
        isGroup = s.optBoolean("group", false),
        hasMask = s.optBoolean("mask", false),
        clipped = s.optBoolean("clipped", false),
        hasAdjust = adj != null,
        hasText = s.optJSONObject("text") != null ||
            o.optJSONObject("text") != null,
        hasBubble = s.optJSONObject("bubble") != null ||
            o.optJSONObject("bubble") != null,
        animKeys = s.optInt("anim", 0),
        children = kids
    )
}

@Composable
fun LayerPanel(
    surface: EngineSurface,
    refreshKey: Int,
    modifier: Modifier = Modifier
) {
    var layers by remember { mutableStateOf(listOf<LayerInfo>()) }
    var active by remember { mutableStateOf(0) }
    var maskTarget by remember { mutableStateOf(-1) }
    var renameIndex by remember { mutableStateOf<Int?>(null) }
    var renameText by remember { mutableStateOf("") }
    // M8 — selected-layer adjustment descriptor mirror.
    var adjustType by remember { mutableStateOf<String?>(null) }
    var adjustAmount by remember { mutableStateOf(0f) }
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }
    // Spec §4: tagColor is app-side only (workspaces persist it later).
    val tagColors = remember { mutableStateMapOf<Int, Int>() }
    var search by remember { mutableStateOf("") }
    var strokeGen by remember { mutableStateOf(0) }
    var menuIndex by remember { mutableStateOf<Int?>(null) }

    // Thumbnail LRU (≤ 60 entries, ~9 Ko each).
    val thumbs = remember {
        object : LinkedHashMap<ThumbKey, Bitmap>(64, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<ThumbKey, Bitmap>
            ): Boolean = size > 60
        }
    }
    var thumbTick by remember { mutableStateOf(0) }

    fun refresh() {
        try {
            val root = JSONObject(surface.layerState())
            active = root.optInt("active", 0)
            maskTarget = root.optInt("maskTarget", -1)
            val arr = root.optJSONArray("layers")
            val out = mutableListOf<LayerInfo>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val info = parseLayer(arr.getJSONObject(i), i)
                    out.add(info.copy(tagColor = tagColors[info.index]))
                }
            }
            layers = out
            strokeGen++
            try {
                if (arr != null && active >= 0 && active < arr.length()) {
                    val entry = arr.getJSONObject(active)
                    val s = entry.optJSONObject("self") ?: entry
                    val adj = s.optJSONObject("adjust")
                        ?: entry.optJSONObject("adjust")
                    if (adj == null) {
                        adjustType = null
                        adjustAmount = 0f
                    } else {
                        adjustType = adj.optString("type", "").ifEmpty { null }
                        adjustAmount = adj.optDouble("amount", 0.0)
                            .toFloat().coerceIn(-1f, 1f)
                    }
                } else {
                    adjustType = null
                    adjustAmount = 0f
                }
            } catch (_: Exception) {
                adjustType = null
                adjustAmount = 0f
            }
        } catch (_: Exception) {
            layers = emptyList()
        }
    }

    fun pushAdjust(type: String, amount: Float) {
        val idx = active
        surface.layerOp {
            setLayerAdjustment(idx, """{"type":"$type","amount":$amount}""")
        }
        refresh()
    }

    fun doMove(from: Int, to: Int) {
        if (from == to) return
        if (from < 0 || to < 0 || from >= layers.size || to >= layers.size) return
        surface.layerOp { moveLayer(from, to) }
        refresh()
    }

    LaunchedEffect(refreshKey) { refresh() }
    LaunchedEffect(Unit) {
        LayerPanelTestHooks.moveOnce = { f, t -> doMove(f, t) }
        LayerPanelTestHooks.thumbSnapshot = {
            thumbs.entries.associate { it.key.index to it.value }
        }
    }

    val shown = remember(layers, search) {
        if (search.isBlank()) layers
        else layers.filter { it.name.contains(search, ignoreCase = true) }
    }
    val compact = layers.size > LayerPanelTestHooks.compactThreshold
    val listState = remember { androidx.compose.foundation.lazy.LazyListState() }

    // Solo-render worker: debounced, never while drawing, active-first.
    val sig = layers.map { Triple(it.opacity, it.blend, it.visible) } to strokeGen
    LaunchedEffect(sig) {
        LayerPanelTestHooks.lastInfo = "waiting layers=${layers.size} gen=$strokeGen"
        delay(LayerPanelTestHooks.thumbDebounceMs)
        if (surface.isDrawingNow()) {
            LayerPanelTestHooks.lastInfo = "skipped-drawing gen=$strokeGen"
            return@LaunchedEffect
        }
        val gen = strokeGen
        val targets = layers.filter { it.visible }
            .sortedBy { if (it.index == active) 0 else 1 }
            .take(8)
        LayerPanelTestHooks.lastInfo = "targets=${targets.map { it.index }} gen=$gen"
        for (info in targets) {
            if (gen != strokeGen || surface.isDrawingNow()) break
            val key = ThumbKey(
                info.index, gen,
                info.opacity.toBits(), info.blend, info.visible
            )
            if (thumbs.containsKey(key)) continue
            val bmp = try {
                surface.soloRenderLayer(info.index)
            } catch (e: Exception) {
                LayerPanelTestHooks.lastInfo = "error ${e.message}"
                null
            }
            if (gen != strokeGen) {
                bmp?.recycle()
                break
            }
            if (bmp != null) {
                thumbs[key] = bmp
                thumbTick++
            }
        }
        LayerPanelTestHooks.lastInfo =
            "done cache=${thumbs.size} gen=$gen"
    }

    fun thumbFor(info: LayerInfo): Bitmap? {
        val e = thumbs.entries.firstOrNull {
            it.key.index == info.index && it.key.gen == strokeGen
        }
        return e?.value
    }

    Column(modifier = modifier) {
        Text(
            stringResource(R.string.layers),
            style = MaterialTheme.typography.titleSmall
        )
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text(stringResource(R.string.layer_search)) },
            singleLine = true,
            // V17: the M3 placeholder/label is forced to a measured
            // ≥ 4.5:1 tone on the dock background (never theme-default).
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedLabelColor = Color(0xFFA0A0B0),
                focusedLabelColor = Color(0xFFA0A0B0),
                unfocusedPlaceholderColor = Color(0xFFA0A0B0),
                focusedPlaceholderColor = Color(0xFFA0A0B0)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("layer-search")
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "${layers.size}/50",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.testTag("layer-count")
            )
            if (compact) {
                Text(
                    stringResource(
                        R.string.layer_compact_auto, layers.size
                    ),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .testTag("layer-list")
        ) {
            itemsIndexed(shown, key = { _, l -> l.index }) { _, info ->
                LayerRow(
                    info = info,
                    selected = info.index == active,
                    maskEditing = info.index == maskTarget,
                    expanded = expanded[info.index] ?: true,
                    showRank2 = !compact || info.index == active,
                    thumb = thumbFor(info),
                    menuOpen = menuIndex == info.index,
                    onToggleExpand = {
                        expanded[info.index] = !(expanded[info.index] ?: true)
                    },
                    onSelect = {
                        surface.layerOp { setActiveLayer(info.index) }
                        refresh()
                    },
                    onRenameAsk = {
                        renameIndex = info.index
                        renameText = info.name
                        menuIndex = null
                    },
                    onVisibility = {
                        surface.layerOp {
                            setLayerVisibility(info.index, !info.visible)
                        }
                        refresh()
                    },
                    onOpacityRelease = { v ->
                        surface.layerOp { setLayerOpacity(info.index, v) }
                        refresh()
                    },
                    onBlend = { b ->
                        surface.layerOp { setLayerBlend(info.index, b) }
                        refresh()
                    },
                    onMove = { f, t -> doMove(f, t); menuIndex = null },
                    onMenu = { menuIndex = if (menuIndex == info.index) null else info.index },
                    onDismissMenu = { menuIndex = null },
                    onAddMask = {
                        surface.layerOp { addLayerMask(info.index) }
                        menuIndex = null
                        refresh()
                    },
                    onMaskTarget = {
                        surface.layerOp {
                            maskTarget(info.index, info.index != maskTarget)
                        }
                        menuIndex = null
                        refresh()
                    },
                    onClipped = {
                        surface.layerOp {
                            setLayerClipped(info.index, !info.clipped)
                        }
                        menuIndex = null
                        refresh()
                    },
                    onAddChild = {
                        surface.layerOp { appendChild(info.index, "Layer") }
                        menuIndex = null
                        refresh()
                    },
                    onTag = { c ->
                        if (c == null) tagColors.remove(info.index)
                        else tagColors[info.index] = c
                        layers = layers.map {
                            if (it.index == info.index) it.copy(tagColor = c) else it
                        }
                        menuIndex = null
                    }
                )
            }
        }
        val compactPad = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(
                onClick = {
                    surface.layerOp { addLayer() }
                    refresh()
                },
                contentPadding = compactPad,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("layer-add")
            ) { Text("+", style = MaterialTheme.typography.labelSmall) }
            OutlinedButton(
                onClick = {
                    surface.layerOp { addGroup("Group") }
                    refresh()
                },
                contentPadding = compactPad,
                modifier = Modifier.height(48.dp)
            ) { Text(stringResource(R.string.layer_add_group), style = MaterialTheme.typography.labelSmall, maxLines = 1) }
            OutlinedButton(
                onClick = {
                    surface.layerOp { addVectorLayer("Vector") }
                    refresh()
                },
                contentPadding = compactPad,
                modifier = Modifier.height(48.dp)
            ) { Text(stringResource(R.string.layer_add_vector), style = MaterialTheme.typography.labelSmall, maxLines = 1) }
        }
        // M8 — adjustment cluster for the selected layer (preserved).
        // V7: single-line labels + horizontal scroll (this row holds 7
        // buttons and always overflows a phone dock).
        val adjustScroll = rememberScrollState()
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(adjustScroll)
                .scrollFade("adjust-row", adjustScroll)
                .testTag("adjust-row")
        ) {
            ADJUST_TYPES.forEach { (type, res) ->
                TextButton(onClick = { pushAdjust(type, adjustAmount) }) {
                    Text(
                        (if (adjustType == type) "[" else "") +
                            stringResource(res) +
                            (if (adjustType == type) "]" else ""),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
            TextButton(onClick = {
                val idx = active
                surface.layerOp { setLayerAdjustment(idx, null) }
                refresh()
            }) {
                Text(
                    stringResource(R.string.adjustment_clear),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
        // Curves are edited in the Comic-tab CurvesPanel (canonical
        // spec), not via the amount slider — same guard as gradientMap.
        if (adjustType != null && adjustType != "gradientMap" &&
            adjustType != "curves"
        ) {
            val t = adjustType
            if (t != null) {
                var amt by remember(active, t, adjustAmount) {
                    mutableStateOf(adjustAmount.coerceIn(-1f, 1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            R.string.adjustment_amount,
                            (amt * 100).toInt()
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Slider(
                        value = amt,
                        onValueChange = { amt = it },
                        onValueChangeFinished = { pushAdjust(t, amt) },
                        valueRange = -1f..1f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    val ri = renameIndex
    if (ri != null) {
        AlertDialog(
            onDismissRequest = { renameIndex = null },
            title = { Text(stringResource(R.string.layer_rename)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.layer_rename_hint)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    surface.layerOp { setLayerName(ri, renameText) }
                    renameIndex = null
                    refresh()
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { renameIndex = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LayerRow(
    info: LayerInfo,
    selected: Boolean,
    maskEditing: Boolean,
    expanded: Boolean,
    showRank2: Boolean,
    thumb: Bitmap?,
    menuOpen: Boolean,
    onToggleExpand: () -> Unit,
    onSelect: () -> Unit,
    onRenameAsk: () -> Unit,
    onVisibility: () -> Unit,
    onOpacityRelease: (Float) -> Unit,
    onBlend: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onAddMask: () -> Unit,
    onMaskTarget: () -> Unit,
    onClipped: () -> Unit,
    onAddChild: () -> Unit,
    onTag: (Int?) -> Unit
) {
    var slider by remember(info.index, info.opacity) {
        mutableStateOf(info.opacity.coerceIn(0f, 1f))
    }
    var blendOpen by remember { mutableStateOf(false) }
    var dragOff by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val rowHpx = with(density) { 112.dp.toPx() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // V6: selection = tint alone (the thumb keeps its own ring);
            // the old 3dp outer border doubled the thumb ring.
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else Color.Transparent,
                RoundedCornerShape(2.dp)
            )
            .padding(4.dp)
            .testTag("layer-row-${info.index}")
    ) {
        // Rank 1: chevron + thumb + name + badges + visibility + handle.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            if (info.isGroup) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clickable { onToggleExpand() }
                        .testTag("layer-expand-${info.index}")
                ) {
                    Text(if (expanded) "▾" else "▸")
                }
            }
            LayerThumb(
                info = info,
                thumb = thumb,
                selected = selected,
                onTap = onSelect,
                onDoubleTap = onRenameAsk
            )
            Text(
                text = info.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .testTag("layer-name-${info.index}")
                    .combinedClickable(
                        onClick = onSelect,
                        onDoubleClick = onRenameAsk,
                        onLongClick = onMenu
                    )
            )
            // Badges (visual 24dp, reached via the long-press menu).
            if (info.clipped) {
                val clipDesc = stringResource(R.string.layer_clipped_on)
                Text(
                    "↓",
                    color = Color(0xFF6FA3FF),
                    modifier = Modifier
                        .testTag("layer-badge-clip-${info.index}")
                        .semantics { contentDescription = clipDesc }
                        .clickable { onMenu() }
                )
            }
            if (info.hasMask) {
                val maskDesc = stringResource(
                    if (maskEditing) R.string.layer_mask_editing
                    else R.string.layer_add_mask
                )
                Text(
                    if (maskEditing) "◑" else "◐",
                    color = if (maskEditing) MaterialTheme.colorScheme.primary
                    else Color(0xFFB8B8C8),
                    modifier = Modifier
                        .testTag("layer-badge-mask-${info.index}")
                        .semantics { contentDescription = maskDesc }
                        .clickable { onMaskTarget() }
                )
            }
            if (info.hasText) {
                val txDesc = stringResource(R.string.text_layer_badge)
                Text(
                    "Tx",
                    color = Color(0xFF7FD4A8),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .testTag("layer-badge-tx-${info.index}")
                        .semantics { contentDescription = txDesc }
                )
            }
            if (info.hasBubble) {
                val bDesc = stringResource(R.string.bubble_layer_badge)
                Text(
                    "○",
                    color = Color(0xFFE08AB0),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .testTag("layer-badge-bubble-${info.index}")
                        .semantics { contentDescription = bDesc }
                )
            }
            if (info.animKeys > 0) {
                Text(
                    "◆",
                    color = Color(0xFFFFC845),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .testTag("layer-badge-anim-${info.index}")
                )
            }
            if (info.hasAdjust) {
                val fxDesc = stringResource(R.string.layer_fx_present)
                Text(
                    "fx",
                    color = Color(0xFFB388FF),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .testTag("layer-badge-fx-${info.index}")
                        .semantics { contentDescription = fxDesc }
                        .clickable { onMenu() }
                )
            }
            val visDesc = stringResource(
                if (info.visible) R.string.layer_visible else R.string.layer_hidden
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clickable { onVisibility() }
                    .testTag("layer-vis-${info.index}")
                    .semantics { contentDescription = visDesc }
            ) {
                Text(if (info.visible) "◉" else "○")
            }
            // Drag handle: the only drag zone (spec §2).
            val reorderDesc = stringResource(R.string.layer_reorder_hint)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("layer-drag-${info.index}")
                    .semantics { contentDescription = reorderDesc }
                    .draggable(
                        state = rememberDraggableState { d -> dragOff += d },
                        orientation = Orientation.Vertical,
                        onDragStopped = {
                            val steps = (dragOff / rowHpx).roundToInt()
                            dragOff = 0f
                            if (steps != 0) onMove(info.index, info.index + steps)
                        }
                    )
            ) {
                Text("≡")
            }
        }
        // Rank 2: opacity slider + % + blend chip (folded in compact mode).
        if (showRank2) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("layer-opacity-${info.index}"),
                    contentAlignment = Alignment.Center
                ) {
                    Slider(
                        value = slider,
                        onValueChange = { slider = it },
                        onValueChangeFinished = { onOpacityRelease(slider) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // V6: short "%d %%" form (the slider sits next door —
                // the word adds nothing) at a width that never clips.
                Text(
                    stringResource(
                        R.string.layer_opacity_layer, (slider * 100).toInt()
                    ),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    maxLines = 1,
                    softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier
                        .width(60.dp)
                        .testTag("layer-dosage-${info.index}")
                )
                val blendDesc = stringResource(R.string.layer_blend) + " " +
                    blendDisplay(info.blend)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(48.dp)
                        .clickable { blendOpen = true }
                        .testTag("layer-blend-${info.index}")
                        .semantics { contentDescription = blendDesc }
                        .padding(horizontal = 4.dp)
                ) {
                    Text(
                        blendDisplay(info.blend) + " ▾",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                }
            }
        }
        if (info.isGroup && expanded && info.children.isNotEmpty()) {
            Text(
                info.children.joinToString { it.name },
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                modifier = Modifier.padding(start = 64.dp)
            )
        }
        // Long-press menu: all secondary actions (a11y + tests).
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = onDismissMenu,
            modifier = Modifier.testTag("layer-menu-${info.index}")
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.layer_rename)) },
                onClick = onRenameAsk,
                modifier = Modifier.testTag("layer-menu-rename-${info.index}")
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.layer_move_up)) },
                onClick = { onMove(info.index, info.index + 1) },
                modifier = Modifier.testTag("layer-up-${info.index}")
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.layer_move_down)) },
                onClick = { onMove(info.index, info.index - 1) },
                modifier = Modifier.testTag("layer-down-${info.index}")
            )
            if (!info.isGroup) {
                if (!info.hasMask) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.layer_add_mask)) },
                        onClick = onAddMask
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.layer_mask_target)) },
                        onClick = onMaskTarget
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.layer_clipped)) },
                    onClick = onClipped
                )
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.layer_children)) },
                    onClick = onAddChild
                )
            }
            DropdownMenuItem(
                text = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.layer_tag_color))
                        TAG_COLORS.forEach { c ->
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(c))
                                    .clickable { onTag(c) }
                                    .testTag("layer-tag-${info.index}-$c")
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .border(1.dp, Color.Gray, RoundedCornerShape(12.dp))
                                .clickable { onTag(null) }
                        )
                    }
                },
                onClick = {}
            )
        }
        if (blendOpen) {
            BlendDialog(
                current = info.blend,
                onPick = {
                    blendOpen = false
                    onBlend(it)
                },
                onDismiss = { blendOpen = false }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LayerThumb(
    info: LayerInfo,
    thumb: Bitmap?,
    selected: Boolean,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit
) {
    val thumbDesc = info.name
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1C1C22))
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary
                else Color(0xFF383844),
                RoundedCornerShape(6.dp)
            )
            .combinedClickable(onClick = onTap, onDoubleClick = onDoubleTap)
            .testTag("layer-thumb-${info.index}")
            .semantics { contentDescription = thumbDesc }
    ) {
        if (thumb != null && !thumb.isRecycled) {
            Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(48.dp)
            )
        } else {
            CheckerPlaceholder(
                empty = !info.visible,
                modifier = Modifier.size(48.dp)
            )
        }
        if (info.tagColor != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(8.dp)
                    .background(Color(info.tagColor))
            )
        }
    }
}

/** Maison checker placeholder (transparency-readable, no tierce). */
@Composable
private fun CheckerPlaceholder(empty: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val n = 6
        val s = size.minDimension / n
        for (y in 0 until n) {
            for (x in 0 until n) {
                drawRect(
                    color = if ((x + y) % 2 == 0) Color(0xFF3A3A44)
                    else Color(0xFF23232A),
                    topLeft = androidx.compose.ui.geometry.Offset(x * s, y * s),
                    size = androidx.compose.ui.geometry.Size(s + 1f, s + 1f)
                )
            }
        }
    }
}

@Composable
private fun BlendDialog(
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val items = remember(query) {
        val q = query.trim().lowercase()
        BLEND_NAMES.filter {
            q.isEmpty() || blendDisplay(it).lowercase().contains(q)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("blend-dialog"),
        title = { Text(stringResource(R.string.layer_blend)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.layer_blend_search)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("blend-search")
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                ) {
                    items(items.size) { k ->
                        val b = items[k]
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clickable { onPick(b) }
                                .testTag("blend-item-" + blendDisplay(b))
                        ) {
                            BlendDot(mode = blendDisplay(b))
                            Text(
                                blendDisplay(b),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            if (b == current) Text("✓")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        }
    )
}

/**
 * Maison 24dp micro-preview: the blend applied (naive per-channel math)
 * to two fixed witness colors. Pure Compose, never engine pixels.
 */
@Composable
private fun BlendDot(mode: String) {
    val mixed = remember(mode) {
        val a = Triple(0x3A, 0x7B, 0xC8)
        val b = Triple(0xE0, 0x8A, 0x3C)
        fun ch(x: Int, y: Int): Int = when (mode) {
            "multiply" -> x * y / 255
            "screen" -> 255 - (255 - x) * (255 - y) / 255
            "overlay" -> if (x < 128) 2 * x * y / 255
            else 255 - 2 * (255 - x) * (255 - y) / 255
            "plus" -> (x + y).coerceAtMost(255)
            "soft-light" -> ((x * y / 255 + x) / 2)
            else -> y
        }
        Color(ch(a.first, b.first), ch(a.second, b.second), ch(a.third, b.third))
    }
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(mixed)
    )
}
