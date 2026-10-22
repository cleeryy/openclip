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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
    // V3: compact + single doc shows a "+"-only row (same 48dp height,
    // no name chip): the single name source stays the title bar, and
    // the chrome height never moves — a height change would resize the
    // canvas and the engine would reset the view zoom (surfaceChanged).
    hideNames: Boolean = false,
    modifier: Modifier = Modifier
) {
    val tabsScroll = rememberScrollState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Color(0xFF1A1A20))
            .horizontalScroll(tabsScroll)
            // V4: 12dp end fade so a cut chip reads as scrollable.
            .scrollFade("doc-tabs", tabsScroll, edgeColor = Color(0xFF1A1A20))
            .padding(horizontal = 8.dp)
            .testTag("doc-tabs"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val maxChars = if (compact) 12 else 16
        if (!hideNames) {
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
                        // Rebrand : pastille dirty « • » en cinabre.
                        buildAnnotatedString {
                            if (doc.dirty) {
                                withStyle(SpanStyle(color = BrandCinnabar)) {
                                    append("• ")
                                }
                            }
                            append(shown)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false,
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
                    containerColor = if (preselected) Color(0xFF284678).copy(alpha = 0.5f)
                    else Color.Transparent
                ),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("doc-tab-${doc.docId}")
            )
        }
        } // hideNames: "+"-only row, same height, no name chip (V3).
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
