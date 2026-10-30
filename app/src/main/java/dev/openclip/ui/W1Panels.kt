// OpenClip W1 (issue #28 slice A) — workflow panels:
//  * CanvasSizeDialog: anchored resize (crop/pad or resample), print-size
//    preview from the document DPI and crop-to-selection.
//  * Reference photo panel: a session-only photo the artist keeps beside
//    the canvas (pan/zoom/fit). Independent of the document and of the
//    M9 glTF overlay: never touches layer pixels, never exported.
// App-side only (0 new JNI beyond the W1 structural symbols, which live
// on EngineSurface). ARCHITECTURE.md "W1 additions".
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.openclip.R
import java.util.Locale
import kotlin.math.min

/** Nine anchors in display order (engine names, row-major). */
val W1_ANCHORS: List<String> = listOf(
    "top-left", "top-center", "top-right",
    "center-left", "center", "center-right",
    "bottom-left", "bottom-center", "bottom-right"
)

/** Glyphs for the 3x3 anchor grid (language-neutral, 48dp targets). */
private val W1_ANCHOR_GLYPHS = listOf("↖", "↑", "↗", "←", "•", "→", "↙", "↓", "↘")

/** Pure math for the reference photo panel (locked by emulator test). */
object RefPhotoMath {
    const val MIN_SCALE = 0.05f
    const val MAX_SCALE = 16f

    /** Scale that fits an image inside a viewport (never upscales past 1:1). */
    fun fitScale(viewW: Float, viewH: Float, imgW: Int, imgH: Int): Float {
        if (viewW <= 0f || viewH <= 0f || imgW <= 0 || imgH <= 0) return 1f
        return min(viewW / imgW.toFloat(), viewH / imgH.toFloat())
            .coerceIn(MIN_SCALE, 1f)
    }

    fun clampScale(s: Float): Float =
        if (!s.isFinite()) 1f else s.coerceIn(MIN_SCALE, MAX_SCALE)

    /** Keep at least a quarter of the image reachable (no lost photo). */
    fun clampPan(pan: Float, viewSpan: Float, imgSpan: Float, scale: Float): Float {
        if (imgSpan <= 0f) return 0f
        val half = (imgSpan * scale) / 2f + viewSpan / 4f
        return pan.coerceIn(-half, half)
    }
}

/**
 * Session-only reference photo state. Completely independent of the
 * document (switching tabs keeps it) and of the glTF overlay model.
 */
object RefPhoto {
    var bitmap: Bitmap? = null
        private set
    var scale by mutableStateOf(1f)
        private set
    var panX by mutableStateOf(0f)
        private set
    var panY by mutableStateOf(0f)
        private set

    val hasPhoto: Boolean get() = bitmap != null

    fun setPhoto(bmp: Bitmap?) {
        if (bmp == null) {
            clear()
            return
        }
        bitmap = bmp
        scale = 1f
        panX = 0f
        panY = 0f
    }

    fun clear() {
        bitmap = null
        scale = 1f
        panX = 0f
        panY = 0f
    }

    fun zoomBy(factor: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        scale = RefPhotoMath.clampScale(scale * factor)
    }

    fun panBy(dx: Float, dy: Float, viewW: Float, viewH: Float) {
        val b = bitmap ?: return
        panX = RefPhotoMath.clampPan(panX + dx, viewW, b.width.toFloat(), scale)
        panY = RefPhotoMath.clampPan(panY + dy, viewH, b.height.toFloat(), scale)
    }

    fun fit(viewW: Float, viewH: Float) {
        val b = bitmap ?: return
        scale = RefPhotoMath.fitScale(viewW, viewH, b.width, b.height)
        panX = 0f
        panY = 0f
    }

    fun zoom100() {
        scale = 1f
        panX = 0f
        panY = 0f
    }
}

/**
 * Canvas size dialog: width/height fields, 3x3 anchor grid, resample
 * switch, live print-size preview and crop-to-selection. Every action
 * goes through EngineSurface (1 engine undo step).
 */
@Composable
fun CanvasSizeDialog(
    surface: EngineSurface,
    onClose: () -> Unit,
    onApplied: () -> Unit
) {
    var wText by remember { mutableStateOf(surface.canvasWidth().toString()) }
    var hText by remember { mutableStateOf(surface.canvasHeight().toString()) }
    var anchor by remember { mutableStateOf("center") }
    var resample by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val dpi = surface.documentDpi()
    val mm = surface.printSizeMm()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.w1_canvas_size)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = wText,
                        onValueChange = { wText = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text(stringResource(R.string.w1_width)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("w1-size-w")
                    )
                    OutlinedTextField(
                        value = hText,
                        onValueChange = { hText = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text(stringResource(R.string.w1_height)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("w1-size-h")
                    )
                }
                Text(
                    stringResource(R.string.w1_anchor),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 10.dp)
                )
                W1_ANCHORS.chunked(3).forEachIndexed { rowIndex, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEachIndexed { colIndex, name ->
                            val glyph = W1_ANCHOR_GLYPHS[rowIndex * 3 + colIndex]
                            FilterChip(
                                selected = anchor == name,
                                onClick = { anchor = name },
                                label = { Text(glyph, maxLines = 1) },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("w1-anchor-$name")
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Switch(
                        checked = resample,
                        onCheckedChange = { resample = it },
                        modifier = Modifier.testTag("w1-resample")
                    )
                    Text(
                        stringResource(R.string.w1_resample),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Text(
                    stringResource(
                        R.string.w1_print_size,
                        String.format(Locale.US, "%.1f × %.1f", mm.first, mm.second),
                        dpi.toInt()
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF90B8FF),
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .testTag("w1-print-size")
                )
                TextButton(
                    onClick = {
                        if (surface.cropToSelection()) {
                            onApplied()
                            onClose()
                        } else {
                            message = ""
                        }
                    },
                    enabled = surface.hasSelection(),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("w1-crop-sel")
                ) {
                    Text(stringResource(R.string.w1_crop_selection), maxLines = 1)
                }
                if (message.isNotEmpty()) {
                    Text(message, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val w = wText.toIntOrNull()
                    val h = hText.toIntOrNull()
                    if (w == null || h == null || w < 16 || h < 16) return@TextButton
                    if (surface.resizeCanvas(w, h, anchor, resample)) {
                        onApplied()
                        onClose()
                    }
                },
                modifier = Modifier
                    .height(48.dp)
                    .testTag("w1-resize-apply")
            ) {
                Text(stringResource(R.string.w1_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onClose, modifier = Modifier.height(48.dp)) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * Selection radius dialog (grow / shrink / feather by N px).
 * `op` is "grow" | "shrink" | "feather".
 */
@Composable
fun SelectionRadiusDialog(
    op: String,
    onClose: () -> Unit,
    onApply: (Int) -> Unit
) {
    var radius by remember { mutableStateOf(4f) }
    val title = when (op) {
        "grow" -> R.string.w1_sel_grow
        "shrink" -> R.string.w1_sel_shrink
        else -> R.string.w1_sel_featherpx
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.w1_sel_radius, radius.toInt()),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.testTag("w1-sel-radius-label")
                )
                androidx.compose.material3.Slider(
                    value = radius,
                    onValueChange = { radius = it },
                    valueRange = 1f..64f,
                    modifier = Modifier.testTag("w1-sel-radius")
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onApply(radius.toInt().coerceIn(1, 64))
                    onClose()
                },
                modifier = Modifier
                    .height(48.dp)
                    .testTag("w1-sel-radius-apply")
            ) { Text(stringResource(R.string.w1_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onClose, modifier = Modifier.height(48.dp)) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * Reference photo panel (independent window): import, pan, pinch-zoom,
 * fit and 1:1. Never writes layer pixels and never reaches an export.
 */
@Composable
fun RefPhotoPanel(
    onClose: () -> Unit,
    onImport: () -> Unit
) {
    Dialog(onDismissRequest = onClose) {
        Surface(color = Color(0xFF1B1B20)) {
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.w1_ref_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFFECECF1),
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = onImport,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("w1-ref-import")
                    ) { Text(stringResource(R.string.w1_ref_import), maxLines = 1) }
                }
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .background(Color(0xFF101014))
                        .testTag("w1-ref-photo")
                ) {
                    val density = LocalDensity.current
                    val viewW = with(density) { maxWidth.toPx() }
                    val viewH = with(density) { maxHeight.toPx() }
                    val bmp = RefPhoto.bitmap
                    if (bmp == null) {
                        Text(
                            stringResource(R.string.w1_ref_empty),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF787888),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .testTag("w1-ref-empty")
                        )
                    } else {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = stringResource(R.string.w1_ref_title),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .graphicsLayer(
                                    scaleX = RefPhoto.scale,
                                    scaleY = RefPhoto.scale,
                                    translationX = RefPhoto.panX,
                                    translationY = RefPhoto.panY
                                )
                                .testTag("w1-ref-image")
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    RefPhoto.panBy(pan.x, pan.y, viewW, viewH)
                                    RefPhoto.zoomBy(zoom)
                                }
                            }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(
                        onClick = { RefPhoto.fit(viewW = 800f, viewH = 600f) },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("w1-ref-fit")
                    ) { Text(stringResource(R.string.w1_ref_fit), maxLines = 1) }
                    TextButton(
                        onClick = { RefPhoto.zoom100() },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("w1-ref-100")
                    ) { Text(stringResource(R.string.w1_ref_100), maxLines = 1) }
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("w1-ref-close")
                    ) { Text(stringResource(R.string.w1_ref_close), maxLines = 1) }
                }
                Text(
                    stringResource(R.string.w1_ref_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFB8B8C8),
                    maxLines = 2
                )
            }
        }
    }
}
