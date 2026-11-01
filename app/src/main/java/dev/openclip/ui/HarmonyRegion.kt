// OpenClip issue #15 — colour harmony: sample a REGION (app-side, 0 JNI).
//
// The harmony guide used to derive its base from the current ink only.
// This unit adds the region half of the feature: a rectangle expressed in
// ENGINE pixels (never screen pixels) is reduced to a small weighted
// palette whose dominant colour drives the harmony base hue.
//
// Why engine pixels: the rect comes from `screenToEngine` at gesture time
// and the selection mask is engine-sized, so zoom, rotation, flip and pan
// cannot change a single sampled byte. The pixels themselves come from
// the displayed COMPOSITE (same pull as the pipette, `pullFrameFull`), so
// hidden layers, adjustment layers and masks are honoured. When that pull
// fails the caller keeps the ink colour and says so (`REASON_FALLBACK`):
// documented fallback, no silent whole-layer sample.
//
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

object HarmonyRegion {
    /** Engine-pixel rectangle: x/y inclusive, w/h > 0 (right/bottom exclusive). */
    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
        val right: Int get() = x + w
        val bottom: Int get() = y + h
    }

    /** One palette entry: representative colour + total weight (alpha count). */
    data class Swatch(val hex: String, val weight: Int)

    /**
     * Result of one region sample. [dominant] is the heaviest entry and
     * drives the harmony base hue; [masked] tells the UI the active
     * selection narrowed the region.
     */
    data class Sample(
        val dominant: String,
        val swatches: List<Swatch>,
        val rect: Rect,
        val pixels: Int,
        val masked: Boolean
    )

    const val REASON_NO_REGION = "no_region"
    const val REASON_FALLBACK = "fallback"
    const val REASON_EMPTY = "empty"

    /** 5 bits per channel: 32^3 bins keep the palette small and stable. */
    private const val BIN_SHIFT = 3

    /**
     * Normalise a drag (engine coordinates, any order) to a clamped rect.
     * A zero-size drag becomes one pixel, so a tap samples exactly the
     * pixel under the finger instead of nothing.
     */
    fun rectFromDrag(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        canvasW: Int,
        canvasH: Int
    ): Rect? {
        if (canvasW <= 0 || canvasH <= 0) return null
        val l = floor(minOf(x0, x1)).toInt().coerceIn(0, canvasW - 1)
        val t = floor(minOf(y0, y1)).toInt().coerceIn(0, canvasH - 1)
        val r = ceil(maxOf(x0, x1)).toInt().coerceIn(l + 1, canvasW)
        val b = ceil(maxOf(y0, y1)).toInt().coerceIn(t + 1, canvasH)
        return Rect(l, t, r - l, b - t)
    }

    /** Inclusive bounding box of the selected pixels (null = empty mask). */
    fun boundsOf(mask: ByteArray, w: Int, h: Int): Rect? {
        if (w <= 0 || h <= 0 || mask.size < w * h) return null
        var x0 = w
        var y0 = h
        var x1 = -1
        var y1 = -1
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                if (mask[base + x] == 0.toByte()) continue
                if (x < x0) x0 = x
                if (y < y0) y0 = y
                if (x > x1) x1 = x
                if (y > y1) y1 = y
            }
        }
        if (x1 < 0) return null
        return Rect(x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }

    /**
     * Reduce `pixels` (ARGB, exactly `rect.w * rect.h`, row-major) to a
     * palette of at most [max] colours, weighted by the selection mask
     * alpha when one is supplied. Transparent composite pixels and pixels
     * outside the mask never contribute. Deterministic: equal weights sort
     * by hex, so the same zone always yields the same palette.
     */
    fun palette(
        pixels: IntArray,
        rect: Rect,
        mask: ByteArray?,
        maskW: Int,
        maskH: Int,
        max: Int = 6
    ): Sample? {
        if (rect.w <= 0 || rect.h <= 0) return null
        if (pixels.size < rect.w * rect.h) return null
        val bins = HashMap<Int, LongArray>()
        var considered = 0
        for (y in 0 until rect.h) {
            val my = rect.y + y
            for (x in 0 until rect.w) {
                var weight = 1
                if (mask != null) {
                    val mx = rect.x + x
                    if (mx < 0 || my < 0 || mx >= maskW || my >= maskH) continue
                    weight = mask[my * maskW + mx].toInt() and 0xFF
                    if (weight == 0) continue
                }
                val argb = pixels[y * rect.w + x]
                if ((argb ushr 24) == 0) continue
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                val key = ((r shr BIN_SHIFT) shl 10) or
                    ((g shr BIN_SHIFT) shl 5) or (b shr BIN_SHIFT)
                val acc = bins.getOrPut(key) { LongArray(4) }
                acc[0] += weight.toLong()
                acc[1] += (r * weight).toLong()
                acc[2] += (g * weight).toLong()
                acc[3] += (b * weight).toLong()
                considered++
            }
        }
        if (bins.isEmpty()) return null
        val swatches = bins.values.map { acc ->
            val w = acc[0]
            Swatch(
                rgbToHex(
                    (acc[1].toDouble() / w).roundToInt(),
                    (acc[2].toDouble() / w).roundToInt(),
                    (acc[3].toDouble() / w).roundToInt()
                ),
                w.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
        }.sortedWith(compareByDescending<Swatch> { it.weight }.thenBy { it.hex })
        return Sample(
            dominant = swatches.first().hex,
            swatches = swatches.take(max.coerceAtLeast(1)),
            rect = rect,
            pixels = considered,
            masked = mask != null
        )
    }
}

/**
 * Session state of the harmony panel (hoisted in MainActivity so the
 * sample survives a dock tab switch). Not persisted: a sample is cheap
 * to take again and a stale one after reopening a document would lie.
 */
class HarmonyState {
    /** Drag-rectangle mode is armed on the canvas. */
    var armed by mutableStateOf(false)

    /** Last successful region sample (null = harmony follows the ink). */
    var sample by mutableStateOf<HarmonyRegion.Sample?>(null)

    /**
     * Outcome of the last attempt: null on success, otherwise one of the
     * [HarmonyRegion] REASON_* codes. Drives the panel's honest status
     * line even when the drag was committed while the panel was hidden.
     */
    var outcome by mutableStateOf<String?>(null)
}

/**
 * Pull the displayed composite and reduce one engine-space zone to a
 * palette. `rect == null` means "the active selection bounds"; when
 * neither exists the call reports [HarmonyRegion.REASON_NO_REGION].
 *
 * Runs through [EngineSurface.pullFrameFull] (the pipette path), so the
 * result is the COMPOSITE, and it is unaffected by zoom/rotation because
 * the rectangle is already in engine pixels.
 */
fun sampleHarmonyRegionAsync(
    surface: EngineSurface,
    rect: HarmonyRegion.Rect?,
    max: Int = 6,
    cb: (HarmonyRegion.Sample?, String?) -> Unit
) {
    val cw = surface.canvasWidth()
    val ch = surface.canvasHeight()
    val mask = if (surface.hasSelection()) surface.exportSelectionBits() else null
    val zone = rect ?: mask?.let { HarmonyRegion.boundsOf(it, cw, ch) }
    if (zone == null) {
        cb(null, HarmonyRegion.REASON_NO_REGION)
        return
    }
    surface.pullFrameFull(surface.currentFrameIndex()) { bmp ->
        if (bmp == null) {
            cb(null, HarmonyRegion.REASON_FALLBACK)
            return@pullFrameFull
        }
        try {
            if (bmp.width < cw || bmp.height < ch) {
                cb(null, HarmonyRegion.REASON_FALLBACK)
                return@pullFrameFull
            }
            val px = IntArray(zone.w * zone.h)
            bmp.getPixels(px, 0, zone.w, zone.x, zone.y, zone.w, zone.h)
            val sample = HarmonyRegion.palette(px, zone, mask, cw, ch, max)
            cb(sample, if (sample == null) HarmonyRegion.REASON_EMPTY else null)
        } catch (_: Exception) {
            cb(null, HarmonyRegion.REASON_FALLBACK)
        } finally {
            bmp.recycle()
        }
    }
}
