// OpenClip T3 — panneaux animation productive : cles, camera,
// pistes, etiquettes + feuille d'exposition (ARCHITECTURE.md §T3).
// Overlay-only (jamais de pixels moteur) ; fiches meta hors undo ;
// cibles >= 48dp ; FR/EN via strings.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.openclip.R
import java.io.File
import kotlin.math.roundToInt
import org.json.JSONObject

/** Lit l'index du calque actif depuis nLayerState ("active"). */
fun t3ActiveLayer(surface: EngineSurface): Int {
    return try {
        val st = org.json.JSONObject(surface.layerState())
        st.optInt("active", 0)
    } catch (_: Exception) {
        0
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnimKeysDialog(
    surface: EngineSurface,
    layerIndex: Int,
    curCel: Int,
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    var keys by remember { mutableStateOf(animKeysFromJson(surface.layerAnim(layerIndex))) }
    var selCel by remember { mutableStateOf<Int?>(null) }
    var dx by remember { mutableStateOf(0f) }
    var dy by remember { mutableStateOf(0f) }
    var scale by remember { mutableStateOf(1f) }
    var angle by remember { mutableStateOf(0f) }
    var opacity by remember { mutableStateOf(1f) }
    var ease by remember { mutableStateOf("linear") }
    var c1x by remember { mutableStateOf(0.25f) }
    var c1y by remember { mutableStateOf(0.1f) }
    var c2x by remember { mutableStateOf(0.75f) }
    var c2y by remember { mutableStateOf(0.9f) }

    fun push(next: List<AnimKey>) {
        val ok = if (next.isEmpty()) surface.setLayerAnim(layerIndex, null)
        else surface.setLayerAnim(layerIndex, animKeysToJson(next))
        if (ok) {
            keys = next
            onChanged()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 6.dp
        ) {
            LazyColumn(modifier = Modifier.padding(16.dp)) {
                item {
                    Text(
                        stringResource(R.string.anim_keys_title, layerIndex),
                        style = MaterialTheme.typography.titleMedium
                    )
                    // Liste des cles : tap = saut, x = supprimer.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        if (keys.isEmpty()) {
                            Text(
                                stringResource(R.string.anim_no_keys),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        for (k in keys) {
                            FilterChip(
                                selected = selCel == k.cel,
                                onClick = {
                                    selCel = k.cel
                                    dx = k.dx
                                    dy = k.dy
                                    scale = k.scale
                                    angle = k.angle
                                    opacity = k.opacity
                                    ease = k.ease
                                },
                                label = { Text("◆${k.cel}") },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("anim-key-${k.cel}")
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                val base = sampleAnimKeys(keys, curCel.toFloat())
                                val nk = AnimKey(
                                    cel = curCel,
                                    dx = base?.dx ?: 0f,
                                    dy = base?.dy ?: 0f,
                                    scale = base?.scale ?: 1f,
                                    angle = base?.angle ?: 0f,
                                    opacity = base?.opacity ?: 1f,
                                    ease = "linear"
                                )
                                push((keys.filter { it.cel != curCel } + nk))
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("anim-key-add")
                        ) { Text(stringResource(R.string.anim_key_add)) }
                        TextButton(
                            enabled = selCel != null,
                            onClick = {
                                val c = selCel ?: return@TextButton
                                push(keys.filter { it.cel != c })
                                selCel = null
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("anim-key-delete")
                        ) { Text(stringResource(R.string.anim_key_delete)) }
                    }
                    // Editeur de la cle selectionnee (ou nouvelle).
                    Text(
                        stringResource(R.string.anim_key_edit),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    AnimSlider(
                        label = stringResource(R.string.anim_dx, dx),
                        value = dx, range = -512f..512f, tag = "anim-dx"
                    ) { dx = it }
                    AnimSlider(
                        label = stringResource(R.string.anim_dy, dy),
                        value = dy, range = -512f..512f, tag = "anim-dy"
                    ) { dy = it }
                    AnimSlider(
                        label = stringResource(R.string.anim_scale, scale),
                        value = scale, range = 0.1f..8f, tag = "anim-scale"
                    ) { scale = it }
                    AnimSlider(
                        label = stringResource(R.string.anim_angle, angle),
                        value = angle, range = -360f..360f, tag = "anim-angle"
                    ) { angle = it }
                    AnimSlider(
                        label = stringResource(R.string.anim_opacity, (opacity * 100).roundToInt()),
                        value = opacity, range = 0f..1f, tag = "anim-opacity"
                    ) { opacity = it }
                    TextButton(
                        onClick = {
                            val c = selCel ?: curCel
                            push(
                                keys.filter { it.cel != c } + AnimKey(
                                    c, dx, dy, scale, angle, opacity, ease
                                )
                            )
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("anim-key-commit")
                    ) { Text(stringResource(R.string.anim_key_set)) }
                    // Aisances (5 segments 48dp).
                    Text(
                        stringResource(R.string.anim_ease),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val labels = mapOf(
                            "linear" to R.string.anim_ease_linear,
                            "ease-in" to R.string.anim_ease_in,
                            "ease-out" to R.string.anim_ease_out,
                            "smooth" to R.string.anim_ease_smooth,
                            "step" to R.string.anim_ease_step
                        )
                        for ((id, res) in labels) {
                            FilterChip(
                                selected = ease == id,
                                onClick = { ease = id },
                                label = { Text(stringResource(res)) },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("anim-ease-$id")
                            )
                        }
                    }
                    // Courbe Bezier maison : apercu de trajectoire
                    // (overlay pointille) + table 64 pas (0 JNI).
                    Text(
                        stringResource(R.string.anim_curve_edit),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    AnimSlider(
                        label = "c1x ${(c1x * 100).roundToInt()}%",
                        value = c1x, range = 0f..1f, tag = "anim-c1x"
                    ) { c1x = it }
                    AnimSlider(
                        label = "c1y ${(c1y * 100).roundToInt()}%",
                        value = c1y, range = 0f..1f, tag = "anim-c1y"
                    ) { c1y = it }
                    AnimSlider(
                        label = "c2x ${(c2x * 100).roundToInt()}%",
                        value = c2x, range = 0f..1f, tag = "anim-c2x"
                    ) { c2x = it }
                    AnimSlider(
                        label = "c2y ${(c2y * 100).roundToInt()}%",
                        value = c2y, range = 0f..1f, tag = "anim-c2y"
                    ) { c2y = it }
                    val table = remember(c1x, c1y, c2x, c2y) {
                        bezierEaseTable(c1x, c1y, c2x, c2y).also {
                            T3TestHooks.lastBezierTable = it
                        }
                    }
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp)
                            .background(Color(0xFF141418))
                            .testTag("anim-curve-preview")
                    ) {
                        val w = size.width
                        val h = size.height
                        drawLine(
                            Color(0xFF5A5A6E), Offset(0f, h), Offset(w, 0f), 1f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                        )
                        var prev: Offset? = null
                        for (i in table.indices) {
                            val cur = Offset(
                                i.toFloat() / (table.size - 1) * w,
                                h - table[i] * h
                            )
                            if (prev != null) drawLine(Color(0xFF90B8FF), prev, cur, 3f)
                            prev = cur
                        }
                    }
                    Text(
                        stringResource(R.string.anim_curve_hint),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text(stringResource(R.string.cancel)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    tag: String,
    onValue: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValue,
            valueRange = range,
            modifier = Modifier
                .weight(2f)
                .testTag(tag)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CameraDialog(
    surface: EngineSurface,
    curCel: Int,
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    var keys by remember { mutableStateOf(cameraKeysFromJson(surface.cameraTrack())) }
    var enabled by remember { mutableStateOf(false) }
    var selCel by remember { mutableStateOf<Int?>(null) }
    var cx by remember { mutableStateOf(128f) }
    var cy by remember { mutableStateOf(128f) }
    var zoom by remember { mutableStateOf(1f) }

    fun push(next: List<CameraKey>) {
        val ok = if (next.isEmpty()) surface.setCameraTrack(null)
        else surface.setCameraTrack(cameraKeysToJson(next))
        if (ok) {
            keys = next
            onChanged()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 6.dp
        ) {
            LazyColumn(modifier = Modifier.padding(16.dp)) {
                item {
                    Text(
                        stringResource(R.string.camera_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = enabled,
                            onCheckedChange = {
                                enabled = it
                                if (surface.setCameraEnabled(it)) onChanged()
                            },
                            modifier = Modifier.testTag("camera-toggle")
                        )
                        Text(
                            stringResource(
                                if (enabled) R.string.camera_track_on
                                else R.string.camera_track_off
                            ),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (keys.isEmpty()) {
                            Text(
                                stringResource(R.string.camera_no_keys),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        for (k in keys) {
                            FilterChip(
                                selected = selCel == k.cel,
                                onClick = {
                                    selCel = k.cel
                                    cx = k.x
                                    cy = k.y
                                    zoom = k.zoom
                                },
                                label = { Text("◉${k.cel}") },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("camera-key-${k.cel}")
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                push(
                                    keys.filter { it.cel != curCel } + CameraKey(
                                        curCel, cx, cy, zoom, 0f, "smooth"
                                    )
                                )
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("camera-key-add")
                        ) { Text(stringResource(R.string.camera_key_add)) }
                        TextButton(
                            enabled = selCel != null,
                            onClick = {
                                val c = selCel ?: return@TextButton
                                push(keys.filter { it.cel != c })
                                selCel = null
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("camera-key-delete")
                        ) { Text(stringResource(R.string.anim_key_delete)) }
                        TextButton(
                            onClick = {
                                push(emptyList())
                                selCel = null
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("camera-reset")
                        ) { Text(stringResource(R.string.camera_reset_view)) }
                    }
                    AnimSlider(
                        label = stringResource(R.string.camera_x, cx),
                        value = cx, range = -2048f..4096f, tag = "camera-x"
                    ) { cx = it }
                    AnimSlider(
                        label = stringResource(R.string.camera_y, cy),
                        value = cy, range = -2048f..4096f, tag = "camera-y"
                    ) { cy = it }
                    AnimSlider(
                        label = stringResource(R.string.camera_zoom, (zoom * 100).roundToInt()),
                        value = zoom, range = 0.25f..8f, tag = "camera-zoom"
                    ) { zoom = it }
                    TextButton(
                        onClick = {
                            val c = selCel ?: curCel
                            push(
                                keys.filter { it.cel != c } + CameraKey(
                                    c, cx, cy, zoom, 0f, "smooth"
                                )
                            )
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("camera-key-commit")
                    ) { Text(stringResource(R.string.anim_key_set)) }
                    Text(
                        stringResource(R.string.camera_manual_cut),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2
                    )
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TracksDialog(
    surface: EngineSurface,
    cacheDir: File,
    curCel: Int,
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    var state by remember {
        mutableStateOf(trackStateFromJson(surface.trackState()))
    }
    var renameIdx by remember { mutableStateOf<Int?>(null) }
    var renameTxt by remember { mutableStateOf("") }
    var dupBusy by remember { mutableStateOf(false) }

    fun refresh() {
        state = trackStateFromJson(surface.trackState())
        onChanged()
    }

    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 6.dp
        ) {
            LazyColumn(modifier = Modifier.padding(16.dp)) {
                item {
                    Text(
                        stringResource(R.string.track_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                items(state.second.indices.toList()) { i ->
                    val t = state.second[i]
                    val active = i == state.first
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.testTag("track-row-$i")
                    ) {
                        FilterChip(
                            selected = active,
                            onClick = {
                                if (surface.setActiveTrack(i)) refresh()
                            },
                            label = { Text(if (t.name.isEmpty()) "P${i + 1}" else t.name) },
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("track-select-$i")
                        )
                        Text(
                            "${t.frames}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.testTag("track-frames-$i")
                        )
                        TextButton(
                            onClick = {
                                if (surface.setTrackVisible(i, !t.visible)) refresh()
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text(if (t.visible) "◉" else "○") }
                        TextButton(
                            onClick = {
                                renameIdx = i
                                renameTxt = t.name
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("✎") }
                        TextButton(
                            enabled = state.second.size > 1,
                            onClick = {
                                if (surface.deleteTrack(i)) refresh()
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("×") }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = state.second.size < 4,
                            onClick = {
                                val idx = surface.addTrack("")
                                if (idx >= 0) refresh()
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("track-add")
                        ) { Text(stringResource(R.string.track_add)) }
                        TextButton(
                            enabled = !dupBusy,
                            onClick = {
                                dupBusy = true
                                // Duplique la cellulo courante seule
                                // (aplatie — annonce, pas tout le clip).
                                surface.pullFrameFull(curCel) { bmp ->
                                    try {
                                        if (bmp == null) return@pullFrameFull
                                        val idx = surface.addTrack("")
                                        if (idx < 0) return@pullFrameFull
                                        val f = File(
                                            cacheDir,
                                            "trackdup-${System.currentTimeMillis()}.png"
                                        )
                                        f.outputStream().use { o ->
                                            bmp.compress(
                                                android.graphics.Bitmap.CompressFormat.PNG,
                                                100, o
                                            )
                                        }
                                        if (surface.setActiveTrack(idx)) {
                                            surface.blitPngIntoLayer(0, f.absolutePath)
                                        }
                                        f.delete()
                                    } finally {
                                        dupBusy = false
                                        refresh()
                                    }
                                }
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("track-duplicate")
                        ) { Text(stringResource(R.string.track_duplicate)) }
                    }
                    Text(
                        stringResource(R.string.track_duplicate_note),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2
                    )
                    if (renameIdx != null) {
                        OutlinedTextField(
                            value = renameTxt,
                            onValueChange = { renameTxt = it.take(24) },
                            label = { Text(stringResource(R.string.track_rename)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("track-rename-field")
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    val i = renameIdx ?: return@TextButton
                                    if (surface.setTrackName(i, renameTxt)) refresh()
                                    renameIdx = null
                                },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text(stringResource(R.string.ok)) }
                            TextButton(
                                onClick = { renameIdx = null },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text(stringResource(R.string.cancel)) }
                        }
                    }
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExposureSheetDialog(
    surface: EngineSurface,
    frameCount: Int,
    holds: Map<Int, Int>,
    curCel: Int,
    onJump: (Int) -> Unit,
    onExportCsv: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var labels by remember { mutableStateOf(surface.t3Labels) }
    var newTxt by remember { mutableStateOf("") }

    fun keyCounts(): Map<Int, Int> {
        return try {
            val st = org.json.JSONObject(surface.layerState())
            val arr = st.optJSONArray("layers") ?: return emptyMap()
            val m = mutableMapOf<Int, Int>()
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val s = e.optJSONObject("self") ?: e
                m[i] = s.optInt("anim", 0)
            }
            m
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun cameraCels(): Set<Int> {
        return try {
            cameraKeysFromJson(surface.cameraTrack()).map { it.cel }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    val rows = remember(frameCount, holds, labels) {
        buildExposure(frameCount, holds, keyCounts(), cameraCels(), labels).also {
            T3TestHooks.lastExposure = it
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 6.dp
        ) {
            LazyColumn(modifier = Modifier.padding(16.dp)) {
                item {
                    Text(
                        stringResource(R.string.exposure_sheet),
                        style = MaterialTheme.typography.titleMedium
                    )
                    // Ajout d'etiquette ici (24 car. max).
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        OutlinedTextField(
                            value = newTxt,
                            onValueChange = { newTxt = it.take(24) },
                            label = { Text(stringResource(R.string.label_add)) },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("label-field")
                        )
                        TextButton(
                            enabled = newTxt.isNotBlank(),
                            onClick = {
                                val next = labels.filter { it.cel != curCel } +
                                    AnimLabel(curCel, newTxt.take(24))
                                labels = next.sortedBy { it.cel }
                                surface.t3Labels = labels
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("label-add")
                        ) { Text("+") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (l in labels) {
                            FilterChip(
                                selected = false,
                                onClick = { onJump(l.cel) },
                                label = { Text("● ${l.txt}@${l.cel}") },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("label-chip-${l.cel}"),
                                trailingIcon = {
                                    TextButton(
                                        onClick = {
                                            val next = labels.filter { it != l }
                                            labels = next
                                            surface.t3Labels = next
                                        },
                                        modifier = Modifier.heightIn(min = 48.dp)
                                    ) { Text("×") }
                                }
                            )
                        }
                    }
                }
                items(rows) { r ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("exposure-row-${r.cel}")
                    ) {
                        Text(
                            "${r.cel}",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.testTag("exposure-cel-${r.cel}")
                        )
                        if (r.hold > 1) {
                            Text(
                                "×${r.hold}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFFC845)
                            )
                        }
                        if (r.keyedLayers.isNotEmpty()) {
                            Text(
                                "◆" + r.keyedLayers.joinToString("+"),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        if (r.cameraKey) {
                            Text(
                                "◉",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        if (r.label != null) {
                            Text(
                                r.label,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                val csv = exposureCsv(rows).also {
                                    T3TestHooks.lastCsv = it
                                }
                                onExportCsv(csv)
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("exposure-export")
                        ) { Text(stringResource(R.string.exposure_export)) }
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text(stringResource(R.string.cancel)) }
                    }
                }
            }
        }
    }
}

/**
 * T3 §4 : panneau sequences + APNG + stickers + audio seul
 * (ARCHITECTURE.md §T3 §4). Le moteur compose en interne (jamais de
 * pull app-side par trame) ; le ZIP est java.util.zip (progression +
 * annulation) ; l'audio seul = copie byte-identique.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun T3ExportPanel(
    surface: EngineSurface,
    cacheDir: File,
    fps: Float,
    hasAudio: Boolean,
    onExportFile: (File, String) -> Unit,
    onExportAudioOnly: () -> Unit,
    onStatus: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var expand by remember { mutableStateOf(true) }
    var apngMaxW by remember { mutableStateOf(1024f) }
    var stickerMode by remember { mutableStateOf("apng") }

    Column(modifier = modifier.fillMaxWidth().testTag("t3-export-panel")) {
        Text(
            stringResource(R.string.export_title),
            style = MaterialTheme.typography.titleSmall
        )
        // Sequence PNG -> ZIP.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Checkbox(
                checked = expand,
                onCheckedChange = { expand = it },
                modifier = Modifier.testTag("t3-expand-holds")
            )
            Text(
                stringResource(R.string.export_hold_expand),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f)
            )
        }
        Button(
            enabled = !busy,
            onClick = {
                busy = true
                v2RunBg {
                    var ok = false
                    var zip: File? = null
                    try {
                        val dir = File(cacheDir, "t3seq-${System.currentTimeMillis()}")
                        dir.mkdirs()
                        val json = JSONObject()
                            .put("expandHolds", expand)
                            .put("prefix", "frame").toString()
                        if (surface.exportPngSeq(dir.absolutePath, json)) {
                            val files = dir.listFiles { f -> f.extension == "png" }
                                ?.sortedBy { it.name } ?: emptyList()
                            if (files.isNotEmpty()) {
                                zip = File(cacheDir, "t3seq-${System.currentTimeMillis()}.zip")
                                java.util.zip.ZipOutputStream(zip.outputStream()).use { z ->
                                    for (f in files) {
                                        z.putNextEntry(java.util.zip.ZipEntry(f.name))
                                        f.inputStream().use { it.copyTo(z) }
                                        z.closeEntry()
                                    }
                                }
                                ok = true
                            }
                        }
                        dir.deleteRecursively()
                    } catch (_: Exception) {
                        ok = false
                    }
                    val z = zip
                    val done = {
                        busy = false
                        if (ok && z != null) onExportFile(z, "application/zip")
                        else {
                            z?.delete()
                            onStatus(ctx.getString(R.string.export_failed))
                        }
                    }
                    done()
                }
            },
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("t3-export-pngseq")
        ) { Text(stringResource(R.string.export_pngseq)) }
        // APNG.
        Text(
            stringResource(R.string.export_apng) + " ≤ ${apngMaxW.toInt()}px",
            style = MaterialTheme.typography.labelSmall
        )
        Slider(
            value = apngMaxW,
            onValueChange = { apngMaxW = it },
            valueRange = 128f..2048f,
            modifier = Modifier.testTag("t3-apng-maxw")
        )
        Button(
            enabled = !busy,
            onClick = {
                busy = true
                v2RunBg {
                    var ok = false
                    try {
                        val dst = File(cacheDir, "t3-${System.currentTimeMillis()}.png")
                        val json = JSONObject()
                            .put("loop", -1).put("maxWidth", apngMaxW.toInt())
                            .put("fps", fps.toDouble()).toString()
                        if (surface.exportApng(dst.absolutePath, json)) {
                            ok = true
                            onExportFile(dst, "image/png")
                        } else {
                            dst.delete()
                        }
                    } catch (_: Exception) {
                        ok = false
                    } finally {
                        busy = false
                        if (!ok) onStatus(ctx.getString(R.string.export_failed))
                    }
                }
            },
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("t3-export-apng")
        ) { Text(stringResource(R.string.export_apng)) }
        // Sticker 512 (APNG fond opaque ; WebM = export film existant).
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("apng" to "APNG", "webm" to "WebM").forEach { (m, label) ->
                FilterChip(
                    selected = stickerMode == m,
                    onClick = { stickerMode = m },
                    label = { Text(label) },
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("t3-sticker-$m")
                )
            }
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    v2RunBg {
                        var ok = false
                        try {
                            if (stickerMode == "apng") {
                                val dst = File(
                                    cacheDir,
                                    "sticker-${System.currentTimeMillis()}.png"
                                )
                                val json = JSONObject()
                                    .put("loop", -1).put("maxWidth", 512)
                                    .put("fps", fps.toDouble())
                                    .put("matte", "#FFFFFF").toString()
                                if (surface.exportApng(dst.absolutePath, json)) {
                                    ok = true
                                    onExportFile(dst, "image/png")
                                } else {
                                    dst.delete()
                                }
                            } else {
                                // WebM sticker = film existant (pleine res,
                                // annonce) — le 512 opaque vit cote APNG.
                                onStatus(ctx.getString(R.string.export_webm))
                            }
                        } catch (_: Exception) {
                            ok = false
                        } finally {
                            busy = false
                            if (!ok && stickerMode == "apng") {
                                onStatus(ctx.getString(R.string.export_failed))
                            }
                        }
                    }
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("t3-export-sticker")
            ) { Text(stringResource(R.string.export_sticker)) }
        }
        // Audio seul (0 JNI : copie byte-identique du cache).
        if (hasAudio) {
            Button(
                enabled = !busy,
                onClick = onExportAudioOnly,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("t3-export-audio")
            ) { Text(stringResource(R.string.export_audio_only)) }
        } else {
            Text(
                stringResource(R.string.export_no_audio),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
