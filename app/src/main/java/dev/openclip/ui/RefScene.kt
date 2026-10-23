// OpenClip T4 — decor scene model + persistence (ARCHITECTURE.md
// "T4 additions" §4). App-side mirror of the engine RefScene: items,
// scene JSON build/parse, dab-log persistence (decor.json). The
// texture itself is session-only engine-side (hors undo); durability
// comes from REPLAY: decor.json stores items + view + the dab log,
// re-sent on load in the persisted view (deterministic — projection
// frozen at stroke time). No decor.json = empty scene, never a
// broken page. tex_itemNN.png files are NOT written (no texture
// read-back exists within the 3-symbol JNI budget); the commit
// ("Reporter sur calque") is the durable artifact, baked into the
// ORA pixels.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** One decor item (mirrors openclip::RefSceneItem). */
data class RefItem(
    val src: String,  // "file:<cache-path>" | "prim:cube|sphere|plane|cylinder"
    val pos: FloatArray = floatArrayOf(0f, 0f, 0f),
    val rot: FloatArray = floatArrayOf(0f, 0f, 0f),
    val scale: Float = 1f,
    val visible: Boolean = true
) {
    fun label(): String = when {
        src.startsWith("prim:") -> src.removePrefix("prim:")
            .replaceFirstChar { it.uppercase() }
        src.startsWith("file:") -> File(src.removePrefix("file:")).name
        else -> src
    }
}

/** One painted stroke batch (replay unit): view + item + dabs JSON. */
data class RefPaintBatch(
    val viewJson: String,
    val item: Int,
    val dabsJson: String
)

object RefScene {
    fun sceneJson(items: List<RefItem>, wire: Boolean = true, flatFor: Int = -1): String {
        val sb = StringBuilder("{\"items\":[")
        items.forEachIndexed { i, it ->
            if (i > 0) sb.append(',')
            sb.append(
                String.format(
                    Locale.US,
                    "{\"src\":\"%s\",\"pos\":[%.2f,%.2f,%.2f]," +
                        "\"rot\":[%.2f,%.2f,%.2f],\"scale\":%.3f," +
                        "\"visible\":%d,\"wire\":%d,\"flat\":%d}",
                    it.src.replace("\\", "\\\\").replace("\"", "\\\""),
                    it.pos[0], it.pos[1], it.pos[2],
                    it.rot[0], it.rot[1], it.rot[2],
                    it.scale,
                    if (it.visible) 1 else 0,
                    if (wire) 1 else 0,
                    if (i == flatFor) 1 else 0
                )
            )
        }
        return sb.append("]}").toString()
    }

    fun viewJson(
        yaw: Float,
        pitch: Float,
        roll: Float,
        dist: Float,
        fov: Float,
        fisheye: Float
    ): String = String.format(
        Locale.US,
        "{\"yaw\":%.2f,\"pitch\":%.2f,\"roll\":%.2f,\"dist\":%.2f," +
            "\"fov\":%.2f,\"fisheye\":%.2f,\"offX\":0,\"offY\":0}",
        yaw, pitch, roll, dist, fov, fisheye
    )

    private fun dirFor(docKey: String, filesDir: File): File {
        val safe = docKey.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48)
        return File(filesDir, "t4-decor/$safe")
    }

    /** Persist scene + view + dab log (best effort, never throws). */
    fun save(
        docKey: String,
        filesDir: File,
        items: List<RefItem>,
        view: String,
        batches: List<RefPaintBatch>
    ): Boolean {
        return try {
            val dir = dirFor(docKey, filesDir)
            dir.mkdirs()
            val root = JSONObject()
            val arr = JSONArray()
            for (it in items) {
                val o = JSONObject()
                o.put("src", it.src)
                o.put("pos", JSONArray(it.pos.map { v -> v.toDouble() }))
                o.put("rot", JSONArray(it.rot.map { v -> v.toDouble() }))
                o.put("scale", it.scale.toDouble())
                o.put("visible", it.visible)
                arr.put(o)
            }
            root.put("items", arr)
            root.put("view", view)
            val log = JSONArray()
            for (b in batches) {
                val o = JSONObject()
                o.put("view", b.viewJson)
                o.put("item", b.item)
                o.put("dabs", b.dabsJson)
                log.put(o)
            }
            root.put("batches", log)
            File(dir, "decor.json").writeText(root.toString())
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Load persisted scene (null = none; empty list = empty scene). */
    data class SavedScene(
        val items: List<RefItem>,
        val view: String,
        val batches: List<RefPaintBatch>
    )

    fun load(docKey: String, filesDir: File): SavedScene? {
        return try {
            val f = File(dirFor(docKey, filesDir), "decor.json")
            if (!f.isFile) return null
            val root = JSONObject(f.readText())
            val items = mutableListOf<RefItem>()
            val arr = root.optJSONArray("items") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val src = o.optString("src", "")
                if (src.isEmpty()) continue
                fun floats(key: String): FloatArray {
                    val a = o.optJSONArray(key) ?: return floatArrayOf(0f, 0f, 0f)
                    return FloatArray(3) { k -> a.optDouble(k, 0.0).toFloat() }
                }
                items.add(
                    RefItem(
                        src = src,
                        pos = floats("pos"),
                        rot = floats("rot"),
                        scale = o.optDouble("scale", 1.0).toFloat()
                            .takeIf { it > 0f } ?: 1f,
                        visible = o.optBoolean("visible", true)
                    )
                )
            }
            val batches = mutableListOf<RefPaintBatch>()
            val log = root.optJSONArray("batches") ?: JSONArray()
            for (i in 0 until log.length()) {
                val o = log.optJSONObject(i) ?: continue
                batches.add(
                    RefPaintBatch(
                        viewJson = o.optString("view", ""),
                        item = o.optInt("item", -1),
                        dabsJson = o.optString("dabs", "")
                    )
                )
            }
            SavedScene(items, root.optString("view", ""), batches)
        } catch (_: Exception) {
            null
        }
    }
}
