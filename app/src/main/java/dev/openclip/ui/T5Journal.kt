// OpenClip T5 — macros rejouables + panneau historique (spec §7,
// app-side 100 %). MacroJournal records successful mutating engine
// calls (op name + params) at the EngineSurface wrapper level;
// navigation ops (view/frame switches) are never recorded (spec §7.1).
// HistoryLog is session-only: each committed undo step publishes a
// name + timestamp (tap-to-return = N normal undos, exact LIFO).
package dev.openclip.ui

import org.json.JSONArray
import org.json.JSONObject

/** One recorded macro step: engine op + params JSON. */
data class MacroOp(val op: String, val params: String)

/** One named macro: linear op list (no conditionals/loops — FUTURE). */
data class Macro(val name: String, val ops: List<MacroOp>)

/**
 * Session journal of mutating engine calls. Recording is explicit
 * (● button); replay re-emits in order on the current doc. Strokes
 * (gesture dabs) are NOT recorded: only discrete mutating ops, so a
 * macro stays a readable op list (spec §7.1: journal des appels JNI
 * mutateurs + paramètres, navigation filtrée).
 */
object MacroJournal {
    var recording: Boolean = false
        private set
    private val current = mutableListOf<MacroOp>()

    /** Ops recorded when they succeed (whitelist, spec §7.1). */
    private val recordable = setOf(
        "applyFilter", "applyTone", "setLayerBlend", "setLayerOpacity",
        "floodFill", "paintGradient", "stampShape", "selectionBlit",
        "puppetWarp", "setLayerAdjustment", "setLayerFlags",
        "setLayerFill", "setLayerText", "setLayerBubble", "setLayerAnim",
        "setCelHold", "addLayer", "setLayerVisibility"
    )

    fun start() {
        current.clear()
        recording = true
    }

    fun stop(): List<MacroOp> {
        recording = false
        return current.toList()
    }

    fun cancel() {
        current.clear()
        recording = false
    }

    fun record(op: String, params: String) {
        if (!recording) return
        if (op !in recordable) return
        current.add(MacroOp(op, params))
    }

    fun pendingCount(): Int = current.size
}

/** macros.json v1 store (local, like workspaces V+3). */
object MacroStore {
    fun toJson(macros: List<Macro>): String {
        val arr = JSONArray()
        for (m in macros) {
            val ops = JSONArray()
            for (o in m.ops) {
                ops.put(JSONObject().put("op", o.op).put("params", o.params))
            }
            arr.put(JSONObject().put("name", m.name).put("ops", ops))
        }
        return arr.toString()
    }

    fun fromJson(raw: String): List<Macro> {
        val out = mutableListOf<Macro>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "").take(48)
                if (name.isEmpty()) continue
                val ops = mutableListOf<MacroOp>()
                val ja = o.optJSONArray("ops") ?: JSONArray()
                for (k in 0 until ja.length()) {
                    val so = ja.optJSONObject(k) ?: continue
                    val op = so.optString("op", "")
                    if (op.isEmpty()) continue
                    ops.add(MacroOp(op, so.optString("params", "")))
                }
                out.add(Macro(name, ops))
            }
        } catch (_: Exception) {
        }
        return out
    }
}

/** Session-only history entry (spec §7.1: name + time, no branching). */
data class HistoryEntry(val label: String, val atMs: Long)

/** Session history log: the shim publishes each committed undo step. */
object HistoryLog {
    private val entries = mutableListOf<HistoryEntry>()
    var revision: Long = 0
        private set

    fun clear() {
        entries.clear()
        revision++
    }

    fun push(label: String) {
        entries.add(HistoryEntry(label.take(64), System.currentTimeMillis()))
        if (entries.size > 200) entries.removeAt(0)
        revision++
    }

    /** An undo pops the matching history entry (tap-to-return view). */
    fun popOne() {
        if (entries.isNotEmpty()) entries.removeAt(entries.size - 1)
        revision++
    }

    fun snapshot(): List<HistoryEntry> = entries.toList()
    fun size(): Int = entries.size
}
