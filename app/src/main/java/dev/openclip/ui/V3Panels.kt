// OpenClip V+3 — confort-pro panels (§1.3, §3, §4, §5, §6, §7, proof
// §1.2). Thin Compose over the V3 pure units + the documented JNI; no
// new engine pixels (proof/channels/pose/text-path are composite-only
// or bridge through V+1/V+2 symbols).
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.Image
import dev.openclip.R
import java.io.File
import java.util.Locale

/** Parse #RRGGBB for dots (fallback gray on malformed input). */
private fun dotColor(hex: String): Color = try {
    val c = android.graphics.Color.parseColor(hex)
    Color(c)
} catch (_: Exception) {
    Color.Gray
}

// ---- §1.3 Harmonies ----

@Composable
fun HarmonyPanel(
    store: ColorStore,
    modifier: Modifier = Modifier,
) {
    var mode by remember { mutableStateOf("complement") }
    val base = Harmony.hexToHsv(store.inkHex)
    val hues = Harmony.huesFor(base[0], mode)
    Column(
        modifier = modifier.fillMaxWidth().testTag("v3-harmony")
    ) {
        Text(
            stringResource(R.string.harmony_title),
            style = MaterialTheme.typography.titleSmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Harmony.MODES.forEach { m ->
                FilterChip(
                    selected = mode == m,
                    onClick = { mode = m },
                    label = {
                        Text(
                            stringResource(
                                when (m) {
                                    "complement" -> R.string.harmony_complement
                                    "analogous" -> R.string.harmony_analogous
                                    "triadic" -> R.string.harmony_triadic
                                    else -> R.string.harmony_square
                                }
                            )
                        )
                    },
                    modifier = Modifier.testTag("v3-harmony-mode-$m")
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            hues.forEachIndexed { i, h ->
                val hex = Harmony.hsvToHex(h, base[1], base[2])
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(dotColor(hex))
                        .clickable { store.pick(hex) }
                        .testTag("v3-harmony-dot-$i")
                )
            }
        }
        Text(
            stringResource(R.string.harmony_hint),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// ---- §4 Canaux nommés ----

@Composable
fun ChannelsPanel(
    surface: EngineSurface,
    docKey: String,
    cacheDir: File,
    onStatus: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    var names by remember {
        mutableStateOf(ChannelStore.list(cacheDir, docKey))
    }
    var draft by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }

    fun refresh() {
        names = ChannelStore.list(cacheDir, docKey)
    }

    Column(
        modifier = modifier.fillMaxWidth().testTag("v3-channels")
    ) {
        Text(
            stringResource(R.string.channel_title),
            style = MaterialTheme.typography.titleSmall
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(stringResource(R.string.channel_name)) },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("v3-channel-name")
            )
            Button(
                onClick = {
                    v2RunBg {
                        val bits = surface.exportSelectionBits()
                        if (bits == null || draft.isBlank()) {
                            onStatus(ctx.getString(R.string.channel_empty))
                            return@v2RunBg
                        }
                        val (w, h) = surface.selectionSize()
                        val png = ChannelOps.bitsToPng(w, h, bits)
                        val dir = ChannelStore.dir(cacheDir, docKey)
                        if (png == null) {
                            onStatus(ctx.getString(R.string.v2_panel_failed))
                            return@v2RunBg
                        }
                        try {
                            ChannelStore.fileFor(dir, draft).writeBytes(png)
                            ChannelStore.save(cacheDir, docKey, names + draft)
                            draft = ""
                            refresh()
                        } catch (_: Exception) {
                            onStatus(ctx.getString(R.string.v2_panel_failed))
                        }
                    }
                },
                modifier = Modifier.testTag("v3-channel-save")
            ) { Text(stringResource(R.string.channel_save)) }
        }
        if (names.isEmpty()) {
            Text(
                stringResource(R.string.channel_empty),
                style = MaterialTheme.typography.bodySmall
            )
        }
        names.forEach { name ->
            Column(modifier = Modifier.testTag("v3-channel-row-$name")) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    ChannelOp.entries.forEach { op ->
                        TextButton(
                            onClick = {
                                v2RunBg {
                                    val dir = ChannelStore.dir(cacheDir, docKey)
                                    val f = ChannelStore.fileFor(dir, name)
                                    if (!f.exists()) {
                                        onStatus(ctx.getString(R.string.v2_panel_failed))
                                        return@v2RunBg
                                    }
                                    val (w, h) = surface.selectionSize()
                                    val over = ChannelOps.pngToBits(w, h, f.readBytes())
                                    if (over == null) {
                                        onStatus(ctx.getString(R.string.channel_size_mismatch))
                                        return@v2RunBg
                                    }
                                    val base = surface.exportSelectionBits()
                                        ?: ByteArray(w * h)
                                    val merged = try {
                                        ChannelOps.apply(op, base, over)
                                    } catch (_: Exception) {
                                        onStatus(ctx.getString(R.string.channel_size_mismatch))
                                        return@v2RunBg
                                    }
                                    if (!surface.importSelectionBits(merged)) {
                                        onStatus(ctx.getString(R.string.channel_size_mismatch))
                                    }
                                }
                            },
                            modifier = Modifier.testTag("v3-channel-op-${op.name}-$name")
                        ) {
                            Text(
                                stringResource(
                                    when (op) {
                                        ChannelOp.REPLACE -> R.string.channel_op_replace
                                        ChannelOp.ADD -> R.string.channel_op_add
                                        ChannelOp.SUBTRACT -> R.string.channel_op_subtract
                                        ChannelOp.INTERSECT -> R.string.channel_op_intersect
                                    }
                                )
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (renaming == name) {
                        OutlinedTextField(
                            value = renameDraft,
                            onValueChange = { renameDraft = it },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                v2RunBg {
                                    if (ChannelStore.rename(cacheDir, docKey, name, renameDraft)) {
                                        renaming = null
                                        refresh()
                                    } else {
                                        onStatus(ctx.getString(R.string.v2_panel_failed))
                                    }
                                }
                            }
                        ) { Text(stringResource(R.string.channel_rename)) }
                    } else {
                        TextButton(
                            onClick = {
                                renaming = name
                                renameDraft = name
                            }
                        ) { Text(stringResource(R.string.channel_rename)) }
                        TextButton(
                            onClick = {
                                v2RunBg {
                                    ChannelStore.delete(cacheDir, docKey, name)
                                    if (renaming == name) renaming = null
                                    refresh()
                                }
                            }
                        ) { Text(stringResource(R.string.channel_delete)) }
                    }
                }
            }
        }
    }
}

// ---- §5 Mannequin posable ----

@Composable
fun PosePanel(
    surface: EngineSurface,
    docKey: String,
    cacheDir: File,
    onStatus: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    var joints by remember { mutableStateOf<List<PoseJoint>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var pose by remember {
        mutableStateOf(PoseStore.load(cacheDir, docKey).toMutableMap())
    }
    var preset by remember { mutableStateOf("standing") }

    fun push() {
        v2RunBg {
            if (!surface.setRefPose(PoseStore.toEngineJson(pose))) {
                onStatus(ctx.getString(R.string.v2_panel_failed))
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().testTag("v3-pose")
    ) {
        Text(
            stringResource(R.string.pose_title),
            style = MaterialTheme.typography.titleSmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    v2RunBg {
                        joints = PoseStore.parseJoints(surface.refJoints())
                        pose = PoseStore.load(cacheDir, docKey).toMutableMap()
                        if (joints.isEmpty()) {
                            onStatus(ctx.getString(R.string.pose_no_model))
                        }
                    }
                },
                modifier = Modifier.testTag("v3-pose-refresh")
            ) { Text(stringResource(R.string.v2_refresh)) }
            OutlinedButton(
                onClick = {
                    v2RunBg {
                        pose = mutableMapOf()
                        PoseStore.save(cacheDir, docKey, pose)
                        push()
                    }
                },
                modifier = Modifier.testTag("v3-pose-reset")
            ) { Text(stringResource(R.string.pose_reset)) }
        }
        if (joints.isEmpty()) {
            Text(
                stringResource(R.string.pose_no_model),
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                joints.forEach { j ->
                    FilterChip(
                        selected = selected == j.name,
                        onClick = { selected = j.name },
                        label = { Text(j.name) },
                        modifier = Modifier.testTag("v3-pose-joint-${j.name}")
                    )
                }
            }
            val cur = selected
            if (cur != null) {
                val e = pose[cur] ?: floatArrayOf(0f, 0f, 0f)
                listOf(0, 1, 2).forEach { ax ->
                    Text(
                        stringResource(
                            R.string.pose_axis,
                            when (ax) {
                                0 -> "X"
                                1 -> "Y"
                                else -> "Z"
                            },
                            e[ax].toInt()
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = e[ax],
                        onValueChange = { v ->
                            val next = (pose[cur] ?: floatArrayOf(0f, 0f, 0f)).copyOf()
                            next[ax] = v
                            pose[cur] = next
                            PoseStore.save(cacheDir, docKey, pose)
                            push()
                        },
                        valueRange = -180f..180f,
                        modifier = Modifier.testTag("v3-pose-axis-$ax")
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PoseStore.PRESETS.keys.forEach { p ->
                FilterChip(
                    selected = preset == p,
                    onClick = {
                        preset = p
                        v2RunBg {
                            pose = PoseStore.PRESETS[p]!!.mapValues { it.value.copyOf() }.toMutableMap()
                            PoseStore.save(cacheDir, docKey, pose)
                            push()
                        }
                    },
                    label = {
                        Text(
                            stringResource(
                                when (p) {
                                    "sitting" -> R.string.pose_sitting
                                    "running" -> R.string.pose_running
                                    else -> R.string.pose_standing
                                }
                            )
                        )
                    },
                    modifier = Modifier.testTag("v3-pose-preset-$p")
                )
            }
        }
        Text(
            stringResource(R.string.pose_hint),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// ---- §3 Texte sur tracé ----

@Composable
fun TextPathPanel(
    surface: EngineSurface,
    cacheDir: File,
    docKey: String,
    onStatus: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var sizePx by remember { mutableStateOf(48f) }
    var offset by remember { mutableStateOf(0f) }
    var bold by remember { mutableStateOf(false) }
    var layers by remember { mutableStateOf<List<Int>>(emptyList()) }
    var layer by remember { mutableStateOf(-1) }
    var pathIdx by remember { mutableStateOf(0) }
    var pathCount by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth().testTag("v3-textpath")
    ) {
        Text(
            stringResource(R.string.text_on_path),
            style = MaterialTheme.typography.titleSmall
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.text_on_path_text)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("v3-textpath-text")
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    v2RunBg {
                        layers = v2VectorLayers(surface)
                        layer = layers.firstOrNull() ?: -1
                        pathCount = if (layer >= 0) {
                            V2Bezier.read(surface, layer).size
                        } else {
                            0
                        }
                        pathIdx = 0
                    }
                },
                modifier = Modifier.testTag("v3-textpath-refresh")
            ) { Text(stringResource(R.string.v2_refresh)) }
            FilterChip(
                selected = bold,
                onClick = { bold = !bold },
                label = { Text(stringResource(R.string.text_on_path_bold)) }
            )
        }
        Text(
            stringResource(R.string.text_on_path_size, sizePx.toInt()),
            style = MaterialTheme.typography.bodySmall
        )
        Slider(
            value = sizePx,
            onValueChange = { sizePx = it },
            valueRange = 8f..256f,
            modifier = Modifier.testTag("v3-textpath-size")
        )
        Text(
            stringResource(R.string.text_offset, (offset * 100).toInt()),
            style = MaterialTheme.typography.bodySmall
        )
        Slider(
            value = offset,
            onValueChange = { offset = it },
            valueRange = 0f..1f,
            modifier = Modifier.testTag("v3-textpath-offset")
        )
        Text(
            stringResource(R.string.text_on_path_paths, layer, pathCount),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("v3-textpath-info")
        )
        Button(
            enabled = !busy,
            onClick = {
                busy = true
                v2RunBg {
                    try {
                        val li = layer
                        val paths = if (li >= 0) V2Bezier.read(surface, li) else emptyList()
                        if (text.isEmpty() || paths.isEmpty() || pathIdx !in paths.indices) {
                            onStatus(ctx.getString(R.string.v2_panel_failed))
                            return@v2RunBg
                        }
                        val target = v2ActiveLayer(surface)
                        if (target < 0) {
                            onStatus(ctx.getString(R.string.v2_panel_failed))
                            return@v2RunBg
                        }
                        val pts = paths[pathIdx].pts.map { (x, y, p) -> SnapPt(x, y, p) }
                        val (w, h) = surface.selectionSize()
                        val bmp = TextOnPath.renderToBitmap(
                            text, pts, w, h, sizePx, "#141414", offset, bold
                        )
                        if (bmp == null) {
                            onStatus(ctx.getString(R.string.v2_panel_failed))
                            return@v2RunBg
                        }
                        val png = File(cacheDir, "textpath-$docKey.png")
                        if (!TextOnPath.bitmapToPng(bmp, png)) {
                            onStatus(ctx.getString(R.string.v2_panel_failed))
                            return@v2RunBg
                        }
                        bmp.recycle()
                        if (!surface.blitPngIntoLayer(target, png.absolutePath)) {
                            onStatus(ctx.getString(R.string.v2_panel_failed))
                            return@v2RunBg
                        }
                        TextOnPath.metaFor(li, offset).forEach { (k, v) ->
                            surface.setLayerMeta(target, k, v)
                        }
                        onStatus(ctx.getString(R.string.filter_applied))
                    } finally {
                        busy = false
                    }
                }
            },
            modifier = Modifier.testTag("v3-textpath-commit")
        ) { Text(stringResource(R.string.text_on_path_commit)) }
    }
}

// ---- §1.2 Épreuvage sortie ----

@Composable
fun ProofPanel(
    surface: EngineSurface,
    onStatus: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var enabled by remember { mutableStateOf(false) }
    var cmyk by remember { mutableStateOf(true) }
    var gamut by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var flagged by remember { mutableStateOf(-1) }

    Column(
        modifier = modifier.fillMaxWidth().testTag("v3-proof")
    ) {
        Text(
            stringResource(R.string.proof_title),
            style = MaterialTheme.typography.titleSmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = enabled,
                onClick = { enabled = !enabled },
                label = { Text(stringResource(R.string.proof_enable)) },
                modifier = Modifier.testTag("v3-proof-toggle")
            )
            FilterChip(
                selected = cmyk,
                onClick = { cmyk = true },
                label = { Text(stringResource(R.string.proof_cmyk)) }
            )
            FilterChip(
                selected = gamut,
                onClick = { gamut = !gamut },
                label = { Text(stringResource(R.string.proof_gamut)) },
                modifier = Modifier.testTag("v3-proof-gamut")
            )
        }
        if (enabled) {
            Button(
                onClick = {
                    surface.pullFrameFull(
                        surface.currentFrameIndex()
                    ) { bmp ->
                        if (bmp == null) {
                            onStatus("proof: empty frame")
                            return@pullFrameFull
                        }
                        val w = bmp.width
                        val h = bmp.height
                        val px = IntArray(w * h)
                        bmp.getPixels(px, 0, w, 0, 0, w, h)
                        val sim = CmykProof.preview(px, gamutWarning = gamut)
                        var n = 0
                        if (gamut) {
                            for (i in px.indices) {
                                val r = (px[i] shr 16) and 0xFF
                                val g = (px[i] shr 8) and 0xFF
                                val b = px[i] and 0xFF
                                if (CmykProof.isOutOfGamut(r, g, b)) n++
                            }
                        }
                        flagged = n
                        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        out.setPixels(sim, 0, w, 0, 0, w, h)
                        preview?.recycle()
                        preview = out
                    }
                },
                modifier = Modifier.testTag("v3-proof-render")
            ) { Text(stringResource(R.string.proof_render)) }
            preview?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .testTag("v3-proof-image")
                )
            }
            if (flagged >= 0) {
                Text(
                    stringResource(R.string.proof_flagged, flagged),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                stringResource(R.string.proof_hint),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

// ---- §6 Imports ouverts ----

@Composable
fun ImportPanel(
    surface: EngineSurface,
    cacheDir: File,
    onStatus: (String) -> Unit,
    onPickTiff: () -> Unit,
    onPickPdf: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    var svgText by remember { mutableStateOf("") }
    var svgInfo by remember { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxWidth().testTag("v3-import")
    ) {
        Text(
            stringResource(R.string.import_title),
            style = MaterialTheme.typography.titleSmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onPickTiff,
                modifier = Modifier.testTag("v3-import-tiff")
            ) { Text(stringResource(R.string.import_tiff)) }
            OutlinedButton(
                onClick = onPickPdf,
                modifier = Modifier.testTag("v3-import-pdf")
            ) { Text(stringResource(R.string.import_pdf)) }
        }
        OutlinedTextField(
            value = svgText,
            onValueChange = { svgText = it },
            label = { Text(stringResource(R.string.import_svg_hint)) },
            modifier = Modifier.fillMaxWidth().testTag("v3-import-svg-text")
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    v2RunBg {
                        val res = SvgImport.parse(svgText)
                        if (res.paths.isEmpty()) {
                            onStatus(ctx.getString(R.string.v2_panel_failed))
                            return@v2RunBg
                        }
                        val layer = v2EnsureVectorLayer(surface, "SVG")
                        if (layer < 0) {
                            onStatus(ctx.getString(R.string.v2_panel_failed))
                            return@v2RunBg
                        }
                        var n = 0
                        for (p in res.paths) {
                            val pts = p.points.map { q -> Pair(q.x, q.y) }
                            val json = buildVectorJson(pts, p.widthPx)
                            if (surface.appendVectorPath(layer, json) >= 0) n++
                        }
                        svgInfo = ctx.getString(
                            R.string.import_svg_done, n, res.ignoredText
                        )
                    }
                },
                modifier = Modifier.testTag("v3-import-svg")
            ) { Text(stringResource(R.string.import_svg)) }
        }
        if (svgInfo.isNotEmpty()) {
            Text(svgInfo, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            stringResource(R.string.import_hint),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * Minimal nAppendVectorPath payload (SpeedBurst.pathJson schema).
 * Stroke width rides the pressure channel: widthAt(p) = 16*(0.25+0.75p)
 * at the default curve, so p = ((w/16)-0.25)/0.75 (clamped, honest
 * approximation documented here).
 */
fun buildVectorJson(pts: List<Pair<Float, Float>>, width: Float): String {
    val p = (((width / 16f) - 0.25f) / 0.75f).coerceIn(0.05f, 1f)
    val sb = StringBuilder("{\"pts\":[")
    pts.forEachIndexed { i, (x, y) ->
        if (i > 0) sb.append(',')
        sb.append(
            String.format(
                Locale.US, "{\"x\":%.1f,\"y\":%.1f,\"p\":%.2f}", x, y, p
            )
        )
    }
    sb.append("]}")
    return sb.toString()
}

// ---- §7 Aide raccourcis ----

@Composable
fun ShortcutHelpDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val fr = try {
        ctx.resources.configuration.locales[0].language == "fr"
    } catch (_: Exception) {
        false
    }
    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .testTag("v3-shortcuts-help")
            ) {
                Text(
                    stringResource(R.string.shortcut_title),
                    style = MaterialTheme.typography.titleMedium
                )
                ShortcutMap.BINDINGS.forEach { b ->
                    Text(
                        "${keyName(b.keyCode)}${if (b.ctrl) " + Ctrl" else ""}" +
                            "${if (b.shift) " + Maj" else ""} — " +
                            if (fr) b.labelFr else b.labelEn,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

private fun keyName(code: Int): String = when (code) {
    android.view.KeyEvent.KEYCODE_Z -> "Z"
    android.view.KeyEvent.KEYCODE_Y -> "Y"
    android.view.KeyEvent.KEYCODE_B -> "B"
    android.view.KeyEvent.KEYCODE_E -> "E"
    android.view.KeyEvent.KEYCODE_M -> "M"
    android.view.KeyEvent.KEYCODE_I -> "I"
    android.view.KeyEvent.KEYCODE_F -> "F"
    android.view.KeyEvent.KEYCODE_SPACE -> "Space"
    android.view.KeyEvent.KEYCODE_EQUALS -> "+"
    android.view.KeyEvent.KEYCODE_PLUS -> "+"
    android.view.KeyEvent.KEYCODE_MINUS -> "−"
    android.view.KeyEvent.KEYCODE_LEFT_BRACKET -> "["
    android.view.KeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
    android.view.KeyEvent.KEYCODE_PAGE_DOWN -> "PgDn"
    android.view.KeyEvent.KEYCODE_PAGE_UP -> "PgUp"
    android.view.KeyEvent.KEYCODE_SLASH -> "?"
    else -> "?"
}
