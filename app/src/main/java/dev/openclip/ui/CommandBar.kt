// OpenClip slice 6 — pinnable command bar + workspace store.
//
// CommandBar: one-tap actions (save / undo / redo / export / fullscreen)
// under the tabs, 48dp each, collapsible to a pastille (every panel is
// born collapsible). Order + visibility persist in commandbar.json
// (single writer). Long-press enters edit mode: visibility chips +
// up/down arrows (no touch drag — accessible + testable).
//
// Workspaces: posture only (dock/tab/timeline/navMode/command bar), never
// docs, pixels, brush or color. Home JSON format `workspace-v1`, never
// `.spws` read/written/named (clean-room).
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.openclip.R
import java.io.File

// ---------- CommandBar store ----------

const val COMMANDBAR_FILE = "commandbar.json"

class CommandBarStore(appContext: Context) {
    private val file = File(appContext.applicationContext.filesDir, COMMANDBAR_FILE)

    // Slice 7: true when a present file failed to parse (factory
    // presets kept). The host posts prefs_reset_hint once per session.
    var loadFallback = false
        private set

    val order = mutableStateListOf(
        "save", "undo", "redo", "export", "fullscreen"
    )
    val visible = mutableStateMapOf(
        "save" to true, "undo" to true, "redo" to true,
        "export" to true, "fullscreen" to true
    )

    fun setVisible(action: String, on: Boolean) {
        visible[action] = on
        schedule()
    }

    fun move(action: String, dir: Int) {
        val i = order.indexOf(action)
        val j = i + dir
        if (i < 0 || j !in order.indices) return
        order.removeAt(i)
        order.add(j, action)
        schedule()
    }

    fun shownActions(): List<String> = order.filter { visible[it] == true }

    // Slice 7: only touch disk when an edit is actually pending — an
    // unconditional write here used to clobber the file on every pause
    // (including a test-only corrupt write ahead of recreate()).
    fun flush() {
        handler.removeCallbacks(saveRun)
        if (savePending) {
            savePending = false
            writeFile()
        }
    }

    private var savePending = false
    private val handler = android.os.Handler(
        android.os.Looper.getMainLooper()
    )
    private val saveRun = Runnable {
        savePending = false
        writeFile()
    }

    private fun schedule() {
        if (!savePending) {
            savePending = true
            handler.postDelayed(saveRun, 500)
        }
    }

    private fun writeFile() {
        try {
            val sb = StringBuilder("{\"version\":1,\"order\":[")
            order.forEachIndexed { i, a ->
                if (i > 0) sb.append(',')
                sb.append('"').append(a).append('"')
            }
            sb.append("],\"hidden\":[")
            var first = true
            for (a in order) {
                if (visible[a] != true) {
                    if (!first) sb.append(',')
                    first = false
                    sb.append('"').append(a).append('"')
                }
            }
            sb.append("]}")
            file.writeText(sb.toString())
        } catch (_: Exception) {
        }
    }

    private fun readFile() {
        try {
            if (!file.exists()) return
            val t = file.readText()
            val orderRe = Regex("\"order\"\\s*:\\s*\\[([^\\]]*)\\]")
            val hiddenRe = Regex("\"hidden\"\\s*:\\s*\\[([^\\]]*)\\]")
            val known = setOf("save", "undo", "redo", "export", "fullscreen")
            val parsed = orderRe.find(t)?.groupValues?.get(1)
                ?.split(",")?.mapNotNull {
                    it.trim().trim('"').takeIf { s -> s in known }
                } ?: run {
                    loadFallback = true
                    return
                }
            if (parsed.size != known.size) {
                loadFallback = true
                return
            }
            order.clear()
            order.addAll(parsed)
            val hidden = hiddenRe.find(t)?.groupValues?.get(1)
                ?.split(",")?.map { it.trim().trim('"') }?.toSet()
                ?: emptySet()
            for (a in known) visible[a] = a !in hidden
        } catch (_: Exception) {
        }
    }

    init {
        readFile()
    }
}

// ---------- CommandBar UI ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CommandBar(
    store: CommandBarStore,
    labels: Map<String, String>,
    onAction: (String) -> Unit,
    onEditHint: () -> Unit,
    modifier: Modifier = Modifier,
    // V10: hoisted undo/redo availability (ToolPalette writes). Tapping
    // an unavailable action posts a status hint instead of dying silent.
    undoAvail: UndoAvailability? = null,
    onDisabledHint: (String) -> Unit = {},
    // Slice 7: collapse affordance (phone pastille, §5). Expanded by
    // default so the 1-tap save/undo/redo contract (slice 6) holds.
    onCollapse: () -> Unit = {}
) {
    val editMode = remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    fun availFor(action: String): Boolean = when (action) {
        "undo" -> undoAvail?.canUndo ?: true
        "redo" -> undoAvail?.canRedo ?: true
        else -> true
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (editMode.value) Modifier else Modifier.height(48.dp))
            .background(Color(0xFF1A1A20))
            .testTag("command-bar")
    ) {
        val cmdScroll = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(cmdScroll)
                .scrollFade("command-bar-row", cmdScroll, edgeColor = Color(0xFF1A1A20))
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        editMode.value = !editMode.value
                        onEditHint()
                    }
                )
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (action in store.shownActions()) {
                val avail = availFor(action)
                // V10: unavailable undo/redo reads as hint (never white)
                // and announces its state; the tap still reports a hint.
                val unavailableDesc = when (action) {
                    "undo" -> if (!avail) ctx.getString(R.string.undo_empty) else null
                    "redo" -> if (!avail) ctx.getString(R.string.redo_empty) else null
                    else -> null
                }
                TextButton(
                    onClick = {
                        if (!avail) onDisabledHint(action)
                        else onAction(action)
                    },
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("cmdbar-$action")
                        .then(
                            if (unavailableDesc != null) {
                                Modifier.semantics {
                                    contentDescription = unavailableDesc
                                }
                            } else Modifier
                        )
                ) {
                    Text(
                        labels[action] ?: action,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (avail) Color(0xFFECECF1) else Color(0xFF787888),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (avail) Modifier
                        else Modifier.graphicsLayer { alpha = 0.6f }
                    )
                }
            }
            TextButton(
                onClick = { editMode.value = !editMode.value },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("cmdbar-edit-toggle")
            ) {
                Text(
                    "⋯",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF787888),
                    maxLines = 1
                )
            }
            TextButton(
                onClick = onCollapse,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("cmdbar-collapse")
            ) {
                Text(
                    "⌄",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF787888),
                    maxLines = 1
                )
            }
        }
        if (editMode.value) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("cmdbar-edit")
            ) {
                Text(
                    stringResource(R.string.cmdbar_edit),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF787888),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
                for (action in store.order) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = store.visible[action] == true,
                            onClick = {
                                store.setVisible(
                                    action, store.visible[action] != true
                                )
                            },
                            label = {
                                Text(
                                    labels[action] ?: action,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            modifier = Modifier.testTag("cmdbar-vis-$action")
                        )
                        TextButton(
                            onClick = { store.move(action, -1) },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("cmdbar-up-$action")
                        ) {
                            Text("↑")
                        }
                        TextButton(
                            onClick = { store.move(action, 1) },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("cmdbar-down-$action")
                        ) {
                            Text("↓")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Slice 7 collapsed pastille: one 48dp tap restores the command bar.
 */
@Composable
fun CommandBarCollapsed(
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Color(0xFF1A1A20))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        TextButton(
            onClick = onExpand,
            modifier = Modifier
                .size(48.dp)
                .testTag("cmdbar-collapsed")
        ) {
            Text(
                "⌃",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF90B8FF),
                maxLines = 1
            )
        }
    }
}

// ---------- Workspace store ----------

const val WORKSPACES_FILE = "workspaces.json"

data class WorkspacePose(
    val dockOpen: Boolean,
    val dockTab: Int,
    val timelineOpen: Boolean,
    val navMode: Boolean,
    val commandBarVisible: Boolean,
    val commandBarOrder: List<String>
)

class WorkspaceStore(appContext: Context) {
    private val dir = appContext.applicationContext.filesDir
    private val file = File(dir, WORKSPACES_FILE)

    // Slice 7: true when the last load fell back to factory presets.
    var loadFallback = false
        private set

    val spaces = mutableStateMapOf<String, WorkspacePose>()
    val lastWorkspace = mutableStateOf<String?>(null)

    fun defaultPose(draw: Boolean): WorkspacePose = WorkspacePose(
        dockOpen = true,
        dockTab = 0,
        timelineOpen = !draw,
        navMode = false,
        commandBarVisible = true,
        commandBarOrder = listOf("save", "undo", "redo", "export", "fullscreen")
    )

    fun seed(drawName: String, animName: String) {
        if (spaces.isEmpty()) {
            spaces[drawName] = defaultPose(true)
            spaces[animName] = defaultPose(false)
            writeFile()
        }
    }

    fun saveAs(name: String, pose: WorkspacePose): Boolean {
        val n = name.trim()
        if (n.isEmpty() || n.length > 48) return false
        spaces[n] = pose
        lastWorkspace.value = n
        writeFile()
        return true
    }

    fun delete(name: String): Boolean {
        if (!spaces.containsKey(name)) return false
        spaces.remove(name)
        if (lastWorkspace.value == name) lastWorkspace.value = null
        writeFile()
        return true
    }

    fun rename(old: String, newName: String): Boolean {
        val n = newName.trim()
        if (!spaces.containsKey(old) || n.isEmpty() || n.length > 48) return false
        if (n != old && spaces.containsKey(n)) return false
        val pose = spaces.remove(old) ?: return false
        spaces[n] = pose
        if (lastWorkspace.value == old) lastWorkspace.value = n
        writeFile()
        return true
    }

    fun flush() = writeFile()

    private fun writeFile() {
        try {
            val sb = StringBuilder("{\"version\":1,\"last\":")
            val last = lastWorkspace.value
            if (last == null) sb.append("null") else sb.append('"').append(esc(last)).append('"')
            sb.append(",\"spaces\":{")
            var first = true
            for ((k, p) in spaces) {
                if (!first) sb.append(',')
                first = false
                sb.append('"').append(esc(k)).append("\":{\"dockOpen\":")
                    .append(p.dockOpen).append(",\"dockTab\":").append(p.dockTab)
                    .append(",\"timelineOpen\":").append(p.timelineOpen)
                    .append(",\"navMode\":").append(p.navMode)
                    .append(",\"commandBarVisible\":").append(p.commandBarVisible)
                    .append(",\"order\":[")
                p.commandBarOrder.forEachIndexed { i, a ->
                    if (i > 0) sb.append(',')
                    sb.append('"').append(a).append('"')
                }
                sb.append("]}")
            }
            sb.append("}}")
            file.writeText(sb.toString())
        } catch (_: Exception) {
        }
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    fun load(drawName: String, animName: String) {
        try {
            if (!file.exists()) {
                seed(drawName, animName)
                return
            }
            val t = file.readText()
            if (!t.contains("\"version\":1")) throw IllegalArgumentException("version")
            spaces.clear()
            val spacesBody = Regex("\"spaces\"\\s*:\\s*\\{(.*)\\}\\s*\\}")
                .find(t)?.groupValues?.get(1) ?: throw IllegalArgumentException("spaces")
            val entryRe = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\\{([^\\}]*)\\}")
            val known = setOf("save", "undo", "redo", "export", "fullscreen")
            for (m in entryRe.findAll(spacesBody)) {
                val key = m.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
                val body = m.groupValues[2]
                fun bool(k: String, d: Boolean): Boolean {
                    val v = Regex("\"$k\"\\s*:\\s*(true|false)").find(body)
                        ?.groupValues?.get(1) ?: return d
                    return v == "true"
                }
                fun int(k: String, d: Int): Int =
                    Regex("\"$k\"\\s*:\\s*(-?\\d+)").find(body)
                        ?.groupValues?.get(1)?.toIntOrNull() ?: d
                val ord = Regex("\"order\"\\s*:\\s*\\[([^\\]]*)\\]").find(body)
                    ?.groupValues?.get(1)?.split(",")
                    ?.mapNotNull { it.trim().trim('"').takeIf { s -> s in known } }
                    ?.takeIf { it.size == known.size }
                    ?: listOf("save", "undo", "redo", "export", "fullscreen")
                spaces[key] = WorkspacePose(
                    dockOpen = bool("dockOpen", true),
                    dockTab = int("dockTab", 0).coerceIn(0, 1),
                    timelineOpen = bool("timelineOpen", false),
                    navMode = bool("navMode", false),
                    commandBarVisible = bool("commandBarVisible", true),
                    commandBarOrder = ord
                )
            }
            if (spaces.isEmpty()) throw IllegalArgumentException("empty")
            val last = Regex("\"last\"\\s*:\\s*(null|\"((?:[^\"\\\\]|\\\\.)*)\")")
                .find(t)?.let {
                    if (it.groupValues[1] == "null") null
                    else it.groupValues[2].replace("\\\"", "\"").replace("\\\\", "\\")
                }
            lastWorkspace.value = last?.takeIf { spaces.containsKey(it) }
        } catch (_: Exception) {
            spaces.clear()
            lastWorkspace.value = null
            loadFallback = true
            seed(drawName, animName)
        }
    }

    init {
        // Seeded lazily once resource names are known (see seed/load).
    }
}

@Composable
fun WorkspaceDialog(
    store: WorkspaceStore,
    currentPose: WorkspacePose,
    drawFallback: String,
    onApply: (String, WorkspacePose) -> Unit,
    onDismiss: () -> Unit
) {
    val nameField = remember { mutableStateOf("") }
    val renameTarget = remember { mutableStateOf<String?>(null) }
    val renameField = remember { mutableStateOf("") }
    val error = remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.workspace_save_title),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedTextField(
                        value = nameField.value,
                        onValueChange = {
                            nameField.value = it.take(48)
                            error.value = null
                        },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("workspace-name")
                    )
                    TextButton(
                        onClick = {
                            val ok = store.saveAs(nameField.value, currentPose)
                            if (ok) {
                                onApply(nameField.value.trim(), currentPose)
                            } else {
                                error.value = nameField.value
                            }
                        },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("workspace-save")
                    ) {
                        Text(stringResource(R.string.ok))
                    }
                }
                if (error.value != null) {
                    Text(
                        stringResource(R.string.workspace_invalid),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFE08080)
                    )
                }
                for (wsName in store.spaces.keys.sorted()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(
                            onClick = {
                                val pose = store.spaces[wsName] ?: return@TextButton
                                onApply(wsName, pose)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("workspace-apply-$wsName")
                        ) {
                            Text(
                                wsName,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (renameTarget.value == wsName) {
                            OutlinedTextField(
                                value = renameField.value,
                                onValueChange = {
                                    renameField.value = it.take(48)
                                },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("workspace-rename-field")
                            )
                            TextButton(
                                onClick = {
                                    if (store.rename(wsName, renameField.value)) {
                                        renameTarget.value = null
                                    }
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("workspace-rename-ok-$wsName")
                            ) {
                                Text("✓")
                            }
                        } else {
                            TextButton(
                                onClick = {
                                    renameTarget.value = wsName
                                    renameField.value = wsName
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("workspace-rename-$wsName")
                            ) {
                                Text("✎")
                            }
                            TextButton(
                                onClick = {
                                    val wasCurrent =
                                        store.lastWorkspace.value == wsName
                                    store.delete(wsName)
                                    if (wasCurrent) {
                                        val fb = store.spaces[drawFallback]
                                        if (fb != null) onApply(drawFallback, fb)
                                    }
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("workspace-delete-$wsName")
                            ) {
                                Text("🗑")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("workspace-close")
            ) {
                Text(stringResource(R.string.ok))
            }
        }
    )
}
