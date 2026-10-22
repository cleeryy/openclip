// OpenClip slice 3 — pinned size/opacity/stabilize bar: compact overlay
// pinned to the bottom of the canvas (composite-only, never engine
// pixels), mirroring the three urgent gesture settings from the same
// hoisted BrushPrefs the ToolPalette writes. Collapsible to a 48dp
// pastille so the canvas never loses focus.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.openclip.R

@Composable
fun PinnedBrushBar(
    surface: EngineSurface,
    prefs: BrushPrefs,
    hidden: Boolean,
    modifier: Modifier = Modifier
) {
    if (hidden) return
    var collapsed by remember { mutableStateOf(false) }

    fun push() {
        try {
            surface.setBrush(prefs.currentJson())
        } catch (_: Exception) {
        }
    }
    // V14: drag ticks preview locally (prefs update is instant) while
    // the engine push is throttled (~33ms) + forced on release.
    val throttle = remember(surface) { BrushPushThrottler(push = { push() }) }
    fun tick(update: () -> Unit) {
        update()
        throttle.onDragTick()
    }
    fun end() {
        throttle.onDragEnd()
    }

    if (collapsed) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .size(48.dp)
                .shadow(4.dp, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xEB282832))
                .clickable { collapsed = false }
                .testTag("pinned-show")
        ) {
            Text("+", color = Color(0xFFECECF1))
        }
        return
    }
    // Slice 5: mirrors the ACTIVE tool memory (brush or eraser) through
    // the single hoisted push path.
    // V1: under 420dp of useful width the weighted sliders would crush
    // to zero and their thumbs would paint over the labels — the row
    // falls back to a fixed-width horizontal scroll (12dp fade) so a
    // thumb never overlaps a label.
    BoxWithConstraints(
        modifier = modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
    ) {
        val tight = maxWidth < 420.dp
        if (tight) {
            val sc = rememberScrollState()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(sc)
                    .scrollFade("pinned-bar", sc, edgeColor = Color(0xEB282832))
                    .shadow(4.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xEB282832))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .testTag("pinned-bar")
            ) {
                PinnedCells(
                    prefs = prefs,
                    tight = true,
                    onTick = ::tick,
                    onEnd = ::end,
                    onTap = ::push,
                    onHide = { collapsed = true }
                )
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(4.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xEB282832))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .testTag("pinned-bar")
            ) {
                PinnedCells(
                    prefs = prefs,
                    tight = false,
                    onTick = ::tick,
                    onEnd = ::end,
                    onTap = ::push,
                    onHide = { collapsed = true }
                )
            }
        }
    }
}

@Composable
private fun RowScope.PinnedCells(
    prefs: BrushPrefs,
    tight: Boolean,
    onTick: (() -> Unit) -> Unit,
    onEnd: () -> Unit,
    onTap: () -> Unit,
    onHide: () -> Unit
) {
    // 48dp touch zones per slice-2 pattern: the tagged Box is the target
    // (the Material Slider itself is ~20dp visual); drags reach the
    // Slider normally. Wide mode keeps weight(1f) with a 96dp floor
    // (V1); tight mode uses fixed 140dp cells inside the scroll.
    fun sliderMod(tag: String): Modifier {
        val base = if (tight) Modifier.width(140.dp)
        else Modifier.weight(1f).widthIn(min = 96.dp)
        return base
            .requiredHeight(48.dp)
            .testTag(tag)
    }
    // Slice 5: mirrors the ACTIVE tool memory (brush or eraser) through
    // the single hoisted push path.
    val shownRadius = prefs.activeRadius()
    val shownOpaque = prefs.activeOpaque()
    Text(
        "${shownRadius.toInt()}px",
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = FontFamily.Monospace
        ),
        color = Color(0xFFD0D0DC),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .width(40.dp)
            .testTag("pinned-size-label")
    )
    Box(
        modifier = sliderMod("pinned-size"),
        contentAlignment = Alignment.Center
    ) {
        Slider(
            value = shownRadius,
            onValueChange = {
                onTick {
                    if (prefs.isEraser) prefs.updateEraserRadius(it)
                    else prefs.updateRadius(it)
                }
            },
            onValueChangeFinished = { onEnd() },
            valueRange = 1f..128f,
            modifier = Modifier.fillMaxWidth()
        )
    }
    Text(
        stringResource(
            R.string.layer_opacity_brush, (shownOpaque * 100).toInt()
        ),
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = FontFamily.Monospace
        ),
        color = Color(0xFFD0D0DC),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .width(72.dp)
            .testTag("pinned-opaque-label")
    )
    Box(
        modifier = sliderMod("pinned-opaque"),
        contentAlignment = Alignment.Center
    ) {
        Slider(
            value = shownOpaque,
            onValueChange = {
                onTick {
                    if (prefs.isEraser) prefs.updateEraserOpaque(it)
                    else prefs.updateOpaque(it)
                }
            },
            onValueChangeFinished = { onEnd() },
            modifier = Modifier.fillMaxWidth()
        )
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(48.dp)
            .clickable {
                val next = when {
                    prefs.stabilize < 0.075f -> 0.15f
                    prefs.stabilize < 0.325f -> 0.5f
                    else -> 0f
                }
                prefs.updateStabilize(next)
                onTap()
            }
            .testTag("pinned-stab")
            .padding(horizontal = 4.dp)
    ) {
        Text(
            stringResource(
                R.string.stabilize, (prefs.stabilize * 100).toInt()
            ),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF90B8FF),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
    // V+3 mixer knife (§1.1): toggle + amount slider. mix 0 = plain
    // brush; the toggle arms 50 %, tapping again disarms.
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(48.dp)
            .clickable {
                prefs.updateMix(if (prefs.mix <= 0f) 0.5f else 0f)
                onTap()
            }
            .testTag("pinned-knife")
            .padding(horizontal = 4.dp)
    ) {
        Text(
            stringResource(R.string.mixer_knife),
            style = MaterialTheme.typography.labelSmall,
            color = if (prefs.mix > 0f) Color(0xFFFFC46B) else Color(0xFFB8B8C8),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
    if (prefs.mix > 0f) {
        Box(
            modifier = sliderMod("pinned-mix"),
            contentAlignment = Alignment.Center
        ) {
            Slider(
                value = prefs.mix,
                onValueChange = { onTick { prefs.updateMix(it) } },
                onValueChangeFinished = { onEnd() },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clickable { onHide() }
            .testTag("pinned-hide")
    ) {
        Text("–", color = Color(0xFFB8B8C8))
    }
}
