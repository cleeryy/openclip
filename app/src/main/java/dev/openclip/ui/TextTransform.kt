// OpenClip B2 §1 (ARCHITECTURE.md "B2 additions") — text transform
// handles: rotate / scale / skew on the text frame, committed in ONE
// undo (nCommitTextLayer). Pure math is host-testable (unit tests);
// the overlay only draws the preview bitmap + 48dp handles and turns
// drags into TextSpec changes.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.openclip.R
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Affine 2x3 in engine coords: x' = a*x + c*y + e, y' = b*x + d*y + f. */
data class Aff2(val a: Float, val b: Float, val c: Float, val d: Float,
                val e: Float, val f: Float) {
    fun apply(x: Float, y: Float): Pair<Float, Float> =
        Pair(a * x + c * y + e, b * x + d * y + f)
}

/**
 * B2 §1 transform matrix around `pivot`: R(rot) * K(tan skew) * S(sx,
 * sy). Matches the app-side `Canvas` order used by TextRenderer:
 * translate(pivot) -> rotate -> skew -> scale -> translate(-pivot).
 */
fun textXformMatrix(
    rotDeg: Float,
    scaleX: Float,
    scaleY: Float,
    skewDeg: Float,
    pivotX: Float,
    pivotY: Float
): Aff2 {
    val r = Math.toRadians(rotDeg.toDouble())
    val cr = cos(r).toFloat()
    val sr = sin(r).toFloat()
    val t = kotlin.math.tan(Math.toRadians(skewDeg.toDouble())).toFloat()
    val sx = scaleX
    val sy = scaleY
    // R * K * S (K = [[1, t], [0, 1]]).
    val a = cr * sx - sr * t * sy
    val c = cr * t * sy - sr * sy
    val b = sr * sx + cr * t * sy
    val d = sr * t * sy + cr * sy
    return Aff2(
        a, b, c, d,
        pivotX - (a * pivotX + c * pivotY),
        pivotY - (b * pivotX + d * pivotY)
    )
}

/** Rotate a screen (engine) delta into the unrotated frame axes. */
fun unrotate(dx: Float, dy: Float, rotDeg: Float): Pair<Float, Float> {
    val r = Math.toRadians(rotDeg.toDouble())
    val c = cos(r).toFloat()
    val s = sin(r).toFloat()
    return Pair(dx * c + dy * s, -dx * s + dy * c)
}

fun normalizeDeg(v: Float): Float {
    var a = v % 360f
    if (a <= -180f) a += 360f
    if (a > 180f) a -= 360f
    return a
}

/** Rotation delta (deg) for a drag from `from` to `to` around `pivot`. */
fun rotateDeltaDeg(
    pivotX: Float, pivotY: Float,
    fromX: Float, fromY: Float,
    toX: Float, toY: Float
): Float {
    val a0 = atan2((fromY - pivotY).toDouble(), (fromX - pivotX).toDouble())
    val a1 = atan2((toY - pivotY).toDouble(), (toX - pivotX).toDouble())
    return normalizeDeg(Math.toDegrees(a1 - a0).toFloat())
}

/** Per-axis scale ratio for a corner drag (local, unrotated axes). */
fun scaleRatio(
    pivotX: Float, pivotY: Float, rotDeg: Float,
    fromX: Float, fromY: Float,
    toX: Float, toY: Float
): Pair<Float, Float> {
    val from = unrotate(fromX - pivotX, fromY - pivotY, rotDeg)
    val to = unrotate(toX - pivotX, toY - pivotY, rotDeg)
    val rx = if (abs(from.first) < 1e-3f) 1f else to.first / from.first
    val ry = if (abs(from.second) < 1e-3f) 1f else to.second / from.second
    return Pair(rx.coerceIn(0.05f, 20f), ry.coerceIn(0.05f, 20f))
}

/** Skew delta (deg) from a horizontal drag of the left-middle handle. */
fun skewDeltaDeg(
    pivotX: Float, pivotY: Float, rotDeg: Float,
    fromX: Float, fromY: Float,
    toX: Float, toY: Float,
    frameH: Float
): Float {
    val from = unrotate(fromX - pivotX, fromY - pivotY, rotDeg)
    val to = unrotate(toX - pivotX, toY - pivotY, rotDeg)
    val h = frameH.coerceAtLeast(8f)
    return normalizeDeg(
        Math.toDegrees(atan2((to.first - from.first).toDouble(), h.toDouble()))
            .toFloat()
    )
}

/**
 * Compose the engine-space transform with the engine->screen affine
 * (derived from three mapped points, so view rotation/flip/zoom are
 * all handled) into android Matrix values.
 */
fun composeScreenMatrix(
    originX: Float, originY: Float,   // engineToScreen(0,0)
    unitXX: Float, unitXY: Float,     // engineToScreen(1,0) - origin
    unitYX: Float, unitYY: Float,     // engineToScreen(0,1) - origin
    xf: Aff2
): FloatArray {
    val f00 = unitXX * xf.a + unitYX * xf.b
    val f01 = unitXX * xf.c + unitYX * xf.d
    val f02 = unitXX * xf.e + unitYX * xf.f + originX
    val f10 = unitXY * xf.a + unitYY * xf.b
    val f11 = unitXY * xf.c + unitYY * xf.d
    val f12 = unitXY * xf.e + unitYY * xf.f + originY
    // android.graphics.Matrix.setValues order:
    // [scaleX, skewX, transX, skewY, scaleY, transY, 0, 0, 1]
    return floatArrayOf(f00, f01, f02, f10, f11, f12, 0f, 0f, 1f)
}

private enum class HandleKind { ROTATE, SCALE, SKEW }

/**
 * B2 §1 overlay: transformed text preview + 3 handles (48dp targets).
 * Valider commits the whole thing through `onCommit` (the caller does
 * reeditText -> nCommitTextLayer = ONE undo). Annuler never writes.
 */
@Composable
fun TextTransformOverlay(
    surface: EngineSurface,
    layer: Int,
    initial: TextSpec,
    onCommit: (TextSpec) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var spec by remember(layer) { mutableStateOf(initial) }
    val (cw, ch) = remember(layer) { surface.canvasDims() }
    val density = LocalDensity.current
    val handlePx = with(density) { 48.dp.toPx() }
    val handleHalf = with(density) { 24.dp.toPx() }

    // Preview bitmap: the untransformed text, rendered once, then moved
    // by the live matrix (cheap drags, no per-frame re-layout).
    val base = remember(layer, initial.text, initial.font, initial.sizePx, cw, ch) {
        initial.copy(rot = 0f, scaleX = 1f, scaleY = 1f, skew = 0f)
    }
    val preview by produceState<Bitmap?>(initialValue = null, base) {
        value = withContext(Dispatchers.Default) {
            try {
                TextRenderer.render(base, cw, ch).bitmap
            } catch (_: Exception) {
                null
            }
        }
    }
    DisposableEffect(preview) {
        onDispose { preview?.let { if (!it.isRecycled) it.recycle() } }
    }

    val (frameW, frameH) = remember(spec.text, spec.sizePx, spec.boxW, cw) {
        TextLineLayout.frameSize(spec, cw)
    }
    val s = surface.pixelsPerEngineUnit().coerceAtLeast(1e-3f)
    val padLocal = (40f * density.density) / s
    val pivotX = spec.boxX
    val pivotY = spec.boxY
    val xf = textXformMatrix(
        spec.rot, spec.scaleX, spec.scaleY, spec.skew, pivotX, pivotY
    )
    fun localToScreen(lx: Float, ly: Float): Offset {
        val (ex, ey) = xf.apply(pivotX + lx, pivotY + ly)
        val (vx, vy) = surface.engineToScreen(ex, ey)
        return Offset(vx, vy)
    }
    val corners = listOf(
        localToScreen(0f, 0f),
        localToScreen(frameW, 0f),
        localToScreen(frameW, frameH),
        localToScreen(0f, frameH)
    )
    val rotateAt = localToScreen(frameW * 0.5f, -padLocal)
    val scaleAt = corners[2]
    // Skew handle ON the left edge (an off-canvas offset would be
    // clipped by the canvas box on narrow frames).
    val skewAt = localToScreen(0f, frameH * 0.5f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .testTag("text-transform-overlay")
    ) {
        // Modal scrim (sibling BELOW the handles in z-order): taps and
        // drags outside the 48dp targets never paint into the canvas.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { } }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ -> change.consume() }
                }
                .testTag("tt-scrim")
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val bmp = preview
            if (bmp != null && !bmp.isRecycled) {
                val p0 = surface.engineToScreen(0f, 0f)
                val p1 = surface.engineToScreen(1f, 0f)
                val p2 = surface.engineToScreen(0f, 1f)
                val vals = composeScreenMatrix(
                    p0.first, p0.second,
                    p1.first - p0.first, p1.second - p0.second,
                    p2.first - p0.first, p2.second - p0.second,
                    xf
                )
                val m = Matrix()
                m.setValues(vals)
                drawContext.canvas.nativeCanvas.drawBitmap(bmp, m, null)
            }
            val path = Path()
            path.moveTo(corners[0].x, corners[0].y)
            for (k in 1 until corners.size) path.lineTo(corners[k].x, corners[k].y)
            path.close()
            drawPath(path, Color(0xFF4FA8FF), style = Stroke(2f))
            drawLine(
                Color(0xFF4FA8FF), corners[0], rotateAt, strokeWidth = 2f
            )
        }
        TransformHandle(
            surface = surface,
            at = rotateAt, half = handleHalf, tag = "tt-rotate",
            label = "⟳", color = Color(0xFF4FA8FF),
            current = { spec },
            onDrag = { start, cur, baseSpec ->
                val d = rotateDeltaDeg(
                    pivotX, pivotY, start.first, start.second,
                    cur.first, cur.second
                )
                spec = baseSpec.copy(rot = normalizeDeg(baseSpec.rot + d))
            }
        )
        TransformHandle(
            surface = surface,
            at = scaleAt, half = handleHalf, tag = "tt-scale",
            label = "⤡", color = Color(0xFFFFC845),
            current = { spec },
            onDrag = { start, cur, baseSpec ->
                val (rx, ry) = scaleRatio(
                    pivotX, pivotY, baseSpec.rot,
                    start.first, start.second, cur.first, cur.second
                )
                spec = baseSpec.copy(
                    scaleX = (baseSpec.scaleX * rx).coerceIn(0.1f, 8f),
                    scaleY = (baseSpec.scaleY * ry).coerceIn(0.1f, 8f)
                )
            }
        )
        TransformHandle(
            surface = surface,
            at = skewAt, half = handleHalf, tag = "tt-skew",
            label = "⇥", color = Color(0xFFFF8A50),
            current = { spec },
            onDrag = { start, cur, baseSpec ->
                val d = skewDeltaDeg(
                    pivotX, pivotY, baseSpec.rot,
                    start.first, start.second, cur.first, cur.second,
                    frameH
                )
                spec = baseSpec.copy(
                    skew = (baseSpec.skew + d).coerceIn(-45f, 45f)
                )
            }
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Clear the bottom chrome / drawer handle band so the
                // three 48dp actions stay fully tappable.
                .padding(bottom = 56.dp)
                .background(Color(0xDD101014))
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(
                    R.string.text_xform_values,
                    spec.rot.toInt(), spec.scaleX, spec.skew.toInt()
                ),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White
            )
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { spec = spec.copy(rot = 0f, scaleX = 1f, scaleY = 1f, skew = 0f) },
                    modifier = Modifier.height(48.dp).testTag("tt-reset")
                ) { Text(stringResource(R.string.text_xform_reset)) }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.height(48.dp).testTag("tt-cancel")
                ) { Text(stringResource(R.string.cancel)) }
                Button(
                    onClick = { onCommit(spec) },
                    modifier = Modifier.height(48.dp).testTag("tt-validate")
                ) { Text(stringResource(R.string.text_validate)) }
            }
        }
    }
}

@Composable
private fun TransformHandle(
    surface: EngineSurface,
    at: Offset,
    half: Float,
    tag: String,
    label: String,
    color: Color,
    current: () -> TextSpec,
    onDrag: (Pair<Float, Float>, Pair<Float, Float>, TextSpec) -> Unit
) {
    // `at` is the handle CENTRE; the gesture node's local origin is the
    // box top-left, half a target away.
    val origin = remember(at, half) { Offset(at.x - half, at.y - half) }
    val surfaceAt = rememberUpdatedState(origin)
    val cur = rememberUpdatedState(current)
    val apply = rememberUpdatedState(onDrag)
    var start by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var base by remember { mutableStateOf<TextSpec?>(null) }
    Box(
        modifier = Modifier
            .offset { IntOffset((at.x - half).toInt(), (at.y - half).toInt()) }
            .size(48.dp)
            .background(color.copy(alpha = 0.25f), CircleShape)
            .border(2.dp, color, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { off ->
                        val anchor = surfaceAt.value
                        // Engine coords through the view matrix: the
                        // gesture works under pan/zoom/view rotation.
                        start = hostEngine(
                            surface, anchor.x + off.x, anchor.y + off.y
                        )
                        base = cur.value()
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val anchor = surfaceAt.value
                        val p = hostEngine(
                            surface,
                            anchor.x + change.position.x,
                            anchor.y + change.position.y
                        )
                        val s0 = start
                        val b = base
                        if (s0 != null && b != null && p != null) {
                            apply.value(s0, p, b)
                        }
                    },
                    onDragEnd = {
                        start = null
                        base = null
                    },
                    onDragCancel = {
                        start = null
                        base = null
                    }
                )
            }
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

/** Screen -> engine through the live view matrix (null on a dead view). */
private fun hostEngine(
    surface: EngineSurface,
    vx: Float,
    vy: Float
): Pair<Float, Float>? = try {
    surface.screenToEngine(vx, vy)
} catch (_: Exception) {
    null
}
