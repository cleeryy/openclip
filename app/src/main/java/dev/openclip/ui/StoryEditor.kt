// OpenClip B2 §2 (ARCHITECTURE.md "B2 additions") — story editor:
// multi-paragraph text with per-paragraph runs. It does NOT introduce a
// second layout engine: paragraphs are offsets into the existing fiche
// text, runs use the existing TextRun{start,len}, and the preview calls
// TextLineLayout.layout (the maison wrapping engine).
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.openclip.R

/** One paragraph of the story: char range in the fiche text. */
data class StoryParagraph(
    val index: Int,
    val start: Int,
    val end: Int,
    val text: String
)

/** Split the fiche text on '\n' (offsets kept, empty paragraphs too). */
fun storyParagraphs(text: String): List<StoryParagraph> {
    val out = mutableListOf<StoryParagraph>()
    var start = 0
    var i = 0
    while (i <= text.length) {
        if (i == text.length || text[i] == '\n') {
            out.add(
                StoryParagraph(
                    out.size, start, i, text.substring(start, i)
                )
            )
            start = i + 1
        }
        i++
    }
    return out
}

/**
 * B2 §2: replace the runs overlapping [start, start+len) with `run`
 * (the TextPanel "apply to selection" rule, extracted so the story
 * editor and the panel share one implementation).
 */
fun storyApplyRun(
    runs: List<TextRun>,
    start: Int,
    len: Int,
    run: TextRun
): List<TextRun> {
    if (len <= 0 || start < 0) return runs
    val end = start + len
    val kept = runs.filter { it.start + it.len <= start || it.start >= end }
    return (kept + run.copy(start = start, len = len)).sortedBy { it.start }
}

/** Runs wholly inside [from, to) (clipped), for the paragraph row. */
fun storyRunsIn(runs: List<TextRun>, from: Int, to: Int): List<TextRun> =
    runs.filter { it.start < to && it.start + it.len > from }

/** Recompute run offsets after a paragraph was edited (delta shift). */
fun storyShiftRuns(
    runs: List<TextRun>,
    after: Int,
    delta: Int,
    textLen: Int
): List<TextRun> {
    if (delta == 0) return runs
    return runs.mapNotNull { r ->
        var s = r.start
        var e = r.start + r.len
        if (s >= after) s += delta
        if (e > after) e += delta
        s = s.coerceIn(0, textLen)
        e = e.coerceIn(0, textLen)
        if (e <= s) null else r.copy(start = s, len = e - s)
    }.sortedBy { it.start }
}

/**
 * B2 §2 story editor: paragraph navigation + per-paragraph field + run
 * styling + shared layout preview. Valider returns the rebuilt fiche.
 */
@Composable
fun StoryEditorDialog(
    initial: TextSpec,
    onApply: (String, List<TextRun>) -> Unit,
    onDismiss: () -> Unit
) {
    var paras by remember(initial.text) {
        mutableStateOf(
            storyParagraphs(initial.text).map { it.text }.ifEmpty { listOf("") }
        )
    }
    var runs by remember(initial.text) { mutableStateOf(initial.runs) }
    var selected by remember { mutableStateOf(0) }
    var bold by remember { mutableStateOf(initial.bold) }
    var italic by remember { mutableStateOf(initial.italic) }

    fun joined(): String = paras.joinToString("\n")
    fun paraStart(i: Int): Int {
        var at = 0
        for (k in 0 until i) at += paras[k].length + 1
        return at
    }
    fun setPara(i: Int, v: String) {
        val oldLen = paras[i].length
        val delta = v.length - oldLen
        val start = paraStart(i)
        // Keep runs inside the edited paragraph stable, shift the rest.
        runs = storyShiftRuns(
            runs, start + oldLen, delta,
            (joined().length + delta).coerceAtLeast(0)
        )
        val out = paras.toMutableList()
        out[i] = v
        paras = out
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 6.dp,
            modifier = Modifier.testTag("story-editor")
        ) {
            Column(
                modifier = Modifier
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(R.string.story_title, paras.size),
                    style = MaterialTheme.typography.titleMedium
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    paras.indices.forEach { i ->
                        FilterChip(
                            selected = i == selected,
                            onClick = { selected = i },
                            label = {
                                Text("${i + 1}: ${paras[i].take(10)}", maxLines = 1)
                            },
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("story-para-$i")
                        )
                    }
                    TextButton(
                        onClick = {
                            paras = paras + ""
                            selected = paras.size - 1
                        },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("story-para-add")
                    ) { Text(stringResource(R.string.story_para_add)) }
                }
                OutlinedTextField(
                    value = paras.getOrElse(selected) { "" },
                    onValueChange = { if (selected in paras.indices) setPara(selected, it) },
                    label = { Text(stringResource(R.string.story_para_hint)) },
                    minLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("story-field")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = bold,
                        onClick = { bold = !bold },
                        label = { Text(stringResource(R.string.text_bold)) },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("story-bold")
                    )
                    FilterChip(
                        selected = italic,
                        onClick = { italic = !italic },
                        label = { Text(stringResource(R.string.text_italic)) },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("story-italic")
                    )
                    Button(
                        onClick = {
                            if (selected in paras.indices) {
                                val start = paraStart(selected)
                                val len = paras[selected].length
                                if (len > 0) {
                                    runs = storyApplyRun(
                                        runs, start, len,
                                        TextRun(
                                            start = start,
                                            len = len,
                                            font = initial.font,
                                            style = when {
                                                bold && italic -> "bold-italic"
                                                bold -> "bold"
                                                italic -> "italic"
                                                else -> "regular"
                                            },
                                            size = initial.sizePx,
                                            colorHex = initial.colorHex
                                        )
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("story-run-apply")
                    ) { Text(stringResource(R.string.text_run_apply)) }
                }
                // Preview: the existing layout engine, paragraph count and
                // wrapped line count (never a second layout).
                val previewSpec = remember(paras, runs, initial) {
                    initial.copy(text = joined(), runs = runs)
                }
                val frame = remember(previewSpec.wrapKey()) {
                    TextLineLayout.frameSize(previewSpec, 360)
                }
                val lines = remember(previewSpec.wrapKey()) {
                    TextLineLayout.layout(previewSpec, frame.first)
                }
                Text(
                    stringResource(R.string.story_layout, paras.size, lines.size),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF23232A))
                        .padding(6.dp)
                        .testTag("story-layout")
                )
                lines.take(8).forEach { ln ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        ln.runs.forEach { lr ->
                            Text(
                                lr.seg.text,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (lr.seg.bold) FontWeight.Bold
                                else FontWeight.Normal,
                                fontStyle = if (lr.seg.italic) FontStyle.Italic
                                else FontStyle.Normal,
                                color = Color(0xFFE4E4EA)
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("story-cancel")
                    ) { Text(stringResource(R.string.cancel)) }
                    Button(
                        onClick = { onApply(joined(), runs) },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("story-validate")
                    ) { Text(stringResource(R.string.text_validate)) }
                }
            }
        }
    }
}

/** Cache key for the layout preview (text + style + wrap inputs). */
private fun TextSpec.wrapKey(): String =
    "$text|$sizePx|$leading|$tracking|$font|$bold|$italic|$align|" +
        runs.joinToString(";") { "${it.start},${it.len},${it.style},${it.size}" }
