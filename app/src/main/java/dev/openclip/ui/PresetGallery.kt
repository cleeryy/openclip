// OpenClip M2 — preset gallery: bundled clean-room .myb presets from
// assets plus SAF import (OpenDocument, octet-stream) validated through
// EngineHost.nLoadMyb. Same panel style as LayerPanel (title + list).
package dev.openclip.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.openclip.R

private val BUNDLED = listOf(
    "soft_round.myb",
    "hard_round.myb",
    "marker.myb",
    "airbrush.myb",
    "pencil.myb",
    "flat.myb"
)

private fun readAsset(ctx: Context, name: String): String =
    ctx.assets.open("presets/$name").bufferedReader().use { it.readText() }

private fun readUri(ctx: Context, uri: Uri): String? = try {
    ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
} catch (_: Exception) {
    null
}

@Composable
fun PresetGallery(
    surface: EngineSurface,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    var active by remember { mutableStateOf<String?>(null) }
    var importedXml by remember { mutableStateOf(mapOf<String, String>()) }
    var error by remember { mutableStateOf(false) }

    fun apply(name: String, xml: String): Boolean {
        val ok = try {
            surface.loadMyb(xml)
        } catch (_: Exception) {
            false
        }
        if (ok) {
            active = name
            error = false
        } else {
            error = true
        }
        return ok
    }

    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val xml = readUri(ctx, uri) ?: run { error = true; return@rememberLauncherForActivityResult }
        val base = uri.lastPathSegment?.substringAfterLast('/') ?: "imported.myb"
        // De-duplicate display names in-session; bytes stay in memory.
        var name = base
        var n = 2
        while (name in BUNDLED || name in importedXml) {
            name = base.removeSuffix(".myb") + "-$n.myb"
            n += 1
        }
        if (apply(name, xml)) importedXml = importedXml + (name to xml)
    }

    // Apply the default bundled preset once the surface handle exists so
    // the first stroke matches the gallery selection.
    LaunchedEffect(surface) {
        if (active == null) {
            try {
                if (surface.loadMyb(readAsset(ctx, BUNDLED[0]))) active = BUNDLED[0]
            } catch (_: Exception) {
                // Surface not ready yet; user tap will apply instead.
            }
        }
    }

    Column(modifier = modifier) {
        Text(
            stringResource(R.string.presets),
            style = MaterialTheme.typography.titleSmall
        )
        (BUNDLED + importedXml.keys.toList()).forEach { name ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val xml = if (name in BUNDLED) {
                            try {
                                readAsset(ctx, name)
                            } catch (_: Exception) {
                                error = true
                                return@clickable
                            }
                        } else {
                            importedXml[name] ?: run {
                                error = true
                                return@clickable
                            }
                        }
                        apply(name, xml)
                    }
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    name.removeSuffix(".myb"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (name == active)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Button(onClick = { importer.launch(arrayOf("*/*")) }) {
            Text(stringResource(R.string.import_preset))
        }
        if (error) {
            Text(
                stringResource(R.string.preset_invalid),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
