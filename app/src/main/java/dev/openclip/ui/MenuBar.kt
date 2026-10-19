// OpenClip slice 0 — studio menu bar (Fichier/Édition/Calque/Sélection/
// Filtre/Affichage/Fenêtre). Pure shell: every entry delegates to an
// existing app-side action (SAF import/export, EngineSurface ops,
// drawer toggles). No new engine feature, no engine change.
// Labels come from string resources only (FR/EN).
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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
            .background(Color(0xFF202026))
            .padding(horizontal = 4.dp)
    ) {
        menus.forEachIndexed { index, menu ->
            Box {
                TextButton(
                    onClick = {
                        expanded.value = if (expanded.value == index) -1 else index
                    }
                ) {
                    Text(
                        menu.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFFECECF1)
                    )
                }
                DropdownMenu(
                    expanded = expanded.value == index,
                    onDismissRequest = { expanded.value = -1 }
                ) {
                    menu.items.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item.label) },
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
