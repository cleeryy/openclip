// OpenClip M7b — WebM export, app-side only (no JNI): full-res frame
// bitmaps in index order through a MediaCodec VP8 surface-input encoder
// + MediaMuxer OUTPUT_FORMAT_WEBM at the clip fps. Frames are paced at
// 1/fps through the input surface (wall-clock presentation times), EOS
// after the last frame. Any failure returns false (the caller surfaces
// export_failed); this function never throws.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.media

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File

object ExportWebm {
    private const val MIME = "video/x-vnd.on2.vp8"

    fun export(bitmaps: List<Bitmap>, fps: Float, out: File): Boolean {
        if (bitmaps.isEmpty()) return false
        val w = bitmaps[0].width
        val h = bitmaps[0].height
        if (w <= 0 || h <= 0) return false
        if (bitmaps.any { it.width != w || it.height != h || it.isRecycled }) return false
        val rate = fps.coerceIn(1f, 60f)
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var surface: Surface? = null
        var ok = false
        try {
            codec = MediaCodec.createEncoderByType(MIME)
            val format = MediaFormat.createVideoFormat(MIME, w, h)
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            format.setInteger(
                MediaFormat.KEY_BIT_RATE,
                (w * h * rate * 0.15f).toInt().coerceIn(100_000, 8_000_000)
            )
            format.setFloat(MediaFormat.KEY_FRAME_RATE, rate)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM)
            val enc = codec
            val mx = muxer
            var trackIndex = -1
            var muxerStarted = false
            val info = MediaCodec.BufferInfo()
            fun poll(timeoutUs: Long, eos: Boolean) {
                var guard = 0
                while (guard < 600) {
                    guard++
                    when (val idx = enc.dequeueOutputBuffer(info, timeoutUs)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                trackIndex = mx.addTrack(enc.outputFormat)
                                mx.start()
                                muxerStarted = true
                            }
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> if (!eos) return
                        else -> if (idx >= 0) {
                            if (muxerStarted && info.size > 0 &&
                                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                            ) {
                                mx.writeSampleData(
                                    trackIndex, enc.getOutputBuffer(idx)!!, info
                                )
                            }
                            enc.releaseOutputBuffer(idx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                            if (!eos) return
                        }
                    }
                }
            }
            val gapMs = (1000f / rate).toLong()
            for (bmp in bitmaps) {
                val c = surface.lockCanvas(null)
                try {
                    c.drawBitmap(bmp, 0f, 0f, null)
                } finally {
                    surface.unlockCanvasAndPost(c)
                }
                poll(0L, false)
                Thread.sleep(gapMs)
            }
            codec.signalEndOfInputStream()
            poll(10_000L, true)
            ok = muxerStarted && out.exists() && out.length() > 0
        } catch (_: Exception) {
            ok = false
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            try {
                surface?.release()
            } catch (_: Exception) {
            }
            try {
                muxer?.stop()
            } catch (_: Exception) {
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
            if (!ok) out.delete()
        }
        return ok
    }
}
