// OpenClip — compact studio top toolbar: brush/eraser tool, inline size &
// opacity sliders, collapsible stabilization/pressure-curve/symmetry bar,
// and undo/redo/filter/file actions. Slice 5: the eraser is a dedicated
// tool with its own size/opacity memory (BrushPrefs), and the pressure
// curve is an editable graph fitted onto the engine scalar.
package dev.openclip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.openclip.R
import java.util.Locale

data class BrushState(
    val color: String,
    val isBrush: Boolean,
    val radius: Float,
    val opaque: Float,
    val stabilize: Float = 0f,
    val curve: Float = 0f
)

private fun BrushState.toJson(): String = String.format(
    Locale.US,
    """{"radius":%.2f,"hardness":%.2f,"opaque":%.2f,"spacing":0.15,"color":"%s","stabilize":%.2f,"curve":%.2f,"mode":"%s"}""",
    radius,
    if (isBrush) 0.7f else 0.9f,
    opaque,
    color,
    stabilize,
    curve,
    if (isBrush) "brush" else "eraser"
)

private const val DEFAULT_INK = "#141414"

@Composable
fun ToolPalette(
    surface: EngineSurface,
    prefs: BrushPrefs,
    initialColor: String = DEFAULT_INK,
    onFileClicked: () -> Unit = {},
    onImportClicked: () -> Unit = {},
    navigateMode: Boolean = false,
    onNavigateToggle: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Slice 5: the tool lives in the hoisted BrushPrefs (persisted);
    // isBrush here is a read-through, never a second source of truth.
    val isBrush = !prefs.isEraser
    var inkColor by remember { mutableStateOf(initialColor) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var symX by remember { mutableStateOf(false) }
    var symY by remember { mutableStateOf(false) }

    fun pushBrush() {
        prefs.bindTool(inkColor, !isBrush, prefs.curve)
        surface.setBrush(prefs.currentJson())
    }

    fun pushSymmetry() {
        val sx = if (symX) surface.canvasWidth() / 2f else -1f
        val sy = if (symY) surface.canvasHeight() / 2f else -1f
        surface.setRulers(
            String.format(Locale.US, """{"symX":%.1f,"symY":%.1f}""", sx, sy)
        )
    }

    LaunchedEffect(initialColor) {
        if (inkColor != initialColor) {
            inkColor = initialColor
            pushBrush()
        }
    }

    LaunchedEffect(surface) {
        val prev = surface.onStrokeChanged
        surface.onStrokeChanged = {
            prev?.invoke()
            canUndo = true
            canRedo = false
        }
        pushBrush()
    }

    val compactPad = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
    val shownRadius = prefs.activeRadius()
    val shownOpaque = prefs.activeOpaque()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF202026))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = isBrush,
                onClick = { prefs.setTool(false); pushBrush() },
                label = { Text(stringResource(R.string.brush)) },
                modifier = Modifier
                    .height(48.dp)
                    .testTag("tool-brush")
            )
            FilterChip(
                selected = !isBrush,
                onClick = { prefs.setTool(true); pushBrush() },
                label = { Text(stringResource(R.string.eraser)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF4A3520),
                    selectedLabelColor = Color(0xFFE0A050)
                ),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("tool-eraser")
            )
            FilterChip(
                selected = navigateMode,
                onClick = onNavigateToggle,
                label = {
                    Text(
                        stringResource(
                            if (navigateMode) R.string.nav_mode_move
                            else R.string.nav_mode_draw
                        )
                    )
                },
                modifier = Modifier.testTag("tool-nav")
            )

            // Compact inline size slider (active tool memory).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp)
            ) {
                Text(
                    "${shownRadius.toInt()}px",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFD0D0DC),
                    modifier = Modifier.width(38.dp)
                )
                Slider(
                    value = shownRadius,
                    onValueChange = {
                        if (isBrush) prefs.updateRadius(it)
                        else prefs.updateEraserRadius(it)
                        pushBrush()
                    },
                    valueRange = 1f..128f,
                    modifier = Modifier
                        .width(110.dp)
                        .height(28.dp)
                )
            }

            // Compact inline opacity slider (active tool memory).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp)
            ) {
                Text(
                    stringResource(R.string.opacity, (shownOpaque * 100).toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFD0D0DC)
                )
                Slider(
                    value = shownOpaque,
                    onValueChange = {
                        if (isBrush) prefs.updateOpaque(it)
                        else prefs.updateEraserOpaque(it)
                        pushBrush()
                    },
                    modifier = Modifier
                        .width(96.dp)
                        .height(28.dp)
                )
            }

            FilterChip(
                selected = showAdvanced,
                onClick = { showAdvanced = !showAdvanced },
                label = { Text("⚙") },
                modifier = Modifier.testTag("advanced-toggle")
            )

            OutlinedButton(
                onClick = {
                    val ok = surface.undo()
                    if (ok) canRedo = true else canUndo = false
                },
                enabled = canUndo,
                contentPadding = compactPad,
                modifier = Modifier.height(34.dp)
            ) {
                Text(stringResource(R.string.undo), style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(
                onClick = {
                    val ok = surface.redo()
                    if (ok) canUndo = true else canRedo = false
                },
                enabled = canRedo,
                contentPadding = compactPad,
                modifier = Modifier.height(34.dp)
            ) {
                Text(stringResource(R.string.redo), style = MaterialTheme.typography.labelMedium)
            }
            Button(
                onClick = { showFilters = true },
                contentPadding = compactPad,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF32323D),
                    contentColor = Color(0xFFECECF1)
                ),
                modifier = Modifier.height(34.dp)
            ) {
                Text(stringResource(R.string.filters), style = MaterialTheme.typography.labelMedium)
            }
            Button(
                onClick = onImportClicked,
                contentPadding = compactPad,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF32323D),
                    contentColor = Color(0xFFECECF1)
                ),
                modifier = Modifier.height(34.dp)
            ) {
                Text(stringResource(R.string.import_doc), style = MaterialTheme.typography.labelMedium)
            }
            Button(
                onClick = onFileClicked,
                contentPadding = compactPad,
                modifier = Modifier.height(34.dp)
            ) {
                Text(stringResource(R.string.file_menu), style = MaterialTheme.typography.labelMedium)
            }
        }

        if (showAdvanced) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.stabilize, (prefs.stabilize * 100).toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFB8B8C8)
                )
                Slider(
                    value = prefs.stabilize,
                    onValueChange = { prefs.updateStabilize(it); pushBrush() },
                    valueRange = 0f..1f,
                    modifier = Modifier
                        .width(110.dp)
                        .height(26.dp)
                )
                FilterChip(
                    selected = symX,
                    onClick = { symX = !symX; pushSymmetry() },
                    label = { Text("Sym X", style = MaterialTheme.typography.labelSmall) }
                )
                FilterChip(
                    selected = symY,
                    onClick = { symY = !symY; pushSymmetry() },
                    label = { Text("Sym Y", style = MaterialTheme.typography.labelSmall) }
                )
            }
            PressureCurveEditor(
                prefs = prefs,
                onCommit = { pushBrush() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            )
        }

        if (showFilters) {
            FilterDialog(surface = surface, onDismiss = { showFilters = false })
        }
    }
}
