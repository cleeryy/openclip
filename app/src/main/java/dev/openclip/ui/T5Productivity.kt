// OpenClip T5 — productivité (spec §7, app-side 100 %, 0 JNI).
// Macros (journal MacroJournal + bibliothèque macros.json v1),
// aligner/répartir (masque-plein + nSelectionBlit move, V+1 §1.5
// pattern), panneau historique (session-only), panneau info (1 Hz).
package dev.openclip.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.openclip.R
import java.io.File
import org.json.JSONObject

/** Session-only align multi-selection (LayerPanel ticks, AlignPanel acts). */
object T5AlignSelection {
    val selected = mutableSetOf<Int>()
    fun toggle(i: Int) {
        if (!selected.add(i)) selected.remove(i)
    }
    fun clear() = selected.clear()
}

/**
 * Replay a macro on [es]: re-emit ops in order (spec §7.1). Indexed
 * ops are guarded (index must exist or the replay stops); unknown ops
 * stop the replay. Returns the per-line report (empty = full success).
 */
fun replayMacro(es: EngineSurface, macro: Macro): List<String> {
    val errors = mutableListOf<String>()
    val layerCount = try {
        JSONObject(es.layerState()).optJSONArray("layers")?.length() ?: 0
    } catch (_: Exception) {
        0
    }
    for ((k, op) in macro.ops.withIndex()) {
        val ok = try {
            when (op.op) {
                "applyFilter" -> {
                    val o = JSONObject(op.params)
                    es.applyFilterSync(
                        o.optString("name"),
                        o.optString("params", "{}")
                    )
                }
                "applyTone" -> es.applyTone(op.params)
                "setLayerBlend" -> {
                    val o = JSONObject(op.params)
                    val i = o.optInt("index", -1)
                    if (i < 0 || i >= layerCount) false
                    else es.setLayerBlend(i, o.optString("name"))
                }
                "setLayerOpacity" -> {
                    val o = JSONObject(op.params)
                    val i = o.optInt("index", -1)
                    if (i < 0 || i >= layerCount) false
                    else es.setLayerOpacity(i, o.optDouble("opacity", 1.0).toFloat())
                }
                "setLayerVisibility" -> {
                    val o = JSONObject(op.params)
                    val i = o.optInt("index", -1)
                    if (i < 0 || i >= layerCount) false
                    else es.setLayerVisibility(i, o.optBoolean("visible", true))
                }
                "setLayerAdjustment" -> {
                    val o = JSONObject(op.params)
                    val i = o.optInt("index", -1)
                    if (i < 0 || i >= layerCount) false
                    else es.setLayerAdjustment(i, o.optString("json"))
                }
                "setLayerFlags" -> {
                    val o = JSONObject(op.params)
                    val i = o.optInt("index", 0)
                    if (i < 0 || i >= layerCount) false
                    else es.setLayerFlags(i, o.optString("json", "{}"))
                }
                "setLayerFill" -> {
                    val o = JSONObject(op.params)
                    val i = o.optInt("index", 0)
                    if (i < 0 || i >= layerCount) false
                    else es.setLayerFill(i, o.optString("json"))
                }
                "floodFill" -> {
                    val o = JSONObject(op.params)
                    es.floodFillAt(
                        o.optInt("x", 0), o.optInt("y", 0),
                        o.optString("color", "#000000"),
                        o.optString("json", "{}")
                    )
                }
                "paintGradient" -> es.paintGradientJson(op.params)
                "stampShape" -> es.stampShapeJson(op.params)
                "selectionBlit" -> es.selectionBlit(op.params)
                "puppetWarp" -> {
                    val o = JSONObject(op.params)
                    es.puppetWarp(o.optInt("index", 0), o.optString("json", "{}"))
                }
                "addLayer" -> es.addLayerNamed(op.params.ifEmpty { "Layer" }) >= 0
                else -> false
            }
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            errors.add("[$k] ${op.op}")
            break  // miss = stop + report, never silent (spec §7.1)
        }
    }
    return errors
}

/** Bounding box of non-transparent pixels (engine px, full-res solo). */
fun t5LayerBBox(es: EngineSurface, index: Int): IntArray? {
    val bmp: Bitmap = es.soloRenderFull(index) ?: return null
    try {
        val w = bmp.width
        val h = bmp.height
        var x0 = w
        var y0 = h
        var x1 = -1
        var y1 = -1
        for (y in 0 until h step 2) {
            for (x in 0 until w step 2) {
                if ((bmp.getPixel(x, y) ushr 24) != 0) {
                    if (x < x0) x0 = x
                    if (y < y0) y0 = y
                    if (x > x1) x1 = x
                    if (y > y1) y1 = y
                }
            }
        }
        if (x1 < 0) return null
        return intArrayOf(x0, y0, x1, y1)
    } finally {
        try {
            bmp.recycle()
        } catch (_: Exception) {
        }
    }
}

/**
 * Aligner/répartir (spec §7.1): 6 aligns + 3 distributes over the
 * session multi-selection. Executed through the existing full-mask +
 * nSelectionBlit move path (masque blanc pleine page posé, blit
 * translaté, 1 pas par calque — 0 JNI neuf). Returns moved count.
 */
fun t5Align(
    es: EngineSurface,
    cacheDir: File,
    op: String,
    useCanvasRef: Boolean
): Int {
    val ids = T5AlignSelection.selected.sorted()
    if (ids.size < 2 && !op.startsWith("grid")) return 0
    val (cw, ch) = es.canvasDims()
    val boxes = ids.mapNotNull { i -> t5LayerBBox(es, i)?.let { Pair(i, it) } }
    if (boxes.size < 2 && !op.startsWith("grid")) return 0
    // Reference: canvas rect or first selected bbox.
    val ref = if (useCanvasRef) intArrayOf(0, 0, cw - 1, ch - 1)
    else boxes.firstOrNull()?.second ?: return 0
    val targets = mutableMapOf<Int, Pair<Int, Int>>()
    when (op) {
        "left" -> for ((i, b) in boxes) targets[i] = Pair(ref[0] - b[0], 0)
        "center_h" -> for ((i, b) in boxes) {
            val cx = (ref[0] + ref[2]) / 2 - (b[0] + b[2]) / 2
            targets[i] = Pair(cx, 0)
        }
        "right" -> for ((i, b) in boxes) targets[i] = Pair(ref[2] - b[2], 0)
        "top" -> for ((i, b) in boxes) targets[i] = Pair(0, ref[1] - b[1])
        "center_v" -> for ((i, b) in boxes) {
            val cy = (ref[1] + ref[3]) / 2 - (b[1] + b[3]) / 2
            targets[i] = Pair(0, cy)
        }
        "bottom" -> for ((i, b) in boxes) targets[i] = Pair(0, ref[3] - b[3])
        "distribute_h" -> {
            val sorted = boxes.sortedBy { (it.second[0] + it.second[2]) / 2 }
            if (sorted.size >= 3) {
                val firstC = (sorted.first().second[0] + sorted.first().second[2]) / 2
                val lastC = (sorted.last().second[0] + sorted.last().second[2]) / 2
                val step = (lastC - firstC).toFloat() / (sorted.size - 1)
                for ((k, p) in sorted.withIndex()) {
                    val c = (p.second[0] + p.second[2]) / 2
                    targets[p.first] = Pair(((firstC + step * k) - c).toInt(), 0)
                }
            }
        }
        "distribute_v" -> {
            val sorted = boxes.sortedBy { (it.second[1] + it.second[3]) / 2 }
            if (sorted.size >= 3) {
                val firstC = (sorted.first().second[1] + sorted.first().second[3]) / 2
                val lastC = (sorted.last().second[1] + sorted.last().second[3]) / 2
                val step = (lastC - firstC).toFloat() / (sorted.size - 1)
                for ((k, p) in sorted.withIndex()) {
                    val c = (p.second[1] + p.second[3]) / 2
                    targets[p.first] = Pair(0, ((firstC + step * k) - c).toInt())
                }
            }
        }
        "grid" -> {
            val n = boxes.size
            if (n >= 2) {
                val cols = kotlin.math.ceil(kotlin.math.sqrt(n.toDouble())).toInt()
                val cellW = cw / cols
                val rows = (n + cols - 1) / cols
                val cellH = ch / rows
                for ((k, p) in boxes.withIndex()) {
                    val gx = (k % cols) * cellW + cellW / 2 -
                        (p.second[0] + p.second[2]) / 2
                    val gy = (k / cols) * cellH + cellH / 2 -
                        (p.second[1] + p.second[3]) / 2
                    targets[p.first] = Pair(gx, gy)
                }
            }
        }
        else -> return 0
    }
    // Full-page white mask (V+1 §1.5 pattern): pose once, move each
    // layer (1 step each), release.
    es.selectAll()
    val png = try {
        File.createTempFile("t5-align-", ".png", cacheDir)
    } catch (_: Exception) {
        return 0
    }
    var moved = 0
    try {
        if (!es.selectionToPng(png.absolutePath)) return 0
        if (!es.setSelectionMask(png.absolutePath)) return 0
        for ((i, d) in targets) {
            if (d.first == 0 && d.second == 0) continue
            es.setActiveLayer(i)
            val ok = es.selectionBlit(
                "{\"dx\":" + d.first + ",\"dy\":" + d.second +
                    ",\"mode\":\"move\"}"
            )
            if (ok) moved++
        }
    } finally {
        es.setSelectionMask(null)
        try {
            png.delete()
        } catch (_: Exception) {
        }
    }
    return moved
}

@Composable
fun MacroPanel(
    surface: EngineSurface,
    cacheDir: File,
    onDismiss: () -> Unit,
    onStatus: (String) -> Unit
) {
    var macros by remember { mutableStateOf(loadMacros(cacheDir)) }
    var recording by remember { mutableStateOf(MacroJournal.recording) }
    var report by remember { mutableStateOf("") }
    val emptyMsg = stringResource(R.string.macro_empty)
    val errPrefix = stringResource(R.string.macro_error)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.macro_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    if (recording) "● ${MacroJournal.pendingCount()}"
                    else report.ifEmpty { stringResource(R.string.macro_library) },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!recording) {
                        Button(
                            onClick = {
                                MacroJournal.start()
                                recording = true
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("macro-record")
                        ) { Text(stringResource(R.string.macro_record)) }
                    } else {
                        Button(
                            onClick = {
                                val ops = MacroJournal.stop()
                                recording = false
                                if (ops.isNotEmpty()) {
                                    val name = "macro-${macros.size + 1}"
                                    macros = macros + Macro(name, ops)
                                    saveMacros(cacheDir, macros)
                                } else {
                                    report = emptyMsg
                                }
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("macro-stop")
                        ) { Text(stringResource(R.string.macro_stop)) }
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .testTag("macro-list")
                ) {
                    items(macros, key = { it.name }) { m ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "${m.name} (${m.ops.size})",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    val errs = replayMacro(surface, m)
                                    report = if (errs.isEmpty()) ""
                                    else errPrefix +
                                        errs.joinToString(",")
                                    onStatus(report)
                                },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("macro-replay-${m.name}")
                            ) { Text(stringResource(R.string.macro_replay)) }
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
    }
}

private fun macrosFile(cacheDir: File): File = File(cacheDir, "macros.json")

private fun loadMacros(cacheDir: File): List<Macro> {
    return try {
        val f = macrosFile(cacheDir)
        if (!f.exists()) emptyList()
        else MacroStore.fromJson(f.readText())
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveMacros(cacheDir: File, macros: List<Macro>) {
    try {
        macrosFile(cacheDir).writeText(MacroStore.toJson(macros))
    } catch (_: Exception) {
    }
}

@Composable
fun AlignPanel(
    surface: EngineSurface,
    cacheDir: File,
    onDismiss: () -> Unit,
    onStatus: (String) -> Unit,
    onChanged: () -> Unit
) {
    var useCanvas by remember { mutableStateOf(true) }
    val ops = listOf(
        "left" to R.string.align_left,
        "center_h" to R.string.align_center_h,
        "right" to R.string.align_right,
        "top" to R.string.align_top,
        "center_v" to R.string.align_center_v,
        "bottom" to R.string.align_bottom,
        "distribute_h" to R.string.align_distribute_h,
        "distribute_v" to R.string.align_distribute_v,
        "grid" to R.string.align_grid
    )
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.align_title) +
                        " (${T5AlignSelection.selected.size})",
                    style = MaterialTheme.typography.titleMedium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = useCanvas,
                        onClick = { useCanvas = true },
                        label = { Text(stringResource(R.string.align_ref)) },
                        modifier = Modifier.testTag("align-ref-canvas")
                    )
                    FilterChip(
                        selected = !useCanvas,
                        onClick = { useCanvas = false },
                        label = { Text(stringResource(R.string.align_ref_first)) },
                        modifier = Modifier.testTag("align-ref-first")
                    )
                }
                ops.forEach { (op, res) ->
                    TextButton(
                        onClick = {
                            val n = t5Align(surface, cacheDir, op, useCanvas)
                            onStatus("$n")
                            onChanged()
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("align-$op")
                    ) { Text(stringResource(res)) }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

@Composable
fun HistoryPanel(
    onDismiss: () -> Unit,
    onJump: (steps: Int) -> Unit
) {
    val entries = remember { HistoryLog.snapshot() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.history_title) +
                        " (${entries.size} " +
                        stringResource(R.string.history_depth) + ")",
                    style = MaterialTheme.typography.titleMedium
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .testTag("history-list")
                ) {
                    items(entries.size) { k ->
                        val e = entries[k]
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                historyLabel(e.label),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { onJump(entries.size - 1 - k) },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("history-jump-$k")
                            ) { Text(stringResource(R.string.history_jump)) }
                        }
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

@Composable
private fun historyLabel(key: String): String = key

@Composable
fun InfoPanel(
    surface: EngineSurface,
    onDismiss: () -> Unit
) {
    val dims = remember { surface.canvasDims() }
    val cursor = remember { surface.cursorEngine() }
    val zoom = remember { surface.viewZoomPercent() }
    val (activeName, flags) = remember {
        try {
            val root = JSONObject(surface.layerState())
            val arr = root.optJSONArray("layers")
            val a = root.optInt("active", 0)
            val e = arr?.optJSONObject(a)
            val s = e?.optJSONObject("self") ?: e
            val f = s?.optJSONObject("flags")
            val name = s?.optString("name", "") ?: ""
            val fl = listOf(
                if (f?.optBoolean("locked", false) == true) "🔒" else "",
                if (f?.optBoolean("alphaLock", false) == true) "α" else "",
                if (f?.optBoolean("reference", false) == true) "R" else "",
                if (f?.optBoolean("draft", false) == true) "D" else "",
                if (s?.optJSONObject("fill") != null) "F" else ""
            ).filter { it.isNotEmpty() }.joinToString("")
            Pair(name, fl)
        } catch (_: Exception) {
            Pair("", "")
        }
    }
    // Approx live memory: canvas bytes × top-level layers (session
    // counter, documented estimate — tiles share the same budget).
    val memKb = remember {
        try {
            val n = JSONObject(surface.layerState())
                .optJSONArray("layers")?.length() ?: 0
            (dims.first * dims.second * 4 * n) / 1024
        } catch (_: Exception) {
            0
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .testTag("info-panel")
            ) {
                Text(
                    stringResource(R.string.info_title),
                    style = MaterialTheme.typography.titleMedium
                )
                val rows = listOf(
                    stringResource(R.string.info_cursor) to
                        (cursor?.let { "${it.first.toInt()},${it.second.toInt()}" } ?: "—"),
                    stringResource(R.string.info_canvas) to "${dims.first}×${dims.second}",
                    stringResource(R.string.info_zoom) to "$zoom%",
                    stringResource(R.string.info_brush) to
                        "${surface.brushRadiusEngine.toInt()}px",
                    stringResource(R.string.info_layer) to "$activeName $flags",
                    stringResource(R.string.info_memory) to "≈${memKb} Ko"
                )
                for (rv in rows) {
                    val k = rv.first
                    val v = rv.second
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            k, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(v, style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}
