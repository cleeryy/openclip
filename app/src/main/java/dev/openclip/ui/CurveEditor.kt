// OpenClip slice 5 — pressure-curve graph editor (app-side only).
// Replaces the abstract 0..100 % curve slider with a tactile graph
// (input pressure x → output pressure y, 4 editable points). The engine
// only knows the scalar "curve" field (exponent 1+3*curve), so the graph
// is least-squares fitted onto that scalar at push time (BrushPrefs);
// the exact graph stays an app-side visual. No JNI.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.openclip.R
import kotlin.math.roundToInt

private val CURVE_X = floatArrayOf(0f, 1f / 3f, 2f / 3f, 1f)

/** Preset display points (exact pow samples, so the fit is exact). */
fun curvePresetPts(kind: String): FloatArray = when (kind) {
    "soft" -> floatArrayOf(0f, 0.124f, 0.463f, 1f)
    "firm" -> floatArrayOf(0f, 0.033f, 0.284f, 1f)
    else -> floatArrayOf(0f, 1f / 3f, 2f / 3f, 1f)
}

/** Catmull-Rom display smoothing through the 4 control points. */
private fun smoothCurve(pts: FloatArray, samples: Int = 32): List<Offset> {
    val xs = CURVE_X
    fun p(i: Int): Offset {
        val k = i.coerceIn(0, 3)
        return Offset(xs[k], pts[k])
    }
    val out = mutableListOf<Offset>()
    for (s in 0..samples) {
        val t = s.toFloat() / samples * 3f
        val seg = t.toInt().coerceIn(0, 2)
        val f = t - seg
        val p0 = p(seg - 1)
        val p1 = p(seg)
        val p2 = p(seg + 1)
        val p3 = p(seg + 2)
        fun cr(a: Float, b: Float, c: Float, d: Float): Float {
            val f2 = f * f
            val f3 = f2 * f
            return 0.5f * (2f * b + (-a + c) * f +
                (2f * a - 5f * b + 4f * c - d) * f2 +
                (-a + 3f * b - 3f * c + d) * f3)
        }
        out.add(Offset(cr(p0.x, p1.x, p2.x, p3.x), cr(p0.y, p1.y, p2.y, p3.y)))
    }
    return out
}

@Composable
fun PressureCurveEditor(
    prefs: BrushPrefs,
    onCommit: () -> Unit,
    // V14: drag-tick vs drag-end pushes (default: every caller is a
    // discrete commit).
    onTick: () -> Unit = onCommit,
    onEnd: () -> Unit = onCommit,
    modifier: Modifier = Modifier
) {
    val pts = prefs.curvePts
    Column(modifier = modifier) {
        Text(
            stringResource(R.string.curve_title),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFB8B8C8)
        )
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(Color(0xFF141418))
        ) {
            val density = LocalDensity.current
            val hPx = with(density) { maxHeight.toPx() }
            val wPx = with(density) { maxWidth.toPx() }
            Canvas(modifier = Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height
                // 3x3 grid + "linear" witness diagonal (dashed).
                for (i in 1..2) {
                    val gx = w * i / 3f
                    val gy = h * i / 3f
                    drawLine(Color(0xFF2E2E38), Offset(gx, 0f), Offset(gx, h), 1f)
                    drawLine(Color(0xFF2E2E38), Offset(0f, gy), Offset(w, gy), 1f)
                }
                drawLine(
                    Color(0xFF5A5A6E),
                    Offset(0f, h),
                    Offset(w, 0f),
                    1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                )
                val sm = smoothCurve(pts)
                var prev: Offset? = null
                for (o in sm) {
                    val cur = Offset(o.x * w, h - o.y.coerceIn(0f, 1f) * h)
                    if (prev != null) {
                        drawLine(Color(0xFF90B8FF), prev, cur, 3f)
                    }
                    prev = cur
                }
                for (i in 0..3) {
                    drawCircle(
                        Color(0xFF90B8FF),
                        9f,
                        Offset(CURVE_X[i] * w, h - pts[i] * h)
                    )
                }
            }
            // 48dp drag handles (24dp visual + padding, slice-2 pattern).
            for (i in 0..3) {
                val cx = CURVE_X[i] * wPx - 24f * density.density
                val cy = (1f - pts[i]) * hPx - 24f * density.density
                Box(
                    modifier = Modifier
                        .offset { IntOffset(cx.roundToInt(), cy.roundToInt()) }
                        .size(48.dp)
                        .testTag("curve-point-$i")
                        .pointerInput(i) {
                            detectVerticalDragGestures(
                                onDragEnd = { onCommit() },
                                onVerticalDrag = { _, dy ->
                                    val dv = -dy / hPx
                                    prefs.updateCurvePt(i, prefs.curvePts[i] + dv)
                                }
                            )
                        }
                )
            }
        }
        Text(
            stringResource(R.string.curve_hint),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF787888),
            maxLines = 2
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = false,
                onClick = { prefs.updateCurvePts(curvePresetPts("linear")); onCommit() },
                label = { Text(stringResource(R.string.curve_linear_reset)) },
                modifier = Modifier
                    .height(48.dp)
                    .testTag("curve-preset-linear")
            )
            FilterChip(
                selected = false,
                onClick = { prefs.updateCurvePts(curvePresetPts("soft")); onCommit() },
                label = { Text(stringResource(R.string.curve_linear)) },
                modifier = Modifier
                    .height(48.dp)
                    .testTag("curve-preset-soft")
            )
            FilterChip(
                selected = false,
                onClick = { prefs.updateCurvePts(curvePresetPts("firm")); onCommit() },
                label = { Text(stringResource(R.string.curve_firm)) },
                modifier = Modifier
                    .height(48.dp)
                    .testTag("curve-preset-firm")
            )
            // Fallback accessibility slider: same scalar, bidirectional.
            // The tagged Box is the 48dp touch target (slice-2 pattern).
            Text(
                stringResource(R.string.curve, (prefs.curve * 100).toInt()),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFB8B8C8)
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width = 110.dp, height = 48.dp)
                    .testTag("curve-slider-box")
            ) {
                Slider(
                    value = prefs.curve,
                    onValueChange = {
                        prefs.updateCurveScalar(it)
                        onTick()
                    },
                    onValueChangeFinished = { onEnd() },
                    valueRange = 0f..1f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("curve-slider")
                )
            }
        }
    }
}
