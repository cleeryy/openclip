// OpenClip V+3 — posable mannequin panel state (§5, app-side + the two
// V+3 symbols nRefJoints/nSetRefPose; overlay-only, never exported).
//
// The rig is a libre glTF (licence logged before bundling — gate asset,
// spec §5); without a model the joints list is empty and the panel says
// so honestly (cube-fixture degraded mode). Poses persist per document
// in pose.json (bone -> [x,y,z] Euler degrees); presets debout/assis/
// course + reset. Handles are overlay-only and follow the view matrix
// (same contract as the M9 wireframe).
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import java.io.File

data class PoseJoint(val name: String, val parent: Int, val pos: FloatArray)

object PoseStore {
    const val FILE = "pose.json"

    fun file(cacheDir: File, docKey: String): File =
        File(cacheDir, "pose-$docKey.json")

    fun load(cacheDir: File, docKey: String): Map<String, FloatArray> {
        val out = mutableMapOf<String, FloatArray>()
        try {
            val f = file(cacheDir, docKey)
            if (!f.exists()) return out
            val t = f.readText()
            val entry = Regex("\"([^\"]+)\"\\s*:\\s*\\[([^\\]]*)\\]")
            for (m in entry.findAll(t)) {
                val nums = m.groupValues[2].split(",").mapNotNull {
                    it.trim().toFloatOrNull()?.coerceIn(-180f, 180f)
                }
                if (nums.size == 3) out[m.groupValues[1]] = nums.toFloatArray()
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun save(
        cacheDir: File,
        docKey: String,
        pose: Map<String, FloatArray>,
    ): Boolean = try {
        val body = pose.entries.joinToString(",") { (k, v) ->
            "\"$k\":[${v[0]},${v[1]},${v[2]}]"
        }
        file(cacheDir, docKey).writeText("{\"pose\":{$body}}")
        true
    } catch (_: Exception) {
        false
    }

    fun toEngineJson(pose: Map<String, FloatArray>): String {
        val body = pose.entries.joinToString(",") { (k, v) ->
            "\"$k\":[${v[0]},${v[1]},${v[2]}]"
        }
        return "{$body}"
    }

    /** Parse an nRefJoints payload (empty object when no named nodes). */
    fun parseJoints(json: String): List<PoseJoint> = try {
        val out = mutableListOf<PoseJoint>()
        val entry =
            Regex("\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"parent\"\\s*:\\s*(-?\\d+)\\s*,\\s*\"pos\"\\s*:\\s*\\[([^\\]]*)\\]")
        for (m in entry.findAll(json)) {
            val nums = m.groupValues[3].split(",")
                .mapNotNull { it.trim().toFloatOrNull() }
            if (nums.size != 3) continue
            out.add(PoseJoint(m.groupValues[1], m.groupValues[2].toInt(), nums.toFloatArray()))
        }
        out
    } catch (_: Exception) {
        emptyList()
    }

    /** Built-in presets (Euler XYZ degrees per bone). */
    val PRESETS: Map<String, Map<String, FloatArray>> = mapOf(
        "standing" to emptyMap(),
        "sitting" to mapOf(
            "hips" to floatArrayOf(0f, 0f, 0f),
            "thighL" to floatArrayOf(-90f, 0f, 0f),
            "thighR" to floatArrayOf(-90f, 0f, 0f),
            "shinL" to floatArrayOf(90f, 0f, 0f),
            "shinR" to floatArrayOf(90f, 0f, 0f),
        ),
        "running" to mapOf(
            "thighL" to floatArrayOf(-45f, 0f, 0f),
            "thighR" to floatArrayOf(30f, 0f, 0f),
            "shinL" to floatArrayOf(60f, 0f, 0f),
            "armL" to floatArrayOf(0f, 0f, 25f),
            "armR" to floatArrayOf(0f, 0f, -25f),
        ),
    )
}
