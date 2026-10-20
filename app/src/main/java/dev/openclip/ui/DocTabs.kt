// OpenClip slice 6 — document tabs row (composite-only overlay, never
// engine pixels). One tab per open doc: truncated name + dirty pastille +
// close cross (48dp min) + a trailing "+" for the new-canvas dialog.
// Horizontal scroll on phones. A stroke-open tab tap only pre-selects;
// the host defers the real switch to ACTION_UP (or a 5 s timeout).
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.openclip.doc.DocEntry

@Composable
fun DocTabs(
    docs: List<DocEntry>,
    activeDocId: String?,
    preselectedDocId: String?,
    compact: Boolean,
    onActivate: (String) -> Unit,
    onClose: (String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A20))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val maxChars = if (compact) 12 else 16
        for (doc in docs) {
            val selected = doc.docId == activeDocId
            val preselected = doc.docId == preselectedDocId
            FilterChip(
                selected = selected,
                onClick = { onActivate(doc.docId) },
                label = {
                    val shown = if (doc.name.length > maxChars)
                        doc.name.take(maxChars) + "…" else doc.name
                    Text(
                        (if (doc.dirty) "• " else "") + shown,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                trailingIcon = {
                    androidx.compose.material3.TextButton(
                        onClick = { onClose(doc.docId) },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("doc-tab-close-${doc.docId}"),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) {
                        Text(
                            "×",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFFC4C4D0)
                        )
                    }
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF284678),
                    containerColor = if (preselected) Color(0xFF33333E)
                    else Color(0xFF232329)
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("doc-tab-${doc.docId}")
            )
        }
        IconButton(
            onClick = onAdd,
            modifier = Modifier
                .size(48.dp)
                .testTag("doc-tab-add")
        ) {
            Text(
                "+",
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFFECECF1)
            )
        }
    }
}
