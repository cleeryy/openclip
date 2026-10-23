// OpenClip M9 — reference pane: drives the glTF reference model overlay
// (mannequin tracing aid). Sliders push the full view JSON on release;
// the engine renders the wireframe OVER the frame in nCompositeInto
// only (never in thumbnails/export). State is owned here; the wrappers
// on EngineSurface are the only engine contact (ARCHITECTURE.md "M9
// additions").
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.openclip.R
import java.util.Locale

@Composable
fun ReferencePanel(
    surface: EngineSurface,
    onImportGlb: () -> Unit,
    modelName: String?,
    modifier: Modifier = Modifier,
    // T4 §4 decor: scene state is app-side (RefScene.kt); the engine
    // holds geometry + textures (session-only). docKey scopes the
    // decor.json replay; inkHex feeds the texture brush.
    docKey: String = "solo",
    inkHex: String = "#000000",
    glbCachePath: String? = null,
    onStatus: (String) -> Unit = {}
) {
    var visible by remember { mutableStateOf(false) }
    var yaw by remember { mutableStateOf(0f) }
    var pitch by remember { mutableStateOf(0f) }
    var roll by remember { mutableStateOf(0f) }
    var dist by remember { mutableStateOf(3f) }
    var fov by remember { mutableStateOf(45f) }
    var fisheye by remember { mutableStateOf(0f) }

    fun push() {
        surface.setRefView(
            String.format(
                Locale.US,
                "{\"yaw\":%.2f,\"pitch\":%.2f,\"roll\":%.2f,\"dist\":%.2f," +
                    "\"fov\":%.2f,\"fisheye\":%.2f,\"offX\":0,\"offY\":0}",
                yaw, pitch, roll, dist, fov, fisheye
            )
        )
    }

    Column(modifier = modifier.fillMaxWidth().testTag("v3-reference")) {
        Text(
            stringResource(R.string.reference),
            style = MaterialTheme.typography.titleSmall
        )
        if (modelName == null) {
            // V12: the empty state leads with the import CTA (48dp).
            Button(
                onClick = onImportGlb,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("ref-import")
            ) {
                Text(
                    stringResource(R.string.reference_import),
                    maxLines = 1,
                    softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modelName,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 8.dp)
                )
                TextButton(onClick = { surface.setRefVisible(false) }) {
                    Text(stringResource(R.string.reference_clear))
                }
            }
        }
        // V12: 48dp touch row; without a model the overlay toggle and
        // the pose sliders stay disabled (hint-toned) — nothing armed
        // on nothing.
        val hasModel = modelName != null
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(48.dp)
                .clickable(enabled = hasModel) {
                    visible = !visible
                    surface.setRefVisible(visible)
                }
                .testTag("ref-show-row")
        ) {
            Checkbox(
                checked = visible,
                enabled = hasModel,
                onCheckedChange = {
                    visible = it
                    surface.setRefVisible(it)
                }
            )
            Text(
                stringResource(R.string.reference_show),
                style = MaterialTheme.typography.labelSmall,
                color = if (hasModel) Color(0xFFECECF1) else Color(0xFF787888),
                maxLines = 1,
                softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        @Composable
        fun sliderRow(
            label: String,
            value: Float,
            range: ClosedFloatingPointRange<Float>,
            onDone: (Float) -> Unit
        ) {
            var local by remember(value) { mutableStateOf(value) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasModel) Color(0xFFECECF1) else Color(0xFF787888),
                    maxLines = 1,
                    softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Slider(
                    value = local,
                    enabled = hasModel,
                    onValueChange = { local = it },
                    onValueChangeFinished = { onDone(local) },
                    valueRange = range,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        sliderRow(
            stringResource(R.string.yaw, yaw.toInt()), yaw, 0f..360f
        ) { yaw = it; push() }
        sliderRow(
            stringResource(R.string.pitch, pitch.toInt()), pitch, 0f..360f
        ) { pitch = it; push() }
        sliderRow(
            stringResource(R.string.roll, roll.toInt()), roll, 0f..360f
        ) { roll = it; push() }
        sliderRow(
            stringResource(R.string.ref_dist, dist), dist, 0.5f..10f
        ) { dist = it; push() }
        sliderRow(
            stringResource(R.string.fov, fov.toInt()), fov, 10f..120f
        ) { fov = it; push() }
        sliderRow(
            stringResource(R.string.fisheye, (fisheye * 100).toInt()),
            fisheye, 0f..1f
        ) { fisheye = it; push() }
        // T4 §4 decor scene (multi-item references + primitives +
        // texture paint + layer commit). The camera sliders above are
        // the session view shared by the single model and the scene.
        RefSceneSection(
            surface = surface,
            docKey = docKey,
            inkHex = inkHex,
            glbCachePath = glbCachePath,
            view = { RefScene.viewJson(yaw, pitch, roll, dist, fov, fisheye) },
            onStatus = onStatus
        )
    }
}

// T4 §4 decor scene section (ARCHITECTURE.md "T4 additions" §4):
// multi-item reference list (glb files + procedural primitives),
// per-item pose sliders, texture paint mode, explicit layer commit.
// Overlay-first: nothing here writes layer pixels except the commit.
@Composable
fun RefSceneSection(
    surface: EngineSurface,
    docKey: String,
    inkHex: String,
    glbCachePath: String?,
    view: () -> String,
    onStatus: (String) -> Unit
) {
    val ctx = LocalContext.current
    var items by remember(surface, docKey) { mutableStateOf(listOf<RefItem>()) }
    var active by remember(surface, docKey) { mutableStateOf(0) }
    var paintMode by remember(surface, docKey) { mutableStateOf(false) }
    var batches by remember(surface, docKey) { mutableStateOf(listOf<RefPaintBatch>()) }
    var replayed by remember(surface, docKey) { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun pushScene() {
        val ok = surface.setRefScene(RefScene.sceneJson(items))
        if (ok) {
            surface.refPaintItem = if (items.isEmpty()) -1 else active.coerceIn(0, items.size - 1)
            RefScene.save(docKey, ctx.filesDir, items, view(), batches)
        } else if (items.isNotEmpty()) {
            onStatus(ctx.getString(R.string.ref_failed))
        }
    }

    // Replay persisted scene once (items + view + dab log).
    androidx.compose.runtime.LaunchedEffect(surface, docKey) {
        if (!replayed) {
            replayed = true
            try {
                val saved = RefScene.load(docKey, ctx.filesDir)
                if (saved != null && (saved.items.isNotEmpty() || saved.batches.isNotEmpty())) {
                    val live = saved.items.filter { it ->
                        !it.src.startsWith("file:") ||
                            java.io.File(it.src.removePrefix("file:")).isFile
                    }
                    if (live.isNotEmpty() || saved.items.isEmpty()) {
                        items = live
                        batches = saved.batches
                        if (live.isNotEmpty()) {
                            surface.setRefView(saved.view)
                            surface.setRefScene(RefScene.sceneJson(live))
                            for (b in saved.batches) {
                                if (b.item in live.indices && b.viewJson.isNotEmpty() &&
                                    b.dabsJson.isNotEmpty()
                                ) {
                                    surface.setRefView(b.viewJson)
                                    surface.paintRefMap(b.item, b.dabsJson)
                                }
                            }
                            surface.setRefView(saved.view.ifEmpty { view() })
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    // Paint-mode strokes report here (dab log for replay).
    androidx.compose.runtime.DisposableEffect(surface) {
        surface.onRefPaintDone = { ok ->
            if (!ok) {
                onStatus(ctx.getString(R.string.ref_failed))
            } else {
                val dabs = surface.refPaintLastDabs
                val item = surface.refPaintLastItem
                if (dabs.isNotEmpty() && item >= 0) {
                    batches = batches + RefPaintBatch(view(), item, dabs)
                    RefScene.save(docKey, ctx.filesDir, items, view(), batches)
                }
            }
        }
        onDispose { surface.onRefPaintDone = null }
    }

    Column(modifier = Modifier.fillMaxWidth().testTag("t4-ref-scene")) {
        Text(
            stringResource(R.string.ref_scene_add),
            style = MaterialTheme.typography.titleSmall
        )
        if (items.isEmpty()) {
            Text(
                stringResource(R.string.ref_scene_empty),
                style = MaterialTheme.typography.labelSmall
            )
        }
        items.forEachIndexed { i, it ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(
                    checked = it.visible,
                    onCheckedChange = { v ->
                        items = items.mapIndexed { j, o ->
                            if (j == i) o.copy(visible = v) else o
                        }
                        pushScene()
                    },
                    modifier = Modifier.testTag("t4-scene-visible-$i")
                )
                Text(
                    it.label(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (i == active) Color(0xFF59D6E6) else Color(0xFFECECF1),
                    maxLines = 1,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                        .clickable {
                            active = i
                            surface.refPaintItem = i
                        }
                        .testTag("t4-scene-item-$i")
                )
                TextButton(
                    onClick = {
                        items = items.filterIndexed { j, _ -> j != i }
                        if (active >= items.size) active = (items.size - 1).coerceAtLeast(0)
                        pushScene()
                    },
                    modifier = Modifier.testTag("t4-scene-del-$i")
                ) { Text(stringResource(R.string.ref_scene_remove)) }
            }
        }
        // Add: loaded .glb + primitives (0 asset, from scratch).
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (glbCachePath != null) {
                OutlinedButton(
                    onClick = {
                        if (items.size >= 8) {
                            onStatus(ctx.getString(R.string.ref_failed))
                            return@OutlinedButton
                        }
                        items = items + RefItem(src = "file:$glbCachePath")
                        active = items.size - 1
                        pushScene()
                    },
                    modifier = Modifier.height(48.dp).testTag("t4-scene-add-glb")
                ) { Text("+ .glb") }
            }
            listOf("cube", "sphere", "plane", "cylinder").forEach { prim ->
                OutlinedButton(
                    onClick = {
                        if (items.size >= 8) {
                            onStatus(ctx.getString(R.string.ref_failed))
                            return@OutlinedButton
                        }
                        items = items + RefItem(src = "prim:$prim")
                        active = items.size - 1
                        pushScene()
                    },
                    modifier = Modifier.height(48.dp).testTag("t4-scene-add-$prim")
                ) { Text("+ $prim") }
            }
        }
        if (items.isNotEmpty()) {
            val cur = items[active.coerceIn(0, items.size - 1)]
            fun update(fn: (RefItem) -> RefItem) {
                val idx = active.coerceIn(0, items.size - 1)
                items = items.mapIndexed { j, o -> if (j == idx) fn(o) else o }
                pushScene()
            }
            Text(
                stringResource(R.string.ref_item_pose),
                style = MaterialTheme.typography.labelSmall
            )
            @Composable
            fun poseSlider(
                label: String,
                value: Float,
                range: ClosedFloatingPointRange<Float>,
                tag: String,
                onDone: (Float) -> Unit
            ) {
                var local by remember(value) { mutableStateOf(value) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Slider(
                        value = local,
                        onValueChange = { local = it },
                        onValueChangeFinished = { onDone(local) },
                        valueRange = range,
                        modifier = Modifier.fillMaxWidth().testTag(tag)
                    )
                }
            }
            poseSlider("X", cur.pos[0], -512f..512f, "t4-pose-x") {
                update { o -> o.copy(pos = floatArrayOf(it, o.pos[1], o.pos[2])) }
            }
            poseSlider("Y", cur.pos[1], -512f..512f, "t4-pose-y") {
                update { o -> o.copy(pos = floatArrayOf(o.pos[0], it, o.pos[2])) }
            }
            poseSlider("rotY", cur.rot[1], 0f..360f, "t4-pose-roty") {
                update { o -> o.copy(rot = floatArrayOf(o.rot[0], it, o.rot[2])) }
            }
            poseSlider("scale", cur.scale, 0.1f..8f, "t4-pose-scale") {
                update { o -> o.copy(scale = it) }
            }
            if (!cur.src.startsWith("prim:")) {
                // Loaded models may carry UV; primitives never do.
                Text(
                    stringResource(R.string.ref_no_uv_planar),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF787888)
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.Checkbox(
                    checked = paintMode,
                    onCheckedChange = {
                        paintMode = it
                        surface.refPaintArmed = it
                        surface.refPaintItem =
                            if (it) active.coerceIn(0, items.size - 1) else -1
                        surface.refPaintColor = inkHex
                        status = null
                    },
                    modifier = Modifier.testTag("t4-paint-toggle")
                )
                Text(
                    stringResource(R.string.ref_paint),
                    style = MaterialTheme.typography.labelSmall
                )
                Button(
                    onClick = {
                        val idx = active.coerceIn(0, items.size - 1)
                        // Commit pipeline: texture opaque, edges off.
                        val commitJson =
                            RefScene.sceneJson(items, wire = false, flatFor = idx)
                        val restoreJson = RefScene.sceneJson(items)
                        surface.commitRefTexture(commitJson, restoreJson) { ok ->
                            if (ok) {
                                status = ctx.getString(R.string.selection_flattened_note)
                                onStatus(
                                    ctx.getString(R.string.selection_flattened_note)
                                )
                            } else {
                                onStatus(ctx.getString(R.string.ref_failed))
                            }
                        }
                    },
                    modifier = Modifier.height(48.dp).testTag("t4-commit-layer")
                ) { Text(stringResource(R.string.ref_commit_layer)) }
            }
            if (status != null) {
                Text(status!!, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    // Keep the paint brush in sync with the ink (dab log replay uses
    // the color carried per gesture — refPaintColor set on toggle).
    if (paintMode) {
        androidx.compose.runtime.LaunchedEffect(inkHex) {
            surface.refPaintColor = inkHex
        }
    }
}
