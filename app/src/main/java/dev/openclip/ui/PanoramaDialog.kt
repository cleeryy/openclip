// OpenClip issue #16 — multi-image panorama stitch (SAF import,
// preview, cancel). The JNI call is blocking, so it runs on a worker
// thread while the dialog polls nPanoramaProgress and can raise
// nPanoramaCancel. No document mutation happens here: the engine writes
// a PNG and the app opens it through the existing nLoadDocument path.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.openclip.R
import java.io.File
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

private data class PanoReport(
    val width: Int,
    val height: Int,
    val images: Int,
    val meanScore: Float,
    val offsets: List<PanoOffset>
)

private data class PanoOffset(val x: Int, val y: Int, val score: Float)

private fun decodeThumb(path: String, maxSide: Int = 192): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxSide * 2 ||
            bounds.outHeight / sample > maxSide * 2
        ) sample *= 2
        BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}

private fun displayName(ctx: Context, uri: Uri): String {
    return try {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        } ?: "image"
    } catch (_: Exception) {
        "image"
    }
}

private fun copyToCache(ctx: Context, uri: Uri, cacheDir: File, stamp: Long): File? {
    return try {
        val raw = displayName(ctx, uri)
        // Keep the extension (stb sniffs content; the name is for the user)
        // and make the cache name unique so repeated picks never collide.
        val safe = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(64)
            .ifBlank { "image.png" }
        val dst = File(cacheDir, "pano_${stamp}_$safe")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            dst.outputStream().use { out -> input.copyTo(out) }
        } ?: return null
        if (dst.length() <= 0L) {
            dst.delete()
            null
        } else dst
    } catch (_: Exception) {
        null
    }
}

/**
 * Multi-image stitch dialog. [initialImages] exists for the
 * instrumented gate (the SAF picker cannot be automated): the job flow
 * is identical to a user pick.
 */
@Composable
fun PanoramaDialog(
    surface: EngineSurface,
    cacheDir: File,
    onDismiss: () -> Unit,
    onOpenDocument: (String) -> Unit,
    onStatus: (String) -> Unit,
    initialImages: List<String> = emptyList()
) {
    val ctx = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    var images by remember { mutableStateOf(initialImages) }
    var feather by remember { mutableStateOf(48) }
    var exposure by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var cancelling by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<PanoReport?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var outputPath by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val tempFiles = remember { mutableListOf<File>() }

    DisposableEffect(Unit) {
        onDispose {
            preview?.recycle()
            for (f in tempFiles) {
                try {
                    f.delete()
                } catch (_: Exception) {
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val stamp = System.currentTimeMillis()
            val added = uris.mapNotNull { u ->
                copyToCache(ctx, u, cacheDir, stamp + u.hashCode())
            }
            if (added.isNotEmpty()) {
                tempFiles.addAll(added)
                images = images + added.map { it.absolutePath }
                report = null
                preview?.recycle()
                preview = null
                outputPath = null
                error = null
            }
        }
    }

    fun startStitch() {
        if (images.size < 2 || running) return
        running = true
        cancelling = false
        progress = 0f
        error = null
        report = null
        preview?.recycle()
        preview = null
        outputPath = null
        val out = File(cacheDir, "panorama_${System.currentTimeMillis()}.png")
        val json = JSONObject().apply {
            put("images", JSONArray(images))
            put("outPath", out.absolutePath)
            put("feather", feather)
            put("exposure", exposure)
            put("maxPixels", 16 * 1024 * 1024)
            put("minOverlap", 16)
        }.toString()
        Thread {
            val raw = surface.stitchPanorama(json)
            var parsed: JSONObject? = null
            try {
                parsed = JSONObject(raw)
            } catch (_: Exception) {
            }
            main.post {
                running = false
                cancelling = false
                val o = parsed
                if (o == null) {
                    error = ctx.getString(R.string.pano_err_unknown, raw)
                    onStatus(ctx.getString(R.string.pano_failed))
                    return@post
                }
                if (o.optBoolean("ok")) {
                    val path = o.optString("path", out.absolutePath)
                    outputPath = path
                    val offsets = mutableListOf<PanoOffset>()
                    val arr = o.optJSONArray("offsets")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val e = arr.optJSONObject(i) ?: continue
                            offsets.add(
                                PanoOffset(
                                    e.optInt("x"), e.optInt("y"),
                                    e.optDouble("score", 0.0).toFloat()
                                )
                            )
                        }
                    }
                    report = PanoReport(
                        o.optInt("width"), o.optInt("height"),
                        o.optInt("images"), o.optDouble("meanScore", 0.0)
                            .toFloat(),
                        offsets
                    )
                    preview = decodeThumb(path, 1024)
                    progress = 1f
                    onStatus(ctx.getString(R.string.pano_done_short))
                } else if (o.optBoolean("cancelled")) {
                    onStatus(ctx.getString(R.string.pano_cancelled))
                } else {
                    error = localizePanoError(ctx, o.optString("error", ""))
                    onStatus(ctx.getString(R.string.pano_failed))
                }
            }
        }.start()
    }

    LaunchedEffect(running) {
        while (running) {
            progress = surface.panoramaProgress()
            delay(120)
        }
    }

    Dialog(onDismissRequest = { if (!running) onDismiss() }) {
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .heightIn(max = 720.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                Text(
                    stringResource(R.string.pano_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    stringResource(R.string.pano_limit_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { picker.launch(arrayOf("image/*")) },
                        enabled = !running && images.size < 32,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("pano-add")
                    ) { Text(stringResource(R.string.pano_add)) }
                    Text(
                        stringResource(R.string.pano_count, images.size),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (images.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pano-images"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(images) { index, path ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                ) {
                                    val bmp = remember(path) { decodeThumb(path) }
                                    if (bmp != null) {
                                        Image(
                                            bitmap = bmp.asImageBitmap(),
                                            contentDescription = stringResource(
                                                R.string.pano_image_n, index + 1
                                            ),
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.size(72.dp)
                                        )
                                    } else {
                                        Text(
                                            stringResource(R.string.pano_bad_thumb),
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(4.dp)
                                        )
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        if (!running) {
                                            images = images.filterIndexed { i, _ ->
                                                i != index
                                            }
                                            report = null
                                            preview?.recycle()
                                            preview = null
                                            outputPath = null
                                        }
                                    },
                                    enabled = !running && !initialImages.contains(path),
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("pano-remove-$index")
                                ) {
                                    Text(
                                        stringResource(R.string.pano_remove),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    stringResource(R.string.pano_feather, feather),
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = feather.toFloat(),
                    onValueChange = { feather = it.toInt() },
                    valueRange = 0f..128f,
                    enabled = !running,
                    modifier = Modifier.testTag("pano-feather")
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = exposure,
                        onCheckedChange = { exposure = it },
                        enabled = !running,
                        modifier = Modifier.testTag("pano-exposure")
                    )
                    Text(
                        stringResource(R.string.pano_exposure),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                if (running) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pano-progress")
                    )
                    Text(
                        if (cancelling) stringResource(R.string.pano_cancelling)
                        else stringResource(
                            R.string.pano_working,
                            (progress * 100).toInt()
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("pano-error")
                    )
                }
                preview?.let { bmp ->
                    Text(
                        stringResource(R.string.pano_preview),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = stringResource(R.string.pano_preview_desc),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .testTag("pano-preview")
                    )
                    report?.let { r ->
                        Text(
                            stringResource(
                                R.string.pano_done, r.images, r.width, r.height,
                                (r.meanScore * 100f).toInt()
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("pano-report")
                        )
                        r.offsets.drop(1).take(2).forEachIndexed { i, o ->
                            Text(
                                stringResource(
                                    R.string.pano_offset, i + 2, o.x, o.y,
                                    (o.score * 100f).toInt()
                                ),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    if (running) {
                        OutlinedButton(
                            onClick = {
                                cancelling = true
                                surface.panoramaCancel()
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("pano-cancel")
                        ) { Text(stringResource(R.string.cancel)) }
                    } else {
                        Button(
                            onClick = { startStitch() },
                            enabled = images.size >= 2,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("pano-stitch")
                        ) {
                            if (images.size < 2) {
                                Text(stringResource(R.string.pano_need_two))
                            } else {
                                Text(stringResource(R.string.pano_stitch))
                            }
                        }
                    }
                    val out = outputPath
                    if (!running && out != null) {
                        Button(
                            onClick = { onOpenDocument(out) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("pano-open")
                        ) { Text(stringResource(R.string.pano_open)) }
                    }
                    TextButton(
                        onClick = onDismiss,
                        enabled = !running,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text(stringResource(R.string.close)) }
                }
                if (images.size < 2) {
                    Text(
                        stringResource(R.string.pano_need_two),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Engine errors are English and precise; map the documented classes to
 *  localized, user-facing messages while keeping the raw text visible. */
private fun localizePanoError(ctx: Context, raw: String): String {
    val text = when {
        raw.contains("featureless") -> ctx.getString(R.string.pano_err_flat)
        raw.contains("overlap") || raw.contains("weak") ->
            ctx.getString(R.string.pano_err_overlap)
        raw.contains("inconsistent") || raw.contains("dimensions") ->
            ctx.getString(R.string.pano_err_dimensions)
        raw.contains("memory") || raw.contains("maxInputPixels") ->
            ctx.getString(R.string.pano_err_memory)
        raw.contains("at least two") || raw.contains("at most 32") ->
            ctx.getString(R.string.pano_err_count)
        raw.contains("decode") || raw.contains("malformed") ->
            ctx.getString(R.string.pano_err_image)
        else -> ctx.getString(R.string.pano_err_unknown, raw)
    }
    return if (raw.isNotEmpty() && !text.contains(raw)) "$text ($raw)" else text
}
