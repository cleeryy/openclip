// OpenClip B2 §5 (ARCHITECTURE.md "B2 additions") — timelapse recorder.
// Session-only: every N committed strokes the current composite is
// pulled, scaled to a 480px longest side and written as a PNG in
// cacheDir/timelapse (bounded 240 frames, oldest dropped and counted).
// Export reuses ExportWebm.export (VP8 + MediaMuxer, M7b lane).
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

object Timelapse {
    const val MAX_FRAMES = 240
    const val MAX_SIDE = 480
    const val DEFAULT_EVERY = 5

    /** Pure-ish recorder state (capture/export touch the filesystem). */
    class Recorder {
        var everyN: Int = DEFAULT_EVERY
            set(value) {
                field = value.coerceIn(1, 50)
            }

        var strokes: Int = 0
            private set
        var dropped: Int = 0
            private set
        val frames = mutableListOf<File>()
        // Monotonic file index (pruning must never reuse a name and
        // overwrite the newest still).
        private var seq: Int = 0

        fun reset() {
            strokes = 0
            dropped = 0
            seq = 0
            frames.clear()
        }

        /** Called per committed stroke; true when a frame is due. */
        fun onStroke(): Boolean {
            strokes++
            return strokes % everyN == 0
        }

        /**
         * Store one frame. Returns false on a bad bitmap / write error
         * (never throws). Oldest frames are dropped past MAX_FRAMES.
         */
        fun capture(bmp: Bitmap?, dir: File): Boolean {
            if (bmp == null || bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0)
                return false
            if (!dir.exists() && !dir.mkdirs()) return false
            val scaled = scaleDown(bmp)
            val f = File(dir, "frame-%04d.png".format(seq++))
            val ok = try {
                f.outputStream().use {
                    scaled.compress(Bitmap.CompressFormat.PNG, 80, it)
                }
            } catch (_: Exception) {
                false
            }
            if (scaled !== bmp) scaled.recycle()
            if (!ok) return false
            frames.add(f)
            while (frames.size > MAX_FRAMES) {
                val old = frames.removeAt(0)
                try {
                    old.delete()
                } catch (_: Exception) {
                }
                dropped++
            }
            return true
        }

        /** Decode + encode the stored frames through the WebM lane. */
        fun exportWebm(fps: Float, out: File): Boolean {
            if (frames.isEmpty()) return false
            val bitmaps = mutableListOf<Bitmap>()
            try {
                for (f in frames) {
                    val b = BitmapFactory.decodeFile(f.absolutePath) ?: continue
                    bitmaps.add(b)
                }
                if (bitmaps.isEmpty()) return false
                return ExportWebm.export(bitmaps, fps, out)
            } catch (_: Exception) {
                return false
            } finally {
                bitmaps.forEach { if (!it.isRecycled) it.recycle() }
            }
        }
    }

    /** Scale a composite down to MAX_SIDE (never up); alpha preserved. */
    fun scaleDown(bmp: Bitmap): Bitmap {
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= MAX_SIDE) return bmp
        val k = MAX_SIDE.toFloat() / longest
        val w = (bmp.width * k).toInt().coerceAtLeast(1)
        val h = (bmp.height * k).toInt().coerceAtLeast(1)
        return try {
            Bitmap.createScaledBitmap(bmp, w, h, true)
        } catch (_: Exception) {
            bmp
        }
    }
}
