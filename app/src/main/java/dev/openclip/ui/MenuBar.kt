// OpenClip slice 0 — studio menu bar (Fichier/Édition/Calque/Sélection/
// Filtre/Affichage/Fenêtre). Pure shell: every entry delegates to an
// existing app-side action (SAF import/export, EngineSurface ops,
// drawer toggles). No new engine feature, no engine change.
// Slice 7 polish: single-line titles (maxLines + no wrap + ellipsis),
// horizontal scroll as a safety net, exact 48dp height, 8dp padding.
// Compact (< 600dp): CompactMenuBar replaces the full bar — one ☰
// button (48dp) + truncated doc title + pinned 1-tap save, with a
// scrollable dropdown listing every menu by section. Never both bars
// at once (slice 0 rule 5).
// Labels come from string resources only (FR/EN).
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.openclip.R

data class StudioMenuItem(
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit = {}
)

data class StudioMenu(
    val title: String,
    val items: List<StudioMenuItem>
)

@Composable
fun MenuBar(
    menus: List<StudioMenu>,
    modifier: Modifier = Modifier
) {
    val expanded = remember { mutableStateOf(-1) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Color(0xFF202026))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp)
            .testTag("menu-bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        menus.forEachIndexed { index, menu ->
            Box {
                TextButton(
                    onClick = {
                        expanded.value = if (expanded.value == index) -1 else index
                    },
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("menu-title-$index")
                ) {
                    Text(
                        menu.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFFECECF1),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                DropdownMenu(
                    expanded = expanded.value == index,
                    onDismissRequest = { expanded.value = -1 }
                ) {
                    menu.items.forEach { item ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    item.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            onClick = {
                                expanded.value = -1
                                item.onClick()
                            },
                            enabled = item.enabled
                        )
                    }
                }
            }
        }
    }
}

/**
 * Slice 7 compact menu (< 600dp): ☰ (48dp, opens a scrollable
 * dropdown with every menu by section) + truncated doc title (12
 * chars) + pinned 1-tap save. Section headers are enabled no-op
 * items: they keep the dropdown open so each section stays reachable.
 */
@Composable
fun CompactMenuBar(
    menus: List<StudioMenu>,
    docTitle: String?,
    docDirty: Boolean,
    onSave: (() -> Unit)?,
    // V3: with ≥ 2 docs the tabs carry the names — the compact bar
    // drops its own title (single name source per state).
    showTitle: Boolean = true,
    modifier: Modifier = Modifier
) {
    val open = remember { mutableStateOf(false) }
    val desc = stringResource(R.string.menu_compact_open)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Color(0xFF202026))
            .padding(horizontal = 8.dp)
            .testTag("menu-bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TextButton(
            onClick = { open.value = true },
            modifier = Modifier
                .size(48.dp)
                .testTag("menu-compact")
                .semantics { contentDescription = desc }
        ) {
            Text(
                "☰",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFECECF1),
                maxLines = 1
            )
        }
        if (docTitle != null && showTitle) {
            val shown = if (docTitle.length > 12) docTitle.take(12) + "…" else docTitle
            Text(
                (if (docDirty) "• " else "") + shown,
                style = MaterialTheme.typography.titleSmall,
                color = Color(0xFFECECF1),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .testTag("doc-title-compact")
            )
        } else {
            Box(Modifier.weight(1f))
        }
        if (onSave != null) {
            TextButton(
                onClick = onSave,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("doc-save")
            ) {
                Text(
                    stringResource(R.string.doc_save),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF90B8FF),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        DropdownMenu(
            expanded = open.value,
            onDismissRequest = { open.value = false }
        ) {
            val maxH = (LocalConfiguration.current.screenHeightDp * 0.7f).dp
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .heightIn(max = maxH)
                    .verticalScroll(rememberScrollState())
            ) {
                menus.forEachIndexed { menuIdx, menu ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                menu.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF787888),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        onClick = { /* header: keep the menu open */ },
                        modifier = Modifier.testTag("menu-compact-header-$menuIdx")
                    )
                    menu.items.forEachIndexed { itemIdx, item ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    item.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            onClick = {
                                open.value = false
                                item.onClick()
                            },
                            enabled = item.enabled,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("menu-compact-item-$menuIdx-$itemIdx")
                        )
                    }
                }
            }
        }
    }
}

/**
 * Slice 7 single switch: exactly one of the two bars is composed.
 */
@Composable
fun ChromeMenuBar(
    menus: List<StudioMenu>,
    compact: Boolean,
    docTitle: String?,
    docDirty: Boolean,
    onSave: (() -> Unit)?,
    showTitle: Boolean = true,
    modifier: Modifier = Modifier
) {
    if (compact) {
        CompactMenuBar(
            menus = menus,
            docTitle = docTitle,
            docDirty = docDirty,
            onSave = onSave,
            showTitle = showTitle,
            modifier = modifier
        )
    } else {
        MenuBar(menus = menus, modifier = modifier)
    }
}
