// OpenClip M3 — filter dialog: the 10 engine-owned filters from the M3
// contract in ARCHITECTURE.md, each with its documented params as
// sliders/toggles/text. Apply runs on the render thread via
// EngineSurface.applyFilter; a false return (unknown name/bad params)
// surfaces as a toast and leaves the canvas unchanged.
package dev.openclip.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.openclip.R
import kotlin.math.roundToInt

private sealed class FilterParam {
    // All param kinds share a JSON key (resolved via the base class so
    // generic maps/by-name lookups compile without smart casts).
    abstract val key: String

    // Numeric JSON param, shown as a slider (int = stepped to integers).
    data class Num(
        override val key: String,
        val min: Float,
        val max: Float,
        val default: Float,
        val int: Boolean = false
    ) : FilterParam()

    // Boolean JSON param, shown as a toggle chip.
    data class Toggle(override val key: String, val default: Boolean) : FilterParam()

    // Free-text JSON param (gradientMap stops), shown as a text field.
    data class Text(override val key: String, val default: String) : FilterParam()
}

private data class FilterSpec(
    val name: String,
    val labelRes: Int,
    val params: List<FilterParam> = emptyList()
)

// The registry mirrors the M3 contract (ARCHITECTURE.md): kernel-based
// gaussianBlur/sharpen/motionBlur/radialBlur, pixelwise
// posterize/levels, and chromaticAberration/addNoise/mosaic/gradientMap.
// Param keys are the shim-documented engine-owned keys; unknown keys are
// ignored engine-side and missing keys fall back to engine defaults.
private val FILTERS = listOf(
    FilterSpec(
        "gaussianBlur", R.string.filter_gaussian_blur,
        listOf(FilterParam.Num("radius", 1f, 32f, 4f))
    ),
    FilterSpec("sharpen", R.string.filter_sharpen),
    FilterSpec(
        "motionBlur", R.string.filter_motion_blur,
        listOf(
            FilterParam.Num("angle", 0f, 360f, 0f),
            FilterParam.Num("length", 1f, 64f, 16f, int = true)
        )
    ),
    FilterSpec(
        "radialBlur", R.string.filter_radial_blur,
        listOf(
            FilterParam.Num("cx", 0f, 1f, 0.5f),
            FilterParam.Num("cy", 0f, 1f, 0.5f),
            FilterParam.Num("strength", 0f, 1f, 0.5f)
        )
    ),
    FilterSpec(
        "posterize", R.string.filter_posterize,
        listOf(FilterParam.Num("levels", 2f, 16f, 4f, int = true))
    ),
    FilterSpec(
        "levels", R.string.filter_levels,
        listOf(
            FilterParam.Num("inBlack", 0f, 255f, 0f, int = true),
            FilterParam.Num("inWhite", 0f, 255f, 255f, int = true),
            FilterParam.Num("outBlack", 0f, 255f, 0f, int = true),
            FilterParam.Num("outWhite", 0f, 255f, 255f, int = true)
        )
    ),
    FilterSpec(
        "chromaticAberration", R.string.filter_chromatic_aberration,
        listOf(FilterParam.Num("shift", 0f, 16f, 2f))
    ),
    FilterSpec(
        "addNoise", R.string.filter_add_noise,
        listOf(
            FilterParam.Num("amount", 0f, 1f, 0.2f),
            FilterParam.Toggle("monochrome", true)
        )
    ),
    FilterSpec(
        "mosaic", R.string.filter_mosaic,
        listOf(FilterParam.Num("blockSize", 2f, 64f, 16f, int = true))
    ),
    FilterSpec(
        "gradientMap", R.string.filter_gradient_map,
        listOf(FilterParam.Text("stops", "#000000:0,#FFFFFF:1"))
    )
)

private fun FilterParam.defaultString(): String = when (this) {
    is FilterParam.Num -> if (int) default.roundToInt().toString() else default.toString()
    is FilterParam.Toggle -> default.toString()
    is FilterParam.Text -> default
}

private fun buildParamsJson(values: Map<String, String>, spec: FilterSpec): String {
    val parts = spec.params.map { p ->
        val raw = values[p.key] ?: p.defaultString()
        val value = when (p) {
            is FilterParam.Text -> "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            else -> raw
        }
        "\"${p.key}\":$value"
    }
    return "{${parts.joinToString(",")}}"
}

@Composable
fun FilterDialog(
    surface: EngineSurface,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    var selected by remember { mutableStateOf(0) }
    val spec = FILTERS[selected]
    // Per-filter param values, reset to documented defaults on switch.
    val values = remember(selected) {
        mutableStateMapOf<String, String>().apply {
            spec.params.forEach { put(it.key, it.defaultString()) }
        }
    }
    var applying by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    stringResource(R.string.filters),
                    style = MaterialTheme.typography.titleMedium
                )
                FILTERS.forEachIndexed { i, f ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = i }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(f.labelRes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (i == selected)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                spec.params.forEach { p ->
                    when (p) {
                        is FilterParam.Num -> {
                            val cur = values[p.key]?.toFloatOrNull() ?: p.default
                            Text(
                                "${p.key}: " +
                                    if (p.int) cur.roundToInt().toString()
                                    else "%.2f".format(cur),
                                style = MaterialTheme.typography.labelSmall
                            )
                            Slider(
                                value = cur.coerceIn(p.min, p.max),
                                onValueChange = {
                                    values[p.key] =
                                        if (p.int) it.roundToInt().toString()
                                        else it.toString()
                                },
                                valueRange = p.min..p.max,
                                steps = if (p.int) (p.max - p.min).roundToInt() - 1 else 0
                            )
                        }
                        is FilterParam.Toggle -> {
                            val cur = values[p.key]?.toBooleanStrictOrNull() ?: p.default
                            FilterChip(
                                selected = cur,
                                onClick = { values[p.key] = (!cur).toString() },
                                label = { Text(p.key) }
                            )
                        }
                        is FilterParam.Text -> {
                            OutlinedTextField(
                                value = values[p.key] ?: p.default,
                                onValueChange = { values[p.key] = it },
                                label = { Text(p.key) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.filter_cancel))
                    }
                    Button(
                        onClick = {
                            applying = true
                            val json = buildParamsJson(values, spec)
                            surface.applyFilter(spec.name, json) { ok ->
                                applying = false
                                Toast.makeText(
                                    ctx,
                                    if (ok) ctx.getString(R.string.filter_applied)
                                    else ctx.getString(R.string.filter_failed),
                                    Toast.LENGTH_SHORT
                                ).show()
                                if (ok) onDismiss()
                            }
                        },
                        enabled = !applying
                    ) {
                        Text(stringResource(R.string.filter_apply))
                    }
                }
            }
        }
    }
}
