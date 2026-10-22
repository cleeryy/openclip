// OpenClip slice 0 — shared collapsible drawer shell.
//
// Generalizes the existing dock/tiroir pattern behind one component:
// a single-tap header toggle (title + expand/collapse glyph), optional
// header extras (e.g. dock tab chips), and the drawer body. Collapsed,
// only a slim rail remains so the canvas keeps maximum space. Used for
// both the right studio dock and the bottom timeline drawer.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun CollapsibleDrawer(
    title: String,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    expandGlyph: String = "⟨",
    collapseGlyph: String = "⟩",
    showTitleWhenCollapsed: Boolean = true,
    toggleTag: String? = null,
    headerExtra: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.background(Color(0xFF1C1C22))
    ) {
        if (open) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF202026))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFB8B8C8)
                )
                headerExtra()
                Box(Modifier.weight(1f))
                val toggleMod = if (toggleTag != null) {
                    Modifier
                        .height(28.dp)
                        .testTag(toggleTag)
                } else {
                    Modifier.height(28.dp)
                }
                TextButton(
                    onClick = onToggle,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = toggleMod
                ) {
                    Text(collapseGlyph, color = Color(0xFFB8B8C8))
                }
            }
            HorizontalDivider(color = Color(0xFF2E2E38), thickness = 1.dp)
            content()
        } else {
            // Slim collapsed rail: one tap reopens, canvas stays dominant.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (showTitleWhenCollapsed) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelSmall,
                        // V11: the folded rail is an action (tap to open),
                        // never hint-toned.
                        color = Color(0xFFC4C4D0)
                    )
                }
                TextButton(
                    onClick = onToggle,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    modifier = if (toggleTag != null) Modifier.height(28.dp).testTag(toggleTag)
                    else Modifier.height(28.dp)
                ) {
                    Text(expandGlyph, color = Color(0xFF90B8FF))
                }
            }
        }
    }
}
