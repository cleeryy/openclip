// OpenClip T5 — calque dégradé/remplissage + foil (spec §2,
// app-side editor; the engine renders composite-time via
// nSetLayerFill, ARCHITECTURE.md "T5 additions" §2).
package dev.openclip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.openclip.R
import org.json.JSONObject

private data class FillStopUi(var color: String, var pos: Float)

private fun parseFillFiche(raw: String?): Triple<String, JSONObject?, Int> {
    // Returns (kind, fiche, activeIndex).
    return try {
        val root = JSONObject(raw ?: "{}")
        val a = root.optInt("active", 0)
        val arr = root.optJSONArray("layers") ?: return Triple("", null, a)
        val e = arr.optJSONObject(a) ?: return Triple("", null, a)
        val s = e.optJSONObject("self") ?: e
        val f = s.optJSONObject("fill") ?: e.optJSONObject("fill")
        Triple(f?.optString("kind", "") ?: "", f, a)
    } catch (_: Exception) {
        Triple("", null, 0)
    }
}

@Composable
fun FillPanel(
    surface: EngineSurface,
    onDismiss: () -> Unit,
    onStatus: (String) -> Unit,
    onChanged: () -> Unit
) {
    val (initKind, fiche, active) = remember { parseFillFiche(surface.layerState()) }
    var kind by remember { mutableStateOf(initKind.ifEmpty { "solid" }) }
    var color by remember { mutableStateOf(fiche?.optString("color", "#808080") ?: "#808080") }
    var mode by remember { mutableStateOf("linear") }
    var hue by remember { mutableStateOf(0f) }
    var scale by remember { mutableStateOf(4f) }
    var shine by remember { mutableStateOf(0.5f) }
    var stops: List<FillStopUi> by remember {
        mutableStateOf(
            try {
                val raw = fiche?.optString("stops", "") ?: ""
                if (raw.isBlank()) mutableListOf(
                    FillStopUi("#000000", 0f),
                    FillStopUi("#FFFFFF", 1f)
                )
                else raw.split(",").mapNotNull { tok ->
                    val c = tok.indexOf(':')
                    if (c != 7) null
                    else FillStopUi(
                        tok.substring(0, 7),
                        tok.substring(8).toFloatOrNull() ?: return@mapNotNull null
                    )
                }.toMutableList()
            } catch (_: Exception) {
                mutableListOf(
                    FillStopUi("#000000", 0f),
                    FillStopUi("#FFFFFF", 1f)
                )
            }
        )
    }

    fun buildJson(): String {
        val stopsJson = stops.sortedBy { it.pos }.joinToString(",") {
            "\"${it.color}:${it.pos}\""
        }
        val (cw, ch) = surface.canvasDims()
        return "{\"kind\":\"$kind\",\"color\":\"$color\"," +
            "\"mode\":\"$mode\",\"x0\":0,\"y0\":0," +
            "\"x1\":$cw,\"y1\":0," +
            "\"stops\":[$stopsJson]," +
            "\"hue\":$hue,\"scale\":$scale,\"shine\":$shine}"
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.fill_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(
                        "solid" to R.string.fill_solid,
                        "gradient" to R.string.fill_gradient,
                        "foil" to R.string.fill_foil
                    ).forEach { (k, res) ->
                        FilterChip(
                            selected = kind == k,
                            onClick = { kind = k },
                            label = { Text(stringResource(res)) },
                            modifier = Modifier.testTag("fill-kind-$k")
                        )
                    }
                }
                if (kind == "gradient") {
                    Text(
                        stringResource(R.string.fill_stops),
                        style = MaterialTheme.typography.bodySmall
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                    ) {
                        itemsIndexed(stops, key = { i, _ -> i }) { i, st ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    st.color,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.weight(1f)
                                )
                                Slider(
                                    value = st.pos,
                                    onValueChange = { v ->
                                        stops = stops.toMutableList().also { list ->
                                            list[i] = list[i].copy(pos = v)
                                        }
                                    },
                                    valueRange = 0f..1f,
                                    modifier = Modifier
                                        .weight(2f)
                                        .testTag("fill-stop-$i")
                                )
                                val stopDelDesc = stringResource(R.string.a11y_remove)
                                TextButton(
                                    onClick = {
                                        if (stops.size > 2) stops =
                                            stops.toMutableList().also { it.removeAt(i) }
                                    },
                                    modifier = Modifier.testTag("fill-stop-del-$i")
                                        .semantics {
                                            contentDescription = stopDelDesc
                                        }
                                ) { Text("−") }
                            }
                        }
                    }
                    val stopAddDesc = stringResource(R.string.a11y_add)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                if (stops.size < 8) stops =
                                    stops + FillStopUi("#808080", 0.5f)
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("fill-stop-add")
                                .semantics {
                                    contentDescription = stopAddDesc
                                }
                        ) { Text("+") }
                    }
                }
                if (kind == "foil") {
                    val rows = listOf(
                        Triple(R.string.fill_hue, hue, 0f..360f),
                        Triple(R.string.fill_scale, scale, 1f..16f),
                        Triple(R.string.fill_shine, shine, 0f..1f)
                    )
                    rows.forEach { (res, v, range) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(res),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.weight(1f)
                            )
                            Slider(
                                value = v,
                                onValueChange = {
                                    when (res) {
                                        R.string.fill_hue -> hue = it
                                        R.string.fill_scale -> scale = it
                                        else -> shine = it
                                    }
                                },
                                valueRange = range,
                                modifier = Modifier.weight(2f)
                            )
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.fill_cancel))
                    }
                    OutlinedButton(
                        onClick = {
                            if (surface.setLayerFill(active, null)) {
                                onStatus("")
                                onChanged()
                                onDismiss()
                            }
                        },
                        modifier = Modifier.testTag("fill-clear")
                    ) { Text(stringResource(R.string.adjustment_clear)) }
                    OutlinedButton(
                        onClick = {
                            if (surface.rasterizeFill(active)) {
                                onChanged()
                                onDismiss()
                            }
                        },
                        modifier = Modifier.testTag("fill-rasterize")
                    ) { Text(stringResource(R.string.fill_rasterize)) }
                    Button(
                        onClick = {
                            if (surface.setLayerFill(active, buildJson())) {
                                onChanged()
                                onDismiss()
                            }
                        },
                        modifier = Modifier.testTag("fill-apply")
                    ) { Text(stringResource(R.string.fill_validate)) }
                }
            }
        }
    }
}
