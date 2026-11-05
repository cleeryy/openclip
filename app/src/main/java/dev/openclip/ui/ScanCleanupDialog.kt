// Calque — issue #17: "Scan cleanup (AI, documents)" dialog.
// Runs the bundled document-denoise model on the active raster layer
// (export → inference → blit back, one undo step) with a mix factor so
// artwork is never silently pushed through a document model.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.openclip.R
import dev.openclip.media.AiScanCleanup
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Modal dialog: mix factor, progress, cancel. The work happens on a
 * background thread; the engine is only touched to export the layer and
 * to blit the result back (both thread-safe).
 */
@Composable
fun ScanCleanupDialog(
    open: Boolean,
    surface: EngineSurface,
    onClose: () -> Unit,
    onStatus: (String) -> Unit,
    onApplied: () -> Unit
) {
    if (!open) return
    val ctx = LocalContext.current
    var mix by remember { mutableFloatStateOf(1f) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val cancelled = remember { AtomicBoolean(false) }

    AlertDialog(
        onDismissRequest = { if (!running) onClose() },
        title = { Text(stringResource(R.string.scan_cleanup_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.scan_cleanup_hint))
                Text(
                    stringResource(
                        R.string.scan_cleanup_mix,
                        (mix * 100).roundToInt()
                    )
                )
                Slider(
                    value = mix,
                    onValueChange = { mix = it },
                    enabled = !running,
                    modifier = Modifier.testTag("scan-mix")
                )
                if (running) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("${(progress * 100).roundToInt()} %")
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (running) {
                    TextButton(onClick = { cancelled.set(true) }) {
                        Text(stringResource(R.string.scan_cleanup_cancel))
                    }
                } else {
                    TextButton(onClick = {
                        val index = surface.activeLayerIndex()
                        if (index < 0) {
                            onStatus(ctx.getString(R.string.scan_cleanup_failed))
                            return@TextButton
                        }
                        val dir = File(ctx.cacheDir, "scan-cleanup").apply { mkdirs() }
                        val input = File(dir, "in-${System.currentTimeMillis()}.png")
                        if (!surface.saveLayerPng(index, input.absolutePath)) {
                            onStatus(ctx.getString(R.string.scan_cleanup_failed))
                            input.delete()
                            return@TextButton
                        }
                        running = true
                        cancelled.set(false)
                        progress = 0f
                        val factor = mix
                        Thread {
                            var done = false
                            try {
                                val bmp = BitmapFactory.decodeFile(input.absolutePath)
                                if (bmp != null) {
                                    val cleaned = AiScanCleanup.cleanup(
                                        ctx, bmp, factor,
                                        progress = { p -> progress = p },
                                        isCancelled = { cancelled.get() }
                                    )
                                    bmp.recycle()
                                    if (cleaned != null) {
                                        val outp = File(dir, "out-${System.currentTimeMillis()}.png")
                                        val wrote = outp.outputStream().use { o ->
                                            cleaned.compress(
                                                android.graphics.Bitmap.CompressFormat.PNG,
                                                100, o
                                            )
                                        }
                                        cleaned.recycle()
                                        if (wrote) {
                                            done = surface.blitPngIntoLayer(
                                                index, outp.absolutePath
                                            )
                                            outp.delete()
                                        }
                                    }
                                }
                            } catch (_: Exception) {
                                done = false
                            } finally {
                                input.delete()
                                running = false
                            }
                            onStatus(
                                ctx.getString(
                                    if (done) R.string.scan_cleanup_done
                                    else R.string.scan_cleanup_failed
                                )
                            )
                            if (done) onApplied()
                        }.start()
                    }) {
                        Text(
                            stringResource(R.string.scan_cleanup_apply),
                            modifier = Modifier.width(96.dp)
                        )
                    }
                    TextButton(onClick = onClose) {
                        Text(stringResource(R.string.scan_cleanup_close))
                    }
                }
            }
        }
    )
}
