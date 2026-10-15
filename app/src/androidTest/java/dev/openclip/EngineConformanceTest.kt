// OpenClip — instrumented conformance gate: the app launches, the JNI
// engine host draws a stroke, and undo restores the composite exactly.
// No screenshots, no pixel-perfect art: pixels are only compared against
// the blank white canvas the shim paints at nInit time.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openclip.engine.EngineHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineConformanceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    // Small canvas: fast to composite, still larger than the brush stamp.
    private val w = 256
    private val h = 256

    // a. MainActivity starts and its Compose UI renders.
    @Test
    fun activityLaunchesAndRendersUi() {
        composeRule.onNodeWithText("Brush").assertIsDisplayed()
        composeRule.onNodeWithText("Eraser").assertIsDisplayed()
        composeRule.onNodeWithText("Layers").assertIsDisplayed()
        assertNotNull(composeRule.activity)
    }

    // b. The JNI shim is alive: handles are non-zero, layer ops take effect.
    @Test
    fun engineHandleAndLayers() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            assertNotEquals(0L, handle)
            assertEquals(1, EngineHost.nLayerCount(handle))
            assertEquals(1, EngineHost.nAddLayer(handle, "Layer"))
            assertEquals(2, EngineHost.nLayerCount(handle))
            assertEquals(1, EngineHost.nSetActiveLayer(handle, 1))
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // c+d. A direct JNI stroke changes the composite; undo restores it
    // exactly (white 0xFFFFFFFF everywhere, matching the shim base layer).
    @Test
    fun strokeCompositesAndUndoRestores() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            EngineHost.nSetBrush(
                handle,
                """{"radius":8,"hardness":0.7,"opaque":1.0,"spacing":0.15,"color":"#141414"}"""
            )
            val before = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, before, w, h))
            assertTrue("fresh canvas must be blank white", before.all { it == -1 })

            val strokeId = EngineHost.nStrokeBegin(handle, 64f, 128f, 1f)
            assertNotEquals(0L, strokeId)
            for (x in 70..170 step 5) {
                EngineHost.nStrokeMove(handle, strokeId, x.toFloat(), 128f, 1f)
            }
            EngineHost.nStrokeEnd(handle, strokeId)

            val after = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, after, w, h))
            val changed = after.indices.count { after[it] != before[it] }
            assertTrue("stroke must dirty composite pixels, got $changed", changed > 50)
            // The line passes through the canvas middle: a pixel on its path
            // must no longer be white.
            assertNotEquals(-1, after[128 * w + 120])

            assertTrue(EngineHost.nUndo(handle))
            val undone = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, undone, w, h))
            assertTrue(
                "undo must restore the blank canvas exactly",
                undone.contentEquals(before)
            )
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // e. M2: nLoadMyb accepts a minimal valid document, rejects garbage
    // without throwing, and leaves the brush usable afterwards.
    @Test
    fun loadMybAcceptsValidRejectsMalformed() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            val valid = "<brush>" +
                "<setting name=\"radius\" base_value=\"12.5\"/>" +
                "<setting name=\"hardness\" base_value=\"0.3\"/>" +
                "<setting name=\"opaque\" base_value=\"0.9\"/>" +
                "</brush>"
            assertTrue(EngineHost.nLoadMyb(handle, valid))

            assertFalse(EngineHost.nLoadMyb(handle, "not xml at all"))
            assertFalse(EngineHost.nLoadMyb(handle, "<brush></brush>"))
            assertFalse(EngineHost.nLoadMyb(handle, ""))
            assertFalse(
                EngineHost.nLoadMyb(
                    handle,
                    "<brush><setting name=\"radius\" base_value=\"big\"/></brush>"
                )
            )

            // Brush still paints after the rejections.
            val strokeId = EngineHost.nStrokeBegin(handle, 64f, 128f, 1f)
            EngineHost.nStrokeMove(handle, strokeId, 120f, 128f, 1f)
            EngineHost.nStrokeEnd(handle, strokeId)
            val after = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, after, w, h))
            assertTrue(after.any { it != -1 })
            assertTrue(EngineHost.nUndo(handle))
        } finally {
            EngineHost.nDispose(handle)
        }
    }
}
