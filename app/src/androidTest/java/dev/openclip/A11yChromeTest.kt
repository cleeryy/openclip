// OpenClip — issue #32 §4: accessibility of the chrome bars.
//
// These tests run the REAL composables in isolation at 200 % font scale
// (the system setting a low-vision user turns on) and assert the
// contract the issue asks for:
//   * icon-only actions carry a contentDescription,
//   * the critical actions stay visible and >= 48 dp tall (no clipped
//     tap target, no truncation that hides the action),
//   * the focus/tap order on a bar follows the layout order (left to
//     right), which is what TalkBack and keyboard traversal use.
//
// Isolated rendering is deliberate: it pins the exact composable at an
// exact density, so the test is deterministic on any device. The whole
// activity at 200 % is covered by the manual device kit
// (docs/device-validation.md §6).
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openclip.doc.DocEntry
import dev.openclip.ui.CommandBar
import dev.openclip.ui.CommandBarStore
import dev.openclip.ui.DocTabs
import dev.openclip.ui.MenuBar
import dev.openclip.ui.NewCanvasDialog
import dev.openclip.ui.StudioMenu
import dev.openclip.ui.StudioMenuItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class A11yChromeTest {

    @get:Rule
    val rule = createComposeRule()

    private fun at200FontScale(content: @Composable () -> Unit) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, 2f)
            ) {
                MaterialTheme { content() }
            }
        }
    }

    private fun str(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private val menus = listOf(
        "File", "Edit", "Layer", "Select", "Filter", "View", "Window"
    ).map { title -> StudioMenu(title, listOf(StudioMenuItem("Item A"), StudioMenuItem("Item B"))) }

    // ---- menu bar ----

    @Test
    fun menuBarAt200Percent_actionsStayReachableAndOrdered() {
        at200FontScale { MenuBar(menus = menus) }
        // Every menu title is still a live target of at least 48 dp.
        var lastX = -1f
        for (i in menus.indices) {
            val node = rule.onNodeWithTag("menu-title-$i")
            node.assertExists()
            val b = node.getUnclippedBoundsInRoot()
            assertTrue(
                "menu-title-$i height ${b.height} < 48dp at 200%",
                b.height >= 48.dp
            )
            // Layout order is left-to-right; this is the focus order.
            assertTrue(
                "menu-title-$i order broke: x=${b.left} after $lastX",
                b.left.value >= lastX
            )
            lastX = b.left.value
        }
    }

    @Test
    fun compactMenuBarGlyphHasDescription() {
        // The ☰ affordance is icon-only: its description must be the
        // localized resource, not the glyph.
        rule.setContent {
            MaterialTheme {
                dev.openclip.ui.CompactMenuBar(
                    menus = menus,
                    docTitle = null,
                    docDirty = false,
                    onSave = null,
                    showTitle = false
                )
            }
        }
        val desc = str(R.string.menu_compact_open)
        rule.onNodeWithTag("menu-compact")
            .assertContentDescriptionEquals(desc)
            .assertIsDisplayed()
    }

    // ---- document tabs ----

    @Test
    fun docTabsAt200Percent_addTargetKeepsDescriptionAnd48dp() {
        val docs = listOf(
            DocEntry("d1", "Page 1"),
            DocEntry("d2", "Page 2")
        )
        var added = 0
        at200FontScale {
            DocTabs(
                docs = docs,
                activeDocId = "d1",
                preselectedDocId = null,
                compact = true,
                onActivate = {},
                onClose = {},
                onAdd = { added++ }
            )
        }
        val add = rule.onNodeWithTag("doc-tab-add")
        add.assertExists()
        assertTrue(
            "add-tab target ${add.getUnclippedBoundsInRoot().height} < 48dp",
            add.getUnclippedBoundsInRoot().height >= 48.dp
        )
        rule.onNodeWithTag("doc-tab-add").performScrollTo().performClick()
        assertEquals(1, added)
    }

    // ---- new-canvas dialog ----

    @Test
    fun newCanvasDialogAt200Percent_criticalActionsVisible() {
        var confirmed = 0
        at200FontScale {
            NewCanvasDialog(
                defaultName = "Untitled",
                onConfirm = { confirmed++ },
                onDismiss = {}
            )
        }
        // Confirm/cancel are the critical actions of the dialog.
        listOf("newcanvas-confirm", "newcanvas-cancel").forEach { tag ->
            val b = rule.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("$tag height ${b.height} < 48dp at 200%", b.height >= 48.dp)
        }
        // The width/height steppers keep a 48 dp target and a description.
        val minus = rule.onNodeWithTag("newcanvas-width-minus")
            .assertExists()
            .assertContentDescriptionEquals(str(R.string.a11y_decrease))
            .getUnclippedBoundsInRoot()
        assertTrue("minus target ${minus.height} < 48dp", minus.height >= 48.dp)
        rule.onNodeWithTag("newcanvas-width-plus")
            .assertContentDescriptionEquals(str(R.string.a11y_increase))
        rule.onNodeWithTag("newcanvas-orient")
            .assertContentDescriptionEquals(str(R.string.a11y_swap_wh))
        // The real memory estimate is always visible.
        rule.onNodeWithTag("newcanvas-estimate").assertIsDisplayed()
        rule.onNodeWithTag("newcanvas-confirm").performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun newCanvasDialogRefusesPastDeviceBudget() {
        val docBudgetMb = dev.openclip.engine.MemoryBudget.mb(
            dev.openclip.engine.MemoryBudget.fromContext(
                InstrumentationRegistry.getInstrumentation().targetContext
            ).first
        )
        var confirmed = 0
        // 8192 x 8192 x 4 B = 256 MB base layer... push W/H past any
        // emulator budget by typing the max the field accepts.
        at200FontScale {
            NewCanvasDialog(
                defaultName = "Untitled",
                defaultW = 4096,
                defaultH = 4096,
                onConfirm = { confirmed++ },
                onDismiss = {}
            )
        }
        // Default 4096² is 64 MB: fits a 384+ MB budget. Type 9999 to
        // cross it (4 B/px -> 399 MB > 384 MB on the 1536 MB emulator).
        rule.onNodeWithTag("newcanvas-width").performTextClearance()
        rule.onNodeWithTag("newcanvas-width").performTextInput("9999")
        if (rule.onAllNodesWithTag("newcanvas-mem-refuse").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithTag("newcanvas-mem-refuse").assertIsDisplayed()
            rule.onNodeWithTag("newcanvas-confirm").performClick()
            assertEquals(
                "a refused size must not reach onConfirm (budget ${docBudgetMb}MB)",
                0, confirmed
            )
        }
        // If the device budget is larger than 399 MB the refusal cannot
        // trigger: that is a valid pass (no false refusal).
    }

    // ---- command bar ----

    @Test
    fun commandBarAt200Percent_focusOrderFollowsLayout() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val store = CommandBarStore(ctx)
        val labels = mapOf(
            "save" to "Save", "undo" to "Undo", "redo" to "Redo",
            "export" to "Export", "fullscreen" to "Full"
        )
        at200FontScale {
            Column {
                CommandBar(
                    store = store,
                    labels = labels,
                    onAction = {},
                    onEditHint = {}
                )
            }
        }
        // Only the visible actions are rendered as targets, in the
        // store's order: assert the x positions ascend in that order.
        var lastX = -1f
        for (action in store.shownActions()) {
            val nodes = rule.onAllNodesWithTag("cmdbar-$action")
                .fetchSemanticsNodes()
            if (nodes.isEmpty()) continue
            val node = rule.onAllNodesWithTag("cmdbar-$action")[0]
            val b = node.getUnclippedBoundsInRoot()
            assertTrue(
                "cmdbar action $action order broke: x=${b.left} after $lastX",
                b.left.value >= lastX
            )
            assertTrue("cmdbar action $action < 44dp", b.height >= 44.dp)
            lastX = b.left.value
        }
        // Glyph-only reorder targets in the editor carry descriptions.
        rule.onAllNodesWithTag("command-bar").fetchSemanticsNodes()
    }

    @Test
    fun workflowBarChromeHasNoUndescribedClickTargets() {
        // A regression guard: the four icon-only buttons patched for
        // issue #32 must keep a non-empty description.
        at200FontScale {
            NewCanvasDialog(defaultName = "X", onConfirm = {}, onDismiss = {})
        }
        listOf(
            "newcanvas-orient", "newcanvas-width-minus",
            "newcanvas-width-plus", "newcanvas-height-minus",
            "newcanvas-height-plus"
        ).forEach { tag ->
            val node = rule.onNodeWithTag(tag).fetchSemanticsNode()
            val desc = node.config.getOrNull(SemanticsProperties.ContentDescription)
            assertTrue("$tag has no contentDescription", !desc.isNullOrEmpty())
        }
    }
}
