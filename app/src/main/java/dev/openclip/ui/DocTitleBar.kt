// OpenClip slice 6 — document title bar (composite-only overlay).
//
// « name — WxH @z% » + 1-tap save. The doc already has a persisted SAF
// path: overwrite silently; otherwise the host opens CreateDocument.
// Compact phones: title truncated to 12 chars, zoom hidden.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.openclip.R

@Composable
fun DocTitleBar(
    name: String,
    dirty: Boolean,
    w: Int,
    h: Int,
    zoomPct: Int,
    compact: Boolean,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A20))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val maxChars = if (compact) 12 else 24
        val shown = if (name.length > maxChars) name.take(maxChars) + "…" else name
        Text(
            (if (dirty) "• " else "") + shown,
            style = MaterialTheme.typography.titleSmall,
            color = Color(0xFFECECF1),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .testTag("doc-title")
        )
        val dims = if (compact) "${w}×${h}"
        else "${w}×${h} @${zoomPct}%"
        Text(
            dims,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF787888),
            maxLines = 1,
            modifier = Modifier.testTag("doc-dims")
        )
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
                maxLines = 1
            )
        }
    }
}
