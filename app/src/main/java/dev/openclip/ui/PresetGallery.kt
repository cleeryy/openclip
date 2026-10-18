// OpenClip — preset gallery: bundled clean-room .myb presets from assets
// in a compact studio grid plus SAF import validated through EngineHost.nLoadMyb.
package dev.openclip.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
        var name = base
        var n = 2
        while (name in BUNDLED || name in importedXml) {
            name = base.removeSuffix(".myb") + "-$n.myb"
            n += 1
        }
        if (apply(name, xml)) importedXml = importedXml + (name to xml)
    }

    LaunchedEffect(surface) {
        if (active == null) {
            try {
                if (surface.loadMyb(readAsset(ctx, BUNDLED[0]))) active = BUNDLED[0]
            } catch (_: Exception) {
            }
        }
    }

    val allPresets = BUNDLED + importedXml.keys.toList()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF25252D), RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.presets),
                style = MaterialTheme.typography.titleSmall,
                color = Color(0xFFECECF1)
            )
            OutlinedButton(
                onClick = { importer.launch(arrayOf("*/*")) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text(
                    stringResource(R.string.import_preset),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        allPresets.chunked(3).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rowItems.forEach { name ->
                    val isSelected = name == active
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isSelected) Color(0xFF3B66B0) else Color(0xFF1C1C22)
                            )
                            .border(
                                1.dp,
                                if (isSelected) Color(0xFF6FA3FF) else Color(0xFF383844),
                                RoundedCornerShape(6.dp)
                            )
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
                            .padding(vertical = 6.dp, horizontal = 4.dp)
                    ) {
                        Text(
                            name.removeSuffix(".myb").replace('_', ' '),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) Color.White else Color(0xFFD0D0DC),
                            maxLines = 1
                        )
                    }
                }
            }
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
