// OpenClip visual-audit fixes (spec docs/audit-visuel.md V1-V18) —
// shared app-side helpers. No engine change, no new engine symbol.
//
// - scrollFade(): 12dp end/start edge fade for scrollable chrome rows
//   (V4/V8) + overflow registry so tests assert the affordance.
// - BrushPushThrottler: local-preview-immediate, engine push throttled
//   to ~33ms + forced on drag end (V14).
// - ThumbLru: bounded recycled thumbnail cache (V13).
// - UndoAvailability: hoisted canUndo/canRedo shared by ToolPalette
//   (writer) and CommandBar (reader, V10).
// - RecomposeTestHooks: composition counters for V15.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Overflow registry for scrollable chrome rows (V4 test hook): the
 * [scrollFade] modifier reports here whether the row actually overflows,
 * so tests assert the affordance without pixel-reading. Plain
 * concurrent map (never snapshot state): the modifier writes from
 * inside the draw scope, where snapshot writes are illegal
 * (RenderNode "recording in progress" crash).
 */
object ScrollFadeTestHooks {
    private val overflowing = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun set(tag: String, over: Boolean) {
        overflowing[tag] = over
    }

    fun isOverflowing(tag: String): Boolean = overflowing[tag] == true

    fun reset() {
        overflowing.clear()
    }
}

/**
 * Edge fade for a horizontally scrollable chrome row (V4/V8): 12dp
 * gradient at the forward edge while more content lies ahead, mirrored
 * at the back edge once scrolled. Drawn only (no touch-eating overlay);
 * the overflow state is reported to [ScrollFadeTestHooks] under [tag]
 * so tests assert the affordance.
 */
fun Modifier.scrollFade(
    tag: String,
    scroll: ScrollState,
    edge: Dp = 12.dp,
    edgeColor: Color = Color(0xFF202026)
): Modifier = this
    .drawWithContent {
        ScrollFadeTestHooks.set(
            tag, scroll.canScrollForward || scroll.canScrollBackward
        )
        drawContent()
        val px = edge.toPx()
        if (px <= 0f) return@drawWithContent
        if (scroll.canScrollForward) {
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to edgeColor.copy(alpha = 0f),
                    1f to edgeColor,
                    startX = size.width - px,
                    endX = size.width
                )
            )
        }
        if (scroll.canScrollBackward) {
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to edgeColor,
                    1f to edgeColor.copy(alpha = 0f),
                    startX = 0f,
                    endX = px
                )
            )
        }
    }

/** Test-only counters for the visual-audit instrumented suite. */
object BrushPushTestHooks {
    /** Effective engine pushes issued through [BrushPushThrottler]. */
    var effectivePushes: Int = 0

    fun reset() {
        effectivePushes = 0
    }
}

/**
 * Slider-drag throttle (V14): the caller applies the value locally on
 * every tick (instant preview) and routes the engine push through
 * here — at most one push per 33ms during the drag, plus a forced
 * trailing push on release so the final value is always exact.
 */
class BrushPushThrottler(
    private val now: () -> Long = { SystemClock.uptimeMillis() },
    private val push: () -> Unit
) {
    private var lastPush = 0L
    private var trailing: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    @Synchronized
    fun onDragTick() {
        val t = now()
        if (t - lastPush >= 33L) {
            lastPush = t
            fire()
        } else if (trailing == null) {
            val delay = 33L - (t - lastPush)
            val run = Runnable {
                synchronized(this) {
                    trailing = null
                    lastPush = now()
                    fire()
                }
            }
            trailing = run
            handler.postDelayed(run, delay)
        }
    }

    @Synchronized
    fun onDragEnd() {
        trailing?.let { handler.removeCallbacks(it) }
        trailing = null
        lastPush = now()
        fire()
    }

    private fun fire() {
        BrushPushTestHooks.effectivePushes++
        try {
            push()
        } catch (_: Exception) {
        }
    }
}

/**
 * Bounded LRU thumbnail cache (V13): at most [max] bitmaps, evicted
 * (and out-of-range) entries are recycled like the LayerPanel LRU.
 */
class ThumbLru(private val max: Int = 24) {
    private val map = object : LinkedHashMap<Int, Bitmap>(max + 1, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Int, Bitmap>
        ): Boolean {
            if (size > max) {
                try {
                    eldest.value.recycle()
                } catch (_: Exception) {
                }
                return true
            }
            return false
        }
    }

    @Synchronized
    fun put(i: Int, bmp: Bitmap?) {
        if (bmp == null) return
        // Never recycle the replaced bitmap here: it may still be
        // displayed by the current composition (recycling it crashes the
        // next draw with "recording in progress"). Eviction and disposal
        // only ever touch entries that are no longer read, so those
        // paths recycle safely; the replaced bitmap is GC-freed.
        map.put(i, bmp)
    }

    @Synchronized
    fun get(i: Int): Bitmap? = map[i]

    @Synchronized
    fun dropFrom(count: Int) {
        // Copy first: get()/remove() on the access-order map reorder it,
        // which a live iterator reports as ConcurrentModification.
        // Removed entries are NOT recycled here: a just-deleted frame's
        // thumb may still sit in an in-flight display list (same hazard
        // as put-replace) — the LRU bound + GC reclaim the memory.
        val doomed = map.keys.filter { it >= count }
        for (k in doomed) {
            map.remove(k)
        }
    }

    @Synchronized
    fun clear() {
        map.clear()
    }

    @Synchronized
    fun snapshot(): Map<Int, Bitmap?> = map.toMap()

    @Synchronized
    fun size(): Int = map.size

    @Synchronized
    fun keys(): Set<Int> = map.keys.toSet()
}

/** Test hooks for the timeline strip (V13). */
object TimelineTestHooks {
    /** Latest thumbnail cache snapshot (frame -> bitmap). */
    var thumbSnapshot: () -> Map<Int, Bitmap?> = { emptyMap() }

    /** Cache bound (spec: 24). */
    var maxCache = 24

    /** Live frame count mirror (V11 pastille diagnostic). */
    var countOf: () -> Int = { 1 }
}

/** Hoisted undo/redo availability (V10). */
class UndoAvailability {
    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)
}

/** Composition counters (V15: isolated poke must not recompose the dock). */
object RecomposeTestHooks {
    var dockCompositions: Int = 0

    fun reset() {
        dockCompositions = 0
    }
}
