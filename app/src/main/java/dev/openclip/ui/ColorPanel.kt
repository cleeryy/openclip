// OpenClip slice 2 — ColorPanel: 4-tab pro color container + persistent
// bottom bar (current swatch + editable hex + 1-tap pipette).
//
// Controlled component: receives inkHex, never owns persistent color state;
// every tab writes through onPick(). Only ToolPalette pushes setBrush to the
// engine. All labels via R.string (EN+FR), touch targets >= 48dp.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import dev.openclip.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun ColorPanel(
    store: ColorStore,
    onStatus: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val inkHex = store.inkHex
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.color_wheel) to "color-tab-wheel",
        stringResource(R.string.color_sliders) to "color-tab-sliders",
        stringResource(R.string.color_sets) to "color-tab-sets",
        stringResource(R.string.color_recent) to "color-tab-recent"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF25252D), RoundedCornerShape(6.dp))
            .padding(8.dp)
            .testTag("color-panel"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            tabs.forEachIndexed { i, (label, tag) ->
                val selected = tab == i
                TextButton(
                    onClick = { tab = i },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(tag)
                        .semantics { contentDescription = label },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = if (selected) Color(0xFF90B8FF) else Color(0xFFC4C4D0)
                    )
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        when (tab) {
            0 -> WheelTab(inkHex = inkHex, onPick = { store.pick(it) })
            1 -> SlidersTab(inkHex = inkHex, onPick = { store.pick(it) }, onStatus = onStatus)
            2 -> SetsTab(store = store)
            else -> RecentsTab(store = store)
        }

        BottomBar(store = store, inkHex = inkHex, onStatus = onStatus)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BottomBar(store: ColorStore, inkHex: String, onStatus: (String) -> Unit) {
    val shown = store.pipetteLive ?: inkHex
    val armedMsg = stringResource(R.string.pipette_armed)
    val holdMsg = stringResource(R.string.pipette_hold)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Current-color witness, 48dp.
        Box(
            modifier = Modifier
                .size(48.dp)
                .testTag("color-current")
                .semantics { contentDescription = shown }
                .clip(RoundedCornerShape(6.dp))
                .background(parseHexColor(shown))
                .border(1.dp, Color(0xFF90B8FF), RoundedCornerShape(6.dp))
        )
        Text(
            shown,
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFECECF1),
            maxLines = 1,
            modifier = Modifier.testTag("color-hex")
        )
        Box(Modifier.weight(1f))
        // 1-tap pipette: tap = arm/disarm, long-press (>= 400 ms framework
        // timeout) = continuous loupe mode (fallback documented in spec §4:
        // same arming path, drag updates live).
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .testTag("pipette-btn")
                .combinedClickable(
                    onClick = {
                        if (store.pipetteArmed) store.disarmPipette()
                        else {
                            store.armPipette(false)
                            onStatus(armedMsg)
                        }
                    },
                    onLongClick = {
                        store.armPipette(true)
                        onStatus(holdMsg)
                    }
                )
                .semantics {
                    contentDescription = "pipette:" + if (store.pipetteArmed) "armed" else "idle"
                }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                stringResource(R.string.pipette),
                style = MaterialTheme.typography.labelLarge,
                color = if (store.pipetteArmed) Color(0xFF5C9DFF) else Color(0xFFC4C4D0),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ---- 3.1 HSV wheel (house Canvas, no assets) ----
@Composable
private fun WheelTab(inkHex: String, onPick: (String) -> Unit) {
    val hsv = remember(inkHex) { hexToHsv(inkHex) }
    var hue by remember(inkHex) { mutableStateOf(hsv[0]) }
    var sat by remember(inkHex) { mutableStateOf(hsv[1]) }
    val value = remember(inkHex) { hsv[2] }

    fun emit(h: Float, s: Float) {
        hue = h
        sat = s
        onPick(hsvToHex(h, s, value))
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Canvas(
            modifier = Modifier
                .size(200.dp)
                .pointerInput(value) {
                    detectTapGestures { off ->
                        val c = size.width / 2f
                        val dx = off.x - c
                        val dy = off.y - c
                        val rr = sqrt(dx * dx + dy * dy)
                        if (rr <= c) {
                            val h = ((Math.toDegrees(atan2(dy, dx).toDouble())).toFloat() + 90f + 360f) % 360f
                            emit(h, (rr / c).coerceIn(0f, 1f))
                        }
                    }
                }
                .pointerInput(value) {
                    detectDragGestures(
                        onDragStart = { off ->
                            val c = size.width / 2f
                            val dx = off.x - c
                            val dy = off.y - c
                            val rr = sqrt(dx * dx + dy * dy)
                            if (rr <= c) {
                                val h = ((Math.toDegrees(atan2(dy, dx).toDouble())).toFloat() + 90f + 360f) % 360f
                                emit(h, (rr / c).coerceIn(0f, 1f))
                            }
                        },
                        onDrag = { change, _ ->
                            val c = size.width / 2f
                            val dx = change.position.x - c
                            val dy = change.position.y - c
                            val rr = sqrt(dx * dx + dy * dy)
                            if (rr <= c) {
                                val h = ((Math.toDegrees(atan2(dy, dx).toDouble())).toFloat() + 90f + 360f) % 360f
                                emit(h, (rr / c).coerceIn(0f, 1f))
                            }
                        }
                    )
                }
                .semantics {
                    contentDescription = "wheel:${hue.roundToInt()}:${(sat * 100).roundToInt()}"
                }
        ) {
            val r = size.minDimension / 2f
            val cx = size.width / 2f
            val cy = size.height / 2f
            val segs = 72
            for (i in 0 until segs) {
                drawArc(
                    color = Color(android.graphics.Color.HSVToColor(floatArrayOf(i * 5f, 1f, 1f))),
                    startAngle = i * 5f - 90f,
                    sweepAngle = 360f / segs + 0.6f,
                    useCenter = true,
                    topLeft = Offset(cx - r, cy - r),
                    size = androidx.compose.ui.geometry.Size(r * 2f, r * 2f)
                )
            }
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White, Color.Transparent),
                    center = Offset(cx, cy),
                    radius = r
                ),
                radius = r,
                center = Offset(cx, cy)
            )
            // Selector ring.
            val a = Math.toRadians((hue + 90f).toDouble())
            val rr = sat * r
            val sx = cx + (cos(a) * rr).toFloat()
            val sy = cy + (sin(a) * rr).toFloat()
            drawCircle(color = Color.White, radius = 13f, center = Offset(sx, sy), style = Stroke(width = 4f))
            drawCircle(color = Color.Black, radius = 13f, center = Offset(sx, sy), style = Stroke(width = 2f))
        }
        SliderRow(
            label = stringResource(R.string.color_val, (value * 100).roundToInt()),
            tag = "slider-val",
            value = value,
            range = 0f..1f,
            onChange = { onPick(hsvToHex(hue, sat, it)) }
        )
    }
}

// ---- 3.2 Sliders (HSV first, then RGB) + editable hex ----
@Composable
private fun SlidersTab(inkHex: String, onPick: (String) -> Unit, onStatus: (String) -> Unit) {
    val hsv = hexToHsv(inkHex)
    val (r, g, b) = hexToRgb(inkHex)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SliderRow(
            label = stringResource(R.string.color_hue, hsv[0].roundToInt()),
            tag = "slider-hue", value = hsv[0], range = 0f..360f,
            onChange = { onPick(hsvToHex(it, hsv[1], hsv[2])) }
        )
        SliderRow(
            label = stringResource(R.string.color_sat, (hsv[1] * 100).roundToInt()),
            tag = "slider-sat", value = hsv[1], range = 0f..1f,
            onChange = { onPick(hsvToHex(hsv[0], it, hsv[2])) }
        )
        SliderRow(
            label = stringResource(R.string.color_val, (hsv[2] * 100).roundToInt()),
            tag = "slider-val2", value = hsv[2], range = 0f..1f,
            onChange = { onPick(hsvToHex(hsv[0], hsv[1], it)) }
        )
        SliderRow(
            label = stringResource(R.string.red, r),
            tag = "slider-red", value = r.toFloat(), range = 0f..255f,
            onChange = { onPick(rgbToHex(it.roundToInt(), g, b)) }
        )
        SliderRow(
            label = stringResource(R.string.green, g),
            tag = "slider-green", value = g.toFloat(), range = 0f..255f,
            onChange = { onPick(rgbToHex(r, it.roundToInt(), b)) }
        )
        SliderRow(
            label = stringResource(R.string.blue, b),
            tag = "slider-blue", value = b.toFloat(), range = 0f..255f,
            onChange = { onPick(rgbToHex(r, g, it.roundToInt())) }
        )
        HexField(inkHex = inkHex, onPick = onPick, onStatus = onStatus)
    }
}

@Composable
private fun SliderRow(
    label: String,
    tag: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    // 48dp touch zone per spec §3.2: the tagged Row is the target (the
    // Material Slider itself is ~20dp visual); taps outside the track are
    // absorbed, drags reach the Slider normally.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(tag)
            .clickable(onClick = {})
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFC4C4D0),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(96.dp)
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun HexField(inkHex: String, onPick: (String) -> Unit, onStatus: (String) -> Unit) {
    var draft by remember(inkHex) { mutableStateOf(inkHex) }
    val keptLabel = stringResource(R.string.color_hex_invalid, inkHex)
    val hint = stringResource(R.string.color_hex, "RRGGBB")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
    ) {
        TextField(
            value = draft,
            onValueChange = { typed ->
                draft = typed.uppercase()
                val t = typed.trim()
                val body = t.removePrefix("#")
                if (body.length == 6 && body.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                    onPick("#$body")
                }
            },
            label = { Text(hint, maxLines = 1) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                val body = draft.trim().removePrefix("#")
                if (body.length == 6 && body.all { it in '0'..'9' || it in 'A'..'F' }) {
                    onPick("#$body")
                } else {
                    draft = inkHex
                    onStatus(keptLabel)
                }
            }),
            modifier = Modifier
                .weight(1f)
                .testTag("hex-field")
        )
    }
}

// ---- 3.3 Saved sets (house JSON, persisted) ----
@Composable
private fun SetsTab(store: ColorStore) {
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var renameDraft by remember(store.activeSet) { mutableStateOf(store.activeSet) }
    var newOpen by remember { mutableStateOf(false) }
    var newDraft by remember { mutableStateOf("") }
    var deleteArmed by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            store.sets.forEachIndexed { i, s ->
                val selected = s.name == store.activeSet
                TextButton(
                    onClick = { store.selectSet(s.name); deleteArmed = false },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("set-chip-$i"),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (selected) Color(0xFF90B8FF) else Color(0xFFC4C4D0)
                    )
                ) {
                    Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        val colors = store.activeColors()
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            colors.chunked(4).forEachIndexed { row, chunk ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    chunk.forEach { hex ->
                        val idx = colors.indexOf(hex)
                        Swatch(
                            hex = hex,
                            tag = "swatch-$idx",
                            selected = hex == store.inkHex,
                            onTap = {
                                store.pick(hex)
                                menuFor = null
                            },
                            onLongPress = {
                                menuFor = if (store.activeSet == "Base") null else hex
                            }
                        )
                    }
                }
            }
        }

        menuFor?.let { target ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        store.replaceInSet(store.activeSet, target, store.inkHex)
                        menuFor = null
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("swatch-replace")
                ) {
                    Text(stringResource(R.string.color_set_add), maxLines = 1)
                }
                TextButton(
                    onClick = {
                        store.removeFromSet(store.activeSet, target)
                        menuFor = null
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("swatch-delete")
                ) {
                    Text(stringResource(R.string.color_set_delete), maxLines = 1)
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = { store.addCurrentToActive() },
                modifier = Modifier.heightIn(min = 48.dp).testTag("set-add"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF32323D), contentColor = Color(0xFFECECF1)
                )
            ) {
                Text(stringResource(R.string.color_set_add), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(
                onClick = { newOpen = !newOpen },
                modifier = Modifier.heightIn(min = 48.dp).testTag("set-new")
            ) {
                Text(
                    "+ " + stringResource(R.string.color_set_new),
                    maxLines = 1, color = Color(0xFFC4C4D0)
                )
            }
        }

        if (newOpen) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextField(
                    value = newDraft,
                    onValueChange = { newDraft = it.take(32) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("set-name-field")
                )
                TextButton(
                    onClick = {
                        if (store.newSet(newDraft.ifBlank { "Set ${store.sets.size + 1}" })) {
                            newDraft = ""
                            newOpen = false
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("set-name-ok")
                ) {
                    Text(stringResource(R.string.ok), maxLines = 1)
                }
            }
        }

        if (store.activeSet != "Base") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (renaming) {
                    TextField(
                        value = renameDraft,
                        onValueChange = { renameDraft = it.take(32) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("set-rename-field")
                    )
                    TextButton(
                        onClick = {
                            if (store.renameSet(store.activeSet, renameDraft)) renaming = false
                        },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("set-rename-ok")
                    ) {
                        Text(stringResource(R.string.ok), maxLines = 1)
                    }
                } else {
                    TextButton(
                        onClick = { renameDraft = store.activeSet; renaming = true },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("set-rename")
                    ) {
                        Text(stringResource(R.string.color_set_rename), maxLines = 1)
                    }
                }
                TextButton(
                    onClick = {
                        if (deleteArmed) {
                            store.deleteSet(store.activeSet)
                            deleteArmed = false
                        } else deleteArmed = true
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("set-delete")
                ) {
                    Text(
                        if (deleteArmed) " ! " + stringResource(R.string.color_set_delete)
                        else stringResource(R.string.color_set_delete),
                        maxLines = 1,
                        color = if (deleteArmed) Color(0xFFFF8A80) else Color(0xFFC4C4D0)
                    )
                }
            }
        }
    }
}

// ---- 3.4 Recents ----
@Composable
private fun RecentsTab(store: ColorStore) {
    var clearArmed by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (store.recents.isEmpty()) {
            Text(
                stringResource(R.string.color_recent_empty),
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF787888),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                store.recents.forEachIndexed { i, hex ->
                    Swatch(
                        hex = hex,
                        tag = "recent-$i",
                        selected = hex == store.inkHex,
                        onTap = { store.pick(hex) }
                    )
                }
            }
        }
        TextButton(
            onClick = {
                if (clearArmed) {
                    store.clearRecents()
                    clearArmed = false
                } else if (store.recents.isNotEmpty()) clearArmed = true
            },
            modifier = Modifier.heightIn(min = 48.dp).testTag("recent-clear")
        ) {
            Text(
                if (clearArmed) "! " + stringResource(R.string.color_recent_clear)
                else stringResource(R.string.color_recent_clear),
                maxLines = 1,
                color = if (clearArmed) Color(0xFFFF8A80) else Color(0xFFC4C4D0)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Swatch(
    hex: String,
    tag: String,
    selected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .testTag(tag)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .semantics { contentDescription = hex }
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(parseHexColor(hex))
                .border(
                    if (selected) 2.dp else 1.dp,
                    if (selected) Color(0xFF90B8FF) else Color(0xFF4A4A58),
                    RoundedCornerShape(6.dp)
                )
        )
    }
}
