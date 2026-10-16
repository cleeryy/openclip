// OpenClip M7 — timeline strip: the engine owns frames, the strip
// pushes through the exact M7 symbols (add/duplicate/delete/setFrame,
// nSetOnion) and pulls thumbnails via nFrameCompositeInto (96px wide,
// scaled app-side, marked current). fps is app-side only (default 12,
// no engine symbol — the contract keeps the engine loop out); play is a
// main-thread Handler stepping nSetFrame at 1/fps. Onion prev/next +
// strength arm nSetOnion ghosts under the current frame (nCompositeInto).
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.openclip.R

@Composable
fun TimelineStrip(
    surface: EngineSurface,
    refreshKey: Int,
    modifier: Modifier = Modifier,
    audioName: String? = null,
    exporting: Boolean = false,
    onPlayingChange: (Boolean) -> Unit = {},
    onFpsChange: (Float) -> Unit = {},
    onImportAudio: () -> Unit = {},
    onClearAudio: () -> Unit = {},
    onExportWebm: () -> Unit = {}
) {
    var count by remember { mutableStateOf(1) }
    var cur by remember { mutableStateOf(0) }
    val thumbs = remember { mutableStateMapOf<Int, Bitmap>() }
    // fps is app-side state (M7 contract: no engine symbol, default 12).
    var fps by remember { mutableStateOf(12f) }
    var playing by remember { mutableStateOf(false) }
    var onionPrev by remember { mutableStateOf(false) }
    var onionNext by remember { mutableStateOf(false) }
    var onionStrength by remember { mutableStateOf(0.3f) }
    val playTick = remember { mutableStateOf(0) }

    fun refresh() {
        val n = surface.frameOpCount()
        count = n.coerceAtLeast(1)
        if (cur >= count) cur = count - 1
        for (i in 0 until count) {
            surface.pullFrameThumbnail(i) { bmp ->
                if (bmp != null) thumbs[i] = bmp
            }
        }
        // Drop thumbnails of deleted frames.
        thumbs.keys.filter { it >= count }.forEach { thumbs.remove(it) }
    }

    LaunchedEffect(refreshKey) { refresh() }

    // Play: main-thread Handler steps nSetFrame at 1/fps and composites
    // through the render thread (needsComposite inside setFrame).
    val main = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(playing, fps, count) {
        if (!playing) return@DisposableEffect onDispose {}
        var next = 0
        val step = object : Runnable {
            override fun run() {
                if (count <= 1) return
                next = (next + 1) % count
                cur = next
                surface.frameOp { setFrame(next) }
                playTick.value++
                main.postDelayed(this, (1000f / fps.coerceIn(1f, 60f)).toLong())
            }
        }
        main.postDelayed(step, (1000f / fps.coerceIn(1f, 60f)).toLong())
        onDispose { main.removeCallbacks(step) }
    }

    Column(modifier = modifier) {
        Text(
            stringResource(R.string.timeline),
            style = MaterialTheme.typography.titleSmall
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items((0 until count).toList()) { i ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(
                            if (i == cur) MaterialTheme.colorScheme.secondaryContainer
                            else Color.Transparent
                        )
                        .padding(2.dp)
                        .clickable {
                            cur = i
                            surface.frameOp { setFrame(i) }
                            refresh()
                        }
                ) {
                    val bmp = thumbs[i]
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.size(96.dp, 72.dp)
                        )
                    } else {
                        Text(
                            "$i",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.size(96.dp, 72.dp)
                        )
                    }
                    Text(
                        if (i == cur) "[$i]" else "$i",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = {
                surface.frameOp {
                    val idx = addFrame()
                    if (idx >= 0) cur = frameCount() - 1
                }
                refresh()
            }) { Text(stringResource(R.string.frame_add)) }
            TextButton(onClick = {
                surface.frameOp {
                    val idx = duplicateFrame()
                    if (idx >= 0) cur = frameCount() - 1
                }
                refresh()
            }) { Text(stringResource(R.string.frame_duplicate)) }
            TextButton(onClick = {
                val victim = cur
                surface.frameOp { deleteFrame(victim) }
                if (cur > 0) cur--
                refresh()
            }) { Text(stringResource(R.string.frame_delete)) }
            TextButton(onClick = {
                playing = !playing
                onPlayingChange(playing)
            }) {
                Text(
                    if (playing) stringResource(R.string.timeline_pause)
                    else stringResource(R.string.timeline_play)
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.timeline_fps, fps.toInt()),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(end = 8.dp)
            )
            Slider(
                value = fps,
                onValueChange = {
                    fps = it
                    onFpsChange(it)
                },
                valueRange = 1f..60f,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.onion),
                style = MaterialTheme.typography.labelSmall
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = onionPrev,
                    onCheckedChange = {
                        onionPrev = it
                        val p = it
                        surface.setOnion(p, onionNext, onionStrength)
                    }
                )
                Text(
                    stringResource(R.string.onion_prev),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = onionNext,
                    onCheckedChange = {
                        onionNext = it
                        val n = it
                        surface.setOnion(onionPrev, n, onionStrength)
                    }
                )
                Text(
                    stringResource(R.string.onion_next),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            var slider by remember(onionStrength) {
                mutableStateOf(onionStrength.coerceIn(0f, 1f))
            }
            Text(
                stringResource(
                    R.string.onion_strength,
                    (onionStrength * 100).toInt()
                ),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(end = 8.dp)
            )
            Slider(
                value = slider,
                onValueChange = { slider = it },
                onValueChangeFinished = {
                    onionStrength = slider
                    surface.setOnion(onionPrev, onionNext, slider)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        // M7b — clip audio + WebM export row (presentation only; the
        // MainActivity lane owns import/export, the strip just reports).
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (audioName == null) {
                TextButton(onClick = onImportAudio) {
                    Text(stringResource(R.string.audio_import))
                }
            } else {
                Text(
                    audioName,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
                TextButton(onClick = onClearAudio) {
                    Text(stringResource(R.string.audio_clear))
                }
            }
            TextButton(
                enabled = !exporting,
                onClick = onExportWebm
            ) {
                Text(
                    stringResource(
                        if (exporting) R.string.exporting
                        else R.string.export_webm
                    )
                )
            }
        }
    }
    // Keep the strip honest when playback steps outside it.
    LaunchedEffect(playTick.value) { refresh() }
}

// Render-thread-safe frame-count read: the strip calls this from the
// UI thread between frames, the same pattern as layerState(). Frame
// ops are discrete queue items, so a torn read cannot corrupt engine
// state (worst case: one stale thumbnail row for a frame).
private fun EngineSurface.frameOpCount(): Int =
    try { frameCount().coerceAtLeast(1) } catch (_: Exception) { 1 }
