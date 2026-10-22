// OpenClip Slice 4 — practical timeline: the engine owns frames, the
// strip pushes through the exact M7 symbols (add/duplicate/delete/
// setFrame, nSetOnion) and pulls thumbnails via nFrameCompositeInto
// (96px wide, scaled app-side to 128x96dp, marked current). fps is
// app-side only (default 12, no engine symbol — the contract keeps the
// engine loop out); play is a main-thread Handler stepping nSetFrame at
// 1/fps. Onion ghosts live under the current frame (nCompositeInto);
// the strip only paints 2dp tinted borders on ghost thumbs and maps
// display counts (before/after 0..4) to the frozen (p, n, strength)
// triple — it never promises the engine more. Scrub is a horizontal
// drag on the track container (16dp arm, ~48dp per cel, setFrame only
// on release); the LazyRow is programmatic-scroll only
// (userScrollEnabled=false) so scrub and scroll never compete; vertical
// drags pass through to the drawer scroll. Pause is automatic when the
// drawer closes (composition leaves -> Handler callbacks removed).
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.openclip.R
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.launch

// Scrub tuning (spec §2/§7): 16dp arms the seek (separates tap from
// drag on noisy touch), then ~48dp per cel.
private const val SCRUB_ARM_DP = 16f
private const val SCRUB_STEP_DP = 48f

// Ghost tints (spec §3): color-blind-safe pair, never pure red/green.
// Alpha follows the onion strength; the canvas itself is untouched.
private val ONION_BEFORE = Color(0xFF40C8B8)
private val ONION_AFTER = Color(0xFFE08050)

@OptIn(ExperimentalLayoutApi::class)
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
    onExportWebm: () -> Unit = {},
    onAiUpscale: (Int) -> Unit = {},
    // V+3 §7: external play/pause requests (space bar). Each increment
    // toggles; 0 (initial) never fires.
    playToggle: Int = 0,
) {
    var count by remember { mutableStateOf(1) }
    var cur by remember { mutableStateOf(0) }
    // V13: bounded LRU (24) with recycle-on-evict, like the LayerPanel
    // 60-entry cache — a long clip no longer pins a full-width Bitmap
    // per frame.
    val thumbCache = remember { ThumbLru(TimelineTestHooks.maxCache) }
    var thumbTick by remember { mutableStateOf(0) }
    // fps is app-side state (M7 contract: no engine symbol, default 12).
    var fps by remember { mutableStateOf(12f) }
    var playing by remember { mutableStateOf(false) }
    // Onion display state (spec §3): master OFF by default (previous
    // behavior: both checkboxes unchecked), counts 0..4 (default 1/1
    // = the old prev/next), strength 30 %.
    var onionMaster by remember { mutableStateOf(false) }
    var onionBefore by remember { mutableStateOf(1) }
    var onionAfter by remember { mutableStateOf(1) }
    var onionStrength by remember { mutableStateOf(0.3f) }
    var showSettings by remember { mutableStateOf(false) }
    var deleteArmed by remember { mutableStateOf(false) }
    var scrubCandidate by remember { mutableStateOf<Int?>(null) }
    val playTick = remember { mutableStateOf(0) }
    var lastPlayToggle by remember { mutableStateOf(0) }
    // V+3 §7 (space bar): external toggle requests.
    LaunchedEffect(playToggle) {
        if (playToggle != 0 && playToggle != lastPlayToggle) {
            lastPlayToggle = playToggle
            playing = !playing
        }
    }
    // V+2: per-cel exposure holds (nSetCelHold 1..32, default 1 when
    // absent). Keyed by frame index; reorder swaps the entries along.
    val holds = remember { mutableStateMapOf<Int, Int>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun refreshThumb(i: Int) {
        surface.pullFrameThumbnail(i) { bmp ->
            if (bmp != null) {
                thumbCache.put(i, bmp)
                thumbTick++
            }
        }
    }

    fun refresh() {
        val n = surface.frameOpCount()
        count = n.coerceAtLeast(1)
        if (cur >= count) cur = count - 1
        for (i in 0 until count) refreshThumb(i)
        // Drop thumbnails of deleted frames.
        thumbCache.dropFrom(count)
        thumbTick++
    }

    // V+2: the engine now draws the real N neighbors per side
    // (nSetOnionCount, ARCHITECTURE.md "V+2 additions"); the tinted
    // borders below stay a display indication of the same counts.
    fun applyOnion() {
        surface.setOnionCount(
            if (onionMaster) onionBefore else 0,
            if (onionMaster) onionAfter else 0,
            onionStrength
        )
    }

    LaunchedEffect(refreshKey) { refresh() }

    // V13: cache snapshot for the bounded-cache test; the whole cache
    // is recycled when the strip leaves the composition.
    LaunchedEffect(Unit) {
        TimelineTestHooks.thumbSnapshot = { thumbCache.snapshot() }
        TimelineTestHooks.countOf = { count }
    }
    DisposableEffect(Unit) {
        onDispose { thumbCache.dropFrom(0) }
    }

    // Auto-scroll the programmatic row to the current cel (tap, scrub
    // release, add/duplicate, play steps). Anchored on cur-1 so the
    // previous cel stays composed: ghost-before borders and neighbor
    // thumbs remain in the hierarchy on narrow windows. scrollToItem
    // is immediate so play stays under 16 ms/frame at 12+ fps.
    LaunchedEffect(cur) {
        if (count > 1) {
            try {
                listState.scrollToItem((cur - 1).coerceAtLeast(0))
            } catch (_: Exception) {
            }
        }
    }

    // Main-thread Handler for play steps and the delete-guard timer
    // (single mechanism; leaving the composition removes callbacks).
    val main = remember { Handler(Looper.getMainLooper()) }

    // Delete guard timer: 1-tap arm, auto-disarm after 3 s (no modal).
    DisposableEffect(deleteArmed) {
        if (!deleteArmed) return@DisposableEffect onDispose {}
        val disarm = Runnable { deleteArmed = false }
        main.postDelayed(disarm, 3000)
        onDispose { main.removeCallbacks(disarm) }
    }

    // Play: main-thread Handler steps nSetFrame at 1/fps and composites
    // through the render thread (needsComposite inside setFrame).
    // V+2 holds: each cel plays `holds[i]` ticks (default 1) before
    // advancing — the hold rhythm the GIF exporter reuses (GCE delay).
    // Leaving the composition (drawer folded, activity gone) removes
    // the callbacks: pause is automatic, no extra wiring.
    var playHoldLeft by remember { mutableStateOf(1) }
    DisposableEffect(playing, fps, count) {
        if (!playing) return@DisposableEffect onDispose {}
        var next = cur
        playHoldLeft = holds[next] ?: 1
        val step = object : Runnable {
            override fun run() {
                if (count <= 1) return
                if (playHoldLeft > 1) {
                    playHoldLeft--
                } else {
                    next = (next + 1) % count
                    cur = next
                    surface.frameOp { setFrame(next) }
                    playHoldLeft = holds[next] ?: 1
                }
                playTick.value++
                main.postDelayed(this, (1000f / fps.coerceIn(1f, 60f)).toLong())
            }
        }
        main.postDelayed(step, (1000f / fps.coerceIn(1f, 60f)).toLong())
        onDispose { main.removeCallbacks(step) }
    }

    fun stopPlay() {
        if (playing) {
            playing = false
            onPlayingChange(false)
        }
    }

    fun gotoFrame(i: Int) {
        stopPlay()
        cur = i.coerceIn(0, (count - 1).coerceAtLeast(0))
        surface.frameOp { setFrame(cur) }
        refresh()
    }

    // Scrub gesture state (spec §2): armed past 16dp, then ~48dp per
    // cel from the drag-start cel; setFrame only on release.
    var scrubStart by remember { mutableStateOf(0) }
    var scrubDx by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val armPx = with(density) { SCRUB_ARM_DP.dp.toPx() }
    val stepPx = with(density) { SCRUB_STEP_DP.dp.toPx() }

    val ghostBefore = if (onionMaster) {
        ((cur - onionBefore) until cur).filter { it >= 0 }.toSet()
    } else emptySet()
    val ghostAfter = if (onionMaster) {
        ((cur + 1)..(cur + onionAfter)).filter { it < count }.toSet()
    } else emptySet()

    Column(modifier = modifier) {
        // Piste: horizontal scrub container (drag) around a
        // programmatic row (no user scroll: scrub owns the horizontal
        // axis, vertical passes to the drawer scroll). No inner title:
        // the drawer header already names it; the 175dp budget only
        // fits piste (~120dp) + transport (48dp).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("timeline-track")
                .draggable(
                    state = rememberDraggableState { delta ->
                        scrubDx += delta
                        val adx = abs(scrubDx)
                        scrubCandidate = if (adx < armPx || count <= 1) {
                            null
                        } else {
                            val steps = ((adx - armPx) / stepPx).toInt() + 1
                            (scrubStart + (sign(scrubDx) * steps).toInt())
                                .coerceIn(0, count - 1)
                        }
                    },
                    orientation = Orientation.Horizontal,
                    onDragStarted = {
                        stopPlay()
                        scrubStart = cur
                        scrubDx = 0f
                    },
                    onDragStopped = {
                        scrubCandidate?.let { c ->
                            if (c != cur) gotoFrame(c)
                        }
                        scrubCandidate = null
                    }
                )
        ) {
            LazyRow(
                state = listState,
                userScrollEnabled = false,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items((0 until count).toList()) { i ->
                    val ghost = when (i) {
                        in ghostBefore -> "before"
                        in ghostAfter -> "after"
                        else -> null
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .background(
                                if (i == cur) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else Color.Transparent
                            )
                            .padding(2.dp)
                            .testTag("cel-thumb-$i")
                            .semantics {
                                contentDescription = when (ghost) {
                                    "before" -> "onion_before"
                                    "after" -> "onion_after"
                                    else -> "cel-$i"
                                }
                            }
                            .clickable { gotoFrame(i) }
                    ) {
                        // V13: read through the bounded cache (thumbTick
                        // resubscribes after each async pull).
                        @Suppress("UNUSED_EXPRESSION")
                        thumbTick
                        val bmp = thumbCache.get(i)
                        val frameMod = Modifier.size(128.dp, 96.dp)
                        val bordered = when (ghost) {
                            "before" -> frameMod.border(
                                2.dp, ONION_BEFORE.copy(alpha = onionStrength)
                            )
                            "after" -> frameMod.border(
                                2.dp, ONION_AFTER.copy(alpha = onionStrength)
                            )
                            else -> frameMod
                        }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = bordered
                            )
                        } else {
                            Text(
                                "$i",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = bordered
                            )
                        }
                        Text(
                            buildString {
                                append(if (i == cur) "[$i]" else "$i")
                                val h = holds[i] ?: 1
                                if (h > 1) append("×$h")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.testTag("cel-label-$i")
                        )
                    }
                }
            }
            // Pastille i/N overlaid top-end (zero height cost): shown
            // from 2 cels (V11 — the rail glyph alone reads as inert).
            // Plain text: drags pass through to the track container.
            if (count > 1) {
                Text(
                    stringResource(R.string.cel_count, cur + 1, count),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .background(Color(0xCC101014))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .testTag("timeline-count")
                )
            }
            // Scrub candidate banner (composite-only overlay, never
            // engine pixels): shown while armed past the threshold.
            val cand = scrubCandidate
            if (cand != null && cand != cur) {
                Text(
                    stringResource(R.string.scrub_cel, cand),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .background(Color(0xCC101014))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .testTag("scrub-candidate")
                )
            }
        }
        // Transport: single bar (wraps on narrow phones). ⏮/⏭ step one
        // cel (pausing first), fps stepper + slider value, onion master
        // toggle, cel ops. Every touch target is >= 48dp.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            TextButton(
                onClick = { gotoFrame((cur - 1 + count) % count) },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("transport-prev")
                    .semantics {
                        contentDescription = "transport_prev"
                    }
            ) {
                Text("⏮", maxLines = 1)
            }
            TextButton(
                onClick = {
                    if (playing) {
                        stopPlay()
                    } else if (!exporting && count > 1) {
                        playing = true
                        onPlayingChange(true)
                    }
                },
                enabled = !exporting,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("transport-play")
            ) {
                Text(
                    if (playing) "⏸" else "▶",
                    maxLines = 1
                )
            }
            TextButton(
                onClick = { gotoFrame((cur + 1) % count) },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("transport-next")
                    .semantics {
                        contentDescription = "transport_next"
                    }
            ) {
                Text("⏭", maxLines = 1)
            }
            TextButton(
                onClick = {
                    fps = (fps - 1f).coerceIn(1f, 60f)
                    onFpsChange(fps)
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("fps-minus")
                    .semantics {
                        contentDescription = "fps_step_down"
                    }
            ) {
                Text("−", maxLines = 1)
            }
            Text(
                stringResource(R.string.timeline_fps, fps.toInt()),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                modifier = Modifier.testTag("fps-value")
            )
            TextButton(
                onClick = {
                    fps = (fps + 1f).coerceIn(1f, 60f)
                    onFpsChange(fps)
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("fps-plus")
                    .semantics {
                        contentDescription = "fps_step_up"
                    }
            ) {
                Text("+", maxLines = 1)
            }
            TextButton(
                onClick = {
                    onionMaster = !onionMaster
                    applyOnion()
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("onion-master")
                    .semantics {
                        contentDescription =
                            "onion-master:" + if (onionMaster) "on" else "off"
                    }
            ) {
                Text(
                    stringResource(R.string.onion_master) +
                        if (onionMaster) " ●" else " ○",
                    maxLines = 1
                )
            }
            TextButton(
                onClick = {
                    stopPlay()
                    surface.frameOp {
                        val idx = addFrame()
                        if (idx >= 0) cur = frameCount() - 1
                    }
                    refresh()
                    scope.launch {
                        try {
                            listState.scrollToItem((cur - 1).coerceAtLeast(0))
                        } catch (_: Exception) {
                        }
                    }
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("cel-add")
            ) { Text(stringResource(R.string.cel_add), maxLines = 1) }
            TextButton(
                onClick = {
                    stopPlay()
                    surface.frameOp {
                        val idx = duplicateFrame()
                        if (idx >= 0) cur = frameCount() - 1
                    }
                    refresh()
                    scope.launch {
                        try {
                            listState.scrollToItem((cur - 1).coerceAtLeast(0))
                        } catch (_: Exception) {
                        }
                    }
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("cel-duplicate")
            ) { Text(stringResource(R.string.cel_duplicate), maxLines = 1) }
            TextButton(
                onClick = {
                    if (!deleteArmed) {
                        deleteArmed = true
                    } else {
                        stopPlay()
                        val victim = cur
                        surface.frameOp { deleteFrame(victim) }
                        if (cur > 0) cur--
                        deleteArmed = false
                        refresh()
                    }
                },
                enabled = count > 1,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("cel-delete")
            ) {
                Text(
                    stringResource(
                        if (deleteArmed) R.string.cel_delete_confirm
                        else R.string.cel_delete
                    ),
                    maxLines = 1
                )
            }
            TextButton(
                onClick = { showSettings = !showSettings },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("timeline-settings")
            ) {
                Text(
                    stringResource(R.string.timeline_settings),
                    maxLines = 1
                )
            }
            // V+2 reorder: current cel one step left/right (same tactile
            // pattern as the layer DnD, one engine nMoveCel call).
            TextButton(
                onClick = {
                    stopPlay()
                    if (cur > 0) {
                        var moved = -1
                        surface.frameOp { if (moveCel(cur, cur - 1)) moved = cur - 1 }
                        if (moved >= 0) {
                            val a = holds[cur]
                            val b = holds[cur - 1]
                            if (a == null) holds.remove(cur - 1) else holds[cur - 1] = a
                            if (b == null) holds.remove(cur) else holds[cur] = b
                            cur = moved
                            gotoFrame(moved)
                        } else refresh()
                    }
                },
                enabled = cur > 0,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("cel-move-left")
            ) { Text("‹", maxLines = 1) }
            TextButton(
                onClick = {
                    stopPlay()
                    if (cur < count - 1) {
                        var moved = -1
                        surface.frameOp { if (moveCel(cur, cur + 1)) moved = cur + 1 }
                        if (moved >= 0) {
                            val a = holds[cur]
                            val b = holds[cur + 1]
                            if (a == null) holds.remove(cur + 1) else holds[cur + 1] = a
                            if (b == null) holds.remove(cur) else holds[cur] = b
                            gotoFrame(moved)
                        } else refresh()
                    }
                },
                enabled = cur < count - 1,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("cel-move-right")
            ) { Text("›", maxLines = 1) }
        }
        // Second rideau (folded by default: the open drawer budget is
        // 175dp): precision fps slider, onion counts + strength, ghost
        // legend. Scrolls with the drawer content, never extends it.
        if (showSettings) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            R.string.timeline_fps, fps.toInt()
                        ),
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("fps-slider")
                    )
                }
                // Onion counts: one row per side (a single 8-item row
                // overflows narrow phones and pushes steppers off-screen).
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        stringResource(R.string.onion_before),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                    TextButton(
                        onClick = {
                            onionBefore = (onionBefore - 1).coerceIn(0, 4)
                            applyOnion()
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("onion-before-minus")
                    ) { Text("−", maxLines = 1) }
                    Text(
                        stringResource(
                            R.string.onion_count_before, onionBefore
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        modifier = Modifier.testTag("onion-before-count")
                    )
                    TextButton(
                        onClick = {
                            onionBefore = (onionBefore + 1).coerceIn(0, 4)
                            applyOnion()
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("onion-before-plus")
                    ) { Text("+", maxLines = 1) }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        stringResource(R.string.onion_after),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                    TextButton(
                        onClick = {
                            onionAfter = (onionAfter - 1).coerceIn(0, 4)
                            applyOnion()
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("onion-after-minus")
                    ) { Text("−", maxLines = 1) }
                    Text(
                        stringResource(
                            R.string.onion_count_after, onionAfter
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        modifier = Modifier.testTag("onion-after-count")
                    )
                    TextButton(
                        onClick = {
                            onionAfter = (onionAfter + 1).coerceIn(0, 4)
                            applyOnion()
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("onion-after-plus")
                    ) { Text("+", maxLines = 1) }
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
                            applyOnion()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("onion-strength-slider")
                    )
                }
                // V+2 hold stepper for the current cel (nSetCelHold
                // 1..32, one tick per hold unit at playback).
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        stringResource(R.string.v2_hold, holds[cur] ?: 1),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        modifier = Modifier.testTag("hold-value")
                    )
                    TextButton(
                        onClick = {
                            val v = ((holds[cur] ?: 1) - 1).coerceIn(1, 32)
                            surface.frameOp { setCelHold(cur, v) }
                            holds[cur] = v
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("hold-minus")
                    ) { Text("−", maxLines = 1) }
                    TextButton(
                        onClick = {
                            val v = ((holds[cur] ?: 1) + 1).coerceIn(1, 32)
                            surface.frameOp { setCelHold(cur, v) }
                            holds[cur] = v
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("hold-plus")
                    ) { Text("+", maxLines = 1) }
                }
                // Ghost legend: tinted dots + before/after words
                // (display convention, never engine pixels).
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.testTag("onion-legend")
                ) {
                    Text(
                        "●",
                        color = ONION_BEFORE,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        stringResource(R.string.onion_before),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                    Text(
                        "●",
                        color = ONION_AFTER,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        stringResource(R.string.onion_after),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                }
            }
        }
        // M7b — clip audio + WebM export row (presentation only; the
        // MainActivity lane owns import/export, the strip just reports).
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (audioName == null) {
                TextButton(
                    onClick = onImportAudio,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(
                        stringResource(R.string.audio_import),
                        maxLines = 1
                    )
                }
            } else {
                Text(
                    audioName,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
                TextButton(
                    onClick = onClearAudio,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(
                        stringResource(R.string.audio_clear),
                        maxLines = 1
                    )
                }
            }
            TextButton(
                enabled = !exporting,
                onClick = onExportWebm,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(
                    stringResource(
                        if (exporting) R.string.exporting
                        else R.string.export_webm
                    ),
                    maxLines = 1
                )
            }
            TextButton(
                enabled = !exporting,
                onClick = { onAiUpscale(cur) },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(
                    stringResource(R.string.ai_upscale),
                    maxLines = 1
                )
            }
        }
    }
    // Play steps only refresh the current thumb (full refresh waits for
    // pause / scrub release / cel ops) to stay under 16 ms/frame.
    LaunchedEffect(playTick.value) {
        if (playing) refreshThumb(cur)
    }
}

// Render-thread-safe frame-count read: the strip calls this from the
// UI thread between frames, the same pattern as layerState(). Frame
// ops are discrete queue items, so a torn read cannot corrupt engine
// state (worst case: one stale thumbnail row for a frame).
private fun EngineSurface.frameOpCount(): Int =
    try { frameCount().coerceAtLeast(1) } catch (_: Exception) { 1 }
