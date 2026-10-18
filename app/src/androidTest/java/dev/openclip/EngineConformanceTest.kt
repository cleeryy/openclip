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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openclip.engine.EngineHost
import dev.openclip.media.AiUpscaler
import dev.openclip.media.ClipAudio
import dev.openclip.media.ExportWebm
import dev.openclip.media.WebdavClient
import dev.openclip.ui.EngineSurface
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

    // Regression gate for tablet UI + touch pipeline: the live EngineSurface
    // on MainActivity must have substantial visible dimensions (never crushed
    // to 0px by panels), and a real MotionEvent DOWN -> MOVE -> UP stroke
    // must draw ink into the active frame and be undoable.
    @Test
    fun canvasSurfaceHasVisibleSizeAndTouchDrawsStroke() {
        composeRule.waitForIdle()
        var surface: EngineSurface? = null
        composeRule.activityRule.scenario.onActivity { act ->
            surface = findEngineSurface(act.window.decorView)
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
}

private fun IntArray.contentNotEquals(other: IntArray): Boolean {
    if (size != other.size) return true
    for (i in indices) if (this[i] != other[i]) return true
    return false
}
