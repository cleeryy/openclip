// OpenClip T4 — warp + smart-shape panels (ARCHITECTURE.md
// "T4 additions" §1 + §5). Warp: perspective/skew/bulge ride the
// nSelectionBlit JSON (0 JNI), puppet rides nPuppetWarp (whole raster
// layer, 1 undo). Smart shape: toggle (default OFF) + tolerance +
// ghost validate (1 undo). Overlay-only UI (handles/ghost), engine
// coords everywhere, targets >= 48dp, FR + EN strings.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.openclip.R
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/** Selection bbox in engine coords (null when no selection). */
private fun t4SelectionBBox(es: EngineSurface): FloatArray? {
    val bits = es.exportSelectionBits() ?: return null
    val (w, h) = es.selectionSize()
    if (bits.size != w * h) return null
    var x0 = w
    var y0 = h
    var x1 = -1
    var y1 = -1
    for (y in 0 until h) {
        for (x in 0 until w) {
            if (bits[y * w + x] != 0.toByte()) {
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
            }
        }
    }
    if (x1 < x0) return null
    return floatArrayOf(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat())
}

private data class PuppetPinUi(
    var x: String = "0",
    var y: String = "0",
    var dx: String = "0",
    var dy: String = "0"
)

@Composable
fun WarpPanel(
    surface: EngineSurface,
    cacheDir: File,
    onStatus: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf("persp") }
    // Persp: per-corner offsets from the selection bbox (clockwise
    // from top-left). Skew: ax/ay. Bulge: amount/radius.
    var cornerDx by remember { mutableStateOf(FloatArray(4)) }
    var cornerDy by remember { mutableStateOf(FloatArray(4)) }
    var ax by remember { mutableStateOf(0f) }
    var ay by remember { mutableStateOf(0f) }
    var amount by remember { mutableStateOf(0f) }
    var radius by remember { mutableStateOf(1f) }
    // Puppet: pin list + influence radius (whole layer, no mask).
    var pins by remember { mutableStateOf(listOf(PuppetPinUi())) }
    var influence by remember { mutableStateOf(0f) }  // 0 = kernel default

    fun applySelectionWarp(json: String) {
        if (!surface.hasSelection()) {
            onStatus(ctx.getString(R.string.warp_no_selection))
            return
        }
        v2RunBg {
            val path = v2UploadSelection(surface, cacheDir)
            if (path == null) {
                onStatus(ctx.getString(R.string.v2_panel_failed))
                return@v2RunBg
            }
            val ok = surface.selectionBlit(json)
            surface.setSelectionMask(null)
            cornerDx = FloatArray(4)
            cornerDy = FloatArray(4)
            ax = 0f
            ay = 0f
            amount = 0f
            radius = 1f
            if (!ok) onStatus(ctx.getString(R.string.v2_panel_failed))
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().testTag("t4-warp-panel")
    ) {
        Text(
            stringResource(R.string.warp_title),
            style = MaterialTheme.typography.titleSmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "persp" to R.string.warp_persp,
                "skew" to R.string.warp_skew,
                "bulge" to R.string.warp_bulge,
                "puppet" to R.string.warp_puppet
            ).forEach { (m, label) ->
                FilterChip(
                    selected = mode == m,
                    onClick = { mode = m },
                    label = { Text(stringResource(label)) },
                    modifier = Modifier.testTag("t4-warp-mode-$m")
                )
            }
        }
        when (mode) {
            "persp" -> {
                val bbox = t4SelectionBBox(surface)
                if (bbox == null) {
                    Text(stringResource(R.string.warp_no_selection))
                } else {
                    val names = listOf("TL", "TR", "BR", "BL")
                    val baseX = floatArrayOf(bbox[0], bbox[2] + 1, bbox[2] + 1, bbox[0])
                    val baseY = floatArrayOf(bbox[1], bbox[1], bbox[3] + 1, bbox[3] + 1)
                    for (k in 0 until 4) {
                        Text(
                            "${names[k]} (${baseX[k].roundToInt()},${baseY[k].roundToInt()})",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Slider(
                                value = cornerDx[k],
                                onValueChange = {
                                    cornerDx = cornerDx.copyOf().also { a -> a[k] = it }
                                },
                                valueRange = -256f..256f,
                                modifier = Modifier.weight(1f).testTag("t4-quad-${k}x")
                            )
                            Slider(
                                value = cornerDy[k],
                                onValueChange = {
                                    cornerDy = cornerDy.copyOf().also { a -> a[k] = it }
                                },
                                valueRange = -256f..256f,
                                modifier = Modifier.weight(1f).testTag("t4-quad-${k}y")
                            )
                        }
                    }
                }
            }
            "skew" -> {
                Text(
                    stringResource(R.string.warp_skew) + " X: ${ax.roundToInt()}°",
                    style = MaterialTheme.typography.labelSmall
                )
                Slider(
                    value = ax, onValueChange = { ax = it }, valueRange = -60f..60f,
                    modifier = Modifier.testTag("t4-skew-ax")
                )
                Text(
                    stringResource(R.string.warp_skew) + " Y: ${ay.roundToInt()}°",
                    style = MaterialTheme.typography.labelSmall
                )
                Slider(
                    value = ay, onValueChange = { ay = it }, valueRange = -60f..60f,
                    modifier = Modifier.testTag("t4-skew-ay")
                )
            }
            "bulge" -> {
                Text(
                    "amount: ${"%.2f".format(amount)}",
                    style = MaterialTheme.typography.labelSmall
                )
                Slider(
                    value = amount, onValueChange = { amount = it },
                    valueRange = -1f..1f,
                    modifier = Modifier.testTag("t4-bulge-amount")
                )
                Text(
                    "radius: ${"%.2f".format(radius)}",
                    style = MaterialTheme.typography.labelSmall
                )
                Slider(
                    value = radius, onValueChange = { radius = it },
                    valueRange = 0.1f..1f,
                    modifier = Modifier.testTag("t4-bulge-radius")
                )
            }
            "puppet" -> {
                Text(
                    stringResource(R.string.warp_pin_add),
                    style = MaterialTheme.typography.labelSmall
                )
                pins.forEachIndexed { i, pin ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(
                            "x" to pin.x, "y" to pin.y,
                            "dx" to pin.dx, "dy" to pin.dy
                        ).forEach { (k, v) ->
                            OutlinedTextField(
                                value = v,
                                onValueChange = { nv ->
                                    pins = pins.mapIndexed { j, p ->
                                        if (j != i) p
                                        else when (k) {
                                            "x" -> p.copy(x = nv)
                                            "y" -> p.copy(y = nv)
                                            "dx" -> p.copy(dx = nv)
                                            else -> p.copy(dy = nv)
                                        }
                                    }
                                },
                                label = { Text(k) },
                                modifier = Modifier.weight(1f)
                                    .testTag("t4-pin-$i$k")
                            )
                        }
                        val pinDelDesc = stringResource(R.string.a11y_remove)
                        OutlinedButton(
                            onClick = {
                                pins = pins.filterIndexed { j, _ -> j != i }
                            },
                            modifier = Modifier.testTag("t4-pin-del-$i")
                                .semantics {
                                    contentDescription = pinDelDesc
                                }
                        ) { Text("×") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val pinAddDesc = stringResource(R.string.a11y_add)
                    OutlinedButton(
                        onClick = {
                            if (pins.size < 64) pins = pins + PuppetPinUi()
                            else onStatus(ctx.getString(R.string.v2_panel_failed))
                        },
                        modifier = Modifier.testTag("t4-pin-add")
                            .semantics { contentDescription = pinAddDesc }
                    ) { Text("+") }
                }
                Text(
                    "radius: ${if (influence <= 0f) "auto" else "%.0f".format(influence)}",
                    style = MaterialTheme.typography.labelSmall
                )
                Slider(
                    value = influence, onValueChange = { influence = it },
                    valueRange = 0f..512f,
                    modifier = Modifier.testTag("t4-puppet-radius")
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Button(
                onClick = {
                    if (mode == "puppet") {
                        val sb = StringBuilder("{\"pins\":[")
                        var bad = false
                        pins.forEachIndexed { i, pin ->
                            val x = pin.x.toFloatOrNull()
                            val y = pin.y.toFloatOrNull()
                            val dx = pin.dx.toFloatOrNull()
                            val dy = pin.dy.toFloatOrNull()
                            if (x == null || y == null || dx == null || dy == null) {
                                bad = true
                                return@forEachIndexed
                            }
                            if (i > 0) sb.append(',')
                            sb.append(
                                String.format(
                                    Locale.US,
                                    "{\"x\":%.1f,\"y\":%.1f,\"dx\":%.1f,\"dy\":%.1f}",
                                    x, y, dx, dy
                                )
                            )
                        }
                        sb.append(']')
                        if (influence > 0f) {
                            sb.append(
                                String.format(Locale.US, ",\"radius\":%.1f", influence)
                            )
                        }
                        sb.append('}')
                        if (bad) {
                            onStatus(ctx.getString(R.string.v2_panel_failed))
                            return@Button
                        }
                        v2RunBg {
                            val ok = surface.puppetWarp(
                                surface.activeLayerIndex(), sb.toString()
                            )
                            if (!ok) onStatus(ctx.getString(R.string.v2_panel_failed))
                        }
                    } else {
                        val json = when (mode) {
                            "persp" -> {
                                val bbox = t4SelectionBBox(surface) ?: return@Button
                                val baseX = floatArrayOf(
                                    bbox[0], bbox[2] + 1, bbox[2] + 1, bbox[0]
                                )
                                val baseY = floatArrayOf(
                                    bbox[1], bbox[1], bbox[3] + 1, bbox[3] + 1
                                )
                                val sb = StringBuilder(
                                    "{\"mode\":\"persp\",\"quad\":["
                                )
                                for (k in 0 until 4) {
                                    if (k > 0) sb.append(',')
                                    sb.append(
                                        String.format(
                                            Locale.US, "[%.1f,%.1f]",
                                            baseX[k] + cornerDx[k],
                                            baseY[k] + cornerDy[k]
                                        )
                                    )
                                }
                                sb.append("]}").toString()
                            }
                            "skew" -> String.format(
                                Locale.US,
                                "{\"mode\":\"skew\",\"ax\":%.1f,\"ay\":%.1f}",
                                ax, ay
                            )
                            else -> String.format(
                                Locale.US,
                                "{\"mode\":\"bulge\",\"amount\":%.2f,\"radius\":%.2f}",
                                amount, radius
                            )
                        }
                        applySelectionWarp(json)
                    }
                },
                modifier = Modifier.height(48.dp).testTag("t4-warp-apply")
            ) { Text(stringResource(R.string.warp_validate)) }
            OutlinedButton(
                onClick = {
                    cornerDx = FloatArray(4)
                    cornerDy = FloatArray(4)
                    ax = 0f
                    ay = 0f
                    amount = 0f
                    radius = 1f
                    pins = listOf(PuppetPinUi())
                    influence = 0f
                },
                modifier = Modifier.height(48.dp).testTag("t4-warp-cancel")
            ) { Text(stringResource(R.string.warp_cancel)) }
        }
    }
}

@Composable
fun SmartShapePanel(
    surface: EngineSurface,
    onStatus: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    var armed by remember(surface) { mutableStateOf(surface.smartShapeArmed) }
    var tolerance by remember(surface) { mutableStateOf(surface.smartTolerance) }
    var auto by remember(surface) { mutableStateOf(surface.smartAuto) }
    var ghostKind by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier.fillMaxWidth().testTag("t4-smart-panel")
    ) {
        Text(
            stringResource(R.string.smart_shape_title),
            style = MaterialTheme.typography.titleSmall
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(
                checked = armed,
                onCheckedChange = {
                    armed = it
                    surface.smartShapeArmed = it
                },
                modifier = Modifier.testTag("t4-smart-toggle")
            )
            Text(
                stringResource(R.string.smart_shape_toggle),
                style = MaterialTheme.typography.labelSmall
            )
        }
        Text(
            stringResource(R.string.smart_shape_tolerance) +
                ": ${"%.2f".format(tolerance)}",
            style = MaterialTheme.typography.labelSmall
        )
        Slider(
            value = tolerance,
            onValueChange = {
                tolerance = it
                surface.smartTolerance = it
            },
            valueRange = 0.5f..0.95f,
            modifier = Modifier.testTag("t4-smart-tolerance")
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(
                checked = auto,
                onCheckedChange = {
                    auto = it
                    surface.smartAuto = it
                },
                modifier = Modifier.testTag("t4-smart-auto")
            )
            Text(
                stringResource(R.string.smart_shape_auto),
                style = MaterialTheme.typography.labelSmall
            )
        }
        if (ghostKind != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    ghostKind!!,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.testTag("t4-smart-ghost")
                )
                Button(
                    onClick = {
                        v2RunBg {
                            val ok = surface.confirmSmartShape()
                            ghostKind = null
                            if (!ok) {
                                onStatus(
                                    ctx.getString(R.string.smart_shape_kept)
                                )
                            }
                        }
                    },
                    modifier = Modifier.height(48.dp).testTag("t4-smart-confirm")
                ) { Text(stringResource(R.string.smart_shape_confirm)) }
                OutlinedButton(
                    onClick = {
                        v2RunBg {
                            surface.dismissSmartShape()
                            ghostKind = null
                        }
                    },
                    modifier = Modifier.height(48.dp)
                ) { Text(stringResource(R.string.warp_cancel)) }
            }
        }
        // Ghost reports arrive on the UI thread (render-thread post).
        androidx.compose.runtime.DisposableEffect(surface) {
            surface.onSmartGhost = { kind, _ ->
                ghostKind = when (kind) {
                    "NONE" -> null
                    "KEPT" -> {
                        onStatus(ctx.getString(R.string.smart_shape_kept))
                        null
                    }
                    else -> kind
                }
            }
            onDispose { surface.onSmartGhost = null }
        }
    }
}
