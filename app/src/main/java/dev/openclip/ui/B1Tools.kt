// OpenClip B1 — retouche et encrage (issue #30 tranche B) : modes de
// brosse réels (estompe/dodge/burn/tampon/motif), source du tampon par
// appui long, motif au trait et texture de calque non destructive.
// ARCHITECTURE.md « B1 additions » §2–§5. 0 pixel côté Kotlin : tout
// passe par les symboles moteur (nSetBrush JSON, nSetCloneSource,
// nSetPatternBrush, nSetLayerTexture).
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.openclip.R
import java.util.Locale
import kotlin.math.roundToInt
import org.json.JSONObject

/** Modes de retouche B1 (miroir des valeurs "mode" du JSON nSetBrush). */
enum class RetouchMode(val wire: String) {
    BRUSH("brush"),
    SMUDGE("smudge"),
    DODGE("dodge"),
    BURN("burn"),
    CLONE("clone"),
    PATTERN("pattern");

    companion object {
        fun fromWire(w: String?): RetouchMode =
            entries.firstOrNull { it.wire == w } ?: BRUSH
    }
}

/** État UI session du motif (survit au repli de la rangée). */
class RetouchUiState {
    var motif by mutableStateOf("dots")
    var motifSize by mutableFloatStateOf(32f)
    var spacing by mutableFloatStateOf(24f)
    var patternScale by mutableFloatStateOf(1f)
    var rotation by mutableFloatStateOf(0f)
    var jitterPos by mutableFloatStateOf(0f)
    var jitterRot by mutableFloatStateOf(0f)
    var seed by mutableIntStateOf(7)
    var cloneAligned by mutableStateOf(false)
    var cloneMerged by mutableStateOf(false)
}

// ---- JSON builders (purs, testés par la suite instrumentée) ---------------

/** JSON nSetCloneSource (appui long = source). */
fun cloneSourceJson(
    x: Float,
    y: Float,
    aligned: Boolean,
    sampleMerged: Boolean
): String = String.format(
    Locale.US,
    """{"x":%.2f,"y":%.2f,"aligned":%s,"sample":"%s"}""",
    x, y,
    if (aligned) "true" else "false",
    if (sampleMerged) "merged" else "layer"
)

/** JSON nSetPatternBrush (motif procédural + espacement/échelle/rotation/jitter). */
fun patternBrushJson(
    motif: String,
    size: Float,
    spacing: Float,
    scale: Float,
    rotation: Float,
    jitterPos: Float,
    jitterRot: Float,
    seed: Int,
    alongPath: Boolean = true
): String {
    val kind = when (motif) {
        "ring", "star", "cross" -> motif
        else -> "dots"
    }
    return String.format(
        Locale.US,
        """{"motif":"%s","size":%d,"spacing":%.1f,"scale":%.3f,"rotation":%.1f,"jitterPos":%.1f,"jitterScale":0,"jitterRot":%.1f,"seed":%d,"alongPath":%s}""",
        kind,
        size.roundToInt().coerceIn(8, 128),
        spacing.coerceIn(1f, 200f),
        scale.coerceIn(0.25f, 4f),
        rotation.coerceIn(-180f, 180f),
        jitterPos.coerceIn(0f, 64f),
        jitterRot.coerceIn(0f, 180f),
        seed,
        if (alongPath) "true" else "false"
    )
}

/** JSON nSetLayerTexture (null/"" = effacer, {"bake":true} = pixeliser). */
fun textureJson(kind: String, scale: Float, strength: Float, seed: Int): String {
    val k = when (kind) {
        "canvas", "grain" -> kind
        else -> "paper"
    }
    return String.format(
        Locale.US,
        """{"kind":"%s","scale":%.3f,"strength":%.3f,"seed":%d}""",
        k, scale.coerceIn(0.25f, 8f), strength.coerceIn(0f, 1f), seed
    )
}

// ---- Rangée de retouche (dans la palette d'outils) -----------------------

@Composable
fun RetouchBar(
    surface: EngineSurface,
    prefs: BrushPrefs,
    ui: RetouchUiState,
    onModeChanged: () -> Unit,
    onStatus: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val mode = RetouchMode.fromWire(prefs.retouchMode)
    // Local mirror of the picked source (Compose state: the surface's
    // @Volatile fields are not observable, the hint must refresh).
    var srcX by remember { mutableFloatStateOf(-1f) }
    var srcY by remember { mutableFloatStateOf(-1f) }

    // Clone: arme l'appui long sur la surface active et pousse l'ancre
    // dès qu'elle est choisie (aligned/sample relus à chaque appui).
    DisposableEffect(
        surface, mode, ui.cloneAligned, ui.cloneMerged, prefs.isEraser
    ) {
        surface.cloneArmed = mode == RetouchMode.CLONE && !prefs.isEraser
        surface.onCloneSource = { ex, ey ->
            val ok = surface.setCloneSource(
                cloneSourceJson(ex, ey, ui.cloneAligned, ui.cloneMerged)
            )
            if (ok) {
                srcX = ex
                srcY = ey
                onStatus(
                    ctx.getString(
                        R.string.b1_clone_source_set,
                        ex.roundToInt(),
                        ey.roundToInt()
                    )
                )
            } else {
                onStatus(ctx.getString(R.string.t1_failed))
            }
        }
        onDispose {
            surface.cloneArmed = false
            surface.onCloneSource = null
        }
    }

    // Pattern: pousse le descriptif du motif à chaque changement et à
    // l'armement (le moteur garde la tuile procédurale).
    LaunchedEffect(
        surface, mode, ui.motif, ui.motifSize, ui.spacing, ui.patternScale,
        ui.rotation, ui.jitterPos, ui.jitterRot, ui.seed
    ) {
        if (mode == RetouchMode.PATTERN) {
            surface.setPatternBrush(
                patternBrushJson(
                    ui.motif, ui.motifSize, ui.spacing, ui.patternScale,
                    ui.rotation, ui.jitterPos, ui.jitterRot, ui.seed
                )
            )
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        val modeScroll = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(modeScroll)
                .scrollFade("b1-retouch-row", modeScroll)
                .testTag("b1-retouch-row"),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(
                RetouchMode.BRUSH to R.string.brush,
                RetouchMode.SMUDGE to R.string.b1_smudge,
                RetouchMode.DODGE to R.string.b1_dodge,
                RetouchMode.BURN to R.string.b1_burn,
                RetouchMode.CLONE to R.string.b1_clone,
                RetouchMode.PATTERN to R.string.b1_pattern
            ).forEach { (m, label) ->
                FilterChip(
                    selected = mode == m,
                    onClick = {
                        prefs.chooseRetouchMode(m.wire)
                        if (m == RetouchMode.PATTERN) {
                            surface.setPatternBrush(
                                patternBrushJson(
                                    ui.motif, ui.motifSize, ui.spacing,
                                    ui.patternScale, ui.rotation, ui.jitterPos,
                                    ui.jitterRot, ui.seed
                                )
                            )
                        }
                        onModeChanged()
                    },
                    label = {
                        Text(
                            stringResource(label),
                            maxLines = 1,
                            softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("b1-mode-" + m.wire)
                )
            }
        }

        when (mode) {
            RetouchMode.SMUDGE -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.testTag("b1-smudge-row")
                ) {
                    Text(
                        stringResource(
                            R.string.b1_smudge_strength,
                            (prefs.smudgeStrength * 100).roundToInt()
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFD0D0DC),
                        modifier = Modifier.width(150.dp)
                    )
                    Slider(
                        value = prefs.smudgeStrength,
                        onValueChange = {
                            prefs.updateSmudgeStrength(it)
                            onModeChanged()
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier
                            .width(140.dp)
                            .height(28.dp)
                            .testTag("b1-smudge-strength")
                    )
                }
            }

            RetouchMode.CLONE -> {
                val rowScroll = rememberScrollState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rowScroll)
                        .scrollFade("b1-clone-row", rowScroll)
                        .testTag("b1-clone-row"),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = ui.cloneAligned,
                        onClick = {
                            ui.cloneAligned = !ui.cloneAligned
                            // Re-push the anchor so the engine flips mode.
                            if (srcX >= 0f) {
                                surface.setCloneSource(
                                    cloneSourceJson(
                                        srcX, srcY, ui.cloneAligned,
                                        ui.cloneMerged
                                    )
                                )
                            }
                        },
                        label = { Text(stringResource(R.string.b1_clone_aligned)) },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("b1-clone-aligned")
                    )
                    FilterChip(
                        selected = !ui.cloneMerged,
                        onClick = {
                            ui.cloneMerged = false
                            if (srcX >= 0f) {
                                surface.setCloneSource(
                                    cloneSourceJson(
                                        srcX, srcY, ui.cloneAligned, false
                                    )
                                )
                            }
                        },
                        label = { Text(stringResource(R.string.t1_source_layer)) },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("b1-clone-sample-layer")
                    )
                    FilterChip(
                        selected = ui.cloneMerged,
                        onClick = {
                            ui.cloneMerged = true
                            if (srcX >= 0f) {
                                surface.setCloneSource(
                                    cloneSourceJson(
                                        srcX, srcY, ui.cloneAligned, true
                                    )
                                )
                            }
                        },
                        label = { Text(stringResource(R.string.b1_clone_sample_merged)) },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("b1-clone-sample-merged")
                    )
                    Text(
                        if (srcX >= 0f) {
                            stringResource(
                                R.string.b1_clone_source_at,
                                srcX.roundToInt(),
                                srcY.roundToInt()
                            )
                        } else {
                            stringResource(R.string.b1_clone_long_press)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFB8B8C8),
                        maxLines = 2,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .testTag("b1-clone-hint")
                    )
                }
            }

            RetouchMode.PATTERN -> {
                val rowScroll = rememberScrollState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rowScroll)
                        .scrollFade("b1-pattern-row", rowScroll)
                        .testTag("b1-pattern-row"),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(
                        "dots" to R.string.b1_motif_dots,
                        "ring" to R.string.b1_motif_ring,
                        "star" to R.string.b1_motif_star,
                        "cross" to R.string.b1_motif_cross
                    ).forEach { (k, label) ->
                        FilterChip(
                            selected = ui.motif == k,
                            onClick = { ui.motif = k },
                            label = { Text(stringResource(label)) },
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("b1-motif-$k")
                        )
                    }
                    PatternSlider(
                        label = stringResource(
                            R.string.b1_motif_size, ui.motifSize.roundToInt()
                        ),
                        value = ui.motifSize,
                        range = 8f..96f,
                        tag = "b1-motif-size",
                        onChange = { ui.motifSize = it }
                    )
                    PatternSlider(
                        label = stringResource(
                            R.string.b1_spacing, ui.spacing.roundToInt()
                        ),
                        value = ui.spacing,
                        range = 4f..96f,
                        tag = "b1-spacing",
                        onChange = { ui.spacing = it }
                    )
                    PatternSlider(
                        label = String.format(
                            Locale.US, "×%.2f", ui.patternScale
                        ),
                        value = ui.patternScale,
                        range = 0.25f..3f,
                        tag = "b1-pattern-scale",
                        onChange = { ui.patternScale = it }
                    )
                    PatternSlider(
                        label = stringResource(
                            R.string.b1_rotation, ui.rotation.roundToInt()
                        ),
                        value = ui.rotation,
                        range = -180f..180f,
                        tag = "b1-rotation",
                        onChange = { ui.rotation = it }
                    )
                    PatternSlider(
                        label = stringResource(
                            R.string.b1_jitter, ui.jitterPos.roundToInt()
                        ),
                        value = ui.jitterPos,
                        range = 0f..32f,
                        tag = "b1-jitter",
                        onChange = { ui.jitterPos = it }
                    )
                    PatternSlider(
                        label = stringResource(
                            R.string.b1_jitter_rot, ui.jitterRot.roundToInt()
                        ),
                        value = ui.jitterRot,
                        range = 0f..90f,
                        tag = "b1-jitter-rot",
                        onChange = { ui.jitterRot = it }
                    )
                    FilterChip(
                        selected = false,
                        onClick = { ui.seed = (ui.seed + 1) % 9973 },
                        label = { Text(stringResource(R.string.b1_seed, ui.seed)) },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("b1-seed")
                    )
                }
            }

            else -> {
                // Brush / dodge / burn: rien de plus (rayon/opacité de la
                // barre principale s'appliquent, pression comprise).
            }
        }
    }
}

@Composable
private fun PatternSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    tag: String,
    onChange: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFD0D0DC),
            modifier = Modifier.width(56.dp)
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier
                .width(96.dp)
                .height(28.dp)
                .testTag(tag)
        )
    }
}

// ---- Texture de calque (non destructive, §5) -----------------------------

private fun parseTextureFiche(raw: String?): JSONObject? {
    if (raw.isNullOrBlank()) return null
    return try {
        val root = JSONObject(raw)
        val a = root.optInt("active", 0)
        val arr = root.optJSONArray("layers")
        val e = arr?.optJSONObject(a)
        (e?.optJSONObject("self") ?: e)?.optJSONObject("texture")
    } catch (_: Exception) {
        null
    }
}

@Composable
fun TexturePanel(
    surface: EngineSurface,
    onDismiss: () -> Unit,
    onStatus: (String) -> Unit,
    onChanged: () -> Unit
) {
    val failedMsg = stringResource(R.string.t1_failed)
    val raw = remember { surface.layerState() }
    val fiche = remember { parseTextureFiche(raw) }
    val active = remember { runCatching { JSONObject(raw).optInt("active", 0) }.getOrDefault(0) }
    var kind by remember { mutableStateOf(fiche?.optString("kind")?.ifEmpty { "paper" } ?: "paper") }
    var scale by remember { mutableStateOf(2f) }
    var strength by remember { mutableStateOf(0.35f) }
    var seed by remember { mutableIntStateOf(7) }
    // Re-edit: the fiche params span "scale|strength|seed".
    LaunchedEffect(Unit) {
        val p = fiche?.optString("params") ?: ""
        val parts = p.split("|")
        if (parts.size == 3) {
            scale = parts[0].toFloatOrNull()?.coerceIn(0.25f, 8f) ?: scale
            strength = parts[1].toFloatOrNull()?.coerceIn(0f, 1f) ?: strength
            seed = parts[2].toFloatOrNull()?.toInt() ?: seed
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.b1_texture_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    stringResource(R.string.b1_texture_non_destructive),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFB8B8C8)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(
                        "paper" to R.string.b1_texture_paper,
                        "canvas" to R.string.b1_texture_canvas,
                        "grain" to R.string.b1_texture_grain
                    ).forEach { (k, res) ->
                        FilterChip(
                            selected = kind == k,
                            onClick = { kind = k },
                            label = { Text(stringResource(res)) },
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("b1-texture-kind-$k")
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.b1_texture_scale, scale),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFD0D0DC),
                        modifier = Modifier.width(120.dp)
                    )
                    Slider(
                        value = scale,
                        onValueChange = { scale = it },
                        valueRange = 0.25f..8f,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("b1-texture-scale")
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            R.string.b1_texture_strength,
                            (strength * 100).roundToInt()
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFD0D0DC),
                        modifier = Modifier.width(120.dp)
                    )
                    Slider(
                        value = strength,
                        onValueChange = { strength = it },
                        valueRange = 0f..1f,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("b1-texture-strength")
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { seed = (seed + 1) % 9973 },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("b1-texture-seed")
                    ) { Text(stringResource(R.string.b1_seed, seed)) }
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
                            if (surface.setLayerTexture(active, null)) {
                                onChanged()
                                onDismiss()
                            } else {
                                onStatus(failedMsg)
                            }
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("b1-texture-clear")
                    ) { Text(stringResource(R.string.adjustment_clear)) }
                    OutlinedButton(
                        onClick = {
                            if (surface.setLayerTexture(active, "{\"bake\":true}")) {
                                onChanged()
                                onDismiss()
                            } else {
                                onStatus(failedMsg)
                            }
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("b1-texture-bake")
                    ) { Text(stringResource(R.string.fill_rasterize)) }
                    Button(
                        onClick = {
                            val json = textureJson(kind, scale, strength, seed)
                            if (surface.setLayerTexture(active, json)) {
                                onChanged()
                                onDismiss()
                            } else {
                                onStatus(failedMsg)
                            }
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("b1-texture-apply")
                    ) { Text(stringResource(R.string.fill_validate)) }
                }
            }
        }
    }
}
