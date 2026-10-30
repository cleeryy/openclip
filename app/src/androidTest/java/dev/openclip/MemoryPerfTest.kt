// OpenClip — issue #32 §3: measured memory/perf budget on the emulator.
//
// This is the reproducible source of the numbers written in
// docs/TASKS.md. It measures the REAL engine (no mocks) on the device
// the suite runs on, and it asserts the POLICY (caps enforced, no OOM),
// not absolute timings (they depend on the host).
//
// Output: every figure is logged with tag OPENCLIP_PERF so a run can be
// harvested with `adb logcat -d -s OPENCLIP_PERF` after scripts/emutest.sh.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip

import android.app.ActivityManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openclip.engine.EngineHost
import dev.openclip.engine.MemoryBudget
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MemoryPerfTest {

    private val tag = "OPENCLIP_PERF"

    private fun log(msg: String) = Log.i(tag, msg)

    private fun ms(startNs: Long): Long =
        (System.nanoTime() - startNs) / 1_000_000L

    /** Resident set size of this process in MiB (native + Java + libs). */
    private fun rssMb(): Long {
        return try {
            val parts = File("/proc/self/statm").readText().trim().split(" ")
            parts[1].toLong() * 4096L / (1024L * 1024L)
        } catch (_: Exception) {
            0L
        }
    }

    private fun totalMemMb(): Long {
        val am = InstrumentationRegistry.getInstrumentation()
            .targetContext.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem / (1024L * 1024L)
    }

    /**
     * Target of the issue: 4096x4096 with 50 layers. 50 x 67.1 MB of
     * RGBA8 is 3.36 GB — impossible on the emulator and on most tablets.
     * The test drives the REAL policy, records the capacity it reaches,
     * and asserts the refusal is clean (budget respected, no crash).
     */
    @Test
    fun perf4096_capacityCompositeAndUndo() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val (docBudget, undoBudget) = MemoryBudget.fromContext(ctx)
        log("device totalMem=${totalMemMb()}MB docBudget=${MemoryBudget.mb(docBudget)}MB " +
            "undoBudget=${MemoryBudget.mb(undoBudget)}MB rssStart=${rssMb()}MB")

        val tInit = System.nanoTime()
        val h = EngineHost.nInit(4096, 4096, 300f)
        val initMs = ms(tInit)
        assertTrue("nInit(4096) returned 0", h != 0L)
        EngineHost.nSetBudget(h, docBudget, undoBudget)

        // Grow layers until the document budget refuses (or 50 = target).
        var added = 1
        var refusedAt = 0
        for (i in 2..50) {
            val idx = EngineHost.nAddLayer(h, "L$i")
            if (idx < 0) {
                refusedAt = i
                break
            }
            added = i
        }
        val state = JSONObject(EngineHost.nBudgetState(h))
        val docBytes = state.getLong("docBytes")
        log(
            "4096 init=${initMs}ms layers=$added refusedAt=${refusedAt ?: 50} " +
                "docBytes=${MemoryBudget.mb(docBytes)}MB docBudget=" +
                "${MemoryBudget.mb(docBudget)}MB rss=${rssMb()}MB"
        )
        assertTrue(
            "document exceeded its byte budget: $docBytes > $docBudget",
            docBytes <= docBudget
        )
        assertEquals("layer count mirror must match addLayer calls", added, state.getInt("layers"))

        // Composite at the reached capacity (real frame push path).
        val px = IntArray(4096 * 4096)
        val times = ArrayList<Long>()
        repeat(3) {
            val t = System.nanoTime()
            EngineHost.nCompositeInto(h, px, 4096, 4096)
            times.add(ms(t))
        }
        val tStroke = System.nanoTime()
        val stroke = EngineHost.nStrokeBegin(h, 512f, 512f, 1f)
        for (k in 1..40) EngineHost.nStrokeMove(h, stroke, 512f + k * 8f, 512f, 1f)
        EngineHost.nStrokeEnd(h, stroke)
        val strokeMs = ms(tStroke)
        val tUndo = System.nanoTime()
        val undone = EngineHost.nUndo(h)
        val undoMs = ms(tUndo)
        val tRedo = System.nanoTime()
        val redone = EngineHost.nRedo(h)
        val redoMs = ms(tRedo)
        // Stroke-time frames: only the active layer changes, so the
        // shim reuses the below cache (issue #32 §3 fix). This is the
        // frame cost the user actually feels under the stylus.
        val strokeFrames = ArrayList<Long>()
        EngineHost.nSetActiveLayer(h, added - 1)  // topmost = worst case win
        val sid = EngineHost.nStrokeBegin(h, 900f, 900f, 1f)
        repeat(3) {
            val t = System.nanoTime()
            EngineHost.nCompositeInto(h, px, 4096, 4096)
            strokeFrames.add(ms(t))
        }
        EngineHost.nStrokeEnd(h, sid)
        val fullAfter = ArrayList<Long>()
        repeat(2) {
            val t = System.nanoTime()
            EngineHost.nCompositeInto(h, px, 4096, 4096)
            fullAfter.add(ms(t))
        }
        log(
            "4096 composite=${times}ms strokeFrame=${strokeFrames}ms " +
                "fullAfterStroke=${fullAfter}ms stroke40=${strokeMs}ms " +
                "undo=${undoMs}ms ($undone) redo=${redoMs}ms ($redone) " +
                "undoBytes=${MemoryBudget.mb(state.getLong("undoBytes"))}MB " +
                "rss=${rssMb()}MB"
        )
        assertTrue("undo after a stroke must succeed", undone)
        assertTrue("redo after undo must succeed", redone)
        val after = JSONObject(EngineHost.nBudgetState(h))
        assertTrue(
            "undo bytes above budget: " + after.getLong("undoBytes"),
            after.getLong("undoBytes") <= after.getLong("undoBudget")
        )
        EngineHost.nDispose(h)
        px.fill(0)
        log("4096 disposed rss=${rssMb()}MB")
    }

    /** Four documents open at once (the app ceiling) at 2048x1536. */
    @Test
    fun perfFourDocuments2048() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val (docBudget, undoBudget) = MemoryBudget.fromContext(ctx)
        val before = rssMb()
        val handles = ArrayList<Long>()
        val openMs = ArrayList<Long>()
        try {
            repeat(4) { d ->
                val t = System.nanoTime()
                val h = EngineHost.nInit(2048, 1536, 300f)
                assertTrue("doc $d failed to init", h != 0L)
                EngineHost.nSetBudget(h, docBudget, undoBudget)
                for (i in 2..8) EngineHost.nAddLayer(h, "D${d}L$i")
                handles.add(h)
                openMs.add(ms(t))
            }
            val afterOpen = rssMb()
            val px = IntArray(2048 * 1536)
            val comp = ArrayList<Long>()
            handles.forEach { h ->
                val t = System.nanoTime()
                EngineHost.nCompositeInto(h, px, 2048, 1536)
                comp.add(ms(t))
            }
            val strokeFrames = ArrayList<Long>()
            EngineHost.nSetActiveLayer(handles[0], 7)  // topmost layer
            val sid = EngineHost.nStrokeBegin(handles[0], 200f, 200f, 1f)
            repeat(3) {
                val t = System.nanoTime()
                EngineHost.nCompositeInto(handles[0], px, 2048, 1536)
                strokeFrames.add(ms(t))
            }
            EngineHost.nStrokeEnd(handles[0], sid)
            // Autosave cost = full ORA serialization of the live document
            // (what CrashRecovery writes on its timer / on background).
            val autosave = ArrayList<Long>()
            val sizes = ArrayList<Long>()
            handles.forEachIndexed { i, h ->
                val f = File(ctx.cacheDir, "perf-autosave-$i.ora")
                val t = System.nanoTime()
                val ok = EngineHost.nSaveDocument(h, f.absolutePath, "ora")
                autosave.add(ms(t))
                sizes.add(f.length() / 1024L)  // KiB
                assertTrue("autosave $i refused", ok)
                f.delete()
            }
            log(
                "4docs 2048x1536x8 open=${openMs}ms composite=${comp}ms " +
                    "strokeFrame=${strokeFrames}ms " +
                    "autosave=${autosave}ms sizes=${sizes}KiB " +
                    "rssBefore=${before}MB rssAfterOpen=${afterOpen}MB " +
                    "rssEnd=${rssMb()}MB"
            )
            assertTrue(
                "RSS grew past a 3-doc budget without caps: ${rssMb()}MB",
                rssMb() - before < 1200
            )
        } finally {
            handles.forEach { EngineHost.nDispose(it) }
        }
    }

    /**
     * Explicit cap: a small undo budget keeps the stack under it and
     * evicts the OLDEST steps first (the newest stay undoable).
     */
    @Test
    fun undoCapEvictsOldestWithoutOom() {
        val h = EngineHost.nInit(1024, 1024, 300f)
        assertTrue(h != 0L)
        val undoBudget = 8L * 1024 * 1024  // a few 256px tiles only
        EngineHost.nSetBudget(h, 0L, undoBudget)
        repeat(40) { i ->
            val id = EngineHost.nStrokeBegin(h, 40f + i * 16f, 40f, 1f)
            EngineHost.nStrokeMove(h, id, 120f + i * 16f, 90f, 1f)
            EngineHost.nStrokeEnd(h, id)
        }
        val state = JSONObject(EngineHost.nBudgetState(h))
        log(
            "undoCap budget=${MemoryBudget.mb(undoBudget)}MB " +
                "bytes=${state.getLong("undoBytes")} depth=" +
                "${state.getInt("undoDepth")} dropped=${state.getInt("dropped")}"
        )
        assertTrue(
            "undo bytes escaped the cap: " + state.getLong("undoBytes"),
            state.getLong("undoBytes") <= undoBudget
        )
        assertTrue("40 strokes must not all be retained", state.getInt("undoDepth") < 40)
        // The newest step is always undoable.
        assertTrue(EngineHost.nUndo(h))
        EngineHost.nDispose(h)
    }

    /** The document guard refuses layer growth politely (never OOM). */
    @Test
    fun documentGuardRefusesOversizedGrowth() {
        val h = EngineHost.nInit(1024, 1024, 300f)
        assertTrue(h != 0L)
        // 1024x1024 = 4 MB per layer; 4 MB base + one 4 MB layer fits 10 MB.
        EngineHost.nSetBudget(h, 10L * 1024 * 1024, 16L * 1024 * 1024)
        val second = EngineHost.nAddLayer(h, "fits")
        val third = EngineHost.nAddLayer(h, "refused")
        log("docGuard second=$second third=$third state=${EngineHost.nBudgetState(h)}")
        assertTrue("one extra layer must fit", second >= 0)
        assertEquals("the layer past the budget must be refused", -1, third)
        assertEquals(2, EngineHost.nLayerCount(h))
        EngineHost.nDispose(h)
    }

    /**
     * 50 layers on a realistic comic canvas (2048x1536 = 12.6 MB/layer):
     * 50 x 12.6 = 629 MB, which fits the 824 MB measured device budget.
     * This is the "50 layers" half of the issue target, at the size the
     * devices actually run (4096² x 50 = 3.36 GB is refused by policy).
     */
    @Test
    fun perf2048_fiftyLayers() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val (docBudget, undoBudget) = MemoryBudget.fromContext(ctx)
        val rss0 = rssMb()
        val h = EngineHost.nInit(2048, 1536, 300f)
        assertTrue(h != 0L)
        EngineHost.nSetBudget(h, docBudget, undoBudget)
        var added = 1
        for (i in 2..50) {
            if (EngineHost.nAddLayer(h, "L$i") < 0) break
            added = i
        }
        val state = JSONObject(EngineHost.nBudgetState(h))
        val px = IntArray(2048 * 1536)
        val full = ArrayList<Long>()
        repeat(3) {
            val t = System.nanoTime()
            EngineHost.nCompositeInto(h, px, 2048, 1536)
            full.add(ms(t))
        }
        EngineHost.nSetActiveLayer(h, added - 1)
        val sid = EngineHost.nStrokeBegin(h, 300f, 300f, 1f)
        val strokeFrames = ArrayList<Long>()
        repeat(3) {
            val t = System.nanoTime()
            EngineHost.nCompositeInto(h, px, 2048, 1536)
            strokeFrames.add(ms(t))
        }
        EngineHost.nStrokeEnd(h, sid)
        log(
            "2048x1536x$added layers docBytes=" +
                "${MemoryBudget.mb(state.getLong("docBytes"))}MB " +
                "composite=${full}ms strokeFrame=${strokeFrames}ms " +
                "rss=${rssMb()}MB (delta ${rssMb() - rss0}MB)"
        )
        assertTrue("50 layers must fit the measured budget", added == 50)
        EngineHost.nDispose(h)
    }

    /** Budget introspection is always available and well-formed. */
    @Test
    fun budgetStateIsParseable() {
        val h = EngineHost.nInit(256, 256, 72f)
        val json = JSONObject(EngineHost.nBudgetState(h))
        assertNotNull(json.opt("undoBudget"))
        assertTrue(json.getLong("undoBudget") >= 1024L * 1024L)
        assertTrue(json.getInt("layers") >= 1)
        EngineHost.nDispose(h)
    }
}
