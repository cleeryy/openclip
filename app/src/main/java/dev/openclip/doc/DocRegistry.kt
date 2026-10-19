// OpenClip slice 0 — single-document registry (app side only).
//
// The engine owns pixels through EngineSurface's single JNI handle; this
// registry only tracks document identities so the shell is ready for N
// docs: a Map<docId, entry> where exactly one tab is active (the one the
// live EngineSurface composites) and every entry is disposed explicitly.
// No multi-doc compositing in this slice — open() on a second id merely
// registers it, the surface keeps showing the active tab.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.doc

data class DocEntry(
    val docId: String,
    var name: String,
    var dirty: Boolean = false
)

class DocRegistry {
    private val docs = LinkedHashMap<String, DocEntry>()
    var activeDocId: String? = null
        private set

    fun open(docId: String, name: String): DocEntry {
        val entry = docs.getOrPut(docId) { DocEntry(docId, name) }
        entry.name = name
        activeDocId = docId
        return entry
    }

    fun activate(docId: String): DocEntry? {
        val entry = docs[docId] ?: return null
        activeDocId = docId
        return entry
    }

    fun active(): DocEntry? = activeDocId?.let { docs[it] }

    fun all(): List<DocEntry> = docs.values.toList()

    fun markDirty(docId: String, dirty: Boolean) {
        docs[docId]?.dirty = dirty
    }

    fun markActiveDirty(dirty: Boolean) {
        activeDocId?.let { docs[it]?.dirty = dirty }
    }

    fun close(docId: String): Boolean {
        if (!docs.containsKey(docId)) return false
        docs.remove(docId)
        if (activeDocId == docId) {
            activeDocId = docs.keys.lastOrNull()
        }
        return true
    }

    fun dispose() {
        docs.clear()
        activeDocId = null
    }

    val size: Int get() = docs.size
}
