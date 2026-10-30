// OpenClip — issue #32 §5: .clip corpus harness.
//
// Real Clip Studio Paint files CANNOT be committed (clean-room rule,
// Celsys assets stay local). This test therefore runs only when a
// directory is provided, iterates every `.clip` it finds, converts it
// through the app-side importer (DocClip -> ORA) and loads the result
// into the real engine. When nothing is provided it SKIPS cleanly
// (JUnit Assume), so CI and the default emulator gate stay green.
//
// Providing a corpus (device-visible path):
//   adb push ~/clip-corpus /sdcard/Download/clip-corpus
//   CALQUE_CLIP_CORPUS=/sdcard/Download/clip-corpus bash scripts/emutest.sh
// or directly:
//   adb shell am instrument -w -e class dev.openclip.ClipCorpusTest \
//     -e CALQUE_CLIP_CORPUS /sdcard/Download/clip-corpus dev.openclip.test/.AndroidJUnitRunner
//
// Honest verdict: a file the importer refuses is reported as REFUSED
// (a compatibility data point, not a crash); a file it accepts must
// produce an ORA the engine can load and composite — that is a hard
// assertion. The tally is logged as OPENCLIP_CORPUS for docs/TASKS.md.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openclip.doc.DocClip
import dev.openclip.engine.EngineHost
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ClipCorpusTest {

    private val tag = "OPENCLIP_CORPUS"

    @Test
    fun importsProvidedClipCorpus() {
        val args = InstrumentationRegistry.getArguments()
        val provided = args.getString("CALQUE_CLIP_CORPUS")
            ?: System.getenv("CALQUE_CLIP_CORPUS")
        assumeTrue(
            "CALQUE_CLIP_CORPUS not set — corpus harness skipped " +
                "(local .clip files are never committed)",
            !provided.isNullOrBlank()
        )
        val root = File(provided!!)
        assertTrue(
            "CALQUE_CLIP_CORPUS was set but is not a directory on this " +
                "device: $provided (adb push it first)",
            root.isDirectory
        )
        val files = root.walkTopDown()
            .filter { it.isFile && it.name.lowercase().endsWith(".clip") }
            .sortedBy { it.absolutePath }
            .take(200)
            .toList()
        assumeTrue("no .clip files under $provided", files.isNotEmpty())

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val work = File(ctx.cacheDir, "clip-corpus").apply { mkdirs() }
        var ok = 0
        var preview = 0
        var degraded = 0
        var refused = 0
        val refusedNames = ArrayList<String>()
        val broken = ArrayList<String>()
        var totalLayers = 0

        for (f in files) {
            val t = System.nanoTime()
            val outcome = DocClip.importToOra(f, work)
            val ms = (System.nanoTime() - t) / 1_000_000L
            when (outcome) {
                is DocClip.Outcome.Err -> {
                    refused++
                    if (refusedNames.size < 20) refusedNames.add("${f.name}: ${outcome.reason}")
                    Log.i(tag, "REFUSED ${f.name} (${f.length()} B, ${ms}ms): ${outcome.reason}")
                }
                is DocClip.Outcome.Ok -> {
                    val imp = outcome.import
                    val h = EngineHost.nInit(256, 256, 72f)
                    val loaded = h != 0L &&
                        EngineHost.nLoadDocument(h, imp.oraFile.absolutePath)
                    var composites = false
                    if (loaded) {
                        val w = EngineHost.nLayerCount(h)
                        totalLayers += w
                        // Composite the full document once (the engine
                        // always walks every layer) but only pull a
                        // 256x256 prefix into Java: a corpus file can be
                        // 4096² and a full IntArray would be 67 MB.
                        val px = IntArray(256 * 256)
                        composites = EngineHost.nCompositeInto(h, px, 256, 256) > 0
                        px.fill(0)
                        ok++
                        if (imp.report.previewOnly) preview++
                        if (imp.report.degradedCount > 0) degraded++
                        if (!composites) broken.add("${f.name}: composite refused")
                        Log.i(
                            tag,
                            "OK ${f.name} layers=$w ${imp.report.width}x${imp.report.height} " +
                                "preview=${imp.report.previewOnly} " +
                                "approx=${imp.report.degradedCount} ${ms}ms"
                        )
                    } else {
                        broken.add("${f.name}: converted ORA did not load")
                    }
                    if (h != 0L) EngineHost.nDispose(h)
                    imp.oraFile.delete()
                }
            }
        }
        Log.i(
            tag,
            "SUMMARY files=${files.size} ok=$ok previewOnly=$preview degraded=$degraded " +
                "refused=$refused avgLayers=" +
                (if (ok > 0) totalLayers / ok else 0) + " root=$provided"
        )
        if (refusedNames.isNotEmpty()) {
            Log.i(tag, "REFUSED SAMPLE: " + refusedNames.joinToString(" | "))
        }
        assertTrue(
            "an accepted .clip produced an unloadable/uncomposable document: " +
                broken.joinToString("; "),
            broken.isEmpty()
        )
    }
}
