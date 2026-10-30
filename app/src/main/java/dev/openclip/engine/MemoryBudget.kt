// OpenClip — issue #32 §3: explicit memory policy (app side).
//
// The engine owns the pixels, so the caps live in the native History and
// in the document guards; this object only derives the two budgets from
// the device RAM and exposes them to the UI (new-canvas estimate, settings
// policy line). Values are deliberately conservative: a painting app that
// dies of OOM mid-stroke is not a beta product.
//
// Measured on the dev emulator (Android 34 x86_64, 1536 MB guest):
//   * 4096x4096 RGBA8 = 67.1 MB/layer, 2048x1536 = 12.6 MB/layer
//   * doc budget 512 MB -> 7 layers at 4096², 40 at 2048x1536
//   * undo budget 153 MB -> 64 stroke steps or ~1 structural snapshot
//     of a 70 MB document (the snapshot keeps a before+after pair).
// A 4 GB+ tablet gets 1365 MB / 409 MB: 20 layers at 4096².
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.engine

import android.app.ActivityManager
import android.content.Context

object MemoryBudget {
    const val MB: Long = 1024L * 1024L

    const val MIN_DOC_BYTES: Long = 256L * MB
    const val MAX_DOC_BYTES: Long = 2048L * MB
    const val MIN_UNDO_BYTES: Long = 64L * MB
    const val MAX_UNDO_BYTES: Long = 512L * MB

    /** Document surfaces budget: ~1/3 of RAM, the rest is Android + Java. */
    fun docBytesFor(totalMem: Long): Long {
        val t = if (totalMem > 0) totalMem else 2048L * MB
        return (t / 3).coerceIn(MIN_DOC_BYTES, MAX_DOC_BYTES)
    }

    /** Undo budget: ~1/10 of RAM, clamped [64 MB, 512 MB]. */
    fun undoBytesFor(totalMem: Long): Long {
        val t = if (totalMem > 0) totalMem else 2048L * MB
        return (t / 10).coerceIn(MIN_UNDO_BYTES, MAX_UNDO_BYTES)
    }

    fun totalMemBytes(ctx: Context): Long {
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.totalMem
        } catch (_: Exception) {
            0L
        }
    }

    /** (docBytes, undoBytes) for this device. */
    fun fromContext(ctx: Context): Pair<Long, Long> {
        val total = totalMemBytes(ctx)
        return docBytesFor(total) to undoBytesFor(total)
    }

    fun mb(bytes: Long): Long = bytes / MB
}
