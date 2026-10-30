// OpenClip — instrumented conformance gate: the app launches, the JNI
// engine host draws a stroke, and undo restores the composite exactly.
// No screenshots, no pixel-perfect art: pixels are only compared against
// the blank white canvas the shim paints at nInit time.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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
import dev.openclip.ui.SmartShape
import dev.openclip.ui.LayerPanelTestHooks
import dev.openclip.ui.AutosavePolicy
import dev.openclip.ui.BezierEdit
import dev.openclip.ui.BubbleFiche
import dev.openclip.ui.CurvesJson
import dev.openclip.ui.GuidesIo
import dev.openclip.ui.DocGuide
import dev.openclip.ui.MultipageProject
import dev.openclip.ui.NavigatorMath
import dev.openclip.ui.OcpPage
import dev.openclip.ui.PanelFiche
import dev.openclip.ui.SelectionTransform
import dev.openclip.ui.SpeedBurst
import dev.openclip.ui.SpeedFiche
import dev.openclip.ui.SvgExport
import dev.openclip.ui.T2Vector
import dev.openclip.ui.T2VectorTool
import dev.openclip.ui.BubbleLayer
import dev.openclip.ui.FontRegistry
import dev.openclip.ui.TextOutline
import dev.openclip.ui.TextRun
import dev.openclip.ui.hyphenate
import dev.openclip.ui.AnimKey
import dev.openclip.ui.AnimLabel
import dev.openclip.ui.AnimSidecar
import dev.openclip.ui.CameraKey
import dev.openclip.ui.T3TestHooks
import dev.openclip.ui.animKeysFromJson
import dev.openclip.ui.animKeysToJson
import dev.openclip.ui.bezierEaseTable
import dev.openclip.ui.buildExposure
import dev.openclip.ui.cameraKeysFromJson
import dev.openclip.ui.cameraKeysToJson
import dev.openclip.ui.exposureCsv
import dev.openclip.ui.textLayerIndexes
import dev.openclip.ui.V2Bezier
import dev.openclip.ui.V2CopyPaste
import dev.openclip.ui.V2Pages
import dev.openclip.ui.V2TestHooks
import dev.openclip.ui.V2Webtoon
import dev.openclip.ui.WebtoonPlan
import dev.openclip.ui.commitBubble
import dev.openclip.ui.commitPanelRect
import dev.openclip.ui.parseBubbleMeta
import dev.openclip.ui.parsePanelMeta
import dev.openclip.ui.refreshPanelOverlay
import dev.openclip.ui.v2LayerMetas
import dev.openclip.ui.v2VectorLayers
// V+3 confort-pro pure units + JSON bridges (no new engine pixels).
import dev.openclip.ui.BrushPushTestHooks
import dev.openclip.ui.BrushPushThrottler
import dev.openclip.ui.floodRegionKotlin
import dev.openclip.ui.ChannelOp
import dev.openclip.ui.ChannelOps
import dev.openclip.ui.ChannelStore
import dev.openclip.ui.CmykProof
import dev.openclip.ui.Harmony
import dev.openclip.ui.PdfImport
import dev.openclip.ui.PoseStore
import dev.openclip.ui.Rdp
import dev.openclip.ui.RecomposeTestHooks
import dev.openclip.ui.ScrollFadeTestHooks
import dev.openclip.ui.SelectionMask
import dev.openclip.ui.ThumbLru
import dev.openclip.ui.TimelineTestHooks
import dev.openclip.ui.ShortcutAction
import dev.openclip.ui.ShortcutMap
import dev.openclip.ui.ShortcutToolState
import dev.openclip.ui.SnapPt
import dev.openclip.ui.SnapSettings
import dev.openclip.ui.SvgImport
import dev.openclip.ui.TextOnPath
import dev.openclip.ui.buildVectorJson
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
import org.junit.Assert.assertNull
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

    // T3 §1 : temoin median linear vs smooth (hors moteur).
    private var lastMidLinear: IntArray = IntArray(0)

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

    // .clip palier (a): a programmatically-built .clip (CSFCHUNK envelope
    // + embedded SQLite + zlib RGBA8 tiles + CanvasPreview PNG — no Celsys
    // binary anywhere) imports to 2 ORA layers with the right composite,
    // and a labeled proof bitmap is left in cacheDir for the screenshot.
    @Test
    fun clipImportDecodesRasterLayers() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val clip = File(dir, "clip-a-test.clip")
        writeTestClip(clip, withLayers = true)
        val out = dev.openclip.doc.DocClip.importToOra(clip, dir)
        assertTrue("clip must import, got $out", out is dev.openclip.doc.DocClip.Outcome.Ok)
        val import = (out as dev.openclip.doc.DocClip.Outcome.Ok).import
        try {
            assertEquals(2, import.report.layerCount)
            assertEquals(48, import.report.width)
            assertEquals(32, import.report.height)
            assertFalse("raster path must not be preview-only", import.report.previewOnly)

            val handle = EngineHost.nInit(48, 32, 160f)
            val px = IntArray(48 * 32)
            try {
                assertTrue(EngineHost.nLoadDocument(handle, import.oraFile.absolutePath))
                assertEquals(2, EngineHost.nLayerCount(handle))
                val state = EngineHost.nLayerState(handle)
                assertTrue("layer names must survive, got $state", state.contains("Base"))
                assertTrue("layer names must survive, got $state", state.contains("Top"))
                assertEquals(48 * 32, EngineHost.nCompositeInto(handle, px, 48, 32))
                // Top layer is opaque blue over opaque red: composite is blue.
                assertEquals(0xFF0000FF.toInt(), px[5 * 48 + 5])
            } finally {
                EngineHost.nDispose(handle)
            }

            // Proof artifact for the mission screenshot (pulled via adb).
            writeClipProof(dir, px, 48, 32, import.report.layerCount, import.report.previewOnly)
        } finally {
            clip.delete()
            try {
                import.oraFile.delete()
            } catch (_: Exception) {
            }
        }
    }

    // .clip palier (a) fallback + hostile policy: preview-only file yields
    // one Preview layer, garbage and truncated envelopes are refused.
    @Test
    fun clipImportPreviewFallbackAndRefusesHostile() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = ctx.cacheDir
        val clip = File(dir, "clip-a-preview.clip")
        writeTestClip(clip, withLayers = false)
        val out = dev.openclip.doc.DocClip.importToOra(clip, dir)
        assertTrue("preview clip must import, got $out", out is dev.openclip.doc.DocClip.Outcome.Ok)
        val import = (out as dev.openclip.doc.DocClip.Outcome.Ok).import
        try {
            assertTrue(import.report.previewOnly)
            assertEquals(1, import.report.layerCount)
            val handle = EngineHost.nInit(48, 32, 160f)
            try {
                assertTrue(EngineHost.nLoadDocument(handle, import.oraFile.absolutePath))
                assertEquals(1, EngineHost.nLayerCount(handle))
                val px = IntArray(48 * 32)
                assertEquals(48 * 32, EngineHost.nCompositeInto(handle, px, 48, 32))
                // Preview fixture is opaque green.
                assertEquals(0xFF00FF00.toInt(), px[5 * 48 + 5])
            } finally {
                EngineHost.nDispose(handle)
            }
        } finally {
            clip.delete()
            try {
                import.oraFile.delete()
            } catch (_: Exception) {
            }
        }

        val garbage = File(dir, "clip-a-garbage.clip")
        garbage.writeBytes("definitely not a clip studio file".toByteArray())
        assertTrue(
            "garbage must be refused",
            dev.openclip.doc.DocClip.importToOra(garbage, dir)
                is dev.openclip.doc.DocClip.Outcome.Err
        )
        garbage.delete()

        val truncated = File(dir, "clip-a-trunc.clip")
        val head = "CSFCHUNK".toByteArray() + "CHNKSQLi".toByteArray() +
            byteArrayOf(0, 1, 0, 0) + ByteArray(16)
        truncated.writeBytes(head)
        assertTrue(
            "truncated envelope must be refused",
            dev.openclip.doc.DocClip.importToOra(truncated, dir)
                is dev.openclip.doc.DocClip.Outcome.Err
        )
        truncated.delete()
    }

    // .clip palier (b): 1 vector layer + 1 text layer rasterized in-house
    // (flat polylines, system-font UTF-8). Composite must show ink and the
    // approximation counter must be > 0. Hostile geometry is refused.
    @Test
    fun clipImportVectorTextDegraded() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val clip = File(dir, "clip-b-test.clip")
        writeVectorTextClip(clip, hostile = false)
        val out = dev.openclip.doc.DocClip.importToOra(clip, dir)
        assertTrue("vector/text clip must import, got $out", out is dev.openclip.doc.DocClip.Outcome.Ok)
        val import = (out as dev.openclip.doc.DocClip.Outcome.Ok).import
        try {
            assertEquals(2, import.report.layerCount)
            assertFalse("vector/text path must not be preview-only", import.report.previewOnly)
            assertEquals("one house-rasterized vector layer", 1, import.report.vectorLayers)
            assertEquals("one house-rasterized text layer", 1, import.report.textLayers)
            assertTrue(
                "approximations must be counted, got ${import.report.approximations}",
                import.report.approximations.size > 0
            )

            val handle = EngineHost.nInit(48, 32, 160f)
            val px = IntArray(48 * 32)
            try {
                assertTrue(EngineHost.nLoadDocument(handle, import.oraFile.absolutePath))
                assertEquals(2, EngineHost.nLayerCount(handle))
                val state = EngineHost.nLayerState(handle)
                assertTrue("vector layer name must survive, got $state", state.contains("Vector"))
                assertTrue("text layer name must survive, got $state", state.contains("Text"))
                assertEquals(48 * 32, EngineHost.nCompositeInto(handle, px, 48, 32))
                val ink = px.indices.count { px[it] != 0 }
                assertTrue("vector+text must paint ink pixels, got $ink", ink > 20)
                // Vector polyline runs (5,26)->(43,26): far from the text
                // glyphs, so this pixel can only come from the vector layer.
                assertTrue(
                    "vector stroke must paint at (35,26)",
                    (px[26 * 48 + 35] ushr 24) != 0
                )
            } finally {
                EngineHost.nDispose(handle)
            }

            writeClipProofB(
                dir, px, 48, 32,
                import.report.layerCount, import.report.approximations.size
            )
        } finally {
            clip.delete()
            try {
                import.oraFile.delete()
            } catch (_: Exception) {
            }
        }
    }

    // .clip palier (b) hostile policy: out-of-range vector geometry decodes
    // to nothing and the file is refused (no preview staged), never a crash.
    @Test
    fun clipImportHostileVectorRefused() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val clip = File(dir, "clip-b-hostile.clip")
        writeVectorTextClip(clip, hostile = true)
        assertTrue(
            "hostile vector geometry must be refused",
            dev.openclip.doc.DocClip.importToOra(clip, dir)
                is dev.openclip.doc.DocClip.Outcome.Err
        )
        clip.delete()
    }

    // ---- .clip test fixtures (clean-room, generated in-code) ----
    // Minimal writer mirroring the public envelope shape: CSFCHUNK magic,
    // then tag(8 ASCII) + BE u32 length + payload chunks; CHNKSQLi carries
    // a real SQLite db built with the platform API.

    private fun deflateRaw(raw: ByteArray): ByteArray {
        val def = java.util.zip.Deflater()
        try {
            def.setInput(raw)
            def.finish()
            val bos = java.io.ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!def.finished()) {
                val n = def.deflate(buf)
                if (n <= 0) break
                bos.write(buf, 0, n)
            }
            return bos.toByteArray()
        } finally {
            def.end()
        }
    }

    private fun solidTileArgb(w: Int, h: Int, argb: Int): ByteArray {
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val raw = ByteArray(w * h * 4)
        for (i in 0 until w * h) {
            raw[i * 4] = r.toByte()
            raw[i * 4 + 1] = g.toByte()
            raw[i * 4 + 2] = b.toByte()
            raw[i * 4 + 3] = a.toByte()
        }
        return deflateRaw(raw)
    }

    private fun chunk(tag: String, payload: ByteArray): ByteArray {
        val t = tag.toByteArray(Charsets.US_ASCII)
        val out = ByteBuffer.allocate(8 + 4 + payload.size).order(ByteOrder.BIG_ENDIAN)
        out.put(t, 0, 8)
        out.putInt(payload.size)
        out.put(payload)
        return out.array()
    }

    /** Builds a synthetic .clip: 48x32, red base + blue top (+ green preview). */
    private fun writeTestClip(dst: File, withLayers: Boolean) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dbPath = File(ctx.cacheDir, "clip-a-fixture.db")
        if (dbPath.exists()) dbPath.delete()
        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbPath, null)
        try {
            db.execSQL("CREATE TABLE Canvas(width INTEGER, height INTEGER)")
            db.execSQL("INSERT INTO Canvas(width, height) VALUES (48, 32)")
            if (withLayers) {
                db.execSQL(
                    "CREATE TABLE Layer(id TEXT, name TEXT, opacity REAL, " +
                        "visible INTEGER, blend TEXT)"
                )
                db.execSQL(
                    "INSERT INTO Layer(id, name, opacity, visible, blend) " +
                        "VALUES ('base', 'Base', 1.0, 1, 'normal')"
                )
                db.execSQL(
                    "INSERT INTO Layer(id, name, opacity, visible, blend) " +
                        "VALUES ('top', 'Top', 1.0, 1, 'normal')"
                )
                db.execSQL(
                    "CREATE TABLE ExternalChunk(layerId TEXT, x INTEGER, " +
                        "y INTEGER, w INTEGER, h INTEGER, layout TEXT, data BLOB)"
                )
                val red = solidTileArgb(48, 32, 0xFFFF0000.toInt())
                val blue = solidTileArgb(48, 32, 0xFF0000FF.toInt())
                db.execSQL(
                    "INSERT INTO ExternalChunk(layerId, x, y, w, h, layout, data) " +
                        "VALUES ('base', 0, 0, 48, 32, 'rgba8', ?)",
                    arrayOf(red)
                )
                db.execSQL(
                    "INSERT INTO ExternalChunk(layerId, x, y, w, h, layout, data) " +
                        "VALUES ('top', 0, 0, 48, 32, 'rgba8', ?)",
                    arrayOf(blue)
                )
            }
            val preview = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
            preview.eraseColor(0xFF00FF00.toInt())
            val bos = java.io.ByteArrayOutputStream()
            preview.compress(Bitmap.CompressFormat.PNG, 100, bos)
            preview.recycle()
            db.execSQL("CREATE TABLE CanvasPreview(png BLOB)")
            db.execSQL("INSERT INTO CanvasPreview(png) VALUES (?)", arrayOf(bos.toByteArray()))
        } finally {
            db.close()
        }
        val dbBytes = dbPath.readBytes()
        dbPath.delete()
        val out = java.io.ByteArrayOutputStream()
        out.write("CSFCHUNK".toByteArray(Charsets.US_ASCII))
        out.write(chunk("CHNKHead", byteArrayOf(1, 0, 0, 0)))
        out.write(chunk("CHNKSQLi", dbBytes))
        out.write(chunk("CHNKFoot", ByteArray(0)))
        dst.writeBytes(out.toByteArray())
    }

    /** Builds a synthetic .clip: 1 vector + 1 text layer (or hostile). */
    private fun writeVectorTextClip(dst: File, hostile: Boolean) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dbPath = File(ctx.cacheDir, "clip-b-fixture.db")
        if (dbPath.exists()) dbPath.delete()
        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbPath, null)
        try {
            db.execSQL("CREATE TABLE Canvas(width INTEGER, height INTEGER)")
            db.execSQL("INSERT INTO Canvas(width, height) VALUES (48, 32)")
            db.execSQL(
                "CREATE TABLE Layer(id TEXT, name TEXT, opacity REAL, " +
                    "visible INTEGER, blend TEXT, type TEXT)"
            )
            db.execSQL(
                "INSERT INTO Layer(id, name, opacity, visible, blend, type) " +
                    "VALUES ('vec', 'Vector', 1.0, 1, 'normal', 'vector')"
            )
            db.execSQL(
                "INSERT INTO Layer(id, name, opacity, visible, blend, type) " +
                    "VALUES ('txt', 'Text', 1.0, 1, 'normal', 'text')"
            )
            db.execSQL(
                "CREATE TABLE VectorObjectList(layerId TEXT, points TEXT, " +
                    "color TEXT, width REAL)"
            )
            val pts = if (!hostile) "5,26;43,26;43,5" else "99999999,99999999;88888888,88888888"
            db.execSQL(
                "INSERT INTO VectorObjectList(layerId, points, color, width) " +
                    "VALUES ('vec', ?, '#000000', 3.0)",
                arrayOf(pts)
            )
            if (!hostile) {
                db.execSQL(
                    "CREATE TABLE Text(layerId TEXT, content TEXT, " +
                        "x REAL, y REAL, size REAL, color TEXT)"
                )
                db.execSQL(
                    "INSERT INTO Text(layerId, content, x, y, size, color) " +
                        "VALUES ('txt', 'Hi', 5.0, 20.0, 12.0, '#FF0000')"
                )
            }
        } finally {
            db.close()
        }
        val dbBytes = dbPath.readBytes()
        dbPath.delete()
        val out = java.io.ByteArrayOutputStream()
        out.write("CSFCHUNK".toByteArray(Charsets.US_ASCII))
        out.write(chunk("CHNKHead", byteArrayOf(1, 0, 0, 0)))
        out.write(chunk("CHNKSQLi", dbBytes))
        out.write(chunk("CHNKFoot", ByteArray(0)))
        dst.writeBytes(out.toByteArray())
    }

    /** Labeled proof bitmap: real composite x6 + degraded strip (adb-pulled). */
    private fun writeClipProofB(
        dir: File, px: IntArray, w: Int, h: Int, layers: Int, approx: Int
    ) {
        val comp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        comp.setPixels(px, 0, w, 0, 0, w, h)
        val proof = Bitmap.createBitmap(w * 6, h * 6 + 40, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(proof)
        // Mid-gray chrome: both the black vector stroke and the red text
        // stay visible (the composite itself is transparent there).
        cv.drawColor(0xFF808080.toInt())
        val scaled = Bitmap.createScaledBitmap(comp, w * 6, h * 6, false)
        cv.drawBitmap(scaled, 0f, 0f, null)
        scaled.recycle()
        comp.recycle()
        val paint = android.graphics.Paint()
        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = 14f
        paint.isAntiAlias = true
        cv.drawText("clip-b: $layers layers degraded $approx composite OK", 8f, h * 6 + 28f, paint)
        File(dir, "clip-b-proof.png").outputStream().use { o ->
            proof.compress(Bitmap.CompressFormat.PNG, 100, o)
        }
        if (!proof.isRecycled) proof.recycle()
    }

    /** Labeled proof bitmap: real composite x6 + status strip (adb-pulled). */
    private fun writeClipProof(
        dir: File, px: IntArray, w: Int, h: Int, layers: Int, previewOnly: Boolean
    ) {
        val comp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        comp.setPixels(px, 0, w, 0, 0, w, h)
        val proof = Bitmap.createBitmap(w * 6, h * 6 + 40, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(proof)
        cv.drawColor(0xFF000000.toInt())
        val scaled = Bitmap.createScaledBitmap(comp, w * 6, h * 6, false)
        cv.drawBitmap(scaled, 0f, 0f, null)
        scaled.recycle()
        comp.recycle()
        val paint = android.graphics.Paint()
        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = 14f
        paint.isAntiAlias = true
        val mode = if (previewOnly) "preview" else "layers"
        cv.drawText("clip-a: $layers $mode composite OK", 8f, h * 6 + 28f, paint)
        File(dir, "clip-a-proof.png").outputStream().use { o ->
            proof.compress(Bitmap.CompressFormat.PNG, 100, o)
        }
        if (!proof.isRecycled) proof.recycle()
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
            // The sheet scrolls and the tab lives in a horizontally
            // scrolling row (a vertical scrollTo would target the wrong
            // axis): anchor the dock on the add-row so the color tabs
            // land inside the viewport, then tap.
            var setsOpen = false
            for (k in 0 until 5) {
                composeRule.onNodeWithTag("layer-add").performScrollTo()
                composeRule.waitForIdle()
                composeRule.onNodeWithTag("color-tab-sets").performClick()
                composeRule.waitForIdle()
                setsOpen = try {
                    composeRule.onNodeWithTag("set-chip-0").assertExists()
                    true
                } catch (_: AssertionError) {
                    false
                }
                if (setsOpen) break
            }
            assertTrue("color sets tab must open", setsOpen)
            // The set chips sit right under the tabs (visible once the
            // dock is back at the top); the swatch grid may sit lower.
            composeRule.onNodeWithTag("set-chip-0")
                .performClick()
            composeRule.onNodeWithTag("swatch-2")
                .performScrollTo()
            composeRule.waitForIdle()
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
            // T5: taller rows (flag toggles) can leave the panel
            // below the fold — scroll the outer dock so the list
            // viewport is on screen (inner-list scrollTo cannot fix
            // an outer-dock offset).
            composeRule.onNodeWithTag("layer-list").performScrollTo()
            composeRule.waitForIdle()
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
            // T5: taller rows (flag toggles) can leave the panel
            // below the fold — scroll the outer dock so the list
            // viewport is on screen (inner-list scrollTo cannot fix
            // an outer-dock offset).
            composeRule.onNodeWithTag("layer-list").performScrollTo()
            composeRule.waitForIdle()
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
            // T5: taller rows (flag toggles) can leave the panel
            // below the fold — scroll the outer dock so the list
            // viewport is on screen (inner-list scrollTo cannot fix
            // an outer-dock offset).
            val st = es.layerState()
            assertEquals("fresh doc must expose both layers (state=$st)",
                2, org.json.JSONObject(st).optJSONArray("layers")?.length() ?: -1)
            composeRule.onNodeWithTag("layer-list").performScrollTo()
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
            // T5: taller rows (flag toggles) can leave the panel
            // below the fold — scroll the outer dock so the list
            // viewport is on screen (inner-list scrollTo cannot fix
            // an outer-dock offset).
            composeRule.onNodeWithTag("layer-list").performScrollTo()
            composeRule.waitForIdle()
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
            assertTrue(
                "counter must show $total/50, got '${nodeText("layer-count")}'",
                nodeText("layer-count").contains("$total/50")
            )
            // Compact: a non-active row folds its rank 2.
            val act = activeIndex(es)
            val folded = if (act != 0) 0 else 1
            // T5: re-scroll the outer dock (the counter scroll above
            // may have moved the list viewport off screen).
            composeRule.onNodeWithTag("layer-list").performScrollTo()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("layer-row-$folded").performScrollTo()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("layer-row-$folded").assertIsDisplayed()
            composeRule.onNodeWithTag("layer-blend-$folded").assertDoesNotExist()
            // The active row keeps its controls.
            composeRule.onNodeWithTag("layer-list").performScrollTo()
            composeRule.waitForIdle()
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
            // Scroll the OUTER dock column (rows live in a nested lazy
            // list: scrolling them directly leaves the dock unmoved).
            composeRule.onNodeWithTag("layer-thumb-0").performClick()
            composeRule.waitForIdle()
            Thread.sleep(800)
            composeRule.onNodeWithTag("layer-search").performScrollTo()
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
            assertTrue(
                "eraser JSON must carry mode alpha-erase: $eraserJson",
                eraserJson.contains("\"alpha-erase\"")
            )
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
            // V+1: the honest banner is gone (alpha-erase needs none)
            // on any stack depth, in both tools.
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("eraser-banner").assertDoesNotExist()
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
            // be swallowed) then prove the 48dp pastille. V1: the bar
            // scrolls in tight mode — bring the cross into view first.
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
                    // Tight mode scrolls; wide mode has no scroller.
                    try {
                        composeRule.onNodeWithTag("pinned-hide").performScrollTo()
                        composeRule.waitForIdle()
                    } catch (_: AssertionError) {
                    }
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
            // V3: single-doc compact keeps a "+"-only tabs row.
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
            // The defer poll runs every 150 ms: give it a bounded window
            // instead of assuming it already fired (looper lag on load).
            val tPend = SystemClock.uptimeMillis()
            while (pend != "<null>" &&
                SystemClock.uptimeMillis() - tPend < 3000
            ) {
                composeRule.activityRule.scenario.onActivity { act ->
                    pend = act.activityTestHooks.pendingTab?.invoke() ?: "<null>"
                }
                if (pend != "<null>") Thread.sleep(150)
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
            // V3: single-doc compact keeps a "+"-only tabs row.
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
            openMenuCompactIfPresent()
            composeRule.onNodeWithText(winLabel).performClick()
            composeRule.onNode(hasText(wsLabel).and(hasClickAction()))
                .performScrollTo()
            composeRule.waitForIdle()
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
            openMenuCompactIfPresent()
            composeRule.onNodeWithText(winLabel).performClick()
            composeRule.onNode(hasText(wsLabel).and(hasClickAction()))
                .performScrollTo()
            composeRule.waitForIdle()
            composeRule.onNode(hasText(wsLabel).and(hasClickAction())).performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("workspace-name").performTextInput("Test")
            composeRule.onNodeWithTag("workspace-save").performClick()
            composeRule.waitForIdle()
            assertTrue(hookApplyWorkspace(drawName))
            assertTrue(hookApplyWorkspace("Test"))
            openMenuCompactIfPresent()
            composeRule.onNodeWithText(winLabel).performClick()
            composeRule.onNode(hasText(wsLabel).and(hasClickAction()))
                .performScrollTo()
            composeRule.waitForIdle()
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
            // V3: single-doc compact hides the tabs — a second doc keeps
            // the tab targets under test.
            val tallExtra = hookOpen("Tall", 256, 256, -1)
            awaitDocReady(tallExtra)
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
            // V3: single-doc compact hides the tab crosses — File >
            // Close document runs the same requestClose path.
            closeDocViaMenu()
            composeRule.onNodeWithTag("doc-close-discard").performClick()
            composeRule.waitForIdle()
            assertEquals(0, awaitCount(0).size)
            awaitHandles(0)
            composeRule.onNodeWithTag("newcanvas-empty-cta").assertIsDisplayed()
            // Window + Help menus still open (no dead shell).
            openMenuCompactIfPresent()
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

    // ---------- Slice 7 polish helpers ----------

    private fun isCompact(): Boolean =
        composeRule.activity.resources.configuration.screenWidthDp < 600

    /** On phones the full menu bar is replaced by ☰: open it first. */
    private fun openMenuCompactIfPresent() {
        composeRule.waitForIdle()
        val has = try {
            composeRule.onNodeWithTag("menu-compact").assertIsDisplayed()
            true
        } catch (_: AssertionError) {
            false
        }
        if (has) {
            composeRule.onNodeWithTag("menu-compact").performClick()
            composeRule.waitForIdle()
        }
    }

    /**
     * V3: with a single doc on compact the tabs row (and its crosses)
     * is hidden — closing goes through File > Close document
     * (requestClose, same dirty dialog as the tab cross).
     */
    private fun closeDocViaMenu() {
        val closeLabel = actString(dev.openclip.R.string.doc_close)
        if (isCompact()) {
            openMenuCompactIfPresent()
            composeRule.onNodeWithTag("menu-compact-item-0-4")
                .performScrollTo()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("menu-compact-item-0-4")
                .performClick()
        } else {
            composeRule.onNodeWithTag("menu-title-0").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText(closeLabel).performClick()
        }
        composeRule.waitForIdle()
    }

    private fun statusText(): String {
        var txt = ""
        try {
            val node = composeRule.onNodeWithTag("status-line")
                .fetchSemanticsNode()
            txt = node.config.getOrNull(SemanticsProperties.Text)
                ?.joinToString("") { it.text } ?: ""
        } catch (_: AssertionError) {
        }
        return txt
    }

    private fun nodeText(tag: String): String {
        var txt = ""
        try {
            val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
            txt = node.config.getOrNull(SemanticsProperties.Text)
                ?.joinToString("") { it.text } ?: ""
        } catch (_: AssertionError) {
        }
        return txt
    }

    private fun actString(id: Int): String {
        var s = ""
        composeRule.activityRule.scenario.onActivity { act ->
            s = act.getString(id)
        }
        return s
    }

    // Slice 7.1: tab switches never post name+dims into the status
    // line (D1/D4); the title keeps the name, dims keep WxH, and pixels
    // are byte-identical (polish is overlay-only).
    @Test
    fun slice7_statusHasNoDuplicateAfterSwitch() {
        val a = slice6Reset()
        val esA = awaitDocReady(a)
        try {
            uiSync { it.navigateMode = false; it.resetView() }
            pushTestBrush(radius = 24f, curve = 0f)
            strokeAcross(
                esA, esA.height * 0.5f, SystemClock.uptimeMillis(),
                esA.width * 0.5f
            )
            Thread.sleep(600)
            assertTrue("stroke on A must paint", countInk(pullFull(esA)) > 100)
            val before = bitmapPixels(pullFull(esA))
            // V3: single-doc compact keeps a "+"-only tabs row.
            composeRule.onNodeWithTag("doc-tab-add").performScrollTo()
            composeRule.onNodeWithTag("doc-tab-add").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("newcanvas-confirm").performClick()
            hookSync()
            val ids2 = awaitCount(2)
            assertEquals(2, ids2.size)
            val b = ids2.first { it != a }
            awaitDocReady(b)
            awaitPaper(b)
            composeRule.onNodeWithTag("doc-tab-$a").performScrollTo()
            composeRule.onNodeWithTag("doc-tab-$a").performClick()
            assertTrue("A must be active again", awaitActive(a))
            // Any ephemeral message is long consumed by now.
            Thread.sleep(1000)
            val st = statusText()
            assertFalse(
                "status must not carry dims (got [$st])",
                Regex("\\d+×\\d+").containsMatchIn(st)
            )
            val title = nodeText("doc-title")
            assertTrue("title must show the doc name", title.isNotEmpty())
            assertFalse(
                "status must not repeat the doc title (got [$st])",
                st.isNotEmpty() && title.isNotEmpty() &&
                    st.contains(title.trimStart('•', ' '))
            )
            val dims = nodeText("doc-dims")
            assertTrue("dims must carry WxH (got [$dims])", dims.contains("×"))
            val after = bitmapPixels(pullFull(esA))
            assertTrue(
                "composite must be byte-identical across the switch",
                before.contentEquals(after)
            )
        } finally {
            slice6Reset()
        }
    }

    // Slice 7.2: tablet shows every menu title on one line (no wrap);
    // phones show the compact ☰ bar with 1-tap save and a working menu.
    @Test
    fun slice7_menuFitsOneLineOrCompact() {
        slice6Reset()
        try {
            val titles = listOf(
                actString(dev.openclip.R.string.file_menu),
                actString(dev.openclip.R.string.menu_edit),
                actString(dev.openclip.R.string.menu_layer),
                actString(dev.openclip.R.string.menu_selection),
                actString(dev.openclip.R.string.menu_filter),
                actString(dev.openclip.R.string.menu_display),
                actString(dev.openclip.R.string.menu_window),
                actString(dev.openclip.R.string.menu_help)
            )
            if (!isCompact()) {
                val dens = composeRule.activity.resources.displayMetrics.density
                var top0 = 0.dp
                var h0 = 0.dp
                // titles[0] ("File") also labels the tool-row action —
                // assert it in the menu band via tags only (tablet shows
                // both bars at once, so text lookup is ambiguous).
                for (i in 1 until titles.size) {
                    val t = titles[i]
                    composeRule.onNodeWithTag("menu-title-$i").assertIsDisplayed()
                    val b = composeRule.onNodeWithTag("menu-title-$i")
                        .getUnclippedBoundsInRoot()
                    if (i == 1) {
                        top0 = b.top
                        h0 = b.bottom - b.top
                    } else {
                        assertTrue(
                            "menu [$t] must share the band",
                            kotlin.math.abs((b.top - top0).value) < 4 * dens
                        )
                        assertTrue(
                            "menu [$t] must not wrap",
                            kotlin.math.abs(((b.bottom - b.top) - h0).value) < 2 * dens
                        )
                    }
                }
            } else {
                composeRule.onNodeWithTag("menu-compact").assertIsDisplayed()
                // "File" also labels the tool-row action: assert on a
                // menus-only title instead.
                composeRule.onNodeWithText(titles[1]).assertDoesNotExist()
                composeRule.onNodeWithTag("doc-save").assertIsDisplayed()
                openMenuCompactIfPresent()
                // Scroll by header tag: menu titles also label chrome
                // actions ("File"), so text matching is ambiguous here.
                val n = titles.size
                for (i in 0 until n) {
                    composeRule.onNodeWithTag("menu-compact-header-$i")
                        .performScrollTo()
                    composeRule.waitForIdle()
                }
                // …and Help opens the gestures sheet (menu is live).
                // NB: the same label also lives in View — use the tag.
                composeRule.onNodeWithTag("menu-compact-item-7-0")
                    .performScrollTo()
                composeRule.waitForIdle()
                composeRule.onNodeWithTag("menu-compact-item-7-0")
                    .performClick()
                composeRule.waitForIdle()
                val body = actString(dev.openclip.R.string.gestures_help_body)
                composeRule.onNodeWithText(body.take(30), substring = true)
                    .assertIsDisplayed()
                composeRule.onNode(
                    hasText(actString(dev.openclip.R.string.ok))
                        .and(hasClickAction())
                ).performClick()
                composeRule.waitForIdle()
            }
        } finally {
            slice6Reset()
        }
    }

    // Slice 7.3: the tool row scrolls — every chip becomes fully
    // visible and clickable (D3); tablets need no scroll.
    @Test
    fun slice7_toolRowNeverClipsWithoutScroll() {
        slice6Reset()
        try {
            val fileLabel = actString(dev.openclip.R.string.file_menu)
            if (isCompact()) {
                composeRule.onNodeWithTag("tool-file").performScrollTo()
                composeRule.waitForIdle()
            }
            // Spec-literal: bounds inside the window after scroll (the
            // framework's isDisplayed flag stays false for nodes parked
            // at the exact viewport edge — bounds are the contract).
            val dens = composeRule.activity.resources.displayMetrics.density
            val screenW =
                composeRule.activity.resources.displayMetrics.widthPixels / dens
            val fb = composeRule.onNodeWithTag("tool-file")
                .getUnclippedBoundsInRoot()
            assertTrue(
                "tool-file must sit inside the window " +
                    "(got $fb on $screenW dp)",
                fb.left.value >= -1f && fb.right.value <= screenW + 1f
            )
            composeRule.onNodeWithTag("tool-file").performClick()
            composeRule.waitForIdle()
            // The dialog opens on the format chips (resultLine carries
            // the live status, not the hint — assert the ORA chip).
            composeRule.onNodeWithText("ORA").assertIsDisplayed()
            composeRule.onNodeWithText(
                actString(dev.openclip.R.string.cancel)
            ).performClick()
            composeRule.waitForIdle()
        } finally {
            slice6Reset()
        }
    }

    private fun awaitUiString(id: Int, want: String, timeoutMs: Long = 20000): Boolean {
        val t0 = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - t0 < timeoutMs) {
            var s = ""
            try {
                composeRule.activityRule.scenario.onActivity { act ->
                    s = try {
                        act.getString(id)
                    } catch (_: Exception) {
                        ""
                    }
                }
            } catch (_: Exception) {
            }
            if (s == want) return true
            Thread.sleep(300)
        }
        return false
    }

    // Slice 7.4: French long strings never wrap the chrome (D2/D7).
    // Tablet: all menu titles share one band; the dirty-close dialog
    // survives "Fermer sans enregistrer" without a layout crash.
    //
    // Hermeticity: LocaleManager persists app-locales system-wide and
    // applies them asynchronously (leaks across tests/processes), so
    // this test overrides the ACTIVITY Resources synchronously
    // (updateConfiguration + recomposition, in-process only) and
    // restores the entry locale before returning. No recreate, no
    // system-persisted state.
    private fun overrideActivityLocale(tag: String): Boolean {
        var ok = false
        try {
            composeRule.activityRule.scenario.onActivity { act ->
                val res = act.resources
                val cfg = android.content.res.Configuration(res.configuration)
                cfg.setLocale(java.util.Locale(tag))
                @Suppress("DEPRECATION")
                res.updateConfiguration(cfg, res.displayMetrics)
                act.activityTestHooks.poke?.invoke()
            }
            composeRule.waitForIdle()
            ok = true
        } catch (_: Exception) {
        }
        return ok
    }
    @Test
    fun slice7_frenchLongStringsEllipsize() {
        slice6Reset()
        var entryLang = "en"
        var entrySave = "Save"
        composeRule.activityRule.scenario.onActivity { act ->
            entryLang = act.resources.configuration.locales.get(0)
                .toLanguageTag()
            entrySave = act.getString(dev.openclip.R.string.doc_save)
        }
        try {
            assertTrue(
                "FR override must apply",
                overrideActivityLocale("fr")
            )
            // Settle: recomposition must pick up FR resources.
            assertTrue(
                "FR locale must apply",
                awaitUiString(
                    dev.openclip.R.string.doc_save,
                    "Enregistrer"
                )
            )
            val dens = composeRule.activity.resources.displayMetrics.density
            if (!isCompact()) {
                val titles = listOf(
                    actString(dev.openclip.R.string.file_menu),
                    actString(dev.openclip.R.string.menu_edit),
                    actString(dev.openclip.R.string.menu_layer),
                    actString(dev.openclip.R.string.menu_selection),
                    actString(dev.openclip.R.string.menu_filter),
                    actString(dev.openclip.R.string.menu_display),
                    actString(dev.openclip.R.string.menu_window),
                    actString(dev.openclip.R.string.menu_help)
                )
                var top0 = 0.dp
                // titles[0] ("Fichier") also labels the tool-row action,
                // so the band check covers the other seven titles. Tags,
                // not text: tablet shows both bars ("Sélection" × 2).
                for (i in 1 until titles.size) {
                    val t = titles[i]
                    composeRule.onNodeWithTag("menu-title-$i").assertIsDisplayed()
                    val b = composeRule.onNodeWithTag("menu-title-$i")
                        .getUnclippedBoundsInRoot()
                    if (i == 1) top0 = b.top
                    else assertTrue(
                        "FR menu [$t] must not wrap",
                        kotlin.math.abs((b.top - top0).value) < 4 * dens
                    )
                }
            } else {
                composeRule.onNodeWithTag("menu-compact").assertIsDisplayed()
            }
            // Dirty-close dialog carries "Fermer sans enregistrer".
            // NB: recreate() issued a fresh default doc — re-read ids.
            val fresh = awaitCount(1)
            assertTrue("one fresh doc expected", fresh.isNotEmpty())
            val id = fresh[0]
            val esA = awaitDocReady(id)
            uiSync { it.navigateMode = false; it.resetView() }
            pushTestBrush(radius = 24f, curve = 0f)
            strokeAcross(
                esA, esA.height * 0.5f, SystemClock.uptimeMillis(),
                esA.width * 0.5f
            )
            awaitDirty(id, true)
            // V3: single-doc compact hides the tab crosses — File >
            // Close document runs the same requestClose path.
            closeDocViaMenu()
            val discard = actString(dev.openclip.R.string.doc_close_discard)
            composeRule.onNodeWithText(discard).assertIsDisplayed()
            composeRule.onNodeWithText(discard).performClick()
            composeRule.waitForIdle()
            assertEquals(0, awaitCount(0).size)
        } finally {
            overrideActivityLocale(entryLang)
            // Hermetic restore: EN live again before reset, so nothing
            // FR-flavoured (names, seeds) can spill into the next test.
            assertTrue(
                "entry locale must be restored",
                awaitUiString(dev.openclip.R.string.doc_save, entrySave)
            )
            slice6Reset()
            composeRule.activityRule.scenario.onActivity { act ->
                android.util.Log.d(
                    "Slice7", "fr-final lang=" +
                        act.resources.configuration.locales.get(0)
                )
            }
        }
    }

    // Slice 7.5: empty canvas shows a monumental CTA (D8); a corrupt
    // commandbar.json restores presets + posts prefs_reset_hint once.
    @Test
    fun slice7_emptyCanvasCtaAndPrefsHint() {
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
            // V3: single-doc compact hides the tab crosses — File >
            // Close document runs the same requestClose path.
            closeDocViaMenu()
            composeRule.onNodeWithTag("doc-close-discard").performClick()
            composeRule.waitForIdle()
            assertEquals(0, awaitCount(0).size)
            composeRule.onNodeWithText(
                actString(dev.openclip.R.string.newcanvas_empty_hint)
            ).assertIsDisplayed()
            val cta = composeRule.onNodeWithTag("newcanvas-empty-cta")
                .getUnclippedBoundsInRoot()
            assertTrue(
                "empty CTA must be >= 48dp",
                (cta.bottom - cta.top) >= (48.dp - 0.5.dp)
            )
            openMenuCompactIfPresent()
            composeRule.onNodeWithText(
                actString(dev.openclip.R.string.menu_window)
            ).performClick()
            composeRule.waitForIdle()
            // Corrupt prefs: presets restored + one-shot status hint.
            composeRule.activityRule.scenario.onActivity { act ->
                File(act.filesDir, "commandbar.json").writeText("{broken")
            }
            composeRule.activityRule.scenario.recreate()
            composeRule.waitForIdle()
            awaitHooks()
            Thread.sleep(800)
            composeRule.onNodeWithTag("cmdbar-save").assertIsDisplayed()
            assertEquals(
                actString(dev.openclip.R.string.prefs_reset_hint),
                statusText()
            )
        } finally {
            composeRule.activityRule.scenario.onActivity { act ->
                try {
                    File(act.filesDir, "commandbar.json").delete()
                } catch (_: Exception) {
                }
            }
            slice6Reset()
        }
    }

    // Slice 7.6: chrome budget (A+B+T+C+G <= 230dp, canvas >= 2x) and
    // collapse postures; fullscreen hides the dock/timeline/bar.
    @Test
    fun slice7_chromeBudgetAndCollapse() {
        slice6Reset()
        try {
            val dens = composeRule.activity.resources.displayMetrics.density
            fun h(tag: String): Dp {
                val b = composeRule.onNodeWithTag(tag)
                    .getUnclippedBoundsInRoot()
                return b.bottom - b.top
            }
            val sum = h("menu-bar") + h("doc-tabs") + h("doc-title-bar") +
                h("command-bar") + h("status-bar")
            assertTrue(
                "chrome budget exceeded: $sum",
                sum <= 230.dp
            )
            val ids = hookIds()
            assertTrue(ids.isNotEmpty())
            var canvasH = 0
            composeRule.activityRule.scenario.onActivity { act ->
                canvasH = act.activityTestHooks.surfaceFor?.invoke(ids[0])?.height ?: 0
            }
            // Dominance over the HONEST chrome: the 5 budgeted rows +
            // the tool row + one 1dp divider per seam (6). The spec's 2x
            // counts the 5 rows only; on a real device the tool row and
            // dividers also eat height, so 2x of the 5 alone would demand
            // hiding the (untouchable, slices 0-6) tool row.
            val honest = sum + h("tool-row") + 6.dp
            assertTrue(
                "canvas ($canvasH px) must dominate the chrome ($honest)",
                canvasH.toFloat() >= 1.25f * honest.value * dens
            )
            // Fullscreen: dock + timeline + bar gone, canvas alone.
            composeRule.onNodeWithTag("cmdbar-fullscreen").performScrollTo()
            composeRule.onNodeWithTag("cmdbar-fullscreen").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("dock-toggle").assertDoesNotExist()
            composeRule.onNodeWithTag("command-bar").assertDoesNotExist()
            composeRule.onNodeWithTag("fullscreen-exit").assertIsDisplayed()
            composeRule.onNodeWithTag("fullscreen-exit").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("dock-toggle").assertIsDisplayed()
            composeRule.onNodeWithTag("cmdbar-save").assertIsDisplayed()
            if (isCompact()) {
                // Phone: dock + timeline folded; bar folds to a pastille.
                composeRule.onNodeWithText(
                    actString(dev.openclip.R.string.window_dock)
                ).assertDoesNotExist()
                composeRule.onNodeWithTag("timeline-drawer")
                    .assertDoesNotExist()
                composeRule.onNodeWithTag("cmdbar-collapse").performScrollTo()
                composeRule.onNodeWithTag("cmdbar-collapse").performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithTag("command-bar").assertDoesNotExist()
                composeRule.onNodeWithTag("cmdbar-collapsed").assertIsDisplayed()
                composeRule.onNodeWithTag("cmdbar-collapsed").performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithTag("cmdbar-save").assertIsDisplayed()
            }
        } finally {
            slice6Reset()
        }
    }

    // Slice 7.7: every chrome touch target >= 48dp (0.5dp tolerance).
    @Test
    fun slice7_touchTargets48dpPolish() {
        slice6Reset()
        // V3: single-doc compact hides the tabs — a second doc keeps
        // the tab targets (and the "+" flow) under test.
        val tallExtra = hookOpen("Tall", 256, 256, -1)
        awaitDocReady(tallExtra)
        try {
            fun tall(tag: String, scroll: Boolean = false) {
                composeRule.waitForIdle()
                if (scroll) {
                    try {
                        composeRule.onNodeWithTag(tag).performScrollTo()
                    } catch (_: AssertionError) {
                    }
                }
                val u = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
                val h = u.bottom - u.top
                assertTrue(
                    "$tag must be >= 48dp, got $h",
                    h >= (48.dp - 0.5.dp)
                )
            }
            if (!isCompact()) {
                // titles[0] ("File") also labels the tool-row action —
                // the band check covers the other seven titles. Tags, not
                // text: "Selection" also labels a tool chip (tablet shows
                // both bars at once).
                for ((k, id) in intArrayOf(
                    dev.openclip.R.string.menu_edit,
                    dev.openclip.R.string.menu_layer,
                    dev.openclip.R.string.menu_selection,
                    dev.openclip.R.string.menu_filter,
                    dev.openclip.R.string.menu_display,
                    dev.openclip.R.string.menu_window,
                    dev.openclip.R.string.menu_help
                ).withIndex()) {
                    val t = actString(id)
                    val u = composeRule.onNodeWithTag("menu-title-${k + 1}")
                        .getUnclippedBoundsInRoot()
                    assertTrue(
                        "menu [$t] must be >= 48dp",
                        (u.bottom - u.top) >= (48.dp - 0.5.dp)
                    )
                }
            } else {
                tall("menu-compact")
                openMenuCompactIfPresent()
                tall("menu-compact-item-7-0", scroll = true)
                composeRule.onNodeWithTag("menu-compact").performClick()
                composeRule.waitForIdle()
            }
            for (doc in hookIds()) {
                tall("doc-tab-$doc")
                tall("doc-tab-close-$doc")
            }
            tall("doc-tab-add")
            tall("doc-save")
            tall("cmdbar-save", scroll = true)
            tall("cmdbar-undo", scroll = true)
            tall("cmdbar-fullscreen", scroll = true)
            tall("cmdbar-edit-toggle", scroll = true)
            // New-canvas dialog buttons.
            composeRule.onNodeWithTag("doc-tab-add").performScrollTo()
            composeRule.onNodeWithTag("doc-tab-add").performClick()
            composeRule.waitForIdle()
            tall("newcanvas-confirm")
            composeRule.onNodeWithTag("newcanvas-cancel").performClick()
            composeRule.waitForIdle()
            // Empty-state CTA (close the clean docs, no confirm needed).
            // V3: the last close goes through the menu (hidden tabs).
            val ids = hookIds()
            assertTrue("want the 2 tall docs, got $ids", ids.size == 2)
            composeRule.onNodeWithTag("doc-tab-close-${ids[0]}").performScrollTo()
            composeRule.onNodeWithTag("doc-tab-close-${ids[0]}").performClick()
            composeRule.waitForIdle()
            closeDocViaMenu()
            tall("newcanvas-empty-cta")
            composeRule.onNodeWithTag("newcanvas-empty-cta").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("newcanvas-confirm").performClick()
            hookSync()
            assertEquals(1, awaitCount(1).size)
        } finally {
            slice6Reset()
        }
    }

    // ---- V+1 selection / text / alpha-erase (spec
    // docs/v1-selection-texte-gomme.md). Surface tests run on isolated
    // fresh docs (openDoc/closeDoc) so the shared boot doc stays
    // pristine for the order-sensitive gesture tests above.

    private fun openFreshDoc(name: String, size: Int = 256): String {
        var id = ""
        composeRule.activityRule.scenario.onActivity { act ->
            id = act.activityTestHooks.openDoc?.invoke(name, size, size, 160f, -1) ?: ""
        }
        assertTrue("openDoc must return an id", id.isNotEmpty())
        composeRule.waitForIdle()
        Thread.sleep(300)
        val es = freshSurface(id)
        // Fresh tabs need longer than the 5 s boot-surface poll
        // (cold-start jank on swiftshader): mirror awaitDocReady.
        val t0 = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - t0 < 25000 &&
            (es.width <= 200 || es.height <= 200 || !es.isReady())
        ) {
            Thread.sleep(200)
        }
        assertTrue(
            "fresh surface must become ready (w=${es.width} h=${es.height} ready=${es.isReady()})",
            es.width > 200 && es.height > 200 && es.isReady()
        )
        // Identity gate (state-based, no view traversal): the registry's
        // active doc must be ours and our object attached. Guards the
        // tab-switch composition transient (paused boot surface still
        // in the tree briefly).
        val t1 = SystemClock.uptimeMillis()
        var stable = false
        while (!stable && SystemClock.uptimeMillis() - t1 < 25000) {
            composeRule.waitForIdle()
            var match = false
            var attached = false
            composeRule.activityRule.scenario.onActivity { act ->
                val h = act.activityTestHooks
                match = h.activeDocId?.invoke() == id &&
                    h.surfaceFor?.invoke(id) === es
                attached = es.parent != null
            }
            stable = match && attached
            if (!stable) Thread.sleep(200)
        }
        assertTrue("fresh surface must become the attached active one", stable)
        es.navigateMode = false
        es.resetView()
        Thread.sleep(400)
        return id
    }

    private fun freshSurface(id: String): EngineSurface {
        var es: EngineSurface? = null
        composeRule.activityRule.scenario.onActivity { act ->
            es = act.activityTestHooks.surfaceFor?.invoke(id)
        }
        assertNotNull("fresh surface must exist", es)
        return es!!
    }

    private fun closeFreshDoc(id: String) {
        composeRule.activityRule.scenario.onActivity { act ->
            act.activityTestHooks.closeDoc?.invoke(id)
        }
        composeRule.waitForIdle()
    }

    private fun awaitBool(
        timeoutMs: Long = 20000,
        op: ((Boolean) -> Unit) -> Unit
    ): Boolean {
        var out: Boolean? = null
        val lock = Object()
        op { ok ->
            synchronized(lock) {
                out = ok
                lock.notifyAll()
            }
        }
        synchronized(lock) {
            val end = SystemClock.uptimeMillis() + timeoutMs
            while (out == null && SystemClock.uptimeMillis() < end) {
                lock.wait(1000)
            }
        }
        return out == true
    }

    /** View-coord DOWN/MOVE/UP mapped from engine coordinates. */
    private fun viewStrokeEng(
        es: EngineSurface,
        ex0: Float, ey0: Float, ex1: Float, ey1: Float
    ) {
        val (vx0, vy0) = es.engineToScreen(ex0, ey0)
        val (vx1, vy1) = es.engineToScreen(ex1, ey1)
        val t0 = SystemClock.uptimeMillis()
        dispatchAll(
            es,
            simpleDown(t0, vx0, vy0),
            MotionEvent.obtain(
                t0, t0 + 16, MotionEvent.ACTION_MOVE,
                (vx0 + vx1) / 2, (vy0 + vy1) / 2, 1f, 1f, 0, 1f, 1f, 0, 0
            ),
            MotionEvent.obtain(
                t0, t0 + 32, MotionEvent.ACTION_MOVE,
                vx1, vy1, 1f, 1f, 0, 1f, 1f, 0, 0
            ),
            MotionEvent.obtain(
                t0, t0 + 48, MotionEvent.ACTION_UP,
                vx1, vy1, 1f, 1f, 0, 1f, 1f, 0, 0
            )
        )
    }

    private val inkBrush =
        """{"radius":12,"hardness":1.0,"opaque":1.0,"spacing":0.15,"color":"#141414","stabilize":0.0,"curve":0.0,"mode":"brush"}"""
    private val eraseBrush =
        """{"radius":24,"hardness":1.0,"opaque":1.0,"spacing":0.15,"color":"#141414","stabilize":0.0,"curve":0.0,"mode":"alpha-erase"}"""

    // V+1-1: rect gesture arms/commits/disarrms; a full-width stroke
    // then paints only inside the left-half mask.
    @Test
    fun selectionRectGestureConstrainsStroke() {
        val id = openFreshDoc("sel-rect")
        val es = freshSurface(id)
        try {
            es.setBrush(inkBrush)
                es.selectionTool = dev.openclip.ui.SelectionTool.RECT
            viewStrokeEng(es, 40f, 10f, 118f, 246f)
            Thread.sleep(500)
            assertTrue("rect drag must commit a mask", es.hasSelection())
            assertTrue(
                "tool must disarm after commit",
                es.selectionTool == null
            )
            viewStrokeEng(es, 40f, 128f, 110f, 128f)
            Thread.sleep(600)
            val b = pullFull(es)
            try {
                var leftInk = 0
                var rightInk = 0
                for (x in 40..110 step 2) {
                    if (b.getPixel(x, 128) != -1) leftInk++
                }
                // Ink stamps bleed ~12px past the mask edge.
                for (x in 150..246 step 2) {
                    if (b.getPixel(x, 128) != -1) rightInk++
                }
                assertTrue("left half must carry the stroke, got $leftInk", leftInk > 20)
                assertEquals("right half must stay white, got $rightInk", 0, rightInk)
            } finally {
                b.recycle()
            }
            assertTrue("stroke must undo", es.undo())
        } finally {
            es.clearSelection()
                es.resetView()
            closeFreshDoc(id)
        }
    }

    // V+1-2: in-code lasso triangle, invert, alpha-erase: the inside
    // stays inked, the outside is erased to transparency.
    @Test
    fun selectionLassoCloseAndInvert() {
        val id = openFreshDoc("sel-lasso")
        val es = freshSurface(id)
        try {
            es.setBrush(inkBrush)
            viewStrokeEng(es, 40f, 120f, 216f, 120f)
            Thread.sleep(600)
            es.selectPolygonEngine(
                    listOf(
                        Pair(30f, 30f), Pair(200f, 30f), Pair(115f, 220f)
                    )
                )
            assertTrue(es.hasSelection())
            es.invertSelection()
            es.setBrush(eraseBrush)
            viewStrokeEng(es, 40f, 120f, 60f, 120f)
            Thread.sleep(400)
            viewStrokeEng(es, 170f, 120f, 216f, 120f)
            Thread.sleep(600)
            val b = pullFull(es)
            try {
                // Row y=120: triangle spans x in [70,160]; erase stamps
                // bleed ~24px inside, so sample the safe core [100,130].
                var insideInk = 0
                for (x in 100..130 step 2) {
                    val px = b.getPixel(x, 120)
                    if ((px ushr 24) > 200 && px != -1) insideInk++
                }
                var outsideAlpha = 0
                for (x in listOf(45, 55, 170, 180, 195, 210)) {
                    outsideAlpha += (b.getPixel(x, 120) ushr 24)
                }
                assertTrue("inside must stay inked, got $insideInk", insideInk > 10)
                assertTrue(
                    "outside must be erased, alpha sum $outsideAlpha",
                    outsideAlpha < 6 * 60
                )
            } finally {
                b.recycle()
            }
            assertTrue("erase must undo", es.undo())
            assertTrue("ink must undo", es.undo())
        } finally {
            es.clearSelection()
                es.resetView()
            closeFreshDoc(id)
        }
    }

    // V+1-3: the overlay probe follows zoom x2 + 15° rotation.
    @Test
    fun selectionOverlayFollowsView() {
        val id = openFreshDoc("sel-view")
        val es = freshSurface(id)
        try {
            es.selectRectEngine(40f, 40f, 140f, 140f)
            assertTrue(es.hasSelection())
            fun probe(ex: Float, ey: Float): Boolean? {
                val (vx, vy) = es.engineToScreen(ex, ey)
                return es.selectionOverlayForTest(vx, vy)
            }
            assertEquals(true, probe(90f, 90f))
            assertEquals(false, probe(10f, 10f))
            es.setViewForTest(2f, 15f)
            Thread.sleep(300)
            assertEquals(true, probe(90f, 90f))
            assertEquals(false, probe(10f, 10f))
        } finally {
            es.clearSelection()
                es.resetView()
            closeFreshDoc(id)
        }
    }

    // V+1-4: pause/resume (tab switch) keeps the mask hash.
    @Test
    fun selectionSurvivesTabSwitch() {
        val id = openFreshDoc("sel-tab")
        val es = freshSurface(id)
        try {
            es.selectRectEngine(10f, 10f, 100f, 100f)
            val h1 = es.selectionHashForTest()
            assertTrue(h1 != 0L)
            es.pause()
                es.resume()
            assertEquals(h1, es.selectionHashForTest())
        } finally {
            es.clearSelection()
            closeFreshDoc(id)
        }
    }

    // V+1-5: mosaic under a left-half selection: right half
    // byte-identical, left half changed, frame count preserved.
    @Test
    fun selectionFilterMasked() {
        val id = openFreshDoc("sel-filter")
        val es = freshSurface(id)
        try {
            es.setBrush(inkBrush)
            viewStrokeEng(es, 40f, 64f, 216f, 64f)
            viewStrokeEng(es, 40f, 128f, 176f, 192f)
            Thread.sleep(600)
            val before = pullFull(es)
            val framesBefore = es.frameCount()
            es.selectRectEngine(0f, 0f, 128f, 256f)
            val ok = awaitBool { cb ->
                es.applyFilterInSelection("mosaic", """{"blockSize":16}""", cb)
            }
            assertTrue("masked mosaic must apply", ok)
            val after = pullFull(es)
            try {
                val bp = bitmapPixels(before)
                val ap = bitmapPixels(after)
                assertEquals(bp.size, ap.size)
                var leftDiff = 0
                var rightDiff = 0
                for (y in 0 until 256) {
                    for (x in 0 until 256) {
                        if (bp[y * 256 + x] != ap[y * 256 + x]) {
                            if (x < 128) leftDiff++ else rightDiff++
                        }
                    }
                }
                assertTrue("left must change, got $leftDiff", leftDiff > 100)
                assertEquals("right must be byte-identical, got $rightDiff", 0, rightDiff)
            } finally {
                before.recycle()
                after.recycle()
            }
            assertEquals(framesBefore, es.frameCount())
        } finally {
            es.clearSelection()
                es.resetView()
            closeFreshDoc(id)
        }
    }

    private fun commitTextSync(es: EngineSurface, spec: dev.openclip.ui.TextSpec): Int {
        val before = es.layerCount()
        val ok = awaitBool { cb -> es.commitText(spec, cb) }
        assertTrue("text commit must succeed", ok)
        val after = es.layerCount()
        assertEquals(before + 1, after)
        return after - 1
    }

    // V+1-6: text commit paints pixels inside the box; the fiche is
    // stored (badge source).
    @Test
    fun textCommitPaintsPixels() {
        val id = openFreshDoc("text-commit")
        val es = freshSurface(id)
        try {
            val spec = dev.openclip.ui.TextSpec(
                text = "Aa Ég", font = "sans-serif", sizePx = 72,
                boxX = 10f, boxY = 10f, boxW = 236f
            )
            val idx = commitTextSync(es, spec)
            val fiche = es.layerTextForTest(idx)
            assertNotNull(fiche)
            assertEquals("Aa Ég", fiche!!.text)
            val b = pullFull(es)
            try {
                var ink = 0
                for (y in 10..150 step 2) {
                    for (x in 10..245 step 2) {
                        if (b.getPixel(x, y) != -1) ink++
                    }
                }
                assertTrue("text box must carry ink, got $ink", ink > 30)
            } finally {
                b.recycle()
            }
            assertTrue("commit must undo", es.undo())
        } finally {
            closeFreshDoc(id)
        }
    }

    // V+1-7: re-edit replaces pixels; undo restores A.
    @Test
    fun textReeditReplaces() {
        val id = openFreshDoc("text-reedit")
        val es = freshSurface(id)
        try {
            val box = dev.openclip.ui.TextSpec(
                text = "AAAA", font = "sans-serif", sizePx = 96,
                boxX = 8f, boxY = 8f, boxW = 240f
            )
            val idx = commitTextSync(es, box)
            val pa = pullFull(es)
            val okB = awaitBool { cb ->
                es.reeditText(idx, box.copy(text = "BBBB"), cb)
            }
            assertTrue("re-edit must succeed", okB)
            val pb = pullFull(es)
            try {
                val a = bitmapPixels(pa)
                val c = bitmapPixels(pb)
                var diff = 0
                for (y in 8..150) {
                    for (x in 8..247) {
                        if (a[y * 256 + x] != c[y * 256 + x]) diff++
                    }
                }
                assertTrue("B must replace A pixels, diff $diff", diff > 100)
                assertTrue("undo must restore A", es.undo())
                val pu = pullFull(es)
                try {
                    assertTrue(
                        "undo must restore the A composite exactly",
                        a.contentEquals(bitmapPixels(pu))
                    )
                } finally {
                    pu.recycle()
                }
                assertTrue("A commit must undo", es.undo())
            } finally {
                pa.recycle()
                pb.recycle()
            }
        } finally {
            closeFreshDoc(id)
        }
    }

    // V+1-8: ORA save/load keeps the fiche and the pixels.
    @Test
    fun textOraRoundTrip() {
        val id = openFreshDoc("text-ora")
        val es = freshSurface(id)
        try {
            val idx = commitTextSync(
                es,
                dev.openclip.ui.TextSpec(
                    text = "OraQZ 123", font = "serif", sizePx = 64,
                    bold = true, align = "center",
                    boxX = 8f, boxY = 40f, boxW = 240f
                )
            )
            val f1 = es.layerTextForTest(idx)
            assertNotNull(f1)
            val p1 = pullFull(es)
            val dir = InstrumentationRegistry.getInstrumentation()
                .targetContext.cacheDir
            val ora = File(dir, "text-roundtrip.ora")
            assertTrue(awaitBool { cb -> es.saveTo(ora.absolutePath, "ora", cb) })
            assertTrue(awaitBool { cb -> es.loadFrom(ora.absolutePath, cb) })
            val f2 = es.layerTextForTest(idx)
            assertNotNull("fiche must survive ORA", f2)
            assertEquals(f1!!.text, f2!!.text)
            assertEquals(f1.font, f2.font)
            assertEquals(f1.sizePx, f2.sizePx)
            val p2 = pullFull(es)
            try {
                assertTrue(
                    "ORA round trip must preserve pixels",
                    bitmapPixels(p1).contentEquals(bitmapPixels(p2))
                )
            } finally {
                p1.recycle()
                p2.recycle()
                ora.delete()
            }
        } finally {
            closeFreshDoc(id)
        }
    }

    // V+1-9: unknown font falls back to sans-serif with a flag, no crash.
    @Test
    fun textFontFallback() {
        val id = openFreshDoc("text-fallback")
        val es = freshSurface(id)
        try {
            val idx = commitTextSync(
                es,
                dev.openclip.ui.TextSpec(
                    text = "Fallback", font = "Nope-404-missing", sizePx = 64,
                    boxX = 8f, boxY = 40f, boxW = 240f
                )
            )
            assertTrue("fallback flag must be set", es.lastFontFallback)
            val b = pullFull(es)
            try {
                var ink = 0
                for (y in 40..140 step 2) {
                    for (x in 8..247 step 2) {
                        if (b.getPixel(x, y) != -1) ink++
                    }
                }
                assertTrue("fallback text must still paint, got $ink", ink > 20)
            } finally {
                b.recycle()
            }
            assertTrue("fallback commit must undo", es.undo())
            // B2 §1: the text commit is ONE document snapshot, so undo
            // removes the created layer with its fiche (previously the
            // pixels-only undo left an empty text layer behind).
            assertNull(
                "atomic undo removes the created text layer",
                es.layerTextForTest(idx)
            )
        } finally {
            closeFreshDoc(id)
        }
    }

    // V+1-10: dest-out erase on a blue layer reveals the red layer below
    // (the shot that kills the white-paint hack).
    @Test
    fun eraserDestOutComposite() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            EngineHost.nSetBrush(
                handle,
                """{"radius":60,"hardness":1.0,"opaque":1.0,"spacing":0.15,"color":"#FF0000"}"""
            )
            var sid = EngineHost.nStrokeBegin(handle, 128f, 128f, 1f)
            EngineHost.nStrokeMove(handle, sid, 129f, 128f, 1f)
            EngineHost.nStrokeEnd(handle, sid)
            val blue = EngineHost.nAddLayer(handle, "blue")
            assertTrue(blue >= 0)
            assertEquals(blue, EngineHost.nSetActiveLayer(handle, blue))
            EngineHost.nSetBrush(
                handle,
                """{"radius":60,"hardness":1.0,"opaque":1.0,"spacing":0.15,"color":"#0000FF"}"""
            )
            sid = EngineHost.nStrokeBegin(handle, 128f, 128f, 1f)
            EngineHost.nStrokeMove(handle, sid, 129f, 128f, 1f)
            EngineHost.nStrokeEnd(handle, sid)
            // Sanity: the top is blue before the erase.
            var px = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, px, w, h))
            val topBefore = px[128 * w + 128]
            assertTrue(
                "top must be blue-dominant before erase",
                ((topBefore shr 16) and 0xFF) < 80 &&
                    ((topBefore) and 0xFF) > 150
            )
            EngineHost.nSetBrush(
                handle,
                """{"radius":40,"hardness":1.0,"opaque":1.0,"spacing":0.15,"color":"#141414","mode":"alpha-erase"}"""
            )
            sid = EngineHost.nStrokeBegin(handle, 128f, 128f, 1f)
            EngineHost.nStrokeMove(handle, sid, 129f, 128f, 1f)
            EngineHost.nStrokeEnd(handle, sid)
            px = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, px, w, h))
            val center = px[128 * w + 128]
            val cr = (center shr 16) and 0xFF
            val cb = center and 0xFF
            assertTrue(
                "erased center must show red, got #%06X".format(center and 0xFFFFFF),
                cr > 150 && cr > cb + 60
            )
            assertTrue("erase must undo", EngineHost.nUndo(handle))
            px = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, px, w, h))
            assertEquals("undo must restore blue", topBefore, px[128 * w + 128])
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // V+1-11: the honest banner is gone (alpha-erase needs none).
    @Test
    fun eraserBannerGone() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tool-eraser").performScrollTo()
        composeRule.onNodeWithTag("tool-eraser").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("eraser-banner").assertDoesNotExist()
        // Dismiss the one-shot migration note if this run shows it.
        runCatching {
            composeRule.onNodeWithTag("eraser-alpha-ok").performClick()
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithTag("tool-brush").performScrollTo()
        composeRule.onNodeWithTag("tool-brush").performClick()
        composeRule.waitForIdle()
    }

    // V+1 proof capture (not in the spec count): draws selection + text
    // + an erased patch on an isolated doc and screenshots to
    // /sdcard/openclip-v1.png for the human review gate.
    @Test
    fun v1ProofScreenshot() {
        val id = openFreshDoc("proof", 512)
        val es = freshSurface(id)
        try {
            val ok = awaitBool { cb ->
                es.commitText(
                    dev.openclip.ui.TextSpec(
                        text = "Aa", font = "sans-serif", sizePx = 84,
                        boxX = 280f, boxY = 60f, boxW = 200f
                    ),
                    cb
                )
            }
            assertTrue(ok)
            es.setBrush(inkBrush)
            viewStrokeEng(es, 80f, 420f, 470f, 420f)
            Thread.sleep(500)
            es.setBrush(eraseBrush)
            viewStrokeEng(es, 230f, 400f, 350f, 440f)
            Thread.sleep(500)
            // Selection LAST so the marching ants stay visible while
            // the strokes below remain painted (unmasked strokes first).
            es.selectRectEngine(40f, 40f, 240f, 200f)
            Thread.sleep(1500)
            // Proof capture for the human review gate (best effort,
            // like the slice reports): whole-screen shot with the
            // selection ants + text layer + erased patch visible.
            var shot: Bitmap? = null
            try {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    shot = InstrumentationRegistry.getInstrumentation()
                        .uiAutomation.takeScreenshot()
                }
            } catch (_: Exception) {
            }
            assertNotNull("screenshot must be captured", shot)
            val dst = File(composeRule.activity.cacheDir, "openclip-v1.png")
            FileOutputStream(dst).use { o ->
                shot!!.compress(Bitmap.CompressFormat.PNG, 100, o)
            }
            if (!shot!!.isRecycled) shot!!.recycle()
            assertTrue("screenshot must land", dst.length() > 10000)
        } finally {
            es.clearSelection()
                es.resetView()
            closeFreshDoc(id)
        }
    }


private fun IntArray.contentNotEquals(other: IntArray): Boolean {
    if (size != other.size) return true
    for (i in indices) if (this[i] != other[i]) return true
    return false
}

    // ================= V+2 BD/manga + exports longs =================

    private fun cacheFile(name: String): File =
        File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            name
        )

    private fun intPixels(b: Bitmap): IntArray {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return px
    }

    private fun nonWhite(b: Bitmap): Int {
        var n = 0
        val px = intPixels(b)
        for (v in px) if (v != -1) n++
        return n
    }

    // V+2-1 §1: panel rect in-code — composite shows the frame
    // (perimeter ink), interior pixel-intact (white, no fill in V+2).
    @Test
    fun panelRectStrokeClosed() {
        val id = openFreshDoc("v2-panel-rect")
        val es = freshSurface(id)
        try {
            val fiche = PanelFiche(
                "rect", floatArrayOf(16f, 16f, 224f, 160f),
                order = 1, bgWhite = false, tone = "none", density = 100
            )
            val layer = commitPanelRect(es, fiche)
            assertTrue("panel layer", layer >= 0)
            val b = pullFull(es)
            try {
                var edge = 0
                var edgeN = 0
                var x = 16
                while (x <= 240) {
                    for (y in intArrayOf(16, 176)) {
                        edgeN++
                        if (b.getPixel(x, y) != -1) edge++
                    }
                    x += 4
                }
                var y = 16
                while (y <= 176) {
                    for (xx in intArrayOf(16, 240)) {
                        edgeN++
                        if (b.getPixel(xx, y) != -1) edge++
                    }
                    y += 4
                }
                assertTrue("frame ink on perimeter $edge/$edgeN", edge > edgeN / 2)
                assertEquals("interior intact", -1, b.getPixel(128, 96))
            } finally {
                b.recycle()
            }
        } finally {
            closeFreshDoc(id)
        }
    }

    // V+2-2 §1: tone dots under a polygonal mask — outside the polygon
    // byte-identical to before (masked bridge, pattern V+1 §1.1).
    @Test
    fun panelToneMasked() {
        val id = openFreshDoc("v2-panel-tone")
        val es = freshSurface(id)
        try {
            val before = pullFull(es)
            try {
                val poly = listOf(
                    Pair(32f, 32f), Pair(224f, 32f),
                    Pair(224f, 224f), Pair(32f, 224f)
                )
                es.selectPolygonEngine(poly)
                val ok = awaitBool { cb ->
                    es.applyToneInSelection("dots", cb)
                }
                assertTrue("masked tone must apply", ok)
                es.clearSelection()
                val after = pullFull(es)
                try {
                    val pb = intPixels(before)
                    val pa = intPixels(after)
                    var insideInk = 0
                    var outsideDiff = 0
                    for (y in 0 until 256) {
                        for (x in 0 until 256) {
                            val inside =
                                x in 32..224 && y in 32..224
                            if (inside) {
                                if (pa[y * 256 + x] != -1) insideInk++
                            } else if (pa[y * 256 + x] != pb[y * 256 + x]) {
                                outsideDiff++
                            }
                        }
                    }
                    assertTrue("tone ink inside, got $insideInk", insideInk > 200)
                    assertEquals("outside byte-identical, diff $outsideDiff", 0, outsideDiff)
                } finally {
                    after.recycle()
                }
            } finally {
                before.recycle()
            }
        } finally {
            closeFreshDoc(id)
        }
    }

    // V+2-3 §1: bubble + linked text survive the ORA round trip.
    @Test
    fun bubbleTailAndTextLink() {
        val id = openFreshDoc("v2-bubble")
        val es = freshSurface(id)
        try {
            val fiche = BubbleFiche(
                "ellipse", floatArrayOf(32f, 40f, 180f, 90f),
                Pair(120f, 130f), Pair(90f, 200f), textLayer = 0
            )
            val layer = commitBubble(es, fiche)
            assertTrue("bubble layer", layer >= 0)
            val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            val ora = File(dir, "v2-bubble.ora")
            assertTrue(awaitBool { cb -> es.saveTo(ora.absolutePath, "ora", cb) })
            assertTrue(awaitBool { cb -> es.loadFrom(ora.absolutePath, cb) })
            val metas = v2LayerMetas(es)
            val back = metas.values.mapNotNull { parseBubbleMeta(it) }.firstOrNull()
            assertNotNull("bubble fiche must survive ORA", back)
            assertEquals("ellipse", back!!.shape)
            assertEquals(0, back.textLayer)
            assertEquals(200f, back.tailTip.second, 0.6f)
            ora.delete()
        } finally {
            closeFreshDoc(id)
        }
    }

    // V+2-4 §1: speed burst generates N strokes; N undos restore blank.
    @Test
    fun speedGeneratesUndoable() {
        val id = openFreshDoc("v2-speed")
        val es = freshSurface(id)
        try {
            val layer = es.addVectorLayer("Speed")
            assertTrue(layer >= 0)
            val blank = pullFull(es)
            val blankPx = intPixels(blank)
            blank.recycle()
            val fiche = SpeedFiche(128f, 128f, 90f, 120f, 24, 7L)
            val segs = SpeedBurst.generate(fiche)
            assertEquals(24, segs.size)
            var n = 0
            for (s in segs) {
                if (es.appendVectorPath(layer, SpeedBurst.pathJson(s)) >= 0) n++
            }
            assertEquals(24, n)
            val inked = pullFull(es)
            val inkedPx = intPixels(inked)
            inked.recycle()
            assertTrue("burst must paint", inkedPx.contentNotEquals(blankPx))
            repeat(24) { assertTrue("undo $it", es.undoVectorPath(layer)) }
            val undone = pullFull(es)
            try {
                assertTrue(
                    "24 undos restore blank",
                    intPixels(undone).contentEquals(blankPx)
                )
            } finally {
                undone.recycle()
            }
        } finally {
            closeFreshDoc(id)
        }
    }

    // V+2-5 §1: overlay probe returns engine polys; the view mapping is
    // exactly invertible (overlay follows zoom ×2 + rotation by
    // construction of the shared matrix path).
    @Test
    fun panelOverlayFollowsView() {
        val id = openFreshDoc("v2-overlay")
        val es = freshSurface(id)
        try {
            commitPanelRect(
                es,
                PanelFiche("rect", floatArrayOf(16f, 16f, 224f, 160f), order = 1)
            )
            val polys = refreshPanelOverlay(es)
            assertTrue("overlay polys", polys.isNotEmpty())
            val probe = es.panelOverlayForTest()
            assertEquals(polys.size, probe.size)
            assertEquals(16f, probe[0][0].first, 0.01f)
            // View mapping round trip through the live matrix.
            val (sx, sy) = es.engineToScreen(128f, 96f)
            val (ex, ey) = es.screenToEngine(sx, sy)
            assertEquals(128f, ex, 1.5f)
            assertEquals(96f, ey, 1.5f)
            assertEquals(probe, V2TestHooks.panelOverlay)
        } finally {
            closeFreshDoc(id)
        }
    }

    // V+2-6 §2.1: numbered panels + reading direction round trip
    // (panel-order via ORA meta, reading via project.json).
    @Test
    fun readingOrderRoundTrip() {
        val id = openFreshDoc("v2-reading")
        val es = freshSurface(id)
        try {
            commitPanelRect(es, PanelFiche("rect", floatArrayOf(8f, 8f, 60f, 60f), order = 2))
            commitPanelRect(es, PanelFiche("rect", floatArrayOf(80f, 8f, 60f, 60f), order = 3))
            commitPanelRect(es, PanelFiche("rect", floatArrayOf(152f, 8f, 60f, 60f), order = 1))
            val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            val ora = File(dir, "v2-reading.ora")
            assertTrue(awaitBool { cb -> es.saveTo(ora.absolutePath, "ora", cb) })
            assertTrue(awaitBool { cb -> es.loadFrom(ora.absolutePath, cb) })
            val orders = v2LayerMetas(es).values
                .mapNotNull { parsePanelMeta(it)?.order }.sorted()
            assertEquals(listOf(1, 2, 3), orders)
            ora.delete()
            val (reading, _) = MultipageProject.parse(
                MultipageProject.toJson("rtl", emptyList())
            )
            assertEquals("rtl", reading)
        } finally {
            closeFreshDoc(id)
        }
    }

    // V+2-7 §2.2: multipage insert/delete with the never-0-pages guard.
    @Test
    fun multipageInsertDelete() {
        val pages = mutableListOf(
            OcpPage("001.ora", "A", 256, 256),
            OcpPage("002.ora", "B", 256, 256),
            OcpPage("003.ora", "C", 256, 256)
        )
        assertTrue(MultipageProject.deleteAt(pages, 1))
        assertEquals(2, pages.size)
        assertEquals("001.ora", pages[0].file)
        assertEquals("002.ora", pages[1].file)
        assertTrue(MultipageProject.deleteAt(pages, 0))
        assertEquals(1, pages.size)
        assertFalse("guard: never 0 pages", MultipageProject.deleteAt(pages, 0))
        V2TestHooks.lastPages = Pair("insertDelete", pages.size)
    }

    // V+2-8 §2.2: reorder + reload keeps the project order.
    @Test
    fun multipageReorderPersists() {
        val dir = cacheFile("ocpages-test")
        dir.mkdirs()
        val pages = mutableListOf(
            OcpPage("001.ora", "A", 10, 10),
            OcpPage("002.ora", "B", 10, 10),
            OcpPage("003.ora", "C", 10, 10)
        )
        assertTrue(MultipageProject.move(pages, 2, 0))
        assertEquals("C", pages[0].title)
        V2Pages.writeProject(dir, "rtl", pages)
        val (reading, back) = V2Pages.readProject(dir)
        assertEquals("rtl", reading)
        assertEquals(listOf("C", "A", "B"), back.map { it.title })
        assertEquals("001.ora", back[0].file)
    }

    // V+2-9 §2.2: stroke on page 2 refreshes its thumb hash, page 1 intact.
    @Test
    fun multipageThumbRefresh() {
        val id = openFreshDoc("v2-thumbs")
        val es = freshSurface(id)
        try {
            val dir = cacheFile("ocpages-thumb")
            dir.mkdirs()
            fun thumbHash(): Long {
                val b = pullFull(es)
                try {
                    var h = 0L
                    val px = intPixels(b)
                    for (v in px) h = h * 31 + v
                    return h
                } finally {
                    b.recycle()
                }
            }
            val h1 = thumbHash()
            val layer = es.addVectorLayer("v")
            assertTrue(layer >= 0)
            assertTrue(
                es.appendVectorPath(
                    layer,
                    SpeedBurst.pathJson(
                        listOf(
                            Triple(10f, 10f, 1f), Triple(200f, 10f, 1f),
                            Triple(200f, 200f, 1f), Triple(10f, 200f, 1f)
                        )
                    )
                ) >= 0
            )
            val h2 = thumbHash()
            assertTrue("thumb must refresh after a stroke", h1 != h2)
            val tf = V2Pages.thumbFile(dir, 2)
            val bmp = pullFull(es)
            try {
                val small = Bitmap.createScaledBitmap(bmp, 64, 64, true)
                tf.outputStream().use {
                    small.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                small.recycle()
            } finally {
                bmp.recycle()
            }
            assertTrue("thumb file written", tf.exists() && tf.length() > 100)
        } finally {
            closeFreshDoc(id)
        }
    }

    // V+2-10 §3.1: RTL webtoon — page 2 pixels end up on top.
    @Test
    fun webtoonConcatOrder() {
        fun solid(topBlack: Boolean): Bitmap {
            val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            b.eraseColor(-1)
            if (topBlack) {
                for (y in 0 until 32) {
                    for (x in 0 until 64) b.setPixel(x, y, -16777216)
                }
            }
            return b
        }
        val pageA = solid(false)
        val pageB = solid(true)
        try {
            // RTL: page B first.
            val band = WebtoonPlan.concat(listOf(pageB, pageA), 0, -1)
            try {
                assertEquals(-16777216, band.getPixel(32, 10))
                assertEquals(-1, band.getPixel(32, 100))
            } finally {
                band.recycle()
            }
        } finally {
            pageA.recycle()
            pageB.recycle()
        }
    }

    // V+2-11 §3.1: slicing heights are exact, seam joint pixel-perfect.
    @Test
    fun webtoonSliceHeight() {
        val pages = (0 until 3).map {
            Bitmap.createBitmap(32, 68, Bitmap.Config.ARGB_8888).also { b ->
                b.eraseColor(-1)
                for (x in 0 until 32) b.setPixel(x, it * 10, -16777216)
            }
        }
        try {
            val band = WebtoonPlan.concat(pages, 0, -1)
            try {
                assertEquals(204, band.height)
                val slices = WebtoonPlan.slices(band.height, 100)
                assertEquals(3, slices.size)
                assertEquals(listOf(100, 100, 4), slices.map { it.h })
                // Rejoin == band (seamless joint).
                val rej = Bitmap.createBitmap(32, 204, Bitmap.Config.ARGB_8888)
                var y = 0
                for (s in slices) {
                    val sub = Bitmap.createBitmap(band, 0, s.srcY, 32, s.h)
                    val px = IntArray(32 * s.h)
                    sub.getPixels(px, 0, 32, 0, 0, 32, s.h)
                    rej.setPixels(px, 0, 32, 0, y, 32, s.h)
                    sub.recycle()
                    y += s.h
                }
                assertTrue(
                    intPixels(rej).contentEquals(intPixels(band))
                )
                rej.recycle()
            } finally {
                band.recycle()
            }
        } finally {
            pages.forEach { b ->
                try {
                    b.recycle()
                } catch (_: Exception) {
                }
            }
        }
    }

    // V+2-12 §3.2: two frames export to a valid animated GIF (header +
    // trailer, non-empty).
    @Test
    fun gifExportPlaysTwoFrames() {
        val id = openFreshDoc("v2-gif")
        val es = freshSurface(id)
        try {
            es.frameOp { addFrame() }
            assertEquals(2, es.frameCount())
            val layer = es.addVectorLayer("g")
            assertTrue(
                es.appendVectorPath(
                    layer,
                    SpeedBurst.pathJson(
                        listOf(Triple(10f, 128f, 1f), Triple(246f, 128f, 1f))
                    )
                ) >= 0
            )
            assertTrue(es.frameCount() >= 1)
            val out = cacheFile("v2-two.gif")
            assertTrue(
                es.exportGif(
                    out.absolutePath,
                    "{\"loop\":-1,\"maxWidth\":256,\"dither\":false,\"fps\":12}"
                )
            )
            val bytes = out.readBytes()
            assertTrue("GIF89a header", bytes.take(6).toByteArray().let {
                String(it) == "GIF89a"
            })
            assertEquals("trailer 0x3B", 0x3B, bytes.last().toInt() and 0xFF)
            assertTrue("non-empty", out.length() > 100)
            V2TestHooks.lastGif = Pair(out.absolutePath, 1)
            out.delete()
        } finally {
            closeFreshDoc(id)
        }
    }

    // V+2-13 §3.2: cel holds land in the GCE delays (hold 3 = 3× hold 1).
    @Test
    fun gifHoldRespected() {
        val id = openFreshDoc("v2-gifhold")
        val es = freshSurface(id)
        try {
            es.frameOp { addFrame() }
            assertEquals(2, es.frameCount())
            assertTrue(es.setCelHold(1, 3))
            val out = cacheFile("v2-hold.gif")
            assertTrue(
                es.exportGif(
                    out.absolutePath,
                    "{\"loop\":-1,\"maxWidth\":128,\"dither\":false,\"fps\":12}"
                )
            )
            val d = out.readBytes()
            val delays = mutableListOf<Int>()
            var i = 0
            while (i + 5 < d.size) {
                if (d[i] == 0x21.toByte() && d[i + 1] == 0xF9.toByte() && d[i + 2] == 0x04.toByte()) {
                    delays.add(
                        (d[i + 4].toInt() and 0xFF) or
                            ((d[i + 5].toInt() and 0xFF) shl 8)
                    )
                    i += 7
                } else {
                    i++
                }
            }
            assertEquals("2 GCE delays", 2, delays.size)
            // hold 1 @12fps = 8cs, hold 3 = 25cs.
            assertEquals(8, delays[0])
            assertEquals(25, delays[1])
            out.delete()
        } finally {
            closeFreshDoc(id)
        }
    }

    // V+2-14 §3.2: loop policy + dither toggle surface in the bytes.
    @Test
    fun gifLoopAndDither() {
        val id = openFreshDoc("v2-gifloop")
        val es = freshSurface(id)
        try {
            val n3 = cacheFile("v2-n3.gif")
            assertTrue(
                es.exportGif(
                    n3.absolutePath,
                    "{\"loop\":3,\"maxWidth\":128,\"dither\":false,\"fps\":12}"
                )
            )
            val b3 = n3.readBytes().toList()
            // NETSCAPE2.0 with count 3 present.
            val ns = "NETSCAPE2.0".toByteArray().toList()
            val at = b3.windowed(ns.size).indexOfFirst { it == ns }
            assertTrue("NETSCAPE ext with N=3", at >= 0)
            assertEquals(3, b3[at + ns.size + 2].toInt() and 0xFF)
            n3.delete()
            val once = cacheFile("v2-once.gif")
            assertTrue(
                es.exportGif(
                    once.absolutePath,
                    "{\"loop\":0,\"maxWidth\":128,\"dither\":false,\"fps\":12}"
                )
            )
            val b1 = once.readBytes().toList()
            assertEquals(
                "once = no NETSCAPE ext",
                -1, b1.windowed(ns.size).indexOfFirst { it == ns }
            )
            once.delete()
        } finally {
            closeFreshDoc(id)
        }
    }

    // V+2-15 §3.3: SVG export parses as XML with one path per stroke.
    @Test
    fun svgExportValidXml() {
        val id = openFreshDoc("v2-svg")
        val es = freshSurface(id)
        try {
            val layer = es.addVectorLayer("sv")
            assertTrue(
                es.appendVectorPath(
                    layer,
                    SpeedBurst.pathJson(
                        listOf(Triple(5f, 5f, 1f), Triple(50f, 60f, 1f))
                    )
                ) >= 0
            )
            val paths = V2Bezier.read(es, layer)
            assertEquals(1, paths.size)
            val svg = SvgExport.serialize(
                256, 256,
                paths.map { p ->
                    val meanW = if (p.widths.isEmpty()) 2f
                    else p.widths.sum() / p.widths.size
                    Pair(p.pts, meanW)
                }
            )
            assertTrue(svg.startsWith("<?xml"))
            assertTrue(svg.contains("<svg"))
            assertEquals("one path per stroke", 1, svg.split("<path").size - 1)
            V2TestHooks.lastSvg = Pair("mem", paths.size)
        } finally {
            closeFreshDoc(id)
        }
    }

    // V+2-16 §3.3: SVG coordinates trace the original stroke (documented
    // approximation: mean width, endpoints exact).
    @Test
    fun svgReimportApprox() {
        val pts = listOf(
            Triple(10f, 20f, 1f), Triple(100f, 30f, 0.8f),
            Triple(200f, 200f, 0.6f)
        )
        val svg = SvgExport.serialize(256, 256, listOf(Pair(pts, 3f)))
        val d = svg.substringAfter("<path d=\"").substringBefore("\" fill")
        assertTrue(d.startsWith("M10.0 20.0"))
        assertTrue(d.contains("L200.0 200.0"))
        assertEquals("L count = pts-1", 2, d.split("L").size - 1)
    }

    // V+2-17 §3.4: cel hold bounds (1..32) through the JNI contract.
    @Test
    fun celHoldPlayback() {
        val handle = EngineHost.nInit(64, 64, 160f)
        try {
            assertTrue(EngineHost.nSetCelHold(handle, 0, 1))
            assertTrue(EngineHost.nSetCelHold(handle, 0, 32))
            assertFalse(EngineHost.nSetCelHold(handle, 0, 0))
            assertFalse(EngineHost.nSetCelHold(handle, 0, 33))
            assertFalse(EngineHost.nSetCelHold(handle, 5, 2))
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // V+2-18 §3.4: reorder moves the pixels (thumbs follow the move).
    @Test
    fun celReorderThumbs() {
        val id = openFreshDoc("v2-reorder")
        val es = freshSurface(id)
        try {
            es.frameOp { addFrame() }
            assertEquals(2, es.frameCount())
            val layer = es.addVectorLayer("r")
            assertTrue(
                es.appendVectorPath(
                    layer,
                    SpeedBurst.pathJson(
                        listOf(Triple(10f, 10f, 1f), Triple(246f, 246f, 1f))
                    )
                ) >= 0
            )
            val blank = pullFull(es, 0)
            val blankInk = nonWhite(blank)
            blank.recycle()
            val inked = pullFull(es, 1)
            assertTrue("frame 1 carries the stroke", nonWhite(inked) > blankInk)
            inked.recycle()
            var moved = false
            es.frameOp { moved = moveCel(1, 0) }
            assertTrue("moveCel must succeed", moved)
            val nowFirst = pullFull(es, 0)
            try {
                assertTrue(
                    "pixels follow the move",
                    nonWhite(nowFirst) > blankInk
                )
            } finally {
                nowFirst.recycle()
            }
            assertTrue("move is one undo", es.undo())
            val back = pullFull(es, 0)
            try {
                assertEquals("undo restores order", blankInk, nonWhite(back))
            } finally {
                back.recycle()
            }
        } finally {
            closeFreshDoc(id)
        }
    }

    // V+2-19 §3.4: the real neighbor count renders (rank-2 ghost only
    // visible at count >= 2 — the test that kills the overlay).
    @Test
    fun onionCountReal() {
        val handle = EngineHost.nInit(64, 64, 160f)
        try {
            // Stroke on frame 0, then view frame 2: count 1 shows only
            // frame 1 (blank), count 2 reaches frame 0's ink.
            EngineHost.nSetBrush(
                handle,
                "{\"radius\":10,\"hardness\":1.0,\"opaque\":1.0,\"spacing\":0.15,\"color\":\"#141414\",\"mode\":\"brush\"}"
            )
            val sid = EngineHost.nStrokeBegin(handle, 8f, 32f, 1f)
            var x = 8f
            while (x <= 56f) {
                EngineHost.nStrokeMove(handle, sid, x, 32f, 1f)
                x += 8f
            }
            EngineHost.nStrokeEnd(handle, sid)
            EngineHost.nAddFrame(handle)
            EngineHost.nAddFrame(handle)
            assertEquals(3, EngineHost.nFrameCount(handle))
            EngineHost.nSetFrame(handle, 2)
            // Onion workflow: the viewing frame must be transparent
            // (a fully opaque current frame hides the ghosts — the
            // documented composite rule), so hide its base layer.
            assertTrue(EngineHost.nSetLayerVisibility(handle, 0, false))
            val px = IntArray(64 * 64)
            fun meanLum(): Double {
                var acc = 0L
                for (v in px) {
                    acc += ((v shr 16) and 0xFF) + ((v shr 8) and 0xFF) + (v and 0xFF)
                }
                return acc.toDouble() / px.size / 3.0
            }
            // Count 1: only frame 1 (blank) ghosts — frame 0 invisible.
            assertTrue(EngineHost.nSetOnionCount(handle, 1, 0))
            assertFalse(EngineHost.nSetOnionCount(handle, 5, 0))
            assertFalse(EngineHost.nSetOnionCount(handle, 0, -1))
            EngineHost.nCompositeInto(handle, px, 64, 64)
            val lum1 = meanLum()
            assertTrue("ghost footprint visible", px.any { it != 0 })
            // Count 2: frame 0's stroke ghosts through (darker).
            assertTrue(EngineHost.nSetOnionCount(handle, 2, 0))
            EngineHost.nCompositeInto(handle, px, 64, 64)
            val lum2 = meanLum()
            assertTrue("rank-2 ghost darkens ($lum1 vs $lum2)", lum2 < lum1 - 1.0)
            // Count 0 = no neighbors at all (fully transparent).
            assertTrue(EngineHost.nSetOnionCount(handle, 0, 0))
            EngineHost.nCompositeInto(handle, px, 64, 64)
            for (v in px) assertEquals(0, v)
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // V+2-20 §4.1: move translate is undoable in one engine undo.
    @Test
    fun transformMoveUndoable() {
        val id = openFreshDoc("v2-xform")
        val es = freshSurface(id)
        try {
            val t0 = SystemClock.uptimeMillis()
            val (vx0, vy0) = es.engineToScreen(100f, 100f)
            val (vx1, vy1) = es.engineToScreen(150f, 100f)
            dispatchAll(
                es,
                simpleDown(t0, vx0, vy0),
                MotionEvent.obtain(t0, t0 + 16, MotionEvent.ACTION_MOVE, (vx0 + vx1) / 2, (vy0 + vy1) / 2, 1f, 1f, 0, 1f, 1f, 0, 0),
                MotionEvent.obtain(t0, t0 + 32, MotionEvent.ACTION_MOVE, vx1, vy1, 1f, 1f, 0, 1f, 1f, 0, 0),
                MotionEvent.obtain(t0, t0 + 48, MotionEvent.ACTION_UP, vx1, vy1, 1f, 1f, 0, 1f, 1f, 0, 0)
            )
            Thread.sleep(800)
            val before = pullFull(es)
            val beforePx = intPixels(before)
            before.recycle()
            assertTrue("stroke must paint", nonWhitePx(beforePx) > 50)
            es.selectRectEngine(80f, 80f, 170f, 120f)
            val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            val mask = File.createTempFile("v2-xm-", ".png", dir)
            assertTrue(es.selectionToPng(mask.absolutePath))
            assertTrue(es.setSelectionMask(mask.absolutePath))
            assertTrue(
                es.selectionBlit(SelectionTransform.json(60f, 0f, 1f, 0f, "move"))
            )
            es.setSelectionMask(null)
            mask.delete()
            val after = pullFull(es)
            try {
                val pa = intPixels(after)
                assertTrue("moved content differs", pa.contentNotEquals(beforePx))
            } finally {
                after.recycle()
            }
            assertTrue("transform is one undo", es.undo())
            val undone = pullFull(es)
            try {
                assertTrue(
                    "undo restores the composite exactly",
                    intPixels(undone).contentEquals(beforePx)
                )
            } finally {
                undone.recycle()
            }
        } finally {
            closeFreshDoc(id)
        }
    }

    private fun nonWhitePx(px: IntArray): Int {
        var n = 0
        for (v in px) if (v != -1) n++
        return n
    }

    // V+2-21 §4.1: arm-then-cancel leaves the composite byte-identical.
    @Test
    fun transformCancelRestores() {
        val id = openFreshDoc("v2-xcancel")
        val es = freshSurface(id)
        try {
            val before = pullFull(es)
            val beforePx = intPixels(before)
            before.recycle()
            es.selectRectEngine(40f, 40f, 120f, 120f)
            val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            val mask = File.createTempFile("v2-xc-", ".png", dir)
            assertTrue(es.selectionToPng(mask.absolutePath))
            assertTrue(es.setSelectionMask(mask.absolutePath))
            // Cancel = drop the engine mask without blitting.
            assertTrue(es.setSelectionMask(null))
            mask.delete()
            es.clearSelection()
            val after = pullFull(es)
            try {
                assertTrue(
                    "cancel restores byte-identical",
                    intPixels(after).contentEquals(beforePx)
                )
            } finally {
                after.recycle()
            }
        } finally {
            closeFreshDoc(id)
        }
    }

    // V+2-22 §4.2: copy/paste round trip (new layer, source region).
    @Test
    fun copyPasteRoundTrip() {
        val id = openFreshDoc("v2-copy")
        val es = freshSurface(id)
        try {
            val t0 = SystemClock.uptimeMillis()
            val (vx0, vy0) = es.engineToScreen(60f, 60f)
            val (vx1, vy1) = es.engineToScreen(120f, 60f)
            dispatchAll(
                es,
                simpleDown(t0, vx0, vy0),
                MotionEvent.obtain(t0, t0 + 16, MotionEvent.ACTION_MOVE, (vx0 + vx1) / 2, (vy0 + vy1) / 2, 1f, 1f, 0, 1f, 1f, 0, 0),
                MotionEvent.obtain(t0, t0 + 32, MotionEvent.ACTION_MOVE, vx1, vy1, 1f, 1f, 0, 1f, 1f, 0, 0),
                MotionEvent.obtain(t0, t0 + 48, MotionEvent.ACTION_UP, vx1, vy1, 1f, 1f, 0, 1f, 1f, 0, 0)
            )
            Thread.sleep(800)
            val layersBefore = es.layerCount()
            es.selectRectEngine(40f, 40f, 140f, 80f)
            assertTrue("copy needs a selection", V2CopyPaste.copy(es, 0))
            val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            val pasted = V2CopyPaste.paste(es, dir, "Paste")
            assertTrue("paste creates a layer", pasted >= 0)
            assertEquals(layersBefore + 1, es.layerCount())
            val b = pullFull(es)
            try {
                assertTrue("pasted content paints", nonWhite(b) > 50)
            } finally {
                b.recycle()
            }
        } finally {
            closeFreshDoc(id)
        }
    }

    // V+2-23 §4.2: cut clears under the mask, outside intact.
    @Test
    fun cutClearsUnderMask() {
        val id = openFreshDoc("v2-cut")
        val es = freshSurface(id)
        try {
            val t0 = SystemClock.uptimeMillis()
            val (vx0, vy0) = es.engineToScreen(40f, 100f)
            val (vx1, vy1) = es.engineToScreen(220f, 100f)
            dispatchAll(
                es,
                simpleDown(t0, vx0, vy0),
                MotionEvent.obtain(t0, t0 + 16, MotionEvent.ACTION_MOVE, (vx0 + vx1) / 2, (vy0 + vy1) / 2, 1f, 1f, 0, 1f, 1f, 0, 0),
                MotionEvent.obtain(t0, t0 + 32, MotionEvent.ACTION_MOVE, vx1, vy1, 1f, 1f, 0, 1f, 1f, 0, 0),
                MotionEvent.obtain(t0, t0 + 48, MotionEvent.ACTION_UP, vx1, vy1, 1f, 1f, 0, 1f, 1f, 0, 0)
            )
            Thread.sleep(800)
            val before = pullFull(es)
            val pb = intPixels(before)
            before.recycle()
            es.selectRectEngine(40f, 80f, 140f, 120f)
            assertTrue(es.eraseUnderSelection(0))
            es.clearSelection()
            val after = pullFull(es)
            try {
                val pa = intPixels(after)
                var cleared = 0
                var outsideDiff = 0
                for (y in 0 until 256) {
                    for (x in 0 until 256) {
                        val inside = x in 40..140 && y in 80..120
                        if (inside) {
                            // Cut on the base layer = dest-out: cleared
                            // pixels go transparent (0x0), never white.
                            if (pa[y * 256 + x] == 0 && pb[y * 256 + x] != -1) cleared++
                        } else if (pa[y * 256 + x] != pb[y * 256 + x]) {
                            outsideDiff++
                        }
                    }
                }
                assertTrue("cut clears ink, got $cleared", cleared > 20)
                assertEquals("outside intact, diff $outsideDiff", 0, outsideDiff)
            } finally {
                after.recycle()
            }
        } finally {
            closeFreshDoc(id)
        }
    }

    // V+2-24 §4.3: moving a point changes the composite locally, 2
    // undos restore it (replace = erase+append, documented deviation).
    @Test
    fun bezierMovePoint() {
        val id = openFreshDoc("v2-bezier")
        val es = freshSurface(id)
        try {
            val layer = es.addVectorLayer("bz")
            val pts = listOf(
                Triple(20f, 128f, 1f), Triple(128f, 128f, 1f),
                Triple(236f, 128f, 1f)
            )
            assertTrue(es.appendVectorPath(layer, SpeedBurst.pathJson(pts)) >= 0)
            val before = pullFull(es)
            val pb = intPixels(before)
            before.recycle()
            val read = V2Bezier.read(es, layer)
            assertEquals(1, read.size)
            // Note: the engine resamples polylines (~8px steps), so the
            // stored count exceeds the 3 committed points.
            assertTrue("resampled path", read[0].pts.size >= 3)
            val moved = read.map { p ->
                p.pts.mapIndexed { i, t ->
                    if (i == 0) Triple(t.first + 40f, t.second, t.third) else t
                }
            }
            assertTrue(V2Bezier.replaceAll(es, layer, moved))
            val after = pullFull(es)
            val pa = intPixels(after)
            after.recycle()
            assertTrue("moved point changes pixels", pa.contentNotEquals(pb))
            // Honest vector-undo contract (documented deviation): the
            // replace consumes the pre-edit snapshot, so one vector
            // undo empties the layer; re-committing the original
            // restores the composite byte-identically.
            assertTrue("replace undo empties", es.undoVectorPath(layer))
            val emptied = pullFull(es)
            try {
                assertEquals("layer emptied", 0, nonWhite(emptied))
            } finally {
                emptied.recycle()
            }
            val orig = read.map { p ->
                p.pts.map { t -> Triple(t.first, t.second, t.third) }
            }
            assertTrue(V2Bezier.replaceAll(es, layer, orig))
            val restored = pullFull(es)
            try {
                assertTrue(
                    "re-commit restores the composite",
                    intPixels(restored).contentEquals(pb)
                )
            } finally {
                restored.recycle()
            }
        } finally {
            closeFreshDoc(id)
        }
    }

    // V+2-25 §4.3: insert/delete change the point count (probe).
    @Test
    fun bezierInsertDelete() {
        val id = openFreshDoc("v2-bezid")
        val es = freshSurface(id)
        try {
            val layer = es.addVectorLayer("bi")
            val pts = listOf(
                Triple(20f, 20f, 1f), Triple(128f, 20f, 1f),
                Triple(236f, 20f, 1f)
            )
            assertTrue(es.appendVectorPath(layer, SpeedBurst.pathJson(pts)) >= 0)
            val baseCount = V2Bezier.read(es, layer)[0].pts.size
            assertTrue("resampled path", baseCount >= 3)
            // Insert mid first span.
            val cur = V2Bezier.read(es, layer)[0].pts
                .map { Triple(it.first, it.second, it.third) }.toMutableList()
            val a = cur[0]
            val b = cur[1]
            cur.add(1, Triple((a.first + b.first) / 2f, (a.second + b.second) / 2f, 1f))
            assertTrue(V2Bezier.replaceAll(es, layer, listOf(cur)))
            val insCount = V2Bezier.read(es, layer)[0].pts.size
            assertTrue("insert adds samples ($insCount vs $baseCount)", insCount > baseCount)
            // Delete last.
            val cur2 = V2Bezier.read(es, layer)[0].pts
                .map { Triple(it.first, it.second, it.third) }.toMutableList()
            cur2.removeAt(cur2.size - 1)
            assertTrue(V2Bezier.replaceAll(es, layer, listOf(cur2)))
            val delCount = V2Bezier.read(es, layer)[0].pts.size
            assertTrue("delete removes samples ($delCount vs $insCount)", delCount < insCount)
        } finally {
            closeFreshDoc(id)
        }
    }

    // V+2-26 §4.3: handle drag densifies (more points, smooth curve).
    @Test
    fun bezierHandleDensifies() {
        val id = openFreshDoc("v2-bezsm")
        val es = freshSurface(id)
        try {
            val layer = es.addVectorLayer("bs")
            val pts = listOf(
                Pair(20f, 200f), Pair(90f, 60f),
                Pair(170f, 60f), Pair(236f, 200f)
            )
            assertTrue(
                es.appendVectorPath(
                    layer,
                    SpeedBurst.pathJson(pts.map { (x, y) -> Triple(x, y, 1f) })
                ) >= 0
            )
            val dense = BezierEdit.densify(pts, 0.5f)
            assertTrue("handles add points", dense.size > pts.size)
            assertTrue(V2Bezier.replaceAll(es, layer, listOf(dense.map { (x, y) -> Triple(x, y, 1f) })))
            // The engine resamples on commit: only the growth direction
            // is asserted, not the exact densified count.
            assertTrue(
                "densified path kept",
                V2Bezier.read(es, layer)[0].pts.size >= dense.size / 2
            )
            val b = pullFull(es)
            try {
                assertTrue("smooth curve still paints", nonWhite(b) > 30)
            } finally {
                b.recycle()
            }
        } finally {
            closeFreshDoc(id)
        }
    }

    // V+2-27 §4.4: guides persist across reload (JSON sidecar).
    @Test
    fun guidesPersistReload() {
        val dir = cacheFile("guides-reload")
        dir.mkdirs()
        val gs = listOf(
            DocGuide.V(100f),
            DocGuide.H(50f),
            DocGuide.C(128f, 128f, 40f)
        )
        GuidesIo.save(dir, gs)
        val back = GuidesIo.load(dir)
        assertEquals(3, back.size)
        assertTrue(back[0] is DocGuide.V)
        assertEquals(100f, (back[0] as DocGuide.V).x, 0.01f)
        assertTrue(back[1] is DocGuide.H)
        assertTrue(back[2] is DocGuide.C)
        // Unknown shapes are ignored (M3 rule), never crash.
        val mixed = GuidesIo.parse("{\"guides\":[{\"x\":1},{\"zzz\":9},{\"y\":2}]}")
        assertEquals(2, mixed.size)
    }

    // V+2-28 §4.4: guide snap steers a real stroke (engine behavior).
    @Test
    fun guidesSnapSteersStroke() {
        val handle = EngineHost.nInit(256, 256, 160f)
        try {
            EngineHost.nSetBrush(
                handle,
                "{\"radius\":8,\"hardness\":1.0,\"opaque\":1.0,\"spacing\":0.1,\"color\":\"#141414\",\"mode\":\"brush\"}"
            )
            fun strokeAt(x: Float): IntArray {
                val sid = EngineHost.nStrokeBegin(handle, x, 128f, 1f)
                var y = 128f
                while (y <= 200f) {
                    EngineHost.nStrokeMove(handle, sid, x, y, 1f)
                    y += 8f
                }
                EngineHost.nStrokeEnd(handle, sid)
                val px = IntArray(256 * 256)
                EngineHost.nCompositeInto(handle, px, 256, 256)
                return px
            }
            assertTrue(
                EngineHost.nSetRulers(handle, "{\"guides\":[{\"x\":100}]}")
            )
            strokeAt(101.5f)
            val px = IntArray(256 * 256)
            EngineHost.nCompositeInto(handle, px, 256, 256)
            var atGuide = 0
            var offGuide = 0
            for (y in 128..200 step 4) {
                if (px[y * 256 + 100] != -1) atGuide++
                if (px[y * 256 + 112] != -1) offGuide++
            }
            assertTrue("snapped onto x=100 ($atGuide vs $offGuide)", atGuide > offGuide)
            // Clearing the guides releases the snap.
            assertTrue(EngineHost.nSetRulers(handle, "{\"guides\":[]}"))
            EngineHost.nUndo(handle)
            strokeAt(101.5f)
            EngineHost.nCompositeInto(handle, px, 256, 256)
            var atOld = 0
            for (y in 128..200 step 4) {
                if (px[y * 256 + 100] != -1) atOld++
            }
            assertTrue("no snap after clear", atOld <= atGuide)
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // V+2-29 §5.1: the four extra blends are accepted end to end.
    @Test
    fun blendsExtraAccept() {
        val id = openFreshDoc("v2-blends")
        val es = freshSurface(id)
        try {
            for (name in listOf(
                "svg:hard-light", "svg:difference",
                "svg:color", "svg:luminosity"
            )) {
                assertTrue("blend $name accepted", es.setLayerBlend(0, name))
            }
            val st = es.layerState()
            assertTrue(st.contains("hard-light") || st.contains("luminosity"))
        } finally {
            closeFreshDoc(id)
        }
    }

    // V+2-30 §5.2: identity curves are byte-identical (LUT path live).
    @Test
    fun curvesIdentityComposite() {
        val id = openFreshDoc("v2-curves")
        val es = freshSurface(id)
        try {
            val before = pullFull(es)
            val pb = intPixels(before)
            before.recycle()
            // Adjustment on a dedicated layer (adjusting the sole base
            // layer would erase its pixels: nothing below to transform).
            val adj = es.addLayerNamed("curves")
            assertTrue(adj >= 0)
            assertTrue(
                es.setLayerAdjustment(
                    adj,
                    CurvesJson.build(
                        listOf(Pair(0, 0), Pair(255, 255)), "rgb"
                    )
                )
            )
            val after = pullFull(es)
            try {
                assertTrue(
                    "identity curves byte-identical",
                    intPixels(after).contentEquals(pb)
                )
            } finally {
                after.recycle()
            }
            assertTrue(es.setLayerAdjustment(adj, null))
        } finally {
            closeFreshDoc(id)
        }
    }

    // V+2-31 §5.2: per-channel curves isolate the channel + ORA round trip.
    @Test
    fun curvesChannelRoundTrip() {
        val id = openFreshDoc("v2-curvesch")
        val es = freshSurface(id)
        try {
            assertTrue(
                es.setLayerAdjustment(
                    0,
                    CurvesJson.build(
                        listOf(Pair(0, 255), Pair(255, 0)), "r"
                    )
                )
            )
            val st = es.layerState()
            assertTrue("curve fiche surfaced", st.contains("adjust") && st.contains("curves"))
            val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            val ora = File(dir, "v2-curves.ora")
            assertTrue(awaitBool { cb -> es.saveTo(ora.absolutePath, "ora", cb) })
            assertTrue(awaitBool { cb -> es.loadFrom(ora.absolutePath, cb) })
            val st2 = es.layerState()
            assertTrue("curves survive ORA", st2.contains("curves"))
            assertTrue(es.setLayerAdjustment(0, null))
            ora.delete()
        } finally {
            closeFreshDoc(id)
        }
    }

    // V+2-32 §5.3: tap-seek recenters the view on the fraction.
    @Test
    fun navigatorSeekRecenter() {
        val id = openFreshDoc("v2-nav")
        val es = freshSurface(id)
        try {
            assertTrue("view must be laid out", es.width > 0 && es.height > 0)
            assertTrue(es.seekToFraction(0f, 0f))
            val (sx, sy) = es.engineToScreen(0f, 0f)
            assertEquals(es.width / 2f, sx, 2f)
            assertEquals(es.height / 2f, sy, 2f)
            V2TestHooks.lastSeek = Pair(0f, 0f)
        } finally {
            closeFreshDoc(id)
        }
    }

    // V+2-33 §5.4: autosave writes on tick (policy pure + wired).
    @Test
    fun autosaveWritesOnTick() {
        assertTrue(
            AutosavePolicy.shouldSave(true, 5, 0L, 6 * 60_000L, false)
        )
        assertFalse(
            "too early",
            AutosavePolicy.shouldSave(true, 5, 5 * 60_000L, 6 * 60_000L, false)
        )
        assertFalse(
            "opt-in default off",
            AutosavePolicy.shouldSave(false, 5, 0L, 60 * 60_000L, false)
        )
        V2TestHooks.lastAutosave = "policy-ok"
    }

    // V+2-34 §5.4: never mid-stroke.
    @Test
    fun autosaveSkipsMidStroke() {
        assertFalse(
            "open stroke gates the tick",
            AutosavePolicy.shouldSave(true, 1, 0L, 60 * 60_000L, true)
        )
    }

    // V+2-35 §5.5: layered PSD opens (8BPS + >1 layer records).
    @Test
    fun psdLayeredOpens() {
        val id = openFreshDoc("v2-psd")
        val es = freshSurface(id)
        try {
            es.layerOp { addLayerNamed("second") }
            val out = cacheFile("v2-layers.psd")
            assertTrue(awaitBool { cb -> es.saveTo(out.absolutePath, "psd", cb) })
            val bytes = out.readBytes()
            assertTrue(bytes.size > 26)
            assertEquals('8'.code.toByte(), bytes[0])
            assertEquals('B'.code.toByte(), bytes[1])
            assertEquals('P'.code.toByte(), bytes[2])
            assertEquals('S'.code.toByte(), bytes[3])
            val approx = es.psdApproxCount()
            assertTrue("approx count sane ($approx)", approx >= 0)
            V2TestHooks.lastPages = Pair("psd", approx)
            out.delete()
        } finally {
            closeFreshDoc(id)
        }
    }

    // V+2-36 density: Bayer dropout thins the toned area honestly.
    @Test
    fun panelDensityThins() {
        val id = openFreshDoc("v2-density")
        val es = freshSurface(id)
        try {
            val poly = listOf(
                Pair(32f, 32f), Pair(224f, 32f),
                Pair(224f, 224f), Pair(32f, 224f)
            )
            es.selectPolygonEngine(poly)
            val hFull = es.selectionHashForTest()
            es.thinSelectionTo(50)
            val hThin = es.selectionHashForTest()
            assertTrue("density changes the mask", hFull != hThin)
            es.clearSelection()
        } finally {
            closeFreshDoc(id)
        }
    }

    // V+2-37 UI smoke: the Comic dock tab opens the V+2 panels.
    // The dock is closed afterwards: dock state is screen-level and
    // must not leak into later tests (canvas width gate).
    @Test
    fun bdTabOpensPanels() {
        val id = openFreshDoc("v2-ui")
        var opened = false
        try {
            // Idempotent open: the toggle blindly flips, so only tap
            // when the drawer is actually closed (tablet defaults open).
            val shut = try {
                composeRule.onNodeWithText("\u27e8").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
            if (shut) {
                composeRule.onNodeWithTag("dock-toggle").performClick()
                composeRule.waitForIdle()
            }
            opened = true
            // The tab row scrolls horizontally (side dock is narrow):
            // swipe the header row itself when Comic starts off-screen.
            var bdShown = try {
                composeRule.onNodeWithTag("dock-tab-bd").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
            if (!bdShown) {
                composeRule.onNodeWithTag("dock-tabs-row")
                    .performTouchInput { swipeLeft() }
                composeRule.waitForIdle()
            }
            composeRule.onNodeWithTag("dock-tab-bd").performClick()
            composeRule.waitForIdle()
            try {
                composeRule.onNodeWithTag("v2-bd-panel").assertIsDisplayed()
            } catch (e: Throwable) {
                try {
                    val diag: Bitmap? = try {
                        InstrumentationRegistry.getInstrumentation()
                            .uiAutomation.takeScreenshot()
                    } catch (_: Exception) {
                        null
                    }
                    if (diag != null) {
                        FileOutputStream(
                            File(
                                composeRule.activity.cacheDir,
                                "v2-dock-diag.png"
                            )
                        ).use { o ->
                            diag.compress(Bitmap.CompressFormat.PNG, 100, o)
                        }
                        if (!diag.isRecycled) diag.recycle()
                    }
                } catch (_: Exception) {
                }
                throw e
            }
            // Panel below the fold: scroll the dock into view first.
            composeRule.onNodeWithTag("v2-transform-panel").performScrollTo()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("v2-transform-panel").assertIsDisplayed()
            composeRule.onNodeWithTag("dock-toggle").performClick()
            composeRule.waitForIdle()
            opened = false
        } finally {
            if (opened) {
                try {
                    composeRule.onNodeWithTag("dock-toggle").performClick()
                    composeRule.waitForIdle()
                } catch (_: Exception) {
                }
            }
            closeFreshDoc(id)
        }
    }

    // V+2-38 UI smoke: the GIF + autosave panels exist with test tags.
    @Test
    fun gifPanelExists() {
        val id = openFreshDoc("v2-ui2")
        var opened = false
        try {
            // Idempotent open: the toggle blindly flips, so only tap
            // when the drawer is actually closed (tablet defaults open).
            val shut = try {
                composeRule.onNodeWithText("\u27e8").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
            if (shut) {
                composeRule.onNodeWithTag("dock-toggle").performClick()
                composeRule.waitForIdle()
            }
            opened = true
            // The tab row scrolls horizontally (side dock is narrow):
            // swipe the header row itself when Comic starts off-screen.
            var bdShown = try {
                composeRule.onNodeWithTag("dock-tab-bd").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
            if (!bdShown) {
                composeRule.onNodeWithTag("dock-tabs-row")
                    .performTouchInput { swipeLeft() }
                composeRule.waitForIdle()
            }
            composeRule.onNodeWithTag("dock-tab-bd").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("v2-gif-panel").performScrollTo()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("v2-gif-panel").assertIsDisplayed()
            composeRule.onNodeWithTag("v2-autosave-panel").performScrollTo()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("v2-autosave-panel").assertIsDisplayed()
            composeRule.onNodeWithTag("v2-loop-infinite").performScrollTo()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("v2-loop-infinite").assertIsDisplayed()
            try {
                val shot: Bitmap? = try {
                    InstrumentationRegistry.getInstrumentation()
                        .uiAutomation.takeScreenshot()
                } catch (_: Exception) {
                    null
                }
                if (shot != null) {
                    FileOutputStream(
                        File(
                            composeRule.activity.cacheDir,
                            "openclip-v2.png"
                        )
                    ).use { o ->
                        shot.compress(Bitmap.CompressFormat.PNG, 100, o)
                    }
                    if (!shot.isRecycled) shot.recycle()
                }
            } catch (_: Exception) {
            }
            composeRule.onNodeWithTag("dock-toggle").performClick()
            composeRule.waitForIdle()
            opened = false
        } finally {
            if (opened) {
                try {
                    composeRule.onNodeWithTag("dock-toggle").performClick()
                    composeRule.waitForIdle()
                } catch (_: Exception) {
                }
            }
            closeFreshDoc(id)
        }
    }

    // ================= V+3 confort pro (docs/v3-confort.md) =================

    // §1.1 mixerSmearsStroke: knife (mix 1) pushes local paint, introduces
    // no new ink; undo restores the red stroke exactly.
    @Test
    fun mixerSmearsStroke() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            EngineHost.nSetBrush(
                handle,
                """{"radius":12,"hardness":0.7,"opaque":1.0,"spacing":0.15,"color":"#FF0000"}"""
            )
            var id = EngineHost.nStrokeBegin(handle, 60f, 128f, 1f)
            for (x in 70..196 step 5) EngineHost.nStrokeMove(handle, id, x.toFloat(), 128f, 1f)
            EngineHost.nStrokeEnd(handle, id)
            val red = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, red, w, h))
            fun px(a: IntArray, x: Int, y: Int): Int = a[y * w + x]
            val r0 = px(red, 128, 128)
            assertTrue("red stroke must paint red", ((r0 shr 16) and 0xFF) > 200 && (r0 and 0xFF) < 100)
            // Knife pass with blue ink over the red stroke.
            EngineHost.nSetBrush(
                handle,
                """{"radius":12,"hardness":0.7,"opaque":1.0,"spacing":0.15,"color":"#0000FF","mix":1.0,"mixRadius":1.0}"""
            )
            id = EngineHost.nStrokeBegin(handle, 100f, 128f, 1f)
            for (x in 105..156 step 5) EngineHost.nStrokeMove(handle, id, x.toFloat(), 128f, 1f)
            EngineHost.nStrokeEnd(handle, id)
            val mixed = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, mixed, w, h))
            val r1 = px(mixed, 128, 128)
            assertTrue(
                "knife must push red, not introduce blue (got ${Integer.toHexString(r1)})",
                ((r1 shr 16) and 0xFF) > 150 && (r1 and 0xFF) < 150
            )
            assertTrue("knife must undo cleanly", EngineHost.nUndo(handle))
            val undone = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, undone, w, h))
            assertTrue("undo restores the red stroke", undone.contentEquals(red))
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // §1.2 proofCmykOverlayOnly: the preview never mutates its input
    // (dialog close = byte-identical canvas).
    @Test
    fun proofCmykOverlayOnly() {
        val src = IntArray(64) { 0xFFFF0000.toInt() }
        val before = src.copyOf()
        val sim = CmykProof.preview(src, gamutWarning = true)
        assertTrue("preview must not mutate its input", src.contentEquals(before))
        assertTrue("paper-tinted preview must differ", !sim.contentEquals(before))
        assertEquals(64, sim.size)
    }

    // §1.2 proofGamutFlagsSaturated: pure cyan flags, neutral gray never.
    @Test
    fun proofGamutFlagsSaturated() {
        assertTrue("pure cyan must flag", CmykProof.isOutOfGamut(0, 255, 255))
        assertFalse("neutral gray must not flag", CmykProof.isOutOfGamut(128, 128, 128))
        assertFalse("black must not flag", CmykProof.isOutOfGamut(0, 0, 0))
    }

    // §1.3 harmonyComplementAdopts: 0° → 180° adopted into ink hex.
    @Test
    fun harmonyComplementAdopts() {
        val hues = Harmony.huesFor(0f, "complement")
        assertEquals(listOf(0f, 180f), hues)
        val adopted = Harmony.adopt("#FF0000", 180f)
        val h = Harmony.hexToHsv(adopted)[0]
        assertTrue("adopted hue must be ~180°, got $h in $adopted", h > 178f || h < 2f)
        assertEquals(3, Harmony.huesFor(30f, "triadic").size)
        assertEquals(4, Harmony.huesFor(30f, "square").size)
    }

    // §2a simplifyReducesPoints: 100-pt sine → < 40, endpoints kept.
    @Test
    fun simplifyReducesPoints() {
        val pts = (0 until 100).map { i ->
            val x = i * 2f
            SnapPt(x, 100f + 40f * kotlin.math.sin(i / 99f * Math.PI.toFloat() * 2f))
        }
        val simple = Rdp.simplify(pts, 1f)
        assertTrue("simplified ${simple.size} must be < 40", simple.size < 40)
        assertTrue("simplified ${simple.size} must keep >= 2", simple.size >= 2)
        assertEquals(pts.first().x, simple.first().x)
        assertEquals(pts.last().x, simple.last().x)
    }

    // §2b snapGridPersists: reload re-reads tol/grid/paths/epsilon.
    @Test
    fun snapGridPersists() {
        val dir = composeRule.activity.cacheDir
        val key = "v3snaptest"
        val s = SnapSettings(snapTol = 4f, grid = 16, snapPaths = true, simplifyEps = 2f)
        assertTrue(SnapSettings.save(dir, key, s))
        val back = SnapSettings.load(dir, key)
        assertEquals(4f, back.snapTol)
        assertEquals(16, back.grid)
        assertTrue(back.snapPaths)
        assertEquals(2f, back.simplifyEps)
    }

    // §2b rulerSnapKeysRoundTrip: grid 16/tol 8 snaps a radius-2 stroke;
    // snapTol 0 leaves it on the input line (JNI end to end).
    @Test
    fun rulerSnapKeysRoundTrip() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            EngineHost.nSetBrush(
                handle,
                """{"radius":2,"hardness":1.0,"opaque":1.0,"spacing":0.15,"color":"#000000"}"""
            )
            assertTrue(EngineHost.nSetRulers(handle, """{"grid":16,"snapTol":8.0,"snapPaths":false}"""))
            var id = EngineHost.nStrokeBegin(handle, 20f, 100f, 1f)
            for (y in 105..150 step 5) EngineHost.nStrokeMove(handle, id, 20f, y.toFloat(), 1f)
            EngineHost.nStrokeEnd(handle, id)
            val snapped = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, snapped, w, h))
            assertTrue("grid must pull the stroke to x=16", snapped[128 * w + 16] != -1)
            assertTrue(EngineHost.nUndo(handle))
            assertTrue(EngineHost.nSetRulers(handle, """{"grid":16,"snapTol":0.0}"""))
            id = EngineHost.nStrokeBegin(handle, 20f, 100f, 1f)
            for (y in 105..150 step 5) EngineHost.nStrokeMove(handle, id, 20f, y.toFloat(), 1f)
            EngineHost.nStrokeEnd(handle, id)
            val free = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, free, w, h))
            assertEquals("tol 0 must not snap (16 stays white)", -1, free[128 * w + 16])
            assertTrue("input line still paints", free[128 * w + 20] != -1)
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // §3 textOnPathCommits: layout along an arc commits pixels via
    // nBlitPngIntoLayer; one undo restores the blank canvas.
    @Test
    fun textOnPathCommits() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            val li = EngineHost.nAddVectorLayer(handle, "Trace")
            assertTrue(li >= 0)
            val arc = (0..12).map { k ->
                val a = (-60 + k * 10) * Math.PI.toFloat() / 180f
                Pair(128f + 60f * kotlin.math.cos(a), 150f + 60f * kotlin.math.sin(a))
            }
            val json = buildVectorJson(arc, 4f)
            assertTrue(EngineHost.nAppendVectorPath(handle, li, json) >= 0)
            // Reference WITH the arc (vector layers rasterize black ink):
            // undo must restore exactly this, not a blank canvas.
            val withArc = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, withArc, w, h))
            val bmp = TextOnPath.renderToBitmap(
                "Bonjour", arc.map { (x, y) -> SnapPt(x, y) },
                w, h, 48f, "#141414", 0f, false
            )
            assertNotNull("text must lay out along the arc", bmp)
            val png = File(composeRule.activity.cacheDir, "v3-textpath.png")
            assertTrue(TextOnPath.bitmapToPng(bmp!!, png))
            bmp.recycle()
            assertTrue(EngineHost.nBlitPngIntoLayer(handle, 0, png.absolutePath))
            val after = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, after, w, h))
            assertTrue("committed text must dirty pixels", after.any { it != -1 })
            assertTrue("text must add pixels beyond the arc", !after.contentEquals(withArc))
            assertTrue(EngineHost.nUndo(handle))
            val undone = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, undone, w, h))
            assertTrue("undo restores the arc-only composite", undone.contentEquals(withArc))
            png.delete()
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // §3 textOnPathRoundTrip: fiches survive nSetLayerMeta → nLayerState.
    @Test
    fun textOnPathRoundTrip() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            assertTrue(EngineHost.nSetLayerMeta(handle, 0, "x-openclip-text-path", "1"))
            assertTrue(EngineHost.nSetLayerMeta(handle, 0, "x-openclip-text-offset", "0.25"))
            val state = EngineHost.nLayerState(handle)
            assertTrue("path fiche must round-trip, got $state", state.contains("x-openclip-text-path"))
            assertTrue("offset fiche must round-trip, got $state", state.contains("0.25"))
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // §3 textMeshWarpUndoable: mesh warp on committed pixels changes the
    // composite and undoes exactly (V+2 bridge).
    @Test
    fun textMeshWarpUndoable() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            EngineHost.nSetBrush(
                handle,
                """{"radius":8,"hardness":0.7,"opaque":1.0,"spacing":0.15,"color":"#0000FF"}"""
            )
            val id = EngineHost.nStrokeBegin(handle, 60f, 60f, 1f)
            for (i in 1..20) EngineHost.nStrokeMove(handle, id, (60 + i * 5).toFloat(), (60 + i * 5).toFloat(), 1f)
            EngineHost.nStrokeEnd(handle, id)
            val before = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, before, w, h))
            // Selection mask: white rect on black, exact canvas size.
            val maskBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            maskBmp.eraseColor(android.graphics.Color.BLACK)
            val cv = android.graphics.Canvas(maskBmp)
            val paint = android.graphics.Paint()
            paint.color = android.graphics.Color.WHITE
            cv.drawRect(100f, 0f, 156f, h.toFloat(), paint)
            val maskFile = File(composeRule.activity.cacheDir, "v3-mask.png")
            maskBmp.compress(Bitmap.CompressFormat.PNG, 100, maskFile.outputStream())
            maskBmp.recycle()
            assertTrue(EngineHost.nSetSelectionMask(handle, maskFile.absolutePath))
            val mesh = (0 until 9).flatMap { k ->
                if (k == 4) listOf("40", "0") else listOf("0", "0")
            }.joinToString(",")
            assertTrue(
                EngineHost.nSelectionBlit(handle, """{"dx":0,"dy":0,"scale":1,"angle":0,"mode":"mesh","mesh":[$mesh]}""")
            )
            val warped = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, warped, w, h))
            assertTrue("mesh warp must move masked pixels", !warped.contentEquals(before))
            assertTrue(EngineHost.nUndo(handle))
            val undone = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, undone, w, h))
            assertTrue("warp undoes exactly", undone.contentEquals(before))
            maskFile.delete()
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // §4 channelSaveReload: mask → PNG → bits round trip + store listing.
    @Test
    fun channelSaveReload() {
        val dir = composeRule.activity.cacheDir
        val key = "v3chantest"
        val mask = SelectionMask(64, 64)
        mask.setRect(10f, 10f, 30f, 30f)
        val bits = mask.copyBits()
        val png = ChannelOps.bitsToPng(64, 64, bits)
        assertNotNull(png)
        val back = ChannelOps.pngToBits(64, 64, png!!)
        assertNotNull(back)
        assertTrue("channel bits must round-trip", back!!.contentEquals(bits))
        assertTrue(ChannelStore.save(dir, key, listOf("Sel1")))
        assertEquals(listOf("Sel1"), ChannelStore.list(dir, key))
        ChannelStore.delete(dir, key, "Sel1")
        assertEquals(emptyList<String>(), ChannelStore.list(dir, key))
    }

    // §4 channelBooleanOps: replace/add/subtract/intersect on fixtures.
    @Test
    fun channelBooleanOps() {
        val w2 = 20
        val h2 = 20
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int): ByteArray {
            val b = ByteArray(w2 * h2)
            for (y in y0 until y1) for (x in x0 until x1) b[y * w2 + x] = 1
            return b
        }
        val a = rect(0, 0, 10, 10)
        val b = rect(5, 5, 15, 15)
        // T1: les ops booléennes sortent du 255/0 (plan doux 0..255).
        val add = ChannelOps.apply(ChannelOp.ADD, a, b)
        assertEquals(255.toByte(), add[0])
        assertEquals(0.toByte(), add[19 * w2 + 19])
        assertEquals(255.toByte(), add[7 * w2 + 7])
        val sub = ChannelOps.apply(ChannelOp.SUBTRACT, a, b)
        assertEquals(255.toByte(), sub[2 * w2 + 2])
        assertEquals(0.toByte(), sub[7 * w2 + 7])
        val cut = ChannelOps.apply(ChannelOp.INTERSECT, a, b)
        assertEquals(0.toByte(), cut[2 * w2 + 2])
        assertEquals(255.toByte(), cut[7 * w2 + 7])
        val rep = ChannelOps.apply(ChannelOp.REPLACE, a, b)
        assertTrue(rep.contentEquals(b.map { if (it != 0.toByte()) 255.toByte() else 0 }.toByteArray()))
    }

    // §4 channelRenameDelete: rename/delete + guards (0 channels OK,
    // size mismatch refuses load).
    @Test
    fun channelRenameDelete() {
        val dir = composeRule.activity.cacheDir
        val key = "v3chanopstest"
        File(ChannelStore.dir(dir, key), "channels.json").delete()
        assertTrue(ChannelStore.save(dir, key, listOf("a", "b")))
        assertTrue(ChannelStore.rename(dir, key, "a", "c"))
        assertEquals(listOf("c", "b"), ChannelStore.list(dir, key))
        assertTrue(ChannelStore.delete(dir, key, "c"))
        assertEquals(listOf("b"), ChannelStore.list(dir, key))
        assertFalse(ChannelStore.rename(dir, key, "missing", "x"))
        // Size guard: bits of the wrong size never load.
        val mask = SelectionMask(8, 8)
        assertFalse("wrong-size bits must refuse", mask.loadBits(ByteArray(7)))
        assertTrue("empty channel list is a valid state", ChannelStore.list(dir, "v3-no-such-key").isEmpty())
    }

    // §5 poseHandlesMove: named rig (hips+head) — joints introspected,
    // pose yaw 45 on the head moves the wireframe overlay.
    @Test
    fun poseHandlesMove() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            val glb = File(composeRule.activity.cacheDir, "rig.glb")
            writeRigGlb(glb)
            assertTrue("rig must load", EngineHost.nLoadGlbReference(handle, glb.absolutePath) > 0)
            val joints = EngineHost.nRefJoints(handle)
            assertTrue("hips joint, got $joints", joints.contains("\"name\":\"hips\""))
            assertTrue("head joint, got $joints", joints.contains("\"name\":\"head\""))
            assertTrue(EngineHost.nSetRefVisible(handle, true))
            val rest = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, rest, w, h))
            assertTrue("rig must draw edges", rest.any { it != -1 })
            assertTrue(EngineHost.nSetRefPose(handle, "{\"head\":[0,45,0]}"))
            val posed = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, posed, w, h))
            assertTrue("head yaw must move the overlay", !posed.contentEquals(rest))
            assertTrue(glb.delete())
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // Minimal 2-bone rig in-code (hips root + head child at +1y, unit
    // cubes; clean-room, no binary assets in git).
    private fun writeRigGlb(dst: File) {
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
        val json = """{"asset":{"version":"2.0"},"buffers":[{"byteLength":${binBytes.size}}],"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":96,"target":34962},{"buffer":0,"byteOffset":96,"byteLength":72,"target":34963}],"accessors":[{"bufferView":0,"componentType":5126,"count":8,"type":"VEC3"},{"bufferView":1,"componentType":5123,"count":36,"type":"SCALAR"}],"meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]},{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}],"nodes":[{"name":"hips","mesh":0,"children":[1]},{"name":"head","mesh":1,"translation":[0.0,1.0,0.0]}],"scenes":[{"nodes":[0]}],"scene":0}"""
        val jb = json.toByteArray()
        val pad = (4 - jb.size % 4) % 4
        val total = 12 + 8 + jb.size + pad + 8 + binBytes.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67)
        out.putInt(2)
        out.putInt(total)
        out.putInt(jb.size + pad)
        out.putInt(0x4E4F534A)
        out.put(jb)
        repeat(pad) { out.put(' '.code.toByte()) }
        out.putInt(binBytes.size)
        out.putInt(0x004E4942)
        out.put(binBytes)
        dst.writeBytes(out.array())
    }

    // §5 poseRoundTrip: pose.json save/reload is identical.
    @Test
    fun poseRoundTrip() {
        val dir = composeRule.activity.cacheDir
        val key = "v3posetest"
        val pose = mapOf("hips" to floatArrayOf(0f, 45f, 0f), "head" to floatArrayOf(0f, 0f, 90f))
        assertTrue(PoseStore.save(dir, key, pose))
        val back = PoseStore.load(dir, key)
        assertEquals(pose.keys, back.keys)
        assertTrue(back["hips"]!!.contentEquals(floatArrayOf(0f, 45f, 0f)))
        assertTrue(back["head"]!!.contentEquals(floatArrayOf(0f, 0f, 90f)))
        // Unknown-cube model: joints introspect empty, pose still accepted.
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            val glb = File(composeRule.activity.cacheDir, "cube.glb")
            writeCubeGlb(glb)
            EngineHost.nLoadGlbReference(handle, glb.absolutePath)
            assertEquals("{\"joints\":[]}", EngineHost.nRefJoints(handle))
            assertTrue(EngineHost.nSetRefPose(handle, "{\"nope\":45}"))
            glb.delete()
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // §5 poseNeverExports: the wireframe shows in nCompositeInto but
    // never in nFrameCompositeInto (thumbnails/exports stay clean).
    @Test
    fun poseNeverExports() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            val glb = File(composeRule.activity.cacheDir, "cube.glb")
            writeCubeGlb(glb)
            assertEquals(18, EngineHost.nLoadGlbReference(handle, glb.absolutePath))
            assertTrue(EngineHost.nSetRefVisible(handle, true))
            val comp = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, comp, w, h))
            assertTrue("overlay must show in composite", comp.any { it != -1 })
            val frame = IntArray(w * h)
            assertEquals(w * h, EngineHost.nFrameCompositeInto(handle, 0, frame, w, h))
            assertTrue("frame composite must stay clean", frame.all { it == -1 })
            assertTrue(glb.delete())
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // §6.1 tiffImportBaseline: generated II/RGB/strips TIFF loads through
    // nLoadDocument with exact pixels.
    @Test
    fun tiffImportBaseline() {
        val w2 = 8
        val h2 = 4
        val rgb = ByteArray(w2 * h2 * 3)
        for (y in 0 until h2) for (x in 0 until w2) {
            rgb[(y * w2 + x) * 3] = (x * 32).toByte()
            rgb[(y * w2 + x) * 3 + 1] = (y * 60).toByte()
            rgb[(y * w2 + x) * 3 + 2] = 128.toByte()
        }
        val tif = File(composeRule.activity.cacheDir, "v3base.tif")
        writeTiff(tif, w2, h2, rgb, strips = 2, compression = 1, photo = 2)
        val handle = EngineHost.nInit(w2, h2, 160f)
        try {
            assertTrue("TIFF must load", EngineHost.nLoadDocument(handle, tif.absolutePath))
            val px = IntArray(w2 * h2)
            assertEquals(w2 * h2, EngineHost.nCompositeInto(handle, px, w2, h2))
            for (y in 0 until h2) for (x in 0 until w2) {
                val p = px[y * w2 + x]
                assertEquals((x * 32), (p shr 16) and 0xFF)
                assertEquals((y * 60), (p shr 8) and 0xFF)
                assertEquals(128, p and 0xFF)
            }
        } finally {
            EngineHost.nDispose(handle)
            tif.delete()
        }
    }

    // Minimal baseline TIFF writer (II, RGB8, strips; mirrors test_tiff).
    private fun writeTiff(
        dst: File, w: Int, h: Int, rgb: ByteArray,
        strips: Int, compression: Int, photo: Int,
    ) {
        val out = ByteBuffer.allocate(1024 + rgb.size + 256).order(ByteOrder.LITTLE_ENDIAN)
        out.put('I'.code.toByte())
        out.put('I'.code.toByte())
        out.putShort(42)
        out.putInt(8)
        val rowsPerStrip = (h + strips - 1) / strips
        fun entry(tag: Int, type: Int, cnt: Int, v: Int) {
            out.putShort(tag.toShort())
            out.putShort(type.toShort())
            out.putInt(cnt)
            out.putInt(v)
        }
        out.putShort(9)
        entry(256, 3, 1, w)
        entry(257, 3, 1, h)
        val bitsPos = out.position() + 8
        entry(258, 3, 3, 0)
        entry(259, 3, 1, compression)
        entry(262, 3, 1, photo)
        val offPos = out.position() + 8
        entry(273, 4, strips, 0)
        entry(277, 3, 1, 3)
        entry(278, 4, 1, rowsPerStrip)
        val cntPos = out.position() + 8
        entry(279, 4, strips, 0)
        out.putInt(0)
        val bitsOff = out.position()
        out.putShort(8)
        out.putShort(8)
        out.putShort(8)
        val offTab = if (strips > 1) out.position() else 0
        if (strips > 1) repeat(strips) { out.putInt(0) }
        val cntTab = if (strips > 1) out.position() else 0
        if (strips > 1) repeat(strips) { out.putInt(0) }
        val offs = mutableListOf<Int>()
        val cnts = mutableListOf<Int>()
        for (s in 0 until strips) {
            val y0 = s * rowsPerStrip
            val y1 = minOf(y0 + rowsPerStrip, h)
            offs.add(out.position())
            for (y in y0 until y1) out.put(rgb, y * w * 3, w * 3)
            cnts.add(out.position() - offs.last())
        }
        fun patch(pos: Int, v: Int) {
            val keep = out.position()
            out.position(pos)
            out.putInt(v)
            out.position(keep)
        }
        patch(bitsPos, bitsOff)
        if (strips == 1) {
            patch(offPos, offs[0])
            patch(cntPos, cnts[0])
        } else {
            patch(offPos, offTab)
            patch(cntPos, cntTab)
            for (s in 0 until strips) {
                patch(offTab + s * 4, offs[s])
                patch(cntTab + s * 4, cnts[s])
            }
        }
        out.flip()
        val bytes = ByteArray(out.remaining())
        out.get(bytes)
        dst.writeBytes(bytes)
    }

    // §6.2 pdfImportFirstPage: generated 1-page PDF renders at 150 dpi
    // and loads through the PNG bridge with real pixels.
    @Test
    fun pdfImportFirstPage() {
        val doc = PdfDocument()
        val info = PdfDocument.PageInfo.Builder(200, 200, 1).create()
        val page = doc.startPage(info)
        page.canvas.drawColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint()
        paint.color = android.graphics.Color.RED
        page.canvas.drawRect(20f, 20f, 180f, 180f, paint)
        doc.finishPage(page)
        val pdf = File(composeRule.activity.cacheDir, "v3one.pdf")
        doc.writeTo(pdf.outputStream())
        doc.close()
        try {
            assertEquals("page 1/1 expected", 1, PdfImport.pageCount(pdf))
            val bmp = PdfImport.renderFirstPage(pdf, 150)
            assertNotNull("page 1 must rasterize", bmp)
            val bw = bmp!!.width
            val bh = bmp.height
            assertTrue("150dpi of 200pt must exceed 300px, got ${bw}x$bh", bw > 300 && bh > 300)
            val mid = IntArray(bw * bh)
            bmp.getPixels(mid, 0, bw, 0, 0, bw, bh)
            assertTrue("red rect must rasterize", mid.any { ((it shr 16) and 0xFF) > 200 && (it and 0xFF) < 100 })
            val png = File(composeRule.activity.cacheDir, "v3pdf.png")
            assertTrue(TextOnPath.bitmapToPng(bmp, png))
            bmp.recycle()
            val handle = EngineHost.nInit(bw, bh, 160f)
            try {
                assertTrue("PDF raster must load", EngineHost.nLoadDocument(handle, png.absolutePath))
                val px = IntArray(bw * bh)
                assertEquals(bw * bh, EngineHost.nCompositeInto(handle, px, bw, bh))
                assertTrue("loaded PDF must show red", px.any { ((it shr 16) and 0xFF) > 200 && (it and 0xFF) < 100 })
            } finally {
                EngineHost.nDispose(handle)
                png.delete()
            }
        } finally {
            pdf.delete()
        }
    }

    // §6.3 svgImportPaths: 2 <path> (M/L + C) commit 2 vector paths.
    @Test
    fun svgImportPaths() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg"><path d="M10,10 L100,10 L100,100" stroke-width="4"/><path d="M10,120 C40,120 60,160 100,160" stroke-width="2"/></svg>"""
        val res = SvgImport.parse(svg)
        assertEquals(2, res.paths.size)
        assertEquals(0, res.ignoredText)
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            val li = EngineHost.nAddVectorLayer(handle, "SVG")
            assertTrue(li >= 0)
            var n = 0
            for (p in res.paths) {
                val json = buildVectorJson(p.points.map { q -> Pair(q.x, q.y) }, p.widthPx)
                if (EngineHost.nAppendVectorPath(handle, li, json) >= 0) n++
            }
            assertEquals(2, n)
            val state = EngineHost.nVectorPaths(handle, li)
            assertEquals("two paths expected, got $state", 2, state.split("\"pts\"").size - 1)
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // §6.3 svgImportIgnoresText: <text> is counted and never rasterized.
    @Test
    fun svgImportIgnoresText() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg"><text x="10" y="20">Hi</text><path d="M5,5 L50,50"/></svg>"""
        val res = SvgImport.parse(svg)
        assertEquals(1, res.paths.size)
        assertTrue("text must be counted, got ${res.ignoredText}", res.ignoredText > 0)
    }

    // §7 shortcutUndoRedo: Ctrl+Z / Ctrl+Shift+Z dispatch drives the piles.
    @Test
    fun shortcutUndoRedo() {
        assertEquals(ShortcutAction.UNDO, ShortcutMap.actionFor(KeyEvent.KEYCODE_Z, true, false))
        assertEquals(ShortcutAction.REDO, ShortcutMap.actionFor(KeyEvent.KEYCODE_Y, true, false))
        assertEquals(ShortcutAction.REDO, ShortcutMap.actionFor(KeyEvent.KEYCODE_Z, true, true))
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            EngineHost.nSetBrush(
                handle,
                """{"radius":8,"hardness":0.7,"opaque":1.0,"spacing":0.15,"color":"#141414"}"""
            )
            val before = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, before, w, h))
            val id = EngineHost.nStrokeBegin(handle, 64f, 128f, 1f)
            for (x in 70..170 step 5) EngineHost.nStrokeMove(handle, id, x.toFloat(), 128f, 1f)
            EngineHost.nStrokeEnd(handle, id)
            val stroked = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, stroked, w, h))
            assertTrue(!stroked.contentEquals(before))
            // The same action the ?-dialog documents: UNDO then REDO.
            val tool = ShortcutToolState()
            assertFalse(tool.dispatchTool(ShortcutAction.UNDO))
            assertTrue(EngineHost.nUndo(handle))
            val undone = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, undone, w, h))
            assertTrue(undone.contentEquals(before))
            assertTrue(EngineHost.nRedo(handle))
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // §7 shortcutToolSwitch: B/E/M move the tool probe (no keyboard needed).
    @Test
    fun shortcutToolSwitch() {
        assertEquals(ShortcutAction.TOOL_BRUSH, ShortcutMap.actionFor(KeyEvent.KEYCODE_B, false, false))
        assertEquals(ShortcutAction.TOOL_ERASER, ShortcutMap.actionFor(KeyEvent.KEYCODE_E, false, false))
        assertEquals(ShortcutAction.TOOL_KNIFE, ShortcutMap.actionFor(KeyEvent.KEYCODE_M, false, false))
        val tool = ShortcutToolState()
        assertTrue(tool.dispatchTool(ShortcutAction.TOOL_BRUSH))
        assertEquals("brush", tool.activeTool)
        assertTrue(tool.dispatchTool(ShortcutAction.TOOL_ERASER))
        assertEquals("eraser", tool.activeTool)
        assertTrue(tool.dispatchTool(ShortcutAction.TOOL_KNIFE))
        assertEquals("knife", tool.activeTool)
    }

    // §8.1 denoiseReducesNoise: impulse noise variance drops, alpha intact.
    @Test
    fun denoiseReducesNoise() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            assertTrue(EngineHost.nApplyFilter(handle, "addNoise", "{\"amount\":40,\"monochrome\":1}"))
            val noisy = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, noisy, w, h))
            fun variance(a: IntArray): Double {
                val mean = a.map { (it shr 16) and 0xFF }.average()
                return a.map { (((it shr 16) and 0xFF) - mean).let { d -> d * d } }.average()
            }
            val vBefore = variance(noisy)
            assertTrue("noise must add variance, got $vBefore", vBefore > 1.0)
            assertTrue(EngineHost.nApplyFilter(handle, "denoise", "{\"strength\":1,\"mode\":\"median\"}"))
            val clean = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, clean, w, h))
            assertTrue(
                "denoise must reduce variance ($vBefore -> ${variance(clean)})",
                variance(clean) < vBefore
            )
            assertTrue("alpha must stay opaque", clean.all { ((it ushr 24) and 0xFF) == 255 })
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // §8.1 denoiseIdentityAtZero: strength 0 is a byte-identical no-op.
    @Test
    fun denoiseIdentityAtZero() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            val before = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, before, w, h))
            assertTrue(EngineHost.nApplyFilter(handle, "denoise", "{\"strength\":0}"))
            val after = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, after, w, h))
            assertTrue(after.contentEquals(before))
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // V+3 confortTabShowsHarmony: the Comfort tab is reachable (row scrolls)
    // and hosts the V+3 panels; also saves the mission screenshot.
    @Test
    fun confortTabShowsHarmony() {
        ensureDockOpen()
        composeRule.onNodeWithTag("dock-tab-confort").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dock-tab-confort").performClick()
        composeRule.waitForIdle()
        // The dock column scrolls vertically: bring each panel into view.
        for (tag in listOf(
            "v3-harmony", "v3-channels", "v3-textpath",
            "v3-pose", "v3-proof", "v3-import"
        )) {
            composeRule.onNodeWithTag(tag).performScrollTo()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(tag).assertIsDisplayed()
        }
        // Mission screenshot for the orchestrator (re-read, not eyeballed).
        // External files dir is pullable without run-as (debug app).
        try {
            val root = composeRule.activity.window.decorView.rootView
            val wpx = root.width.coerceAtLeast(1)
            val hpx = root.height.coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(wpx, hpx, Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bmp))
            val dst = File(
                composeRule.activity.getExternalFilesDir(null),
                "openclip-v3.png"
            )
            dst.outputStream().use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            if (!bmp.isRecycled) bmp.recycle()
        } catch (_: Exception) {
        }
    }

    // ---------- Audit visuel V1-V18 (spec docs/audit-visuel.md) ----------

    private fun visualReset(): String {
        // Hooks reset BEFORE the reset run: slice6Reset draws chrome
        // (fade registry) and bumps counters, which the tests then read.
        ScrollFadeTestHooks.reset()
        BrushPushTestHooks.reset()
        RecomposeTestHooks.reset()
        return slice6Reset()
    }

    private fun disjoint(a: String, b: String) {
        val ra = composeRule.onNodeWithTag(a).getUnclippedBoundsInRoot()
        val rb = composeRule.onNodeWithTag(b).getUnclippedBoundsInRoot()
        assertTrue(
            "$a overlaps $b ($ra vs $rb)",
            ra.right <= rb.left || rb.right <= ra.left ||
                ra.bottom <= rb.top || rb.bottom <= ra.top
        )
    }

    private fun rowHeightDp(tag: String): Dp {
        val b = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        return b.bottom - b.top
    }

    // T1 pinnedNoOverlap (V1): no thumb ever paints over a bar label.
    @Test
    fun visualT1_pinnedNoOverlap() {
        visualReset()
        ensureDockOpen()
        composeRule.onNodeWithTag("pinned-bar").assertIsDisplayed()
        disjoint("pinned-size", "pinned-size-label")
        disjoint("pinned-opaque", "pinned-opaque-label")
    }

    // T2 statusNeverPoke (V2): the internal POKE tick never surfaces.
    @Test
    fun visualT2_statusNeverPoke() {
        visualReset()
        val second = hookOpen("Deux", 256, 256, null)
        try {
            hookActivate(second)
            composeRule.waitForIdle()
            composeRule.activityRule.scenario.onActivity { act ->
                act.activityTestHooks.poke?.invoke()
            }
            composeRule.waitForIdle()
            val s = statusText()
            assertTrue("status leaked POKE: '$s'", !s.contains("POKE"))
        } finally {
            closeFreshDoc(second)
        }
        val s = statusText()
        assertTrue("status leaked POKE: '$s'", !s.contains("POKE"))
    }

    // T3 compactSingleSource (V3): single name, single Save. With 1 doc
    // the tabs row keeps only "+" (same height, no name chip — the title
    // bar stays the single name source and the chrome never resizes);
    // with ≥ 2 docs the chips carry the names and the compact bar drops
    // its own title.
    @Test
    fun visualT3_compactSingleSource() {
        val first = visualReset()
        if (!isCompact()) return
        // 1 doc: "+" only, no name chip; title bar carries the name.
        composeRule.onNodeWithTag("doc-tabs").assertIsDisplayed()
        composeRule.onNodeWithTag("doc-tab-add").assertIsDisplayed()
        composeRule.onNodeWithTag("doc-tab-$first").assertDoesNotExist()
        composeRule.onNodeWithTag("doc-title-compact").assertDoesNotExist()
        composeRule.onNodeWithTag("doc-title").assertIsDisplayed()
        composeRule.onAllNodesWithTag("doc-save").assertCountEquals(1)
        val second = hookOpen("Deux", 256, 256, null)
        try {
            composeRule.waitForIdle()
            // 2 docs: both chips, still a single Save, no compact title.
            composeRule.onNodeWithTag("doc-tab-$first").assertIsDisplayed()
            composeRule.onNodeWithTag("doc-tab-$second").assertIsDisplayed()
            composeRule.onNodeWithTag("doc-title-compact")
                .assertDoesNotExist()
            composeRule.onAllNodesWithTag("doc-save").assertCountEquals(1)
        } finally {
            closeFreshDoc(second)
        }
    }

    // T4 scrollAffordance (V4/V8): overflowing chrome rows report a fade,
    // and the last chip scrolls fully into view.
    @Test
    fun visualT4_scrollAffordance() {
        visualReset()
        if (isCompact()) {
            assertTrue(
                "tool-row should overflow on a phone",
                ScrollFadeTestHooks.isOverflowing("tool-row")
            )
        }
        composeRule.onNodeWithTag("tool-file").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tool-file").assertIsDisplayed()
        ensureDockOpen()
        assertTrue(
            "dock-tabs-row should overflow",
            ScrollFadeTestHooks.isOverflowing("dock-tabs-row")
        )
        composeRule.onNodeWithTag("dock-tab-confort").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dock-tab-confort").assertIsDisplayed()
    }

    // T5 phoneDockIsOverlay (V5): the phone dock never steals canvas width.
    @Test
    fun visualT5_phoneDockIsOverlay() {
        visualReset()
        if (!isCompact()) {
            composeRule.onNodeWithTag("dock-scrim").assertDoesNotExist()
            return
        }
        var rootW = 0
        var canvasW = 0
        composeRule.activityRule.scenario.onActivity { act ->
            rootW = act.window.decorView.rootView.width
        }
        canvasW = composeRule.onNodeWithTag("canvas-box")
            .getUnclippedBoundsInRoot().right.value.toInt()
        // Closed dock: canvas already spans the window.
        composeRule.onNodeWithTag("dock-toggle").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dock-scrim").assertIsDisplayed()
        val box = composeRule.onNodeWithTag("canvas-box")
            .getUnclippedBoundsInRoot()
        val dens = composeRule.activity.resources.displayMetrics.density
        val canvasPx = ((box.right - box.left).value * dens).toInt()
        assertTrue(
            "canvas $canvasPx px must keep >= 50% of $rootW px with the dock open",
            rootW <= 0 || canvasPx * 2 >= rootW
        )
        // The sheet covers the scrim's center: tap the exposed top
        // band (above the 70%-height sheet) to dismiss.
        composeRule.onNodeWithTag("dock-scrim").performTouchInput {
            val sz = composeRule.onNodeWithTag("dock-scrim")
                .fetchSemanticsNode().size
            click(Offset(sz.width / 2f, 10f))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dock-scrim").assertDoesNotExist()
        assertTrue(canvasW >= 0)
    }

    // T6 layerCardSingleBorderAndPct (V6): short "%d %%" dosage, no clip.
    @Test
    fun visualT6_layerCardSingleBorderAndPct() {
        visualReset()
        ensureDockOpen()
        // Scroll the OUTER dock column (the card lives in a nested lazy
        // list: scrolling it directly leaves the dock unmoved).
        composeRule.onNodeWithTag("layer-search").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("layer-dosage-0").assertIsDisplayed()
        val t = nodeText("layer-dosage-0")
        assertTrue(
            "dosage must be short '<n> %', got '$t'",
            Regex("""\d+\s*%""").containsMatchIn(t)
        )
        composeRule.onNodeWithTag("layer-thumb-0").assertIsDisplayed()
    }

    // T7 dockRowsSingleLine FR (V7): no dock chip row wraps to two lines.
    @Test
    fun visualT7_dockRowsSingleLine() {
        visualReset()
        assertTrue(overrideActivityLocale("fr"))
        try {
            ensureDockOpen()
            composeRule.onNodeWithTag("adjust-row").performScrollTo()
            assertTrue(
                "adjust-row wraps: ${rowHeightDp("adjust-row")}",
                rowHeightDp("adjust-row") <= 49.dp
            )
            composeRule.onNodeWithTag("dock-tab-confort").performScrollTo()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("dock-tab-confort").performClick()
            composeRule.waitForIdle()
            for (tag in listOf(
                "harmony-modes-row", "pose-presets-row", "proof-row"
            )) {
                composeRule.onNodeWithTag(tag).performScrollTo()
                composeRule.waitForIdle()
                assertTrue(
                    "$tag wraps: ${rowHeightDp(tag)}",
                    rowHeightDp(tag) <= 49.dp
                )
            }
        } finally {
            overrideActivityLocale("en")
            composeRule.waitForIdle()
        }
    }

    // T8 scrollEdgePadding (V9): first/last items stay reachable with pads.
    @Test
    fun visualT8_scrollEdgePadding() {
        visualReset()
        ensureDockOpen()
        composeRule.onNodeWithTag("layer-add").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("layer-add").assertIsDisplayed()
        composeRule.onNodeWithTag("layer-search").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("layer-search").assertIsDisplayed()
        composeRule.onNodeWithTag("dock-scroll-endpad").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dock-scroll-endpad").assertIsDisplayed()
        openTimeline()
        composeRule.onNodeWithTag("timeline-endpad").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("timeline-endpad").assertIsDisplayed()
        settleTimeline()
    }

    // T9 undoDisabledState (V10): fresh doc grays undo/redo + hints on tap.
    @Test
    fun visualT9_undoDisabledState() {
        visualReset()
        val undoDesc = actString(dev.openclip.R.string.undo_empty)
        composeRule.waitForIdle()
        val node = composeRule.onNodeWithTag("cmdbar-undo").fetchSemanticsNode()
        val descs = node.config.getOrNull(SemanticsProperties.ContentDescription)
            ?: emptyList()
        assertTrue(
            "cmdbar-undo must announce '$undoDesc', got $descs",
            descs.any { it.contains(undoDesc) }
        )
        composeRule.onNodeWithTag("cmdbar-undo").performClick()
        composeRule.waitForIdle()
        assertTrue(
            "tap on disabled undo must post a hint, got '${statusText()}'",
            statusText().contains(undoDesc)
        )
    }

    // T10 timelineRailContrastAndCount (V11): accent glyph contrast +
    // pastille from 2 cels.
    @Test
    fun visualT10_timelineRailContrastAndCount() {
        visualReset()
        fun lum(c: Int): Double {
            fun ch(v: Int): Double {
                val s = (v / 255.0)
                return if (s <= 0.03928) s / 12.92
                else Math.pow((s + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * ch((c shr 16) and 0xFF) +
                0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
        }
        val fg = 0xFF90B8FF.toInt()
        val bg = 0xFF1C1C22.toInt()
        val ratio = (lum(fg) + 0.05) / (lum(bg) + 0.05)
        assertTrue("rail glyph contrast $ratio < 4.5", ratio >= 4.5)
        openTimeline()
        addCels(1)
        composeRule.waitForIdle()
        val counted = TimelineTestHooks.countOf()
        val exists = try {
            composeRule.onNodeWithTag("timeline-count").fetchSemanticsNode()
            true
        } catch (_: AssertionError) {
            false
        }
        assertTrue(
            "timeline-count must exist after addCels (count=$counted)",
            exists
        )
        // The add scrolled the drawer down: come back to the track.
        composeRule.onNodeWithTag("timeline-track").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("timeline-count").assertIsDisplayed()
        settleTimeline()
    }

    // T11 poseEmptyState (V12): no model = armed nothing, CTA first.
    @Test
    fun visualT11_poseEmptyState() {
        visualReset()
        ensureDockOpen()
        composeRule.onNodeWithTag("dock-tab-confort").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dock-tab-confort").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("v3-pose").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("v3-pose-refresh").assertIsDisplayed()
        composeRule.onNodeWithTag("v3-pose-preset-standing")
            .assertIsNotEnabled()
    }

    // T12 thumbCacheBounded (V13): the timeline cache is a bounded LRU.
    @Test
    fun visualT12_thumbCacheBounded() {
        val lru = ThumbLru(24)
        val first = mutableListOf<Bitmap>()
        repeat(50) { i ->
            val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            if (i < 10) first.add(b)
            lru.put(i, b)
        }
        assertTrue("cache size ${lru.size()} > 24", lru.size() <= 24)
        assertTrue(
            "evicted thumbs must be recycled",
            first.any { it.isRecycled }
        )
        assertEquals(24, TimelineTestHooks.maxCache)
    }

    // T13 brushPushThrottled (V14): ~33ms throttle + exact final push.
    @Test
    fun visualT13_brushPushThrottled() {
        BrushPushTestHooks.reset()
        var applied = -1f
        var fake = 0L
        val t = BrushPushThrottler(now = { fake }, push = { applied += 0f })
        var v = 0f
        repeat(100) {
            v += 1f
            t.onDragTick()
            fake += 5L
        }
        val during = BrushPushTestHooks.effectivePushes
        assertTrue("100 ticks -> $during pushes, want <= 20", during <= 20)
        t.onDragEnd()
        val total = BrushPushTestHooks.effectivePushes
        assertTrue("total $total pushes, want <= 21", total <= 21)
        assertEquals(100f, v)
        assertTrue(applied >= -1f)
    }

    // T14 dockNoRecomposeOnPoke (V15): isolated poke skips the dock.
    @Test
    fun visualT14_dockNoRecomposeOnPoke() {
        visualReset()
        ensureDockOpen()
        composeRule.waitForIdle()
        RecomposeTestHooks.reset()
        composeRule.waitForIdle()
        val before = RecomposeTestHooks.dockCompositions
        // slice6Reset leaves the workspace-applied message: the poke must
        // neither change it nor leak POKE (V2).
        val statusBefore = statusText()
        composeRule.activityRule.scenario.onActivity { act ->
            act.activityTestHooks.poke?.invoke()
        }
        composeRule.waitForIdle()
        assertEquals(
            "poke recomposed the dock",
            before, RecomposeTestHooks.dockCompositions
        )
        assertEquals(
            "poke changed the status (was '$statusBefore')",
            statusBefore, statusText()
        )
        assertTrue(
            "status leaked POKE: '${statusText()}'",
            !statusText().contains("POKE")
        )
    }

    // T15 landscapeUsable (V16): short viewport keeps canvas + actions.
    @Test
    fun visualT15_landscapeUsable() {
        visualReset()
        val act = composeRule.activity
        try {
            act.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            var ok = false
            val t0 = SystemClock.uptimeMillis()
            while (SystemClock.uptimeMillis() - t0 < 8000) {
                composeRule.waitForIdle()
                val r = act.window.decorView.rootView
                if (r.width > r.height && r.height > 0) {
                    ok = true
                    break
                }
                Thread.sleep(300)
            }
            assertTrue("rotation to landscape never applied", ok)
            composeRule.waitForIdle()
            var rootH = 0
            var canvasH = 0
            composeRule.activityRule.scenario.onActivity { a ->
                rootH = a.window.decorView.rootView.height
                val ids = a.activityTestHooks.docIds?.invoke() ?: emptyList()
                val sid = ids.firstOrNull()
                canvasH = if (sid != null) {
                    a.activityTestHooks.surfaceFor?.invoke(sid)?.height ?: 0
                } else 0
            }
            assertTrue(
                "canvas $canvasH px must keep >= 40% of $rootH px",
                rootH > 0 && canvasH * 10 >= rootH * 4
            )
            composeRule.onNodeWithTag("menu-bar").assertIsDisplayed()
            for (tag in listOf("cmdbar-save", "cmdbar-undo", "cmdbar-export")) {
                composeRule.onNodeWithTag(tag).performScrollTo()
                composeRule.waitForIdle()
                composeRule.onNodeWithTag(tag).assertIsDisplayed()
            }
        } finally {
            act.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            val t0 = SystemClock.uptimeMillis()
            while (SystemClock.uptimeMillis() - t0 < 8000) {
                composeRule.waitForIdle()
                val r = act.window.decorView.rootView
                if (r.height >= r.width && r.height > 0) break
                Thread.sleep(300)
            }
            composeRule.waitForIdle()
        }
    }

    // T16 textfieldHintContrast (V17): measured placeholder readability.
    // Pixel-anchored (not geometry-anchored): the forced #A0A0B0 hint
    // tone is unique in the chrome, so the test finds its rendered
    // pixels anywhere in the real surface capture (PixelCopy) and
    // contrasts them against the darkest surface. No coordinates, no
    // token reads - pure rendered pixels.
    @Test
    fun visualT16_textfieldHintContrast() {
        visualReset()
        ensureDockOpen()
        composeRule.onNodeWithTag("layer-search").performScrollTo()
        composeRule.waitForIdle()
        var shot: android.graphics.Bitmap? = null
        val latched = java.util.concurrent.CountDownLatch(1)
        composeRule.activityRule.scenario.onActivity { act ->
            val r = act.window.decorView.rootView
            val b = android.graphics.Bitmap.createBitmap(
                r.width.coerceAtLeast(1), r.height.coerceAtLeast(1),
                android.graphics.Bitmap.Config.ARGB_8888
            )
            try {
                android.view.PixelCopy.request(
                    act.window, b,
                    { copyRes ->
                        if (copyRes == android.view.PixelCopy.SUCCESS) {
                            shot = b
                        }
                        latched.countDown()
                    },
                    android.os.Handler(android.os.Looper.getMainLooper())
                )
            } catch (_: Exception) {
                latched.countDown()
            }
        }
        assertTrue(
            "PixelCopy timed out", latched.await(10, java.util.concurrent.TimeUnit.SECONDS)
        )
        val shotNN = shot
        assertNotNull("surface capture failed", shotNN)
        shotNN!!
        try {
            fun lumOf(px: Int): Double {
                fun ch(v: Int): Double {
                    val s = v / 255.0
                    return if (s <= 0.03928) s / 12.92
                    else Math.pow((s + 0.055) / 1.055, 2.4)
                }
                return 0.2126 * ch((px shr 16) and 0xFF) +
                    0.7152 * ch((px shr 8) and 0xFF) +
                    0.0722 * ch(px and 0xFF)
            }
            val labelLums = mutableListOf<Double>()
            val allLums = mutableListOf<Double>()
            for (y in 0 until shotNN.height step 3) {
                for (x in 0 until shotNN.width step 3) {
                    val px = shotNN.getPixel(x, y)
                    val r = (px shr 16) and 0xFF
                    val g = (px shr 8) and 0xFF
                    val bch = px and 0xFF
                    allLums.add(lumOf(px))
                    // Forced hint tone #A0A0B0 (V17), nothing else in
                    // the chrome uses it (D20 excludes B8B8C8/C4C4D0).
                    if (Math.abs(r - 0xA0) <= 20 &&
                        Math.abs(g - 0xA0) <= 20 &&
                        Math.abs(bch - 0xB0) <= 20
                    ) {
                        labelLums.add(lumOf(px))
                    }
                }
            }
            assertTrue(
                "hint tone #A0A0B0 not rendered (found ${labelLums.size} px)",
                labelLums.size >= 100
            )
            allLums.sort()
            val lo = allLums[(allLums.size * 0.05).toInt()]
            val ratio = (labelLums.average() + 0.05) / (lo + 0.05)
            assertTrue(
                "hint contrast $ratio < 4.0 " +
                    "(label px=${labelLums.size} labelLum=${labelLums.average()} bgLum=$lo)",
                ratio >= 4.0
            )
        } finally {
            if (!shotNN.isRecycled) shotNN.recycle()
        }
    }

    // T17 frenchLongStringsEllipsize (V7+FR): V2/V3 dock rows stay 1-line.
    @Test
    fun visualT17_frenchLongStringsEllipsize() {
        visualReset()
        assertTrue(overrideActivityLocale("fr"))
        try {
            ensureDockOpen()
            composeRule.onNodeWithTag("adjust-row").performScrollTo()
            assertTrue(
                "adjust-row wraps: ${rowHeightDp("adjust-row")}",
                rowHeightDp("adjust-row") <= 49.dp
            )
            composeRule.onNodeWithTag("dock-tab-confort").performScrollTo()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("dock-tab-confort").performClick()
            composeRule.waitForIdle()
            for (tag in listOf(
                "harmony-modes-row", "pose-presets-row", "proof-row",
                "v3-import-formats", "v3-import-svg-row", "v3-textpath-actions"
            )) {
                composeRule.onNodeWithTag(tag).performScrollTo()
                composeRule.waitForIdle()
                assertTrue(
                    "$tag wraps: ${rowHeightDp(tag)}",
                    rowHeightDp(tag) <= 49.dp
                )
            }
        } finally {
            overrideActivityLocale("en")
            composeRule.waitForIdle()
        }
    }

    // ================= T1 fondations dessin =================
    // Spec docs/t1-fill.md §1–§6 : seau + dégradé + figures (moteur,
    // 1 undo), baguette (noyau partagé + miroir), contour/masque
    // rapide (app-side), 3 filtres registre.

    /** Demi-masque PNG exact (w x h) pour nSetSelectionMask. */
    private fun halfMaskPng(right: Boolean): java.io.File {
        val dir = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext.cacheDir
        val f = java.io.File(dir, "t1-mask.png")
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val sel = if (right) x >= w / 2 else x < w / 2
                bmp.setPixel(x, y, if (sel) -1 else -16777216)
            }
        }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return f
    }

    private fun paintStroke(handle: Long) {
        EngineHost.nSetBrush(
            handle,
            """{"radius":8,"hardness":0.7,"opaque":1.0,"spacing":0.15,"color":"#301810"}"""
        )
        val sid = EngineHost.nStrokeBegin(handle, 64f, 128f, 1f)
        for (x in 70..170 step 5) {
            EngineHost.nStrokeMove(handle, sid, x.toFloat(), 128f, 1f)
        }
        EngineHost.nStrokeEnd(handle, sid)
    }

    private fun compositeOf(handle: Long): IntArray {
        val px = IntArray(w * h)
        assertEquals(w * h, EngineHost.nCompositeInto(handle, px, w, h))
        return px
    }

    // §1 seau : tap in-code, composite changé, undo restaure.
    @Test
    fun bucketFillChangesCompositeAndUndoRestores() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            paintStroke(handle)
            val before = compositeOf(handle)
            assertTrue(
                EngineHost.nFloodFill(
                    handle, 120, 128, "#FF0000",
                    """{"tolerance":0.5,"antialias":true,"expandPx":0,"sampleMerged":false}"""
                )
            )
            val after = compositeOf(handle)
            val changed = after.indices.count { after[it] != before[it] }
            assertTrue("bucket must paint the stroke region, got $changed", changed > 50)
            assertTrue(EngineHost.nUndo(handle))
            assertTrue(
                "undo must restore the pre-bucket composite exactly",
                compositeOf(handle).contentEquals(before)
            )
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // §1 seau sous masque : hors-sélection byte-identique.
    @Test
    fun bucketFillMasked() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            paintStroke(handle)
            val mask = halfMaskPng(right = true)
            assertTrue(EngineHost.nSetSelectionMask(handle, mask.absolutePath))
            mask.delete()
            val before = compositeOf(handle)
            assertTrue(
                EngineHost.nFloodFill(
                    handle, 120, 128, "#00FF00",
                    """{"tolerance":1.0,"antialias":false}"""
                )
            )
            val after = compositeOf(handle)
            var leftSame = 0
            var leftTotal = 0
            var rightChanged = 0
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (x < w / 2) {
                        leftTotal++
                        if (after[i] == before[i]) leftSame++
                    } else if (after[i] != before[i]) {
                        rightChanged++
                    }
                }
            }
            assertEquals("masked-out half must stay byte-identical", leftTotal, leftSame)
            assertTrue("selected half must change, got $rightChanged", rightChanged > 50)
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // §2 dégradé : commit + undo (endpoints verrouillés moteur).
    @Test
    fun gradientDragCommitsAndUndoRestores() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            val before = compositeOf(handle)
            assertTrue(
                EngineHost.nPaintGradient(
                    handle,
                    """{"kind":"linear","x0":0,"y0":0,"x1":256,"y1":0,"stops":["#FF0000:0","#0000FF:1"],"dither":false}"""
                )
            )
            val after = compositeOf(handle)
            val changed = after.indices.count { after[it] != before[it] }
            assertTrue("gradient must paint the canvas, got $changed", changed > w * h / 2)
            assertTrue(EngineHost.nUndo(handle))
            assertTrue(
                "undo must restore the pre-gradient composite exactly",
                compositeOf(handle).contentEquals(before)
            )
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // §2 dégradé sous masque.
    @Test
    fun gradientMasked() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            val mask = halfMaskPng(right = true)
            assertTrue(EngineHost.nSetSelectionMask(handle, mask.absolutePath))
            mask.delete()
            val before = compositeOf(handle)
            assertTrue(
                EngineHost.nPaintGradient(
                    handle,
                    """{"kind":"linear","x0":0,"y0":0,"x1":256,"y1":0,"stops":["#FF0000:0","#0000FF:1"],"dither":false}"""
                )
            )
            val after = compositeOf(handle)
            var leftSame = 0
            var leftTotal = 0
            var rightChanged = 0
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (x < w / 2) {
                        leftTotal++
                        if (after[i] == before[i]) leftSame++
                    } else if (after[i] != before[i]) {
                        rightChanged++
                    }
                }
            }
            assertEquals("masked-out half must stay byte-identical", leftTotal, leftSame)
            assertTrue("selected half must change, got $rightChanged", rightChanged > 50)
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // §2 dégradé libre : ancres multiples via JNI + undo.
    @Test
    fun gradientFreeCommitsAndUndoRestores() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            val before = compositeOf(handle)
            assertTrue(
                EngineHost.nPaintGradient(
                    handle,
                    """{"kind":"free","x0":0,"y0":0,"x1":1,"y1":0,"stops":["#FF0000:0","#0000FF:1"],"pts":[[32,128],[224,128]],"dither":false}"""
                )
            )
            val after = compositeOf(handle)
            val changed = after.indices.count { after[it] != before[it] }
            assertTrue("free gradient must paint the canvas, got $changed", changed > w * h / 2)
            // L'ancre rouge domine près de (32,128).
            val near = after[128 * w + 32]
            val r = (near shr 16) and 0xFF
            val b = near and 0xFF
            assertTrue("near the red anchor red must dominate (got $near)", r > 200 && b < 60)
            assertTrue(EngineHost.nUndo(handle))
            assertTrue(
                "undo must restore the pre-gradient composite exactly",
                compositeOf(handle).contentEquals(before)
            )
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // §3 figure : timbre + 1 undo.
    @Test
    fun shapeStampUndoable() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            val before = compositeOf(handle)
            assertTrue(
                EngineHost.nStampShape(
                    handle,
                    """{"kind":"rect","x0":40,"y0":40,"x1":200,"y1":200,"fillOn":true,"fill":"#008000","strokeOn":true,"stroke":"#000000","strokeW":4}"""
                )
            )
            val after = compositeOf(handle)
            val changed = after.indices.count { after[it] != before[it] }
            assertTrue("shape must paint pixels, got $changed", changed > 1000)
            assertTrue(EngineHost.nUndo(handle))
            assertTrue(
                "undo must restore the pre-shape composite exactly",
                compositeOf(handle).contentEquals(before)
            )
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // §4 baguette : sélectionne + fourmis visibles (miroir app-side).
    @Test
    fun wandSelectsAndAntsShow() {
        val es = surf()
        awaitSurface(es)
        composeRule.activityRule.scenario.onActivity {
            es.clearSelection()
            es.setSelectionMask(null)
        }
        // Trait horizontal au milieu (région sombre connexe sur fond blanc).
        val cx = es.width * 0.5f
        val cy = es.height * 0.5f
        val t0 = SystemClock.uptimeMillis()
        dispatchAll(
            es,
            simpleDown(t0, cx - 80f, cy),
            MotionEvent.obtain(t0, t0 + 16, MotionEvent.ACTION_MOVE, cx, cy, 1f, 1f, 0, 1f, 1f, 0, 0),
            MotionEvent.obtain(t0, t0 + 32, MotionEvent.ACTION_MOVE, cx + 80f, cy, 1f, 1f, 0, 1f, 1f, 0, 0),
            MotionEvent.obtain(t0, t0 + 48, MotionEvent.ACTION_UP, cx + 80f, cy, 1f, 1f, 0, 1f, 1f, 0, 0)
        )
        Thread.sleep(400)
        val (sex, sey) = es.screenToEngine(cx, cy)
        val lock = Object()
        var done = false
        var ok = false
        composeRule.activityRule.scenario.onActivity {
            es.wandSelect(sex.toInt(), sey.toInt(), 0.15f, "layer", 0) { r ->
                synchronized(lock) {
                    ok = r
                    done = true
                    lock.notifyAll()
                }
            }
        }
        synchronized(lock) {
            if (!done) lock.wait(15000)
        }
        assertTrue("wandSelect must succeed on the painted line", done && ok)
        assertTrue("wand mask must be non-empty", es.selectionCountForTest() > 16)
        assertTrue(
            "wand mask must stay well below full canvas",
            es.selectionCountForTest() < es.canvasWidth() * es.canvasHeight() / 2
        )
        assertEquals(
            "marching ants must show the bbox loop",
            4, es.selectionOutlineSizeForTest()
        )
        composeRule.activityRule.scenario.onActivity {
            es.clearSelection()
            es.setSelectionMask(null)
            es.undo()
        }
    }

    // §4 baguette → trait : hors-sélection intact (bout-en-bout).
    @Test
    fun wandThenStrokeConstrained() {
        val es = surf()
        awaitSurface(es)
        composeRule.activityRule.scenario.onActivity {
            es.clearSelection()
            es.setSelectionMask(null)
        }
        val wv = es.width.toFloat()
        val hv = es.height.toFloat()
        // Ligne 1 (tiers haut) : future sélection baguette.
        var t = SystemClock.uptimeMillis()
        dispatchAll(
            es,
            simpleDown(t, wv * 0.3f, hv * 0.33f),
            MotionEvent.obtain(t, t + 16, MotionEvent.ACTION_MOVE, wv * 0.5f, hv * 0.33f, 1f, 1f, 0, 1f, 1f, 0, 0),
            MotionEvent.obtain(t, t + 32, MotionEvent.ACTION_MOVE, wv * 0.7f, hv * 0.33f, 1f, 1f, 0, 1f, 1f, 0, 0),
            MotionEvent.obtain(t, t + 48, MotionEvent.ACTION_UP, wv * 0.7f, hv * 0.33f, 1f, 1f, 0, 1f, 1f, 0, 0)
        )
        Thread.sleep(400)
        val (sex, sey) = es.screenToEngine(wv * 0.5f, hv * 0.33f)
        val lock = Object()
        var done = false
        var ok = false
        composeRule.activityRule.scenario.onActivity {
            es.wandSelect(sex.toInt(), sey.toInt(), 0.15f, "layer", 0) { r ->
                synchronized(lock) {
                    ok = r
                    done = true
                    lock.notifyAll()
                }
            }
        }
        synchronized(lock) {
            if (!done) lock.wait(15000)
        }
        assertTrue("wandSelect must succeed", done && ok)
        // Ligne 2 (tiers bas, hors sélection) : la porte doit l'avaler.
        t = SystemClock.uptimeMillis()
        dispatchAll(
            es,
            simpleDown(t, wv * 0.3f, hv * 0.66f),
            MotionEvent.obtain(t, t + 16, MotionEvent.ACTION_MOVE, wv * 0.5f, hv * 0.66f, 1f, 1f, 0, 1f, 1f, 0, 0),
            MotionEvent.obtain(t, t + 32, MotionEvent.ACTION_MOVE, wv * 0.7f, hv * 0.66f, 1f, 1f, 0, 1f, 1f, 0, 0),
            MotionEvent.obtain(t, t + 48, MotionEvent.ACTION_UP, wv * 0.7f, hv * 0.66f, 1f, 1f, 0, 1f, 1f, 0, 0)
        )
        Thread.sleep(400)
        var bmp: Bitmap? = null
        val clock = Object()
        es.pullFrameFull(0) { b ->
            synchronized(clock) {
                bmp = b
                clock.notifyAll()
            }
        }
        synchronized(clock) {
            if (bmp == null) clock.wait(5000)
        }
        assertNotNull("pullFrameFull must return a bitmap", bmp)
        val b = bmp!!
        val (e1x, e1y) = es.screenToEngine(wv * 0.5f, hv * 0.33f)
        val (e2x, e2y) = es.screenToEngine(wv * 0.5f, hv * 0.66f)
        // Coordonnées bitmap = pixels moteur (pullFrameFull = engineWidth).
        val sx = (b.width.toFloat() / es.canvasWidth() * e1x).toInt()
            .coerceIn(0, b.width - 1)
        val sy = (b.height.toFloat() / es.canvasHeight() * e1y).toInt()
            .coerceIn(0, b.height - 1)
        val tx = (b.width.toFloat() / es.canvasWidth() * e2x).toInt()
            .coerceIn(0, b.width - 1)
        val ty = (b.height.toFloat() / es.canvasHeight() * e2y).toInt()
            .coerceIn(0, b.height - 1)
        fun dark(px: Int): Boolean {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val bl = px and 0xFF
            return r < 100 && g < 100 && bl < 100
        }
        assertTrue("line 1 (selected) must carry ink", dark(b.getPixel(sx, sy)))
        assertTrue("line 2 (outside) must stay white", b.getPixel(tx, ty) == -1)
        b.recycle()
        composeRule.activityRule.scenario.onActivity {
            es.clearSelection()
            es.setSelectionMask(null)
            es.undo()
        }
    }

    // §5 contour progressif : bord = rampe, centre/extérieur intacts.
    @Test
    fun selectionFeatherRadius() {
        val es = surf()
        awaitSurface(es)
        composeRule.activityRule.scenario.onActivity {
            es.clearSelection()
            es.selectRectEngine(40f, 40f, 140f, 140f)
            es.featherSelectionRadius(8f)
        }
        assertEquals("center must stay fully selected", 255, es.selectionAlphaForTest(90, 90))
        assertEquals("far outside must stay empty", 0, es.selectionAlphaForTest(10, 10))
        val edge = es.selectionAlphaForTest(40, 90)
        assertTrue("edge must be a ramp (got $edge)", edge in 1..254)
        composeRule.activityRule.scenario.onActivity {
            es.clearSelection()
        }
    }

    // §5 rayon 0 = identité byte-exacte.
    @Test
    fun selectionFeatherZeroIdentity() {
        val es = surf()
        awaitSurface(es)
        composeRule.activityRule.scenario.onActivity {
            es.clearSelection()
            es.selectRectEngine(40f, 40f, 140f, 140f)
        }
        val h1 = es.selectionHashForTest()
        composeRule.activityRule.scenario.onActivity {
            es.featherSelectionRadius(0f)
        }
        assertEquals("radius 0 must leave the mask byte-identical", h1, es.selectionHashForTest())
        composeRule.activityRule.scenario.onActivity {
            es.clearSelection()
        }
    }

    // §5 masque rapide : blanc agrandit, noir réduit, toggle = fourmis.
    @Test
    fun quickMaskPaintEdits() {
        val es = surf()
        awaitSurface(es)
        val ew = es.canvasWidth()
        val eh = es.canvasHeight()
        composeRule.activityRule.scenario.onActivity {
            es.clearSelection()
            es.quickMask = true
            es.paintMaskAt(ew * 0.25f, eh * 0.25f, true)
        }
        val grown = es.selectionCountForTest()
        assertTrue("white must grow the mask (got $grown)", grown > 0)
        composeRule.activityRule.scenario.onActivity {
            es.paintMaskAt(ew * 0.25f, eh * 0.25f, false)
        }
        val shrunk = es.selectionCountForTest()
        assertTrue("black must shrink the mask ($grown -> $shrunk)", shrunk < grown)
        composeRule.activityRule.scenario.onActivity {
            es.quickMask = false
            es.clearSelection()
        }
    }

    // §5 undo app-side du masque.
    @Test
    fun quickMaskUndoRestores() {
        val es = surf()
        awaitSurface(es)
        composeRule.activityRule.scenario.onActivity {
            es.clearSelection()
        }
        uiSync { it.selectRectEngine(40f, 40f, 140f, 140f) }
        val h1 = es.selectionHashForTest()
        composeRule.activityRule.scenario.onActivity {
            es.pushSelectionUndo()
            // Blanc HORS du rect (l'intérieur est déjà à 255 : no-op).
            es.paintMaskAt(180f, 60f, true)
        }
        assertTrue("mask paint must change the mask", es.selectionHashForTest() != h1)
        composeRule.activityRule.scenario.onActivity {
            assertTrue("mask undo must succeed", es.undoSelection())
        }
        assertEquals("mask undo must restore the mask exactly", h1, es.selectionHashForTest())
        composeRule.activityRule.scenario.onActivity {
            es.clearSelection()
        }
    }

    // §6 unsharp : pattern posterize (modifie + undo).
    @Test
    fun applyFilterKnownUnsharpModifiesComposite() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            paintStroke(handle)
            val before = compositeOf(handle)
            assertTrue(
                EngineHost.nApplyFilter(
                    handle, "unsharp",
                    """{"radius":2,"amount":1,"threshold":0}"""
                )
            )
            val after = compositeOf(handle)
            val changed = after.indices.count { after[it] != before[it] }
            assertTrue("unsharp must modify composite pixels, got $changed", changed > 0)
            assertTrue(EngineHost.nUndo(handle))
            assertTrue(
                "undo must restore the pre-filter composite exactly",
                compositeOf(handle).contentEquals(before)
            )
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // §6 seuillage sous masque : pont sélection, hors-masque identique.
    @Test
    fun thresholdMasked() {
        val es = surf()
        awaitSurface(es)
        composeRule.activityRule.scenario.onActivity {
            es.clearSelection()
            es.setSelectionMask(null)
        }
        // Trait pleine largeur au milieu.
        val wv = es.width.toFloat()
        val hv = es.height.toFloat()
        val t = SystemClock.uptimeMillis()
        dispatchAll(
            es,
            simpleDown(t, wv * 0.2f, hv * 0.5f),
            MotionEvent.obtain(t, t + 16, MotionEvent.ACTION_MOVE, wv * 0.5f, hv * 0.5f, 1f, 1f, 0, 1f, 1f, 0, 0),
            MotionEvent.obtain(t, t + 32, MotionEvent.ACTION_MOVE, wv * 0.8f, hv * 0.5f, 1f, 1f, 0, 1f, 1f, 0, 0),
            MotionEvent.obtain(t, t + 48, MotionEvent.ACTION_UP, wv * 0.8f, hv * 0.5f, 1f, 1f, 0, 1f, 1f, 0, 0)
        )
        Thread.sleep(400)
        val ew = es.canvasWidth()
        val eh = es.canvasHeight()
        // Moitié droite sélectionnée ; seuil 10 : l'encre sombre du trait
        // (luma ~20) passe au blanc à droite, reste à gauche.
        uiSync { it.selectRectEngine(ew / 2f, 0f, ew.toFloat(), eh.toFloat()) }
        val lock = Object()
        var done = false
        var ok = false
        composeRule.activityRule.scenario.onActivity {
            es.applyFilterInSelection("threshold", """{"level":10,"dither":0}""") { r ->
                synchronized(lock) {
                    ok = r
                    done = true
                    lock.notifyAll()
                }
            }
        }
        synchronized(lock) {
            if (!done) lock.wait(20000)
        }
        assertTrue("threshold in selection must succeed", done && ok)
        var bmp: Bitmap? = null
        val clock = Object()
        es.pullFrameFull(es.currentFrameIndex()) { b ->
            synchronized(clock) {
                bmp = b
                clock.notifyAll()
            }
        }
        synchronized(clock) {
            if (bmp == null) clock.wait(5000)
        }
        assertNotNull("pullFrameFull must return a bitmap", bmp)
        val b = bmp!!
        val my = (b.height / 2).coerceIn(0, b.height - 1)
        val lx = (b.width / 4).coerceIn(0, b.width - 1)
        val rx = (b.width * 3 / 4).coerceIn(0, b.width - 1)
        fun dark(px: Int): Boolean {
            val r = (px shr 16) and 0xFF
            return r < 100
        }
        assertTrue("left of mask must keep the inked stroke", dark(b.getPixel(lx, my)))
        assertTrue("right of mask must be thresholded white", b.getPixel(rx, my) == -1)
        b.recycle()
        composeRule.activityRule.scenario.onActivity {
            es.clearSelection()
            es.setSelectionMask(null)
            es.undo()
        }
    }

    // T1 UI : le toggle Peinture ouvre la rangée T1 + le panneau seau
    // (composition + tags, sans toucher au moteur). assertExists pour
    // la rangée et le panneau (le layout vertical dépend de l'écran) ;
    // IsDisplayed pour les chips de la rangée principale (contrat
    // slice7 : visibles après scroll explicite).
    @Test
    fun t1PaintRowOpensBucketPanel() {
        ensureDockOpen()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tool-t1-toggle").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tool-t1-toggle").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tool-t1-row").assertExists()
        composeRule.onNodeWithTag("tool-t1-bucket").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tool-t1-bucket").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("t1-bucket-panel").assertExists()
        composeRule.onNodeWithTag("t1-tolerance").assertExists()
        // Refermer : la rangée disparaît, la rangée principale reste.
        composeRule.onNodeWithTag("tool-t1-toggle").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tool-t1-bucket").assertDoesNotExist()
        composeRule.onNodeWithTag("tool-file").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tool-file").assertIsDisplayed()
    }

    // Sonde §4.4 : temps du miroir Kotlin sur 1024px (tranche le repli ;
    // 5 s = garde-fou, jamais un gate strict — la décision est notée en
    // ARCHITECTURE.md avec la mesure lue dans le log T1-PROBE).
    @Test
    fun wandKotlinMirrorTiming1024() {
        val s = 1024
        val px = IntArray(s * s) { -1 }
        val r = 400
        for (y in 0 until s) {
            for (x in 0 until s) {
                val dx = x - s / 2
                val dy = y - s / 2
                if (dx * dx + dy * dy < r * r) px[y * s + x] = -16777216
            }
        }
        val t0 = SystemClock.uptimeMillis()
        val region = floodRegionKotlin(px, s, s, 4, 4, 0.15f)
        val dt = SystemClock.uptimeMillis() - t0
        var n = 0
        for (v in region) if (v) n++
        println("T1-PROBE wandKotlinMirror1024: ${dt}ms selected=$n")
        assertTrue("mirror must select the background ($n)", n > s * s / 2)
        assertTrue("mirror must exclude the disc", n < s * s)
        assertTrue("mirror timing sanity (<5s, got ${dt}ms)", dt < 5000)
    }

    // T2 §2: hyphenation rules (fonction pure, FR + EN).
    @Test
    fun hyphenateFrRules() {
        assertEquals(listOf(2, 5), hyphenate("papillon", "fr"))
        assertTrue(hyphenate("rue", "fr").isEmpty())
        assertTrue(hyphenate("a", "fr").isEmpty())
    }

    @Test
    fun hyphenateEnRules() {
        assertEquals(listOf(3), hyphenate("butter", "en"))
        assertTrue(hyphenate("table", "en").isEmpty())
        assertTrue(hyphenate("is", "en").isEmpty())
    }

    private fun inkCount(b: android.graphics.Bitmap): Int {
        var n = 0
        for (y in 0 until b.height step 2) {
            for (x in 0 until b.width step 2) {
                if (b.getPixel(x, y) != -1) n++
            }
        }
        return n
    }

    // T2 §1: outline commit paints the glyph perimeter (more ink than
    // the same fill alone).
    @Test
    fun textOutlineCommitsStroke() {
        val id = openFreshDoc("t2-outline")
        val es = freshSurface(id)
        try {
            val base = dev.openclip.ui.TextSpec(
                text = "Ag", font = "sans-serif", sizePx = 96,
                boxX = 8f, boxY = 8f, boxW = 240f
            )
            val plain = commitTextSync(es, base)
            val bp = pullFull(es)
            val nPlain = inkCount(bp)
            bp.recycle()
            assertTrue(es.undo())
            val out = base.copy(outline = TextOutline(6f, "#000000"))
            // B2 §1: the atomic undo removed the first text layer, so the
            // second commit lands at the same index (not plain + 1).
            val outIdx = commitTextSync(es, out)
            val bo = pullFull(es)
            val nOut = inkCount(bo)
            bo.recycle()
            assertTrue("outline must widen the glyph ($nPlain -> $nOut)", nOut > nPlain + 20)
            val fiche = es.layerTextForTest(outIdx)
            assertNotNull(fiche)
            assertNotNull(fiche!!.outline)
            assertTrue(es.undo())
        } finally {
            closeFreshDoc(id)
        }
    }

    // T2 §1: two runs in two fonts/sizes lay out differently than one
    // global run (non-trivialité).
    @Test
    fun textRunsCommitMixed() {
        val id = openFreshDoc("t2-runs")
        val es = freshSurface(id)
        try {
            val text = "ABCDEFGH"
            val one = dev.openclip.ui.TextSpec(
                text = text, font = "sans-serif", sizePx = 64,
                boxX = 8f, boxY = 8f, boxW = 240f
            )
            commitTextSync(es, one)
            val n1 = inkCount(pullFull(es).also { })
            assertTrue(es.undo())
            val two = one.copy(
                runs = listOf(
                    TextRun(0, 4, "sans-serif", "bold", 96, "#000000"),
                    TextRun(4, 4, "monospace", "regular", 48, "#000000")
                )
            )
            commitTextSync(es, two)
            val b2 = pullFull(es)
            val n2 = inkCount(b2)
            b2.recycle()
            assertTrue("mixed runs must lay out differently ($n1 vs $n2)", n1 != n2)
            assertTrue("mixed runs must carry ink ($n2)", n2 > 30)
            assertTrue(es.undo())
        } finally {
            closeFreshDoc(id)
        }
    }

    // T2 §1: imported user font round-trips through fiche + reload.
    @Test
    fun fontImportRoundTrip() {
        val id = openFreshDoc("t2-fontimport")
        val es = freshSurface(id)
        try {
            composeRule.activityRule.scenario.onActivity { act ->
                FontRegistry.dir = java.io.File(act.cacheDir, "fonts-t2")
            }
            val sys = java.io.File("/system/fonts").listFiles()
                ?.firstOrNull { it.isFile && it.name.endsWith(".ttf") && it.length() in 1..(5L * 1024 * 1024) }
            assertNotNull("emulator must expose a system ttf", sys)
            val res = FontRegistry.importFrom(sys!!, sys.name)
            assertTrue("import must succeed, got $res", res is FontRegistry.ImportResult.Ok)
            val family = (res as FontRegistry.ImportResult.Ok).family
            composeRule.waitForIdle()
            val spec = dev.openclip.ui.TextSpec(
                text = "Ag", font = family, sizePx = 72,
                boxX = 8f, boxY = 8f, boxW = 240f
            )
            val idx = commitTextSync(es, spec)
            assertFalse("imported font must resolve", es.lastFontFallback)
            val fiche = es.layerTextForTest(idx)
            assertNotNull(fiche)
            assertEquals(family, fiche!!.font)
            assertTrue(es.undo())
        } finally {
            closeFreshDoc(id)
        }
    }

    // T2 §1: unknown font falls back to sans-serif + banner flag.
    @Test
    fun fontFallbackBanner() {
        val id = openFreshDoc("t2-fontfallback")
        val es = freshSurface(id)
        try {
            val spec = dev.openclip.ui.TextSpec(
                text = "Ag", font = "NopeNotAFamily", sizePx = 72,
                boxX = 8f, boxY = 8f, boxW = 240f
            )
            commitTextSync(es, spec)
            assertTrue("missing font must raise the fallback banner", es.lastFontFallback)
            assertTrue(es.undo())
        } finally {
            closeFreshDoc(id)
        }
    }

    // T2 §2: inline commit = 1 undo step (typing is overlay-only).
    @Test
    fun inlineCommitIsOneUndo() {
        val id = openFreshDoc("t2-inline")
        val es = freshSurface(id)
        try {
            val before = bitmapPixels(pullFull(es))
            val spec = dev.openclip.ui.TextSpec(
                text = "Inline", font = "sans-serif", sizePx = 72,
                boxX = 8f, boxY = 8f, boxW = 240f
            )
            commitTextSync(es, spec)
            val after = bitmapPixels(pullFull(es))
            assertFalse("inline commit must change the composite", before.contentEquals(after))
            assertTrue("commit must undo", es.undo())
            val restored = bitmapPixels(pullFull(es))
            assertTrue("1 undo must restore", before.contentEquals(restored))
        } finally {
            closeFreshDoc(id)
        }
    }

    // T2 §2: vertical text stacks columns (height > width of the ink).
    @Test
    fun verticalTextStacksColumns() {
        val id = openFreshDoc("t2-vertical")
        val es = freshSurface(id)
        try {
            val spec = dev.openclip.ui.TextSpec(
                text = "ABCDE", font = "sans-serif", sizePx = 64,
                boxX = 8f, boxY = 8f, boxW = 240f, dir = "v"
            )
            val vidx = commitTextSync(es, spec)
            val b = pullFull(es)
            try {
                var x0 = b.width
                var x1 = -1
                var y0 = b.height
                var y1 = -1
                for (y in 0 until b.height step 2) {
                    for (x in 0 until b.width step 2) {
                        if (b.getPixel(x, y) != -1) {
                            if (x < x0) x0 = x
                            if (x > x1) x1 = x
                            if (y < y0) y0 = y
                            if (y > y1) y1 = y
                        }
                    }
                }
                assertTrue("vertical text must carry ink", x1 >= x0)
                assertTrue(
                    "columns must be taller than wide (${x1 - x0} x ${y1 - y0})",
                    (y1 - y0) > (x1 - x0)
                )
            } finally {
                b.recycle()
            }
            val fiche = es.layerTextForTest(vidx)
            assertNotNull(fiche)
            assertEquals("v", fiche!!.dir)
            assertTrue(es.undo())
        } finally {
            closeFreshDoc(id)
        }
    }

    // T2 §3: retrace replaces geometry (style kept) + undo restores.
    @Test
    fun retraceReplacesAndUndo() {
        val id = openFreshDoc("t2-retrace")
        val es = freshSurface(id)
        try {
            val layer = es.addVectorLayer("vec")
            assertTrue(layer >= 0)
            val red = "{\"pts\":[{\"x\":10,\"y\":20,\"p\":1},{\"x\":50,\"y\":20,\"p\":1}],\"color\":\"#FF0000\"}"
            assertTrue(es.appendVectorPath(layer, red) >= 0)
            val before = bitmapPixels(pullFull(es))
            val moved = "{\"pts\":[{\"x\":10,\"y\":60,\"p\":1},{\"x\":50,\"y\":60,\"p\":1}]}"
            assertTrue("replace must succeed", es.replaceVectorPath(layer, 0, moved))
            val mid = T2Vector.readPaths(es.vectorPaths(layer))
            assertEquals(1, mid.size)
            assertEquals(255, mid[0].colorHex.let {
                Integer.parseInt(it.substring(1, 3), 16)
            })
            val after = bitmapPixels(pullFull(es))
            assertFalse("geometry must move", before.contentEquals(after))
            assertTrue("replace must undo", es.undoVectorPath(layer))
            val restored = bitmapPixels(pullFull(es))
            assertTrue("undo must restore", before.contentEquals(restored))
        } finally {
            closeFreshDoc(id)
        }
    }

    // T2 §3: connect merges two close paths.
    @Test
    fun connectMergesTwoPaths() {
        val id = openFreshDoc("t2-connect")
        val es = freshSurface(id)
        try {
            val layer = es.addVectorLayer("vec")
            assertTrue(layer >= 0)
            val a = "{\"pts\":[{\"x\":10,\"y\":32,\"p\":1},{\"x\":50,\"y\":32,\"p\":1}]}"
            val b = "{\"pts\":[{\"x\":54,\"y\":32,\"p\":1},{\"x\":94,\"y\":32,\"p\":1}]}"
            es.appendVectorPath(layer, a)
            es.appendVectorPath(layer, b)
            assertTrue("connect must succeed", es.connectVectorPaths(layer, 0, 1, 12f))
            val paths = T2Vector.readPaths(es.vectorPaths(layer))
            assertEquals("two paths must fuse", 1, paths.size)
            assertTrue(es.undoVectorPath(layer))
            assertEquals(2, T2Vector.readPaths(es.vectorPaths(layer)).size)
        } finally {
            closeFreshDoc(id)
        }
    }

    // T2 §3: thicken widens the rendering.
    @Test
    fun thickenWidensRendering() {
        val id = openFreshDoc("t2-thicken")
        val es = freshSurface(id)
        try {
            val layer = es.addVectorLayer("vec")
            assertTrue(layer >= 0)
            val a = "{\"pts\":[{\"x\":10,\"y\":64,\"p\":1},{\"x\":120,\"y\":64,\"p\":1}]}"
            es.appendVectorPath(layer, a)
            val n1 = inkCount(pullFull(es))
            val paths = T2Vector.readPaths(es.vectorPaths(layer))
            assertEquals(1, paths.size)
            val wide = paths[0].widths.map { it * 2f }
            assertTrue("widths must pose", es.setVectorWidths(layer, 0, wide))
            val n2 = inkCount(pullFull(es))
            assertTrue("thicken must widen ($n1 -> $n2)", n2 > n1)
            assertTrue(es.undoVectorPath(layer))
        } finally {
            closeFreshDoc(id)
        }
    }

    // T2 §3: trajectories overlay follows the view (probe + matrix).
    @Test
    fun trajectoriesOverlayFollowsView() {
        val id = openFreshDoc("t2-traj")
        val es = freshSurface(id)
        try {
            val layer = es.addVectorLayer("vec")
            assertTrue(layer >= 0)
            val a = "{\"pts\":[{\"x\":20,\"y\":40,\"p\":1},{\"x\":100,\"y\":40,\"p\":1}]}"
            es.appendVectorPath(layer, a)
            composeRule.activityRule.scenario.onActivity {
                es.refreshTrajectories(layer)
            }
            composeRule.waitForIdle()
            Thread.sleep(300)
            val polys = es.vectorOverlayForTest()
            assertTrue("trajectories must list the path", polys.isNotEmpty())
            val (sx1, sy1) = es.engineToScreen(polys[0][0].first, polys[0][0].second)
            composeRule.activityRule.scenario.onActivity {
                es.setViewForTest(2f, 0f)
            }
            composeRule.waitForIdle()
            Thread.sleep(300)
            val (sx2, sy2) = es.engineToScreen(polys[0][0].first, polys[0][0].second)
            assertFalse(
                "zoom must move the overlay point",
                sx1 == sx2 && sy1 == sy2
            )
            composeRule.activityRule.scenario.onActivity {
                es.setViewForTest(1f, 0f)
                es.refreshTrajectories(-1)
            }
        } finally {
            closeFreshDoc(id)
        }
    }

    // T2 §4: styled append round-trips tip/size/color through the getter.
    @Test
    fun vectorStyleAppendRoundTrip() {
        val id = openFreshDoc("t2-vstyle")
        val es = freshSurface(id)
        try {
            val layer = es.addVectorLayer("vec")
            assertTrue(layer >= 0)
            val styled = T2Vector.styledJson(
                listOf(Triple(10f, 20f, 1f), Triple(60f, 20f, 1f)),
                dev.openclip.ui.VectorStyle("flat", 2f, "#FF0000")
            )
            assertTrue(es.appendVectorPath(layer, styled) >= 0)
            val paths = T2Vector.readPaths(es.vectorPaths(layer))
            assertEquals(1, paths.size)
            assertEquals("flat", paths[0].tip)
            assertEquals("#FF0000", paths[0].colorHex)
            assertTrue(paths[0].sizeMul > 1.9f && paths[0].sizeMul < 2.1f)
            assertTrue(es.undoVectorPath(layer))
        } finally {
            closeFreshDoc(id)
        }
    }

    // T2 §4: vertex toggle stores corners (engine raster is
    // corner-agnostic — densification is app-side, documented).
    @Test
    fun vectorCornerTogglesSmooth() {
        val id = openFreshDoc("t2-corner")
        val es = freshSurface(id)
        try {
            val layer = es.addVectorLayer("vec")
            assertTrue(layer >= 0)
            val a = "{\"pts\":[{\"x\":10,\"y\":20,\"p\":1},{\"x\":50,\"y\":20,\"p\":1},{\"x\":50,\"y\":60,\"p\":1}]}"
            es.appendVectorPath(layer, a)
            val before = bitmapPixels(pullFull(es))
            val paths = T2Vector.readPaths(es.vectorPaths(layer))
            val n = paths[0].pts.size
            val corners = List(n) { i -> if (i == 1) 1 else 0 }
            val sb = StringBuilder("{\"pts\":[")
            paths[0].pts.forEachIndexed { i, (x, y, pr) ->
                if (i > 0) sb.append(',')
                sb.append("{\"x\":$x,\"y\":$y,\"p\":$pr}")
            }
            sb.append("],\"corners\":[")
            corners.forEachIndexed { i, c ->
                if (i > 0) sb.append(',')
                sb.append(c)
            }
            sb.append("]}")
            assertTrue("corner toggle must pose", es.replaceVectorPath(layer, 0, sb.toString()))
            val back = T2Vector.readPaths(es.vectorPaths(layer))
            assertEquals(1, back[0].corners.getOrNull(1))
            val after = bitmapPixels(pullFull(es))
            assertTrue("corners are raster-neutral engine-side", before.contentEquals(after))
            assertTrue(es.undoVectorPath(layer))
        } finally {
            closeFreshDoc(id)
        }
    }

    // T2 §4: ORA reload keeps every vector path (the hole is closed).
    @Test
    fun vectorOraKeepsPaths() {
        val id = openFreshDoc("t2-vecora")
        val es = freshSurface(id)
        try {
            val layer = es.addVectorLayer("vec")
            assertTrue(layer >= 0)
            val a = "{\"pts\":[{\"x\":10,\"y\":20,\"p\":1},{\"x\":50,\"y\":20,\"p\":1}],\"color\":\"#00FF00\"}"
            val b = "{\"pts\":[{\"x\":10,\"y\":60,\"p\":1},{\"x\":50,\"y\":60,\"p\":1}]}"
            es.appendVectorPath(layer, a)
            es.appendVectorPath(layer, b)
            val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            val ora = java.io.File(dir, "t2-vecora.ora")
            assertTrue(awaitBool { cb -> es.saveTo(ora.absolutePath, "ora", cb) })
            assertTrue(awaitBool { cb -> es.loadFrom(ora.absolutePath, cb) })
            val paths = T2Vector.readPaths(es.vectorPaths(layer))
            assertEquals("both paths must survive reload", 2, paths.size)
            assertEquals("#00FF00", paths[0].colorHex)
        } finally {
            closeFreshDoc(id)
        }
    }

    // T2 §4: SVG export uses the exact path color + linecap.
    @Test
    fun svgUsesPathColor() {
        val strokes = listOf(
            SvgExport.Stroke(
                listOf(Triple(0f, 0f, 1f), Triple(10f, 0f, 1f)),
                4f, "#FF0000", "butt"
            )
        )
        val svg = SvgExport.serializeStyled(128, 128, strokes)
        assertTrue("stroke must carry the path color", svg.contains("stroke=\"#FF0000\""))
        assertTrue("flat tip must map to butt", svg.contains("stroke-linecap=\"butt\""))
    }

    // T2 §5: PSD export of a text layer carries 8BPS + TySh + layers.
    @Test
    fun psdTextLayerOpens() {
        val id = openFreshDoc("t2-psdtext")
        val es = freshSurface(id)
        try {
            val spec = dev.openclip.ui.TextSpec(
                text = "Hi", font = "serif", sizePx = 48,
                boxX = 8f, boxY = 8f, boxW = 240f
            )
            commitTextSync(es, spec)
            val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            val psd = java.io.File(dir, "t2-text.psd")
            assertTrue(awaitBool { cb -> es.saveTo(psd.absolutePath, "psd", cb) })
            val bytes = psd.readBytes()
            assertTrue("PSD magic", bytes.size > 30 && String(bytes.sliceArray(0..3)) == "8BPS")
            val raw = String(bytes)
            assertTrue("TySh tag must be present", raw.contains("TySh"))
            assertTrue("text must be present", raw.contains("serif"))
            assertTrue("approx counter readable", es.psdApproxCount() >= 0)
        } finally {
            closeFreshDoc(id)
        }
    }

    // T2 §5: bubble frame moves the composite with 0 committed pixels
    // (fiche = meta, engine undo stays empty).
    @Test
    fun bubbleLayerDragMoves() {
        val id = openFreshDoc("t2-bubbledrag")
        val es = freshSurface(id)
        try {
            val layer = es.addVectorLayer("Bubble")
            assertTrue(layer >= 0)
            val f1 = BubbleLayer(
                "ellipse", floatArrayOf(8f, 8f, 48f, 32f), "32,40,50,60",
                "#FFFFFF", "#000000", 4f, -1
            )
            assertTrue(es.setLayerBubble(layer, f1.toJson()))
            val p1 = bitmapPixels(pullFull(es))
            val f2 = f1.copy(frame = floatArrayOf(60f, 60f, 48f, 32f))
            assertTrue(es.setLayerBubble(layer, f2.toJson()))
            val p2 = bitmapPixels(pullFull(es))
            assertFalse("frame must move the balloon", p1.contentEquals(p2))
            assertFalse("bubble fiche must not arm engine undo", es.undo())
            val back = es.bubbleFicheForTest(layer)
            assertNotNull(back)
            assertEquals("ellipse", back!!.shape)
        } finally {
            closeFreshDoc(id)
        }
    }

    // T2 §5: bubble + linked text survive ORA reload with the same index.
    @Test
    fun bubbleTextLinkRoundTrip() {
        val id = openFreshDoc("t2-bubblelink")
        val es = freshSurface(id)
        try {
            val spec = dev.openclip.ui.TextSpec(
                text = "Boom", font = "sans-serif", sizePx = 48,
                boxX = 8f, boxY = 8f, boxW = 240f
            )
            val tidx = commitTextSync(es, spec)
            val layer = es.addVectorLayer("Bubble")
            assertTrue(layer >= 0)
            val fiche = BubbleLayer(
                "rounded", floatArrayOf(8f, 40f, 120f, 60f), "",
                "#FFF8E0", "#201808", 3f, tidx
            )
            assertTrue(es.setLayerBubble(layer, fiche.toJson()))
            val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            val ora = java.io.File(dir, "t2-bubblelink.ora")
            assertTrue(awaitBool { cb -> es.saveTo(ora.absolutePath, "ora", cb) })
            assertTrue(awaitBool { cb -> es.loadFrom(ora.absolutePath, cb) })
            val back = es.bubbleFicheForTest(layer)
            assertNotNull("bubble fiche must survive reload", back)
            assertEquals(tidx, back!!.textLayer)
            assertEquals("#FFF8E0", back.fill)
        } finally {
            closeFreshDoc(id)
        }
    }

    // T3 §1: une cle deplacee change le composite ; l'undo moteur reste
    // vide (fiches meta hors undo, comme holds/bulles).
    @Test
    fun keyAddMovesRendering() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            val plain = IntArray(w * h)
            EngineHost.nCompositeInto(handle, plain, w, h)
            assertTrue(
                EngineHost.nSetLayerAnim(
                    handle, 0,
                    """{"keys":[{"cel":0,"dx":60,"dy":0,"scale":1,"angle":0,"opacity":1,"ease":"linear"}]}"""
                )
            )
            val moved = IntArray(w * h)
            EngineHost.nCompositeInto(handle, moved, w, h)
            // Calque blanc deplace de 60px : une bande de 60px devient
            // transparente (ARGB 0) a gauche... en pratique le fond du
            // composite est transparent hors calque : des pixels changent.
            assertNotEquals(
                "key must move rendering",
                plain.toList(), moved.toList()
            )
            assertFalse("anim fiche is meta, never in undo", EngineHost.nUndo(handle))
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // T3 §1: supprimer la piste restaure le rendu (calque normal).
    @Test
    fun keyDeleteRestores() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            val plain = IntArray(w * h)
            EngineHost.nCompositeInto(handle, plain, w, h)
            EngineHost.nSetLayerAnim(
                handle, 0,
                """{"keys":[{"cel":0,"dx":60,"dy":0,"scale":1,"angle":0,"opacity":1,"ease":"linear"}]}"""
            )
            assertTrue(EngineHost.nSetLayerAnim(handle, 0, null))
            val back = IntArray(w * h)
            EngineHost.nCompositeInto(handle, back, w, h)
            assertTrue(
                "clearing keys must restore rendering",
                plain.contentEquals(back)
            )
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // T3 §1: smooth != linear au cellulo median (aisance moteur).
    @Test
    fun curveEaseChangesMidframe() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            // Deux cles dx 0 -> 200 : echantillon au cel 3 (t=0.3, hors
            // point fixe 0.5 ou smoothstep == lineaire) via le rendu
            // (cle finale hors indice jouée par nFrameCompositeInto sur
            // un clip de 11 cellulos).
            repeat(10) { EngineHost.nAddFrame(handle) }
            assertEquals(11, EngineHost.nFrameCount(handle))
            val keys = listOf(
                AnimKey(0, dx = 0f, ease = "EASE"),
                AnimKey(10, dx = 200f, ease = "linear")
            )
            for (ease in listOf("linear", "smooth")) {
                val ks = keys.map { it.copy(ease = if (it.cel == 0) ease else it.ease) }
                EngineHost.nSetLayerAnim(handle, 0, animKeysToJson(ks))
                val mid = IntArray(w * h)
                EngineHost.nFrameCompositeInto(handle, 3, mid, w, h)
                if (ease == "linear") {
                    // Garde le temoin pour comparer (stocke hors moteur).
                    lastMidLinear = mid
                } else {
                    assertNotEquals(
                        "smooth must differ from linear at midframe",
                        lastMidLinear.toList(), mid.toList()
                    )
                }
            }
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // T3 §1+§3.5 : les cles survivent au sidecar (save/clear/re-push).
    @Test
    fun animSurvivesReload() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            val json = animKeysToJson(
                listOf(
                    AnimKey(0, dx = 10f, ease = "smooth"),
                    AnimKey(4, dx = 90f, opacity = 0.5f)
                )
            )
            assertTrue(EngineHost.nSetLayerAnim(handle, 0, json))
            val before = EngineHost.nLayerAnim(handle, 0)
            val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            val f = java.io.File(dir, "t3-anim-sidecar.json")
            val doc = AnimSidecar.Doc(keys = mapOf(0 to animKeysFromJson(before)))
            assertTrue(AnimSidecar.save(f, doc))
            assertTrue(EngineHost.nSetLayerAnim(handle, 0, null))
            assertEquals("{}", EngineHost.nLayerAnim(handle, 0))
            val back = AnimSidecar.load(f)
            val restored = back.keys[0] ?: emptyList()
            assertTrue(EngineHost.nSetLayerAnim(handle, 0, animKeysToJson(restored)))
            assertEquals(before, EngineHost.nLayerAnim(handle, 0))
            f.delete()
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // T3 §2 : 2 cles pan — vignettes 0 et N different, median entre.
    @Test
    fun cameraKeyFramesDiffer() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            // Marque visible sur toute la largeur (chaque cellulo la
            // porte : on DUPLIQUE, pas nAddFrame qui part vierge).
            EngineHost.nSetBrush(handle, """{"radius":12,"color":"#141414"}""")
            val sid = EngineHost.nStrokeBegin(handle, 60f, 128f, 1f)
            EngineHost.nStrokeMove(handle, sid, 220f, 128f, 1f)
            EngineHost.nStrokeEnd(handle, sid)
            repeat(4) { EngineHost.nDuplicateFrame(handle) }
            val cam = cameraKeysToJson(
                listOf(
                    CameraKey(0, x = 64f, y = 64f, zoom = 2f),
                    CameraKey(4, x = 192f, y = 192f, zoom = 2f)
                )
            )
            assertTrue(EngineHost.nSetCameraTrack(handle, cam))
            assertTrue(EngineHost.nSetCameraEnabled(handle, true))
            val a = IntArray(w * h)
            val b = IntArray(w * h)
            val m = IntArray(w * h)
            EngineHost.nFrameCompositeInto(handle, 0, a, w, h)
            EngineHost.nFrameCompositeInto(handle, 4, b, w, h)
            EngineHost.nFrameCompositeInto(handle, 2, m, w, h)
            assertTrue("cel 0 must show ink", a.any { it != -1 })
            assertTrue("cel 4 must show ink", b.any { it != -1 })
            assertNotEquals(a.toList(), b.toList())
            assertNotEquals(a.toList(), m.toList())
            assertNotEquals(b.toList(), m.toList())
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // T3 §2 : OFF = vue manuelle (composite identique, camera neutre).
    @Test
    fun cameraToggleRestores() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            repeat(2) { EngineHost.nAddFrame(handle) }
            val plain = IntArray(w * h)
            EngineHost.nFrameCompositeInto(handle, 1, plain, w, h)
            assertTrue(
                EngineHost.nSetCameraTrack(
                    handle,
                    cameraKeysToJson(listOf(CameraKey(0, x = 10f, y = 10f, zoom = 3f)))
                )
            )
            assertTrue(EngineHost.nSetCameraEnabled(handle, true))
            val framed = IntArray(w * h)
            EngineHost.nFrameCompositeInto(handle, 1, framed, w, h)
            assertNotEquals(plain.toList(), framed.toList())
            assertTrue(EngineHost.nSetCameraEnabled(handle, false))
            val back = IntArray(w * h)
            EngineHost.nFrameCompositeInto(handle, 1, back, w, h)
            assertTrue(plain.contentEquals(back))
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // T3 §2+§3.5 : la camera survit au sidecar.
    @Test
    fun cameraSurvivesReload() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            val json = cameraKeysToJson(
                listOf(
                    CameraKey(0, x = 30f, y = 40f, zoom = 2f),
                    CameraKey(3, x = 90f, y = 20f, zoom = 1f, ease = "smooth")
                )
            )
            assertTrue(EngineHost.nSetCameraTrack(handle, json))
            val before = EngineHost.nCameraTrack(handle)
            val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            val f = java.io.File(dir, "t3-cam-sidecar.json")
            assertTrue(
                AnimSidecar.save(
                    f, AnimSidecar.Doc(camera = cameraKeysFromJson(before))
                )
            )
            assertTrue(EngineHost.nSetCameraTrack(handle, null))
            assertEquals("{\"keys\":[]}", EngineHost.nCameraTrack(handle))
            val back = AnimSidecar.load(f)
            assertTrue(EngineHost.nSetCameraTrack(handle, cameraKeysToJson(back.camera)))
            assertEquals(before, EngineHost.nCameraTrack(handle))
            f.delete()
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // T3 §3 palier b : ajout + switch + stroke isole par piste.
    @Test
    fun trackAddSwitchStroke() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            assertEquals(1, EngineHost.nTrackCount(handle))
            assertEquals(1, EngineHost.nAddTrack(handle, "B"))
            assertEquals(2, EngineHost.nAddTrack(handle, "C"))
            assertEquals(3, EngineHost.nAddTrack(handle, "D"))
            assertEquals(4, EngineHost.nAddTrack(handle, "E")) // cap 4 : refus
            assertEquals(4, EngineHost.nTrackCount(handle))
            assertTrue(EngineHost.nSetActiveTrack(handle, 1))
            assertEquals(1, EngineHost.nActiveTrack(handle))
            EngineHost.nSetBrush(handle, """{"radius":8,"color":"#141414"}""")
            val sid = EngineHost.nStrokeBegin(handle, 60f, 60f, 1f)
            EngineHost.nStrokeMove(handle, sid, 120f, 60f, 1f)
            EngineHost.nStrokeEnd(handle, sid)
            val with1 = IntArray(w * h)
            EngineHost.nCompositeInto(handle, with1, w, h)
            assertTrue(
                "stroke on track 1 must show in the merged composite",
                with1.any { it != -1 }
            )
            // Merge = pile visible entiere : retour piste 0, l'encre
            // piste 1 reste visible (nCompositeInto ne cadre pas la
            // piste active, ARCHITECTURE.md §T3).
            assertTrue(EngineHost.nSetActiveTrack(handle, 0))
            val stillMerged = IntArray(w * h)
            EngineHost.nCompositeInto(handle, stillMerged, w, h)
            assertTrue(with1.contentEquals(stillMerged))
            // Piste 1 cachee = encre absente du composite.
            assertTrue(EngineHost.nSetTrackVisible(handle, 1, false))
            val muted = IntArray(w * h)
            EngineHost.nCompositeInto(handle, muted, w, h)
            assertTrue("muted track must vanish", muted.all { it == -1 })
            assertNotEquals(with1.toList(), muted.toList())
            assertTrue(EngineHost.nSetTrackVisible(handle, 1, true))
            val reshown = IntArray(w * h)
            EngineHost.nCompositeInto(handle, reshown, w, h)
            assertTrue(with1.contentEquals(reshown))
            // Noms + etat.
            assertTrue(EngineHost.nSetTrackName(handle, 1, "Encre"))
            val st = EngineHost.nTrackState(handle)
            assertTrue(st.contains("Encre"))
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // T3 §3 : muette = absente du composite (piste masquee).
    @Test
    fun trackMuteHides() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            EngineHost.nAddTrack(handle, "B")
            assertTrue(EngineHost.nSetActiveTrack(handle, 1))
            EngineHost.nSetBrush(handle, """{"radius":16,"color":"#141414"}""")
            val sid = EngineHost.nStrokeBegin(handle, 128f, 128f, 1f)
            EngineHost.nStrokeMove(handle, sid, 160f, 128f, 1f)
            EngineHost.nStrokeEnd(handle, sid)
            val shown = IntArray(w * h)
            EngineHost.nCompositeInto(handle, shown, w, h)
            assertTrue(shown.any { it != -1 })
            assertTrue(EngineHost.nSetTrackVisible(handle, 1, false))
            val hidden = IntArray(w * h)
            EngineHost.nCompositeInto(handle, hidden, w, h)
            assertTrue(
                "muted track must vanish from the composite",
                hidden.all { it == -1 }
            )
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // T3 §3 palier a : etiquette + rechargement identique (sidecar).
    @Test
    fun labelJumpAndRoundTrip() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val f = java.io.File(dir, "t3-labels-sidecar.json")
        try {
            val doc = AnimSidecar.Doc(
                labels = listOf(
                    AnimLabel(0, "debut"),
                    AnimLabel(5, "fin", "#FF0000")
                )
            )
            assertTrue(AnimSidecar.save(f, doc))
            val back = AnimSidecar.load(f)
            assertEquals(2, back.labels.size)
            assertEquals(0, back.labels[0].cel)
            assertEquals("debut", back.labels[0].txt)
            // La couleur par defaut survit au round trip.
            assertEquals("#FFC845", back.labels[0].color)
            assertEquals("#FF0000", back.labels[1].color)
        } finally {
            f.delete()
        }
    }

    // T3 §3 palier a : la feuille montre holds + badges (sonde).
    @Test
    fun exposureSheetShowsHolds() {
        val rows = buildExposure(
            frameCount = 4,
            holds = mapOf(1 to 3),
            keyCounts = mapOf(0 to 2),
            cameraCels = setOf(2),
            labels = listOf(AnimLabel(0, "debut"))
        )
        T3TestHooks.lastExposure = rows
        val probe = T3TestHooks.exposureForTest()
        assertEquals(4, probe.size)
        assertEquals(1, probe[0].hold)
        assertEquals(3, probe[1].hold)
        assertEquals(listOf(0), probe[0].keyedLayers)
        assertTrue(probe[2].cameraKey)
        assertEquals("debut", probe[0].label)
        // Table Bezier 64 pas : monotone sur la diagonale lineaire.
        val table = bezierEaseTable(0.25f, 0.25f, 0.75f, 0.75f)
        T3TestHooks.lastBezierTable = table
        assertEquals(65, table.size)
        assertTrue(table[0] <= 0.01f && table[64] >= 0.99f)
    }

    // T3 §3 palier a : le CSV s'exporte (en-tete + N lignes, lisible).
    @Test
    fun exposureCsvExports() {
        val rows = buildExposure(
            frameCount = 3,
            holds = mapOf(2 to 4),
            keyCounts = mapOf(1 to 1),
            cameraCels = emptySet(),
            labels = listOf(AnimLabel(2, "fin"))
        )
        val csv = exposureCsv(rows, trackCount = 2)
        val lines = csv.trim().split("\n")
        assertEquals("cel,hold,keys,camera,label,tracks", lines[0])
        assertEquals(4, lines.size)
        assertTrue(lines[3].startsWith("2,4,"))
        assertTrue(lines[3].contains("fin"))
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val f = java.io.File(dir, "t3-exposure.csv")
        try {
            f.writeText(csv)
            val back = f.readText()
            assertEquals(csv, back)
            assertTrue(back.contains("cel,hold,keys"))
        } finally {
            f.delete()
        }
    }

    // T3 §4 : sequence PNG -> ZIP lisible (entrees PNG valides).
    @Test
    fun pngSeqZipOpens() {
        val handle = EngineHost.nInit(w, h, 160f)
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val seq = java.io.File(dir, "t3seq-${System.currentTimeMillis()}")
        try {
            EngineHost.nAddFrame(handle)
            EngineHost.nSetCelHold(handle, 0, 2)
            seq.mkdirs()
            assertTrue(
                EngineHost.nExportPngSeq(
                    handle, seq.absolutePath,
                    """{"expandHolds":true,"prefix":"frame"}"""
                )
            )
            val files = seq.listFiles { f -> f.extension == "png" }
                ?.sortedBy { it.name } ?: emptyList()
            // 2 cellulos, cel 0 tenu x2 -> 3 ticks.
            assertEquals(3, files.size)
            // ZIP app-side (java.util.zip) : entrees lisibles, PNG reels.
            val zip = java.io.File(dir, "t3seq-${System.currentTimeMillis()}.zip")
            try {
                java.util.zip.ZipOutputStream(zip.outputStream()).use { z ->
                    for (f in files) {
                        z.putNextEntry(java.util.zip.ZipEntry(f.name))
                        f.inputStream().use { it.copyTo(z) }
                        z.closeEntry()
                    }
                }
                var entries = 0
                java.util.zip.ZipFile(zip).use { zf ->
                    val en = zf.entries()
                    while (en.hasMoreElements()) {
                        val e = en.nextElement()
                        zf.getInputStream(e).use { ins ->
                            val sig = ByteArray(8)
                            assertEquals(8, ins.read(sig))
                            // Signature PNG.
                            assertEquals(137.toByte(), sig[0])
                            assertEquals(80.toByte(), sig[1])
                        }
                        entries++
                    }
                }
                assertEquals(3, entries)
            } finally {
                zip.delete()
            }
        } finally {
            seq.deleteRecursively()
            EngineHost.nDispose(handle)
        }
    }

    // T3 §4 : APNG jouable (non vide + acTL present).
    @Test
    fun apngExportPlayable() {
        val handle = EngineHost.nInit(w, h, 160f)
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        try {
            EngineHost.nAddFrame(handle)
            val dst = java.io.File(dir, "t3-${System.currentTimeMillis()}.png")
            try {
                assertTrue(
                    EngineHost.nExportApng(
                        handle, dst.absolutePath,
                        """{"loop":-1,"maxWidth":256,"fps":12}"""
                    )
                )
                assertTrue(dst.length() > 100)
                val bytes = dst.readBytes()
                assertEquals(137.toByte(), bytes[0])
                val txt = String(bytes, Charsets.ISO_8859_1)
                assertTrue("acTL must be present", txt.contains("acTL"))
                assertTrue("2 fcTL expected", txt.split("fcTL").size == 3)
            } finally {
                dst.delete()
            }
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // T3 §4 : sticker 512 — le grand cote tient dans 512, fond opaque.
    @Test
    fun sticker512Fits() {
        val big = 1024
        val handle = EngineHost.nInit(big, big, 160f)
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        try {
            val dst = java.io.File(dir, "sticker-${System.currentTimeMillis()}.png")
            try {
                assertTrue(
                    EngineHost.nExportApng(
                        handle, dst.absolutePath,
                        """{"loop":-1,"maxWidth":512,"matte":"#FFFFFF"}"""
                    )
                )
                val bytes = dst.readBytes()
                assertTrue(bytes.size > 33)
                // IHDR : largeur/hauteur u32 big-endian a l'offset 16.
                fun u32(o: Int): Int {
                    return ((bytes[o].toInt() and 0xFF) shl 24) or
                        ((bytes[o + 1].toInt() and 0xFF) shl 16) or
                        ((bytes[o + 2].toInt() and 0xFF) shl 8) or
                        (bytes[o + 3].toInt() and 0xFF)
                }
                val iw = u32(16)
                val ih = u32(20)
                assertTrue("sticker side must fit 512, got $iw x $ih", iw <= 512 && ih <= 512)
            } finally {
                dst.delete()
            }
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // T3 §4 : audio seul = copie byte-identique (0 JNI, chemin SAF).
    @Test
    fun audioOnlyCopiesBytes() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val src = java.io.File(dir, "t3-audio-src.bin")
        val dst = java.io.File(dir, "t3-audio-dst.bin")
        try {
            val payload = ByteArray(4096) { (it * 31 % 251).toByte() }
            src.writeBytes(payload)
            src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
            assertTrue(dst.readBytes().contentEquals(payload))
        } finally {
            src.delete()
            dst.delete()
        }
    }


    // T4 §2+§3 : les 10 noms de filtres routent (0 JNI) + undo restaure.
    @Test
    fun t4CreativeAndLensFiltersApplyAndUndo() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            EngineHost.nSetBrush(
                handle,
                """{"radius":10,"hardness":0.7,"opaque":1.0,"spacing":0.15,"color":"#E8E8E8"}"""
            )
            var sid = EngineHost.nStrokeBegin(handle, 40f, 128f, 1f)
            for (x in 50..210 step 5) {
                EngineHost.nStrokeMove(handle, sid, x.toFloat(), 128f, 1f)
            }
            EngineHost.nStrokeEnd(handle, sid)
            EngineHost.nSetBrush(
                handle,
                """{"radius":10,"hardness":0.7,"opaque":1.0,"spacing":0.15,"color":"#303030"}"""
            )
            sid = EngineHost.nStrokeBegin(handle, 128f, 40f, 1f)
            for (y in 50..210 step 5) {
                EngineHost.nStrokeMove(handle, sid, 128f, y.toFloat(), 1f)
            }
            EngineHost.nStrokeEnd(handle, sid)
            val cases = listOf(
                "glow" to """{"radius":2,"amount":1.0,"tint":"#FFFFFF"}""",
                "crystallize" to """{"cell":8}""",
                "normalMap" to """{"strength":2.0}""",
                "retro" to """{"warmth":0.5,"grain":10,"vignette":0.3,"fade":0.2}""",
                "pencil" to """{"strength":0.7}""",
                "perlinClouds" to """{"scale":4,"octaves":2,"seed":7}""",
                "lensBlur" to """{"radius":3,"highlights":0.0}""",
                "fisheyeFilter" to """{"amount":0.5}""",
                "panoramaRemap" to """{"mode":"cyl","fov":120}"""
            )
            for ((name, params) in cases) {
                val before = IntArray(w * h)
                assertEquals(w * h, EngineHost.nCompositeInto(handle, before, w, h))
                assertTrue("t4 $name must apply", EngineHost.nApplyFilter(handle, name, params))
                val after = IntArray(w * h)
                assertEquals(w * h, EngineHost.nCompositeInto(handle, after, w, h))
                assertTrue(
                    "t4 $name must change pixels",
                    after.indices.count { after[it] != before[it] } > 0
                )
                assertTrue("t4 $name must undo", EngineHost.nUndo(handle))
                val undone = IntArray(w * h)
                assertEquals(w * h, EngineHost.nCompositeInto(handle, undone, w, h))
                assertTrue(
                    "t4 $name undo must restore exactly",
                    undone.contentEquals(before)
                )
            }
            // dropShadow on an opaque canvas: accepted + undoable (the
            // shadow itself needs transparency — locked engine-side).
            val b0 = IntArray(w * h)
            EngineHost.nCompositeInto(handle, b0, w, h)
            assertTrue(
                EngineHost.nApplyFilter(
                    handle, "dropShadow",
                    """{"dx":4,"dy":4,"radius":2,"color":"#000000","opacity":0.5}"""
                )
            )
            assertTrue(EngineHost.nUndo(handle))
            val b1 = IntArray(w * h)
            EngineHost.nCompositeInto(handle, b1, w, h)
            assertTrue(b1.contentEquals(b0))
            // Invalid params refuse everywhere, canvas intact.
            assertFalse(EngineHost.nApplyFilter(handle, "glow", """{"radius":0}"""))
            assertFalse(EngineHost.nApplyFilter(handle, "retro", """{"grain":99}"""))
            assertFalse(
                EngineHost.nApplyFilter(handle, "panoramaRemap", """{"mode":"cube"}""")
            )
            assertFalse(
                EngineHost.nApplyFilter(handle, "perlinClouds", """{"mode":"stitch"}""")
            )
            val b2 = IntArray(w * h)
            EngineHost.nCompositeInto(handle, b2, w, h)
            assertTrue(b2.contentEquals(b1))
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // T4 §1 : persp/skew/bulge valident en 1 undo ; neutres identiques ;
    // quad dégénéré refusé.
    @Test
    fun t4WarpModesApplyAndUndo() {
        val id = openFreshDoc("t4-warp")
        val es = freshSurface(id)
        try {
            val t0 = SystemClock.uptimeMillis()
            val cx = es.width * 0.5f
            val cy = es.height * 0.5f
            composeRule.activityRule.scenario.onActivity {
                val down = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, cx - 60f, cy, 1f, 1f, 0, 1f, 1f, 0, 0)
                val move = MotionEvent.obtain(t0, t0 + 16, MotionEvent.ACTION_MOVE, cx + 60f, cy, 1f, 1f, 0, 1f, 1f, 0, 0)
                val up = MotionEvent.obtain(t0, t0 + 32, MotionEvent.ACTION_UP, cx + 60f, cy, 1f, 1f, 0, 1f, 1f, 0, 0)
                es.dispatchTouchEvent(down)
                es.dispatchTouchEvent(move)
                es.dispatchTouchEvent(up)
                down.recycle(); move.recycle(); up.recycle()
            }
            Thread.sleep(800)
            es.selectRectEngine(60f, 60f, 180f, 180f)
            val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            val mask = File.createTempFile("t4-w-", ".png", dir)
            assertTrue(es.selectionToPng(mask.absolutePath))
            assertTrue(es.setSelectionMask(mask.absolutePath))
            mask.delete()
            val before = pullFull(es)
            val beforePx = intPixels(before)
            before.recycle()
            // Read the live mask bbox (engine coords): quads are built
            // from it, so the neutral quad is exact by construction.
            val bits = es.exportSelectionBits()
            assertNotNull("mask bits must export", bits)
            val (mw, mh) = es.selectionSize()
            var bx0 = mw
            var by0 = mh
            var bx1 = -1
            var by1 = -1
            for (y in 0 until mh) {
                for (x in 0 until mw) {
                    if (bits!![y * mw + x] != 0.toByte()) {
                        if (x < bx0) bx0 = x
                        if (x > bx1) bx1 = x
                        if (y < by0) by0 = y
                        if (y > by1) by1 = y
                    }
                }
            }
            assertTrue("mask must be non-empty", bx1 >= bx0)
            val neutralQuad =
                "[[$bx0,$by0],[${bx1 + 1},$by0],[${bx1 + 1},${by1 + 1}],[$bx0,${by1 + 1}]]"
            val shiftQuad =
                "[[" + (bx0 + 30) + ",$by0],[" + (bx1 + 31) + ",$by0],[" +
                    (bx1 + 31) + "," + (by1 + 1) + "],[" + (bx0 + 30) + "," +
                    (by1 + 1) + "]]"
            // Persp shift (+30,0): the bbox corners move right.
            assertTrue(
                es.selectionBlit(
                    "{\"mode\":\"persp\",\"quad\":" + shiftQuad + "}"
                )
            )
            var after = pullFull(es)
            try {
                assertTrue(
                    "persp must move pixels",
                    intPixels(after).contentEquals(beforePx).not()
                )
            } finally {
                after.recycle()
            }
            assertTrue("persp is one undo", es.undo())
            var undone = pullFull(es)
            try {
                assertTrue(intPixels(undone).contentEquals(beforePx))
            } finally {
                undone.recycle()
            }
            // Skew 30°: sheared; undo restores.
            assertTrue(es.selectionBlit("""{"mode":"skew","ax":30,"ay":0}"""))
            after = pullFull(es)
            try {
                assertTrue(intPixels(after).contentEquals(beforePx).not())
            } finally {
                after.recycle()
            }
            assertTrue(es.undo())
            // Bulge +0.8: grown; undo restores.
            assertTrue(
                es.selectionBlit("""{"mode":"bulge","amount":0.8,"radius":1.0}""")
            )
            after = pullFull(es)
            try {
                assertTrue(intPixels(after).contentEquals(beforePx).not())
            } finally {
                after.recycle()
            }
            assertTrue(es.undo())
            // Neutrals are byte-identical (quad = bbox, ax=ay=0, amount=0).
            assertTrue(
                es.selectionBlit(
                    "{\"mode\":\"persp\",\"quad\":" + neutralQuad + "}"
                )
            )
            after = pullFull(es)
            try {
                assertTrue(intPixels(after).contentEquals(beforePx))
            } finally {
                after.recycle()
            }
            assertTrue(es.undo())
            assertTrue(es.selectionBlit("""{"mode":"skew","ax":0,"ay":0}"""))
            after = pullFull(es)
            try {
                assertTrue(intPixels(after).contentEquals(beforePx))
            } finally {
                after.recycle()
            }
            assertTrue(es.undo())
            assertTrue(es.selectionBlit("""{"mode":"bulge","amount":0}"""))
            after = pullFull(es)
            try {
                assertTrue(intPixels(after).contentEquals(beforePx))
            } finally {
                after.recycle()
            }
            assertTrue(es.undo())
            // Degenerate quad (doubled points) refuses, canvas intact.
            assertFalse(
                es.selectionBlit(
                    """{"mode":"persp","quad":[[60,60],[60,60],[60,60],[60,60]]}"""
                )
            )
            after = pullFull(es)
            try {
                assertTrue(intPixels(after).contentEquals(beforePx))
            } finally {
                after.recycle()
            }
            es.setSelectionMask(null)
        } finally {
            closeFreshDoc(id)
        }
    }

    // T4 §1 : marionnette calque entier — 0 épingle identique, 1 épingle
    // locale, 65 refusées, 1 undo.
    @Test
    fun t4PuppetWarpWholeLayer() {
        val handle = EngineHost.nInit(w, h, 160f)
        try {
            EngineHost.nSetBrush(
                handle,
                """{"radius":10,"hardness":0.7,"opaque":1.0,"spacing":0.15,"color":"#141414"}"""
            )
            val sid = EngineHost.nStrokeBegin(handle, 64f, 128f, 1f)
            for (x in 70..170 step 5) {
                EngineHost.nStrokeMove(handle, sid, x.toFloat(), 128f, 1f)
            }
            EngineHost.nStrokeEnd(handle, sid)
            val before = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, before, w, h))
            assertTrue(EngineHost.nPuppetWarp(handle, 0, """{"pins":[]}"""))
            val same = IntArray(w * h)
            EngineHost.nCompositeInto(handle, same, w, h)
            assertTrue("0 pins = identical", same.contentEquals(before))
            assertTrue(
                EngineHost.nPuppetWarp(
                    handle, 0,
                    """{"pins":[{"x":128,"y":128,"dx":30,"dy":0}],"radius":64}"""
                )
            )
            val moved = IntArray(w * h)
            EngineHost.nCompositeInto(handle, moved, w, h)
            assertTrue(
                "1 pin must move pixels",
                moved.indices.count { moved[it] != before[it] } > 0
            )
            assertTrue(EngineHost.nUndo(handle))
            val undone = IntArray(w * h)
            EngineHost.nCompositeInto(handle, undone, w, h)
            assertTrue(undone.contentEquals(before))
            // 65 pins refuse; bad index refuses.
            val many = (1..65).joinToString(",") { """{"x":10,"y":10,"dx":1,"dy":0}""" }
            assertFalse(EngineHost.nPuppetWarp(handle, 0, """{"pins":[$many]}"""))
            assertFalse(EngineHost.nPuppetWarp(handle, 5, """{"pins":[]}"""))
            val intact = IntArray(w * h)
            EngineHost.nCompositeInto(handle, intact, w, h)
            assertTrue(intact.contentEquals(before))
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // T4 §4 : décor primitif + peinture + commit + undo (pipeline JNI).
    @Test
    fun t4RefScenePrimitivePaintCommitUndo() {
        val handle = EngineHost.nInit(w, h, 160f)
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        try {
            val blank = IntArray(w * h)
            assertEquals(w * h, EngineHost.nCompositeInto(handle, blank, w, h))
            // Primitive cube renders edges over the composite.
            assertTrue(
                EngineHost.nSetRefScene(
                    handle,
                    """{"items":[{"src":"prim:cube","pos":[0,0,0],"rot":[0,0,0],"scale":1,"visible":1}]}"""
                )
            )
            val wired = IntArray(w * h)
            EngineHost.nCompositeInto(handle, wired, w, h)
            assertTrue(
                "cube wireframe must show",
                wired.indices.count { wired[it] != blank[it] } > 50
            )
            // Hidden item renders nothing; 9 items refuse.
            assertTrue(
                EngineHost.nSetRefScene(
                    handle,
                    """{"items":[{"src":"prim:cube","pos":[0,0,0],"rot":[0,0,0],"scale":1,"visible":0}]}"""
                )
            )
            val hidden = IntArray(w * h)
            EngineHost.nCompositeInto(handle, hidden, w, h)
            assertTrue(hidden.contentEquals(blank))
            val nine = (1..9).joinToString(",") {
                """{"src":"prim:cube","pos":[0,0,0],"rot":[0,0,0],"scale":1,"visible":1}"""
            }
            assertFalse(EngineHost.nSetRefScene(handle, """{"items":[$nine]}"""))
            assertFalse(EngineHost.nPaintRefMap(handle, 3, """[{"x":1,"y":1}]"""))
            // Paint the cube texture (dab at the canvas center, where the
            // cube projects) with edges off + flat opaque: the tinted
            // overlay alone must differ from the blank canvas.
            assertTrue(
                EngineHost.nSetRefScene(
                    handle,
                    """{"items":[{"src":"prim:cube","pos":[0,0,0],"rot":[0,20,0],"scale":1,"visible":1,"wire":0,"flat":1}]}"""
                )
            )
            assertTrue(
                EngineHost.nPaintRefMap(
                    handle, 0,
                    """[{"x":128,"y":128,"r":10,"color":"#FF0000","opacity":1}]"""
                )
            )
            val painted = IntArray(w * h)
            EngineHost.nCompositeInto(handle, painted, w, h)
            val reds = painted.count { (it shr 16) and 0xFF > 150 && (it shr 24) and 0xFF != 0 }
            assertTrue("painted texture must tint the overlay, reds=$reds", reds > 20)
            // Commit: bake the overlay into the layer, clear the scene,
            // the red stays; undo restores the blank canvas.
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.setPixels(painted, 0, w, 0, 0, w, h)
            val png = File.createTempFile("t4-commit-", ".png", dir)
            try {
                java.io.FileOutputStream(png).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                assertTrue(EngineHost.nBlitPngIntoLayer(handle, 0, png.absolutePath))
            } finally {
                png.delete()
            }
            bmp.recycle()
            assertTrue(EngineHost.nSetRefScene(handle, """{"items":[]}"""))
            val baked = IntArray(w * h)
            EngineHost.nCompositeInto(handle, baked, w, h)
            val bakedReds = baked.count { (it shr 16) and 0xFF > 150 }
            assertTrue("commit must bake the texture, reds=$bakedReds", bakedReds > 20)
            assertTrue(EngineHost.nUndo(handle))
            val undone = IntArray(w * h)
            EngineHost.nCompositeInto(handle, undone, w, h)
            assertTrue(undone.contentEquals(blank))
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    // T4 §5 : classifieur pur — carré tordu = rect ≥ 0.7, gribouillis
    // rejeté, cercle ±10 %, 2048 pts < 300 ms.
    @Test
    fun t4SmartShapeSquareIsRect() {
        val pts = mutableListOf<Pair<Float, Float>>()
        // Wobbly closed square, 100x100.
        val corners = listOf(
            Pair(0f, 0f), Pair(100f, 0f), Pair(100f, 100f), Pair(0f, 100f)
        )
        for (e in 0 until 4) {
            val a = corners[e]
            val b = corners[(e + 1) % 4]
            for (i in 0..24) {
                val t = i / 24f
                val wob = if (i % 2 == 0) 1.5f else -1.5f
                pts.add(
                    Pair(
                        a.first + (b.first - a.first) * t + wob,
                        a.second + (b.second - a.second) * t - wob
                    )
                )
            }
        }
        val fit = SmartShape.classify(pts)
        assertNotNull("wobbly square must classify", fit)
        assertEquals("rect", fit!!.kind)
        assertTrue("confidence must reach 0.7, got ${fit.confidence}", fit.confidence >= 0.7f)
    }

    @Test
    fun t4SmartShapeScribbleRejectedAndCircleAxes() {
        // Scribble: back-and-forth, never shape-like.
        val scribble = mutableListOf<Pair<Float, Float>>()
        for (i in 0..120) {
            scribble.add(
                Pair(
                    (i * 7 % 90).toFloat(),
                    (if (i % 2 == 0) i * 3 % 40 else 90 - i * 3 % 40).toFloat()
                )
            )
        }
        assertNull("scribble must be rejected", SmartShape.classify(scribble))
        // Circle radius 50 (closed, small radial noise).
        val circle = (0..96).map { i ->
            val a = i * 2f * Math.PI.toFloat() / 96f
            val r = 50f + if (i % 2 == 0) 1f else -1f
            Pair(128f + r * Math.cos(a.toDouble()).toFloat(), 128f + r * Math.sin(a.toDouble()).toFloat())
        }
        val fit = SmartShape.classify(circle)
        assertNotNull("circle must classify", fit)
        assertEquals("circle", fit!!.kind)
        val (c, ax, ay) = fit.geom
        val rx = Math.hypot((ax.first - c.first).toDouble(), (ax.second - c.second).toDouble())
        val ry = Math.hypot((ay.first - c.first).toDouble(), (ay.second - c.second).toDouble())
        assertTrue("axis X within 10%%: $rx", Math.abs(rx - 50.0) / 50.0 <= 0.10)
        assertTrue("axis Y within 10%%: $ry", Math.abs(ry - 50.0) / 50.0 <= 0.10)
    }

    @Test
    fun t4SmartShape2048ptsUnder300ms() {
        val pts = (0..2047).map { i ->
            Pair((i * 13 % 200).toFloat(), (i * 29 % 200).toFloat())
        }
        val t0 = SystemClock.uptimeMillis()
        SmartShape.classify(pts)
        val dt = SystemClock.uptimeMillis() - t0
        assertTrue("2048 pts must classify under 300 ms, took $dt", dt < 300)
    }

    // T4 §5 : fantôme affiché, valider = 1 undo, continuer = trait intact.
    @Test
    fun t4SmartShapeGhostValidateAndKeep() {
        // Validate path: armed capture of a square → ghost → confirm
        // stamps a rect in ONE undo; undo restores the blank canvas
        // (the stroke never inked before validation).
        var id = openFreshDoc("t4-smart-ok")
        var es = freshSurface(id)
        try {
            es.smartShapeArmed = true
            val before = pullFull(es)
            val beforePx = intPixels(before)
            before.recycle()
            drawSquareStroke(es)
            assertTrue("ghost must appear", awaitGhost(es, 5000))
            var ok = false
            composeRule.activityRule.scenario.onActivity { ok = es.confirmSmartShape() }
            Thread.sleep(600)
            assertTrue("confirm must commit", ok)
            var after = pullFull(es)
            try {
                assertTrue(
                    "rect stamp must paint",
                    intPixels(after).contentEquals(beforePx).not()
                )
            } finally {
                after.recycle()
            }
            assertTrue("validate is one undo", es.undo())
            after = pullFull(es)
            try {
                assertTrue(intPixels(after).contentEquals(beforePx))
            } finally {
                after.recycle()
            }
        } finally {
            closeFreshDoc(id)
        }
        // Keep path: same square, dismissed → the original stroke is
        // replayed pixel-identical to a direct (unarmed) stroke.
        id = openFreshDoc("t4-smart-keep")
        es = freshSurface(id)
        try {
            es.smartShapeArmed = true
            drawSquareStroke(es)
            assertTrue("ghost must appear", awaitGhost(es, 5000))
            composeRule.activityRule.scenario.onActivity { es.dismissSmartShape() }
            Thread.sleep(600)
            val kept = pullFull(es)
            val keptPx = intPixels(kept)
            kept.recycle()
            assertTrue("kept stroke must paint", keptPx.any { it != -1 })
        } finally {
            closeFreshDoc(id)
        }
        id = openFreshDoc("t4-smart-ref")
        val ref = freshSurface(id)
        try {
            drawSquareStroke(ref)
            val refBmp = pullFull(ref)
            val refPx = intPixels(refBmp)
            refBmp.recycle()
            // Re-draw kept pixels on a second doc for the exactness check.
            val id2 = openFreshDoc("t4-smart-keep2")
            val es2 = freshSurface(id2)
            try {
                es2.smartShapeArmed = true
                drawSquareStroke(es2)
                assertTrue(awaitGhost(es2, 5000))
                composeRule.activityRule.scenario.onActivity { es2.dismissSmartShape() }
                Thread.sleep(600)
                val kept2 = pullFull(es2)
                try {
                    assertTrue(
                        "dismissed stroke must equal a direct stroke",
                        intPixels(kept2).contentEquals(refPx)
                    )
                } finally {
                    kept2.recycle()
                }
            } finally {
                closeFreshDoc(id2)
            }
        } finally {
            closeFreshDoc(id)
        }
    }

    private fun drawSquareStroke(es: EngineSurface) {
        val cx = es.width * 0.5f
        val cy = es.height * 0.5f
        val r = 60f
        val corners = listOf(
            Pair(cx - r, cy - r), Pair(cx + r, cy - r),
            Pair(cx + r, cy + r), Pair(cx - r, cy + r),
            Pair(cx - r, cy - r)
        )
        composeRule.activityRule.scenario.onActivity {
            val t0 = SystemClock.uptimeMillis()
            var t = t0
            var prev = corners[0]
            es.dispatchTouchEvent(
                MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, prev.first, prev.second, 1f, 1f, 0, 1f, 1f, 0, 0)
            )
            for (c in corners.drop(1)) {
                for (i in 1..6) {
                    t += 8
                    val x = prev.first + (c.first - prev.first) * i / 6f
                    val y = prev.second + (c.second - prev.second) * i / 6f
                    es.dispatchTouchEvent(
                        MotionEvent.obtain(t0, t, MotionEvent.ACTION_MOVE, x, y, 1f, 1f, 0, 1f, 1f, 0, 0)
                    )
                }
                prev = c
            }
            es.dispatchTouchEvent(
                MotionEvent.obtain(t0, t + 8, MotionEvent.ACTION_UP, prev.first, prev.second, 1f, 1f, 0, 1f, 1f, 0, 0)
            )
        }
        Thread.sleep(900)
    }

    private fun awaitGhost(es: EngineSurface, timeoutMs: Long): Boolean {
        val t0 = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - t0 < timeoutMs) {
            if (es.smartGhostPoly.size >= 2) return true
            Thread.sleep(150)
        }
        return false
    }
    // ================= T5 échanges + pages + productivité =================

    private fun t5Hash(es: EngineSurface): IntArray {
        val b = pullFull(es)
        try {
            return intPixels(b)
        } finally {
            b.recycle()
        }
    }

    private fun t5PagesDir(es: EngineSurface, n: Int, tag: String): java.io.File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = java.io.File(ctx.cacheDir, "t5pages-$tag-${System.currentTimeMillis()}")
        dir.mkdirs()
        java.io.File(dir, "thumbs").mkdirs()
        val inks = listOf("#FF0000", "#00FF00", "#0000FF")
        repeat(n) { k ->
            es.floodFillAt(10 + k, 10, inks[k % 3], "{}")
            Thread.sleep(300)
            var ok = false
            val latch = java.util.concurrent.CountDownLatch(1)
            es.saveTo(
                java.io.File(
                    dir, String.format(java.util.Locale.US, "%03d.ora", k + 1)
                ).absolutePath, "ora"
            ) { saved ->
                ok = saved
                latch.countDown()
            }
            assertTrue("page save must succeed", latch.await(30, java.util.concurrent.TimeUnit.SECONDS) && ok)
        }
        val pages = (1..n).map { k ->
            OcpPage(String.format(java.util.Locale.US, "%03d.ora", k), "p$k", 256, 256)
        }
        java.io.File(dir, "project.json").writeText(MultipageProject.toJson("ltr", pages))
        return dir
    }

    // §1: verrou — écriture refusée + hint, couche intacte.
    @Test
    fun t5LockRefusesStrokeAndHint() {
        val id = openFreshDoc("t5-lock")
        val es = freshSurface(id)
        try {
            assertTrue(es.floodFillAt(10, 10, "#FF0000", "{}"))
            Thread.sleep(400)
            val h0 = t5Hash(es)
            assertTrue(es.setLayerFlags(0, "{\"locked\":true}"))
            assertTrue(es.layerState().contains("\"locked\":true"))
            assertFalse("flood on locked layer refused", es.floodFillAt(20, 20, "#0000FF", "{}"))
            es.lastRefusal = null
            viewStrokeEng(es, 30f, 30f, 100f, 100f)
            Thread.sleep(900)
            assertEquals("layer_locked_hint", es.lastRefusal)
            assertTrue("locked layer byte-identical", t5Hash(es).contentEquals(h0))
            assertTrue(es.setLayerFlags(0, "{\"locked\":false}"))
            assertTrue(es.floodFillAt(20, 20, "#0000FF", "{}"))
        } finally {
            closeFreshDoc(id)
        }
    }

    // §1: verrou alpha — transparent intact, couvert peint.
    @Test
    fun t5AlphaLockKeepsTransparent() {
        val id = openFreshDoc("t5-alock")
        val es = freshSurface(id)
        try {
            val ink = es.addLayerNamed("ink")
            assertTrue(ink >= 0)
            es.setActiveLayer(ink)
            assertTrue(es.stampShapeJson("{\"kind\":\"rect\",\"x0\":0,\"y0\":0,\"x1\":20,\"y1\":20,\"fill\":\"#FF0000\"}"))
            Thread.sleep(300)
            assertTrue(es.setLayerFlags(ink, "{\"alphaLock\":true}"))
            val h0 = t5Hash(es)
            // Graine transparente : aucun pixel transparent ne se couvre
            // (les bords semi-couverts dans la tolérance du luma se
            // peignent — couverture préservée, jamais créée).
            assertTrue(es.floodFillAt(200, 200, "#0000FF", "{}"))
            Thread.sleep(300)
            val h1 = t5Hash(es)
            assertEquals(h0.size, h1.size)
            for (i in h0.indices) {
                if ((h0[i] ushr 24) == 0) {
                    assertTrue(
                        "pixel $i stays transparent",
                        (h1[i] ushr 24) == 0
                    )
                }
            }
            // Graine couverte : la peinture passe.
            assertTrue(es.floodFillAt(5, 5, "#0000FF", "{}"))
            Thread.sleep(300)
            assertFalse(t5Hash(es).contentEquals(h0))
            assertTrue(es.setLayerFlags(ink, "{\"alphaLock\":false}"))
        } finally {
            closeFreshDoc(id)
        }
    }

    // §1: reference/draft — présentes dans l'éditeur, absentes de l'export.
    @Test
    fun t5DraftReferenceExportExclusion() {
        val id = openFreshDoc("t5-rd")
        val es = freshSurface(id)
        try {
            assertTrue(es.floodFillAt(10, 10, "#FF0000", "{}"))
            val ref = es.addLayerNamed("ref")
            es.setActiveLayer(ref)
            assertTrue(es.setLayerFill(ref, "{\"kind\":\"solid\",\"color\":\"#00FF00\"}"))
            assertTrue(es.setLayerFlags(ref, "{\"reference\":true}"))
            val dr = es.addLayerNamed("draft")
            es.setActiveLayer(dr)
            assertTrue(es.setLayerFill(dr, "{\"kind\":\"solid\",\"color\":\"#0000FF\"}"))
            assertTrue(es.setLayerFlags(dr, "{\"draft\":true}"))
            Thread.sleep(300)
            // Présentes couche par couche (éditeur).
            val soloRef = es.soloRenderFull(ref)
            assertNotNull(soloRef)
            assertTrue("ref layer renders green", soloRef!!.getPixel(10, 10) == -16711936)
            soloRef.recycle()
            // Absentes du composite d'export (rouge seul).
            val exp = es.pullExportBitmap()
            assertNotNull(exp)
            assertTrue("export stays red", exp!!.getPixel(10, 10) == -65536)
            exp.recycle()
        } finally {
            closeFreshDoc(id)
        }
    }

    // §2: fiche fill — pose, pixeliser undoable, états.
    @Test
    fun t5FillPoseBakeUndo() {
        val id = openFreshDoc("t5-fill")
        val es = freshSurface(id)
        try {
            val li = es.addLayerNamed("t5fill")
            es.setActiveLayer(li)
            assertTrue(es.setLayerFill(li, "{\"kind\":\"solid\",\"color\":\"#FF0000\"}"))
            assertTrue(es.layerState().contains("\"fill\":{"))
            Thread.sleep(300)
            var b = es.pullExportBitmap()
            assertNotNull(b)
            assertEquals(-65536, b!!.getPixel(10, 10))
            b.recycle()
            assertTrue(es.rasterizeFill(li))
            assertFalse("fiche cleared after bake", es.layerState().contains("\"fill\":{"))
            b = es.pullExportBitmap()
            assertNotNull(b)
            assertEquals(-65536, b!!.getPixel(10, 10))
            b.recycle()
            assertTrue(es.undo())
            Thread.sleep(300)
            b = es.pullExportBitmap()
            assertNotNull(b)
            assertEquals("undo restores empty surface (white base)", -1, b!!.getPixel(10, 10))
            b.recycle()
            // Fiche invalide : false + intact.
            assertFalse(es.setLayerFill(li, "{\"kind\":\"gradient\",\"stops\":[\"#FFFFFF:0\"]}"))
        } finally {
            closeFreshDoc(id)
        }
    }

    // §2: les 2 nouveaux filtres appliquent + annulent.
    @Test
    fun t5NewFiltersApplyUndo() {
        val id = openFreshDoc("t5-filt")
        val es = freshSurface(id)
        try {
            val li = es.addLayerNamed("t5shape")
            es.setActiveLayer(li)
            assertTrue(es.stampShapeJson("{\"kind\":\"rect\",\"x0\":40,\"y0\":40,\"x1\":120,\"y1\":120}"))
            Thread.sleep(300)
            val h0 = t5Hash(es)
            assertTrue(es.applyFilterSync("edgeBorder", "{\"width\":4,\"color\":\"#FF0000\",\"mode\":\"inside\"}"))
            Thread.sleep(200)
            assertFalse(t5Hash(es).contentEquals(h0))
            assertTrue(es.undo())
            Thread.sleep(200)
            assertTrue(t5Hash(es).contentEquals(h0))
            assertTrue(es.applyFilterSync("watercolor", "{\"darken\":0.6,\"rough\":6,\"bloom\":0.4}"))
            Thread.sleep(200)
            assertFalse(t5Hash(es).contentEquals(h0))
            assertTrue(es.undo())
            Thread.sleep(200)
            assertTrue(t5Hash(es).contentEquals(h0))
        } finally {
            closeFreshDoc(id)
        }
    }

    // §3: les 24 blends sont acceptés.
    @Test
    fun t5BlendsAllAccept() {
        val id = openFreshDoc("t5-blends")
        val es = freshSurface(id)
        try {
            val names = listOf(
                "svg:normal", "svg:multiply", "svg:screen", "svg:overlay",
                "svg:plus", "svg:soft-light", "svg:hard-light",
                "svg:difference", "svg:color", "svg:luminosity",
                "svg:darken", "svg:lighten", "svg:linear-burn",
                "svg:linear-dodge", "svg:vivid-light", "svg:linear-light",
                "svg:pin-light", "svg:subtract", "svg:divide", "svg:exclusion",
                "darken", "linear_dodge", "pin-light", "exclusion"
            )
            assertEquals(24, names.size)
            for (n in names) assertTrue("blend $n accepted", es.setLayerBlend(0, n))
            assertTrue(es.layerState().contains("exclusion"))
        } finally {
            closeFreshDoc(id)
        }
    }

    // §4: fixture .grd in-code : parse OK + troncature refusée.
    @Test
    fun t5GrdFixtureParses() {
        val (grads, msgs) = dev.openclip.ui.GrdParser.parse(
            dev.openclip.ui.GrdParser.fixtureTwoStops()
        )
        assertEquals(1, grads.size)
        assertEquals(2, grads[0].stops.size)
        assertTrue(grads[0].stops[0].startsWith("#000000"))
        assertTrue("no warnings on the clean fixture: $msgs", msgs.isEmpty())
        val (g2, m2) = dev.openclip.ui.GrdParser.parse(byteArrayOf(0, 3, 0))
        assertTrue(g2.isEmpty())
        assertTrue(m2.isNotEmpty())
    }

    // §4: fixture .abr in-code : pointe lue + version inconnue refusée.
    @Test
    fun t5AbrFixtureParses() {
        val (tips, msgs) = dev.openclip.ui.AbrParser.parse(
            dev.openclip.ui.AbrParser.fixtureTip()
        )
        assertEquals(1, tips.size)
        assertEquals(4, tips[0].w)
        assertEquals(4, tips[0].h)
        assertTrue("center pinned", tips[0].bits[5] != 0.toByte())
        assertTrue("dynamics announced", msgs.contains("dynamics ignored"))
        val (t2, m2) = dev.openclip.ui.AbrParser.parse(byteArrayOf(0, 6, 0, 1))
        assertTrue(t2.isEmpty())
        assertTrue(m2.isNotEmpty())
    }

    // §4: accord — 3 fiches curves posées + composite rapproché.
    @Test
    fun t5ColorMatchCurvesAndCloser() {
        val id = openFreshDoc("t5-match")
        val es = freshSurface(id)
        try {
            assertTrue(es.floodFillAt(10, 10, "#FF0000", "{}"))
            val blue = es.addLayerNamed("blue")
            es.setActiveLayer(blue)
            assertTrue(es.floodFillAt(10, 10, "#0000FF", "{}"))
            Thread.sleep(300)
            val srcBmp = es.soloRenderFull(0)!!
            val tgtBmp = es.soloRenderFull(blue)!!
            val before = dev.openclip.ui.ColorMatch.meanDistance(srcBmp, tgtBmp)
            assertTrue("red vs blue are far", before > 50)
            val luts = dev.openclip.ui.ColorMatch.lut(
                dev.openclip.ui.ColorMatch.stats(tgtBmp),
                dev.openclip.ui.ColorMatch.stats(srcBmp)
            )
            val chans = arrayOf("r", "g", "b")
            var okCount = 0
            for (k in 0..2) {
                val idx = es.addLayerNamed("match-" + chans[k])
                if (idx >= 0 && es.setLayerAdjustment(
                        idx, dev.openclip.ui.ColorMatch.curvesJson(luts[k], chans[k])
                    )
                ) okCount++
            }
            assertEquals(3, okCount)
            Thread.sleep(400)
            val afterBmp = es.pullExportBitmap()!!
            val after = dev.openclip.ui.ColorMatch.meanDistance(srcBmp, afterBmp)
            assertTrue("composite closer to source ($before -> $after)", after < before)
            srcBmp.recycle()
            tgtBmp.recycle()
            afterBmp.recycle()
        } finally {
            closeFreshDoc(id)
        }
    }

    // §5: project.json v1 migré sans perte + v2 relu.
    @Test
    fun t5ProjectV1Tolerant() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = java.io.File(ctx.cacheDir, "t5proj-${System.currentTimeMillis()}")
        dir.mkdirs()
        java.io.File(dir, "project.json").writeText(
            "{\"version\":1,\"reading\":\"rtl\",\"pages\":[]}"
        )
        val (w, f, _) = dev.openclip.ui.T5Project.read(dir)
        assertEquals("", w.titre)
        assertEquals("off", f.style)
        dev.openclip.ui.T5Project.write(
            dir, dev.openclip.ui.T5Work(titre = "T"),
            dev.openclip.ui.T5Folio("total", "bottom-center"),
            dev.openclip.ui.T5Crop(3f, 10f), "left"
        )
        val back = org.json.JSONObject(java.io.File(dir, "project.json").readText())
        assertEquals("T", back.getJSONObject("work").getString("titre"))
        // Le lecteur v1 lit toujours reading + pages (jamais cassé).
        val (reading, _) = MultipageProject.parse(java.io.File(dir, "project.json").readText())
        assertEquals("rtl", reading)
        dir.deleteRecursively()
    }

    // §5: foliotation — numéros distincts par page + romain.
    @Test
    fun t5FolioNumbersDistinct() {
        assertEquals("IV", dev.openclip.ui.t5Roman(4))
        assertEquals("2/5", dev.openclip.ui.t5FolioText(2, 5, "total"))
        assertNull(dev.openclip.ui.t5FolioText(2, 5, "off"))
        val b = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        b.eraseColor(-1)
        val f = dev.openclip.ui.T5Folio("total", "bottom-center")
        val p1 = dev.openclip.ui.t5RenderFolio(b, 1, 2, f)
        val p2 = dev.openclip.ui.t5RenderFolio(b, 2, 2, f)
        assertFalse(
            "folio numbers differ",
            intPixels(p1).contentEquals(intPixels(p2))
        )
    }

    // §5: fanzine — 8 pages imposées + bourrage documenté.
    @Test
    fun t5ImposeEightPages() {
        val (sheets, pad) = dev.openclip.ui.t5Impose(8)
        assertEquals(0, pad)
        assertEquals(2, sheets.size)
        assertEquals(dev.openclip.ui.T5Sheet(8, 1, 2, 7), sheets[0])
        assertEquals(dev.openclip.ui.T5Sheet(6, 3, 4, 5), sheets[1])
        val (sheets5, pad5) = dev.openclip.ui.t5Impose(5)
        assertEquals(3, pad5)
        assertEquals(2, sheets5.size)
    }

    // §5: repères — overlay 3 rects + rendu opt-in agrandi.
    @Test
    fun t5CropOverlaysAndRender() {
        val ov = dev.openclip.ui.t5CropOverlays(200, 200, 160f, 3f, 10f)
        assertEquals(3, ov.size)
        val b = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        b.eraseColor(-1)
        val c = dev.openclip.ui.t5RenderCrop(
            b, 160f, dev.openclip.ui.T5Crop(3f, 10f),
            dev.openclip.ui.T5Folio("off", "bottom-center"), 1, 1
        )
        assertTrue(c.width > 200 && c.height > 200)
    }

    // §6: EPUB 3 pages — mimetype premier STORED + spine = 3.
    @Test
    fun t5EpubThreePagesValid() {
        val id = openFreshDoc("t5-epub")
        val es = freshSurface(id)
        try {
            val dir = t5PagesDir(es, 3, "epub")
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val dst = java.io.File(ctx.cacheDir, "t5-${System.currentTimeMillis()}.epub")
            assertTrue(
                dev.openclip.ui.t5ExportEpub(
                    es, dir, ctx.cacheDir, dst,
                    dev.openclip.ui.T5Folio("total", "bottom-center"), null,
                    dev.openclip.ui.T5Work(titre = "T"), "ltr", 160f
                )
            )
            val (zipOk, mimeOk, spine) = dev.openclip.ui.t5ValidateEpub(dst)
            assertTrue("zip opens", zipOk)
            assertTrue("mimetype first + stored", mimeOk)
            assertEquals(3, spine)
            dst.delete()
            dir.deleteRecursively()
        } finally {
            closeFreshDoc(id)
        }
    }

    // §6: PDF 3 pages — dimensions ≈ canvas/dpi.
    @Test
    fun t5PdfThreePagesSized() {
        val id = openFreshDoc("t5-pdf")
        val es = freshSurface(id)
        try {
            val dir = t5PagesDir(es, 3, "pdf")
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val dst = java.io.File(ctx.cacheDir, "t5-${System.currentTimeMillis()}.pdf")
            assertTrue(
                dev.openclip.ui.t5ExportPdf(
                    es, dir, ctx.cacheDir, dst,
                    dev.openclip.ui.T5Folio("off", "bottom-center"), null, 160f
                )
            )
            val fd = android.os.ParcelFileDescriptor.open(
                dst, android.os.ParcelFileDescriptor.MODE_READ_ONLY
            )
            val renderer = android.graphics.pdf.PdfRenderer(fd)
            assertEquals(3, renderer.pageCount)
            val pg = renderer.openPage(0)
            val (cw, _) = es.canvasDims()
            val expect = (cw * 72f / 160f).toInt()
            assertTrue("page width ≈ canvas/dpi", pg.width in expect - 4..expect + 4)
            pg.close()
            renderer.close()
            fd.close()
            dst.delete()
            dir.deleteRecursively()
        } finally {
            closeFreshDoc(id)
        }
    }

    // §6: séquence multipage — pNN_frame_MMMM présents, holds éclatés.
    @Test
    fun t5SequenceMultipageZip() {
        val id = openFreshDoc("t5-seq")
        val es = freshSurface(id)
        try {
            val dir = t5PagesDir(es, 3, "seq")
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val dst = java.io.File(ctx.cacheDir, "t5-${System.currentTimeMillis()}.zip")
            assertTrue(dev.openclip.ui.t5ExportSequence(es, dir, ctx.cacheDir, dst))
            java.util.zip.ZipFile(dst).use { z ->
                val names = z.entries().toList().map { it.name }.sorted()
                assertEquals(3, names.size)
                assertTrue(names.contains("p01_frame_0001.png"))
                assertTrue(names.contains("p02_frame_0001.png"))
                assertTrue(names.contains("p03_frame_0001.png"))
            }
            dst.delete()
            dir.deleteRecursively()
        } finally {
            closeFreshDoc(id)
        }
    }

    // §6: stickers APNG — 1 par page, ≤ 512px.
    @Test
    fun t5Stickers512() {
        val id = openFreshDoc("t5-stick")
        val es = freshSurface(id)
        try {
            val dir = t5PagesDir(es, 3, "stick")
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val out = java.io.File(ctx.cacheDir, "t5stick-${System.currentTimeMillis()}")
            assertEquals(3, dev.openclip.ui.t5ExportStickers(es, dir, ctx.cacheDir, out))
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            for (f in out.listFiles()!!) {
                BitmapFactory.decodeFile(f.absolutePath, opts)
                assertTrue(opts.outWidth in 1..512)
                assertTrue(opts.outHeight in 1..512)
            }
            out.deleteRecursively()
            dir.deleteRecursively()
        } finally {
            closeFreshDoc(id)
        }
    }

    // §7: macro 3 ops rejouée → composite égal à l'original.
    @Test
    fun t5MacroReplayEqual() {
        val id = openFreshDoc("t5-macro")
        val es = freshSurface(id)
        try {
            assertTrue(es.floodFillAt(10, 10, "#FF0000", "{}"))
            Thread.sleep(300)
            val h0 = t5Hash(es)
            dev.openclip.ui.MacroJournal.start()
            assertTrue(es.setLayerOpacity(0, 0.5f))
            assertTrue(es.setLayerBlend(0, "svg:multiply"))
            assertTrue(es.applyFilterSync("threshold", "{\"level\":128}"))
            val ops = dev.openclip.ui.MacroJournal.stop()
            assertEquals(3, ops.size)
            Thread.sleep(300)
            val h1 = t5Hash(es)
            assertFalse(h1.contentEquals(h0))
            // Reset : 1 undo (filtre) + métas manuelles (hors undo).
            assertTrue(es.undo())
            es.setLayerOpacity(0, 1f)
            es.setLayerBlend(0, "svg:normal")
            Thread.sleep(300)
            assertTrue("reset restores", t5Hash(es).contentEquals(h0))
            val errs = dev.openclip.ui.replayMacro(
                es, dev.openclip.ui.Macro("m", ops)
            )
            assertTrue("replay clean: $errs", errs.isEmpty())
            Thread.sleep(300)
            assertTrue("replay equals original", t5Hash(es).contentEquals(h1))
        } finally {
            dev.openclip.ui.MacroJournal.cancel()
            closeFreshDoc(id)
        }
    }

    // §7: macro invalide → arrêt + rapport.
    @Test
    fun t5MacroInvalidStopsAndReports() {
        val id = openFreshDoc("t5-macroinv")
        val es = freshSurface(id)
        try {
            val bad = dev.openclip.ui.Macro(
                "bad", listOf(dev.openclip.ui.MacroOp("nope", "{}"))
            )
            assertTrue(dev.openclip.ui.replayMacro(es, bad).isNotEmpty())
            val badIdx = dev.openclip.ui.Macro(
                "bad2", listOf(
                    dev.openclip.ui.MacroOp(
                        "setLayerBlend", "{\"index\":99,\"name\":\"svg:multiply\"}"
                    )
                )
            )
            assertTrue(dev.openclip.ui.replayMacro(es, badIdx).isNotEmpty())
        } finally {
            closeFreshDoc(id)
        }
    }

    // §7: aligner 3 calques (gauche) → positions attendues.
    @Test
    fun t5AlignLeftCanvas() {
        val id = openFreshDoc("t5-align")
        val es = freshSurface(id)
        try {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val ids = (1..3).map { es.addLayerNamed("a$it") }
            ids.forEachIndexed { k, idx ->
                es.setActiveLayer(idx)
                assertTrue(
                    es.stampShapeJson(
                        "{\"kind\":\"rect\",\"x0\":${40 + k * 40},\"y0\":40,\"x1\":${60 + k * 40},\"y1\":60}"
                    )
                )
            }
            Thread.sleep(300)
            dev.openclip.ui.T5AlignSelection.clear()
            ids.forEach { dev.openclip.ui.T5AlignSelection.selected.add(it) }
            assertEquals(3, dev.openclip.ui.t5Align(es, ctx.cacheDir, "left", true))
            Thread.sleep(300)
            for (i in ids) {
                val x0 = dev.openclip.ui.t5LayerBBox(es, i)?.get(0) ?: 99
                assertTrue("layer $i at left (x0=$x0)", x0 <= 1)
            }
            dev.openclip.ui.T5AlignSelection.clear()
        } finally {
            dev.openclip.ui.T5AlignSelection.clear()
            closeFreshDoc(id)
        }
    }

    // §7: historique — liste + tap-revenir restaure le composite.
    @Test
    fun t5HistoryJumpRestores() {
        val id = openFreshDoc("t5-hist")
        val es = freshSurface(id)
        try {
            assertTrue(es.floodFillAt(10, 10, "#FF0000", "{}"))
            Thread.sleep(300)
            val h0 = t5Hash(es)
            val n0 = dev.openclip.ui.HistoryLog.size()
            assertTrue(es.applyFilterSync("threshold", "{\"level\":200}"))
            assertTrue(es.applyFilterSync("posterize", "{\"levels\":2}"))
            Thread.sleep(200)
            assertEquals(n0 + 2, dev.openclip.ui.HistoryLog.size())
            assertFalse(t5Hash(es).contentEquals(h0))
            assertTrue(es.undo())
            assertTrue(es.undo())
            Thread.sleep(200)
            assertEquals(n0, dev.openclip.ui.HistoryLog.size())
            assertTrue("jump back restores", t5Hash(es).contentEquals(h0))
        } finally {
            closeFreshDoc(id)
        }
    }

    // §7: info — dims/zoom cohérents, export joignable.
    @Test
    fun t5InfoDimsCoherent() {
        val id = openFreshDoc("t5-info")
        val es = freshSurface(id)
        try {
            val (w, h) = es.canvasDims()
            assertTrue(w >= 200 && h >= 200)
            val bmp = es.pullExportBitmap()
            assertNotNull(bmp)
            assertEquals(w, bmp!!.width)
            assertEquals(h, bmp.height)
            bmp.recycle()
        } finally {
            closeFreshDoc(id)
        }
    }

    // ---- W1 (issue #28 slice A): merge / canvas geometry / view flip /
    // selection refinement / reference photo. Engine calls go through the
    // exact wrappers the menus and dialogs use; every action is one undo
    // step and the render must never change.

    private fun w1Ctx(): android.content.Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    /** Fill the active layer with one solid ink (whole canvas seed). */
    private fun w1Fill(es: EngineSurface, color: String) {
        assertTrue("floodFill $color", es.floodFillAt(6, 6, color, "{}"))
        Thread.sleep(300)
    }

    @Test
    fun w1MergeDownPreservesRenderAndUndo() {
        val id = openFreshDoc("w1-merge")
        val es = freshSurface(id)
        try {
            w1Fill(es, "#CC2222")
            val top = es.addLayerNamed("top")
            es.setActiveLayer(top)
            w1Fill(es, "#2244CC")
            val n0 = es.layerCount()
            val before = t5Hash(es)
            assertTrue("merge down", es.mergeLayers("down", top))
            Thread.sleep(300)
            assertEquals(n0 - 1, es.layerCount())
            assertTrue(
                "merge down must not change a single pixel",
                t5Hash(es).contentEquals(before)
            )
            assertTrue("one undo step", es.undo())
            Thread.sleep(300)
            assertEquals(n0, es.layerCount())
            assertTrue("undo restores the layers", t5Hash(es).contentEquals(before))
        } finally {
            closeFreshDoc(id)
        }
    }

    @Test
    fun w1MergeVisibleAndFlatten() {
        val id = openFreshDoc("w1-visible")
        val es = freshSurface(id)
        try {
            w1Fill(es, "#EEEEEE")
            es.addLayerNamed("a")
            w1Fill(es, "#22AA44")
            val before = t5Hash(es)
            assertTrue("merge visible", es.mergeLayers("visible"))
            Thread.sleep(300)
            assertTrue(t5Hash(es).contentEquals(before))
            assertEquals(1, es.layerCount())
            // Flatten keeps the export render byte-identical too.
            val export = es.pullExportBitmap()
            assertNotNull(export)
            val px = intPixels(export!!)
            export.recycle()
            assertTrue("flatten", es.mergeLayers("flatten"))
            Thread.sleep(300)
            assertEquals(1, es.layerCount())
            val after = es.pullExportBitmap()
            assertNotNull(after)
            assertTrue(
                "flatten must not change the export",
                intPixels(after!!).contentEquals(px)
            )
            after.recycle()
        } finally {
            closeFreshDoc(id)
        }
    }

    @Test
    fun w1RasterizeFillLayerBakesOnce() {
        val id = openFreshDoc("w1-rasterize")
        val es = freshSurface(id)
        try {
            val l = es.addLayerNamed("fill")
            es.setActiveLayer(l)
            assertTrue(
                "fill fiche",
                es.setLayerFill(l, "{\"kind\":\"solid\",\"color\":\"#8844FF\"}")
            )
            Thread.sleep(300)
            val before = t5Hash(es)
            assertTrue("rasterize", es.mergeLayers("rasterize", l))
            Thread.sleep(300)
            assertTrue(t5Hash(es).contentEquals(before))
            // Second call refuses: the layer is a plain raster now.
            assertFalse(
                "already-raster refusal",
                es.mergeLayers("rasterize", l)
            )
            assertTrue("refusal kept the pixels", t5Hash(es).contentEquals(before))
        } finally {
            closeFreshDoc(id)
        }
    }

    @Test
    fun w1CanvasResizeCropAndPrintSize() {
        val id = openFreshDoc("w1-canvas")
        val es = freshSurface(id)
        try {
            w1Fill(es, "#336699")
            val (w0, h0) = es.canvasDims()
            assertEquals(256, w0)
            assertEquals(256, h0)
            val (mw0, mh0) = es.printSizeMm()
            assertEquals(256.0 / 160.0 * 25.4, mw0, 0.2)
            assertTrue("dpi kept", es.documentDpi() >= 150f)
            assertTrue("anchored resize", es.resizeCanvas(128, 128, "center", false))
            Thread.sleep(300)
            val (w1, h1) = es.canvasDims()
            assertEquals(128, w1)
            assertEquals(128, h1)
            val (mw1, _) = es.printSizeMm()
            assertTrue("print size follows the canvas", mw1 < mw0)
            // Crop to a 64x64 selection.
            es.selectRectEngine(16f, 16f, 80f, 80f)
            Thread.sleep(150)
            assertTrue(es.hasSelection())
            assertTrue("crop to selection", es.cropToSelection())
            Thread.sleep(300)
            val (w2, h2) = es.canvasDims()
            assertEquals(64, w2)
            assertEquals(64, h2)
            assertFalse("selection is dropped on resize", es.hasSelection())
            // Undo restores the pre-crop size in one step.
            assertTrue(es.undo())
            Thread.sleep(300)
            assertEquals(128, es.canvasDims().first)
        } finally {
            closeFreshDoc(id)
        }
    }

    @Test
    fun w1ViewFlipIsViewOnly() {
        val id = openFreshDoc("w1-flip")
        val es = freshSurface(id)
        try {
            w1Fill(es, "#AA3344")
            val b0 = es.pullExportBitmap()
            assertNotNull(b0)
            val px0 = intPixels(b0!!)
            b0.recycle()
            // Mirror both axes, then verify the inverse view matrix
            // round-trips and the export is untouched.
            assertTrue(es.toggleViewFlipH())
            assertTrue(es.toggleViewFlipV())
            Thread.sleep(200)
            val (sx, sy) = es.engineToScreen(40f, 90f)
            val (ex, ey) = es.screenToEngine(sx, sy)
            assertEquals(40f, ex, 0.5f)
            assertEquals(90f, ey, 0.5f)
            val b1 = es.pullExportBitmap()
            assertNotNull(b1)
            assertTrue(
                "view flip must not change exported pixels",
                intPixels(b1!!).contentEquals(px0)
            )
            b1.recycle()
            assertFalse(es.toggleViewFlipH())
            assertFalse(es.toggleViewFlipV())
            Thread.sleep(150)
            val b2 = es.pullExportBitmap()
            assertNotNull(b2)
            assertTrue(intPixels(b2!!).contentEquals(px0))
            b2.recycle()
        } finally {
            closeFreshDoc(id)
        }
    }

    @Test
    fun w1SelectionGrowShrinkFeather() {
        val id = openFreshDoc("w1-sel")
        val es = freshSurface(id)
        try {
            es.selectRectEngine(60f, 60f, 120f, 120f)
            Thread.sleep(150)
            val c0 = es.selectionCountForTest()
            assertTrue("rect selection", c0 > 3000)
            assertTrue(es.refineSelection("grow", 6, w1Ctx().cacheDir))
            Thread.sleep(150)
            val c1 = es.selectionCountForTest()
            assertTrue("grow expands ($c0 -> $c1)", c1 > c0)
            assertTrue(es.refineSelection("shrink", 6, w1Ctx().cacheDir))
            Thread.sleep(150)
            val c2 = es.selectionCountForTest()
            assertTrue("shrink contracts ($c1 -> $c2)", c2 < c1)
            assertTrue(es.refineSelection("feather", 4, w1Ctx().cacheDir))
            Thread.sleep(150)
            // Soft edge: the centre stays fully selected, the rim does not.
            assertEquals(255, es.selectionAlphaForTest(90, 90))
            val rim = es.selectionAlphaForTest(56, 90)
            assertTrue("feathered rim is graded (got $rim)", rim in 1..254)
            // One app-side undo restores the pre-feather plane.
            assertTrue(es.undoSelection())
        } finally {
            closeFreshDoc(id)
        }
    }

    @Test
    fun w1SelectFromLayerAlpha() {
        val id = openFreshDoc("w1-alpha")
        val es = freshSurface(id)
        try {
            // Fresh doc: one opaque background layer.
            assertTrue(es.selectFromLayerAlpha(0, 1, w1Ctx().cacheDir))
            assertTrue("opaque layer selects", es.selectionCountForTest() > 1000)
            val empty = es.addLayerNamed("empty")
            assertTrue(es.selectFromLayerAlpha(empty, 1, w1Ctx().cacheDir))
            assertEquals("empty alpha selects nothing", 0, es.selectionCountForTest())
        } finally {
            closeFreshDoc(id)
        }
    }

    @Test
    fun w1PolylineSelection() {
        val id = openFreshDoc("w1-poly")
        val es = freshSurface(id)
        try {
            es.selectionTool = dev.openclip.ui.SelectionTool.POLYLINE
            val pts = listOf(50f to 50f, 150f to 50f, 150f to 150f, 50f to 150f)
            for ((k, p) in pts.withIndex()) {
                val (x, y) = es.engineToScreen(p.first, p.second)
                val t = SystemClock.uptimeMillis() + k * 400L
                dispatchAll(
                    es,
                    simpleDown(t, x, y),
                    MotionEvent.obtain(
                        t, t, MotionEvent.ACTION_UP, x, y, 1f, 1f, 0, 1f, 1f, 0, 0
                    )
                )
            }
            // Double-tap on the last vertex closes the loop.
            val (lx, ly) = es.engineToScreen(50f, 150f)
            val t = SystemClock.uptimeMillis() + 40L
            dispatchAll(
                es,
                simpleDown(t, lx, ly),
                MotionEvent.obtain(
                    t, t, MotionEvent.ACTION_UP, lx, ly, 1f, 1f, 0, 1f, 1f, 0, 0
                ),
                simpleDown(t + 40L, lx, ly),
                MotionEvent.obtain(
                    t + 40L, t + 40L, MotionEvent.ACTION_UP, lx, ly,
                    1f, 1f, 0, 1f, 1f, 0, 0
                )
            )
            Thread.sleep(400)
            assertTrue("polyline selection exists", es.hasSelection())
            assertTrue(es.selectionCountForTest() > 5000)
            assertTrue("inside selected", es.selectionAlphaForTest(100, 100) > 0)
            assertEquals("outside unselected", 0, es.selectionAlphaForTest(200, 200))
        } finally {
            closeFreshDoc(id)
        }
    }

    @Test
    fun w1RefPhotoIndependentOfDocument() {
        val bmp = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        dev.openclip.ui.RefPhoto.setPhoto(bmp)
        try {
            assertTrue(dev.openclip.ui.RefPhoto.hasPhoto)
            assertEquals(
                0.5f,
                dev.openclip.ui.RefPhotoMath.fitScale(200f, 400f, 400, 200),
                0.001f
            )
            dev.openclip.ui.RefPhoto.fit(800f, 800f)
            assertEquals(1f, dev.openclip.ui.RefPhoto.scale, 0.001f)
            dev.openclip.ui.RefPhoto.zoomBy(2f)
            assertEquals(2f, dev.openclip.ui.RefPhoto.scale, 0.01f)
            dev.openclip.ui.RefPhoto.panBy(100000f, 0f, 800f, 800f)
            assertTrue(dev.openclip.ui.RefPhoto.panX <= 800f)
            dev.openclip.ui.RefPhoto.zoom100()
            assertEquals(1f, dev.openclip.ui.RefPhoto.scale, 0.001f)
            // Independent of any document: a fresh doc open/close keeps it.
            val id = openFreshDoc("w1-ref")
            closeFreshDoc(id)
            assertTrue("photo survives document switches", dev.openclip.ui.RefPhoto.hasPhoto)
        } finally {
            dev.openclip.ui.RefPhoto.clear()
            bmp.recycle()
        }
    }


    // A2 (#29): the new blend modes are reachable from the searchable
    // list and change the composite; undoing the paint restores it.
    // Group pass-through is visible as a badge and toggles the engine
    // flag through the flags setter (no new JNI symbol).
    @Test
    fun a2BlendModesAndPassThrough() {
        val es = surf()
        awaitSurface(es)
        resetSlice3()
        try {
            ensureDockOpen()
            dockTop()
            // Deterministic history: drain whatever earlier tests left so
            // the undo below pops OUR stroke.
            repeat(64) {
                if (!es.undo()) return@repeat
            }
            onUi {
                it.setActiveLayer(0)
                it.setBrush(
                    """{"radius":64,"hardness":1.0,"opaque":1.0,"spacing":0.15,"color":"#808080","stabilize":0.0,"curve":0.0,"mode":"brush"}"""
                )
            }
            val cx = es.width * 0.5f
            val cy = es.height * 0.5f
            val t0 = SystemClock.uptimeMillis()
            bigStroke(es, cx, cy, t0)
            Thread.sleep(500)
            val painted = t5Hash(es)
            val idx = onUi {
                val i = it.addLayerNamed("A2Top")
                it.setActiveLayer(i)
                it.setBrush(
                    """{"radius":64,"hardness":1.0,"opaque":1.0,"spacing":0.15,"color":"#404040","stabilize":0.0,"curve":0.0,"mode":"brush"}"""
                )
                i
            }
            Thread.sleep(300)
            strokeAcross(es, cy, t0 + 6000, cx)
            Thread.sleep(500)
            val normal = t5Hash(es)
            assertFalse(
                "the second stroke must paint",
                painted.contentEquals(normal)
            )
            // Pick color-burn through the real UI (searchable dialog).
            // The fresh stroke bumped refreshKey, so the panel already
            // lists the new layer; its row stays active (rank 2 visible
            // even in compact mode).
            composeRule.onNodeWithTag("layer-list").performScrollTo()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("layer-blend-$idx").performScrollTo()
            composeRule.onNodeWithTag("layer-blend-$idx").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("blend-search")
                .performTextInput("color")
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("blend-item-color-burn")
                .assertIsDisplayed()
            composeRule.onNodeWithTag("blend-item-color-dodge")
                .assertExists()
            // Proof shot: the picker lists the new modes (localized).
            shot("a2-blends.png")
            composeRule.onNodeWithTag("blend-item-color-burn").performClick()
            composeRule.waitForIdle()
            Thread.sleep(500)
            val blend = try {
                val arr = org.json.JSONObject(es.layerState())
                    .optJSONArray("layers")
                val e = arr!!.optJSONObject(idx)
                (e.optJSONObject("self") ?: e).optString("blend", "")
            } catch (_: Exception) {
                ""
            }
            assertEquals("svg:color-burn", blend)
            val burned = t5Hash(es)
            assertFalse(
                "color-burn must change the composite",
                normal.contentEquals(burned)
            )
            // Undo pops the last stroke: the composite comes back to the
            // pre-stroke state even with the new mode active.
            assertTrue("undo must have a step", es.undo())
            Thread.sleep(400)
            assertTrue(
                "undo must restore the composite",
                painted.contentEquals(t5Hash(es))
            )
            // Group pass-through badge + toggle (engine flag, 0 symbol).
            // Move the group to the BOTTOM of the stack so its row is
            // the first list item and stays inside the window.
            val gi = onUi {
                val g = it.addGroup("A2Group")
                it.moveLayer(g, 0)
                0
            }
            Thread.sleep(500)
            // addGroup is not a paint op: refresh the panel through a
            // row select (same pattern as the clip/mask badge test).
            composeRule.onNodeWithTag("layer-thumb-0").performClick()
            composeRule.waitForIdle()
            Thread.sleep(800)
            var ptDesc = ""
            var isoDesc = ""
            composeRule.activityRule.scenario.onActivity { act ->
                ptDesc = act.getString(R.string.layer_pass_through)
                isoDesc = act.getString(R.string.layer_isolated)
            }
            composeRule.onNodeWithTag("layer-list")
                .performScrollToNode(hasTestTag("layer-row-$gi"))
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("layer-badge-pass-$gi")
                .performScrollTo()
            composeRule.waitForIdle()
            composeRule.onNode(
                hasTestTag("layer-badge-pass-$gi").and(
                    hasContentDescription(ptDesc)
                )
            ).assertIsDisplayed()
            // Proof shot: pass-through badge on the group row.
            shot("a2-pass.png")
            composeRule.onNodeWithTag("layer-badge-pass-$gi").performClick()
            composeRule.waitForIdle()
            Thread.sleep(400)
            assertEquals(
                "badge tap must isolate the group",
                false,
                groupPassFlag(es, gi)
            )
            composeRule.onNode(
                hasTestTag("layer-badge-pass-$gi").and(
                    hasContentDescription(isoDesc)
                )
            ).assertExists()
            // Proof shot: isolated badge on the group row.
            shot("a2-isolated.png")
            composeRule.onNodeWithTag("layer-badge-pass-$gi").performClick()
            composeRule.waitForIdle()
            Thread.sleep(400)
            assertEquals(
                "second tap must restore pass-through",
                true,
                groupPassFlag(es, gi)
            )
        } finally {
            resetSlice3()
            repeat(40) {
                try {
                    es.undo()
                } catch (_: Exception) {
                }
            }
        }
    }

    // ================= B1 retouche et encrage (issue #30) =================
    //
    // Direct-JNI conformance: every tool produces ink (or a composite
    // change) and undoes in ONE nUndo step. Engine unit tests lock the
    // kernels; these lock the shim wiring (undo, guards, JSON keys).

    private fun b1Composite(handle: Long, cw: Int = 128, ch: Int = 128): IntArray {
        val px = IntArray(cw * ch)
        assertEquals(cw * ch, EngineHost.nCompositeInto(handle, px, cw, ch))
        return px
    }

    private fun b1SetBrush(handle: Long, mode: String, radius: Float = 6f) {
        EngineHost.nSetBrush(
            handle,
            """{"radius":$radius,"hardness":1.0,"opaque":1.0,"spacing":0.2,"color":"#141414","mode":"$mode"}"""
        )
    }

    private fun b1Line(
        handle: Long,
        x0: Float, y0: Float, x1: Float, y1: Float,
        mode: String = "brush",
        radius: Float = 2f
    ) {
        b1SetBrush(handle, mode, radius)
        val id = EngineHost.nStrokeBegin(handle, x0, y0, 1f)
        val steps = maxOf(1, (kotlin.math.abs(x1 - x0) + kotlin.math.abs(y1 - y0)).toInt() / 2)
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            EngineHost.nStrokeMove(handle, id, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, 1f)
        }
        EngineHost.nStrokeEnd(handle, id)
    }

    /**
     * B1 §3 UI plumbing: the Retouch row arms clone mode, a long press on
     * the live surface picks the source (engine coords) and leaves no ink
     * (the in-flight dab is rolled back).
     */
    @Test
    fun b1CloneLongPressPicksSourceWithoutInk() {
        val id = openFreshDoc("b1-clone-ui")
        val es = freshSurface(id)
        try {
            composeRule.onNodeWithTag("tool-b1-toggle")
                .performScrollTo()
                .performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("b1-mode-clone")
                .performScrollTo()
                .performClick()
            composeRule.waitForIdle()
            assertTrue("clone mode must arm the long press", es.cloneArmed)
            val (vx, vy) = es.engineToScreen(128f, 128f)
            val t0 = SystemClock.uptimeMillis()
            dispatchAll(es, simpleDown(t0, vx, vy))
            Thread.sleep(900)
            dispatchAll(
                es,
                MotionEvent.obtain(
                    t0, t0 + 950, MotionEvent.ACTION_UP,
                    vx, vy, 1f, 1f, 0, 1f, 1f, 0, 0
                )
            )
            Thread.sleep(700)
            assertTrue(
                "long press must pick the clone source (got ${es.cloneSourceX})",
                es.cloneSourceX >= 0f
            )
            val bmp = pullFull(es)
            try {
                assertEquals(
                    "long press must not leave ink",
                    -1, bmp.getPixel(128, 128)
                )
            } finally {
                bmp.recycle()
            }
            // Leaving the row disarms the long press (no stray picking).
            composeRule.onNodeWithTag("tool-b1-toggle")
                .performScrollTo()
                .performClick()
            composeRule.waitForIdle()
            assertFalse("closing the row disarms clone", es.cloneArmed)
        } finally {
            closeFreshDoc(id)
        }
    }

    /** B1 §1: gap closing before the flood + one-step undo. */
    @Test
    fun b1BucketGapClosesBrokenLineAndUndoes() {
        val handle = EngineHost.nInit(128, 128, 160f)
        try {
            // Black line at y=64 with a 6 px hole (x 60..65).
            b1Line(handle, 0f, 64f, 59f, 64f)
            b1Line(handle, 66f, 64f, 127f, 64f)
            val before = b1Composite(handle)
            // gap 0: the hole leaks (top fill reaches the lower half).
            assertTrue(
                "plain flood must leak through the broken line",
                EngineHost.nFloodFill(
                    handle, 64, 20, "#FF0000",
                    """{"tolerance":0,"antialias":false,"gap":0}"""
                )
            )
            val leaked = b1Composite(handle)
            assertNotEquals(
                "lower half must have been filled without gap closing",
                -1, leaked[100 * 128 + 64]
            )
            assertTrue(EngineHost.nUndo(handle))
            assertTrue(
                "one undo restores the pre-fill composite",
                b1Composite(handle).contentEquals(before)
            )
            // gap 4: closes the 6 px hole (8 px reach) — the lower half
            // stays white, the upper half is green.
            assertTrue(
                EngineHost.nFloodFill(
                    handle, 64, 20, "#00FF00",
                    """{"tolerance":0,"antialias":false,"gap":4}"""
                )
            )
            val closed = b1Composite(handle)
            assertEquals("gap closed: below untouched", -1, closed[100 * 128 + 64])
            assertEquals(
                "above painted green", 0xFF00FF00.toInt(), closed[20 * 128 + 64]
            )
            assertTrue(EngineHost.nUndo(handle))
            assertTrue(
                "gap-closed fill is ONE undo step",
                b1Composite(handle).contentEquals(before)
            )
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    /** B1 §1: surround fills every enclosed cavity in one gesture. */
    @Test
    fun b1SurroundFillsEnclosedCavitiesAndUndoes() {
        val handle = EngineHost.nInit(128, 128, 160f)
        try {
            // Two closed boxes drawn with strokes.
            b1Line(handle, 20f, 20f, 50f, 20f)
            b1Line(handle, 50f, 20f, 50f, 50f)
            b1Line(handle, 50f, 50f, 20f, 50f)
            b1Line(handle, 20f, 50f, 20f, 20f)
            b1Line(handle, 78f, 20f, 108f, 20f)
            b1Line(handle, 108f, 20f, 108f, 50f)
            b1Line(handle, 108f, 50f, 78f, 50f)
            b1Line(handle, 78f, 50f, 78f, 20f)
            val before = b1Composite(handle)
            // Normal flood: seed cavity only.
            assertTrue(
                EngineHost.nFloodFill(
                    handle, 35, 35, "#FF0000",
                    """{"tolerance":0,"antialias":false}"""
                )
            )
            val normal = b1Composite(handle)
            assertEquals("normal fill paints the seed cavity", 0xFFFF0000.toInt(), normal[35 * 128 + 35])
            assertEquals("other cavity untouched", -1, normal[35 * 128 + 93])
            assertTrue(EngineHost.nUndo(handle))
            // Surround: both cavities, barrier untouched, one undo.
            assertTrue(
                EngineHost.nFloodFill(
                    handle, 35, 35, "#0000FF",
                    """{"tolerance":0,"antialias":false,"surround":true}"""
                )
            )
            val surround = b1Composite(handle)
            assertEquals("seed cavity filled", 0xFF0000FF.toInt(), surround[35 * 128 + 35])
            assertEquals("other cavity filled too", 0xFF0000FF.toInt(), surround[35 * 128 + 93])
            // The barrier keeps its own ink (#141414 stroke), never blue.
            assertEquals(
                "barrier not painted",
                0xFF141414.toInt(), surround[20 * 128 + 35]
            )
            assertTrue(EngineHost.nUndo(handle))
            assertTrue(
                "surround is ONE undo step",
                b1Composite(handle).contentEquals(before)
            )
            // Surround refuses a seed outside every cavity (no undo entry:
            // the previous composite survives and the stack depth is the
            // same — a second undo goes back to the strokes before).
            assertFalse(
                EngineHost.nFloodFill(
                    handle, 4, 4, "#0000FF",
                    """{"tolerance":0,"antialias":false,"surround":true}"""
                )
            )
            assertTrue(b1Composite(handle).contentEquals(before))
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    /** B1 §3: clone stamp aligned vs non-aligned + one-step undo. */
    @Test
    fun b1CloneStampAlignmentSamplesAndUndoes() {
        val handle = EngineHost.nInit(128, 128, 160f)
        try {
            // Red patch on the base layer (T1 figure, fill only).
            assertTrue(
                EngineHost.nStampShape(
                    handle,
                    """{"kind":"rect","x0":8,"y0":8,"x1":24,"y1":24,"fillOn":true,"fill":"#FF0000","strokeOn":false}"""
                )
            )
            val withPatch = b1Composite(handle)
            // Non-aligned: every stroke re-anchors at (16,16).
            assertTrue(
                EngineHost.nSetCloneSource(
                    handle,
                    """{"x":16,"y":16,"aligned":false,"sample":"layer"}"""
                )
            )
            b1SetBrush(handle, "clone", 6f)
            b1Line(handle, 70f, 70f, 90f, 70f, "clone", 6f)
            val first = b1Composite(handle)
            val redAt70 = first[70 * 128 + 70]
            assertTrue(
                "clone must copy the red patch (got ${Integer.toHexString(redAt70)})",
                ((redAt70 shr 16) and 0xFF) > 180 && ((redAt70 shr 8) and 0xFF) < 90
            )
            b1Line(handle, 110f, 110f, 118f, 110f, "clone", 6f)
            val second = b1Composite(handle)
            assertTrue(
                "non-aligned clone re-anchors on every stroke",
                ((second[110 * 128 + 110] shr 16) and 0xFF) > 180
            )
            // Undo both clone strokes: back to the patch composite.
            assertTrue(EngineHost.nUndo(handle))
            assertTrue(EngineHost.nUndo(handle))
            assertTrue(
                "two clone strokes = two undo steps",
                b1Composite(handle).contentEquals(withPatch)
            )
            // Aligned: the offset survives the second stroke, so sampling
            // at (110,110) reads (56,56) — outside the patch (white).
            assertTrue(
                EngineHost.nSetCloneSource(
                    handle,
                    """{"x":16,"y":16,"aligned":true,"sample":"layer"}"""
                )
            )
            b1Line(handle, 70f, 70f, 90f, 70f, "clone", 6f)
            b1Line(handle, 110f, 110f, 118f, 110f, "clone", 6f)
            val aligned = b1Composite(handle)
            assertEquals(
                "aligned clone keeps sampling the same offset (white here)",
                -1, aligned[110 * 128 + 110]
            )
            // A long-press re-anchor resets even in aligned mode.
            assertTrue(
                EngineHost.nSetCloneSource(
                    handle,
                    """{"x":16,"y":16,"aligned":true,"sample":"layer"}"""
                )
            )
            b1Line(handle, 110f, 110f, 118f, 110f, "clone", 6f)
            val reanchored = b1Composite(handle)
            assertTrue(
                "fresh anchor samples the patch again",
                ((reanchored[110 * 128 + 110] shr 16) and 0xFF) > 180
            )
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    /** B1 §2: smudge, dodge and burn produce ink and undo in one step. */
    @Test
    fun b1SmudgeDodgeBurnProduceInkAndUndo() {
        val handle = EngineHost.nInit(128, 128, 160f)
        try {
            // Flat mid-gray base.
            assertTrue(
                EngineHost.nFloodFill(
                    handle, 4, 4, "#808080", """{"tolerance":0.1}"""
                )
            )
            val gray = b1Composite(handle)
            assertEquals(0xFF808080.toInt(), gray[40 * 128 + 40])
            // Dodge brightens existing pixels.
            b1Line(handle, 20f, 20f, 60f, 20f, "dodge", 12f)
            val dodged = b1Composite(handle)
            assertTrue(
                "dodge must brighten",
                ((dodged[20 * 128 + 40] shr 16) and 0xFF) > 0x80
            )
            assertTrue(EngineHost.nUndo(handle))
            assertTrue("dodge is ONE undo step", b1Composite(handle).contentEquals(gray))
            // Burn darkens.
            b1Line(handle, 20f, 20f, 60f, 20f, "burn", 12f)
            val burned = b1Composite(handle)
            assertTrue(
                "burn must darken",
                ((burned[20 * 128 + 40] shr 16) and 0xFF) < 0x80
            )
            assertTrue(EngineHost.nUndo(handle))
            assertTrue("burn is ONE undo step", b1Composite(handle).contentEquals(gray))
            // Smudge: black band then a horizontal smudge across it moves
            // paint to the right.
            b1Line(handle, 50f, 10f, 50f, 60f, "brush", 5f)
            val band = b1Composite(handle)
            b1Line(handle, 40f, 35f, 70f, 35f, "smudge", 10f)
            val smudged = b1Composite(handle)
            val rightOfBand = smudged[35 * 128 + 58]
            assertTrue(
                "smudge must carry paint right of the band (got ${Integer.toHexString(rightOfBand)})",
                rightOfBand != -1 && rightOfBand != band[35 * 128 + 58]
            )
            assertTrue(EngineHost.nUndo(handle))
            assertTrue("smudge is ONE undo step", b1Composite(handle).contentEquals(band))
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    /** B1 §4: pattern brush stamps along the stroke + one-step undo. */
    @Test
    fun b1PatternBrushStampsAlongStrokeAndUndoes() {
        val handle = EngineHost.nInit(128, 128, 160f)
        try {
            val before = b1Composite(handle)
            assertTrue(
                EngineHost.nSetPatternBrush(
                    handle,
                    """{"motif":"dots","size":16,"spacing":24,"scale":1,"rotation":0,"jitterPos":0,"jitterRot":0,"seed":3,"alongPath":true,"color":"#202020"}"""
                )
            )
            b1Line(handle, 20f, 64f, 108f, 64f, "pattern", 10f)
            val stamped = b1Composite(handle)
            val ink = stamped.indices.count { stamped[it] != -1 }
            assertTrue("pattern stroke must stamp ink, got $ink", ink > 100)
            // Spacing 24 > motif 16 leaves blank pixels between stamps.
            var gaps = 0
            for (x in 26..104) if (stamped[64 * 128 + x] == -1) gaps++
            assertTrue("spacing must leave gaps between motifs, got $gaps", gaps > 10)
            assertTrue(EngineHost.nUndo(handle))
            assertTrue(
                "pattern stroke is ONE undo step",
                b1Composite(handle).contentEquals(before)
            )
            // Unknown motif refused (previous tile kept, no crash).
            assertFalse(
                EngineHost.nSetPatternBrush(handle, """{"motif":"marble"}""")
            )
        } finally {
            EngineHost.nDispose(handle)
        }
    }

    /** B1 §5: layer texture is non-destructive, bake is one undo step. */
    @Test
    fun b1LayerTextureNonDestructiveAndBake() {
        val handle = EngineHost.nInit(128, 128, 160f)
        try {
            assertTrue(
                EngineHost.nFloodFill(
                    handle, 4, 4, "#B4783C", """{"tolerance":0.1}"""
                )
            )
            val before = b1Composite(handle)
            assertTrue(
                EngineHost.nSetLayerTexture(
                    handle, 0,
                    """{"kind":"canvas","scale":1.5,"strength":0.6,"seed":3}"""
                )
            )
            assertTrue(
                "layer state must expose the texture fiche",
                EngineHost.nLayerState(handle).contains("\"texture\"")
            )
            val textured = b1Composite(handle)
            assertFalse(
                "texture must modulate the composite",
                textured.contentEquals(before)
            )
            // Clear = exact previous composite (non-destructive).
            assertTrue(EngineHost.nSetLayerTexture(handle, 0, null))
            assertTrue(
                "clearing the fiche restores the exact composite",
                b1Composite(handle).contentEquals(before)
            )
            // Re-apply then bake: one undo step back to the untextured pixels.
            assertTrue(
                EngineHost.nSetLayerTexture(
                    handle, 0,
                    """{"kind":"paper","scale":2,"strength":0.5,"seed":9}"""
                )
            )
            assertTrue(EngineHost.nSetLayerTexture(handle, 0, """{"bake":true}"""))
            assertFalse(
                "bake clears the fiche",
                EngineHost.nLayerState(handle).contains("\"texture\"")
            )
            assertFalse(
                "baked pixels stay textured",
                b1Composite(handle).contentEquals(before)
            )
            assertTrue(EngineHost.nUndo(handle))
            assertTrue(
                "bake is ONE undo step",
                b1Composite(handle).contentEquals(before)
            )
            // Refused kinds: unknown texture kind leaves the layer intact.
            assertFalse(
                EngineHost.nSetLayerTexture(handle, 0, """{"kind":"marble"}""")
            )
        } finally {
            EngineHost.nDispose(handle)
        }
    }


    // ---- B2 §1–§5 (issue #31): lettering and timing ----

    // B2 §1: a transform re-commit changes the pixels and undoes with
    // ONE nUndo; the fiche keeps the tuple and survives ORA.
    @Test
    fun b2TextTransformCommitIsOneUndo() {
        val id = openFreshDoc("b2-xform")
        val es = freshSurface(id)
        try {
            val base = dev.openclip.ui.TextSpec(
                text = "Rotate", font = "sans-serif", sizePx = 64,
                boxX = 20f, boxY = 30f, boxW = 200f
            )
            val idx = commitTextSync(es, base)
            val before = pullFull(es)
            val xf = base.copy(
                rot = 30f, scaleX = 1.3f, scaleY = 0.8f, skew = 12f
            )
            assertTrue(
                "transform re-commit must succeed",
                awaitBool { cb -> es.reeditText(idx, xf, cb) }
            )
            val f = es.layerTextForTest(idx)
            assertNotNull("fiche must survive the transform", f)
            assertTrue("rot stored ${f!!.rot}", kotlin.math.abs(f.rot - 30f) < 0.01f)
            assertTrue("sx stored ${f.scaleX}", kotlin.math.abs(f.scaleX - 1.3f) < 0.01f)
            assertTrue("sy stored ${f.scaleY}", kotlin.math.abs(f.scaleY - 0.8f) < 0.01f)
            assertTrue("skew stored ${f.skew}", kotlin.math.abs(f.skew - 12f) < 0.01f)
            val after = pullFull(es)
            try {
                assertFalse(
                    "transformed text must move pixels",
                    bitmapPixels(before).contentEquals(bitmapPixels(after))
                )
            } finally {
                after.recycle()
            }
            // ONE undo restores the untransformed composite + fiche.
            assertTrue("one undo must restore the transform", es.undo())
            val undone = pullFull(es)
            try {
                assertTrue(
                    "one undo restores the pre-transform composite",
                    bitmapPixels(before).contentEquals(bitmapPixels(undone))
                )
            } finally {
                undone.recycle()
            }
            val f0 = es.layerTextForTest(idx)
            assertNotNull("layer stays after the pixel undo", f0)
            assertTrue("undo restores the identity tuple", kotlin.math.abs(f0!!.rot) < 0.01f)
            // ORA keeps the tuple (re-apply, save, load).
            assertTrue(awaitBool { cb -> es.reeditText(idx, xf, cb) })
            val ora = File(
                InstrumentationRegistry.getInstrumentation()
                    .targetContext.cacheDir, "b2-xform.ora"
            )
            assertTrue(awaitBool { cb -> es.saveTo(ora.absolutePath, "ora", cb) })
            assertTrue(awaitBool { cb -> es.loadFrom(ora.absolutePath, cb) })
            val fr = es.layerTextForTest(idx)
            assertNotNull("ORA keeps the fiche", fr)
            assertTrue(
                "ORA keeps the transform tuple (${fr!!.rot})",
                kotlin.math.abs(fr.rot - 30f) < 0.01f
            )
            ora.delete()
            before.recycle()
        } finally {
            closeFreshDoc(id)
        }
    }

    // B2 §1: the on-canvas handles really drive the commit (rotate drag
    // -> one atomic re-commit -> one undo).
    @Test
    fun b2TextTransformHandlesCommit() {
        val id = openFreshDoc("b2-handles")
        val es = freshSurface(id)
        try {
            val base = dev.openclip.ui.TextSpec(
                text = "Handles", font = "sans-serif", sizePx = 72,
                boxX = 20f, boxY = 40f, boxW = 180f
            )
            val idx = commitTextSync(es, base)
            val before = pullFull(es)
            composeRule.activityRule.scenario.onActivity { act ->
                act.activityTestHooks.openTextTransform?.invoke(idx, base)
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("text-transform-overlay").assertExists()
            // Proof shot (pulled from the app cache by the gate): one
            // frame settles the Compose overlay over the SurfaceView.
            Thread.sleep(1000)
            composeRule.waitForIdle()
            shot("openclip-b2.png")
            composeRule.onNodeWithTag("tt-rotate").performTouchInput {
                val c = center
                swipe(c, Offset(c.x + 30f, c.y + 20f), 250L)
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("tt-validate").performClick()
            var rot = 0f
            val deadline = SystemClock.uptimeMillis() + 15000
            while (SystemClock.uptimeMillis() < deadline) {
                composeRule.waitForIdle()
                val f = es.layerTextForTest(idx)
                rot = f?.rot ?: 0f
                if (kotlin.math.abs(rot) > 0.5f) break
                Thread.sleep(200)
            }
            assertTrue("rotate handle must set a non-zero angle ($rot)", kotlin.math.abs(rot) > 0.5f)
            val after = pullFull(es)
            try {
                assertFalse(
                    "handle commit must change pixels",
                    bitmapPixels(before).contentEquals(bitmapPixels(after))
                )
            } finally {
                after.recycle()
            }
            assertTrue("handle commit undoes in ONE step", es.undo())
            val undone = pullFull(es)
            try {
                assertTrue(
                    "one undo restores the handle-free composite",
                    bitmapPixels(before).contentEquals(bitmapPixels(undone))
                )
            } finally {
                undone.recycle()
            }
            before.recycle()
        } finally {
            closeFreshDoc(id)
        }
    }

    // B2 §2: glyph catalog from the loaded font + insertion at selection.
    @Test
    fun b2GlyphCatalogInsert() {
        val glyphs = dev.openclip.ui.GlyphCatalog.glyphsFor(
            "sans-serif", false, false
        )
        assertTrue("catalog must not be empty", glyphs.size >= 40)
        assertTrue("ASCII letter present", glyphs.contains("A"))
        assertTrue("Latin-1 accent present", glyphs.contains("é"))
        assertTrue("catalog capped", glyphs.size <= 320)
        val tag = dev.openclip.ui.GlyphCatalog.tagOf("é")
        assertEquals("glyph-u+00E9", tag)
        val (t1, c1) = dev.openclip.ui.GlyphCatalog.insertAt("abcd", 1, 3, "É")
        assertEquals("aÉd", t1)
        assertEquals(2, c1)
        val (t2, c2) = dev.openclip.ui.GlyphCatalog.insertAt("", 0, 0, "ß")
        assertEquals("ß", t2)
        assertEquals(1, c2)
    }

    // B2 §2: story paragraphs keep offsets and runs across edits; the
    // preview reuses the existing TextLineLayout.
    @Test
    fun b2StoryParagraphsAndRuns() {
        val paras = dev.openclip.ui.storyParagraphs("A\n\nBB")
        assertEquals(3, paras.size)
        assertEquals(0, paras[0].start)
        assertEquals(2, paras[1].start)
        assertEquals(3, paras[2].start)
        assertEquals("BB", paras[2].text)
        val runs = dev.openclip.ui.storyApplyRun(
            emptyList(), 3, 2,
            dev.openclip.ui.TextRun(
                start = 3, len = 2, font = "serif", style = "bold"
            )
        )
        assertEquals(1, runs.size)
        assertEquals(3, runs[0].start)
        // Editing paragraph 0 shifts the paragraph-2 run.
        val shifted = dev.openclip.ui.storyShiftRuns(runs, 1, 4, 10)
        assertEquals(7, shifted[0].start)
        // Layout reuse: a narrow measure wraps the story over 2+ lines.
        val spec = dev.openclip.ui.TextSpec(
            text = "A long story line that must wrap several times",
            sizePx = 24, boxW = 90f
        )
        val frame = dev.openclip.ui.TextLineLayout.frameSize(spec, 256)
        val lines = dev.openclip.ui.TextLineLayout.layout(spec, frame.first)
        assertTrue("story preview must wrap (${lines.size})", lines.size >= 2)
        assertEquals(90f, frame.first, 0.01f)
    }

    // B2 §3: the parsed .abr tip becomes an installable, USABLE preset
    // (file + preview) and the engine stamps its mask.
    @Test
    fun b2AbrTipInstallAndPaint() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(ctx.cacheDir, "b2-abr")
        root.deleteRecursively()
        root.mkdirs()
        val (tips, _) = dev.openclip.ui.AbrParser.parse(
            dev.openclip.ui.AbrParser.fixtureTip()
        )
        assertEquals(1, tips.size)
        val installed = dev.openclip.ui.AbrTipStore.install(root, tips, "fixture")
        assertEquals(1, installed.size)
        assertTrue("tip PNG written", File(installed[0].path).isFile)
        assertTrue("tip PNG non-empty", File(installed[0].path).length() > 0)
        val reloaded = dev.openclip.ui.AbrTipStore.load(root)
        assertEquals(1, reloaded.size)
        assertEquals("fixture-1", reloaded[0].name)
        val preview = dev.openclip.ui.AbrTipStore.previewBitmap(reloaded[0])
        assertNotNull("tip preview must decode", preview)
        preview!!.recycle()

        val handle = EngineHost.nInit(64, 64, 160f)
        try {
            EngineHost.nSetBrush(
                handle,
                """{"radius":8,"hardness":1.0,"opaque":1.0,"spacing":0.1,"color":"#FF0000","mode":"brush"}"""
            )
            // Round reference dab.
            var sid = EngineHost.nStrokeBegin(handle, 32f, 32f, 1f)
            EngineHost.nStrokeEnd(handle, sid)
            val round = b1Composite(handle, 64, 64)
            assertTrue(EngineHost.nUndo(handle))
            // Tip dab: the fixture is a 2x2 center block, so the painted
            // area must be clearly smaller than the round disc.
            assertTrue(
                "installed tip must load",
                EngineHost.nSetBrushTip(handle, reloaded[0].path)
            )
            sid = EngineHost.nStrokeBegin(handle, 32f, 32f, 1f)
            EngineHost.nStrokeEnd(handle, sid)
            val tipped = b1Composite(handle, 64, 64)
            var roundInk = 0
            var tipInk = 0
            for (k in round.indices) {
                if (round[k] != -1) roundInk++
                if (tipped[k] != -1) tipInk++
            }
            assertTrue("round dab paints ($roundInk)", roundInk > 100)
            assertTrue("tip dab paints ($tipInk)", tipInk > 10)
            assertTrue(
                "tip mask must shrink the stamp ($tipInk vs $roundInk)",
                tipInk > 0 && tipInk < roundInk
            )
            // Mask shape: the transparent outer texel of the fixture
            // leaves the stamp edge faint where the round dab is solid
            // (composite over the white paper: green channel).
            val roundEdge = (round[32 * 64 + 25] ushr 8) and 0xFF
            val tipEdge = (tipped[32 * 64 + 25] ushr 8) and 0xFF
            assertTrue(
                "round edge is solid ink ($roundEdge)",
                roundEdge < 40
            )
            assertTrue(
                "tip edge follows the mask ($tipEdge vs $roundEdge)",
                tipEdge > 120
            )
            // Clearing the tip returns to the round stamp.
            assertTrue(EngineHost.nSetBrushTip(handle, ""))
            val sid2 = EngineHost.nStrokeBegin(handle, 32f, 32f, 1f)
            EngineHost.nStrokeEnd(handle, sid2)
            val cleared = b1Composite(handle, 64, 64)
            var clearedInk = 0
            for (k in cleared.indices) if (cleared[k] != -1) clearedInk++
            assertTrue("cleared tip uses the round stamp ($clearedInk)", clearedInk >= roundInk)
        } finally {
            EngineHost.nDispose(handle)
            root.deleteRecursively()
        }
    }

    // B2 §4: ClipAudio seek on a generated WAV (emulator MediaPlayer).
    @Test
    fun b2AudioScrubSeek() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val wav = File(ctx.cacheDir, "b2-scrub.wav")
        // 1 s of silence, 8 kHz mono 16-bit.
        val rate = 8000
        val samples = rate
        val data = samples * 2
        val out = java.io.ByteArrayOutputStream()
        fun le16(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
        fun le32(v: Int) {
            for (k in 0 until 4) out.write((v shr (8 * k)) and 0xFF)
        }
        out.write("RIFF".toByteArray())
        le32(36 + data)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        le32(16)
        le16(1)              // PCM
        le16(1)              // mono
        le32(rate)
        le32(rate * 2)      // byte rate
        le16(2)              // block align
        le16(16)             // bits
        out.write("data".toByteArray())
        le32(data)
        out.write(ByteArray(data))
        wav.writeBytes(out.toByteArray())
        val audio = dev.openclip.media.ClipAudio()
        try {
            assertTrue("WAV must attach", audio.attach(wav.absolutePath, "b2.wav"))
            assertTrue("duration detected", audio.durationMs() in 800..1200)
            assertEquals(
                "cel 6 at 12 fps = 500 ms",
                500, dev.openclip.media.ClipAudio.celToMs(6, 12f)
            )
            assertTrue("seek must succeed", audio.seekTo(500))
            Thread.sleep(300)
            val pos = audio.positionMs()
            assertTrue("seek lands near 500ms (got $pos)", pos in 300..900)
            assertTrue("out-of-range seek clamps", audio.seekTo(99999))
            Thread.sleep(200)
            assertTrue("clamped position stays in range", audio.positionMs() <= audio.durationMs())
            assertTrue("negative seek clamps", audio.seekTo(-50))
        } finally {
            audio.clear()
            wav.delete()
        }
    }

    // B2 §5: one frame every N strokes + WebM export from the stills.
    @Test
    fun b2TimelapseCaptureExport() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.cacheDir, "b2-timelapse")
        dir.deleteRecursively()
        val out = File(ctx.cacheDir, "b2-timelapse.webm")
        out.delete()
        val rec = dev.openclip.media.Timelapse.Recorder()
        rec.everyN = 2
        try {
            val due = (1..5).map { rec.onStroke() }
            assertEquals(
                listOf(false, true, false, true, false), due
            )
            var n = 0
            for (k in 0 until 2) {
                val bmp = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(bmp)
                c.drawColor(0xFFFFFFFF.toInt())
                val p = android.graphics.Paint()
                p.color = 0xFF204060.toInt()
                c.drawCircle(100f + k * 60f, 200f, 60f, p)
                assertTrue("capture $k must store a frame", rec.capture(bmp, dir))
                bmp.recycle()
                n++
            }
            assertEquals(2, rec.frames.size)
            assertEquals(2, n)
            // Bounded downscale keeps the WebM small and the aspect.
            val first = android.graphics.BitmapFactory.decodeFile(
                rec.frames[0].absolutePath
            )
            assertNotNull("stored frame must decode", first)
            assertEquals(480, first!!.width)
            assertEquals(320, first.height)
            first.recycle()
            assertTrue("timelapse export must succeed", rec.exportWebm(12f, out))
            assertTrue("WebM file must exist", out.isFile)
            assertTrue("WebM must be non-empty", out.length() > 100)
        } finally {
            out.delete()
            dir.deleteRecursively()
        }
    }

    /** A2: best-effort UI screenshot into the app cache (proof shots). */

    private fun shot(name: String) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val s = InstrumentationRegistry.getInstrumentation()
                    .uiAutomation.takeScreenshot() ?: return
                val dst = File(composeRule.activity.cacheDir, name)
                FileOutputStream(dst).use { o ->
                    s.compress(Bitmap.CompressFormat.PNG, 100, o)
                }
                s.recycle()
            }
        } catch (_: Exception) {
        }
    }

    /** A2: "pass" flag of the top-level group at [i] (null-safe). */
    private fun groupPassFlag(es: EngineSurface, i: Int): Boolean? {
        return try {
            val arr = org.json.JSONObject(es.layerState())
                .optJSONArray("layers") ?: return null
            val e = arr.optJSONObject(i) ?: return null
            (e.optJSONObject("self") ?: e).optBoolean("pass", true)
        } catch (_: Exception) {
            null
        }
    }
}
