// OpenClip T2 — vector tools + balloon layer (app-side).
//
// Retrace / thicken / connect arming (EngineSurface.vectorTool),
// trajectories overlay, per-path style + vertex toggle, SVG color
// export, and the dedicated bubble layer (nSetLayerBubble). Ops are
// index-based engine calls (1 undo each); ids are re-read from
// nVectorPaths before every gesture (no id caching — ARCHITECTURE.md
// §T2). Overlay-only UI, ≥48dp targets.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.openclip.R
import java.util.Locale
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.json.JSONObject

/** Armed vector sub-tool (null = ink/eraser as usual). */
enum class T2VectorTool { RETRACE, THICKEN, CONNECT }

/** Per-path style (T2 §4): tip + size factor + ink. */
data class VectorStyle(
    val tip: String = "round", // round|flat
    val sizeMul: Float = 1f, // 0.25..4
    val colorHex: String = "#000000"
)

/** Bubble fiche mirror (T2 §5 — composite-time, meta, not in undo). */
data class BubbleLayer(
    val shape: String, // ellipse|rounded
    val frame: FloatArray, // x,y,w,h
    val tail: String, // bx,by,tx,ty or ""
    val fill: String = "#FFFFFF",
    val stroke: String = "#000000",
    val strokeW: Float = 4f,
    val textLayer: Int = -1
) {
    fun toJson(): String {
        val f = frame
        val fs = if (f.size == 4) {
            String.format(Locale.US, "\"%.1f,%.1f,%.1f,%.1f\"", f[0], f[1], f[2], f[3])
        } else "\"0,0,0,0\""
        return "{\"shape\":\"$shape\",\"frame\":$fs,\"tail\":\"$tail\"," +
            "\"fill\":\"$fill\",\"stroke\":\"$stroke\"," +
            "\"strokeW\":$strokeW,\"textLayer\":$textLayer}"
    }
}

object T2Vector {
    data class VPath(
        val pts: List<Triple<Float, Float, Float>>,
        val widths: List<Float>,
        val kind: String,
        val tip: String = "round",
        val sizeMul: Float = 1f,
        val colorHex: String = "#000000",
        val corners: List<Int> = emptyList()
    )

    /** Parse nVectorPaths (tolerant — old getters lack style keys). */
    fun readPaths(raw: String): List<VPath> {
        val out = mutableListOf<VPath>()
        try {
            val root = JSONObject(raw)
            val arr = root.optJSONArray("paths") ?: return out
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val pa = p.optJSONArray("pts") ?: continue
                val pts = mutableListOf<Triple<Float, Float, Float>>()
                for (k in 0 until pa.length()) {
                    val q = pa.optJSONArray(k) ?: continue
                    pts.add(
                        Triple(
                            q.optDouble(0, 0.0).toFloat(),
                            q.optDouble(1, 0.0).toFloat(),
                            q.optDouble(2, 1.0).toFloat()
                        )
                    )
                }
                val wa = p.optJSONArray("widths")
                val widths = mutableListOf<Float>()
                if (wa != null) {
                    for (k in 0 until wa.length()) {
                        widths.add(wa.optDouble(k, 2.0).toFloat())
                    }
                }
                val ca = p.optJSONArray("corners")
                val corners = mutableListOf<Int>()
                if (ca != null) {
                    for (k in 0 until ca.length()) corners.add(ca.optInt(k, 0))
                }
                out.add(
                    VPath(
                        pts, widths, p.optString("kind", "stroke"),
                        tip = p.optString("tip", "round"),
                        sizeMul = p.optDouble("size", 1.0).toFloat(),
                        colorHex = p.optString("color", "#000000"),
                        corners = corners
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** Capture polyline → pathJson (keeps the target's style — engine). */
    fun captureJson(pts: List<Triple<Float, Float, Float>>): String {
        val sb = StringBuilder("{\"pts\":[")
        pts.forEachIndexed { i, (x, y, pr) ->
            if (i > 0) sb.append(',')
            sb.append(
                String.format(Locale.US, "{\"x\":%.1f,\"y\":%.1f,\"p\":%.2f}", x, y, pr)
            )
        }
        return sb.append("]}").toString()
    }

    /** Styled pathJson for appends / style re-application (T2 §4). */
    fun styledJson(pts: List<Triple<Float, Float, Float>>, style: VectorStyle): String {
        val base = captureJson(pts)
        return base.dropLast(1) + String.format(
            Locale.US, ",\"tip\":\"%s\",\"size\":%.2f,\"color\":\"%s\"}",
            if (style.tip == "flat") "flat" else "round",
            style.sizeMul.coerceIn(0.25f, 4f), style.colorHex
        )
    }

    private fun dist2(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return dx * dx + dy * dy
    }

    private fun segDist2(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val l2 = dx * dx + dy * dy
        if (l2 < 1e-9f) return dist2(px, py, ax, ay)
        var t = ((px - ax) * dx + (py - ay) * dy) / l2
        t = t.coerceIn(0f, 1f)
        return dist2(px, py, ax + t * dx, ay + t * dy)
    }

    /** Nearest path to a point (12px default like the engine seam). */
    fun nearestPath(paths: List<VPath>, at: Triple<Float, Float, Float>, maxDist: Float = 12f): Int {
        var best = -1
        var bestD = maxDist * maxDist
        for ((i, p) in paths.withIndex()) {
            if (p.kind == "eraser-stroke") continue // no retrace of erasers
            for (k in p.pts.indices) {
                val d = if (k == 0) dist2(at.first, at.second, p.pts[0].first, p.pts[0].second)
                else segDist2(
                    at.first, at.second,
                    p.pts[k - 1].first, p.pts[k - 1].second,
                    p.pts[k].first, p.pts[k].second
                )
                if (d < bestD) {
                    bestD = d
                    best = i
                }
            }
        }
        return best
    }

    /** Nearest path endpoint (for connect): (pathIdx, end 0|1). */
    fun nearestEndpoint(
        paths: List<VPath>,
        at: Triple<Float, Float, Float>,
        maxDist: Float = 24f
    ): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var bestD = maxDist * maxDist
        for ((i, p) in paths.withIndex()) {
            if (p.kind == "eraser-stroke" || p.pts.isEmpty()) continue
            val ends = listOf(0 to p.pts.first(), 1 to p.pts.last())
            for ((end, q) in ends) {
                val d = dist2(at.first, at.second, q.first, q.second)
                if (d < bestD) {
                    bestD = d
                    best = Pair(i, end)
                }
            }
        }
        return best
    }

    /** Gaussian falloff widths around a gesture (engine poses them). */
    fun falloffWidths(
        path: VPath,
        gesture: List<Triple<Float, Float, Float>>,
        sigma: Float,
        sign: Float
    ): List<Float> {
        val sig = sigma.coerceIn(4f, 128f)
        val out = mutableListOf<Float>()
        for (q in path.pts) {
            var d2min = Float.MAX_VALUE
            for (k in gesture.indices) {
                val g = gesture[k]
                val d2 = if (k == 0) dist2(q.first, q.second, g.first, g.second)
                else segDist2(
                    q.first, q.second,
                    gesture[k - 1].first, gesture[k - 1].second, g.first, g.second
                )
                if (d2 < d2min) d2min = d2
            }
            val base = path.widths.getOrNull(out.size) ?: 8f
            val k = 1f + sign * 0.9f * exp(-d2min / (2f * sig * sig)).toFloat()
            out.add((base * k).coerceIn(0.5f, 128f))
        }
        return out
    }

    /** Centerlines for the trajectories overlay probe. */
    fun trajectoryPolys(paths: List<VPath>): List<List<Pair<Float, Float>>> {
        return paths.filter { it.kind != "eraser-stroke" }
            .map { p -> p.pts.map { Pair(it.first, it.second) } }
            .filter { it.size >= 2 }
    }
}

@Composable
fun VectorStylePanel(
    surface: EngineSurface,
    inkHex: String,
    onStatus: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    var tip by remember { mutableStateOf("round") }
    var size by remember { mutableStateOf(1f) }
    var pathIdx by remember { mutableStateOf(0) }
    var info by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("t2-vector-style-panel")
    ) {
        Text(
            stringResource(R.string.vector_trajectories),
            style = MaterialTheme.typography.titleSmall
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            listOf("round" to R.string.vector_tip_round, "flat" to R.string.vector_tip_flat)
                .forEach { (v, label) ->
                    FilterChip(
                        selected = tip == v,
                        onClick = { tip = v },
                        label = { Text(stringResource(label)) },
                        modifier = Modifier.height(48.dp).testTag("t2-vector-tip-$v")
                    )
                }
        }
        Text(
            stringResource(R.string.vector_size, size),
            style = MaterialTheme.typography.labelSmall
        )
        Slider(
            value = size,
            onValueChange = { size = it },
            valueRange = 0.25f..4f,
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("t2-vector-size")
        )
        Text(
            stringResource(R.string.vector_style_apply),
            style = MaterialTheme.typography.labelSmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    v2RunBg {
                        val layer = v2VectorLayers(surface).firstOrNull()
                        if (layer == null) {
                            onStatus(ctx.getString(R.string.vector_too_few))
                            return@v2RunBg
                        }
                        val paths = T2Vector.readPaths(surface.vectorPaths(layer))
                        if (pathIdx !in paths.indices) {
                            onStatus(ctx.getString(R.string.vector_too_few))
                            return@v2RunBg
                        }
                        val p = paths[pathIdx]
                        val style = VectorStyle(tip, size, inkHex)
                        val ok = surface.replaceVectorPath(
                            layer, pathIdx,
                            T2Vector.styledJson(p.pts, style)
                        )
                        info = "style#$pathIdx=$ok"
                        if (!ok) onStatus(ctx.getString(R.string.v2_panel_failed))
                    }
                },
                modifier = Modifier.height(48.dp).testTag("t2-vector-style-apply")
            ) { Text(stringResource(R.string.vector_style_apply)) }
            val prevDesc = stringResource(R.string.a11y_prev)
            val nextDesc = stringResource(R.string.a11y_next)
            OutlinedButton(
                onClick = { pathIdx = maxOf(0, pathIdx - 1) },
                modifier = Modifier.height(48.dp).testTag("t2-vector-path-prev")
                    .semantics { contentDescription = prevDesc }
            ) { Text("−") }
            Text(
                "#$pathIdx $info",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
            OutlinedButton(
                onClick = { pathIdx += 1 },
                modifier = Modifier.height(48.dp).testTag("t2-vector-path-next")
                    .semantics { contentDescription = nextDesc }
            ) { Text("+") }
        }
    }
}

@Composable
fun VectorOpsPanel(
    surface: EngineSurface,
    onStatus: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    var inflate by remember { mutableStateOf(true) }
    var tol by remember { mutableStateOf(12f) }
    var traj by remember { mutableStateOf(false) }
    var cornerPath by remember { mutableStateOf(0) }
    var cornerPt by remember { mutableStateOf(0) }

    fun toolLabel(t: T2VectorTool?): String {
        return when (t) {
            T2VectorTool.RETRACE -> ctx.getString(R.string.vector_retrace)
            T2VectorTool.THICKEN -> ctx.getString(R.string.vector_thicken)
            T2VectorTool.CONNECT -> ctx.getString(R.string.vector_connect)
            null -> "—"
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("t2-vector-ops-panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            stringResource(R.string.vector_trajectories),
            style = MaterialTheme.typography.titleSmall
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            listOf(
                T2VectorTool.RETRACE to R.string.vector_retrace,
                T2VectorTool.THICKEN to R.string.vector_thicken,
                T2VectorTool.CONNECT to R.string.vector_connect
            ).forEach { (t, label) ->
                FilterChip(
                    selected = surface.vectorTool == t,
                    onClick = {
                        surface.vectorTool = if (surface.vectorTool == t) null else t
                    },
                    label = { Text(stringResource(label)) },
                    modifier = Modifier.height(48.dp).testTag("t2-vector-tool-${t.name.lowercase()}")
                )
            }
        }
        if (surface.vectorTool == T2VectorTool.THICKEN) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val inflateDesc = stringResource(R.string.a11y_increase)
                FilterChip(
                    selected = inflate,
                    onClick = {
                        inflate = true
                        surface.vectorThickenSign = 1f
                    },
                    label = { Text("+") },
                    modifier = Modifier.height(48.dp).testTag("t2-vector-inflate")
                        .semantics { contentDescription = inflateDesc }
                )
                val deflateDesc = stringResource(R.string.a11y_decrease)
                FilterChip(
                    selected = !inflate,
                    onClick = {
                        inflate = false
                        surface.vectorThickenSign = -1f
                    },
                    label = { Text("−") },
                    modifier = Modifier.height(48.dp).testTag("t2-vector-deflate")
                        .semantics { contentDescription = deflateDesc }
                )
            }
        }
        if (surface.vectorTool == T2VectorTool.CONNECT) {
            Text(
                "tol ${tol.roundToInt()}px",
                style = MaterialTheme.typography.labelSmall
            )
            Slider(
                value = tol,
                onValueChange = {
                    tol = it
                    surface.vectorConnectTol = it
                },
                valueRange = 4f..48f,
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("t2-vector-tol")
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.vector_trajectories),
                style = MaterialTheme.typography.labelSmall
            )
            Switch(
                checked = traj,
                onCheckedChange = { on ->
                    traj = on
                    v2RunBg {
                        val layer = v2VectorLayers(surface).firstOrNull() ?: -1
                        surface.refreshTrajectories(if (on) layer else -1)
                    }
                },
                modifier = Modifier.testTag("t2-vector-traj")
            )
            Text(
                toolLabel(surface.vectorTool),
                style = MaterialTheme.typography.labelSmall
            )
        }
        // Vertex toggle (T2 §4): tap point = hard/smooth flip via
        // replace-with-corners (geometry unchanged, M6 grid stable).
        Text(
            stringResource(R.string.vector_vertex_sharp) + "/" +
                stringResource(R.string.vector_vertex_smooth),
            style = MaterialTheme.typography.labelSmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val ptPrevDesc = stringResource(R.string.a11y_prev)
            OutlinedButton(
                onClick = { cornerPt = maxOf(0, cornerPt - 1) },
                modifier = Modifier.height(48.dp).testTag("t2-vector-pt-prev")
                    .semantics { contentDescription = ptPrevDesc }
            ) { Text("−") }
            OutlinedButton(
                onClick = {
                    v2RunBg {
                        val layer = v2VectorLayers(surface).firstOrNull()
                        if (layer == null) {
                            onStatus(ctx.getString(R.string.vector_too_few))
                            return@v2RunBg
                        }
                        val paths = T2Vector.readPaths(surface.vectorPaths(layer))
                        if (cornerPath !in paths.indices) {
                            onStatus(ctx.getString(R.string.vector_too_few))
                            return@v2RunBg
                        }
                        val p = paths[cornerPath]
                        if (cornerPt !in p.pts.indices) {
                            onStatus(ctx.getString(R.string.vector_too_few))
                            return@v2RunBg
                        }
                        val corners = p.pts.indices.map { i ->
                            if (i == cornerPt) {
                                if (p.corners.getOrNull(i) == 1) 0 else 1
                            } else p.corners.getOrNull(i) ?: 0
                        }
                        val sb = StringBuilder("{\"pts\":[")
                        p.pts.forEachIndexed { i, (x, y, pr) ->
                            if (i > 0) sb.append(',')
                            sb.append(
                                String.format(
                                    Locale.US,
                                    "{\"x\":%.1f,\"y\":%.1f,\"p\":%.2f}",
                                    x, y, pr
                                )
                            )
                        }
                        sb.append("],\"corners\":[")
                        corners.forEachIndexed { i, c ->
                            if (i > 0) sb.append(',')
                            sb.append(c)
                        }
                        sb.append("]}")
                        if (!surface.replaceVectorPath(layer, cornerPath, sb.toString())) {
                            onStatus(ctx.getString(R.string.v2_panel_failed))
                        }
                    }
                },
                modifier = Modifier.height(48.dp).testTag("t2-vector-corner-toggle")
            ) { Text("#$cornerPt") }
            val ptNextDesc = stringResource(R.string.a11y_next)
            OutlinedButton(
                onClick = { cornerPt += 1 },
                modifier = Modifier.height(48.dp).testTag("t2-vector-pt-next")
                    .semantics { contentDescription = ptNextDesc }
            ) { Text("+") }
        }
    }
}

@Composable
fun BubblePanel(
    surface: EngineSurface,
    onStatus: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    var shape by remember { mutableStateOf("ellipse") }
    var frame by remember { mutableStateOf("8,8,48,32") }
    var tail by remember { mutableStateOf("") }
    var fill by remember { mutableStateOf("#FFFFFF") }
    var stroke by remember { mutableStateOf("#000000") }
    var strokeW by remember { mutableStateOf(4f) }
    val textLayers = remember { mutableStateOf(listOf<Int>()) }
    var textSel by remember { mutableStateOf(-1) }

    Text(
        stringResource(R.string.bubble_layer),
        style = MaterialTheme.typography.titleSmall
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("t2-bubble-panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            listOf(
                "ellipse" to R.string.v2_bubble_ellipse,
                "rounded" to R.string.v2_bubble_rounded
            ).forEach { (v, label) ->
                FilterChip(
                    selected = shape == v,
                    onClick = { shape = v },
                    label = { Text(stringResource(label)) },
                    modifier = Modifier.height(48.dp).testTag("t2-bubble-shape-$v")
                )
            }
        }
        OutlinedTextField(
            value = frame,
            onValueChange = { frame = it },
            label = { Text(stringResource(R.string.v2_bubble_frame)) },
            modifier = Modifier.fillMaxWidth().testTag("t2-bubble-frame")
        )
        OutlinedTextField(
            value = tail,
            onValueChange = { tail = it },
            label = { Text(stringResource(R.string.bubble_tail)) },
            modifier = Modifier.fillMaxWidth().testTag("t2-bubble-tail")
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = fill,
                onValueChange = { fill = it },
                label = { Text(stringResource(R.string.bubble_fill)) },
                modifier = Modifier.weight(1f).testTag("t2-bubble-fill")
            )
            OutlinedTextField(
                value = stroke,
                onValueChange = { stroke = it },
                label = { Text(stringResource(R.string.bubble_stroke)) },
                modifier = Modifier.weight(1f).testTag("t2-bubble-stroke")
            )
        }
        Text(
            stringResource(R.string.bubble_stroke_w, strokeW),
            style = MaterialTheme.typography.labelSmall
        )
        Slider(
            value = strokeW,
            onValueChange = { strokeW = it },
            valueRange = 1f..32f,
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("t2-bubble-stroke-w")
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    v2RunBg {
                        textLayers.value = textLayerIndexes(surface)
                        if (textSel !in textLayers.value) {
                            textSel = textLayers.value.firstOrNull() ?: -1
                        }
                    }
                },
                modifier = Modifier.height(48.dp).testTag("t2-bubble-text-scan")
            ) { Text(stringResource(R.string.bubble_link_text)) }
            Text(
                if (textSel >= 0) "#$textSel" else "—",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
        }
        Text(
            stringResource(R.string.bubble_drag_hint),
            style = MaterialTheme.typography.labelSmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    v2RunBg {
                        val fiche = BubbleLayer(
                            shape, frame.split(",")
                                .mapNotNull { it.trim().toFloatOrNull() }
                                .toFloatArray(),
                            tail, fill, stroke, strokeW, textSel
                        )
                        if (fiche.frame.size != 4) {
                            onStatus(ctx.getString(R.string.v2_panel_failed))
                            return@v2RunBg
                        }
                        val layer = surface.addVectorLayer("Bubble")
                        if (layer < 0 ||
                            !surface.setLayerBubble(layer, fiche.toJson())
                        ) {
                            onStatus(ctx.getString(R.string.v2_panel_failed))
                        }
                    }
                },
                modifier = Modifier.height(48.dp).testTag("t2-bubble-commit")
            ) { Text(stringResource(R.string.v2_bubble_title)) }
            TextButton(
                onClick = {
                    v2RunBg {
                        val layer = try {
                            JSONObject(surface.layerState()).optInt("active", -1)
                        } catch (_: Exception) {
                            -1
                        }
                        if (layer >= 0) surface.setLayerBubble(layer, null)
                    }
                },
                modifier = Modifier.height(48.dp).testTag("t2-bubble-clear")
            ) { Text(stringResource(R.string.cancel)) }
        }
    }
}

/** Top-level text-carrying layers (for the bubble link selector). */
fun textLayerIndexes(surface: EngineSurface): List<Int> {
    return try {
        val st = JSONObject(surface.layerState())
        val arr = st.optJSONArray("layers") ?: return emptyList()
        val out = mutableListOf<Int>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val s = e.optJSONObject("self") ?: e
            if (s.optJSONObject("text") != null) out.add(i)
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}

/** T2 §5: stash live text fiches as commit-ready JSON (for the OFF
 * path of "include editable text" — meta-only, pixels untouched). */
fun stashTextFiches(es: EngineSurface): Map<Int, String> {
    val out = mutableMapOf<Int, String>()
    try {
        val st = JSONObject(es.layerState())
        val arr = st.optJSONArray("layers") ?: return out
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val s = e.optJSONObject("self") ?: e
            if (s.optJSONObject("text") != null) {
                es.layerTextForTest(i)?.let { out[i] = it.toJson() }
            }
        }
    } catch (_: Exception) {
    }
    return out
}

/** Restore stashed text fiches (finally-safe: best effort each). */
fun restoreTextFiches(es: EngineSurface, stashed: Map<Int, String>) {
    for ((i, json) in stashed) {
        try {
            es.setLayerText(i, json)
        } catch (_: Exception) {
        }
    }
}

/** Clear all text fiches (OFF path — caller must restore). */
fun clearTextFiches(es: EngineSurface) {
    for ((i, _) in stashTextFiches(es)) {
        try {
            es.setLayerText(i, null)
        } catch (_: Exception) {
        }
    }
}
