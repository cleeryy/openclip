// OpenClip slice 3 — pinned size/opacity/stabilize bar: compact overlay
// pinned to the bottom of the canvas (composite-only, never engine
// pixels), mirroring the three urgent gesture settings from the same
// hoisted BrushPrefs the ToolPalette writes. Collapsible to a 48dp
// pastille so the canvas never loses focus.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
    val shownRadius = prefs.activeRadius()
    val shownOpaque = prefs.activeOpaque()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xEB282832))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("pinned-bar")
    ) {
        Text(
            "${shownRadius.toInt()}px",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace
            ),
            color = Color(0xFFD0D0DC),
            maxLines = 1,
            modifier = Modifier.width(40.dp)
        )
    // 48dp touch zones per slice-2 pattern: the tagged Box is the target
    // (the Material Slider itself is ~20dp visual); drags reach the
    // Slider normally.
    Box(
        modifier = Modifier
            .weight(1f)
            .requiredHeight(48.dp)
            .testTag("pinned-size"),
        contentAlignment = Alignment.Center
    ) {
        Slider(
            value = shownRadius,
            onValueChange = {
                if (prefs.isEraser) prefs.updateEraserRadius(it)
                else prefs.updateRadius(it)
                push()
            },
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
            modifier = Modifier.width(72.dp)
        )
    Box(
        modifier = Modifier
            .weight(1f)
            .requiredHeight(48.dp)
            .testTag("pinned-opaque"),
        contentAlignment = Alignment.Center
    ) {
        Slider(
            value = shownOpaque,
            onValueChange = {
                if (prefs.isEraser) prefs.updateEraserOpaque(it)
                else prefs.updateOpaque(it)
                push()
            },
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
                    push()
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
                maxLines = 1
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .clickable {
                    collapsed = true
                }
                .testTag("pinned-hide")
        ) {
            Text("–", color = Color(0xFFB8B8C8))
        }
    }
}
