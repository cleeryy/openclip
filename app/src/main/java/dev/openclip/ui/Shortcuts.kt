// OpenClip V+3 — physical-keyboard shortcuts (§7, 0 JNI).
//
// Keyboard-only (Chromebook / tablet + edge keyboard; no soft-keyboard
// rebinding in V+3 — the table is fixed and versioned here). Dispatch is
// a pure function of (keyCode, ctrl, shift) so the emulator gate probes
// it without injecting platform key events; the UI wires it through a
// single onPreviewKeyEvent hook in the studio root.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import android.view.KeyEvent

enum class ShortcutAction {
    UNDO, REDO,
    TOOL_BRUSH, TOOL_ERASER, TOOL_KNIFE,
    SIZE_UP, SIZE_DOWN,
    PICKER,
    LAYER_UP, LAYER_DOWN,
    FRAME_NEXT, FRAME_PREV,
    PLAY_TOGGLE,
    FULLSCREEN,
    HELP,
}

data class ShortcutBinding(
    val keyCode: Int,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val action: ShortcutAction,
    val labelEn: String,
    val labelFr: String,
)

/** Fixed V+3 table (versioned, non-rebindable). */
object ShortcutMap {
    val BINDINGS: List<ShortcutBinding> = listOf(
        ShortcutBinding(KeyEvent.KEYCODE_Z, ctrl = true, action = ShortcutAction.UNDO, labelEn = "Undo", labelFr = "Annuler"),
        ShortcutBinding(KeyEvent.KEYCODE_Y, ctrl = true, action = ShortcutAction.REDO, labelEn = "Redo", labelFr = "Rétablir"),
        ShortcutBinding(KeyEvent.KEYCODE_Z, ctrl = true, shift = true, action = ShortcutAction.REDO, labelEn = "Redo", labelFr = "Rétablir"),
        ShortcutBinding(KeyEvent.KEYCODE_B, action = ShortcutAction.TOOL_BRUSH, labelEn = "Brush", labelFr = "Pinceau"),
        ShortcutBinding(KeyEvent.KEYCODE_E, action = ShortcutAction.TOOL_ERASER, labelEn = "Eraser", labelFr = "Gomme"),
        ShortcutBinding(KeyEvent.KEYCODE_M, action = ShortcutAction.TOOL_KNIFE, labelEn = "Knife", labelFr = "Couteau"),
        ShortcutBinding(KeyEvent.KEYCODE_EQUALS, action = ShortcutAction.SIZE_UP, labelEn = "Size +", labelFr = "Taille +"),
        ShortcutBinding(KeyEvent.KEYCODE_PLUS, action = ShortcutAction.SIZE_UP, labelEn = "Size +", labelFr = "Taille +"),
        ShortcutBinding(KeyEvent.KEYCODE_MINUS, action = ShortcutAction.SIZE_DOWN, labelEn = "Size −", labelFr = "Taille −"),
        ShortcutBinding(KeyEvent.KEYCODE_I, action = ShortcutAction.PICKER, labelEn = "Picker", labelFr = "Pipette"),
        ShortcutBinding(KeyEvent.KEYCODE_LEFT_BRACKET, action = ShortcutAction.LAYER_DOWN, labelEn = "Layer down", labelFr = "Calque bas"),
        ShortcutBinding(KeyEvent.KEYCODE_RIGHT_BRACKET, action = ShortcutAction.LAYER_UP, labelEn = "Layer up", labelFr = "Calque haut"),
        ShortcutBinding(KeyEvent.KEYCODE_PAGE_DOWN, action = ShortcutAction.FRAME_NEXT, labelEn = "Next cel", labelFr = "Cel suivant"),
        ShortcutBinding(KeyEvent.KEYCODE_PAGE_UP, action = ShortcutAction.FRAME_PREV, labelEn = "Prev cel", labelFr = "Cel précédent"),
        ShortcutBinding(KeyEvent.KEYCODE_SPACE, action = ShortcutAction.PLAY_TOGGLE, labelEn = "Play/pause", labelFr = "Lecture/pause"),
        ShortcutBinding(KeyEvent.KEYCODE_F, action = ShortcutAction.FULLSCREEN, labelEn = "Fullscreen", labelFr = "Plein écran"),
        ShortcutBinding(KeyEvent.KEYCODE_SLASH, shift = true, action = ShortcutAction.HELP, labelEn = "Shortcuts (?)", labelFr = "Raccourcis (?)"),
    )

    /** Pure dispatch: exact (keyCode, ctrl, shift) match wins. */
    fun actionFor(keyCode: Int, ctrl: Boolean, shift: Boolean): ShortcutAction? =
        BINDINGS.firstOrNull {
            it.keyCode == keyCode && it.ctrl == ctrl && it.shift == shift
        }?.action
}

/**
 * Testable tool state for the B/E/M probe: the UI mirrors this into
 * the real tool (BrushPrefs/ToolPalette); the gate asserts the switch
 * here without a keyboard.
 */
class ShortcutToolState {
    var activeTool: String = "brush"
        private set

    fun dispatchTool(action: ShortcutAction): Boolean = when (action) {
        ShortcutAction.TOOL_BRUSH -> {
            activeTool = "brush"
            true
        }
        ShortcutAction.TOOL_ERASER -> {
            activeTool = "eraser"
            true
        }
        ShortcutAction.TOOL_KNIFE -> {
            activeTool = "knife"
            true
        }
        else -> false
    }
}
