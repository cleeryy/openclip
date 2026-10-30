// OpenClip M7b — clip-level audio track (session-only, not stored in
// ORA). Thin state machine over platform MediaPlayer: attach (SAF cache
// copy path), play/pause/stop/release. play()/pause() never throw;
// failures surface as false so the timeline degrades to silent
// playback instead of crashing.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.media

import android.media.MediaPlayer

class ClipAudio {
    companion object {
        /** B2 §4: cel index -> clip time in ms (fps clamped to >= 1). */
        fun celToMs(frame: Int, fps: Float): Int =
            (frame.coerceAtLeast(0) * 1000f / fps.coerceAtLeast(1f)).toInt()
    }

    private var player: MediaPlayer? = null

    var fileName: String? = null
        private set

    val hasAudio: Boolean get() = player != null

    var isPlaying = false
        private set

    /** Attach an audio file by plain path (e.g. SAF cache copy). */
    fun attach(path: String, displayName: String): Boolean {
        clear()
        return try {
            val p = MediaPlayer()
            p.setDataSource(path)
            p.prepare()
            if (p.duration <= 0) {
                p.release()
                false
            } else {
                player = p
                fileName = displayName
                true
            }
        } catch (_: Exception) {
            player = null
            fileName = null
            false
        }
    }

    fun play(): Boolean {
        val p = player ?: return false
        return try {
            if (!isPlaying) p.start()
            isPlaying = true
            true
        } catch (_: Exception) {
            false
        }
    }

    fun pause() {
        try {
            if (isPlaying) player?.pause()
        } catch (_: Exception) {
            // Already torn down: state still flips to paused.
        } finally {
            isPlaying = false
        }
    }

    /**
     * B2 §4 (ARCHITECTURE.md "B2 additions"): seek the attached track
     * to `ms` (clamped to the duration). Never throws; false without
     * audio or on a torn-down player. Playback state is untouched
     * (seeking while paused stays paused).
     */
    fun seekTo(ms: Int): Boolean {
        val p = player ?: return false
        return try {
            val d = p.duration
            if (d <= 0) return false
            p.seekTo(ms.coerceIn(0, d))
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Current playback position in ms (0 without audio). */
    fun positionMs(): Int = try {
        player?.currentPosition ?: 0
    } catch (_: Exception) {
        0
    }

    fun durationMs(): Int = try {
        player?.duration ?: 0
    } catch (_: Exception) {
        0
    }

    fun clear() {
        isPlaying = false
        try {
            player?.stop()
        } catch (_: Exception) {
            // stop() on an idle player throws: ignore, we release anyway.
        }
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        fileName = null
    }
}
