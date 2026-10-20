// OpenClip — instrumented conformance gate: the app launches, the JNI
// engine host draws a stroke, and undo restores the composite exactly.
// No screenshots, no pixel-perfect art: pixels are only compared against
// the blank white canvas the shim paints at nInit time.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openclip.engine.EngineHost
import dev.openclip.media.AiUpscaler
import dev.openclip.media.ClipAudio
import dev.openclip.media.ExportWebm
import dev.openclip.media.WebdavClient
import dev.openclip.ui.BrushPrefs
import dev.openclip.ui.EngineSurface
import dev.openclip.ui.LayerPanelTestHooks
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class EngineConformanceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    // Small canvas: fast to composite, still larger than the brush stamp.
    private val w = 256
    private val h = 256

    // a. MainActivity starts and its Compose UI renders. On compact
    // phones the dock starts folded (Slice 0 §3): open it first so the
    // Studio tab (Layers) is composed on every form factor.
    @Test
    fun activityLaunchesAndRendersUi() {
        ensureDockOpen()
        composeRule.onNodeWithText("Brush").assertIsDisplayed()
        composeRule.onNodeWithText("Eraser").assertIsDisplayed()
        // Slice 6 chrome shortened the central row: scroll the Layers
        // header back into view before asserting.
        composeRule.onNodeWithText("Layers").performScrollTo()
        composeRule.waitForIdle()
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

    // f. M3: nApplyFilter rejects an unknown name (false, composite
    // unchanged, no crash), and a known cheap filter (posterize) returns
    // true and modifies the composite over a painted stroke.
    @Test
    fun applyFilterRejectsUnknown() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            val before = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, before, w, h))
            assertFalse(EngineHost.nApplyFilter(handle, "nonexistent", "{}"))
            val after = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, after, w, h))
            assertTrue(
                "unknown filter must leave the composite unchanged",
                after.contentEquals(before)
            )
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    @Test
    fun applyFilterKnownPosterizeModifiesComposite() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            EngineHost.nSetBrush(
                handle,
                """{"radius":8,"hardness":0.7,"opaque":1.0,"spacing":0.15,"color":"#804020"}"""
            )
            val strokeId = EngineHost.nStrokeBegin(handle, 64f, 128f, 1f)
            for (x in 70..170 step 5) {
                EngineHost.nStrokeMove(handle, strokeId, x.toFloat(), 128f, 1f)
            }
            EngineHost.nStrokeEnd(handle, strokeId)
            val before = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, before, w, h))
            assertTrue(EngineHost.nApplyFilter(handle, "posterize", """{"levels":2}"""))
            val after = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, after, w, h))
            val changed = after.indices.count { after[it] != before[it] }
            assertTrue(
                "posterize must modify composite pixels, got $changed",
                changed > 0
            )
            // The filter application is undoable like a stroke.
            assertTrue(EngineHost.nUndo(handle))
            val undone = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, undone, w, h))
            assertTrue(
                "undo must restore the pre-filter composite exactly",
                undone.contentEquals(before)
            )
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // M4: document save/load via the real filesystem (cacheDir on the
    // emulator, no SAF interaction in CI). ORA round trip keeps the
    // composite pixel-parity; a corrupt document is refused and the
    // canvas stays untouched.
    @Test
    fun documentLoadSaveOraRoundTrip() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            EngineHost.nSetBrush(
                handle,
                """{"radius":12,"hardness":0.7,"opaque":1.0,"spacing":0.15,"color":"#204080"}"""
            )
            val sid = EngineHost.nStrokeBegin(handle, 96f, 64f, 1f)
            for (x in 100..150 step 5) {
                EngineHost.nStrokeMove(handle, sid, x.toFloat(), 64f, 1f)
            }
            EngineHost.nStrokeEnd(handle, sid)
            val before = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, before, w, h))

            val dir = androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().targetContext.cacheDir
            val ora = java.io.File(dir, "roundtrip.ora")
            assertTrue(EngineHost.nSaveDocument(handle, ora.absolutePath, "ora"))
            assertTrue(ora.length() > 0)

            assertTrue(EngineHost.nLoadDocument(handle, ora.absolutePath))
            val reloaded = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, reloaded, w, h))
            assertTrue(
                "ORA round trip must preserve composite pixels",
                before.contentEquals(reloaded)
            )
            ora.delete()
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    @Test
    fun documentLoadRejectsGarbage() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            val before = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, before, w, h))
            val dir = androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().targetContext.cacheDir
            val bad = java.io.File(dir, "garbage.ora")
            bad.writeText("definitely not an ORA file")
            assertFalse(EngineHost.nLoadDocument(handle, bad.absolutePath))
            val after = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, after, w, h))
            assertTrue(
                "failed load must leave the canvas untouched",
                before.contentEquals(after)
            )
            bad.delete()
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // M5-9: addLayerMask + maskTarget redirect. A black stroke onto the
    // armed mask hides the painted layer where it lands (composite bytes
    // change); undo restores the exact pre-stroke composite.
    @Test
    fun maskTargetStrokeHidesAndUndoRestores() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            EngineHost.nSetBrush(
                handle,
                """{"radius":12,"hardness":1.0,"opaque":1.0,"spacing":0.15,"color":"#204080"}"""
            )
            val sid = EngineHost.nStrokeBegin(handle, 96f, 64f, 1f)
            for (x in 100..150 step 5) {
                EngineHost.nStrokeMove(handle, sid, x.toFloat(), 64f, 1f)
            }
            EngineHost.nStrokeEnd(handle, sid)
            val painted = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, painted, w, h))
            assertTrue(painted.any { it != -1 })

            assertTrue(EngineHost.nAddLayerMask(handle, 0))
            assertTrue(EngineHost.nMaskTarget(handle, 0, true))
            // Black ink erases the mask where the stroke lands.
            EngineHost.nSetBrush(
                handle,
                """{"radius":12,"hardness":1.0,"opaque":1.0,"spacing":0.15,"color":"#000000"}"""
            )
            val mid = EngineHost.nStrokeBegin(handle, 110f, 64f, 1f)
            for (x in 112..150 step 4) {
                EngineHost.nStrokeMove(handle, mid, x.toFloat(), 64f, 1f)
            }
            EngineHost.nStrokeEnd(handle, mid)
            val masked = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, masked, w, h))
            val hidden = masked.indices.count { masked[it] != painted[it] }
            assertTrue("mask stroke must hide composite pixels, got $hidden", hidden > 20)

            assertTrue(EngineHost.nUndo(handle))
            val undone = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, undone, w, h))
            assertTrue(
                "undo must restore the pre-mask composite exactly",
                undone.contentEquals(painted)
            )
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // M5-10: nSetLayerClipped stencil math on real composite bytes. The
    // base layer is opaque white by design, so it is hidden first: the
    // stencil is a transparent layer with a small painted stroke, and the
    // clipped top layer (full-canvas red) only survives on that stroke.
    @Test
    fun clippedLayerRespectsStencil() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            assertTrue(EngineHost.nSetLayerVisibility(handle, 0, false))
            // Stencil: small blue stroke on a transparent layer.
            val stencilLayer = EngineHost.nAddLayer(handle, "stencil")
            assertTrue(stencilLayer >= 1)
            EngineHost.nSetActiveLayer(handle, stencilLayer)
            EngineHost.nSetBrush(
                handle,
                """{"radius":6,"hardness":1.0,"opaque":1.0,"spacing":0.15,"color":"#0000FF"}"""
            )
            val s0 = EngineHost.nStrokeBegin(handle, 40f, 40f, 1f)
            for (x in 42..60 step 2) {
                EngineHost.nStrokeMove(handle, s0, x.toFloat(), 40f, 1f)
            }
            EngineHost.nStrokeEnd(handle, s0)

            // Top: full-canvas red layer.
            val top = EngineHost.nAddLayer(handle, "top")
            assertTrue(top > stencilLayer)
            EngineHost.nSetActiveLayer(handle, top)
            EngineHost.nSetBrush(
                handle,
                """{"radius":64,"hardness":1.0,"opaque":1.0,"spacing":0.15,"color":"#FF0000"}"""
            )
            for (yy in 0..h step 32) {
                val s = EngineHost.nStrokeBegin(handle, 0f, yy.toFloat(), 1f)
                for (x in 0..w step 32) {
                    EngineHost.nStrokeMove(handle, s, x.toFloat(), yy.toFloat(), 1f)
                }
                EngineHost.nStrokeEnd(handle, s)
            }
            val unclipped = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, unclipped, w, h))
            // Sanity: the far corner is opaque red before clipping.
            val far = 200 * w + 200
            assertTrue("top layer must cover the far corner", (unclipped[far] ushr 24) == 255)

            assertTrue(EngineHost.nSetLayerClipped(handle, top, true))
            val clipped = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, clipped, w, h))
            val changed = clipped.indices.count { clipped[it] != unclipped[it] }
            assertTrue("clipping must change composite pixels, got $changed", changed > 100)
            // Far from the stencil the clipped layer contributes nothing:
            // base hidden + stencil transparent there = fully transparent.
            assertEquals(0, clipped[far])
            // On the stencil stroke the clipped red survives (opaque).
            val on = 40 * w + 50
            assertTrue("clipped layer must paint on the stencil", (clipped[on] ushr 24) == 255)

            // Unclip restores byte parity with the pre-clip composite.
            assertTrue(EngineHost.nSetLayerClipped(handle, top, false))
            val restored = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, restored, w, h))
            assertTrue(
                "unclip must restore the pre-clip composite exactly",
                restored.contentEquals(unclipped)
            )
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // M6: vector layer — appended paths rasterize into the composite and
    // per-layer vector undo removes the last path's contribution.
    @Test
    fun vectorLayerAppendAndUndo() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            val vi = EngineHost.nAddVectorLayer(handle, "vector")
            assertTrue("addVectorLayer must return a valid index", vi >= 0)
            val before = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, before, w, h))
            assertTrue(EngineHost.nSetVectorMode(handle, vi, true))
            assertTrue(EngineHost.nSetActiveLayer(handle, vi) >= 0)
            assertTrue(
                EngineHost.nAppendVectorPath(
                    handle, vi,
                    """{"pts":[{"x":80,"y":80},{"x":160,"y":160}]}"""
                ) >= 0
            )
            val after = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, after, w, h))
            val changed = after.indices.count { after[it] != before[it] }
            assertTrue(
                "vector path must modify composite pixels, got $changed",
                changed > 0
            )
            assertTrue("vector undo should pop one path", EngineHost.nUndoVector(handle, vi))
            val undone = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, undone, w, h))
            assertTrue(
                "vector undo must restore the pre-path composite",
                undone.contentEquals(before)
            )
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // M6: rulers — symmetryX mirrors every stamp across a vertical line.
    @Test
    fun rulerSymmetryMirrorsStrokes() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            // Canvas is w x h = 256x256 (test fixture); mirror at 128.
            assertTrue(EngineHost.nSetRulers(handle, """{"symmetryX":128.0}"""))
            val sid = EngineHost.nStrokeBegin(handle, 48f, 128f, 1f)
            EngineHost.nStrokeEnd(handle, sid)
            val cx = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, cx, w, h))
            fun painted(x: Int, y: Int): Boolean = cx[y * w + x] != 0xFFFFFFFF.toInt()
            assertTrue("left point must be painted", painted(48, 128))
            assertTrue(
                "mirror point (208) must be painted by symmetry",
                painted(208, 128)
            )
            assertTrue(
                "a point equidistant on the other side must stay empty",
                !painted(64, 128)
            )
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // M6: vector eraser removes an intersecting path (path-level erase).
    @Test
    fun vectorEraserRemovesIntersectingPath() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            val vi = EngineHost.nAddVectorLayer(handle, "vec")
            EngineHost.nSetActiveLayer(handle, vi)
            val px0 = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, px0, w, h))
            assertTrue(
                EngineHost.nAppendVectorPath(
                    handle, vi,
                    """{"pts":[{"x":40,"y":40},{"x":120,"y":40}]}"""
                ) >= 0
            )
            val px1 = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, px1, w, h))
            assertTrue(px1.contentNotEquals(px0))
            // Erase along a line crossing the path in the middle.
            assertTrue(
                EngineHost.nVectorEraser(
                    handle, vi, true,
                    """{"pts":[{"x":0,"y":40},{"x":200,"y":40}]}"""
                )
            )
            val px2 = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, px2, w, h))
            assertTrue(
                "after erasing the only path, composite must return to pre-path",
                px2.contentEquals(px0)
            )
        } finally {
            EngineHost.nDispose(handle)
        }
    }
    // M7-14: frames — addFrame grows the clip, thumbnails see each
    // frame, deleteFrame shrinks back to one.
    @Test
    fun framesAddCountThumbnailDelete() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            assertEquals(1, EngineHost.nFrameCount(handle))
            assertEquals(1, EngineHost.nAddFrame(handle))
            assertEquals(2, EngineHost.nFrameCount(handle))
            val dup = EngineHost.nDuplicateFrame(handle)
            assertEquals(2, dup)
            assertEquals(3, EngineHost.nFrameCount(handle))
            // Each frame composites to exactly one white base layer.
            for (i in 0 until 3) {
                val px = IntArray(w * h)
                assertEquals(w * h, EngineHost.nFrameCompositeInto(handle, i, px, w, h))
                assertTrue("frame $i must be blank white", px.all { it == -1 })
            }
            // Out-of-range indexes are refused, state untouched.
            assertFalse(EngineHost.nSetFrame(handle, 9))
            assertFalse(EngineHost.nDeleteFrame(handle, 9))
            assertEquals(3, EngineHost.nFrameCount(handle))
            // Delete the middle frame; the current index clamps, history
            // semantics stay valid (undo of nothing = false).
            assertTrue(EngineHost.nSetFrame(handle, 2))
            assertTrue(EngineHost.nDeleteFrame(handle, 1))
            assertEquals(2, EngineHost.nFrameCount(handle))
            assertFalse(EngineHost.nUndo(handle))
            // The last frame can never be deleted.
            assertTrue(EngineHost.nDeleteFrame(handle, 1))
            assertEquals(1, EngineHost.nFrameCount(handle))
            assertFalse(EngineHost.nDeleteFrame(handle, 0))
            assertEquals(1, EngineHost.nFrameCount(handle))
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // M7-15: frame switch isolation — a stroke on frame 1 never stains
    // frame 0 (full Canvas copies, per-frame history reset).
    @Test
    fun frameSwitchIsolatesStrokes() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            assertEquals("addFrame must append and select frame 1",
                         1, EngineHost.nAddFrame(handle))
            assertTrue("switch to frame 1 must succeed",
                       EngineHost.nSetFrame(handle, 1))
            EngineHost.nSetBrush(
                handle,
                """{"radius":12,"hardness":1.0,"opaque":1.0,"spacing":0.15,"color":"#141414"}"""
            )
            val sid = EngineHost.nStrokeBegin(handle, 64f, 128f, 1f)
            for (x in 70..170 step 5) {
                EngineHost.nStrokeMove(handle, sid, x.toFloat(), 128f, 1f)
            }
            EngineHost.nStrokeEnd(handle, sid)
            val onFrame1 = IntArray(w * h)
            assertEquals("composite must fill the buffer", w * h,
                         EngineHost.nCompositeInto(handle, onFrame1, w, h))
            assertTrue("stroke must be visible on frame 1 (colored pixels)",
                       onFrame1.any { it != -1 })

            assertTrue("switch back to frame 0 must succeed",
                       EngineHost.nSetFrame(handle, 0))
            val onFrame0 = IntArray(w * h)
            assertEquals("composite must fill the buffer", w * h,
                         EngineHost.nCompositeInto(handle, onFrame0, w, h))
            assertTrue("frame 0 must stay blank white", onFrame0.all { it == -1 })
            // History reset on switch: nothing to undo on frame 0.
            assertFalse(EngineHost.nUndo(handle))

            // Back on frame 1 the stroke is still there and undoable.
            assertTrue("switch to frame 1 again must succeed",
                       EngineHost.nSetFrame(handle, 1))
            val back = IntArray(w * h)
            assertEquals("composite must fill the buffer", w * h,
                         EngineHost.nCompositeInto(handle, back, w, h))
            assertTrue("frame 1 stroke must survive the round trip",
                       back.contentEquals(onFrame1))
            assertTrue("undo must be available on frame 1 after switch",
                       EngineHost.nUndo(handle))
            val undone = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, undone, w, h))
            assertTrue("undo on frame 1 must restore white", undone.all { it == -1 })
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // M7-16: onion skin — the next frame's paint shows through the
    // current (transparent-base) frame at strength once armed.
    @Test
    fun onionNextGhostVisibleInComposite() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            assertTrue(EngineHost.nSetLayerVisibility(handle, 0, false))
            EngineHost.nSetBrush(
                handle,
                """{"radius":12,"hardness":1.0,"opaque":1.0,"spacing":0.15,"color":"#FF0000"}"""
            )
            val dup = EngineHost.nDuplicateFrame(handle)
            assertEquals(1, dup)
            // The duplicate inherits the hidden base (that is why the
            // pre-dup frame0 is transparent). Re-show it: an animation
            // frame must be visible to leave ghosts in the onion pass.
            assertTrue(EngineHost.nSetLayerVisibility(handle, 0, true))
            for (yy in 96..160 step 32) {
                val s = EngineHost.nStrokeBegin(handle, 96f, yy.toFloat(), 1f)
                for (x in 96..160 step 8) {
                    EngineHost.nStrokeMove(handle, s, x.toFloat(), yy.toFloat(), 1f)
                }
                EngineHost.nStrokeEnd(handle, s)
            }
            assertTrue(EngineHost.nSetFrame(handle, 0))
            val plain = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, plain, w, h))
            assertTrue("empty frame must stay transparent", plain.all { it == 0 })

            assertTrue(EngineHost.nSetOnion(handle, false, true, 1.0f))
            val ghost = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, ghost, w, h))
            val added = ghost.indices.count { ghost[it] != plain[it] }
            assertTrue("onion next must add ghost pixels, got $added", added > 100)
            // Ghost is tinted: not pure opaque red, but visible alpha.
            val probe = ghost[128 * w + 128]
            assertTrue("ghost pixel must carry alpha", (probe ushr 24) > 0)
            assertTrue("ghost pixel must not be plain white", probe != -1)

            // Disarming restores the exact transparent frame.
            assertTrue(EngineHost.nSetOnion(handle, false, false, 0.3f))
            val restored = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, restored, w, h))
            assertTrue(
                "disarmed onion must restore the plain composite",
                restored.contentEquals(plain)
            )
        } finally {
            EngineHost.nDispose(handle)
        }
    }
    // M7-17: timeline thumbnail parity — every frame index the strip
    // would pull composites to a non-white frame exactly where the
    // stroke landed; thumbnails for empty frames stay blank white.
    // (Frame-switch isolation itself is covered by M7-15 above.)
    @Test
    fun timelineThumbnailsMatchFrameCount() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            assertEquals(1, EngineHost.nAddFrame(handle))
            assertEquals(2, EngineHost.nFrameCount(handle))
            assertTrue(EngineHost.nSetFrame(handle, 1))
            EngineHost.nSetBrush(
                handle,
                """{"radius":12,"hardness":1.0,"opaque":1.0,"spacing":0.15,"color":"#141414"}"""
            )
            val sid = EngineHost.nStrokeBegin(handle, 64f, 128f, 1f)
            for (x in 70..170 step 5) {
                EngineHost.nStrokeMove(handle, sid, x.toFloat(), 128f, 1f)
            }
            EngineHost.nStrokeEnd(handle, sid)

            // The strip pulls one thumbnail per frame index: frame 0 is
            // untouched, frame 1 carries the stroke.
            val t0 = IntArray(w * h)
            assertEquals(w * h, EngineHost.nFrameCompositeInto(handle, 0, t0, w, h))
            assertTrue("thumbnail 0 must stay blank white", t0.all { it == -1 })
            val t1 = IntArray(w * h)
            assertEquals(w * h, EngineHost.nFrameCompositeInto(handle, 1, t1, w, h))
            val changed = t1.indices.count { t1[it] != t0[it] }
            assertTrue("thumbnail 1 must show the stroke, got $changed", changed > 50)

            // Bad indexes are refused with the documented -2 code.
            val junk = IntArray(w * h)
            assertEquals(-2, EngineHost.nFrameCompositeInto(handle, 7, junk, w, h))
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // M7b-18: webm export — a 3-frame clip encodes to a real EBML/webm
    // file (magic header + size floor). Frames pulled straight from JNI.
    @Test
    fun webmExportProducesPlayableFile() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            assertEquals(1, EngineHost.nAddFrame(handle))
            assertEquals(2, EngineHost.nAddFrame(handle))
            assertEquals(3, EngineHost.nFrameCount(handle))
            val colors = arrayOf("#141414", "#C80000", "#0014C8")
            for (i in 0 until 3) {
                assertTrue(EngineHost.nSetFrame(handle, i))
                EngineHost.nSetBrush(
                    handle,
                    """{"radius":12,"hardness":1.0,"opaque":1.0,"spacing":0.15,"color":"${colors[i]}"}"""
                )
                val sid = EngineHost.nStrokeBegin(handle, 64f, 128f, 1f)
                for (x in 70..170 step 5) {
                    EngineHost.nStrokeMove(handle, sid, x.toFloat(), 128f, 1f)
                }
                EngineHost.nStrokeEnd(handle, sid)
            }
            val frames = (0 until 3).map { i ->
                val px = IntArray(w * h)
                assertEquals(w * h, EngineHost.nFrameCompositeInto(handle, i, px, w, h))
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.setPixels(px, 0, w, 0, 0, w, h)
                bmp
            }
            val out = File(composeRule.activity.cacheDir, "m7b-test.webm")
            if (out.exists()) out.delete()
            assertTrue("webm export must succeed", ExportWebm.export(frames, 12f, out))
            assertTrue("webm file must exist", out.exists())
            assertTrue(
                "webm file must clear 512B, got ${out.length()}",
                out.length() > 512
            )
            val head = ByteArray(4)
            FileInputStream(out).use { it.read(head) }
            assertEquals("EBML header magic", 0x1A, head[0].toInt() and 0xFF)
            assertEquals("EBML header magic", 0x45, head[1].toInt() and 0xFF)
            assertEquals("EBML header magic", 0xDF, head[2].toInt() and 0xFF)
            assertEquals("EBML header magic", 0xA3, head[3].toInt() and 0xFF)
            frames.forEach { if (!it.isRecycled) it.recycle() }
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // M7b-19: audio controller state machine on a generated WAV fixture
    // (audibility never asserted — headless emulator).
    @Test
    fun audioAttachPlayPause() {
        val wav = File(composeRule.activity.cacheDir, "m7b-tone.wav")
        writeSineWav(wav)
        val audio = ClipAudio()
        try {
            assertTrue("wav must attach", audio.attach(wav.absolutePath, "tone.wav"))
            assertTrue(audio.hasAudio)
            assertEquals("tone.wav", audio.fileName)
            assertTrue(
                "duration must be positive, got ${audio.durationMs()}",
                audio.durationMs() > 0
            )
            assertTrue("play must not throw", audio.play())
            assertTrue(audio.isPlaying)
            audio.pause()
            assertFalse(audio.isPlaying)
        } finally {
            audio.clear()
        }
        assertFalse(audio.hasAudio)
    }

    // M8-20: AI x3 super-resolution — a deterministic two-tone bitmap
    // upscales to exactly 3x with real pixel content (luma follows the
    // source's bright/dark split; no crash on x86_64 CPU EP).
    @Test
    fun aiSuperResTriplesBitmap() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val src = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                val v = if (y < 32) 240 else 15  // top bright, bottom dark
                src.setPixel(x, y, (0xFF shl 24) or (v shl 16) or (v shl 8) or v)
            }
        }
        val up = AiUpscaler.upscale(ctx, src)
        assertNotNull(
            "ai upscale must produce a bitmap: ${AiUpscaler.lastError}",
            up
        )
        assertEquals("width must triple", 192, up!!.width)
        assertEquals("height must triple", 192, up.height)
        val top = up.getPixel(32, 16)
        val bottom = up.getPixel(32, 176)
        assertTrue("top region must stay bright", (top and 0xFF) > 100)
        assertTrue("bottom region must stay dark", (bottom and 0xFF) < 120)
        assertTrue(
            "bright/dark split must survive",
            ((top and 0xFF) - (bottom and 0xFF)) > 60
        )
        src.recycle()
        if (!up.isRecycled) up.recycle()
    }

    // M9-21: glTF reference overlay — a generated cube .glb loads through
    // JNI (18 edges), composites edge pixels over the frame, and the
    // visibility toggle clears them.
    @Test
    fun refLoadAndCompositeShowsEdges() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            val glb = File(composeRule.activity.cacheDir, "cube.glb")
            writeCubeGlb(glb)
            assertEquals(
                "cube must load with 18 edges", 18,
                EngineHost.nLoadGlbReference(handle, glb.absolutePath)
            )
            assertTrue(EngineHost.nSetRefVisible(handle, true))
            val withRef = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, withRef, w, h))
            val edgePixels = withRef.indices.count { withRef[it] != -1 }
            assertTrue(
                "reference must add edge pixels, got $edgePixels",
                edgePixels > 0
            )
            assertTrue(EngineHost.nSetRefVisible(handle, false))
            val without = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, without, w, h))
            assertTrue(
                "hiding the reference must restore the clean frame",
                without.all { it == -1 }
            )
            assertTrue(glb.delete())
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // Minimal valid glTF 2.0 binary in-code: unit cube, 12 triangles
    // (18 unique edges). glTF is LITTLE-ENDIAN — ByteBuffer LE order
    // (DataOutputStream big-endian would corrupt the magic). Clean-room:
    // no binary assets in git.
    private fun writeCubeGlb(dst: File) {
        val verts = floatArrayOf(
            -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, -0.5f,
            -0.5f, 0.5f, -0.5f, -0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f,
            0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f
        )
        val idx = intArrayOf(
            0, 1, 2, 0, 2, 3, 4, 6, 5, 4, 7, 6, 0, 4, 5, 0, 5, 1,
            3, 2, 6, 3, 6, 7, 0, 3, 7, 0, 7, 4, 1, 5, 6, 1, 6, 2
        )
        val bin = ByteBuffer.allocate(96 + 72).order(ByteOrder.LITTLE_ENDIAN)
        for (v in verts) bin.putFloat(v)
        for (i in idx) bin.putShort(i.toShort())
        val binBytes = bin.array()
        val json = """{"asset":{"version":"2.0"},"buffers":[{"byteLength":${binBytes.size}}],"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":96,"target":34962},{"buffer":0,"byteOffset":96,"byteLength":72,"target":34963}],"accessors":[{"bufferView":0,"componentType":5126,"count":8,"type":"VEC3"},{"bufferView":1,"componentType":5123,"count":36,"type":"SCALAR"}],"meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}],"nodes":[{"mesh":0}],"scenes":[{"nodes":[0]}],"scene":0}"""
        val jb = json.toByteArray()
        val pad = (4 - jb.size % 4) % 4
        val total = 12 + 8 + jb.size + pad + 8 + binBytes.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67)  // "glTF"
        out.putInt(2)
        out.putInt(total)
        out.putInt(jb.size + pad)
        out.putInt(0x4E4F534A)  // "JSON"
        out.put(jb)
        repeat(pad) { out.put(' '.code.toByte()) }
        out.putInt(binBytes.size)
        out.putInt(0x004E4942)  // "BIN\0"
        out.put(binBytes)
        dst.writeBytes(out.array())
    }

    // M10-22: material tone via JNI repaints the active layer and undo
    // restores the clean frame.
    @Test
    fun toneFillChangesCompositeAndUndoRestores() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            val before = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, before, w, h))
            assertTrue(EngineHost.nApplyTone(handle, "hatch"))
            val after = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, after, w, h))
            assertTrue(
                "tone must repaint pixels",
                !after.contentEquals(before)
            )
            assertTrue(EngineHost.nUndo(handle))
            val undone = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, undone, w, h))
            assertTrue(
                "undo must restore the clean frame",
                undone.contentEquals(before)
            )
            assertFalse(
                "unknown tone must be refused",
                EngineHost.nApplyTone(handle, "bogus")
            )
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // M10-23: WebDAV round trip against an in-process loopback server
    // (PUT 201 + GET bytes back; wrong password -> 401 -> failure).
    @Test
    fun webdavRoundTripLoopback() {
        val goodAuth = "Basic " + android.util.Base64.encodeToString(
            "user:pass".toByteArray(), android.util.Base64.NO_WRAP
        )
        var stored = byteArrayOf()
        val stop = AtomicBoolean(false)
        val server = ServerSocket(0)
        val port = server.localPort
        val t = Thread {
            try {
                while (!stop.get()) {
                    server.accept().use { sock ->
                        val i = sock.getInputStream()
                        val o = sock.getOutputStream()
                        val all = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(2048)
                        var end = -1
                        while (end < 0) {
                            val r = i.read(buf)
                            if (r < 0) return@use
                            all.write(buf, 0, r)
                            end = all.toString("ISO-8859-1").indexOf("\r\n\r\n")
                        }
                        val head = all.toString("ISO-8859-1").substring(0, end)
                        val lines = head.split("\r\n")
                        val method = lines[0].substringBefore(" ")
                        val authed = head.contains(goodAuth)
                        var cl = 0
                        for (l in lines) {
                            if (l.startsWith("Content-Length:")) {
                                cl = l.substringAfter(":").trim().toInt()
                            }
                        }
                        val body = ByteArray(cl)
                        var have = all.size() - (end + 4)
                        if (have > 0) {
                            System.arraycopy(
                                all.toByteArray(), end + 4, body, 0,
                                minOf(have, cl)
                            )
                        }
                        var got = have
                        while (got < cl) {
                            val r = i.read(body, got, cl - got)
                            if (r < 0) break
                            got += r
                        }
                        if (!authed) {
                            o.write(
                                ("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n" +
                                    "Connection: close\r\n\r\n").toByteArray()
                            )
                        } else if (method == "PUT") {
                            stored = body.copyOf()
                            o.write(
                                ("HTTP/1.1 201 Created\r\nContent-Length: 0\r\n" +
                                    "Connection: close\r\n\r\n").toByteArray()
                            )
                        } else {
                            o.write(
                                ("HTTP/1.1 200 OK\r\nContent-Length: " +
                                    "${stored.size}\r\nConnection: close\r\n\r\n")
                                    .toByteArray()
                            )
                            o.write(stored)
                        }
                        o.flush()
                    }
                }
            } catch (_: Exception) {
            }
        }
        t.start()
        try {
            val payload = "ORA-DOC-${System.nanoTime()}".toByteArray()
            val doc = File(composeRule.activity.cacheDir, "wd.ora")
            doc.writeBytes(payload)
            assertTrue(
                "push must succeed",
                WebdavClient.push(
                    doc, "http://127.0.0.1:$port/doc.ora", "user", "pass"
                )
            )
            assertTrue(
                "server must hold the payload",
                stored.contentEquals(payload)
            )
            val pulled = File(composeRule.activity.cacheDir, "wd-back.ora")
            assertTrue(
                "pull must succeed",
                WebdavClient.pull(
                    "http://127.0.0.1:$port/doc.ora", "user", "pass", pulled
                )
            )
            assertTrue(
                "pulled bytes must round trip",
                pulled.readBytes().contentEquals(payload)
            )
            assertFalse(
                "wrong password must fail",
                WebdavClient.push(
                    doc, "http://127.0.0.1:$port/doc.ora", "user", "wrong"
                )
            )
            doc.delete()
            pulled.delete()
        } finally {
            stop.set(true)
            try {
                server.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun findEngineSurface(root: View): EngineSurface? {
        if (root is EngineSurface) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findEngineSurface(root.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    // Slice 6: with N tabs only the resumed surface is interactive; prefer
    // it (a parked tab keeps its size but ignores input until resume).
    private fun findActiveEngineSurface(root: View): EngineSurface? {
        if (root is EngineSurface) {
            if (!root.isPaused()) return root
            return null
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findActiveEngineSurface(root.getChildAt(i))
                if (found != null) return found
            }
            // Fallback: every tab parked (transient) — return the first.
            for (i in 0 until root.childCount) {
                val found = findEngineSurface(root.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    // Regression gate for tablet UI + touch pipeline: the live EngineSurface
    // on MainActivity must have substantial visible dimensions (never crushed
    // to 0px by panels), and a real MotionEvent DOWN -> MOVE -> UP stroke
    // must draw ink into the active frame and be undoable.
    @Test
    fun canvasSurfaceHasVisibleSizeAndTouchDrawsStroke() {
        composeRule.waitForIdle()
        var surface: EngineSurface? = null
        composeRule.activityRule.scenario.onActivity { act ->
            surface = findActiveEngineSurface(act.window.decorView)
        }
        val es = surface
        assertNotNull("EngineSurface must be attached to MainActivity", es)
        assertTrue(
            "EngineSurface width (${es!!.width}) and height (${es.height}) must be > 200px",
            es.width > 200 && es.height > 200
        )

        val cx = es.width * 0.5f
        val cy = es.height * 0.5f
        composeRule.activityRule.scenario.onActivity {
            val t0 = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, cx - 80f, cy, 0.8f, 1f, 0, 1f, 1f, 0, 0)
            val move1 = MotionEvent.obtain(t0, t0 + 16, MotionEvent.ACTION_MOVE, cx, cy, 0.8f, 1f, 0, 1f, 1f, 0, 0)
            val move2 = MotionEvent.obtain(t0, t0 + 32, MotionEvent.ACTION_MOVE, cx + 80f, cy, 0.8f, 1f, 0, 1f, 1f, 0, 0)
            val up = MotionEvent.obtain(t0, t0 + 48, MotionEvent.ACTION_UP, cx + 80f, cy, 0.8f, 1f, 0, 1f, 1f, 0, 0)
            es.dispatchTouchEvent(down)
            es.dispatchTouchEvent(move1)
            es.dispatchTouchEvent(move2)
            es.dispatchTouchEvent(up)
            down.recycle(); move1.recycle(); move2.recycle(); up.recycle()
        }

        // Wait briefly for the render thread to drain the touch queue.
        Thread.sleep(350)

        var bmp: Bitmap? = null
        val lock = Object()
        es.pullFrameFull(0) { b ->
            synchronized(lock) {
                bmp = b
                lock.notifyAll()
            }
        }
        synchronized(lock) {
            if (bmp == null) lock.wait(5000)
        }
        assertNotNull("pullFrameFull must return a bitmap", bmp)
        val b = bmp!!
        val midY = b.height / 2
        var nonWhite = 0
        for (x in 0 until b.width) {
            if (b.getPixel(x, midY) != -1) nonWhite++
        }
        b.recycle()
        assertTrue(
            "touch stroke across canvas center must paint non-white pixels (got $nonWhite)",
            nonWhite > 20
        )
        assertTrue("undo after touch stroke must succeed", es.undo())
    }

    // ---- Slice 1 canvas gestures (spec docs/slice1-gestures.md §8) ----
    // Each test drives the live EngineSurface with synthetic MotionEvents
    // (the KVM emulator has no stylus) and asserts on the engine
    // composite, which must stay ink-free through every navigation
    // gesture. The view is reset after each test.

    private fun countRedDom(b: Bitmap): Int {
        var red = 0
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val px = b.getPixel(x, y)
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val bl = px and 0xFF
                if (r > g + 40 && r > bl + 40) red++
                x += 4
            }
            y += 4
        }
        return red
    }

    private fun surf(): EngineSurface {
        composeRule.waitForIdle()
        var surface: EngineSurface? = null
        composeRule.activityRule.scenario.onActivity { act ->
            surface = findActiveEngineSurface(act.window.decorView)
        }
        assertNotNull("EngineSurface must be attached to MainActivity", surface)
        return surface!!
    }

    /** Poll until the render thread accepts input (cold-start safe). */
    private fun awaitSurface(es: EngineSurface) {
        var tries = 0
        while (tries < 50 && (es.width <= 200 || es.height <= 200 || !es.isReady())) {
            Thread.sleep(100)
            tries++
        }
        assertTrue(
            "surface must become ready (w=${es.width} h=${es.height} ready=${es.isReady()})",
            es.width > 200 && es.height > 200 && es.isReady()
        )
    }

    private fun uiSync(op: (EngineSurface) -> Unit) {
        composeRule.activityRule.scenario.onActivity { act ->
            op(findActiveEngineSurface(act.window.decorView)!!)
        }
    }

    private fun dispatchAll(es: EngineSurface, vararg events: MotionEvent) {
        composeRule.activityRule.scenario.onActivity {
            for (e in events) es.dispatchTouchEvent(e)
        }
        for (e in events) e.recycle()
    }

    private fun simpleDown(t0: Long, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x, y, 1f, 1f, 0, 1f, 1f, 0, 0)

    private fun fingerProps(vararg ids: Int): Array<MotionEvent.PointerProperties> =
        ids.map { id ->
            MotionEvent.PointerProperties().apply {
                this.id = id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }.toTypedArray()

    private fun fingerCoords(vararg xy: Pair<Float, Float>): Array<MotionEvent.PointerCoords> =
        xy.map { (x, y) ->
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 1f
            }
        }.toTypedArray()

    private fun multiObtain(
        downTime: Long,
        eventTime: Long,
        action: Int,
        props: Array<MotionEvent.PointerProperties>,
        coords: Array<MotionEvent.PointerCoords>
    ): MotionEvent = MotionEvent.obtain(
        downTime, eventTime, action, props.size, props, coords,
        0, 0, 1f, 1f, 0, 0, 0, 0
    )

    private fun pullFull(es: EngineSurface, idx: Int = 0): Bitmap {
        var bmp: Bitmap? = null
        val lock = Object()
        es.pullFrameFull(idx) { b ->
            synchronized(lock) {
                bmp = b
                lock.notifyAll()
            }
        }
        synchronized(lock) {
            if (bmp == null) lock.wait(8000)
        }
        assertNotNull("pullFrameFull must return a bitmap", bmp)
        return bmp!!
    }

    private fun countInk(b: Bitmap): Int {
        var n = 0
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                if (b.getPixel(x, y) != -1) n++
                x += 8
            }
            y += 8
        }
        return n
    }

    private fun strokeAcross(es: EngineSurface, y: Float, t0: Long, cx: Float) {
        dispatchAll(
            es,
            simpleDown(t0, cx - 80f, y),
            MotionEvent.obtain(t0, t0 + 16, MotionEvent.ACTION_MOVE, cx, y, 1f, 1f, 0, 1f, 1f, 0, 0),
            MotionEvent.obtain(t0, t0 + 32, MotionEvent.ACTION_MOVE, cx + 80f, y, 1f, 1f, 0, 1f, 1f, 0, 0),
            MotionEvent.obtain(t0, t0 + 48, MotionEvent.ACTION_UP, cx + 80f, y, 1f, 1f, 0, 1f, 1f, 0, 0)
        )
    }

    private fun twoFingerTap(es: EngineSurface, x: Float, y: Float, t0: Long) {
        val props = fingerProps(0, 1)
        val c = fingerCoords(Pair(x, y), Pair(x + 20f, y))
        val down = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        dispatchAll(
            es,
            simpleDown(t0, x, y),
            multiObtain(t0, t0 + 10, MotionEvent.ACTION_POINTER_DOWN or down, props, c),
            multiObtain(t0, t0 + 60, MotionEvent.ACTION_POINTER_UP or down, props, c),
            MotionEvent.obtain(t0, t0 + 80, MotionEvent.ACTION_UP, x, y, 1f, 1f, 0, 1f, 1f, 0, 0)
        )
    }

    private fun threeFingerTap(es: EngineSurface, x: Float, y: Float, t0: Long) {
        val props2 = fingerProps(0, 1)
        val props3 = fingerProps(0, 1, 2)
        val c2 = fingerCoords(Pair(x, y), Pair(x + 20f, y))
        val c3 = fingerCoords(Pair(x, y), Pair(x + 20f, y), Pair(x + 10f, y + 20f))
        val d1 = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        val d2 = 2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        dispatchAll(
            es,
            simpleDown(t0, x, y),
            multiObtain(t0, t0 + 10, MotionEvent.ACTION_POINTER_DOWN or d1, props2, c2),
            multiObtain(t0, t0 + 20, MotionEvent.ACTION_POINTER_DOWN or d2, props3, c3),
            multiObtain(t0, t0 + 60, MotionEvent.ACTION_POINTER_UP or d2, props3, c3),
            multiObtain(t0, t0 + 80, MotionEvent.ACTION_POINTER_UP or d1, props2, c2),
            MotionEvent.obtain(t0, t0 + 100, MotionEvent.ACTION_UP, x, y, 1f, 1f, 0, 1f, 1f, 0, 0)
        )
    }

    // 1. A stroke cancelled by a second finger + pinch spread leaves no
    // ink, and there is nothing left to undo (Slice 0 rollback already
    // cleaned it).
    @Test
    fun pinchZoomLeavesNoInk() {
        val es = surf()
        uiSync { it.navigateMode = false; it.resetView() }
        val t0 = SystemClock.uptimeMillis()
        val cx = es.width * 0.5f
        val cy = es.height * 0.5f
        val props2 = fingerProps(0, 1)
        dispatchAll(
            es,
            simpleDown(t0, cx - 80f, cy),
            MotionEvent.obtain(t0, t0 + 16, MotionEvent.ACTION_MOVE, cx, cy, 1f, 1f, 0, 1f, 1f, 0, 0),
            multiObtain(
                t0, t0 + 32,
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                props2, fingerCoords(Pair(cx, cy), Pair(cx + 40f, cy))
            ),
            multiObtain(
                t0, t0 + 48, MotionEvent.ACTION_MOVE,
                props2, fingerCoords(Pair(cx - 80f, cy), Pair(cx + 80f, cy))
            ),
            multiObtain(
                t0, t0 + 64, MotionEvent.ACTION_MOVE,
                props2, fingerCoords(Pair(cx - 120f, cy), Pair(cx + 120f, cy))
            ),
            multiObtain(
                t0, t0 + 80,
                MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                props2, fingerCoords(Pair(cx - 120f, cy), Pair(cx + 120f, cy))
            ),
            MotionEvent.obtain(t0, t0 + 96, MotionEvent.ACTION_UP, cx - 120f, cy, 1f, 1f, 0, 1f, 1f, 0, 0)
        )
        Thread.sleep(400)
        val b = pullFull(es)
        val ink = countInk(b)
        b.recycle()
        assertTrue("pinch must leave no ink, got $ink", ink == 0)
        assertFalse("rolled-back stroke leaves nothing to undo", es.undo())
        uiSync { it.resetView() }
    }

    // 2. Two-finger tap undoes a stroke; a second tap inside the 400 ms
    // debounce window is swallowed (exactly one undo).
    @Test
    fun twoFingerTapUndoesStroke() {
        val es = surf()
        uiSync { it.navigateMode = false; it.resetView() }
        val t0 = SystemClock.uptimeMillis()
        val cx = es.width * 0.5f
        val cy = es.height * 0.5f
        strokeAcross(es, cy, t0, cx)
        Thread.sleep(300)
        strokeAcross(es, cy + 100f, t0 + 1000, cx)
        Thread.sleep(300)
        var b = pullFull(es)
        assertTrue("two strokes must paint ink", countInk(b) > 20)
        b.recycle()
        twoFingerTap(es, cx, cy, t0 + 2000)
        Thread.sleep(300)
        b = pullFull(es)
        assertTrue("first tap must undo stroke B but keep A", countInk(b) > 20)
        b.recycle()
        twoFingerTap(es, cx, cy, t0 + 2100)
        Thread.sleep(300)
        b = pullFull(es)
        assertTrue("debounced tap must not consume stroke A", countInk(b) > 20)
        b.recycle()
        assertTrue("stroke A must still be undoable", es.undo())
        assertFalse("nothing left to undo after A", es.undo())
        uiSync { it.resetView() }
    }

    // 3. Three-finger tap redoes the stroke undone by (2).
    @Test
    fun threeFingerTapRedoesStroke() {
        val es = surf()
        uiSync { it.navigateMode = false; it.resetView() }
        val t0 = SystemClock.uptimeMillis()
        val cx = es.width * 0.5f
        val cy = es.height * 0.5f
        strokeAcross(es, cy, t0, cx)
        Thread.sleep(300)
        var b = pullFull(es)
        assertTrue("stroke must paint ink", countInk(b) > 20)
        b.recycle()
        twoFingerTap(es, cx, cy, t0 + 2000)
        Thread.sleep(300)
        b = pullFull(es)
        assertTrue("two-finger tap must undo the stroke", countInk(b) == 0)
        b.recycle()
        threeFingerTap(es, cx, cy, t0 + 3000)
        Thread.sleep(300)
        b = pullFull(es)
        assertTrue("three-finger tap must redo the stroke", countInk(b) > 20)
        b.recycle()
        assertTrue("redone stroke must be undoable for cleanup", es.undo())
        uiSync { it.resetView() }
    }

    // 4. Two-finger drag pans the view without ink, and a later stroke
    // still lands at the engine center (inverse mapping is sane).
    @Test
    fun twoFingerDragPansWithoutInk() {
        val es = surf()
        uiSync { it.navigateMode = false; it.resetView() }
        val t0 = SystemClock.uptimeMillis()
        val cx = es.width * 0.5f
        val cy = es.height * 0.5f
        val props2 = fingerProps(0, 1)
        dispatchAll(
            es,
            simpleDown(t0, cx, cy),
            multiObtain(
                t0, t0 + 10,
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                props2, fingerCoords(Pair(cx, cy), Pair(cx + 40f, cy))
            ),
            multiObtain(
                t0, t0 + 30, MotionEvent.ACTION_MOVE,
                props2, fingerCoords(Pair(cx + 100f, cy), Pair(cx + 140f, cy))
            ),
            multiObtain(
                t0, t0 + 50, MotionEvent.ACTION_MOVE,
                props2, fingerCoords(Pair(cx + 200f, cy), Pair(cx + 240f, cy))
            ),
            multiObtain(
                t0, t0 + 70,
                MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                props2, fingerCoords(Pair(cx + 200f, cy), Pair(cx + 240f, cy))
            ),
            MotionEvent.obtain(t0, t0 + 90, MotionEvent.ACTION_UP, cx + 200f, cy, 1f, 1f, 0, 1f, 1f, 0, 0)
        )
        Thread.sleep(300)
        var b = pullFull(es)
        assertTrue("two-finger drag must paint no ink", countInk(b) == 0)
        b.recycle()
        val (ox, oy) = es.viewOffset()
        val moved = sqrt((ox * ox + oy * oy).toDouble())
        assertTrue("drag must pan the view, got offset ($ox,$oy)", moved > 50)
        // A later stroke still lands at the engine center.
        strokeAcross(es, cy, t0 + 2000, cx)
        Thread.sleep(300)
        b = pullFull(es)
        assertTrue("stroke after pan must paint ink", countInk(b) > 20)
        b.recycle()
        assertTrue(es.undo())
        uiSync { it.resetView() }
    }

    // 5. A two-finger twist tilts the view without ink; resetView clears
    // the angle. Also captures the loupe screenshot for the report.
    @Test
    fun rotateGestureTiltsWithoutInk() {
        val es = surf()
        uiSync { it.navigateMode = false; it.resetView() }
        val t0 = SystemClock.uptimeMillis()
        val cx = es.width * 0.5f
        val cy = es.height * 0.5f
        val r = 60f
        fun ring(deg: Double): Pair<Pair<Float, Float>, Pair<Float, Float>> {
            val rad = Math.toRadians(deg)
            val dx = (r * Math.cos(rad)).toFloat()
            val dy = (r * Math.sin(rad)).toFloat()
            return Pair(Pair(cx - dx, cy - dy), Pair(cx + dx, cy + dy))
        }
        val props2 = fingerProps(0, 1)
        val (a0, b0) = ring(0.0)
        val (a1, b1) = ring(5.0)
        val (a2, b2) = ring(10.0)
        val (a3, b3) = ring(15.0)
        dispatchAll(
            es,
            MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, a0.first, a0.second, 1f, 1f, 0, 1f, 1f, 0, 0),
            multiObtain(
                t0, t0 + 10,
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                props2, fingerCoords(a0, b0)
            ),
            multiObtain(t0, t0 + 30, MotionEvent.ACTION_MOVE, props2, fingerCoords(a1, b1)),
            multiObtain(t0, t0 + 50, MotionEvent.ACTION_MOVE, props2, fingerCoords(a2, b2)),
            multiObtain(t0, t0 + 70, MotionEvent.ACTION_MOVE, props2, fingerCoords(a3, b3)),
            multiObtain(
                t0, t0 + 90,
                MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                props2, fingerCoords(a3, b3)
            ),
            MotionEvent.obtain(t0, t0 + 110, MotionEvent.ACTION_UP, a3.first, a3.second, 1f, 1f, 0, 1f, 1f, 0, 0)
        )
        Thread.sleep(300)
        val b = pullFull(es)
        assertTrue("rotation must paint no ink", countInk(b) == 0)
        b.recycle()
        val ang = es.viewAngle()
        assertTrue("view angle must be ~15 deg, got $ang", ang > 13f && ang < 17f)
        // Loupe screenshot for the slice report (best effort, never fails
        // the gate): the loupe is still visible < 800 ms after the twist.
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val shot = InstrumentationRegistry.getInstrumentation()
                    .uiAutomation.takeScreenshot()
                if (shot != null) {
                    val dst = File(composeRule.activity.cacheDir, "slice1-loupe.png")
                    FileOutputStream(dst).use { o ->
                        shot.compress(Bitmap.CompressFormat.PNG, 100, o)
                    }
                    if (!shot.isRecycled) shot.recycle()
                }
            }
        } catch (_: Exception) {
        }
        uiSync { it.resetView() }
        assertEquals("resetView must clear the angle", 0f, es.viewAngle())
    }

    // 6. In navigate mode 1 finger pans (no ink) but the stylus still
    // draws; in draw mode 1 finger draws (non-regression).
    @Test
    fun oneFingerDrawsInBothModes() {
        val es = surf()
        uiSync { it.navigateMode = false; it.resetView() }
        val t0 = SystemClock.uptimeMillis()
        val cx = es.width * 0.5f
        val cy = es.height * 0.5f
        strokeAcross(es, cy, t0, cx)
        Thread.sleep(300)
        var b = pullFull(es)
        assertTrue("draw mode: 1 finger must draw", countInk(b) > 20)
        b.recycle()
        assertTrue(es.undo())
        // Navigate mode: 1-finger drag pans, paints nothing.
        uiSync { it.navigateMode = true }
        dispatchAll(
            es,
            simpleDown(t0 + 2000, cx, cy),
            MotionEvent.obtain(t0 + 2000, t0 + 2016, MotionEvent.ACTION_MOVE, cx + 50f, cy, 1f, 1f, 0, 1f, 1f, 0, 0),
            MotionEvent.obtain(t0 + 2000, t0 + 2032, MotionEvent.ACTION_MOVE, cx + 100f, cy, 1f, 1f, 0, 1f, 1f, 0, 0),
            MotionEvent.obtain(t0 + 2000, t0 + 2048, MotionEvent.ACTION_UP, cx + 100f, cy, 1f, 1f, 0, 1f, 1f, 0, 0)
        )
        Thread.sleep(300)
        b = pullFull(es)
        assertTrue("navigate mode: 1 finger must paint no ink", countInk(b) == 0)
        b.recycle()
        val (ox, oy) = es.viewOffset()
        assertTrue("navigate mode: 1 finger must pan", sqrt((ox * ox + oy * oy).toDouble()) > 20)
        // Stylus still draws in navigate mode.
        val sprops = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_STYLUS
            }
        )
        fun scoords(x: Float, y: Float) = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 0.8f
                size = 1f
            }
        )
        dispatchAll(
            es,
            multiObtain(t0 + 3000, t0 + 3000, MotionEvent.ACTION_DOWN, sprops, scoords(cx - 80f, cy)),
            multiObtain(t0 + 3000, t0 + 3016, MotionEvent.ACTION_MOVE, sprops, scoords(cx, cy)),
            multiObtain(t0 + 3000, t0 + 3032, MotionEvent.ACTION_MOVE, sprops, scoords(cx + 80f, cy)),
            multiObtain(t0 + 3000, t0 + 3048, MotionEvent.ACTION_UP, sprops, scoords(cx + 80f, cy))
        )
        Thread.sleep(300)
        b = pullFull(es)
        assertTrue("stylus must draw even in navigate mode", countInk(b) > 20)
        b.recycle()
        assertTrue(es.undo())
        uiSync { it.navigateMode = false; it.resetView() }
    }

    // 7. A swipe from the right edge reopens the dock without ink.
    @Test
    fun edgeSwipeReopensDockWithoutInk() {
        val es = surf()
        uiSync { it.navigateMode = false; it.resetView() }
        var fired = -1
        uiSync { it.onEdgeSwipe = { edge -> fired = edge } }
        val t0 = SystemClock.uptimeMillis()
        val x0 = es.width - 5f
        val cy = es.height * 0.5f
        dispatchAll(
            es,
            simpleDown(t0, x0, cy),
            MotionEvent.obtain(t0, t0 + 30, MotionEvent.ACTION_MOVE, x0 - 80f, cy, 1f, 1f, 0, 1f, 1f, 0, 0),
            MotionEvent.obtain(t0, t0 + 60, MotionEvent.ACTION_MOVE, x0 - 200f, cy, 1f, 1f, 0, 1f, 1f, 0, 0),
            MotionEvent.obtain(t0, t0 + 90, MotionEvent.ACTION_UP, x0 - 200f, cy, 1f, 1f, 0, 1f, 1f, 0, 0)
        )
        Thread.sleep(300)
        assertEquals("right-edge swipe must fire EDGE_RIGHT", EngineSurface.EDGE_RIGHT, fired)
        val b = pullFull(es)
        assertTrue("edge swipe must paint no ink", countInk(b) == 0)
        b.recycle()
        uiSync { it.onEdgeSwipe = null; it.resetView() }
    }

    // ---- Slice 2 pro color (spec docs/slice2-color.md §7) ----
    // Color = engine pixels: every chain is proven through real strokes or
    // pullFrameFull, never Compose state alone. Each test resets inkHex to
    // #141414 + active set Base in setup and teardown.

    private fun slice2Store() = composeRule.activity.colorStore

    private fun resetColorState() {
        composeRule.activityRule.scenario.onActivity { act ->
            act.colorStore.disarmPipette()
            act.colorStore.selectSet("Base")
            act.colorStore.pick("#141414")
        }
        uiSync {
            it.navigateMode = false
            it.resetView()
            it.pipetteArmed = false
            it.pipetteLoupe = false
        }
    }

    private fun ensureDockOpen() {
        composeRule.waitForIdle()
        val shown = try {
            composeRule.onNodeWithTag("color-panel")
                .assertIsDisplayed()
            true
        } catch (_: AssertionError) {
            false
        }
        if (!shown) {
            // Either folded (⟨ visible) or open-but-scrolled (⟩ visible,
            // e.g. a restored workspace opened it). Open if needed, then
            // scroll the panel into view (slice 6 chrome shrank the
            // central row, so it can sit below the fold on phones).
            val folded = try {
                composeRule.onNodeWithText("⟨").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
            if (folded) {
                composeRule.onNodeWithText("⟨").performClick()
                composeRule.waitForIdle()
            }
            composeRule.onNodeWithTag("color-panel").performScrollTo()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("color-panel")
                .assertIsDisplayed()
        }
    }

    // 1. Sets tab pick drives the engine: red swatch -> 1-finger stroke ->
    // composite carries R-dominant pixels. Also captures the slice report
    // screenshot (wheel tab visible, pipette + sets chips in frame).
    @Test
    fun wheelDrivesEngine() {
        val es = surf()
        awaitSurface(es)
        resetColorState()
        try {
            ensureDockOpen()
            composeRule.onNodeWithTag("color-tab-wheel")
                .performClick()
            composeRule.waitForIdle()
            // Scroll the bottom bar into view: dock viewport then shows
            // tabs + wheel + pastille/hex/pipette in a single shot.
            composeRule.onNodeWithTag("pipette-btn")
                .performScrollTo()
            composeRule.waitForIdle()
            try {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    val shot = InstrumentationRegistry.getInstrumentation()
                        .uiAutomation.takeScreenshot()
                    if (shot != null) {
                        val dst = File(composeRule.activity.cacheDir, "slice2-color.png")
                        FileOutputStream(dst).use { o ->
                            shot.compress(Bitmap.CompressFormat.PNG, 100, o)
                        }
                        if (!shot.isRecycled) shot.recycle()
                    }
                }
            } catch (_: Exception) {
            }
            composeRule.onNodeWithTag("color-tab-sets")
                .performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("set-chip-0")
                .performClick()
            composeRule.onNodeWithTag("swatch-2")
                .performClick()
            composeRule.waitForIdle()
            assertEquals("#E53935", slice2Store().inkHex)
            val t0 = SystemClock.uptimeMillis()
            val cx = es.width * 0.5f
            val cy = es.height * 0.5f
            strokeAcross(es, cy, t0, cx)
            Thread.sleep(400)
            val b = pullFull(es)
            var red = 0
            var y = 0
            while (y < b.height) {
                var x = 0
                while (x < b.width) {
                    val px = b.getPixel(x, y)
                    val r = (px shr 16) and 0xFF
                    val g = (px shr 8) and 0xFF
                    val bl = px and 0xFF
                    if (r > g + 40 && r > bl + 40) red++
                    x += 4
                }
                y += 4
            }
            b.recycle()
            assertTrue(
                "red stroke must paint R-dominant pixels, got $red",
                red >= 10
            )
            assertTrue(es.undo())
        } finally {
            resetColorState()
            try {
                es.undo()
            } catch (_: Exception) {
            }
        }
    }

    // 2. Sets persist across an activity restart; corrupt JSON falls back
    // to Base without crashing.
    @Test
    fun recentsSurviveRestart() {
        resetColorState()
        try {
            composeRule.activityRule.scenario.onActivity { act ->
                val s = act.colorStore
                s.newSet("Test")
                s.selectSet("Test")
                s.pick("#FF0000")
                s.addCurrentToActive()
                s.pick("#00FF00")
                s.addCurrentToActive()
            }
            Thread.sleep(900)
            // Synchronous flush: the debounce write above proves the timer
            // path (file exists); flush nails the content before recreate so
            // a wedged main looper cannot lose the last edit.
            composeRule.activityRule.scenario.onActivity { act ->
                act.colorStore.flush()
            }
            val f = File(composeRule.activity.filesDir, "color-sets.json")
            assertTrue("color-sets.json must be persisted", f.exists())
            composeRule.activityRule.scenario.recreate()
            composeRule.waitForIdle()
            var okSets = false
            var okActive = false
            composeRule.activityRule.scenario.onActivity { act ->
                val s = act.colorStore
                okSets = s.sets.any {
                    it.name == "Test" && it.colors == listOf("#FF0000", "#00FF00")
                }
                okActive = s.activeSet == "Test"
            }
            assertTrue("set Test must survive restart with 2 colors", okSets)
            assertTrue("active set must survive restart", okActive)
            composeRule.activityRule.scenario.onActivity { act ->
                File(act.filesDir, "color-sets.json").writeText("{{{")
            }
            composeRule.activityRule.scenario.recreate()
            composeRule.waitForIdle()
            var okBase = false
            composeRule.activityRule.scenario.onActivity { act ->
                val s = act.colorStore
                okBase = s.activeSet == "Base" &&
                    s.sets.any { it.name == "Base" && it.colors.size == 10 }
            }
            assertTrue("corrupt JSON must fall back to Base", okBase)
        } finally {
            composeRule.activityRule.scenario.onActivity { act ->
                act.colorStore.deleteSet("Test")
                act.colorStore.selectSet("Base")
                act.colorStore.pick("#141414")
                act.colorStore.flush()
            }
            Thread.sleep(700)
        }
    }

    // 3. Pipette samples the drawn red stroke at the canvas center (±8 LSB
    // per channel); a tap on the checker background misses and keeps ink.
    @Test
    fun pipetteSamplesRightColor() {
        val es = surf()
        awaitSurface(es)
        resetColorState()
        try {
            uiSync { it.navigateMode = false; it.resetView() }
            composeRule.activityRule.scenario.onActivity { act ->
                act.colorStore.pick("#FF0000")
            }
            composeRule.waitForIdle()
            Thread.sleep(400)
            val t0 = SystemClock.uptimeMillis()
            val cx = es.width * 0.5f
            val cy = es.height * 0.5f
            strokeAcross(es, cy, t0, cx)
            Thread.sleep(400)
            // Stage gate: the stroke itself must be red at the center before
            // testing the pipette (separates brush/stroke faults from
            // sampling faults).
            var pre = pullFull(es)
            val preRed = countRedDom(pre)
            pre.recycle()
            assertTrue(
                "stroke must paint red before pipette test, got $preRed",
                preRed >= 10
            )
            ensureDockOpen()
            // BottomBar sits below the fold (LayerPanel + wheel above it):
            // scroll it into view like a real user before tapping. Re-read
            // the canvas geometry: opening the dock resized the view.
            composeRule.onNodeWithTag("pipette-btn")
                .performScrollTo()
            composeRule.waitForIdle()
            val tx = es.width * 0.5f
            val ty = es.height * 0.5f
            composeRule.onNodeWithTag("pipette-btn")
                .performClick()
            composeRule.waitForIdle()
            assertTrue(slice2Store().pipetteArmed)
            dispatchAll(
                es,
                simpleDown(t0 + 5000, tx, ty),
                MotionEvent.obtain(
                    t0 + 5000, t0 + 5016, MotionEvent.ACTION_UP,
                    tx, ty, 1f, 1f, 0, 1f, 1f, 0, 0
                )
            )
            Thread.sleep(900)
            val picked = slice2Store().inkHex
            val b = pullFull(es)
            val center = b.getPixel(b.width / 2, b.height / 2)
            b.recycle()
            fun chan(v: Int, shift: Int) = (v shr shift) and 0xFF
            val pr = chan(android.graphics.Color.parseColor(picked), 16)
            val pg = chan(android.graphics.Color.parseColor(picked), 8)
            val pb = chan(android.graphics.Color.parseColor(picked), 0)
            assertTrue(
                "pipette $picked must match engine center (±8 LSB)",
                kotlin.math.abs(pr - chan(center, 16)) <= 8 &&
                    kotlin.math.abs(pg - chan(center, 8)) <= 8 &&
                    kotlin.math.abs(pb - chan(center, 0)) <= 8
            )
            // Miss on the checker background: ink unchanged, disarmed.
            composeRule.onNodeWithTag("pipette-btn")
                .performScrollTo()
            composeRule.onNodeWithTag("pipette-btn")
                .performClick()
            composeRule.waitForIdle()
            assertTrue(slice2Store().pipetteArmed)
            dispatchAll(
                es,
                simpleDown(t0 + 9000, 8f, 8f),
                MotionEvent.obtain(
                    t0 + 9000, t0 + 9016, MotionEvent.ACTION_UP,
                    8f, 8f, 1f, 1f, 0, 1f, 1f, 0, 0
                )
            )
            Thread.sleep(900)
            assertEquals("miss must not change ink", picked, slice2Store().inkHex)
            assertFalse(slice2Store().pipetteArmed)
            assertTrue(es.undo())
        } finally {
            resetColorState()
            try {
                es.undo()
            } catch (_: Exception) {
            }
        }
    }

    // 4. Sliders tab: pick updates the hex readout; typed hex updates the
    // store; invalid hex keeps the color and raises the status message.
    @Test
    fun slidersAndHexAgree() {
        resetColorState()
        try {
            ensureDockOpen()
            composeRule.onNodeWithTag("color-tab-sliders")
                .performClick()
            composeRule.waitForIdle()
            composeRule.activityRule.scenario.onActivity { act ->
                act.colorStore.pick("#00FF00")
            }
            composeRule.waitForIdle()
            composeRule.onNode(
                hasTestTag("color-hex") and hasText("#00FF00")
            ).performScrollTo()
            composeRule.onNode(
                hasTestTag("color-hex") and hasText("#00FF00")
            ).assertIsDisplayed()
            assertEquals("#00FF00", slice2Store().inkHex)
            composeRule.onNodeWithTag("hex-field")
                .performClick()
            composeRule.onNodeWithTag("hex-field")
                .performTextClearance()
            composeRule.onNodeWithTag("hex-field")
                .performTextInput("0000FF")
            composeRule.waitForIdle()
            Thread.sleep(300)
            assertEquals("#0000FF", slice2Store().inkHex)
            composeRule.onNodeWithTag("hex-field")
                .performTextClearance()
            composeRule.onNodeWithTag("hex-field")
                .performTextInput("ZZZZZZ")
            composeRule.onNodeWithTag("hex-field")
                .performImeAction()
            composeRule.waitForIdle()
            Thread.sleep(300)
            assertEquals("#0000FF", slice2Store().inkHex)
            var expected = ""
            composeRule.activityRule.scenario.onActivity { act ->
                expected = act.getString(R.string.color_hex_invalid, "#0000FF")
            }
            composeRule.onNodeWithText(expected)
                .assertIsDisplayed()
        } finally {
            resetColorState()
        }
    }

    // 5. Every color touch target measures >= 48dp high (0.5dp tolerance).
    @Test
    fun touchTargets48dp() {
        resetColorState()
        try {
            ensureDockOpen()
            // Touch-target size is the node's OWN size (unclipped): targets
            // below the dock fold still measure full height.
            fun tall(tag: String) {
                composeRule.waitForIdle()
                val u = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
                val h = u.bottom - u.top
                assertTrue(
                    "$tag must be >= 48dp, got $h",
                    h >= (48.dp - 0.5.dp)
                )
            }
            listOf(
                "color-tab-wheel", "color-tab-sliders", "color-tab-sets",
                "color-tab-recent", "pipette-btn", "color-current"
            ).forEach { tall(it) }
            composeRule.onNodeWithTag("color-tab-sliders")
                .performClick()
            composeRule.waitForIdle()
            listOf(
                "slider-hue", "slider-sat", "slider-val2", "slider-red",
                "slider-green", "slider-blue", "hex-field"
            ).forEach { tall(it) }
            composeRule.onNodeWithTag("color-tab-sets")
                .performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("set-chip-0")
                .performClick()
            tall("swatch-0")
            tall("set-add")
            tall("set-new")
            composeRule.onNodeWithTag("color-tab-recent")
                .performClick()
            composeRule.waitForIdle()
            tall("recent-clear")
        } finally {
            resetColorState()
        }
    }

    // 6. Recents fill on finished strokes only: slider picks alone stay out;
    // repeats dedupe and bubble to the front.
    @Test
    fun recentsFillOnStrokeOnly() {
        val es = surf()
        awaitSurface(es)
        resetColorState()
        try {
            uiSync { it.navigateMode = false; it.resetView() }
            composeRule.activityRule.scenario.onActivity { act ->
                val s = act.colorStore
                s.clearRecents()
                s.pick("#FF0000")
            }
            Thread.sleep(700)
            assertTrue(
                "slider-only pick must not fill recents",
                slice2Store().recents.isEmpty()
            )
            val t0 = SystemClock.uptimeMillis()
            val cx = es.width * 0.5f
            val cy = es.height * 0.5f
            strokeAcross(es, cy, t0, cx)
            Thread.sleep(500)
            assertEquals(listOf("#FF0000"), slice2Store().recents)
            composeRule.activityRule.scenario.onActivity { act ->
                act.colorStore.pick("#0000FF")
            }
            Thread.sleep(300)
            strokeAcross(es, cy + 60f, t0 + 2000, cx)
            Thread.sleep(500)
            assertEquals(listOf("#0000FF", "#FF0000"), slice2Store().recents)
            composeRule.activityRule.scenario.onActivity { act ->
                act.colorStore.pick("#FF0000")
            }
            Thread.sleep(300)
            strokeAcross(es, cy + 120f, t0 + 4000, cx)
            Thread.sleep(500)
            assertEquals(listOf("#FF0000", "#0000FF"), slice2Store().recents)
            assertTrue(es.undo())
            assertTrue(es.undo())
            assertTrue(es.undo())
        } finally {
            resetColorState()
            composeRule.activityRule.scenario.onActivity { act ->
                act.colorStore.clearRecents()
            }
            try {
                es.undo()
            } catch (_: Exception) {
            }
        }
    }

    // ---- Slice 3 pro layers (spec docs/slice3-layers.md §8) ----
    // Thumbnails prove the app-side solo-render pipeline (no new JNI);
    // every other chain ends in engine pixels or nLayerState, never
    // Compose state alone. Each test leaves the doc dirty-tolerant:
    // layers can only be added (no JNI remove), so tests are
    // index-agnostic except where they create their own fixtures.

    private fun slice3Prefs() = composeRule.activity.brushPrefs

    private fun resetSlice3() {
        composeRule.activityRule.scenario.onActivity { act ->
            val p = act.brushPrefs
            p.updateRadius(10f)
            p.updateOpaque(1f)
            p.updateStabilize(0.15f)
            p.flush()
        }
        uiSync {
            it.navigateMode = false
            it.resetView()
        }
    }

    private fun dockTop() {
        // The dock column keeps its scroll offset across tests: rewind it
        // so panel headers are visible before interacting.
        try {
            composeRule.onNodeWithTag("dock-scroll")
                .performTouchInput { swipeDown() }
        } catch (_: Exception) {
        }
        composeRule.waitForIdle()
    }

    private fun <T> onUi(op: (EngineSurface) -> T): T {
        var out: T? = null
        composeRule.activityRule.scenario.onActivity { act ->
            out = op(findActiveEngineSurface(act.window.decorView)!!)
        }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private fun layerNames(es: EngineSurface): List<String> {
        val names = mutableListOf<String>()
        try {
            val arr = org.json.JSONObject(es.layerState()).optJSONArray("layers")
                ?: return names
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val s = e.optJSONObject("self") ?: e
                names.add(s.optString("name", "Layer"))
            }
        } catch (_: Exception) {
        }
        return names
    }

    private fun activeIndex(es: EngineSurface): Int = try {
        org.json.JSONObject(es.layerState()).optInt("active", 0)
    } catch (_: Exception) {
        0
    }

    private fun domCount(b: Bitmap, wantR: Boolean): Int {
        var n = 0
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val px = b.getPixel(x, y)
                if ((px ushr 24) > 10) {
                    val r = (px shr 16) and 0xFF
                    val g = (px shr 8) and 0xFF
                    val bl = px and 0xFF
                    if (wantR) {
                        if (r > g + 40 && r > bl + 40) n++
                    } else {
                        if (bl > r + 40 && bl > g + 40) n++
                    }
                }
                x += 2
            }
            y += 2
        }
        return n
    }

    // Short centered passes: with the dock open the canvas is narrow, so
    // wide strokes would arm the 32dp edge detector and paint nothing.
    // ±40px around the center stays clear of every edge band.
    private fun bigStroke(
        es: EngineSurface, cx: Float, cy: Float, t0: Long, yOff: Float = 0f
    ) {
        for (dy in longArrayOf(-90, -30, 30, 90)) {
            dispatchAll(
                es,
                simpleDown(t0 + dy, cx - 40f, cy + yOff + dy),
                MotionEvent.obtain(
                    t0 + dy, t0 + dy + 16, MotionEvent.ACTION_MOVE,
                    cx, cy + yOff + dy, 1f, 1f, 0, 1f, 1f, 0, 0
                ),
                MotionEvent.obtain(
                    t0 + dy, t0 + dy + 32, MotionEvent.ACTION_MOVE,
                    cx + 40f, cy + yOff + dy, 1f, 1f, 0, 1f, 1f, 0, 0
                ),
                MotionEvent.obtain(
                    t0 + dy, t0 + dy + 48, MotionEvent.ACTION_UP,
                    cx + 40f, cy + yOff + dy, 1f, 1f, 0, 1f, 1f, 0, 0
                )
            )
        }
    }

    /** Stage gate: the full composite must carry red before solo asserts. */
    private fun assertRedStage(es: EngineSurface) {
        val b = pullFull(es)
        val red = countRedDom(b)
        var ink = 0
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                if (b.getPixel(x, y) != -1) ink++
                x += 8
            }
            y += 8
        }
        b.recycle()
        assertTrue(
            "stage gate: stroke must paint red (ink=$ink red=$red " +
                "layers=${layerNames(es)} active=${activeIndex(es)})",
            red >= 10
        )
    }

    // 1. Solo-render thumbnails without new JNI: red on layer 0, blue on
    // layer 1; each solo composite is dominant in its own channel; hiding
    // layer 1 yields the placeholder (null), proving invalidation.
    @Test
    fun layerThumbsSoloRender() {
        val es = surf()
        awaitSurface(es)
        resetSlice3()
        try {
            ensureDockOpen()
            onUi {
                it.setBrush(
                    """{"radius":64,"hardness":1.0,"opaque":1.0,"spacing":0.15,"color":"#FF0000","stabilize":0.0,"curve":0.0,"mode":"brush"}"""
                )
                it.setActiveLayer(0)
            }
            val t0 = SystemClock.uptimeMillis()
            val cx = es.width * 0.5f
            val cy = es.height * 0.5f
            // Red high, blue low: opaque top layers would otherwise hide
            // the color under test in the full composite.
            bigStroke(es, cx, cy, t0, -140f)
            Thread.sleep(400)
            val fresh = onUi {
                val idx = it.addLayerNamed("Blue")
                it.setActiveLayer(idx)
                it.setBrush(
                    """{"radius":64,"hardness":1.0,"opaque":1.0,"spacing":0.15,"color":"#0000FF","stabilize":0.0,"curve":0.0,"mode":"brush"}"""
                )
                idx
            }
            bigStroke(es, cx, cy, t0 + 5000, 140f)
            Thread.sleep(400)
            assertRedStage(es)
            // Refresh the panel so the debounced worker picks up both
            // layers, then let it solo-render (poll: emulator timing).
            dockTop()
            composeRule.onNodeWithTag("layer-thumb-0").performClick()
            composeRule.waitForIdle()
            var tries = 0
            while (LayerPanelTestHooks.thumbSnapshot().isEmpty() && tries < 10) {
                Thread.sleep(500)
                tries++
            }
            assertTrue(
                "thumb worker must populate the cache " +
                    "(${LayerPanelTestHooks.lastInfo})",
                LayerPanelTestHooks.thumbSnapshot().isNotEmpty()
            )
            val b0 = onUi { it.soloRenderLayer(0) }
            assertNotNull("solo render of layer 0 must succeed", b0)
            assertTrue(
                "layer 0 thumb must be R-dominant, got ${domCount(b0!!, true)}",
                domCount(b0, true) >= 20
            )
            b0.recycle()
            val b1 = onUi { it.soloRenderLayer(fresh) }
            assertNotNull("solo render of layer 1 must succeed", b1)
            assertTrue(
                "layer 1 thumb must be B-dominant, got ${domCount(b1!!, false)}",
                domCount(b1, false) >= 20
            )
            b1.recycle()
            // Hide layer 1 through the UI toggle: placeholder, not blue.
            composeRule.onNodeWithTag("layer-thumb-$fresh")
                .performScrollTo()
            composeRule.onNodeWithTag("layer-vis-$fresh").performClick()
            composeRule.waitForIdle()
            Thread.sleep(1000)
            assertTrue(
                "hidden layer must solo-render to null (placeholder)",
                onUi { it.soloRenderLayer(fresh) } == null
            )
            onUi { it.setLayerVisibility(fresh, true) }
        } finally {
            resetSlice3()
            repeat(20) {
                try {
                    if (!es.undo()) return@repeat
                } catch (_: Exception) {
                }
            }
        }
    }

    // 2. DnD drop = exactly one engine moveLayer (hook used by the drop
    // handler), then smoke the ^/v menu alternative.
    @Test
    fun layerDragReorderMovesOnce() {
        val es = surf()
        awaitSurface(es)
        resetSlice3()
        try {
            ensureDockOpen()
            dockTop()
            // Self-sufficient: add two fresh layers, operate on the tail.
            val base = layerNames(es).size
            onUi {
                it.addLayerNamed("AAA")
                it.addLayerNamed("BBB")
            }
            val n = layerNames(es).size
            assertTrue("need at least 2 layers", n >= 2)
            // Refresh the panel past the just-added layers so the drop
            // guard sees the real size.
            dockTop()
            composeRule.onNodeWithTag("layer-thumb-0").performClick()
            composeRule.waitForIdle()
            val from = n - 2
            val to = n - 1
            val hook = LayerPanelTestHooks.moveOnce
            assertNotNull("drop hook must be installed", hook)
            onUi { it.moveCallCount = 0 }
            val before = layerNames(es)
            hook!!(from, to)
            composeRule.waitForIdle()
            assertEquals("drop must cost exactly one moveLayer", 1, es.moveCallCount)
            val after = layerNames(es)
            val expected = before.toMutableList().also {
                val m = it.removeAt(from)
                it.add(to, m)
            }
            assertEquals("order must reflect the single move", expected, after)
            // Smoke: ^/v from the long-press menu (non-tactile path).
            onUi { it.moveCallCount = 0 }
            composeRule.onNodeWithTag("layer-name-$to").performScrollTo()
            composeRule.waitForIdle()
            // Long-press via semantics (deterministic): opens the same menu
            // as a touch long-press (touch injection is flaky here).
            composeRule.onNodeWithTag("layer-name-$to")
                .performSemanticsAction(SemanticsActions.OnLongClick)
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("layer-down-$to").performClick()
            composeRule.waitForIdle()
            assertEquals("menu v must cost exactly one moveLayer", 1, es.moveCallCount)
            assertEquals(
                "menu v must move the layer back down",
                before,
                layerNames(es)
            )
            assertTrue(base >= 0)
        } finally {
            repeat(4) {
                try {
                    es.undo()
                } catch (_: Exception) {
                }
            }
            resetSlice3()
        }
    }

    // 3. Searchable blend dialog: filter, apply, engine proves it.
    @Test
    fun blendSearchableApplies() {
        val es = surf()
        awaitSurface(es)
        resetSlice3()
        try {
            ensureDockOpen()
            onUi {
                it.setBrush(
                    """{"radius":64,"hardness":1.0,"opaque":1.0,"spacing":0.15,"color":"#808080","stabilize":0.0,"curve":0.0,"mode":"brush"}"""
                )
                it.setActiveLayer(0)
            }
            val t0 = SystemClock.uptimeMillis()
            bigStroke(es, es.width * 0.5f, es.height * 0.5f, t0)
            Thread.sleep(400)
            val idx = onUi {
                val i = it.addLayerNamed("BlendTop")
                it.setActiveLayer(i)
                it.setBrush(
                    """{"radius":64,"hardness":1.0,"opaque":1.0,"spacing":0.15,"color":"#FF0000","stabilize":0.0,"curve":0.0,"mode":"brush"}"""
                )
                i
            }
            bigStroke(es, es.width * 0.5f, es.height * 0.5f, t0 + 5000)
            Thread.sleep(400)
            val before = pullFull(es)
            // The panel only learns the new layer through a UI refresh.
            dockTop()
            composeRule.onNodeWithTag("layer-thumb-0").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("layer-blend-$idx").performScrollTo()
            composeRule.onNodeWithTag("layer-blend-$idx").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("blend-search").performTextInput("mult")
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("blend-item-multiply").assertIsDisplayed()
            composeRule.onNodeWithTag("blend-item-screen").assertDoesNotExist()
            composeRule.onNodeWithTag("blend-item-multiply").performClick()
            composeRule.waitForIdle()
            Thread.sleep(600)
            val blend = try {
                val arr = org.json.JSONObject(es.layerState()).optJSONArray("layers")
                val e = arr!!.optJSONObject(idx)
                (e.optJSONObject("self") ?: e).optString("blend", "")
            } catch (_: Exception) {
                ""
            }
            assertEquals("engine must hold the picked blend", "svg:multiply", blend)
            val after = pullFull(es)
            var diff = 0
            for (y in 0 until after.height step 8) {
                for (x in 0 until after.width step 8) {
                    if (after.getPixel(x, y) != before.getPixel(x, y)) diff++
                }
            }
            before.recycle()
            after.recycle()
            assertTrue("multiply must change the composite, got $diff", diff > 0)
            onUi { it.setLayerBlend(idx, "svg:normal") }
        } finally {
            resetSlice3()
            repeat(20) {
                try {
                    if (!es.undo()) return@repeat
                } catch (_: Exception) {
                }
            }
        }
    }

    // 4. Pinned bar persists across restart, mirrors ToolPalette, and the
    // radius actually drives the stroke width (relative proof).
    @Test
    fun pinnedBarPersistsAndMirrors() {
        val es = surf()
        awaitSurface(es)
        resetSlice3()
        try {
            ensureDockOpen()
            composeRule.activityRule.scenario.onActivity { act ->
                val p = act.brushPrefs
                p.updateRadius(24f)
                p.updateOpaque(0.5f)
                p.flush()
                act.brushPrefs.bindTool("#141414", false, 0f)
            }
            Thread.sleep(800)
            val f = File(composeRule.activity.filesDir, "brush-defaults.json")
            assertTrue("brush-defaults.json must be persisted", f.exists())
            assertTrue(
                "radius 24 must be persisted, got ${f.readText()}",
                f.readText().contains("24")
            )
            composeRule.activityRule.scenario.recreate()
            composeRule.waitForIdle()
            Thread.sleep(500)
            composeRule.onNodeWithTag("pinned-bar").assertIsDisplayed()
            // Mirror: the hoisted 24px value shows in the pinned bar and
            // in ToolPalette (same BrushPrefs, single push path).
            composeRule.onAllNodes(hasText("24px", substring = true))
                .assertCountEquals(2)
            // Relative width proof on engine pixels: 64px opaque stroke
            // covers more than a 10px one over the same path. NOTE: the
            // activity (and its EngineSurface) was recreated above, so
            // re-acquire the live surface — the old handle is retired.
            val live = surf()
            awaitSurface(live)
            val t0 = SystemClock.uptimeMillis()
            val cx = live.width * 0.5f
            val cy = live.height * 0.5f
            onUi {
                it.setBrush(
                    """{"radius":64,"hardness":1.0,"opaque":1.0,"spacing":0.15,"color":"#141414","stabilize":0.0,"curve":0.0,"mode":"brush"}"""
                )
            }
            strokeAcross(live, cy, t0, cx)
            Thread.sleep(300)
            var wide = pullFull(live)
            val wideN = countInk(wide)
            wide.recycle()
            assertTrue(live.undo())
            onUi {
                it.setBrush(
                    """{"radius":10,"hardness":1.0,"opaque":1.0,"spacing":0.15,"color":"#141414","stabilize":0.0,"curve":0.0,"mode":"brush"}"""
                )
            }
            strokeAcross(live, cy, t0 + 5000, cx)
            Thread.sleep(300)
            var narrow = pullFull(live)
            val narrowN = countInk(narrow)
            narrow.recycle()
            assertTrue("wide stroke must cover more ink ($wideN vs $narrowN)", wideN > narrowN)
            assertTrue(live.undo())
        } finally {
            resetSlice3()
            try {
                surf().undo()
            } catch (_: Exception) {
            }
        }
    }

    // 5. Every layer touch target measures >= 48dp (0.5dp tolerance) on
    // the current form factor.
    @Test
    fun touchTargets48dpLayers() {
        val es = surf()
        awaitSurface(es)
        resetSlice3()
        try {
            ensureDockOpen()
            dockTop()
            composeRule.waitForIdle()
            val act = activeIndex(es)
            fun tall(tag: String) {
                composeRule.waitForIdle()
                val u = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
                val h = u.bottom - u.top
                assertTrue(
                    "$tag must be >= 48dp, got $h",
                    h >= (48.dp - 0.5.dp)
                )
            }
            // Rank 2 of the ACTIVE layer is always unfolded (§6).
            composeRule.onNodeWithTag("layer-row-$act").performScrollTo()
            listOf(
                "layer-thumb-$act", "layer-vis-$act", "layer-drag-$act",
                "layer-blend-$act", "layer-opacity-$act", "layer-search",
                "layer-add", "pinned-bar", "pinned-size", "pinned-opaque",
                "pinned-stab"
            ).forEach { tall(it) }
        } finally {
            resetSlice3()
        }
    }

    // 6. 50 layers stay readable: compact mode, counter, name search.
    @Test
    fun fiftyLayersStayReadable() {
        val es = surf()
        awaitSurface(es)
        resetSlice3()
        try {
            ensureDockOpen()
            dockTop()
            val start = layerNames(es).size
            onUi {
                for (i in start until 50) it.addLayerNamed("Layer $i")
            }
            // Refresh the panel, then prove the counter.
            composeRule.onNodeWithTag("layer-thumb-0").performClick()
            composeRule.waitForIdle()
            Thread.sleep(800)
            composeRule.onNodeWithTag("layer-count").performScrollTo()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("layer-count").assertIsDisplayed()
            val total = layerNames(es).size
            assertTrue("need 50 layers, got $total", total >= 50)
            composeRule.onNode(hasText("$total/50", substring = true))
                .assertIsDisplayed()
            // Compact: a non-active row folds its rank 2.
            val act = activeIndex(es)
            val folded = if (act != 0) 0 else 1
            composeRule.onNodeWithTag("layer-row-$folded").performScrollTo()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("layer-row-$folded").assertIsDisplayed()
            composeRule.onNodeWithTag("layer-blend-$folded").assertDoesNotExist()
            // The active row keeps its controls.
            composeRule.onNodeWithTag("layer-row-$act").performScrollTo()
            composeRule.onNodeWithTag("layer-blend-$act").assertIsDisplayed()
            // Name search filters (unique match "Layer 49": substring proof,
            // single row, always composed as the first lazy item).
            composeRule.onNodeWithTag("layer-search").performScrollTo()
            composeRule.onNodeWithTag("layer-search").performClick()
            composeRule.onNodeWithTag("layer-search").performTextInput("Layer 49")
            composeRule.waitForIdle()
            Thread.sleep(500)
            composeRule.onNodeWithTag("layer-row-49").assertExists()
            composeRule.onNodeWithTag("layer-row-49").assertIsDisplayed()
            composeRule.onNodeWithTag("layer-search").performTextClearance()
            composeRule.waitForIdle()
        } finally {
            // Best effort: added layers are undoable one by one.
            repeat(60) {
                try {
                    if (!es.undo()) return@repeat
                } catch (_: Exception) {
                    return@repeat
                }
            }
            resetSlice3()
        }
    }

    // 7. Clip/mask/fx badges with the right content descriptions; tapping
    // the mask badge drives maskTarget in the engine.
    @Test
    fun clipMaskBadges() {
        val es = surf()
        awaitSurface(es)
        resetSlice3()
        try {
            ensureDockOpen()
            dockTop()
            onUi {
                it.setLayerClipped(0, true)
                it.addLayerMask(0)
                it.maskTarget(0, true)
                it.setLayerAdjustment(0, """{"type":"brightness","amount":0.5}""")
            }
            var clipDesc = ""
            var maskDesc = ""
            var fxDesc = ""
            composeRule.activityRule.scenario.onActivity { act ->
                clipDesc = act.getString(R.string.layer_clipped_on)
                maskDesc = act.getString(R.string.layer_mask_editing)
                fxDesc = act.getString(R.string.layer_fx_present)
            }
            // Refresh the panel through the UI, then prove the badges.
            composeRule.onNodeWithTag("layer-thumb-0").performClick()
            composeRule.waitForIdle()
            Thread.sleep(800)
            composeRule.onNodeWithTag("layer-row-0").performScrollTo()
            composeRule.waitForIdle()
            composeRule.onNode(
                hasTestTag("layer-badge-clip-0").and(
                    hasContentDescription(clipDesc)
                )
            ).assertIsDisplayed()
            composeRule.onNode(
                hasTestTag("layer-badge-mask-0").and(
                    hasContentDescription(maskDesc)
                )
            ).assertIsDisplayed()
            composeRule.onNode(
                hasTestTag("layer-badge-fx-0").and(
                    hasContentDescription(fxDesc)
                )
            ).assertIsDisplayed()
            // Slice report screenshot (best effort): badges + pinned bar +
            // thumbnails in one frame.
            try {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    val shot = InstrumentationRegistry.getInstrumentation()
                        .uiAutomation.takeScreenshot()
                    if (shot != null) {
                        val dst = File(composeRule.activity.cacheDir, "slice3-layers.png")
                        FileOutputStream(dst).use { o ->
                            shot.compress(Bitmap.CompressFormat.PNG, 100, o)
                        }
                        if (!shot.isRecycled) shot.recycle()
                    }
                }
            } catch (_: Exception) {
            }
            // Tap the mask badge: engine leaves mask-target mode.
            composeRule.onNodeWithTag("layer-badge-mask-0").performClick()
            composeRule.waitForIdle()
            Thread.sleep(400)
            assertEquals(
                "mask badge tap must clear maskTarget",
                -1,
                try {
                    org.json.JSONObject(es.layerState()).optInt("maskTarget", 0)
                } catch (_: Exception) {
                    0
                }
            )
            onUi {
                it.setLayerClipped(0, false)
                it.setLayerAdjustment(0, null)
            }
        } finally {
            resetSlice3()
            try {
                es.undo()
            } catch (_: Exception) {
            }
        }
    }

    // 8 kHz mono 16-bit 1 s 440 Hz sine: generated in-code so no binary
    // asset ever enters the repo (clean-room rule).
    private fun writeSineWav(dst: File) {
        val rate = 8000
        val n = rate
        val pcm = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (Math.sin(2.0 * Math.PI * 440.0 * i / rate) * 10000).toInt()
            pcm[i * 2] = (v and 0xFF).toByte()
            pcm[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        fun wavInt(v: Int): ByteArray = byteArrayOf(
            (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
        )
        fun wavShort(v: Int): ByteArray = byteArrayOf(
            (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()
        )
        FileOutputStream(dst).use { o ->
            o.write("RIFF".toByteArray())
            o.write(wavInt(36 + pcm.size))
            o.write("WAVE".toByteArray())
            o.write("fmt ".toByteArray())
            o.write(wavInt(16))
            o.write(wavShort(1))
            o.write(wavShort(1))
            o.write(wavInt(rate))
            o.write(wavInt(rate * 2))
            o.write(wavShort(2))
            o.write(wavShort(16))
            o.write("data".toByteArray())
            o.write(wavInt(pcm.size))
            o.write(pcm)
        }
    }

    // ---- Slice 4 practical timeline (spec docs/slice4-timeline.md §7)
    // App-side only: scrub, onion display mapping, delete guard, fps
    // transport, drawer budget. Each test recreates the activity for a
    // pristine reset (onion OFF, fps 12, cur 0, drawer folded, 1 frame)
    // so order never matters.

    private fun openTimeline() {
        composeRule.waitForIdle()
        // Tolerant opener: right after a recreate the rail may not be
        // composed yet, and mid-teardown the glyph can be briefly
        // absent — retry both branches instead of failing once.
        repeat(8) {
            val trackShown = try {
                composeRule.onNodeWithTag("timeline-track")
                    .assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
            if (trackShown) return
            try {
                composeRule.onNodeWithText("▸").performClick()
            } catch (_: AssertionError) {
                Thread.sleep(500)
            }
            composeRule.waitForIdle()
            Thread.sleep(300)
        }
        composeRule.onNodeWithTag("timeline-track").assertIsDisplayed()
    }

    private fun freshTimeline(): EngineSurface {
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        val es = surf()
        awaitSurface(es)
        openTimeline()
        return es
    }

    private fun addCels(n: Int) {
        repeat(n) {
            tap("cel-add")
        }
        Thread.sleep(300)
    }

    // Scroll-into-view tap for drawer controls: on narrow windows the
    // transport FlowRow wraps and later items sit below the 175dp
    // fold; a raw click would land outside the drawer and silently do
    // nothing. Never use on LazyRow thumbs (userScrollEnabled=false).
    private fun tap(tag: String) {
        reveal(tag)
        composeRule.onNodeWithTag(tag).performClick()
        composeRule.waitForIdle()
    }

    // Leave-no-trace cleanup for the next test (spec §7: reset onion
    // OFF, fps 12, cur 0, drawer folded, single cel — without the churn
    // of an activity recreate, which races the follower's startup).
    // Folding the drawer disposes the strip (stops play); the clip is
    // reset in place via direct engine calls.
    private fun settleTimeline() {
        composeRule.waitForIdle()
        try {
            composeRule.onNodeWithText("▾").performClick()
            composeRule.waitForIdle()
        } catch (_: AssertionError) {
            // Already folded.
        }
        Thread.sleep(200)
        onUi { es ->
            try {
                val n = es.frameCount()
                for (i in n - 1 downTo 1) {
                    es.setFrame(i)
                    es.deleteFrame(i)
                }
                es.setFrame(0)
                es.setOnion(false, false, 0.3f)
            } catch (_: Exception) {
            }
        }
        Thread.sleep(200)
    }

    // Scroll a drawer control into view without clicking (for text
    // asserts below the 175dp fold on narrow windows).
    private fun reveal(tag: String) {
        composeRule.onNodeWithTag(tag).performScrollTo()
        composeRule.waitForIdle()
    }

    private fun shortStroke(es: EngineSurface, cx: Float, cy: Float, t0: Long) {
        dispatchAll(
            es,
            simpleDown(t0, cx - 40f, cy),
            MotionEvent.obtain(t0, t0 + 16, MotionEvent.ACTION_MOVE, cx, cy, 1f, 1f, 0, 1f, 1f, 0, 0),
            MotionEvent.obtain(t0, t0 + 32, MotionEvent.ACTION_MOVE, cx + 40f, cy, 1f, 1f, 0, 1f, 1f, 0, 0),
            MotionEvent.obtain(t0, t0 + 48, MotionEvent.ACTION_UP, cx + 40f, cy, 1f, 1f, 0, 1f, 1f, 0, 0)
        )
    }

    private fun bitmapPixels(b: Bitmap): IntArray {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return px
    }

    private fun timelineButtonText(tag: String): String {
        val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.Text)
            ?.joinToString("") { it.text } ?: ""
    }

    // 1. A controlled 48dp horizontal drag on the track seeks exactly
    // one cel (16dp arm + ~48dp per cel, setFrame only on release);
    // the landed composite equals the stored pixels of that cel and no
    // cel is created/deleted.
    @Test
    fun scrubChangesCurrentFrame() {
        try {
        val es = freshTimeline()
        addCels(2)
        assertEquals(3, onUi { it.frameCount() })
        val t0 = SystemClock.uptimeMillis()
        val cx = es.width * 0.5f
        val cy = es.height * 0.5f
        // Ink cel 1 only: tap its thumb, stroke, wait for the render
        // thread to settle (two identical pulls), snapshot all frames.
        composeRule.onNodeWithTag("cel-thumb-1").performClick()
        composeRule.waitForIdle()
        shortStroke(es, cx, cy, t0)
        Thread.sleep(400)
        val settled = pullFull(es, 1)
        val settledPx = bitmapPixels(settled)
        settled.recycle()
        Thread.sleep(200)
        val check = pullFull(es, 1)
        val checkPx = bitmapPixels(check)
        check.recycle()
        assertTrue("render thread must settle after a stroke", settledPx.contentEquals(checkPx))
        val stored = (0 until 3).map { i ->
            val b = pullFull(es, i)
            val px = bitmapPixels(b)
            b.recycle()
            px
        }
        assertTrue("cel 1 stroke must paint ink", stored[1].any { it != -1 })
        assertTrue("cel 0 must stay blank", stored[0].all { it == -1 })
        // Back to cel 0, then a single-step 48dp seek right.
        composeRule.onNodeWithTag("cel-thumb-0").performClick()
        composeRule.waitForIdle()
        assertEquals(0, onUi { it.currentFrameIndex() })
        reveal("timeline-track")
        // NOTE: performTouchInput coordinates are node-local (0,0 is
        // the track's top-left), not boundsInRoot.
        val size = composeRule.onNodeWithTag("timeline-track")
            .fetchSemanticsNode().size
        val dxPx = 48f * composeRule.density.density
        val tcy = size.height / 2f
        val start = Offset(size.width / 2f - dxPx / 2, tcy)
        val end = Offset(size.width / 2f + dxPx / 2, tcy)
        composeRule.onNodeWithTag("timeline-track")
            .performTouchInput { swipe(start, end, 200L) }
        // The release lands async: poll instead of a fixed sleep.
        var landed = -1
        val deadline = SystemClock.uptimeMillis() + 5000
        while (landed != 1 && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(200)
            landed = onUi { it.currentFrameIndex() }
        }
        assertEquals("one 48dp seek must advance exactly one cel", 1, landed)
        assertEquals("scrub must not create/delete cels", 3, onUi { it.frameCount() })
        val now = pullFull(es, landed)
        val nowPx = bitmapPixels(now)
        now.recycle()
        assertTrue(
            "landed composite must equal the stored cel $landed pixels",
            nowPx.contentEquals(stored[landed])
        )
        composeRule.onNodeWithTag("scrub-candidate").assertDoesNotExist()
        assertTrue(onUi { it.undo() })
        } finally {
            settleTimeline()
        }
    }

    // 2. Onion master + counts never touch frame pixels
    // (nFrameCompositeInto excludes ghosts by engine contract) while
    // the ghost borders show in the strip hierarchy.
    @Test
    fun onionTintTouchesNoPixels() {
        try {
        val es = freshTimeline()
        addCels(2)
        val t0 = SystemClock.uptimeMillis()
        val cx = es.width * 0.5f
        val cy = es.height * 0.5f
        // After the adds cur=2: step back with prev (auto-scroll keeps
        // thumbs composed) and ink cels 0 and 1 differently.
        tap("transport-prev")
        tap("transport-prev")
        assertEquals(0, onUi { it.currentFrameIndex() })
        shortStroke(es, cx, cy - 60f, t0)
        Thread.sleep(300)
        tap("transport-next")
        assertEquals(1, onUi { it.currentFrameIndex() })
        shortStroke(es, cx, cy + 60f, t0 + 1000)
        Thread.sleep(400)
        val plain = pullFull(es, 1)
        val plainPx = bitmapPixels(plain)
        plain.recycle()
        assertTrue("fixture strokes must paint ink", plainPx.any { it != -1 })
        // Arm onion: master ON (counts default 1/1), strength ~50 %.
        tap("onion-master")
        tap("timeline-settings")
        try {
            composeRule.onNodeWithTag("onion-strength-slider")
                .performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
            composeRule.waitForIdle()
        } catch (_: Exception) {
        }
        val ghosted = pullFull(es, 1)
        val ghostPx = bitmapPixels(ghosted)
        ghosted.recycle()
        assertTrue(
            "onion display must not alter frame pixels",
            ghostPx.contentEquals(plainPx)
        )
        // The strength slider scrolled the drawer down: bring the
        // piste back before asserting on its ghost borders.
        reveal("timeline-track")
        composeRule.onNodeWithContentDescription("onion_before")
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("onion_after")
            .assertIsDisplayed()
        // Master OFF restores the exact same bytes.
        tap("onion-master")
        val off = pullFull(es, 1)
        val offPx = bitmapPixels(off)
        off.recycle()
        assertTrue(
            "onion OFF must keep byte-identical pixels",
            offPx.contentEquals(plainPx)
        )
        assertTrue(onUi { it.undo() })
        assertTrue(onUi { it.undo() })
        } finally {
            settleTimeline()
        }
    }

    // 3. Onion count steppers clamp to 0..4 and drive which thumbs
    // carry ghost borders (honest p/n mapping), without engine faults.
    @Test
    fun onionCountStepperBounds() {
        try {
        freshTimeline()
        addCels(3)
        assertEquals(4, onUi { it.frameCount() })
        // cur=3 after the adds: two prev steps to cel 1 (neighbors
        // both sides, auto-scroll window {0,1,2} keeps them composed).
        tap("transport-prev")
        tap("transport-prev")
        assertEquals(1, onUi { it.currentFrameIndex() })
        tap("timeline-settings")
        repeat(5) { tap("onion-before-plus") }
        reveal("onion-before-count")
        composeRule.onNodeWithTag("onion-before-count")
            .assertTextContains("4", substring = true)
        repeat(6) { tap("onion-before-minus") }
        reveal("onion-before-count")
        composeRule.onNodeWithTag("onion-before-count")
            .assertTextContains("0", substring = true)
        repeat(5) { tap("onion-after-plus") }
        reveal("onion-after-count")
        composeRule.onNodeWithTag("onion-after-count")
            .assertTextContains("4", substring = true)
        repeat(6) { tap("onion-after-minus") }
        reveal("onion-after-count")
        composeRule.onNodeWithTag("onion-after-count")
            .assertTextContains("0", substring = true)
        // Mapping: master ON with 0/0 shows no ghosts; 1/1 shows both
        // sides (cur=1 of 4 has neighbors either way).
        tap("onion-master")
        composeRule.onNodeWithContentDescription("onion_before")
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription("onion_after")
            .assertDoesNotExist()
        tap("onion-before-plus")
        tap("onion-after-plus")
        // The stepper taps scrolled the drawer down: bring the piste
        // back into view before asserting on its ghost borders.
        reveal("timeline-track")
        composeRule.onNodeWithContentDescription("onion_before")
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("onion_after")
            .assertIsDisplayed()
        assertEquals("bounds must not fault the engine", 4, onUi { it.frameCount() })
        // Master back off (each test recreates the activity anyway).
        tap("onion-master")
        } finally {
            settleTimeline()
        }
    }

    // 4. Delete guard: disabled at 1 cel, 1-tap 3 s arm, auto-disarm on
    // timeout, cur folds back after the last delete.
    @Test
    fun celDeleteGuardSingleFrame() {
        try {
        freshTimeline()
        composeRule.onNodeWithTag("cel-delete").assertIsNotEnabled()
        addCels(1)
        assertEquals(2, onUi { it.frameCount() })
        tap("cel-delete")
        val armed = timelineButtonText("cel-delete")
        assertTrue(
            "first tap must arm the confirm, got '$armed'",
            armed == "Tap again to confirm" || armed == "Toucher pour confirmer"
        )
        tap("cel-delete")
        Thread.sleep(300)
        assertEquals(1, onUi { it.frameCount() })
        assertEquals(0, onUi { it.currentFrameIndex() })
        composeRule.onNodeWithTag("cel-delete").assertIsNotEnabled()
        // Timeout path: arm, wait out the 3 s (4.5 s sleep keeps margin
        // on a loaded emulator), next tap re-arms only. The pre-assert
        // proves the timer disarmed before the tap, separating a timer
        // fault from a click fault.
        addCels(1)
        assertEquals(2, onUi { it.frameCount() })
        tap("cel-delete")
        Thread.sleep(4500)
        reveal("cel-delete")
        val disarmed = timelineButtonText("cel-delete")
        assertTrue(
            "guard must auto-disarm after 3 s, got '$disarmed'",
            disarmed == "Delete" || disarmed == "Supprimer"
        )
        tap("cel-delete")
        Thread.sleep(300)
        assertEquals("timeout must disarm (no delete)", 2, onUi { it.frameCount() })
        val rearmed = timelineButtonText("cel-delete")
        assertTrue(
            "timeout tap must re-arm, got '$rearmed'",
            rearmed == "Tap again to confirm" || rearmed == "Toucher pour confirmer"
        )
        tap("cel-delete")
        Thread.sleep(300)
        assertEquals(1, onUi { it.frameCount() })
        } finally {
            settleTimeline()
        }
    }

    // 5. fps clamps 1..60, play steps the clip, folding the drawer
    // pauses (fresh strip shows play on reopen).
    @Test
    fun fpsClampAndPlaySteps() {
        try {
        val es = freshTimeline()
        addCels(2)
        assertEquals(3, onUi { it.frameCount() })
        tap("timeline-settings")
        // NOTE: this Compose version's SetProgress takes the raw value
        // in range units (verified: 1f lands on fps 1, not max).
        composeRule.onNodeWithTag("fps-slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(60f) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("fps-value")
            .assertTextContains("60", substring = true)
        composeRule.onNodeWithTag("fps-slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("fps-value")
            .assertTextContains("1", substring = true)
        // Stepper holds the bounds too (no 0 / 61).
        tap("fps-minus")
        composeRule.onNodeWithTag("fps-value")
            .assertTextContains("1", substring = true)
        repeat(3) { tap("fps-plus") }
        composeRule.onNodeWithTag("fps-value")
            .assertTextContains("4", substring = true)
        // Play ~2.4 s at 4 fps: the current frame must advance.
        val seen = mutableSetOf<Int>()
        tap("transport-play")
        assertEquals("playing must show pause glyph", "⏸", timelineButtonText("transport-play"))
        repeat(4) {
            Thread.sleep(600)
            seen.add(onUi { it.currentFrameIndex() })
        }
        assertTrue("play must advance frames, saw $seen", seen.size >= 2)
        assertEquals("play must not create/delete cels", 3, onUi { it.frameCount() })
        // Folding the drawer pauses: reopen shows a fresh play glyph.
        composeRule.onNodeWithText("▾").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("timeline-track").assertDoesNotExist()
        composeRule.onNodeWithText("▸").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("timeline-track").assertIsDisplayed()
        assertEquals("reopen must show play (paused)", "▶", timelineButtonText("transport-play"))
        } finally {
            settleTimeline()
        }
    }

    // 6. Open drawer fits the 175dp budget, every transport target is
    // >= 48dp, one tap folds it back; also captures the slice report
    // screenshot (open drawer, tinted onion, fps visible).
    @Test
    fun drawerKeepsMaxHeightPhoneFolded() {
        try {
        val es = freshTimeline()
        addCels(2)
        val t0 = SystemClock.uptimeMillis()
        shortStroke(es, es.width * 0.5f, es.height * 0.5f, t0)
        Thread.sleep(400)
        tap("onion-master")
        tap("timeline-settings")
        val hDp = composeRule.onNodeWithTag("timeline-drawer")
            .getUnclippedBoundsInRoot().let { it.bottom - it.top }
        assertTrue(
            "open drawer must fit 175dp, got $hDp",
            hDp <= 176.dp
        )
        fun tall(tag: String) {
            composeRule.waitForIdle()
            val u = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            val h = u.bottom - u.top
            assertTrue(
                "$tag must be >= 48dp, got $h",
                h >= (48.dp - 0.5.dp)
            )
        }
        listOf(
            "transport-prev", "transport-play", "transport-next",
            "fps-minus", "fps-plus", "onion-master",
            "cel-add", "cel-duplicate", "cel-delete", "timeline-settings"
        ).forEach { tall(it) }
        // Slice report screenshot (best effort, never fails the gate):
        // piste + transport in view (thumbs, onion tint, fps).
        reveal("timeline-track")
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val shot = InstrumentationRegistry.getInstrumentation()
                    .uiAutomation.takeScreenshot()
                if (shot != null) {
                    val dst = File(composeRule.activity.cacheDir, "slice4-timeline.png")
                    FileOutputStream(dst).use { o ->
                        shot.compress(Bitmap.CompressFormat.PNG, 100, o)
                    }
                    if (!shot.isRecycled) shot.recycle()
                }
            }
        } catch (_: Exception) {
        }
        // One tap folds; canvas keeps full width; one tap reopens.
        composeRule.onNodeWithText("▾").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("timeline-track").assertDoesNotExist()
        assertTrue(
            "folded drawer must leave a full-width canvas",
            es.width > 200 && es.height > 200
        )
        composeRule.onNodeWithText("▸").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("timeline-track").assertIsDisplayed()
        } finally {
            settleTimeline()
        }
    }
    // ---- Slice 5 brush feeling (spec docs/slice5-brushfeel.md §7) ----
    // App-side only: cursor ring, curve graph -> engine scalar, honest
    // eraser tool, HUD pastille. Every chain ends in engine pixels or
    // test-only hooks, never Compose state alone.

    private fun resetBrushFeel() {
        resetSlice3()
        composeRule.activityRule.scenario.onActivity { act ->
            val p = act.brushPrefs
            p.setTool(false)
            p.updateEraserRadius(48f)
            p.updateEraserOpaque(1f)
            p.updateCurveScalar(0f)
            p.flush()
        }
        uiSync {
            it.navigateMode = false
            it.resetView()
            it.clearHoverForTest()
            it.lastStrokeLatencyMs = -1
        }
    }

    private fun pushTestBrush(
        radius: Float = 24f,
        opaque: Float = 1f,
        eraser: Boolean = false,
        curve: Float = 0f
    ) {
        composeRule.activityRule.scenario.onActivity { act ->
            val p = act.brushPrefs
            if (eraser) {
                p.updateEraserRadius(radius)
                p.updateEraserOpaque(opaque)
            } else {
                p.updateRadius(radius)
                p.updateOpaque(opaque)
            }
            p.setTool(eraser)
            p.updateCurveScalar(curve)
            p.bindTool("#141414", eraser, p.curve)
            findActiveEngineSurface(act.window.decorView)!!.setBrush(p.currentJson())
        }
    }

    private fun stylusProps() = arrayOf(
        MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_STYLUS
        }
    )

    private fun stylusCoords(x: Float, y: Float) = arrayOf(
        MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 0.8f
            size = 1f
        }
    )

    private fun hoverStylus(t0: Long, x: Float, y: Float, action: Int): MotionEvent =
        MotionEvent.obtain(
            t0, t0, action, 1, stylusProps(), stylusCoords(x, y),
            0, 0, 1f, 1f, 0, 0,
            android.view.InputDevice.SOURCE_STYLUS, 0
        )

    private fun strokeAcrossP(es: EngineSurface, y: Float, t0: Long, cx: Float, press: Float) {
        dispatchAll(
            es,
            MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, cx - 80f, y, press, 1f, 0, 1f, 1f, 0, 0),
            MotionEvent.obtain(t0, t0 + 16, MotionEvent.ACTION_MOVE, cx, y, press, 1f, 0, 1f, 1f, 0, 0),
            MotionEvent.obtain(t0, t0 + 32, MotionEvent.ACTION_MOVE, cx + 80f, y, press, 1f, 0, 1f, 1f, 0, 0),
            MotionEvent.obtain(t0, t0 + 48, MotionEvent.ACTION_UP, cx + 80f, y, press, 1f, 0, 1f, 1f, 0, 0)
        )
    }

    /** Exact non-white pixel count (step 1 over the raw buffer). */
    private fun countInkExact(b: Bitmap): Int {
        val px = bitmapPixels(b)
        var n = 0
        for (v in px) if (v != -1) n++
        return n
    }

    /** Clearly-painted core pixels (all channels below threshold). */
    private fun countDark(b: Bitmap, thresh: Int = 200): Int {
        val px = bitmapPixels(b)
        var n = 0
        for (v in px) {
            val r = (v shr 16) and 0xFF
            val g = (v shr 8) and 0xFF
            val bl = v and 0xFF
            if (r < thresh && g < thresh && bl < thresh) n++
        }
        return n
    }

    // 1. The hover ring matches the brush diameter through the Slice 1
    // view matrix; finger lift hides it; hover never touches pixels.
    @Test
    fun cursorMatchesBrushDiameter() {
        val es = surf()
        awaitSurface(es)
        resetBrushFeel()
        try {
            uiSync { it.navigateMode = false; it.resetView() }
            pushTestBrush(radius = 24f, curve = 0f)
            val cx = es.width * 0.5f
            val cy = es.height * 0.5f
            val before = pullFull(es)
            val beforePx = bitmapPixels(before)
            before.recycle()
            val t0 = SystemClock.uptimeMillis()
            val hov = hoverStylus(t0, cx, cy, MotionEvent.ACTION_HOVER_MOVE)
            composeRule.activityRule.scenario.onActivity { es.onHoverEvent(hov) }
            hov.recycle()
            Thread.sleep(400)
            val ring = es.cursorRingForTest()
            assertNotNull("stylus hover must show the cursor ring", ring)
            assertFalse("brush ring must not be the eraser style", ring!!.eraser)
            // dab at nominal 0.6 pressure: 24*(0.25+0.75*0.6) = 16.8px
            // motor; screen = motor * fitScale * viewScale (== 1 here).
            val fit = minOf(
                (es.width - 24f) / 2048f,
                (es.height - 24f) / 1536f
            )
            val expected = 16.8f * fit
            assertTrue(
                "ring radius ${ring.radiusScreen} must match 16.8*fit=$expected",
                kotlin.math.abs(ring.radiusScreen - expected) <= 2f
            )
            // Finger lift hides the ring; hover leaves pixels untouched.
            val exit = hoverStylus(t0 + 100, cx, cy, MotionEvent.ACTION_HOVER_EXIT)
            composeRule.activityRule.scenario.onActivity { es.onHoverEvent(exit) }
            exit.recycle()
            Thread.sleep(300)
            assertTrue(
                "hover exit must hide the ring",
                es.cursorRingForTest() == null
            )
            val after = pullFull(es)
            val afterPx = bitmapPixels(after)
            after.recycle()
            assertTrue(
                "hover overlay must not touch engine pixels",
                beforePx.contentEquals(afterPx)
            )
        } finally {
            resetBrushFeel()
        }
    }

    // 2. The pressure scalar drives rendering; the 4-point graph fits
    // onto it; points survive a restart.
    @Test
    fun curveChangesRendering() {
        val es = surf()
        awaitSurface(es)
        resetBrushFeel()
        try {
            uiSync { it.navigateMode = false; it.resetView() }
            pushTestBrush(radius = 24f, curve = 0f)
            val cx = es.width * 0.5f
            val cy = es.height * 0.5f
            // Simulated 0.5 pressure: raw 0.25 (pressureOf doubles).
            strokeAcrossP(es, cy, SystemClock.uptimeMillis(), cx, 0.25f)
            Thread.sleep(400)
            var b = pullFull(es)
            val linDark = countDark(b)
            b.recycle()
            assertTrue("linear stroke at 0.5 must paint a core, got $linDark", linDark > 100)
            assertTrue(es.undo())
            // curve=1 -> exponent 4: 0.5^4 = 0.0625, dab nearly invisible.
            pushTestBrush(radius = 24f, curve = 1f)
            strokeAcrossP(es, cy, SystemClock.uptimeMillis() + 5000, cx, 0.25f)
            Thread.sleep(400)
            b = pullFull(es)
            val firmDark = countDark(b)
            b.recycle()
            assertTrue(
                "curve=1 must shrink the painted core ($firmDark vs $linDark)",
                firmDark * 2 < linDark
            )
            assertTrue(es.undo())
            // 4-point "firm" graph -> scalar fit ~= 0.7.
            composeRule.activityRule.scenario.onActivity { act ->
                act.brushPrefs.updateCurvePts(
                    dev.openclip.ui.curvePresetPts("firm")
                )
            }
            var fitted = -1f
            var jsonCurve = -1f
            composeRule.activityRule.scenario.onActivity { act ->
                val p = act.brushPrefs
                fitted = p.curve
                p.bindTool("#141414", false, p.curve)
                val j = p.currentJson()
                jsonCurve = Regex("\"curve\"\\s*:\\s*(-?[0-9.]+)")
                    .find(j)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
                findActiveEngineSurface(act.window.decorView)!!.setBrush(j)
                p.flush()
            }
            assertTrue("firm graph must fit curve ~= 0.7, got $fitted", kotlin.math.abs(fitted - 0.7f) <= 0.15f)
            assertTrue("pushed JSON must carry the fitted curve, got $jsonCurve", kotlin.math.abs(jsonCurve - 0.7f) <= 0.15f)
            Thread.sleep(700)
            val f = File(composeRule.activity.filesDir, "brush-defaults.json")
            assertTrue("curve points must be persisted", f.readText().contains("curvePts"))
            composeRule.activityRule.scenario.recreate()
            composeRule.waitForIdle()
            Thread.sleep(500)
            var restored = floatArrayOf()
            var restoredCurve = -1f
            composeRule.activityRule.scenario.onActivity { act ->
                restored = act.brushPrefs.curvePts.copyOf()
                restoredCurve = act.brushPrefs.curve
            }
            assertEquals(4, restored.size)
            val want = dev.openclip.ui.curvePresetPts("firm")
            for (i in 0..3) {
                assertTrue(
                    "curve point $i must survive restart (${restored[i]} vs ${want[i]})",
                    kotlin.math.abs(restored[i] - want[i]) <= 0.02f
                )
            }
            assertTrue(
                "curve scalar must survive restart, got $restoredCurve",
                kotlin.math.abs(restoredCurve - 0.7f) <= 0.15f
            )
        } finally {
            resetBrushFeel()
            try {
                es.undo()
            } catch (_: Exception) {
            }
        }
    }

    // 3. The raster eraser only ever touches the active layer (honest
    // white-paint limit documented). TODO(alpha-erase): a real dest-out
    // erase (ARCHITECTURE.md first) must keep this test green while
    // revealing transparency instead of white.
    @Test
    fun eraserSparesOtherLayers() {
        val es = surf()
        awaitSurface(es)
        resetBrushFeel()
        try {
            uiSync { it.navigateMode = false; it.resetView() }
            val cx = es.width * 0.5f
            val cy = es.height * 0.5f
            val t0 = SystemClock.uptimeMillis()
            val liRed = onUi {
                val i = it.addLayerNamed("S5Red")
                it.setActiveLayer(i)
                it.setBrush(
                    """{"radius":64,"hardness":1.0,"opaque":1.0,"spacing":0.15,"color":"#FF0000","stabilize":0.0,"curve":0.0,"mode":"brush"}"""
                )
                i
            }
            bigStroke(es, cx, cy, t0, -140f)
            Thread.sleep(400)
            val liBlue = onUi {
                val i = it.addLayerNamed("S5Blue")
                it.setActiveLayer(i)
                it.setBrush(
                    """{"radius":64,"hardness":1.0,"opaque":1.0,"spacing":0.15,"color":"#0000FF","stabilize":0.0,"curve":0.0,"mode":"brush"}"""
                )
                i
            }
            bigStroke(es, cx, cy, t0 + 5000, 140f)
            Thread.sleep(400)
            val soloBefore = onUi { it.soloRenderLayer(liRed) }
            assertNotNull("solo render of the red layer must succeed", soloBefore)
            val beforePx = IntArray(soloBefore!!.width * soloBefore.height)
            soloBefore.getPixels(beforePx, 0, soloBefore.width, 0, 0, soloBefore.width, soloBefore.height)
            soloBefore.recycle()
            // Eraser stroke on the active (blue) layer, across its band
            // (blue covers cy+50..cy+230; an eraser pass at cy would land
            // on bare white and change nothing).
            pushTestBrush(radius = 48f, eraser = true)
            val fullBefore = pullFull(es)
            val fullBeforePx = bitmapPixels(fullBefore)
            fullBefore.recycle()
            strokeAcross(es, cy + 140f, t0 + 9000, cx)
            Thread.sleep(400)
            val soloAfter = onUi { it.soloRenderLayer(liRed) }
            assertNotNull("solo render after erase must succeed", soloAfter)
            val afterPx = IntArray(soloAfter!!.width * soloAfter.height)
            soloAfter.getPixels(afterPx, 0, soloAfter.width, 0, 0, soloAfter.width, soloAfter.height)
            soloAfter.recycle()
            assertTrue(
                "eraser must leave the bottom layer pixel-identical (white-paint limit)",
                beforePx.contentEquals(afterPx)
            )
            val fullAfter = pullFull(es)
            val fullAfterPx = bitmapPixels(fullAfter)
            fullAfter.recycle()
            assertTrue(
                "eraser stroke must change the composite",
                !fullBeforePx.contentEquals(fullAfterPx)
            )
            var white = 0
            var ink = 0
            var i = 0
            while (i < fullAfterPx.size) {
                if (fullAfterPx[i] != -1) ink++ else white++
                i += 2
            }
            assertTrue("composite must keep ink, got $ink", ink > 20)
            assertTrue("composite must keep white, got $white", white > 20)
            repeat(4) {
                try {
                    es.undo()
                } catch (_: Exception) {
                }
            }
        } finally {
            resetBrushFeel()
            repeat(10) {
                try {
                    if (!es.undo()) return@repeat
                } catch (_: Exception) {
                }
            }
        }
    }

    // 4. Eraser tool separation: own size/opacity memory, amber dashed
    // cursor, honest banner on multi-layer stacks; vector layers route
    // to the real vector eraser.
    @Test
    fun eraserToolSeparation() {
        val es = surf()
        awaitSurface(es)
        resetBrushFeel()
        try {
            ensureDockOpen()
            uiSync { it.navigateMode = false; it.resetView() }
            // Guarantee a multi-layer stack for the banner proof.
            onUi { it.addLayerNamed("S5Sep") }
            pushTestBrush(radius = 24f, opaque = 1f, eraser = false)
            pushTestBrush(radius = 48f, opaque = 0.5f, eraser = true)
            pushTestBrush(radius = 24f, opaque = 1f, eraser = false)
            var brushR = -1f
            var eraserR = -1f
            var brushJson = ""
            var eraserJson = ""
            composeRule.activityRule.scenario.onActivity { act ->
                val p = act.brushPrefs
                brushR = p.radius
                eraserR = p.eraserRadius
                p.bindTool("#141414", false, p.curve)
                brushJson = p.currentJson()
                p.bindTool("#141414", true, p.curve)
                eraserJson = p.currentJson()
            }
            assertEquals(24f, brushR)
            assertEquals(48f, eraserR)
            assertTrue("brush JSON must carry mode brush: $brushJson", brushJson.contains("\"brush\""))
            assertTrue("eraser JSON must carry mode eraser: $eraserJson", eraserJson.contains("\"eraser\""))
            // Amber dashed cursor in eraser mode.
            pushTestBrush(radius = 48f, eraser = true)
            val cx = es.width * 0.5f
            val cy = es.height * 0.5f
            composeRule.activityRule.scenario.onActivity { es.hoverForTest(cx, cy) }
            Thread.sleep(300)
            val ring = es.cursorRingForTest()
            assertNotNull("eraser hover must show the ring", ring)
            assertTrue("eraser ring must carry the eraser style", ring!!.eraser)
            composeRule.activityRule.scenario.onActivity { es.clearHoverForTest() }
            // Honest banner on a 2-layer stack, gone in brush mode.
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("eraser-banner").assertIsDisplayed()
            pushTestBrush(radius = 24f, eraser = false)
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("eraser-banner").assertDoesNotExist()
            // Vector routing: a real path removed by an eraser touch stroke.
            val vi = onUi {
                val i = it.addVectorLayer("S5Vec")
                it.setActiveLayer(i)
                i
            }
            onUi {
                it.appendVectorPath(
                    vi,
                    """{"pts":[{"x":100,"y":600},{"x":1900,"y":900}]}"""
                )
            }
            Thread.sleep(300)
            var b = pullFull(es)
            assertTrue("vector path must paint ink", countInk(b) > 20)
            b.recycle()
            pushTestBrush(radius = 48f, eraser = true)
            onUi { it.setActiveLayer(vi) }
            val t0 = SystemClock.uptimeMillis()
            strokeAcross(es, cy, t0, cx)
            Thread.sleep(400)
            b = pullFull(es)
            assertTrue(
                "vector eraser stroke must remove the path (got ${countInk(b)} ink)",
                countInk(b) == 0
            )
            b.recycle()
        } finally {
            resetBrushFeel()
            repeat(10) {
                try {
                    es.undo()
                } catch (_: Exception) {
                }
            }
        }
    }

    // 5. Strokes stay incremental and the HUD pastille lives (10 Hz text,
    // 800 ms TTL) with a measurable sub-500 ms down-to-ink latency.
    @Test
    fun strokeStaysIncrementalAndHudLives() {
        val es = surf()
        awaitSurface(es)
        resetBrushFeel()
        try {
            uiSync { it.navigateMode = false; it.resetView() }
            pushTestBrush(radius = 24f, curve = 0f)
            val cx = es.width * 0.5f
            val cy = es.height * 0.5f
            val t0 = SystemClock.uptimeMillis()
            dispatchAll(es, simpleDown(t0, cx - 80f, cy))
            // Wait for the render thread to open the stroke (bounded:
            // a loaded box can stall the loop, fixed sleeps would lie).
            var started = false
            for (k in 0 until 50) {
                if (es.lastStrokeLatencyMs >= 0) {
                    started = true
                    break
                }
                Thread.sleep(200)
            }
            assertTrue("render thread must open the stroke", started)
            dispatchAll(
                es,
                MotionEvent.obtain(t0, t0 + 100, MotionEvent.ACTION_MOVE, cx, cy, 1f, 1f, 0, 1f, 1f, 0, 0)
            )
            dispatchAll(
                es,
                MotionEvent.obtain(t0, t0 + 200, MotionEvent.ACTION_MOVE, cx + 80f, cy, 1f, 1f, 0, 1f, 1f, 0, 0)
            )
            // Incremental proof: poll until ink is composited pre-UP.
            var inkN = 0
            for (k in 0 until 50) {
                val b = pullFull(es)
                inkN = countInk(b)
                b.recycle()
                if (inkN > 0) break
                Thread.sleep(200)
            }
            assertTrue(
                "composite must carry ink mid-stroke (incremental)",
                inkN > 0
            )
            // HUD pastille visible with the live diameter (polled: the
            // render thread posts at 10 Hz, recomposition follows).
            var hudShown = false
            for (k in 0 until 15) {
                try {
                    composeRule.onNodeWithTag("brush-hud").assertIsDisplayed()
                    composeRule.onNodeWithTag("brush-hud-text")
                        .assertTextContains("24", substring = true)
                    hudShown = true
                    break
                } catch (_: AssertionError) {
                    Thread.sleep(200)
                }
            }
            assertTrue("HUD pastille must show 24px mid-stroke", hudShown)
            dispatchAll(
                es,
                MotionEvent.obtain(t0, t0 + 300, MotionEvent.ACTION_UP, cx + 80f, cy, 1f, 1f, 0, 1f, 1f, 0, 0)
            )
            Thread.sleep(1300)
            composeRule.onNodeWithTag("brush-hud").assertDoesNotExist()
            val lat = es.lastStrokeLatencyMs
            assertTrue(
                "down-to-ink latency must be measurable and < 500 ms, got $lat",
                lat >= 0 && lat < 500
            )
            assertTrue(es.undo())
        } finally {
            resetBrushFeel()
            try {
                es.undo()
            } catch (_: Exception) {
            }
        }
    }

    // 6. Every brush touch target >= 48dp; slice report screenshot (ring
    // + curve graph + eraser, best effort, never fails the gate). The
    // dock is folded first: on narrow windows the pinned bar overflows
    // its parent and the hide pastille is unreachable by tap.
    @Test
    fun touchTargets48dpBrush() {
        val es = surf()
        awaitSurface(es)
        resetBrushFeel()
        try {
            val dockWasOpen = try {
                composeRule.onNodeWithTag("color-panel").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
            if (dockWasOpen) {
                composeRule.onNodeWithTag("dock-toggle").performClick()
                composeRule.waitForIdle()
            }
            uiSync { it.navigateMode = false; it.resetView() }
            pushTestBrush(radius = 48f, eraser = true)
            // The palette row scrolls horizontally: bring the gear into
            // view like a real user before tapping (raw performClick on
            // an off-screen chip lands nowhere).
            composeRule.onNodeWithTag("advanced-toggle").performScrollTo()
            composeRule.onNodeWithTag("advanced-toggle").performClick()
            composeRule.waitForIdle()
            Thread.sleep(300)
            fun tall(tag: String) {
                composeRule.waitForIdle()
                val u = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
                val h = u.bottom - u.top
                assertTrue(
                    "$tag must be >= 48dp, got $h",
                    h >= (48.dp - 0.5.dp)
                )
            }
            listOf(
                "curve-point-0", "curve-point-1", "curve-point-2",
                "curve-point-3", "curve-preset-linear", "curve-preset-soft",
                "curve-preset-firm", "curve-slider-box", "tool-brush",
                "tool-eraser", "pinned-hide"
            ).forEach { tall(it) }
            // Slice report screenshot: amber eraser chip + curve graph +
            // banner + cursor ring in one frame.
            composeRule.activityRule.scenario.onActivity { act ->
                findActiveEngineSurface(act.window.decorView)!!
                    .hoverForTest(es.width * 0.5f, es.height * 0.5f)
            }
            Thread.sleep(400)
            try {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    val shot = InstrumentationRegistry.getInstrumentation()
                        .uiAutomation.takeScreenshot()
                    if (shot != null) {
                        val dst = File(composeRule.activity.cacheDir, "slice5-brushfeel.png")
                        FileOutputStream(dst).use { o ->
                            shot.compress(Bitmap.CompressFormat.PNG, 100, o)
                        }
                        if (!shot.isRecycled) shot.recycle()
                    }
                }
            } catch (_: Exception) {
            }
            composeRule.activityRule.scenario.onActivity { act ->
                findActiveEngineSurface(act.window.decorView)!!.clearHoverForTest()
            }
            // Collapse the bar (poll: a tap landing mid-recomposition can
            // be swallowed) then prove the 48dp pastille.
            for (k in 0 until 15) {
                val expanded = try {
                    composeRule.onNodeWithTag("pinned-hide").assertExists()
                    true
                } catch (_: AssertionError) {
                    false
                }
                val collapsed = try {
                    composeRule.onNodeWithTag("pinned-show").assertExists()
                    true
                } catch (_: AssertionError) {
                    false
                }
                if (collapsed && !expanded) break
                if (expanded) {
                    composeRule.onNodeWithTag("pinned-hide").performClick()
                }
                composeRule.waitForIdle()
                Thread.sleep(200)
            }
            composeRule.onNodeWithTag("pinned-show").assertExists()
            tall("pinned-show")
            composeRule.onNodeWithTag("pinned-show").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("pinned-bar").assertIsDisplayed()
            composeRule.onNodeWithTag("advanced-toggle").performScrollTo()
            composeRule.onNodeWithTag("advanced-toggle").performClick()
            composeRule.waitForIdle()
            ensureDockOpen()
        } finally {
            resetBrushFeel()
        }
    }

    // ---- Slice 6 multi-docs + save 1-tap + workspaces (spec
    // docs/slice6-multidoc.md). All state flows through the activity's
    // test hooks (UI thread via scenario.onActivity); user-visible paths
    // (tabs, dialogs, command bar) go through real Compose taps.

    private fun hookIds(): List<String> {
        var r: List<String> = emptyList()
        composeRule.activityRule.scenario.onActivity { act ->
            r = act.activityTestHooks.docIds?.invoke() ?: emptyList()
        }
        return r
    }

    private fun hookActive(): String? {
        var r: String? = null
        composeRule.activityRule.scenario.onActivity { act ->
            r = act.activityTestHooks.activeDocId?.invoke()
        }
        return r
    }

    private fun hookSurface(id: String): EngineSurface? {
        var r: EngineSurface? = null
        composeRule.activityRule.scenario.onActivity { act ->
            r = act.activityTestHooks.surfaceFor?.invoke(id)
        }
        return r
    }

    private fun hookDirty(id: String): Boolean {
        var r = false
        composeRule.activityRule.scenario.onActivity { act ->
            r = act.activityTestHooks.dirtyOf?.invoke(id) == true
        }
        return r
    }

    private fun hookSync() {
        composeRule.waitForIdle()
        Thread.sleep(300)
    }

    private fun hookOpen(name: String, w: Int, h: Int, bg: Int?): String {
        var r = ""
        composeRule.activityRule.scenario.onActivity { act ->
            r = act.activityTestHooks.openDoc?.invoke(name, w, h, 160f, bg) ?: ""
        }
        assertTrue("openDoc must succeed (ceiling?)", r.isNotEmpty())
        hookSync()
        return r
    }

    private fun hookActivate(id: String) {
        composeRule.activityRule.scenario.onActivity { act ->
            act.activityTestHooks.activateDoc?.invoke(id)
        }
        hookSync()
    }

    private fun hookPose(): dev.openclip.ui.WorkspacePose? {
        var r: dev.openclip.ui.WorkspacePose? = null
        composeRule.activityRule.scenario.onActivity { act ->
            r = act.activityTestHooks.currentPose?.invoke()
        }
        return r
    }

    private fun hookApplyWorkspace(name: String): Boolean {
        var r = false
        composeRule.activityRule.scenario.onActivity { act ->
            r = act.activityTestHooks.applyWorkspace?.invoke(name) == true
        }
        return r
    }

    private fun awaitHooks(timeoutMs: Long = 15000) {
        val t0 = SystemClock.uptimeMillis()
        var ok = false
        while (!ok && SystemClock.uptimeMillis() - t0 < timeoutMs) {
            composeRule.activityRule.scenario.onActivity { act ->
                ok = act.activityTestHooks.docIds != null
            }
            if (!ok) Thread.sleep(200)
        }
        assertTrue("slice6 test hooks must be wired", ok)
    }

    private fun awaitDocReady(id: String, timeoutMs: Long = 25000): EngineSurface {
        var es: EngineSurface? = null
        val t0 = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - t0 < timeoutMs) {
            es = hookSurface(id)
            if (es != null && es.width > 200 && es.height > 200 && es.isReady()) break
            Thread.sleep(200)
        }
        assertNotNull("surface $id must become ready", es)
        val s = es!!
        assertTrue(
            "surface $id ready (w=${s.width} h=${s.height} ready=${s.isReady()})",
            s.width > 200 && s.height > 200 && s.isReady()
        )
        return s
    }

    private fun awaitActive(id: String, timeoutMs: Long = 8000): Boolean {
        val t0 = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - t0 < timeoutMs) {
            if (hookActive() == id) return true
            Thread.sleep(150)
        }
        return hookActive() == id
    }

    private fun awaitCount(want: Int, timeoutMs: Long = 8000): List<String> {
        val t0 = SystemClock.uptimeMillis()
        var ids = hookIds()
        while (ids.size != want && SystemClock.uptimeMillis() - t0 < timeoutMs) {
            Thread.sleep(200)
            ids = hookIds()
        }
        return ids
    }

    private fun awaitDirty(id: String, want: Boolean, timeoutMs: Long = 8000) {
        val t0 = SystemClock.uptimeMillis()
        while (hookDirty(id) != want && SystemClock.uptimeMillis() - t0 < timeoutMs) {
            Thread.sleep(200)
        }
        assertEquals("dirty($id) must be $want", want, hookDirty(id))
    }

    private fun awaitPaper(id: String, timeoutMs: Long = 30000) {
        val t0 = SystemClock.uptimeMillis()
        var ready = false
        while (!ready && SystemClock.uptimeMillis() - t0 < timeoutMs) {
            composeRule.activityRule.scenario.onActivity { act ->
                ready = act.activityTestHooks.paperReady?.invoke(id) == true
            }
            if (!ready) Thread.sleep(300)
        }
        assertTrue("paper must load for $id", ready)
    }

    private fun awaitHandles(want: Int, timeoutMs: Long = 8000) {
        val t0 = SystemClock.uptimeMillis()
        while (EngineSurface.liveHandles != want &&
            SystemClock.uptimeMillis() - t0 < timeoutMs
        ) {
            Thread.sleep(200)
        }
        assertEquals("live handles must be $want", want, EngineSurface.liveHandles)
    }

    /** Canonical reset: exactly 1 clean default doc. Returns its id. */
    private fun slice6Reset(): String {
        awaitHooks()
        var id = ""
        composeRule.activityRule.scenario.onActivity { act ->
            id = act.activityTestHooks.resetDocs?.invoke() ?: ""
            act.activityTestHooks.poke?.invoke()
        }
        // Cross-test isolation: workspaces/commandbar files persist in
        // filesDir and the last workspace auto-applies on launch (which
        // can reopen the dock and shrink the canvas). Reset to factory
        // presets + Drawing pose, then close the dock for a deterministic
        // wide canvas on every form factor.
        composeRule.activityRule.scenario.onActivity { act ->
            try {
                File(act.filesDir, "workspaces.json").delete()
            } catch (_: Exception) {
            }
            try {
                File(act.filesDir, "commandbar.json").delete()
            } catch (_: Exception) {
            }
            act.activityTestHooks.reloadWorkspaces?.invoke()
            val draw = act.getString(dev.openclip.R.string.workspace_draw)
            act.activityTestHooks.applyWorkspace?.invoke(draw)
        }
        var dockOpen = true
        composeRule.activityRule.scenario.onActivity { act ->
            dockOpen = act.activityTestHooks.currentPose?.invoke()?.dockOpen == true
        }
        if (dockOpen) {
            composeRule.onNodeWithTag("dock-toggle").performClick()
            composeRule.waitForIdle()
        }
        // Never leak a "last workspace" into the next test's launch.
        composeRule.activityRule.scenario.onActivity { act ->
            act.activityTestHooks.clearLastWorkspace?.invoke()
        }
        composeRule.waitForIdle()
        assertTrue("resetDocs must open a doc", id.isNotEmpty())
        awaitDocReady(id)
        awaitPaper(id)
        awaitHandles(1)
        awaitDirty(id, false)
        return id
    }

    private fun pinchOut(es: EngineSurface, cx: Float, cy: Float, t0: Long) {
        val props2 = fingerProps(0, 1)
        val down = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        dispatchAll(
            es,
            simpleDown(t0, cx - 80f, cy),
            multiObtain(
                t0, t0 + 32, MotionEvent.ACTION_POINTER_DOWN or down, props2,
                fingerCoords(Pair(cx - 80f, cy), Pair(cx - 40f, cy))
            ),
            multiObtain(
                t0, t0 + 48, MotionEvent.ACTION_MOVE, props2,
                fingerCoords(Pair(cx - 120f, cy), Pair(cx + 40f, cy))
            ),
            multiObtain(
                t0, t0 + 64, MotionEvent.ACTION_MOVE, props2,
                fingerCoords(Pair(cx - 160f, cy), Pair(cx + 120f, cy))
            ),
            multiObtain(
                t0, t0 + 80, MotionEvent.ACTION_POINTER_UP or down, props2,
                fingerCoords(Pair(cx - 160f, cy), Pair(cx + 120f, cy))
            ),
            MotionEvent.obtain(
                t0, t0 + 96, MotionEvent.ACTION_UP,
                cx - 160f, cy, 1f, 1f, 0, 1f, 1f, 0, 0
            )
        )
    }

    // Slice 6.1: two tabs keep byte-identical pixels across switches, the
    // per-handle M7 undo stack survives pause/resume, and each surface
    // keeps its own view matrix.
    @Test
    fun slice6_twoDocsSwitchKeepsPixelsAndUndo() {
        val a = slice6Reset()
        val esA = awaitDocReady(a)
        try {
            uiSync { it.navigateMode = false; it.resetView() }
            pushTestBrush(radius = 24f, curve = 0f)
            val cx = esA.width * 0.5f
            val cy = esA.height * 0.5f
            strokeAcross(esA, cy, SystemClock.uptimeMillis(), cx)
            Thread.sleep(600)
            var b = pullFull(esA)
            val inkA1 = countInk(b)
            b.recycle()
            assertTrue("stroke on A must paint ink, got $inkA1", inkA1 > 100)
            pinchOut(esA, cx, cy, SystemClock.uptimeMillis() + 2000)
            Thread.sleep(400)
            var scaleA = 0f
            composeRule.activityRule.scenario.onActivity { scaleA = esA.viewScale() }
            assertTrue("pinch must zoom A (got $scaleA)", scaleA > 1.2f)
            val bmpBefore = pullFull(esA)
            val pxBefore = bitmapPixels(bmpBefore)
            bmpBefore.recycle()
            // Open B through the real + flow (defaults 2048x1536 white).
            composeRule.onNodeWithTag("doc-tab-add").performScrollTo()
            composeRule.onNodeWithTag("doc-tab-add").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("newcanvas-confirm").performClick()
            hookSync()
            val ids2 = awaitCount(2)
            assertEquals(2, ids2.size)
            val bId = ids2.first { it != a }
            val esB = awaitDocReady(bId)
            awaitPaper(bId)
            assertEquals("B must be active after +", bId, hookActive())
            var scaleB = 0f
            composeRule.activityRule.scenario.onActivity { scaleB = esB.viewScale() }
            assertEquals("B must start at 100%", 1f, scaleB, 0.001f)
            strokeAcross(esB, cy, SystemClock.uptimeMillis() + 4000, cx)
            Thread.sleep(600)
            var bb = pullFull(esB)
            assertTrue("stroke on B must paint ink", countInk(bb) > 100)
            bb.recycle()
            // Back to A through the real tab: composite byte-identical.
            composeRule.onNodeWithTag("doc-tab-$a").performScrollTo()
            composeRule.onNodeWithTag("doc-tab-$a").performClick()
            assertTrue("A must be active again", awaitActive(a))
            val bmpAfter = pullFull(esA)
            val pxAfter = bitmapPixels(bmpAfter)
            bmpAfter.recycle()
            assertTrue(
                "A composite must be byte-identical across the switch",
                pxBefore.contentEquals(pxAfter)
            )
            // Undo survives pause/resume (handle kept alive while parked).
            var undone = false
            composeRule.activityRule.scenario.onActivity { undone = esA.undo() }
            assertTrue("undo on A must succeed after switch", undone)
            Thread.sleep(500)
            var ub = pullFull(esA)
            val inkAUndo = countInk(ub)
            ub.recycle()
            assertTrue(
                "undo must remove ink ($inkAUndo vs $inkA1)",
                inkAUndo < inkA1
            )
            var redone = false
            composeRule.activityRule.scenario.onActivity { redone = esA.redo() }
            assertTrue("redo must succeed", redone)
            Thread.sleep(500)
            var rb = pullFull(esA)
            assertEquals("redo must restore ink", inkA1, countInk(rb))
            rb.recycle()
            var scaleA2 = 0f
            composeRule.activityRule.scenario.onActivity { scaleA2 = esA.viewScale() }
            assertEquals(
                "A zoom must survive the round trip",
                scaleA, scaleA2, 0.01f
            )
            var scaleB2 = 0f
            composeRule.activityRule.scenario.onActivity { scaleB2 = esB.viewScale() }
            assertEquals("B zoom must stay 100%", 1f, scaleB2, 0.001f)
            // Slice report screenshot: two tabs + doc title + 1-tap save.
            try {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    val shot = InstrumentationRegistry.getInstrumentation()
                        .uiAutomation.takeScreenshot()
                    if (shot != null) {
                        val dst = File(
                            composeRule.activity.cacheDir, "slice6-multidoc.png"
                        )
                        FileOutputStream(dst).use { o ->
                            shot.compress(Bitmap.CompressFormat.PNG, 100, o)
                        }
                        if (!shot.isRecycled) shot.recycle()
                    }
                }
            } catch (_: Exception) {
            }
        } finally {
            slice6Reset()
        }
    }

    // Slice 6.2: tapping a tab mid-stroke pre-selects but defers the real
    // switch to ACTION_UP (5 s timeout); no point leaks into the other doc.
    @Test
    fun slice6_switchDuringStrokeDefers() {
        val a = slice6Reset()
        val esA = awaitDocReady(a)
        try {
            uiSync { it.navigateMode = false; it.resetView() }
            pushTestBrush(radius = 24f, curve = 0f)
            val cx = esA.width * 0.5f
            val cy = esA.height * 0.5f
            val bId = hookOpen("B", 2048, 1536, -1)
            val esB = awaitDocReady(bId)
            awaitPaper(bId)
            hookActivate(a)
            assertTrue(awaitActive(a))
            // Open stroke on A (DOWN + MOVEs, no UP).
            val t0 = SystemClock.uptimeMillis()
            var downRet = false
            var openNow = false
            var pausedNow = true
            var vw = 0
            var vh = 0
            composeRule.activityRule.scenario.onActivity {
                vw = esA.width
                vh = esA.height
                downRet = esA.dispatchTouchEvent(simpleDown(t0, cx - 80f, cy))
                openNow = esA.isDrawingNow()
                pausedNow = esA.isPaused()
                esA.dispatchTouchEvent(
                    MotionEvent.obtain(
                        t0, t0 + 16, MotionEvent.ACTION_MOVE,
                        cx, cy, 1f, 1f, 0, 1f, 1f, 0, 0
                    )
                )
                esA.dispatchTouchEvent(
                    MotionEvent.obtain(
                        t0, t0 + 32, MotionEvent.ACTION_MOVE,
                        cx + 80f, cy, 1f, 1f, 0, 1f, 1f, 0, 0
                    )
                )
            }
            assertTrue("DOWN must be accepted (ret=$downRet)", downRet)
            assertTrue(
                "stroke must open immediately (paused=$pausedNow view=${vw}x$vh pt=${cx - 80f},$cy)",
                openNow
            )
            var drawing = false
            val t1 = SystemClock.uptimeMillis()
            while (!drawing && SystemClock.uptimeMillis() - t1 < 5000) {
                composeRule.activityRule.scenario.onActivity {
                    drawing = esA.isDrawingNow()
                }
                if (!drawing) Thread.sleep(150)
            }

            assertTrue("stroke must be open on A", drawing)
            // Tap B via direct hook (deterministic delivery): deferred.
            // No waitForIdle here — idling while a stroke is open stalls
            // past the 5 s defer timeout.
            composeRule.activityRule.scenario.onActivity { act ->
                act.activityTestHooks.requestActivate?.invoke(bId)
            }
            Thread.sleep(600)
            var pendAfterTap = "<unset>"
            var activeAfterTap = "<unset>"
            composeRule.activityRule.scenario.onActivity { act ->
                pendAfterTap = act.activityTestHooks.pendingTab?.invoke() ?: "<null>"
                activeAfterTap = act.activityTestHooks.activeDocId?.invoke() ?: "<null>"
            }
            assertEquals(
                "tap must arm the deferred switch (active=$activeAfterTap)",
                bId,
                pendAfterTap
            )
            assertEquals("switch must defer while stroking", a, hookActive())
            // UP lands on A: the stroke commits there, then the switch fires.
            composeRule.activityRule.scenario.onActivity {
                esA.dispatchTouchEvent(
                    MotionEvent.obtain(
                        t0, t0 + 300, MotionEvent.ACTION_UP,
                        cx + 80f, cy, 1f, 1f, 0, 1f, 1f, 0, 0
                    )
                )
            }
            var stillDrawing = true
            var drawingChecks = 0
            val tUp = SystemClock.uptimeMillis()
            while (stillDrawing && SystemClock.uptimeMillis() - tUp < 5000) {
                composeRule.activityRule.scenario.onActivity {
                    stillDrawing = esA.isDrawingNow()
                }
                drawingChecks++
                if (stillDrawing) Thread.sleep(300)
            }
            assertTrue(
                "stroke must close after UP (checks=$drawingChecks)",
                !stillDrawing
            )
            var pend = "<unset>"
            composeRule.activityRule.scenario.onActivity { act ->
                pend = act.activityTestHooks.pendingTab?.invoke() ?: "<null>"
            }
            assertTrue("pending must clear after UP (pending=$pend)", pend == "<null>")
            assertTrue("switch must complete after UP", awaitActive(bId))
            Thread.sleep(500)
            var bb = pullFull(esB)
            assertEquals("B must be virgin (no leaked points)", 0, countInk(bb))
            bb.recycle()
            var ab = pullFull(esA)
            assertTrue("stroke must have committed on A", countInk(ab) > 100)
            ab.recycle()
            // Timeout path: fresh stroke on B, tap A, never lift for 5 s.
            hookActivate(bId)
            assertTrue(awaitActive(bId))
            val t2 = SystemClock.uptimeMillis()
            composeRule.activityRule.scenario.onActivity {
                esB.dispatchTouchEvent(simpleDown(t2, cx - 80f, cy))
                esB.dispatchTouchEvent(
                    MotionEvent.obtain(
                        t2, t2 + 16, MotionEvent.ACTION_MOVE,
                        cx, cy, 1f, 1f, 0, 1f, 1f, 0, 0
                    )
                )
            }
            Thread.sleep(400)
            composeRule.activityRule.scenario.onActivity { act ->
                act.activityTestHooks.requestActivate?.invoke(a)
            }
            Thread.sleep(5800)
            assertEquals("expired defer must stay on B", bId, hookActive())
            // Release and switch normally.
            composeRule.activityRule.scenario.onActivity {
                esB.dispatchTouchEvent(
                    MotionEvent.obtain(
                        t2, t2 + 6000, MotionEvent.ACTION_UP,
                        cx, cy, 1f, 1f, 0, 1f, 1f, 0, 0
                    )
                )
            }
            Thread.sleep(400)
            composeRule.activityRule.scenario.onActivity { act ->
                act.activityTestHooks.requestActivate?.invoke(a)
            }
            assertTrue(awaitActive(a))
        } finally {
            slice6Reset()
        }
    }

    // Slice 6.3: 1-tap save clears dirty on the saved doc only.
    @Test
    fun slice6_saveOneTapClearsDirtyOnlyThere() {
        val a = slice6Reset()
        val esA = awaitDocReady(a)
        var saveFile: File? = null
        try {
            uiSync { it.navigateMode = false; it.resetView() }
            pushTestBrush(radius = 24f, curve = 0f)
            strokeAcross(
                esA, esA.height * 0.5f, SystemClock.uptimeMillis(),
                esA.width * 0.5f
            )
            awaitDirty(a, true)
            // Inject a direct file path (no SAF dialog on the emulator).
            var path = ""
            composeRule.activityRule.scenario.onActivity { act ->
                path = File(act.cacheDir, "slice6-A.ora").absolutePath
                act.activityTestHooks.setSavePath?.invoke(a, path)
            }
            saveFile = File(path)
            if (saveFile.exists()) saveFile.delete()
            composeRule.onNodeWithTag("doc-save").performClick()
            awaitDirty(a, false, 15000)
            assertTrue(
                "save file must exist",
                saveFile.exists() && saveFile.length() > 0
            )
            // Untouched B stays clean; dirtying B leaves A clean.
            val bId = hookOpen("B", 2048, 1536, -1)
            awaitDocReady(bId)
            awaitDirty(bId, false)
            awaitPaper(bId)
            val esB = hookSurface(bId)!!
            assertEquals(bId, hookActive())
            uiSync { it.navigateMode = false }
            pushTestBrush(radius = 24f, curve = 0f)
            strokeAcross(
                esB, esB.height * 0.5f, SystemClock.uptimeMillis() + 5000,
                esB.width * 0.5f
            )
            awaitDirty(bId, true)
            assertEquals("A must stay clean", false, hookDirty(a))
        } finally {
            try {
                saveFile?.delete()
            } catch (_: Exception) {
            }
            slice6Reset()
        }
    }

    // Slice 6.4: ceiling at 4 docs with a polite dialog, synchronous clean
    // dispose (handle counter), and a dirty-close confirm dialog.
    @Test
    fun slice6_fourDocsCeilingAndCleanDispose() {
        val a = slice6Reset()
        try {
            val bId = hookOpen("B", 1024, 768, -1)
            val cId = hookOpen("C", 1024, 768, -1)
            val dId = hookOpen("D", 1024, 768, -1)
            awaitDocReady(bId)
            awaitDocReady(cId)
            awaitDocReady(dId)
            assertEquals(4, hookIds().size)
            awaitHandles(4)
            // 5th refused: polite dialog, no 5th handle.
            composeRule.onNodeWithTag("doc-tab-add").performScrollTo()
            composeRule.onNodeWithTag("doc-tab-add").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("docs-full-hint").assertIsDisplayed()
            assertEquals(4, hookIds().size)
            awaitHandles(4)
            composeRule.onNodeWithTag("docs-full-ok").performClick()
            composeRule.waitForIdle()
            // Close clean B through its cross: no dialog, active stays D.
            composeRule.onNodeWithTag("doc-tab-close-$bId").performScrollTo()
            composeRule.onNodeWithTag("doc-tab-close-$bId").performClick()
            val ids3 = awaitCount(3)
            assertEquals(3, ids3.size)
            assertFalse(ids3.contains(bId))
            assertEquals("active stays last tab", dId, hookActive())
            awaitHandles(3)
            // Ceiling freed: reopen works.
            val eId = hookOpen("E", 1024, 768, -1)
            awaitDocReady(eId)
            awaitPaper(eId)
            assertEquals(4, hookIds().size)
            awaitHandles(4)
            // Dirty close asks: Save / Discard / Cancel.
            val esE = hookSurface(eId)!!
            hookActivate(eId)
            uiSync { it.navigateMode = false }
            pushTestBrush(radius = 24f, curve = 0f)
            strokeAcross(
                esE, esE.height * 0.5f, SystemClock.uptimeMillis(),
                esE.width * 0.5f
            )
            awaitDirty(eId, true)
            composeRule.onNodeWithTag("doc-tab-close-$eId").performScrollTo()
            composeRule.onNodeWithTag("doc-tab-close-$eId").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("doc-close-dialog").assertIsDisplayed()
            composeRule.onNodeWithTag("doc-close-discard").assertIsDisplayed()
            composeRule.onNodeWithTag("doc-close-save").assertIsDisplayed()
            composeRule.onNodeWithTag("doc-close-cancel").performClick()
            composeRule.waitForIdle()
            assertTrue("cancel keeps the tab", hookIds().contains(eId))
            assertEquals("E still active", eId, hookActive())
            assertEquals("still dirty", true, hookDirty(eId))
        } finally {
            slice6Reset()
        }
    }

    // Slice 6.5: new-canvas dialog dims/guard — parameterized surface (JNI
    // unchanged), transparent paper (center alpha 0), small hint, 4096 cap.
    @Test
    fun slice6_newCanvasDialogDimsAndGuard() {
        val a = slice6Reset()
        try {
            // 3000x2000 transparent through the real dialog.
            composeRule.onNodeWithTag("doc-tab-add").performScrollTo()
            composeRule.onNodeWithTag("doc-tab-add").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("newcanvas-preset-3000").performScrollTo()
            composeRule.onNodeWithTag("newcanvas-preset-3000").performClick()
            composeRule.onNodeWithTag("newcanvas-bg-transparent").performScrollTo()
            composeRule.onNodeWithTag("newcanvas-bg-transparent").performClick()
            composeRule.onNodeWithTag("newcanvas-confirm").performClick()
            hookSync()
            val ids = awaitCount(2)
            val tId = ids.first { it != a }
            val esT = awaitDocReady(tId)
            awaitPaper(tId, 45000)
            assertEquals(3000, esT.canvasWidth())
            assertEquals(2000, esT.canvasHeight())
            // Transparent layer 1: center pixel alpha 0 (poll paper load).
            var alpha = -1
            val t0 = SystemClock.uptimeMillis()
            while (SystemClock.uptimeMillis() - t0 < 25000) {
                val bmp = pullFull(esT)
                alpha = (bmp.getPixel(bmp.width / 2, bmp.height / 2) ushr 24) and 0xFF
                bmp.recycle()
                if (alpha == 0) break
                Thread.sleep(500)
            }
            assertEquals("transparent paper must load (alpha=$alpha)", 0, alpha)
            // 100x100: small hint shown, creation still allowed.
            composeRule.onNodeWithTag("doc-tab-add").performScrollTo()
            composeRule.onNodeWithTag("doc-tab-add").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("newcanvas-width").performScrollTo()
            composeRule.onNodeWithTag("newcanvas-width").performTextClearance()
            composeRule.onNodeWithTag("newcanvas-width").performTextInput("100")
            composeRule.onNodeWithTag("newcanvas-height").performScrollTo()
            composeRule.onNodeWithTag("newcanvas-height").performTextClearance()
            composeRule.onNodeWithTag("newcanvas-height").performTextInput("100")
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("newcanvas-small-hint").assertIsDisplayed()
            composeRule.onNodeWithTag("newcanvas-estimate").assertIsDisplayed()
            composeRule.onNodeWithTag("newcanvas-confirm").performClick()
            hookSync()
            val ids3 = awaitCount(3)
            val sId = ids3.first { it != a && it != tId }
            val esS = awaitDocReady(sId)
            awaitPaper(sId)
            assertEquals(100, esS.canvasWidth())
            assertEquals(100, esS.canvasHeight())
            // 5000px refused politely: no tab created.
            composeRule.onNodeWithTag("doc-tab-add").performScrollTo()
            composeRule.onNodeWithTag("doc-tab-add").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("newcanvas-width").performScrollTo()
            composeRule.onNodeWithTag("newcanvas-width").performTextClearance()
            composeRule.onNodeWithTag("newcanvas-width").performTextInput("5000")
            composeRule.onNodeWithTag("newcanvas-confirm").performClick()
            Thread.sleep(800)
            assertEquals("oversize canvas must be refused", 3, hookIds().size)
            composeRule.onNodeWithTag("newcanvas-cancel").performClick()
            composeRule.waitForIdle()
        } finally {
            slice6Reset()
        }
    }

    // Slice 6.6: workspace round trip — posture only, 6 fields, persisted
    // last, corrupt JSON falls back to factory presets.
    @Test
    fun slice6_workspaceRoundTrip() {
        slice6Reset()
        try {
            var drawName = ""
            var animName = ""
            composeRule.activityRule.scenario.onActivity { act ->
                drawName = act.getString(dev.openclip.R.string.workspace_draw)
                animName = act.getString(dev.openclip.R.string.workspace_anim)
            }
            assertTrue(hookApplyWorkspace(animName))
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("timeline-drawer").assertIsDisplayed()
            // Modify: close the dock + navigate mode on (nav toggle chip).
            composeRule.onNodeWithTag("dock-toggle").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("tool-nav").performScrollTo()
            composeRule.onNodeWithTag("tool-nav").performClick()
            composeRule.waitForIdle()
            var winLabel = ""
            composeRule.activityRule.scenario.onActivity { act ->
                winLabel = act.getString(dev.openclip.R.string.menu_window)
            }
            var wsLabel = ""
            composeRule.activityRule.scenario.onActivity { act ->
                wsLabel = act.getString(dev.openclip.R.string.workspace_save_title)
            }
            composeRule.waitForIdle()
            // Save as "Test" through the dialog.
            composeRule.onNodeWithText(winLabel).performClick()
            composeRule.onNode(hasText(wsLabel).and(hasClickAction())).performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("workspace-name").performTextInput("Test")
            composeRule.onNodeWithTag("workspace-save").performClick()
            composeRule.waitForIdle()
            // Apply Drawing, then re-apply Test: all 6 fields back.
            assertTrue(hookApplyWorkspace(drawName))
            composeRule.waitForIdle()
            assertTrue(hookApplyWorkspace("Test"))
            composeRule.waitForIdle()
            val pose = hookPose()
            assertNotNull("pose must be readable", pose)
            assertEquals(false, pose!!.dockOpen)
            assertEquals(true, pose.timelineOpen)
            assertEquals(true, pose.navMode)
            assertEquals(true, pose.commandBarVisible)
            assertEquals(0, pose.dockTab)
            assertEquals(
                listOf("save", "undo", "redo", "export", "fullscreen"),
                pose.commandBarOrder
            )
            composeRule.onNodeWithTag("timeline-drawer").assertIsDisplayed()
            // Recreate: last workspace restored.
            composeRule.activityRule.scenario.recreate()
            composeRule.waitForIdle()
            awaitHooks()
            Thread.sleep(800)
            val pose2 = hookPose()
            assertNotNull("pose must survive recreate", pose2)
            assertEquals(false, pose2!!.dockOpen)
            assertEquals(true, pose2.timelineOpen)
            assertEquals(true, pose2.navMode)
            // Corrupt JSON: factory presets, no crash.
            composeRule.activityRule.scenario.onActivity { act ->
                File(act.filesDir, "workspaces.json").writeText("{broken")
                act.activityTestHooks.reloadWorkspaces?.invoke()
            }
            composeRule.waitForIdle()
            assertTrue(hookApplyWorkspace(drawName))
            val pose3 = hookPose()
            assertNotNull(pose3)
            assertEquals(true, pose3!!.dockOpen)
            assertEquals(false, pose3.timelineOpen)
            assertEquals(false, pose3.navMode)
            // Re-save "Test" on the Animation pose, then delete the
            // current workspace through the dialog: falls back to Drawing.
            assertTrue(hookApplyWorkspace(animName))
            composeRule.waitForIdle()
            composeRule.onNodeWithText(winLabel).performClick()
            composeRule.onNode(hasText(wsLabel).and(hasClickAction())).performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("workspace-name").performTextInput("Test")
            composeRule.onNodeWithTag("workspace-save").performClick()
            composeRule.waitForIdle()
            assertTrue(hookApplyWorkspace(drawName))
            assertTrue(hookApplyWorkspace("Test"))
            composeRule.onNodeWithText(winLabel).performClick()
            composeRule.onNode(hasText(wsLabel).and(hasClickAction())).performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("workspace-delete-Test").performClick()
            composeRule.waitForIdle()
            val pose4 = hookPose()
            assertNotNull(pose4)
            assertEquals(true, pose4!!.dockOpen)
            assertEquals(false, pose4.timelineOpen)
            assertEquals(false, pose4.navMode)
        } finally {
            composeRule.activityRule.scenario.onActivity { act ->
                try {
                    File(act.filesDir, "workspaces.json").delete()
                } catch (_: Exception) {
                }
                act.activityTestHooks.reloadWorkspaces?.invoke()
            }
            slice6Reset()
        }
    }

    // Slice 6.7: command bar hides/shows + persists, fullscreen overlays the
    // chrome without touching the workspace, touch targets >= 48dp.
    @Test
    fun slice6_commandBarPinAndFullscreen() {
        slice6Reset()
        try {
            // Edit mode toggle (⋯) hides export; long-press on the bar
            // enters the same mode (manual path).
            composeRule.onNodeWithTag("cmdbar-edit-toggle").performScrollTo()
            composeRule.onNodeWithTag("cmdbar-edit-toggle").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("cmdbar-edit").assertIsDisplayed()
            composeRule.onNodeWithTag("cmdbar-vis-export").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("cmdbar-export").assertDoesNotExist()
            // Order conserved across recreate (recreate flushes via onPause).
            Thread.sleep(900)
            composeRule.activityRule.scenario.recreate()
            composeRule.waitForIdle()
            awaitHooks()
            Thread.sleep(800)
            composeRule.onNodeWithTag("cmdbar-export").assertDoesNotExist()
            composeRule.onNodeWithTag("cmdbar-save").assertIsDisplayed()
            // Fullscreen: dock + timeline + bar gone, canvas alone.
            composeRule.onNodeWithTag("cmdbar-fullscreen").performScrollTo()
            composeRule.onNodeWithTag("cmdbar-fullscreen").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("dock-toggle").assertDoesNotExist()
            composeRule.onNodeWithTag("fullscreen-exit").assertIsDisplayed()
            // Exit restores the exact posture.
            composeRule.onNodeWithTag("fullscreen-exit").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("dock-toggle").assertIsDisplayed()
            composeRule.onNodeWithTag("cmdbar-save").assertIsDisplayed()
            // Touch targets >= 48dp (0.5dp tolerance).
            fun tall(tag: String) {
                composeRule.waitForIdle()
                val u = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
                val h = u.bottom - u.top
                assertTrue(
                    "$tag must be >= 48dp, got $h",
                    h >= (48.dp - 0.5.dp)
                )
            }
            for (doc in hookIds()) {
                tall("doc-tab-$doc")
                tall("doc-tab-close-$doc")
            }
            tall("doc-save")
            tall("cmdbar-save")
            tall("cmdbar-undo")
            tall("cmdbar-fullscreen")
        } finally {
            composeRule.activityRule.scenario.onActivity { act ->
                try {
                    File(act.filesDir, "commandbar.json").delete()
                } catch (_: Exception) {
                }
                act.activityTestHooks.commandSetVisible?.invoke("export", true)
            }
            slice6Reset()
        }
    }

    // Slice 6.8: closing the last doc shows an honest empty canvas; the
    // shell stays usable and + recreates a drawable doc.
    @Test
    fun slice6_closeLastDocShowsEmptyCanvas() {
        val a = slice6Reset()
        val esA = awaitDocReady(a)
        try {
            uiSync { it.navigateMode = false; it.resetView() }
            pushTestBrush(radius = 24f, curve = 0f)
            strokeAcross(
                esA, esA.height * 0.5f, SystemClock.uptimeMillis(),
                esA.width * 0.5f
            )
            awaitDirty(a, true)
            // Dirty close through the dialog (discard path).
            composeRule.onNodeWithTag("doc-tab-close-$a").performScrollTo()
            composeRule.onNodeWithTag("doc-tab-close-$a").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("doc-close-discard").performClick()
            composeRule.waitForIdle()
            assertEquals(0, awaitCount(0).size)
            awaitHandles(0)
            composeRule.onNodeWithTag("newcanvas-empty-cta").assertIsDisplayed()
            // Window + Help menus still open (no dead shell).
            var winLabel = ""
            var helpLabel = ""
            composeRule.activityRule.scenario.onActivity { act ->
                winLabel = act.getString(dev.openclip.R.string.menu_window)
                helpLabel = act.getString(dev.openclip.R.string.menu_help)
            }
            composeRule.onNodeWithText(winLabel).performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText(helpLabel).performClick()
            composeRule.waitForIdle()
            // + recreates a drawable doc.
            composeRule.onNodeWithTag("newcanvas-empty-cta").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("newcanvas-confirm").performClick()
            hookSync()
            val ids = awaitCount(1)
            assertEquals(1, ids.size)
            val esN = awaitDocReady(ids[0])
            awaitPaper(ids[0])
            pushTestBrush(radius = 24f, curve = 0f)
            strokeAcross(
                esN, esN.height * 0.5f, SystemClock.uptimeMillis() + 5000,
                esN.width * 0.5f
            )
            Thread.sleep(600)
            val nb = pullFull(esN)
            assertTrue("recreated doc must draw", countInk(nb) > 100)
            nb.recycle()
        } finally {
            slice6Reset()
        }
    }
}

private fun IntArray.contentNotEquals(other: IntArray): Boolean {
    if (size != other.size) return true
    for (i in indices) if (this[i] != other[i]) return true
    return false
}
