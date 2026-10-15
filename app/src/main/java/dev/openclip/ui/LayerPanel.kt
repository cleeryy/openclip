// OpenClip M5 — real layer panel: the engine owns truth, the panel
// pushes through the exact M5 symbols (visibility/opacity/blend/name/
// addGroup/moveLayer/appendChild/clipped/mask/maskTarget) and reads back
// a compact JSON snapshot via nLayerState. Groups render collapsible
// with their children indented; reordering is up/down buttons (no DnD).
package dev.openclip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.openclip.R
import org.json.JSONObject

private data class LayerInfo(
    val index: Int,
    val name: String,
    val visible: Boolean,
    val opacity: Float,
    val blend: String,
    val isGroup: Boolean,
    val hasMask: Boolean,
    val clipped: Boolean,
    val children: List<LayerInfo> = emptyList()
)

private val BLEND_NAMES = listOf(
    "svg:normal", "svg:multiply", "svg:screen", "svg:overlay",
    "svg:plus", "svg:soft-light"
)

private fun parseLayer(o: JSONObject, index: Int): LayerInfo {
    val kids = mutableListOf<LayerInfo>()
    val arr = o.optJSONArray("children")
    if (arr != null) {
        for (k in 0 until arr.length()) {
            val c = arr.optJSONObject(k) ?: continue
            kids.add(
                LayerInfo(
                    index = -1,
                    name = c.optString("name", "Layer"),
                    visible = c.optBoolean("visible", true),
                    opacity = c.optDouble("opacity", 1.0).toFloat(),
                    blend = c.optString("blend", "svg:normal"),
                    isGroup = false,
                    hasMask = c.optBoolean("mask", false),
                    clipped = c.optBoolean("clipped", false)
                )
            )
        }
    }
    val s = o.optJSONObject("self") ?: o
    return LayerInfo(
        index = index,
        name = s.optString("name", "Layer"),
        visible = s.optBoolean("visible", true),
        opacity = s.optDouble("opacity", 1.0).toFloat(),
        blend = s.optString("blend", "svg:normal"),
        isGroup = s.optBoolean("group", false),
        hasMask = s.optBoolean("mask", false),
        clipped = s.optBoolean("clipped", false),
        children = kids
    )
}

@Composable
fun LayerPanel(
    surface: EngineSurface,
    refreshKey: Int,
    modifier: Modifier = Modifier
) {
    var layers by remember { mutableStateOf(listOf<LayerInfo>()) }
    var active by remember { mutableStateOf(0) }
    var maskTarget by remember { mutableStateOf(-1) }
    var renameIndex by remember { mutableStateOf<Int?>(null) }
    var renameText by remember { mutableStateOf("") }
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }

    fun refresh() {
        try {
            val root = JSONObject(surface.layerState())
            active = root.optInt("active", 0)
            maskTarget = root.optInt("maskTarget", -1)
            val arr = root.optJSONArray("layers")
            val out = mutableListOf<LayerInfo>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    out.add(parseLayer(arr.getJSONObject(i), i))
                }
            }
            layers = out
        } catch (_: Exception) {
            layers = emptyList()
        }
    }

    LaunchedEffect(refreshKey) { refresh() }

    Column(modifier = modifier) {
        Text(
            stringResource(R.string.layers),
            style = MaterialTheme.typography.titleSmall
        )
        layers.forEach { info ->
            LayerRow(
                info = info,
                selected = info.index == active,
                maskEditing = info.index == maskTarget,
                expanded = expanded[info.index] ?: true,
                onToggleExpand = {
                    expanded[info.index] = !(expanded[info.index] ?: true)
                },
                onSelect = {
                    surface.layerOp { setActiveLayer(info.index) }
                    refresh()
                },
                onVisibility = {
                    surface.layerOp { setLayerVisibility(info.index, !info.visible) }
                    refresh()
                },
                onOpacityRelease = { v ->
                    surface.layerOp { setLayerOpacity(info.index, v) }
                    refresh()
                },
                onBlend = { b ->
                    surface.layerOp { setLayerBlend(info.index, b) }
                    refresh()
                },
                onRename = {
                    renameIndex = info.index
                    renameText = info.name
                },
                onMoveUp = {
                    if (info.index + 1 < layers.size) {
                        surface.layerOp { moveLayer(info.index, info.index + 1) }
                        refresh()
                    }
                },
                onMoveDown = {
                    if (info.index - 1 >= 0) {
                        surface.layerOp { moveLayer(info.index, info.index - 1) }
                        refresh()
                    }
                },
                onAddMask = {
                    surface.layerOp { addLayerMask(info.index) }
                    refresh()
                },
                onMaskTarget = {
                    surface.layerOp { maskTarget(info.index, info.index != maskTarget) }
                    refresh()
                },
                onClipped = {
                    surface.layerOp { setLayerClipped(info.index, !info.clipped) }
                    refresh()
                },
                onAddChild = {
                    surface.layerOp { appendChild(info.index, "Layer") }
                    refresh()
                }
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = {
                surface.layerOp { addLayer() }
                refresh()
            }) { Text("+") }
            Button(onClick = {
                surface.layerOp { addGroup("Group") }
                refresh()
            }) { Text(stringResource(R.string.layer_add_group)) }
        }
    }

    val ri = renameIndex
    if (ri != null) {
        AlertDialog(
            onDismissRequest = { renameIndex = null },
            title = { Text(stringResource(R.string.layer_rename)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.layer_rename_hint)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    surface.layerOp { setLayerName(ri, renameText) }
                    renameIndex = null
                    refresh()
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { renameIndex = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun LayerRow(
    info: LayerInfo,
    selected: Boolean,
    maskEditing: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onSelect: () -> Unit,
    onVisibility: () -> Unit,
    onOpacityRelease: (Float) -> Unit,
    onBlend: (String) -> Unit,
    onRename: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onAddMask: () -> Unit,
    onMaskTarget: () -> Unit,
    onClipped: () -> Unit,
    onAddChild: () -> Unit
) {
    var slider by remember(info.index, info.opacity) {
        mutableStateOf(info.opacity.coerceIn(0f, 1f))
    }
    var blendOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else Color.Transparent
            )
            .padding(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (info.isGroup) {
                Text(
                    if (expanded) "-" else "+",
                    modifier = Modifier.clickable { onToggleExpand() }
                )
            }
            Text(
                text = (if (info.visible) "" else "x ") + info.name +
                    (if (maskEditing) " [M]" else ""),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect() }
            )
            Text(
                text = if (info.visible) "o" else "-",
                modifier = Modifier.clickable { onVisibility() }
            )
        }
        Slider(
            value = slider,
            onValueChange = { slider = it },
            onValueChangeFinished = { onOpacityRelease(slider) },
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = info.blend.removePrefix("svg:"),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.clickable { blendOpen = true }
            )
            DropdownMenu(
                expanded = blendOpen,
                onDismissRequest = { blendOpen = false }
            ) {
                BLEND_NAMES.forEach { b ->
                    DropdownMenuItem(
                        text = { Text(b.removePrefix("svg:")) },
                        onClick = {
                            blendOpen = false
                            onBlend(b)
                        }
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onRename) {
                Text("Aa", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = onMoveUp) {
                Text("^", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = onMoveDown) {
                Text("v", style = MaterialTheme.typography.labelSmall)
            }
            if (!info.isGroup) {
                if (!info.hasMask) {
                    TextButton(onClick = onAddMask) {
                        Text("M+", style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    TextButton(onClick = onMaskTarget) {
                        Text(
                            if (maskEditing) "[M]" else "M",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                TextButton(onClick = onClipped) {
                    Text(
                        if (info.clipped) "[C]" else "C",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            } else {
                TextButton(onClick = onAddChild) {
                    Text("+ch", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (info.isGroup && expanded) {
            Text(
                stringResource(R.string.layer_children) +
                    ": " + info.children.joinToString { it.name },
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 8.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = info.visible,
                    onCheckedChange = { onVisibility() }
                )
                Text(
                    if (info.visible) stringResource(R.string.layer_visible)
                    else stringResource(R.string.layer_hidden),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
