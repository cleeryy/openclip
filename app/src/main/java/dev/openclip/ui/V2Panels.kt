// OpenClip V+2 — BD/manga + long-export panels (app-side only).
// Cases/bulles/vitesse (§1), lecture (§2.1). Thin Compose over V2Studio
// logic + the documented JNI; fiches persist via nSetLayerMeta (ORA).
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.openclip.R
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.json.JSONObject

// ---- Shared helpers ----

/** Active top-level layer index (-1 when none). */
fun v2ActiveLayer(es: EngineSurface): Int = try {
    val st = JSONObject(es.layerState())
    st.optInt("active", -1)
} catch (_: Exception) {
    -1
}

/** Fiche maps per top-level layer (from nLayerState "meta"). */
fun v2LayerMetas(es: EngineSurface): Map<Int, Map<String, String>> {
    val out = mutableMapOf<Int, Map<String, String>>()
    try {
        val st = JSONObject(es.layerState())
        val arr = st.optJSONArray("layers") ?: return out
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val s = e.optJSONObject("self") ?: e
            val meta = s.optJSONObject("meta")
            val m = mutableMapOf<String, String>()
            if (meta != null) {
                val keys = meta.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    m[k] = meta.optString(k, "")
                }
            }
            // T2 §5: the dedicated bubble fiche (nSetLayerBubble) is
            // projected onto the legacy meta keys so V+2 readers
            // (parseBubbleMeta) keep working on new files (same attr
            // names — the engine already migrates old files the other
            // way on load).
            val b = s.optJSONObject("bubble")
            if (b != null && !m.containsKey("x-openclip-bubble")) {
                m["x-openclip-bubble"] = b.optString("shape", "")
                m["x-openclip-bubble-frame"] = b.optString("frame", "")
                m["x-openclip-bubble-tail"] = b.optString("tail", "")
                m["x-openclip-bubble-text"] =
                    b.optInt("textLayer", -1).toString()
            }
            out[i] = m
        }
    } catch (_: Exception) {
    }
    return out
}

/** Top-level vector layer indexes (nLayerState "vector" flag). */
fun v2VectorLayers(es: EngineSurface): List<Int> {
    val out = mutableListOf<Int>()
    try {
        val st = JSONObject(es.layerState())
        val arr = st.optJSONArray("layers") ?: return out
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val s = e.optJSONObject("self") ?: e
            if (s.optBoolean("vector", false)) out.add(i)
        }
    } catch (_: Exception) {
    }
    return out
}

/** First vector layer, or a fresh one (for panels/bulles/speed). */
fun v2EnsureVectorLayer(es: EngineSurface, name: String): Int {
    val existing = v2VectorLayers(es)
    if (existing.isNotEmpty()) return existing[0]
    return es.addVectorLayer(name)
}

/** Upload the live selection as the engine mask; null when none. */
fun v2UploadSelection(
    es: EngineSurface,
    cacheDir: File
): String? {
    if (!es.hasSelection()) return null
    val png = try {
        File.createTempFile("v2-sel-", ".png", cacheDir)
    } catch (_: Exception) {
        return null
    }
    if (!es.selectionToPng(png.absolutePath)) {
        try {
            png.delete()
        } catch (_: Exception) {
        }
        return null
    }
    if (!es.setSelectionMask(png.absolutePath)) {
        try {
            png.delete()
        } catch (_: Exception) {
        }
        return null
    }
    return png.absolutePath
}

fun v2RunBg(run: () -> Unit) {
    Thread {
        try {
            run()
        } catch (_: Exception) {
        }
    }.start()
}

/** Direct fiche commit (tests + UI share it): vector strokes + meta.
 * One panel = one vector layer (one fiche per layer, ORA attrs). */
fun commitPanelRect(
    es: EngineSurface,
    fiche: PanelFiche
): Int {
    val layer = es.addVectorLayer("Panel ${fiche.order}")
    val pts: List<Pair<Float, Float>> = if (fiche.shape == "poly") {
        fiche.poly
    } else {
        val r = fiche.rect ?: return -1
        listOf(
            Pair(r[0], r[1]), Pair(r[0] + r[2], r[1]),
            Pair(r[0] + r[2], r[1] + r[3]), Pair(r[0], r[1] + r[3])
        )
    }
    if (pts.size < 3) return -1
    val closed = pts + pts[0]
    val json = SpeedBurst.pathJson(
        closed.map { (x, y) -> Triple(x, y, 1f) }
    )
    if (es.appendVectorPath(layer, json) < 0) return -1
    for ((k, v) in fiche.toMeta()) es.setLayerMeta(layer, k, v)
    return layer
}

/** Direct bubble commit (T2 §5 — ARCHITECTURE.md §T2): the V+2 vector
 * paths + meta convention is REPLACED by the dedicated bubble layer
 * fiche (nSetLayerBubble, composite-time, meta, not in undo). The
 * signature is kept so existing callers keep working. */
fun commitBubble(es: EngineSurface, fiche: BubbleFiche): Int {
    val layer = es.addVectorLayer("Bubble")
    if (layer < 0) return -1
    val tail = "${fiche.tailBase.first},${fiche.tailBase.second}," +
        "${fiche.tailTip.first},${fiche.tailTip.second}"
    val f = fiche.frame
    val json = "{\"shape\":\"${fiche.shape}\"," +
        "\"frame\":\"${f[0]},${f[1]},${f[2]},${f[3]}\"," +
        "\"tail\":\"$tail\",\"fill\":\"#FFFFFF\"," +
        "\"stroke\":\"#000000\",\"strokeW\":4," +
        "\"textLayer\":${fiche.textLayer}}"
    if (!es.setLayerBubble(layer, json)) return -1
    return layer
}

/** Push the current panel/bubble overlay (engine coords, rotation-aware
 * draw path like the ants). Returns the polylines for the probe. */
fun refreshPanelOverlay(es: EngineSurface): List<List<Pair<Float, Float>>> {
    val polys = mutableListOf<List<Pair<Float, Float>>>()
    // T2 §5: dedicated bubble fiches (composite-time balloons) also
    // project their frame into the overlay (legacy meta bubbles below
    // keep working through parseBubbleMeta).
    try {
        val st = JSONObject(es.layerState())
        val arr = st.optJSONArray("layers")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val s = e.optJSONObject("self") ?: e
                val b = s.optJSONObject("bubble") ?: continue
                val f = b.optString("frame", "").split(",")
                    .mapNotNull { it.trim().toFloatOrNull() }
                if (f.size != 4) continue
                val cx = f[0] + f[2] / 2f
                val cy = f[1] + f[3] / 2f
                polys.add(
                    (0 until 24).map { k ->
                        val a = k * 2f * PI.toFloat() / 24f
                        Pair(cx + f[2] / 2f * cos(a), cy + f[3] / 2f * sin(a))
                    }
                )
            }
        }
    } catch (_: Exception) {
    }
    for ((_, meta) in v2LayerMetas(es)) {
        parsePanelMeta(meta)?.let { p ->
            if (p.shape == "poly") polys.add(p.poly + p.poly[0])
            else {
                val r = p.rect
                if (r != null) polys.add(
                    listOf(
                        Pair(r[0], r[1]), Pair(r[0] + r[2], r[1]),
                        Pair(r[0] + r[2], r[1] + r[3]),
                        Pair(r[0], r[1] + r[3]), Pair(r[0], r[1])
                    )
                )
            }
        }
        parseBubbleMeta(meta)?.let { b ->
            val f = b.frame
            val cx = f[0] + f[2] / 2f
            val cy = f[1] + f[3] / 2f
            polys.add(
                (0 until 24).map { i ->
                    val a = i * 2f * PI.toFloat() / 24f
                    Pair(cx + f[2] / 2f * cos(a), cy + f[3] / 2f * sin(a))
                }
            )
            polys.add(listOf(b.tailBase, b.tailTip))
        }
    }
    es.v2Overlays = polys
    V2TestHooks.panelOverlay = polys
    return polys
}

private fun floatField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    tag: String,
    onCommit: () -> Unit = {}
): @Composable () -> Unit = {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(tag)
    )
}

// ---- §1 Cases / bulles / traits de vitesse + §2.1 lecture ----

@Composable
fun BdToolsPanel(
    surface: EngineSurface,
    docKey: String,
    cacheDir: File,
    onStatus: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    var shape by remember { mutableStateOf("rect") }
    var bgWhite by remember { mutableStateOf(false) }
    var tone by remember { mutableStateOf("none") }
    var density by remember { mutableStateOf(100) }
    var order by remember { mutableStateOf(0) }
    var rx by remember { mutableStateOf("16") }
    var ry by remember { mutableStateOf("16") }
    var rw by remember { mutableStateOf("224") }
    var rh by remember { mutableStateOf("160") }
    var polyPts by remember { mutableStateOf("16,16;240,16;240,176;16,176") }
    var bubbleShape by remember { mutableStateOf("ellipse") }
    var bubbleFrame by remember { mutableStateOf("32,200,180,90") }
    var bubbleTail by remember { mutableStateOf("120,290,90,340") }
    var bubbleTextLayer by remember { mutableStateOf("-1") }
    var spdX by remember { mutableStateOf("128") }
    var spdY by remember { mutableStateOf("128") }
    var spdAngle by remember { mutableStateOf(0f) }
    var spdLen by remember { mutableStateOf(120f) }
    var spdCount by remember { mutableStateOf(24) }
    var spdSeed by remember { mutableStateOf("7") }
    var reading by remember { mutableStateOf(loadReadingPref(cacheDir, docKey)) }
    var tick by remember { mutableStateOf(0) }
    @Suppress("UNUSED_EXPRESSION")
    tick

    fun err(res: Int) {
        onStatus(ctx.getString(res))
    }

    // No inner scroll: the dock column already scrolls (nested
    // verticalScroll crashes composition).
    Column(
        modifier = modifier.fillMaxWidth()
            .testTag("v2-bd-panel")
    ) {
        Text(
            stringResource(R.string.v2_panel_title),
            style = MaterialTheme.typography.titleSmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = shape == "rect",
                onClick = { shape = "rect" },
                label = { Text(stringResource(R.string.v2_rect)) },
                modifier = Modifier.testTag("v2-panel-rect")
            )
            FilterChip(
                selected = shape == "poly",
                onClick = { shape = "poly" },
                label = { Text(stringResource(R.string.v2_poly)) },
                modifier = Modifier.testTag("v2-panel-poly")
            )
            FilterChip(
                selected = bgWhite,
                onClick = { bgWhite = !bgWhite },
                label = { Text(stringResource(R.string.v2_panel_bg)) },
                modifier = Modifier.testTag("v2-panel-bg")
            )
        }
        if (shape == "rect") {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    Triple(rx, { s: String -> rx = s }, "X"),
                    Triple(ry, { s: String -> ry = s }, "Y"),
                    Triple(rw, { s: String -> rw = s }, "W"),
                    Triple(rh, { s: String -> rh = s }, "H")
                ).forEach { (v, set, lab) ->
                    OutlinedTextField(
                        value = v, onValueChange = set, label = { Text(lab) },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                }
            }
        } else {
            OutlinedTextField(
                value = polyPts, onValueChange = { polyPts = it },
                label = { Text(stringResource(R.string.v2_poly_pts)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("v2-panel-polypts")
            )
        }
        Text(stringResource(R.string.v2_tone))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("none", "hatch", "dots", "grid").forEach { t ->
                FilterChip(
                    selected = tone == t,
                    onClick = { tone = t },
                    label = { Text(t) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("crosshatch", "checker", "stripes").forEach { t ->
                FilterChip(
                    selected = tone == t,
                    onClick = { tone = t },
                    label = { Text(t) }
                )
            }
        }
        Text(stringResource(R.string.v2_density, density))
        Slider(
            value = density.toFloat(), onValueChange = { density = it.toInt() },
            valueRange = 0f..100f,
            modifier = Modifier.testTag("v2-density")
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            OutlinedButton(
                onClick = {
                    val fiche = if (shape == "poly") {
                        val pts = polyPts.split(";").mapNotNull { s ->
                            val q = s.split(",").mapNotNull { it.toFloatOrNull() }
                            if (q.size == 2) Pair(q[0], q[1]) else null
                        }
                        if (pts.size < 3) {
                            err(R.string.v2_bad_poly)
                            return@OutlinedButton
                        }
                        PanelFiche("poly", poly = pts, order = order, bgWhite = bgWhite, tone = tone, density = density)
                    } else {
                        val x = rx.toFloatOrNull()
                        val y = ry.toFloatOrNull()
                        val w = rw.toFloatOrNull()
                        val h = rh.toFloatOrNull()
                        if (x == null || y == null || w == null || h == null || w <= 0 || h <= 0) {
                            err(R.string.v2_bad_rect)
                            return@OutlinedButton
                        }
                        PanelFiche("rect", floatArrayOf(x, y, w, h), order = order, bgWhite = bgWhite, tone = tone, density = density)
                    }
                    v2RunBg {
                        val layer = commitPanelRect(surface, fiche)
                        if (layer < 0) err(R.string.v2_panel_failed)
                        else {
                            refreshPanelOverlay(surface)
                            order += 1
                            tick += 1
                        }
                    }
                },
                modifier = Modifier.testTag("v2-panel-create")
            ) { Text(stringResource(R.string.v2_panel_create)) }
            OutlinedButton(
                onClick = {
                    v2RunBg {
                        // Tone sous masque polygonal (pont V+1 §1.1 réutilisé :
                        // composite avant/après + mix app-side + load dans
                        // une trame dupliquée — reset undo annoncé).
                        val metas = v2LayerMetas(surface)
                        val target = metas.entries
                            .mapNotNull { (i, m) -> parsePanelMeta(m)?.let { Pair(i, it) } }
                            .maxByOrNull { it.second.order } ?: run {
                            err(R.string.v2_no_panel)
                            return@v2RunBg
                        }
                        if (target.second.tone == "none") {
                            err(R.string.v2_no_tone)
                            return@v2RunBg
                        }
                        val pts = if (target.second.shape == "poly") target.second.poly
                        else {
                            val r = target.second.rect ?: return@v2RunBg
                            listOf(
                                Pair(r[0], r[1]), Pair(r[0] + r[2], r[1]),
                                Pair(r[0] + r[2], r[1] + r[3]),
                                Pair(r[0], r[1] + r[3])
                            )
                        }
                        surface.selectPolygonEngine(pts)
                        surface.thinSelectionTo(target.second.density)
                        surface.applyToneInSelection(target.second.tone) { ok ->
                            surface.clearSelection()
                            if (!ok) err(R.string.filter_failed)
                        }
                    }
                },
                modifier = Modifier.testTag("v2-panel-tone")
            ) { Text(stringResource(R.string.v2_panel_tone)) }
        }

        Text(
            stringResource(R.string.v2_bubble_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = bubbleShape == "ellipse",
                onClick = { bubbleShape = "ellipse" },
                label = { Text(stringResource(R.string.v2_bubble_ellipse)) }
            )
            FilterChip(
                selected = bubbleShape == "rounded",
                onClick = { bubbleShape = "rounded" },
                label = { Text(stringResource(R.string.v2_bubble_rounded)) }
            )
        }
        OutlinedTextField(
            value = bubbleFrame, onValueChange = { bubbleFrame = it },
            label = { Text(stringResource(R.string.v2_bubble_frame)) },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = bubbleTail, onValueChange = { bubbleTail = it },
            label = { Text(stringResource(R.string.v2_bubble_tail)) },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = bubbleTextLayer, onValueChange = { bubbleTextLayer = it },
                label = { Text(stringResource(R.string.v2_bubble_text)) },
                singleLine = true, modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    val f = bubbleFrame.split(",").mapNotNull { it.toFloatOrNull() }
                    val t = bubbleTail.split(",").mapNotNull { it.toFloatOrNull() }
                    if (f.size != 4 || t.size != 4) {
                        err(R.string.v2_bad_rect)
                        return@Button
                    }
                    val fiche = BubbleFiche(
                        bubbleShape, floatArrayOf(f[0], f[1], f[2], f[3]),
                        Pair(t[0], t[1]), Pair(t[2], t[3]),
                        bubbleTextLayer.toIntOrNull() ?: -1
                    )
                    v2RunBg {
                        val layer = commitBubble(surface, fiche)
                        if (layer < 0) err(R.string.v2_panel_failed)
                        else {
                            refreshPanelOverlay(surface)
                            tick += 1
                        }
                    }
                },
                modifier = Modifier.testTag("v2-bubble-create")
            ) { Text(stringResource(R.string.v2_create)) }
        }

        Text(
            stringResource(R.string.v2_speed_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = spdX, onValueChange = { spdX = it },
                label = { Text("X") }, singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = spdY, onValueChange = { spdY = it },
                label = { Text("Y") }, singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = spdSeed, onValueChange = { spdSeed = it },
                label = { Text("Seed") }, singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Text(stringResource(R.string.v2_speed_angle, spdAngle.toInt()))
        Slider(
            value = spdAngle, onValueChange = { spdAngle = it },
            valueRange = 0f..360f
        )
        Text(stringResource(R.string.v2_speed_len, spdLen.toInt()))
        Slider(
            value = spdLen, onValueChange = { spdLen = it },
            valueRange = 16f..512f
        )
        Text(stringResource(R.string.v2_speed_count, spdCount))
        Slider(
            value = spdCount.toFloat(),
            onValueChange = { spdCount = it.toInt().coerceIn(1, 256) },
            valueRange = 1f..256f
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val fx = spdX.toFloatOrNull()
                    val fy = spdY.toFloatOrNull()
                    val seed = spdSeed.toLongOrNull()
                    if (fx == null || fy == null || seed == null) {
                        err(R.string.v2_bad_rect)
                        return@Button
                    }
                    val fiche = SpeedFiche(fx, fy, spdAngle, spdLen, spdCount, seed)
                    v2RunBg {
                        val layer = v2EnsureVectorLayer(surface, "Speed")
                        var n = 0
                        for (seg in SpeedBurst.generate(fiche)) {
                            if (surface.appendVectorPath(layer, SpeedBurst.pathJson(seg)) >= 0) n++
                        }
                        if (n > 0) {
                            surface.setLayerMeta(layer, "x-openclip-speed", fiche.toMeta())
                            refreshPanelOverlay(surface)
                            V2TestHooks.panelOverlay = V2TestHooks.panelOverlay
                        } else {
                            err(R.string.v2_panel_failed)
                        }
                    }
                },
                modifier = Modifier.testTag("v2-speed-generate")
            ) { Text(stringResource(R.string.v2_generate)) }
            OutlinedButton(
                onClick = {
                    v2RunBg {
                        // Annuler = nUndoVector × N (1 undo par tracé, spec §1.1).
                        val layer = v2VectorLayers(surface).firstOrNull() ?: return@v2RunBg
                        repeat(spdCount) { surface.undoVectorPath(layer) }
                        refreshPanelOverlay(surface)
                    }
                },
                modifier = Modifier.testTag("v2-speed-cancel")
            ) { Text(stringResource(R.string.cancel)) }
        }

        Text(
            stringResource(R.string.v2_reading_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = reading == "ltr",
                onClick = {
                    reading = "ltr"
                    saveReadingPref(cacheDir, docKey, "ltr")
                },
                label = { Text(stringResource(R.string.v2_reading_ltr)) },
                modifier = Modifier.testTag("v2-reading-ltr")
            )
            FilterChip(
                selected = reading == "rtl",
                onClick = {
                    reading = "rtl"
                    saveReadingPref(cacheDir, docKey, "rtl")
                },
                label = { Text(stringResource(R.string.v2_reading_rtl)) },
                modifier = Modifier.testTag("v2-reading-rtl")
            )
        }
        Text(
            stringResource(R.string.v2_reading_hint),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun loadReadingPref(cacheDir: File, docKey: String): String = try {
    val f = File(cacheDir, "reading-$docKey.json")
    if (f.exists() && f.readText().contains("rtl")) "rtl" else "ltr"
} catch (_: Exception) {
    "ltr"
}

private fun saveReadingPref(cacheDir: File, docKey: String, v: String) {
    try {
        File(cacheDir, "reading-$docKey.json").writeText("{\"reading\":\"$v\"}")
    } catch (_: Exception) {
    }
}

// ---- §4.1 Transfo de sélection ----

@Composable
fun TransformPanel(
    surface: EngineSurface,
    cacheDir: File,
    onStatus: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    var dx by remember { mutableStateOf(0f) }
    var dy by remember { mutableStateOf(0f) }
    var scale by remember { mutableStateOf(1f) }
    var angle by remember { mutableStateOf(0f) }
    var mode by remember { mutableStateOf("move") }
    var armed by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth().testTag("v2-transform-panel")
    ) {
        Text(
            stringResource(R.string.v2_transform_title),
            style = MaterialTheme.typography.titleSmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("move" to R.string.v2_t_move, "free" to R.string.v2_t_free, "mesh" to R.string.v2_t_mesh)
                .forEach { (m, label) ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { mode = m },
                        label = { Text(stringResource(label)) },
                        modifier = Modifier.testTag("v2-tmode-$m")
                    )
                }
        }
        Text(stringResource(R.string.v2_t_dx, dx.toInt()))
        Slider(value = dx, onValueChange = { dx = it }, valueRange = -512f..512f)
        Text(stringResource(R.string.v2_t_dy, dy.toInt()))
        Slider(value = dy, onValueChange = { dy = it }, valueRange = -512f..512f)
        if (mode == "free") {
            Text(stringResource(R.string.v2_t_scale, (scale * 100).toInt()))
            Slider(value = scale, onValueChange = { scale = it }, valueRange = 0.1f..4f)
            Text(stringResource(R.string.v2_t_angle, angle.toInt()))
            Slider(value = angle, onValueChange = { angle = it }, valueRange = -180f..180f)
        }
        if (mode == "mesh") {
            Text(
                stringResource(R.string.v2_t_mesh_hint),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (!surface.hasSelection()) {
                        onStatus(ctx.getString(R.string.v2_no_selection))
                        return@Button
                    }
                    v2RunBg {
                        val path = v2UploadSelection(surface, cacheDir)
                        if (path == null) {
                            onStatus(ctx.getString(R.string.v2_panel_failed))
                            return@v2RunBg
                        }
                        armed = true
                    }
                },
                modifier = Modifier.testTag("v2-transform-arm")
            ) { Text(stringResource(R.string.v2_arm)) }
            OutlinedButton(
                enabled = armed,
                onClick = {
                    v2RunBg {
                        val json = SelectionTransform.json(dx, dy, scale, angle, mode)
                        val ok = surface.selectionBlit(json)
                        surface.setSelectionMask(null)
                        armed = false
                        dx = 0f
                        dy = 0f
                        scale = 1f
                        angle = 0f
                        if (!ok) onStatus(ctx.getString(R.string.v2_panel_failed))
                    }
                },
                modifier = Modifier.testTag("v2-transform-apply")
            ) { Text(stringResource(R.string.v2_apply)) }
            OutlinedButton(
                enabled = armed,
                onClick = {
                    v2RunBg {
                        surface.setSelectionMask(null)
                        armed = false
                        dx = 0f
                        dy = 0f
                        scale = 1f
                        angle = 0f
                    }
                },
                modifier = Modifier.testTag("v2-transform-cancel")
            ) { Text(stringResource(R.string.cancel)) }
        }
    }
}

// ---- §4.2 Copier / coller / couper ----

object V2CopyPaste {
    /** Copy the ACTIVE layer under the live selection into the session clipboard. */
    fun copy(surface: EngineSurface, active: Int): Boolean {
        if (!surface.hasSelection()) return false
        val full = surface.soloRenderFull(active) ?: return false
        return try {
            V2Clipboard.store(full)
            full.recycle()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Paste the clipboard as a new layer (exact canvas-size match). */
    fun paste(surface: EngineSurface, cacheDir: File, name: String): Int {
        val bmp = V2Clipboard.bitmap ?: return -1
        if (bmp.width != surface.canvasWidth() || bmp.height != surface.canvasHeight()) return -1
        return try {
            val png = File.createTempFile("v2-paste-", ".png", cacheDir)
            png.outputStream().use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            val idx = surface.addLayerNamed(name)
            if (idx < 0 || !surface.blitPngIntoLayer(idx, png.absolutePath)) {
                try {
                    png.delete()
                } catch (_: Exception) {
                }
                return -1
            }
            try {
                png.delete()
            } catch (_: Exception) {
            }
            idx
        } catch (_: Exception) {
            -1
        }
    }
}

@Composable
fun CopyPastePanel(
    surface: EngineSurface,
    cacheDir: File,
    onStatus: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth().testTag("v2-copypaste-panel")
    ) {
        Button(
            onClick = {
                v2RunBg {
                    val ok = V2CopyPaste.copy(surface, v2ActiveLayer(surface))
                    if (!ok) onStatus(ctx.getString(R.string.v2_copy_failed))
                }
            },
            modifier = Modifier.testTag("v2-copy")
        ) { Text(stringResource(R.string.v2_copy)) }
        Button(
            onClick = {
                v2RunBg {
                    val active = v2ActiveLayer(surface)
                    if (!V2CopyPaste.copy(surface, active)) {
                        onStatus(ctx.getString(R.string.v2_copy_failed))
                        return@v2RunBg
                    }
                    if (!surface.eraseUnderSelection(active)) {
                        onStatus(ctx.getString(R.string.v2_copy_failed))
                    }
                }
            },
            modifier = Modifier.testTag("v2-cut")
        ) { Text(stringResource(R.string.v2_cut)) }
        Button(
            onClick = {
                v2RunBg {
                    val idx = V2CopyPaste.paste(surface, cacheDir, "Paste")
                    if (idx < 0) onStatus(ctx.getString(R.string.v2_paste_failed))
                }
            },
            modifier = Modifier.testTag("v2-paste")
        ) { Text(stringResource(R.string.v2_paste)) }
    }
}

// ---- §4.3 Édition Bézier (densification app-side, 0 JNI neuf) ----

object V2Bezier {
    data class VPath(
        val pts: List<Triple<Float, Float, Float>>,
        val widths: List<Float>,
        val kind: String
    )

    fun read(surface: EngineSurface, layer: Int): List<VPath> {
        val out = mutableListOf<VPath>()
        try {
            val root = JSONObject(surface.vectorPaths(layer))
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
                    for (k in 0 until wa.length()) widths.add(wa.optDouble(k, 2.0).toFloat())
                }
                out.add(VPath(pts, widths, p.optString("kind", "stroke")))
            }
        } catch (_: Exception) {
        }
        return out
    }

    /**
     * Replace all paths of a vector layer (honest cost: one engine undo
     * per removed path + one per appended path — documented deviation
     * from the "1 undo" shorthand, surfaced by the Annuler button).
     */
    fun replaceAll(
        surface: EngineSurface,
        layer: Int,
        paths: List<List<Triple<Float, Float, Float>>>
    ): Boolean {
        val cur = read(surface, layer)
        repeat(cur.size) {
            if (!surface.undoVectorPath(layer)) return false
        }
        for (pts in paths) {
            if (surface.appendVectorPath(layer, SpeedBurst.pathJson(pts)) < 0) return false
        }
        return true
    }
}

@Composable
fun BezierPanel(
    surface: EngineSurface,
    onStatus: (String) -> Unit,
    modifier: Modifier = Modifier,
    cacheDir: File? = null,
    docKey: String = "",
) {
    val ctx = LocalContext.current
    var layer by remember { mutableStateOf(-1) }
    var pathIdx by remember { mutableStateOf(0) }
    var tension by remember { mutableStateOf(0.5f) }
    var info by remember { mutableStateOf("") }

    fun current(): List<V2Bezier.VPath> =
        if (layer < 0) emptyList() else V2Bezier.read(surface, layer)

    Column(
        modifier = modifier.fillMaxWidth().testTag("v2-bezier-panel")
    ) {
        Text(
            stringResource(R.string.v2_bezier_title),
            style = MaterialTheme.typography.titleSmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    v2RunBg {
                        val ls = v2VectorLayers(surface)
                        layer = ls.firstOrNull() ?: -1
                        pathIdx = 0
                        val n = current().size
                        info = ctx.getString(R.string.v2_bezier_info, layer, n)
                    }
                },
                modifier = Modifier.testTag("v2-bezier-refresh")
            ) { Text(stringResource(R.string.v2_refresh)) }
            OutlinedButton(
                onClick = {
                    v2RunBg {
                        val paths = current()
                        if (paths.isEmpty() || pathIdx !in paths.indices) {
                            onStatus(ctx.getString(R.string.v2_panel_failed))
                            return@v2RunBg
                        }
                        val pts = paths[pathIdx].pts.map { Pair(it.first, it.second) }.toMutableList()
                        // Déplacer le premier point de +8px.
                        pts[0] = Pair(pts[0].first + 8f, pts[0].second)
                        val all = paths.mapIndexed { i, p ->
                            if (i == pathIdx) pts.map { (x, y) -> Triple(x, y, 1f) }
                            else p.pts
                        }
                        if (!V2Bezier.replaceAll(surface, layer, all)) {
                            onStatus(ctx.getString(R.string.v2_panel_failed))
                        }
                    }
                },
                modifier = Modifier.testTag("v2-bezier-move")
            ) { Text(stringResource(R.string.v2_bezier_move)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    v2RunBg {
                        val paths = current()
                        if (paths.isEmpty() || pathIdx !in paths.indices) {
                            onStatus(ctx.getString(R.string.v2_panel_failed))
                            return@v2RunBg
                        }
                        val pts = paths[pathIdx].pts.map { Pair(it.first, it.second) }.toMutableList()
                        // Insérer au milieu du premier segment.
                        val a = pts[0]
                        val b = pts[1]
                        pts.add(1, Pair((a.first + b.first) / 2f, (a.second + b.second) / 2f))
                        val all = paths.mapIndexed { i, p ->
                            if (i == pathIdx) pts.map { (x, y) -> Triple(x, y, 1f) }
                            else p.pts
                        }
                        if (!V2Bezier.replaceAll(surface, layer, all)) {
                            onStatus(ctx.getString(R.string.v2_panel_failed))
                        }
                    }
                },
                modifier = Modifier.testTag("v2-bezier-insert")
            ) { Text(stringResource(R.string.v2_bezier_insert)) }
            OutlinedButton(
                onClick = {
                    v2RunBg {
                        val paths = current()
                        if (paths.isEmpty() || pathIdx !in paths.indices) {
                            onStatus(ctx.getString(R.string.v2_panel_failed))
                            return@v2RunBg
                        }
                        val pts = paths[pathIdx].pts.map { Pair(it.first, it.second) }.toMutableList()
                        if (pts.size <= 2) {
                            onStatus(ctx.getString(R.string.v2_panel_failed))
                            return@v2RunBg
                        }
                        // Supprimer le dernier point.
                        pts.removeAt(pts.size - 1)
                        val all = paths.mapIndexed { i, p ->
                            if (i == pathIdx) pts.map { (x, y) -> Triple(x, y, 1f) }
                            else p.pts
                        }
                        if (!V2Bezier.replaceAll(surface, layer, all)) {
                            onStatus(ctx.getString(R.string.v2_panel_failed))
                        }
                    }
                },
                modifier = Modifier.testTag("v2-bezier-delete")
            ) { Text(stringResource(R.string.v2_bezier_delete)) }
        }
        Text(stringResource(R.string.v2_bezier_tension, (tension * 100).toInt()))
        Slider(
            value = tension, onValueChange = { tension = it },
            valueRange = 0f..1f,
            modifier = Modifier.testTag("v2-bezier-tension")
        )
        OutlinedButton(
            onClick = {
                v2RunBg {
                    val paths = current()
                    if (paths.isEmpty() || pathIdx !in paths.indices) {
                        onStatus(ctx.getString(R.string.v2_panel_failed))
                        return@v2RunBg
                    }
                    val pts = paths[pathIdx].pts.map { Pair(it.first, it.second) }
                    val dense = BezierEdit.densify(pts, tension)
                    val all = paths.mapIndexed { i, p ->
                        if (i == pathIdx) dense.map { (x, y) -> Triple(x, y, 1f) }
                        else p.pts
                    }
                    if (!V2Bezier.replaceAll(surface, layer, all)) {
                        onStatus(ctx.getString(R.string.v2_panel_failed))
                    }
                }
            },
            modifier = Modifier.testTag("v2-bezier-densify")
        ) { Text(stringResource(R.string.v2_bezier_densify)) }
        // V+3 Ramer-Douglas-Peucker (§2a): simplify the selected path
        // before re-commit (replaceAll = re-append, 1 undo per path).
        OutlinedButton(
            onClick = {
                v2RunBg {
                    val paths = current()
                    if (paths.isEmpty() || pathIdx !in paths.indices) {
                        onStatus(ctx.getString(R.string.v2_panel_failed))
                        return@v2RunBg
                    }
                    val eps = if (cacheDir != null) {
                        SnapSettings.load(cacheDir, docKey).simplifyEps
                    } else {
                        1f
                    }
                    val pts = paths[pathIdx].pts.map {
                        SnapPt(it.first, it.second, it.third)
                    }
                    val simple = Rdp.simplify(pts, eps)
                    if (simple.size < 2) {
                        onStatus(ctx.getString(R.string.v2_panel_failed))
                        return@v2RunBg
                    }
                    val all = paths.mapIndexed { i, p ->
                        if (i == pathIdx) {
                            simple.map { q -> Triple(q.x, q.y, q.p) }
                        } else {
                            p.pts
                        }
                    }
                    if (!V2Bezier.replaceAll(surface, layer, all)) {
                        onStatus(ctx.getString(R.string.v2_panel_failed))
                    } else {
                        info = ctx.getString(
                            R.string.simplify_done,
                            paths[pathIdx].pts.size, simple.size
                        )
                    }
                }
            },
            modifier = Modifier.testTag("v2-bezier-simplify")
        ) { Text(stringResource(R.string.simplify_apply)) }
        if (info.isNotEmpty()) {
            Text(info, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            stringResource(R.string.v2_bezier_undo_hint),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// ---- §4.4 Guides persistants ----

@Composable
fun GuidesPanel(
    surface: EngineSurface,
    docKey: String,
    cacheDir: File,
    onStatus: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    var guides by remember {
        mutableStateOf(GuidesIo.load(File(cacheDir, "guides-$docKey")))
    }
    var visible by remember { mutableStateOf(true) }
    var gx by remember { mutableStateOf("128") }
    var gy by remember { mutableStateOf("128") }
    var gr by remember { mutableStateOf("64") }
    // V+3 stroke precision (§2): snap settings ride the same rulers
    // payload (snapTol/grid/snapPaths keys) and persist per document.
    var snap by remember { mutableStateOf(SnapSettings.load(cacheDir, docKey)) }

    fun push(sync: List<DocGuide>) {
        // Rebuild the exact nSetRulers grammar from the parsed list.
        val sb = StringBuilder("{\"guides\":[")
        sync.forEachIndexed { i, g ->
            if (i > 0) sb.append(',')
            when (g) {
                is DocGuide.V -> sb.append(
                    String.format(Locale.US, "{\"x\":%.1f}", g.x)
                )
                is DocGuide.H -> sb.append(
                    String.format(Locale.US, "{\"y\":%.1f}", g.y)
                )
                is DocGuide.C -> sb.append(
                    String.format(
                        Locale.US, "{\"cx\":%.1f,\"cy\":%.1f,\"r\":%.1f}",
                        g.cx, g.cy, g.r
                    )
                )
            }
        }
        sb.append("]}")
        // guideJson stays the guides ARRAY fragment (symmetry merge
        // below re-appends it); the full payload adds the V+3 snap
        // keys after it (unknown keys ignored engine-side, M3 rule).
        val guidesArr = sb.toString().substringAfter("{\"guides\":").dropLast(1)
        surface.guideJson = guidesArr
        val snapFrag = snap.toJsonFragment()
        val full = "{\"guides\":$guidesArr,$snapFrag}"
        // Merge with the symmetry fragment owned by ToolPalette.
        val sym = surface.symmetryJson
        val payload = if (visible) {
            if (sym.isEmpty()) full else "{$sym,\"guides\":$guidesArr,$snapFrag}"
        } else {
            // Hidden: guides cleared engine-side, snap keys kept.
            if (sym.isEmpty()) "{\"guides\":[],$snapFrag}" else "{$sym,$snapFrag}"
        }
        if (!surface.setRulers(payload)) {
            onStatus(ctx.getString(R.string.v2_panel_failed))
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().testTag("v2-guides-panel")
    ) {
        Text(
            stringResource(R.string.v2_guides_title),
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            stringResource(R.string.v2_guides_count, guides.size),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("v2-guides-count")
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = gx, onValueChange = { gx = it },
                label = { Text("X") }, singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = gy, onValueChange = { gy = it },
                label = { Text("Y") }, singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = gr, onValueChange = { gr = it },
                label = { Text("R") }, singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val x = gx.toFloatOrNull() ?: return@Button
                    val ng = guides + DocGuide.V(x)
                    guides = ng
                    GuidesIo.save(File(cacheDir, "guides-$docKey"), ng)
                    push(ng)
                },
                modifier = Modifier.testTag("v2-guide-addv")
            ) { Text(stringResource(R.string.v2_guide_v)) }
            Button(
                onClick = {
                    val y = gy.toFloatOrNull() ?: return@Button
                    val ng = guides + DocGuide.H(y)
                    guides = ng
                    GuidesIo.save(File(cacheDir, "guides-$docKey"), ng)
                    push(ng)
                },
                modifier = Modifier.testTag("v2-guide-addh")
            ) { Text(stringResource(R.string.v2_guide_h)) }
            Button(
                onClick = {
                    val x = gx.toFloatOrNull() ?: return@Button
                    val y = gy.toFloatOrNull() ?: return@Button
                    val r = gr.toFloatOrNull() ?: return@Button
                    if (r <= 0) return@Button
                    val ng = guides + DocGuide.C(x, y, r)
                    guides = ng
                    GuidesIo.save(File(cacheDir, "guides-$docKey"), ng)
                    push(ng)
                },
                modifier = Modifier.testTag("v2-guide-addc")
            ) { Text(stringResource(R.string.v2_guide_c)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    visible = !visible
                    push(guides)
                },
                modifier = Modifier.testTag("v2-guides-toggle")
            ) {
                Text(
                    stringResource(
                        if (visible) R.string.v2_hide else R.string.v2_show
                    )
                )
            }
            OutlinedButton(
                onClick = {
                    guides = emptyList()
                    GuidesIo.save(File(cacheDir, "guides-$docKey"), emptyList())
                    push(emptyList())
                },
                modifier = Modifier.testTag("v2-guides-clear")
            ) { Text(stringResource(R.string.v2_clear)) }
        }
        // V+3 stroke precision (§2): grid step + path snap + RDP epsilon.
        // Same rulers push (snapTol = guides tolerance), persisted per
        // document; epsilon feeds BezierPanel simplification.
        Text(
            stringResource(R.string.snap_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 4.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 8, 16, 32).forEach { step ->
                FilterChip(
                    selected = snap.grid == step,
                    onClick = {
                        snap = snap.copy(grid = step)
                        SnapSettings.save(cacheDir, docKey, snap)
                        push(guides)
                    },
                    label = {
                        Text(
                            if (step == 0) stringResource(R.string.snap_grid_off)
                            else step.toString()
                        )
                    },
                    modifier = Modifier.testTag("v2-snap-grid-$step")
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = snap.snapPaths,
                onClick = {
                    snap = snap.copy(snapPaths = !snap.snapPaths)
                    SnapSettings.save(cacheDir, docKey, snap)
                    push(guides)
                },
                label = { Text(stringResource(R.string.snap_paths)) },
                modifier = Modifier.testTag("v2-snap-paths")
            )
        }
        Text(
            stringResource(R.string.simplify_epsilon, snap.simplifyEps),
            style = MaterialTheme.typography.bodySmall
        )
        Slider(
            value = snap.simplifyEps,
            onValueChange = {
                snap = snap.copy(simplifyEps = it)
                SnapSettings.save(cacheDir, docKey, snap)
            },
            valueRange = 0f..8f,
            modifier = Modifier.testTag("v2-simplify-eps")
        )
        Text(
            stringResource(R.string.snap_tol, snap.snapTol),
            style = MaterialTheme.typography.bodySmall
        )
        Slider(
            value = snap.snapTol,
            onValueChange = {
                snap = snap.copy(snapTol = it)
                SnapSettings.save(cacheDir, docKey, snap)
                push(guides)
            },
            valueRange = 0f..8f,
            modifier = Modifier.testTag("v2-snap-tol")
        )
    }
}

// ---- §3.2 GIF animé ----

@Composable
fun GifPanel(
    surface: EngineSurface,
    cacheDir: File,
    fps: Float,
    onExportFile: (File, String) -> Unit,
    onStatus: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    var loopMode by remember { mutableStateOf("infinite") }
    var loopN by remember { mutableStateOf("3") }
    var maxW by remember { mutableStateOf(1024f) }
    var dither by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth().testTag("v2-gif-panel")
    ) {
        Text(
            stringResource(R.string.v2_gif_title),
            style = MaterialTheme.typography.titleSmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("once" to R.string.v2_loop_once, "infinite" to R.string.v2_loop_infinite, "n" to R.string.v2_loop_n)
                .forEach { (m, label) ->
                    FilterChip(
                        selected = loopMode == m,
                        onClick = { loopMode = m },
                        label = { Text(stringResource(label)) },
                        modifier = Modifier.testTag("v2-loop-$m")
                    )
                }
        }
        if (loopMode == "n") {
            OutlinedTextField(
                value = loopN, onValueChange = { loopN = it },
                label = { Text(stringResource(R.string.v2_loop_n)) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
        }
        Text(stringResource(R.string.v2_gif_maxw, maxW.toInt()))
        Slider(
            value = maxW, onValueChange = { maxW = it },
            valueRange = 256f..2048f,
            modifier = Modifier.testTag("v2-gif-maxw")
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = dither,
                onClick = { dither = !dither },
                label = { Text(stringResource(R.string.v2_dither)) },
                modifier = Modifier.testTag("v2-gif-dither")
            )
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    v2RunBg {
                        val loop = when (loopMode) {
                            "once" -> 0
                            "infinite" -> -1
                            else -> loopN.toIntOrNull() ?: -1
                        }
                        val json = String.format(
                            Locale.US,
                            "{\"loop\":%d,\"maxWidth\":%d,\"dither\":%s,\"fps\":%.1f}",
                            loop, maxW.toInt(), if (dither) "true" else "false", fps
                        )
                        val out = try {
                            File.createTempFile("v2-", ".gif", cacheDir)
                        } catch (_: Exception) {
                            busy = false
                            onStatus(ctx.getString(R.string.export_failed))
                            return@v2RunBg
                        }
                        val ok = surface.exportGif(out.absolutePath, json)
                        busy = false
                        if (ok) {
                            V2TestHooks.lastGif =
                                Pair(out.absolutePath, surface.frameCount())
                            onExportFile(out, "image/gif")
                        } else {
                            try {
                                out.delete()
                            } catch (_: Exception) {
                            }
                            onStatus(ctx.getString(R.string.export_failed))
                        }
                    }
                },
                modifier = Modifier.testTag("v2-gif-export")
            ) { Text(stringResource(R.string.export_apply)) }
        }
        Text(
            stringResource(R.string.v2_gif_hint),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// ---- §3.1 Bande verticale webtoon ----

object V2Webtoon {
    /** Pull every frame composite in order (caller recycles). */
    fun pullAll(
        surface: EngineSurface,
        order: List<Int>,
        done: (List<Bitmap>) -> Unit
    ) {
        val acc = mutableListOf<Bitmap>()
        fun pull(i: Int) {
            if (i >= order.size) {
                done(acc)
                return
            }
            surface.pullFrameFull(order[i]) { bmp ->
                if (bmp == null) {
                    acc.forEach { b ->
                        try {
                            b.recycle()
                        } catch (_: Exception) {
                        }
                    }
                    done(emptyList())
                    return@pullFrameFull
                }
                acc.add(bmp)
                pull(i + 1)
            }
        }
        pull(0)
    }
}

@Composable
fun WebtoonPanel(
    surface: EngineSurface,
    cacheDir: File,
    reading: String,
    onExportFile: (File, String) -> Unit,
    onStatus: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    var gutter by remember { mutableStateOf(0f) }
    var maxH by remember { mutableStateOf("8192") }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth().testTag("v2-webtoon-panel")
    ) {
        Text(
            stringResource(R.string.v2_webtoon_title),
            style = MaterialTheme.typography.titleSmall
        )
        Text(stringResource(R.string.v2_gutter, gutter.toInt()))
        Slider(
            value = gutter, onValueChange = { gutter = it },
            valueRange = 0f..64f
        )
        OutlinedTextField(
            value = maxH, onValueChange = { maxH = it },
            label = { Text(stringResource(R.string.v2_maxh)) },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Button(
            enabled = !busy,
            onClick = {
                val mh = maxH.toIntOrNull() ?: return@Button
                if (mh <= 0) return@Button
                busy = true
                val n = surface.frameCount()
                val order = (0 until n).toList()
                val ordered = if (reading == "rtl") order.reversed() else order
                V2Webtoon.pullAll(surface, ordered) { frames ->
                    v2RunBg {
                        if (frames.isEmpty()) {
                            busy = false
                            onStatus(ctx.getString(R.string.export_failed))
                            return@v2RunBg
                        }
                        val band = WebtoonPlan.concat(frames, gutter.toInt(), -1)
                        frames.forEach { b ->
                            try {
                                b.recycle()
                            } catch (_: Exception) {
                            }
                        }
                        val slices = WebtoonPlan.slices(band.height, mh)
                        V2TestHooks.lastWebtoonPlan = Pair(n, slices.size)
                        var idx = 0
                        for (s in slices) {
                            idx++
                            val sub = try {
                                Bitmap.createBitmap(band, 0, s.srcY, band.width, s.h)
                            } catch (_: Exception) {
                                null
                            } ?: continue
                            val out = try {
                                File.createTempFile("v2-webtoon-$idx-", ".png", cacheDir)
                            } catch (_: Exception) {
                                try {
                                    sub.recycle()
                                } catch (_: Exception) {
                                }
                                continue
                            }
                            try {
                                out.outputStream().use {
                                    sub.compress(Bitmap.CompressFormat.PNG, 100, it)
                                }
                            } catch (_: Exception) {
                                try {
                                    out.delete()
                                } catch (_: Exception) {
                                }
                                try {
                                    sub.recycle()
                                } catch (_: Exception) {
                                }
                                continue
                            }
                            try {
                                sub.recycle()
                            } catch (_: Exception) {
                            }
                            onExportFile(out, "image/png")
                        }
                        try {
                            band.recycle()
                        } catch (_: Exception) {
                        }
                        busy = false
                    }
                }
            },
            modifier = Modifier.testTag("v2-webtoon-export")
        ) { Text(stringResource(R.string.export_apply)) }
        Text(
            stringResource(R.string.v2_webtoon_hint),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// ---- §3.3 Export SVG ----

@Composable
fun SvgPanel(
    surface: EngineSurface,
    cacheDir: File,
    onExportFile: (File, String) -> Unit,
    onStatus: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    var layer by remember { mutableStateOf(-1) }

    Column(
        modifier = modifier.fillMaxWidth().testTag("v2-svg-panel")
    ) {
        Text(
            stringResource(R.string.v2_svg_title),
            style = MaterialTheme.typography.titleSmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    v2RunBg {
                        layer = v2VectorLayers(surface).firstOrNull() ?: -1
                        if (layer < 0) onStatus(ctx.getString(R.string.v2_no_vector))
                    }
                },
                modifier = Modifier.testTag("v2-svg-refresh")
            ) { Text(stringResource(R.string.v2_refresh)) }
            Button(
                enabled = layer >= 0,
                // Rebrand : cinabre réservé au CTA premier (export SVG).
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandCinnabar,
                    contentColor = BrandInk
                ),
                onClick = {
                    v2RunBg {
                        val paths = V2Bezier.read(surface, layer)
                        if (paths.isEmpty()) {
                            onStatus(ctx.getString(R.string.v2_no_vector))
                            return@v2RunBg
                        }
                        val serial = paths.map { p ->
                            val meanW = if (p.widths.isEmpty()) 2f
                            else p.widths.sum() / p.widths.size
                            Pair(p.pts, meanW)
                        }
                        // T2 §4: exact per-path color + linecap (the
                        // styles ride the same V2Bezier read — tip and
                        // color default through the tolerant parser).
                        val styled = T2Vector.readPaths(
                            surface.vectorPaths(layer)
                        )
                        val strokes = serial.mapIndexed { i, (pts, meanW) ->
                            val st = styled.getOrNull(i)
                            SvgExport.Stroke(
                                pts, meanW,
                                colorHex = st?.colorHex ?: "#000000",
                                linecap = if (st?.tip == "flat") "butt" else "round"
                            )
                        }
                        val svg = SvgExport.serializeStyled(
                            surface.canvasWidth(), surface.canvasHeight(),
                            if (strokes.size == serial.size) strokes
                            else serial.map { (pts, meanW) ->
                                SvgExport.Stroke(pts, meanW)
                            }
                        )
                        val out = try {
                            File.createTempFile("v2-", ".svg", cacheDir)
                        } catch (_: Exception) {
                            onStatus(ctx.getString(R.string.export_failed))
                            return@v2RunBg
                        }
                        try {
                            out.writeText(svg)
                        } catch (_: Exception) {
                            onStatus(ctx.getString(R.string.export_failed))
                            return@v2RunBg
                        }
                        V2TestHooks.lastSvg = Pair(out.absolutePath, paths.size)
                        onExportFile(out, "image/svg+xml")
                    }
                },
                modifier = Modifier.testTag("v2-svg-export")
            ) { Text(stringResource(R.string.export_apply)) }
        }
        Text(
            stringResource(R.string.v2_svg_hint),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// ---- §2.2 Multipage maison (.ocpages/) ----

object V2Pages {
    fun projectDir(cacheDir: File, docKey: String): File =
        File(cacheDir, "ocpages-$docKey").apply { mkdirs() }

    fun pageFile(dir: File, n: Int): File =
        File(dir, MultipageProject.pageFile(n))

    fun thumbFile(dir: File, n: Int): File =
        File(File(dir, "thumbs").apply { mkdirs() }, "p$n.png")

    fun writeProject(dir: File, reading: String, pages: List<OcpPage>) {
        try {
            File(dir, "project.json").writeText(
                MultipageProject.toJson(reading, pages)
            )
        } catch (_: Exception) {
        }
    }

    fun readProject(dir: File): Pair<String, MutableList<OcpPage>> {
        return try {
            val f = File(dir, "project.json")
            if (!f.exists()) return Pair("ltr", mutableListOf())
            val (r, ps) = MultipageProject.parse(f.readText())
            Pair(r, ps.toMutableList())
        } catch (_: Exception) {
            Pair("ltr", mutableListOf())
        }
    }
}

@Composable
fun PagesPanel(
    surface: EngineSurface,
    docKey: String,
    cacheDir: File,
    onStatus: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val dir = remember(docKey) { V2Pages.projectDir(cacheDir, docKey) }
    var pages by remember(docKey) {
        val (_, ps) = V2Pages.readProject(dir)
        mutableStateOf(if (ps.isEmpty()) mutableListOf() else ps)
    }
    var reading by remember(docKey) {
        mutableStateOf(V2Pages.readProject(dir).first)
    }
    var current by remember(docKey) { mutableStateOf(0) }
    var title by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun persist() {
        V2Pages.writeProject(dir, reading, pages)
        V2TestHooks.lastPages = Pair("pages", pages.size)
    }

    /** Save the live doc over page n (ORA). */
    fun savePage(n: Int, done: (Boolean) -> Unit) {
        if (n !in 1..pages.size) {
            done(false)
            return
        }
        surface.saveTo(V2Pages.pageFile(dir, n).absolutePath, "ora", done)
    }

    Column(
        modifier = modifier.fillMaxWidth().testTag("v2-pages-panel")
    ) {
        Text(
            stringResource(R.string.v2_pages_title),
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            stringResource(R.string.v2_pages_count, pages.size),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("v2-pages-count")
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = reading == "ltr",
                onClick = {
                    reading = "ltr"
                    persist()
                },
                label = { Text(stringResource(R.string.v2_reading_ltr)) }
            )
            FilterChip(
                selected = reading == "rtl",
                onClick = {
                    reading = "rtl"
                    persist()
                },
                label = { Text(stringResource(R.string.v2_reading_rtl)) }
            )
        }
        OutlinedTextField(
            value = title, onValueChange = { title = it },
            label = { Text(stringResource(R.string.v2_page_title)) },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    v2RunBg {
                        // Nouvelle page = ORA vierge chargée après
                        // sauvegarde de la page courante (scope mémoire :
                        // une seule page chargée, règle slice 6).
                        val w = surface.canvasWidth()
                        val h = surface.canvasHeight()
                        if (pages.isNotEmpty() && current in pages.indices) {
                            var saved: Boolean? = null
                            val lock = Object()
                            surface.saveTo(
                                V2Pages.pageFile(dir, current + 1).absolutePath,
                                "ora"
                            ) { ok ->
                                synchronized(lock) {
                                    saved = ok
                                    lock.notifyAll()
                                }
                            }
                            synchronized(lock) {
                                var t = 0
                                while (saved == null && t < 200) {
                                    lock.wait(100)
                                    t++
                                }
                            }
                        }
                        val np = OcpPage(
                            "", title.ifEmpty { "Page ${pages.size + 1}" }, w, h
                        )
                        pages = pages.toMutableList().also {
                            it.add(np)
                            val tmp = it.toMutableList()
                            for (i in tmp.indices) {
                                tmp[i] = tmp[i].copy(
                                    file = MultipageProject.pageFile(i + 1)
                                )
                            }
                            it.clear()
                            it.addAll(tmp)
                        }
                        current = pages.size - 1
                        persist()
                        // Vignette de la page quittée (hash change => refresh).
                        surface.pullFrameThumbnail(0) { bmp ->
                            if (bmp != null) {
                                v2RunBg {
                                    try {
                                        val thumb = Bitmap.createScaledBitmap(bmp, 256, 256 * bmp.height / bmp.width.coerceAtLeast(1), true)
                                        V2Pages.thumbFile(dir, current + 1).outputStream().use {
                                            thumb.compress(Bitmap.CompressFormat.PNG, 100, it)
                                        }
                                        thumb.recycle()
                                    } catch (_: Exception) {
                                    }
                                    try {
                                        bmp.recycle()
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                        }
                        busy = false
                    }
                },
                modifier = Modifier.testTag("v2-page-add")
            ) { Text(stringResource(R.string.v2_page_add)) }
            OutlinedButton(
                enabled = !busy && pages.size > 1 && current in pages.indices,
                onClick = {
                    v2RunBg {
                        val idx = current
                        val tmp = pages.toMutableList()
                        if (!MultipageProject.deleteAt(tmp, idx)) {
                            onStatus(ctx.getString(R.string.v2_panel_failed))
                            return@v2RunBg
                        }
                        pages = tmp
                        current = current.coerceIn(0, pages.size - 1)
                        persist()
                        savePage(current + 1) { ok ->
                            if (ok) {
                                surface.loadFrom(
                                    V2Pages.pageFile(dir, current + 1).absolutePath
                                ) { _ -> }
                            }
                        }
                    }
                },
                modifier = Modifier.testTag("v2-page-delete")
            ) { Text(stringResource(R.string.v2_page_delete)) }
        }
        // Reorder + open: one row per page (garde jamais-0-pages, spec §2.2).
        pages.forEachIndexed { i, p ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            ) {
                Text(
                    "${i + 1}. ${p.title}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    enabled = i > 0,
                    onClick = {
                        v2RunBg {
                            val tmp = pages.toMutableList()
                            if (MultipageProject.move(tmp, i, i - 1)) {
                                pages = tmp
                                persist()
                            }
                        }
                    }
                ) { Text("↑") }
                OutlinedButton(
                    enabled = i < pages.size - 1,
                    onClick = {
                        v2RunBg {
                            val tmp = pages.toMutableList()
                            if (MultipageProject.move(tmp, i, i + 1)) {
                                pages = tmp
                                persist()
                            }
                        }
                    }
                ) { Text("↓") }
                OutlinedButton(
                    onClick = {
                        if (i == current) return@OutlinedButton
                        v2RunBg {
                            busy = true
                            savePage(current + 1) { _ ->
                                current = i
                                surface.loadFrom(
                                    V2Pages.pageFile(dir, i + 1).absolutePath
                                ) { _ -> busy = false }
                            }
                        }
                    },
                    modifier = Modifier.testTag("v2-page-open-$i")
                ) { Text(stringResource(R.string.v2_open)) }
            }
        }
    }
}

// ---- §5.3 Navigateur miniature ----

@Composable
fun NavigatorPanel(
    surface: EngineSurface,
    modifier: Modifier = Modifier
) {
    var thumb by remember { mutableStateOf<Bitmap?>(null) }
    Column(
        modifier = modifier.fillMaxWidth().testTag("v2-navigator-panel")
    ) {
        Text(
            stringResource(R.string.v2_nav_title),
            style = MaterialTheme.typography.titleSmall
        )
        OutlinedButton(
            onClick = {
                surface.pullFrameThumbnail(0) { bmp ->
                    val old = thumb
                    thumb = bmp
                    try {
                        old?.recycle()
                    } catch (_: Exception) {
                    }
                }
            },
            modifier = Modifier.testTag("v2-nav-refresh")
        ) { Text(stringResource(R.string.v2_refresh)) }
        val t = thumb
        if (t != null) {
            Text(
                stringResource(R.string.v2_nav_hint),
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(
                onClick = {
                    // Tap-seek démonstration : recentre sur le coin
                    // haut-gauche (la sonde retient la fraction).
                    if (surface.seekToFraction(0f, 0f)) {
                        V2TestHooks.lastSeek = Pair(0f, 0f)
                    }
                },
                modifier = Modifier.testTag("v2-nav-seek")
            ) { Text(stringResource(R.string.v2_nav_seek)) }
        }
    }
}

// ---- §5.4 Autosave périodique explicite (opt-in, local seul) ----

@Composable
fun AutosavePanel(
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    intervalMin: Int,
    onInterval: (Int) -> Unit,
    lastWrite: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().testTag("v2-autosave-panel")
    ) {
        Text(
            stringResource(R.string.v2_autosave_title),
            style = MaterialTheme.typography.titleSmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !enabled,
                onClick = { onEnabled(false) },
                label = { Text(stringResource(R.string.v2_off)) },
                modifier = Modifier.testTag("v2-autosave-off")
            )
            listOf(1, 5, 15).forEach { m ->
                FilterChip(
                    selected = enabled && intervalMin == m,
                    onClick = {
                        onEnabled(true)
                        onInterval(m)
                    },
                    label = { Text(stringResource(R.string.v2_autosave_min, m)) },
                    modifier = Modifier.testTag("v2-autosave-$m")
                )
            }
        }
        Text(
            lastWrite.ifEmpty { stringResource(R.string.v2_autosave_never) },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("v2-autosave-status")
        )
    }
}

// ---- §5.2 Courbes de tons ----

@Composable
fun CurvesPanel(
    surface: EngineSurface,
    onStatus: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    var channel by remember { mutableStateOf("rgb") }
    var y0 by remember { mutableStateOf(0f) }
    var y1 by remember { mutableStateOf(85f) }
    var y2 by remember { mutableStateOf(170f) }
    var y3 by remember { mutableStateOf(255f) }

    Column(
        modifier = modifier.fillMaxWidth().testTag("v2-curves-panel")
    ) {
        Text(
            stringResource(R.string.v2_curves_title),
            style = MaterialTheme.typography.titleSmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("rgb", "r", "g", "b").forEach { c ->
                FilterChip(
                    selected = channel == c,
                    onClick = { channel = c },
                    label = { Text(c.uppercase()) },
                    modifier = Modifier.testTag("v2-curves-$c")
                )
            }
        }
        listOf(
            Triple(0, y0, { v: Float -> y0 = v }),
            Triple(85, y1, { v: Float -> y1 = v }),
            Triple(170, y2, { v: Float -> y2 = v }),
            Triple(255, y3, { v: Float -> y3 = v })
        ).forEach { (x, y, set) ->
            Text("$x → ${y.toInt()}")
            Slider(
                value = y, onValueChange = set,
                valueRange = 0f..255f
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    v2RunBg {
                        val idx = v2ActiveLayer(surface)
                        if (idx < 0) {
                            onStatus(ctx.getString(R.string.v2_panel_failed))
                            return@v2RunBg
                        }
                        val json = CurvesJson.build(
                            listOf(
                                Pair(0, y0.toInt()), Pair(85, y1.toInt()),
                                Pair(170, y2.toInt()), Pair(255, y3.toInt())
                            ),
                            channel
                        )
                        if (!surface.setLayerAdjustment(idx, json)) {
                            onStatus(ctx.getString(R.string.filter_failed))
                        }
                    }
                },
                modifier = Modifier.testTag("v2-curves-apply")
            ) { Text(stringResource(R.string.v2_apply)) }
            OutlinedButton(
                onClick = {
                    v2RunBg {
                        val idx = v2ActiveLayer(surface)
                        if (idx >= 0) surface.setLayerAdjustment(idx, null)
                    }
                },
                modifier = Modifier.testTag("v2-curves-clear")
            ) { Text(stringResource(R.string.v2_clear)) }
        }
    }
}
