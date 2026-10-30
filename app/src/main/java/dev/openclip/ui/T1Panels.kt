// OpenClip T1 — panneaux des outils de fondations dessin (§1–§4) +
// dialogue contour progressif + masque rapide (§5). Overlay-only pour
// les prévisualisations ; les validations appellent le moteur (1 undo
// par geste). 48dp partout, FR/EN via ressources.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.window.Dialog
import dev.openclip.R
import kotlin.math.roundToInt

/** Refus honnêtes avant tout op T1 : ajustement / vectoriel. */
private fun t1Refusal(surface: EngineSurface): Int? = when {
    surface.activeLayerIsAdjustment() -> R.string.selection_adjust_guard
    surface.activeLayerIsVector() -> R.string.t1_vector_guard
    else -> null
}

@Composable
fun T1PanelHost(
    surface: EngineSurface,
    inkColor: String,
    onStatus: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val tool = surface.paintTool ?: return
    // Les callbacks tap/move/up vivent sur la surface (routage tactile
    // EngineSurface) ; ce host les arme/désarme avec l'outil.
    var bucketTol by remember(tool) { mutableFloatStateOf(0.12f) }
    var bucketAa by remember(tool) { mutableStateOf(true) }
    var bucketExpand by remember(tool) { mutableIntStateOf(0) }
    var bucketMerged by remember(tool) { mutableStateOf(false) }
    // B1 §1: fermeture de gaps + mode « entourer et remplir ».
    var bucketGap by remember(tool) { mutableIntStateOf(0) }
    var bucketSurround by remember(tool) { mutableStateOf(false) }
    var gradKind by remember(tool) { mutableStateOf("linear") }
    var gradDither by remember(tool) { mutableStateOf(true) }
    val gradStops = remember(tool) {
        mutableStateListOf(
            Pair(inkColor, 0f),
            Pair("#FFFFFF", 1f)
        )
    }
    val gradAnchors = remember(tool) { mutableStateListOf<Pair<Float, Float>>() }
    var gradStart by remember(tool) { mutableStateOf<Pair<Float, Float>?>(null) }
    var lastTapMs by remember(tool) { mutableStateOf(0L) }
    var lastTapXY by remember(tool) { mutableStateOf(Pair(0f, 0f)) }
    var shapeKind by remember(tool) { mutableStateOf("rect") }
    var shapeSides by remember(tool) { mutableIntStateOf(4) }
    var shapeRot by remember(tool) { mutableFloatStateOf(0f) }
    var shapeCorner by remember(tool) { mutableFloatStateOf(0f) }
    var shapeFillOn by remember(tool) { mutableStateOf(true) }
    var shapeFill by remember(tool) { mutableStateOf(inkColor) }
    var shapeStrokeOn by remember(tool) { mutableStateOf(false) }
    var shapeStroke by remember(tool) { mutableStateOf("#000000") }
    var shapeStrokeW by remember(tool) { mutableFloatStateOf(4f) }
    var shapeLock by remember(tool) { mutableStateOf(false) }
    var shapeStart by remember(tool) { mutableStateOf<Pair<Float, Float>?>(null) }
    var wandTol by remember(tool) { mutableFloatStateOf(0.15f) }
    var wandSource by remember(tool) { mutableStateOf("layer") }
    var wandCombine by remember(tool) { mutableIntStateOf(0) }
    var wandSoften by remember(tool) { mutableStateOf(false) }
    val ctx = LocalContext.current

    fun err(res: Int) = onStatus(ctx.getString(res))

    // Les callbacks lisent les reglages : l'effet est re-arme a chaque
    // changement (re-enregistrement leger, jamais de valeur stale).
    DisposableEffect(
        tool, bucketTol, bucketAa, bucketExpand, bucketMerged,
        bucketGap, bucketSurround,
        gradKind, gradStops.toList(), gradAnchors.toList(), gradDither,
        shapeKind, shapeSides, shapeRot, shapeCorner,
        shapeFillOn, shapeFill, shapeStrokeOn, shapeStroke, shapeStrokeW,
        shapeLock, wandTol, wandSource, wandCombine, wandSoften
    ) {
        surface.onPaintTap = { t, ex, ey ->
            if (t == PaintTool.BUCKET) {
                t1Refusal(surface)?.let { err(it) } ?: run {
                    Thread {
                        val ok = surface.floodFillAt(
                            ex.toInt(), ey.toInt(), inkColor,
                            bucketJson(
                                bucketTol, bucketAa, bucketExpand,
                                bucketMerged, bucketGap, bucketSurround
                            )
                        )
                        if (!ok) err(R.string.t1_failed)
                    }.start()
                }
            } else if (t == PaintTool.GRADIENT) {
                if (gradKind == "free") {
                    val now = android.os.SystemClock.uptimeMillis()
                    val dx = ex - lastTapXY.first
                    val dy = ey - lastTapXY.second
                    if (now - lastTapMs < 350 && dx * dx + dy * dy < 400f &&
                        gradAnchors.size >= 2
                    ) {
                        // Double-tap = commit.
                        t1Refusal(surface)?.let { err(it) } ?: run {
                            val json = gradientJson(
                                "free", 0f, 0f, 1f, 0f,
                                gradStops.toList(), gradAnchors.toList(), gradDither
                            )
                            Thread {
                                if (surface.paintGradientJson(json)) {
                                    gradAnchors.clear()
                                    surface.paintPreviewClear()
                                } else err(R.string.t1_failed)
                            }.start()
                        }
                        lastTapMs = 0L
                    } else if (gradAnchors.size < 8 &&
                        gradAnchors.size < gradStops.size
                    ) {
                        gradAnchors.add(Pair(ex, ey))
                        surface.paintPreviewPoints(
                            gradAnchors.toList() + Pair(ex, ey)
                        )
                        lastTapMs = now
                        lastTapXY = Pair(ex, ey)
                    } else {
                        err(R.string.t1_free_full)
                        lastTapMs = now
                        lastTapXY = Pair(ex, ey)
                    }
                } else {
                    gradStart = Pair(ex, ey)
                    surface.paintFrameStart(ex, ey)
                }
            } else if (t == PaintTool.SHAPE) {
                shapeStart = Pair(ex, ey)
                surface.paintFrameStart(ex, ey)
            } else if (t == PaintTool.WAND) {
                t1Refusal(surface)?.let { err(it) } ?: run {
                    surface.wandSelect(
                        ex.toInt(), ey.toInt(), wandTol, wandSource, wandCombine
                    ) { ok ->
                        if (!ok) {
                            err(R.string.t1_wand_empty)
                        } else {
                            if (wandSoften) surface.featherSelectionRadius(1f)
                            val n = surface.selectionCountForTest()
                            onStatus(ctx.getString(R.string.t1_wand_selected, n))
                        }
                    }
                }
            }
        }
        surface.onPaintUp = { t, ex, ey ->
            if (t == PaintTool.GRADIENT && gradKind != "free") {
                val s = gradStart
                if (s != null) {
                    t1Refusal(surface)?.let { err(it) } ?: run {
                        val json = gradientJson(
                            gradKind, s.first, s.second, ex, ey,
                            gradStops.toList(), emptyList(), gradDither
                        )
                        Thread {
                            if (!surface.paintGradientJson(json)) {
                                err(R.string.t1_failed)
                            }
                        }.start()
                    }
                    gradStart = null
                }
            } else if (t == PaintTool.SHAPE) {
                val s = shapeStart
                if (s != null) {
                    t1Refusal(surface)?.let { err(it) } ?: run {
                        var x0 = minOf(s.first, ex)
                        var y0 = minOf(s.second, ey)
                        var x1 = maxOf(s.first, ex)
                        var y1 = maxOf(s.second, ey)
                        if (shapeLock) {
                            val side = maxOf(x1 - x0, y1 - y0)
                            x1 = x0 + side
                            y1 = y0 + side
                        }
                        val json = shapeJson(
                            shapeKind, x0, y0, x1, y1,
                            shapeSides, shapeRot, shapeCorner,
                            shapeFillOn, shapeFill,
                            shapeStrokeOn, shapeStroke, shapeStrokeW
                        )
                        Thread {
                            if (!surface.stampShapeJson(json)) {
                                err(R.string.t1_failed)
                            }
                        }.start()
                    }
                    shapeStart = null
                }
            }
        }
        onDispose {
            surface.onPaintTap = null
            surface.onPaintMove = null
            surface.onPaintUp = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
    ) {
        when (tool) {
            PaintTool.BUCKET -> BucketPanel(
                tol = bucketTol,
                onTol = { bucketTol = it },
                antialias = bucketAa,
                onAa = { bucketAa = it },
                expand = bucketExpand,
                onExpand = { bucketExpand = it },
                merged = bucketMerged,
                onMerged = { bucketMerged = it },
                gap = bucketGap,
                onGap = { bucketGap = it },
                surround = bucketSurround,
                onSurround = { bucketSurround = it }
            )
            PaintTool.GRADIENT -> GradientPanel(
                kind = gradKind,
                onKind = {
                    gradKind = it
                    gradAnchors.clear()
                    gradStart = null
                    surface.paintPreviewClear()
                },
                stops = gradStops,
                dither = gradDither,
                onDither = { gradDither = it },
                anchorCount = gradAnchors.size,
                inkColor = inkColor
            )
            PaintTool.SHAPE -> ShapePanel(
                kind = shapeKind,
                onKind = { shapeKind = it },
                sides = shapeSides,
                onSides = { shapeSides = it },
                rotation = shapeRot,
                onRotation = { shapeRot = it },
                corner = shapeCorner,
                onCorner = { shapeCorner = it },
                fillOn = shapeFillOn,
                onFillOn = { shapeFillOn = it },
                fill = shapeFill,
                onFill = { shapeFill = it },
                strokeOn = shapeStrokeOn,
                onStrokeOn = { shapeStrokeOn = it },
                stroke = shapeStroke,
                onStroke = { shapeStroke = it },
                strokeW = shapeStrokeW,
                onStrokeW = { shapeStrokeW = it },
                lock = shapeLock,
                onLock = { shapeLock = it }
            )
            PaintTool.WAND -> WandPanel(
                tol = wandTol,
                onTol = { wandTol = it },
                source = wandSource,
                onSource = { wandSource = it },
                combine = wandCombine,
                onCombine = { wandCombine = it },
                soften = wandSoften,
                onSoften = { wandSoften = it }
            )
        }
    }
}

@Composable
private fun BucketPanel(
    tol: Float,
    onTol: (Float) -> Unit,
    antialias: Boolean,
    onAa: (Boolean) -> Unit,
    expand: Int,
    onExpand: (Int) -> Unit,
    merged: Boolean,
    onMerged: (Boolean) -> Unit,
    gap: Int,
    onGap: (Int) -> Unit,
    surround: Boolean,
    onSurround: (Boolean) -> Unit
) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .testTag("t1-bucket-panel"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.t1_tolerance, (tol * 100).roundToInt()),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.testTag("t1-tolerance-label")
        )
        Slider(
            value = tol,
            onValueChange = onTol,
            valueRange = 0f..1f,
            modifier = Modifier
                .width(140.dp)
                .height(28.dp)
                .testTag("t1-tolerance")
        )
        FilterChip(
            selected = antialias,
            onClick = { onAa(!antialias) },
            label = { Text(stringResource(R.string.t1_antialias)) },
            modifier = Modifier
                .height(48.dp)
                .testTag("t1-antialias")
        )
        listOf(0, 1, 2).forEach { px ->
            FilterChip(
                selected = expand == px,
                onClick = { onExpand(px) },
                label = { Text("+${px}px") },
                modifier = Modifier
                    .height(48.dp)
                    .testTag("t1-expand-$px")
            )
        }
        FilterChip(
            selected = !merged,
            onClick = { onMerged(false) },
            label = { Text(stringResource(R.string.t1_source_layer)) },
            modifier = Modifier
                .height(48.dp)
                .testTag("t1-source-layer")
        )
        FilterChip(
            selected = merged,
            onClick = { onMerged(true) },
            label = { Text(stringResource(R.string.t1_source_merged)) },
            modifier = Modifier
                .height(48.dp)
                .testTag("t1-source-merged")
        )
        // B1 §1: gap closing (0/1/2/4 px) — the kernel closes gaps up to
        // 2*gap px wide before flooding.
        Text(
            stringResource(R.string.b1_gap_label),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.testTag("b1-gap-label")
        )
        listOf(0, 1, 2, 4).forEach { px ->
            FilterChip(
                selected = gap == px,
                onClick = { onGap(px) },
                label = { Text(if (px == 0) "0" else "≤${2 * px}px") },
                modifier = Modifier
                    .height(48.dp)
                    .testTag("b1-gap-$px")
            )
        }
        // B1 §1: surround mode ("draw the enclosure, fill it").
        FilterChip(
            selected = surround,
            onClick = { onSurround(!surround) },
            label = { Text(stringResource(R.string.b1_surround)) },
            modifier = Modifier
                .height(48.dp)
                .testTag("b1-surround")
        )
    }
}

@Composable
private fun GradientPanel(
    kind: String,
    onKind: (String) -> Unit,
    stops: MutableList<Pair<String, Float>>,
    dither: Boolean,
    onDither: (Boolean) -> Unit,
    anchorCount: Int,
    inkColor: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("t1-gradient-panel")
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(
                "linear" to R.string.t1_linear,
                "radial" to R.string.t1_radial,
                "free" to R.string.t1_free
            ).forEach { (k, label) ->
                FilterChip(
                    selected = kind == k,
                    onClick = { onKind(k) },
                    label = { Text(stringResource(label)) },
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("t1-gradient-$k")
                )
            }
            FilterChip(
                selected = dither,
                onClick = { onDither(!dither) },
                label = { Text(stringResource(R.string.t1_dither)) },
                modifier = Modifier
                    .height(48.dp)
                    .testTag("t1-dither")
            )
        }
        if (kind == "free") {
            Text(
                stringResource(R.string.t1_free_hint, anchorCount, stops.size),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.testTag("t1-free-hint")
            )
        }
        stops.forEachIndexed { i, (color, pos) ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = color,
                    onValueChange = { stops[i] = Pair(it, pos) },
                    label = { Text("#RRGGBB") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("t1-stop-color-$i")
                )
                Slider(
                    value = pos,
                    onValueChange = { stops[i] = Pair(color, it) },
                    valueRange = 0f..1f,
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .testTag("t1-stop-pos-$i")
                )
                if (stops.size > 2) {
                    val delDesc = stringResource(R.string.a11y_remove)
                    TextButton(
                        onClick = { stops.removeAt(i) },
                        modifier = Modifier
                            .testTag("t1-stop-del-$i")
                            .semantics { contentDescription = delDesc }
                    ) { Text("−") }
                }
            }
        }
        if (stops.size < 8) {
            TextButton(
                onClick = { stops.add(Pair(inkColor, 1f)) },
                modifier = Modifier.testTag("t1-stop-add")
            ) { Text(stringResource(R.string.t1_add_stop)) }
        }
    }
}

@Composable
private fun ShapePanel(
    kind: String,
    onKind: (String) -> Unit,
    sides: Int,
    onSides: (Int) -> Unit,
    rotation: Float,
    onRotation: (Float) -> Unit,
    corner: Float,
    onCorner: (Float) -> Unit,
    fillOn: Boolean,
    onFillOn: (Boolean) -> Unit,
    fill: String,
    onFill: (String) -> Unit,
    strokeOn: Boolean,
    onStrokeOn: (Boolean) -> Unit,
    stroke: String,
    onStroke: (String) -> Unit,
    strokeW: Float,
    onStrokeW: (Float) -> Unit,
    lock: Boolean,
    onLock: (Boolean) -> Unit
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("t1-shape-panel")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(
                "rect" to R.string.t1_rect,
                "ellipse" to R.string.t1_ellipse,
                "polygon" to R.string.t1_polygon
            ).forEach { (k, label) ->
                FilterChip(
                    selected = kind == k,
                    onClick = { onKind(k) },
                    label = { Text(stringResource(label)) },
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("t1-shape-$k")
                )
            }
            if (kind == "polygon") {
                val minusDesc = stringResource(R.string.a11y_decrease)
                val plusDesc = stringResource(R.string.a11y_increase)
                TextButton(
                    onClick = { onSides((sides - 1).coerceAtLeast(3)) },
                    modifier = Modifier
                        .testTag("t1-sides-minus")
                        .semantics { contentDescription = minusDesc }
                ) { Text("−") }
                Text(
                    stringResource(R.string.t1_sides, sides),
                    modifier = Modifier.testTag("t1-sides-label")
                )
                TextButton(
                    onClick = { onSides((sides + 1).coerceAtMost(12)) },
                    modifier = Modifier
                        .testTag("t1-sides-plus")
                        .semantics { contentDescription = plusDesc }
                ) { Text("+") }
                Text(stringResource(R.string.t1_rotation, rotation.roundToInt()))
                Slider(
                    value = rotation,
                    onValueChange = onRotation,
                    valueRange = 0f..360f,
                    modifier = Modifier
                        .width(140.dp)
                        .height(28.dp)
                        .testTag("t1-rotation")
                )
            }
            if (kind == "rect") {
                Text(stringResource(R.string.t1_corner, corner.roundToInt()))
                Slider(
                    value = corner,
                    onValueChange = onCorner,
                    valueRange = 0f..64f,
                    modifier = Modifier
                        .width(140.dp)
                        .height(28.dp)
                        .testTag("t1-corner")
                )
            }
            FilterChip(
                selected = lock,
                onClick = { onLock(!lock) },
                label = { Text(stringResource(R.string.t1_square_lock)) },
                modifier = Modifier
                    .height(48.dp)
                    .testTag("t1-square-lock")
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            FilterChip(
                selected = fillOn,
                onClick = { onFillOn(!fillOn) },
                label = { Text(stringResource(R.string.t1_fill)) },
                modifier = Modifier
                    .height(48.dp)
                    .testTag("t1-fill-toggle")
            )
            OutlinedTextField(
                value = fill,
                onValueChange = onFill,
                label = { Text(stringResource(R.string.t1_fill)) },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("t1-fill-color")
            )
            FilterChip(
                selected = strokeOn,
                onClick = { onStrokeOn(!strokeOn) },
                label = { Text(stringResource(R.string.t1_stroke)) },
                modifier = Modifier
                    .height(48.dp)
                    .testTag("t1-stroke-toggle")
            )
            OutlinedTextField(
                value = stroke,
                onValueChange = onStroke,
                label = { Text(stringResource(R.string.t1_stroke)) },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("t1-stroke-color")
            )
        }
        if (strokeOn) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.t1_stroke_width, strokeW.roundToInt()))
                Slider(
                    value = strokeW,
                    onValueChange = onStrokeW,
                    valueRange = 1f..64f,
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .testTag("t1-stroke-width")
                )
            }
        }
    }
}

@Composable
private fun WandPanel(
    tol: Float,
    onTol: (Float) -> Unit,
    source: String,
    onSource: (String) -> Unit,
    combine: Int,
    onCombine: (Int) -> Unit,
    soften: Boolean,
    onSoften: (Boolean) -> Unit
) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .testTag("t1-wand-panel"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.t1_tolerance, (tol * 100).roundToInt()),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.testTag("t1-wand-tolerance-label")
        )
        Slider(
            value = tol,
            onValueChange = onTol,
            valueRange = 0f..1f,
            modifier = Modifier
                .width(140.dp)
                .height(28.dp)
                .testTag("t1-wand-tolerance")
        )
        FilterChip(
            selected = source == "layer",
            onClick = { onSource("layer") },
            label = { Text(stringResource(R.string.t1_source_layer)) },
            modifier = Modifier
                .height(48.dp)
                .testTag("t1-wand-source-layer")
        )
        FilterChip(
            selected = source == "merged",
            onClick = { onSource("merged") },
            label = { Text(stringResource(R.string.t1_source_merged)) },
            modifier = Modifier
                .height(48.dp)
                .testTag("t1-wand-source-merged")
        )
        listOf(
            0 to R.string.t1_combine_set,
            1 to R.string.t1_combine_add,
            2 to R.string.t1_combine_sub
        ).forEach { (m, label) ->
            FilterChip(
                selected = combine == m,
                onClick = { onCombine(m) },
                label = { Text(stringResource(label)) },
                modifier = Modifier
                    .height(48.dp)
                    .testTag("t1-wand-combine-$m")
            )
        }
        FilterChip(
            selected = soften,
            onClick = { onSoften(!soften) },
            label = { Text(stringResource(R.string.t1_soften)) },
            modifier = Modifier
                .height(48.dp)
                .testTag("t1-wand-soften")
        )
    }
}

/** Dialogue contour progressif (remplace l'ancien +1px fixe). */
@Composable
fun FeatherDialog(
    surface: EngineSurface,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    var radius by remember { mutableFloatStateOf(0f) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(12.dp)
                    .testTag("t1-feather-dialog")
            ) {
                Text(
                    stringResource(R.string.t1_feather_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    stringResource(R.string.t1_feather_radius, radius.roundToInt()),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.testTag("t1-feather-label")
                )
                Slider(
                    value = radius,
                    onValueChange = { radius = it },
                    valueRange = 0f..32f,
                    steps = 31,
                    modifier = Modifier.testTag("t1-feather-slider")
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.filter_cancel))
                    }
                    Button(
                        onClick = {
                            if (!surface.hasSelection()) {
                                Toast.makeText(
                                    ctx, ctx.getString(R.string.v2_no_selection),
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@Button
                            }
                            Thread {
                                surface.featherSelectionRadius(radius)
                            }.start()
                            onDismiss()
                        },
                        modifier = Modifier.testTag("t1-feather-apply")
                    ) {
                        Text(stringResource(R.string.filter_apply))
                    }
                }
            }
        }
    }
}
