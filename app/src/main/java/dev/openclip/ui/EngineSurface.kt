// OpenClip M1 — canvas view: SurfaceView hosting the render thread.
// Input (stylus/touch) is captured on the UI thread and queued; stroke
// replay and compositing happen on the render thread only, per the
// threading note in ARCHITECTURE.md. Phase 2 replaces the full-canvas
// IntArray composite with a Direct/AndroidHardwareBuffer image path.
package dev.openclip.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import android.view.SurfaceHolder
import dev.openclip.engine.EngineHost
import java.util.concurrent.atomic.AtomicBoolean

class EngineSurface(context: Context) : SurfaceView(context) {

    private data class QueuedPoint(
        val x: Float,
        val y: Float,
        val pressure: Float,
        val action: Int
    )

    // M3: a named filter application request. Processed on the render
    // thread only (ARCHITECTURE.md threading); the result is posted back
    // to the caller on the UI thread.
    private data class FilterRequest(
        val name: String,
        val paramsJson: String,
        val onResult: (Boolean) -> Unit
    )

    companion object {
        private const val DEFAULT_BRUSH =
            """{"radius":8,"hardness":0.7,"opaque":1.0,"spacing":0.15,"color":"#141414"}"""
    }

    private val engineWidth: Int
    private val engineHeight: Int
    private var handle: Long = 0
    private var strokeId = 0L
    private val queue = ArrayDeque<QueuedPoint>()
    private val filterQueue = ArrayDeque<FilterRequest>()
    private val running = AtomicBoolean(false)
    private var needsComposite = true

    // M2: invoked (posted to the UI thread) at each stroke end so panels
    // can refresh real undo/redo state. Null = no listener.
    var onStrokeChanged: (() -> Unit)? = null

    init {
        // Phase 1: canvas lives at fixed reference resolution and is
        // letterboxed into the view; pan/zoom comes with the M5 tool work.
        engineWidth = 2048
        engineHeight = 1536
    }

    fun start(dpi: Float) {
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (handle == 0L) {
                    handle = EngineHost.nInit(engineWidth, engineHeight, dpi)
                    setBrush(DEFAULT_BRUSH)
                }
                needsComposite = true
                running.set(true)
                Thread(::renderLoop, "openclip-render").apply {
                    isDaemon = true
                    start()
                }
            }

            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, he: Int) {
                needsComposite = true
            }

            override fun surfaceDestroyed(h: SurfaceHolder) {
                running.set(false)
            }
        })
    }

    fun dispose() {
        running.set(false)
        if (handle != 0L) EngineHost.nDispose(handle)
        handle = 0
    }

    fun setBrush(json: String) {
        if (handle != 0L) EngineHost.nSetBrush(handle, json)
    }

    // M5: feed a .myb document to the engine; false = malformed, brush kept.
    fun loadMyb(xml: String): Boolean =
        if (handle != 0L) EngineHost.nLoadMyb(handle, xml) else false

    // M5 layer system: thin wrappers; the engine owns truth, the panel
    // pushes through these exact symbols. Each op marks the composite
    // dirty on success so the SurfaceView re-renders.
    fun addLayerNamed(name: String) =
        if (handle != 0L) EngineHost.nAddLayer(handle, name) else -1

    fun addLayer() = addLayerNamed("Layer")

    fun addGroup(name: String) =
        if (handle != 0L) EngineHost.nAddGroup(handle, name) else -1

    fun layerState(): String =
        if (handle != 0L) EngineHost.nLayerState(handle)
        else "{\"active\":0,\"maskTarget\":-1,\"layers\":[]}"

    fun setLayerVisibility(index: Int, visible: Boolean) =
        if (handle != 0L) EngineHost.nSetLayerVisibility(handle, index, visible)
            .also { if (it) needsComposite = true } else false

    fun setLayerOpacity(index: Int, opacity: Float) =
        if (handle != 0L) EngineHost.nSetLayerOpacity(handle, index, opacity)
            .also { if (it) needsComposite = true } else false

    fun setLayerBlend(index: Int, name: String) =
        if (handle != 0L) EngineHost.nSetLayerBlend(handle, index, name)
            .also { if (it) needsComposite = true } else false

    fun setLayerName(index: Int, name: String) =
        if (handle != 0L) EngineHost.nSetLayerName(handle, index, name) else false

    fun layerOp(op: EngineSurface.() -> Unit) = runOnEngine(op)

    // M6 vector layers + rulers (thin wrappers over the exact symbols).
    fun addVectorLayer(name: String) =
        if (handle != 0L) EngineHost.nAddVectorLayer(handle, name)
            .also { if (it >= 0) needsComposite = true } else -1

    fun setVectorMode(index: Int, on: Boolean) =
        if (handle != 0L) EngineHost.nSetVectorMode(handle, index, on)
            .also { if (it) needsComposite = true } else false

    fun appendVectorPath(index: Int, pathJson: String) =
        if (handle != 0L) EngineHost.nAppendVectorPath(handle, index, pathJson)
            .also { if (it >= 0) needsComposite = true } else -1

    fun vectorEraser(index: Int, pathJson: String) =
        if (handle != 0L) EngineHost.nVectorEraser(handle, index, true, pathJson)
            .also { if (it) needsComposite = true } else false

    fun undoVectorPath(index: Int) =
        if (handle != 0L) EngineHost.nUndoVector(handle, index)
            .also { if (it) needsComposite = true } else false

    fun setRulers(kindsJson: String) =
        if (handle != 0L) EngineHost.nSetRulers(handle, kindsJson) else false

    // M7 frames + onion skin (thin wrappers over the exact M7 symbols;
    // fps stays app-side — no engine symbol — the timeline owns it).
    fun frameCount() = if (handle != 0L) EngineHost.nFrameCount(handle) else 0

    fun addFrame() =
        if (handle != 0L) EngineHost.nAddFrame(handle)
            .also { if (it >= 0) needsComposite = true } else -1

    fun duplicateFrame() =
        if (handle != 0L) EngineHost.nDuplicateFrame(handle)
            .also { if (it >= 0) needsComposite = true } else -1

    fun deleteFrame(index: Int) =
        if (handle != 0L) EngineHost.nDeleteFrame(handle, index)
            .also { if (it) needsComposite = true } else false

    fun setFrame(index: Int) =
        if (handle != 0L) EngineHost.nSetFrame(handle, index)
            .also { if (it) needsComposite = true } else false

    fun frameOp(op: EngineSurface.() -> Unit) = runOnEngine(op)

    fun setOnion(previous: Boolean, next: Boolean, strength: Float) {
        if (handle == 0L || !running.get()) return
        synchronized(engineQueue) {
            engineQueue.add(
                EngineOp {
                    if (EngineHost.nSetOnion(handle, previous, next, strength))
                        needsComposite = true
                }
            )
        }
    }

    // M7 thumbnails: pulled on the render thread (engine state stays
    // single-thread), full-canvas nFrameCompositeInto downscaled to 96px
    // wide app-side, result posted on the UI thread. Null = no engine.
    private data class ThumbRequest(
        val index: Int,
        val onResult: (Bitmap?) -> Unit,
        val full: Boolean = false
    )
    private val thumbQueue = ArrayDeque<ThumbRequest>()

    fun pullFrameThumbnail(index: Int, onResult: (Bitmap?) -> Unit) {
        if (handle == 0L || !running.get()) { post { onResult(null) }; return }
        synchronized(thumbQueue) { thumbQueue.add(ThumbRequest(index, onResult)) }
    }

    // M7b export: full-res frame pull on the render thread (same queue
    // discipline as thumbnails, no downscale). Null = no engine.
    fun pullFrameFull(index: Int, onResult: (Bitmap?) -> Unit) {
        if (handle == 0L || !running.get()) { post { onResult(null) }; return }
        synchronized(thumbQueue) {
            thumbQueue.add(ThumbRequest(index, onResult, full = true))
        }
    }

    private fun drainThumbQueue() {
        val drained: List<ThumbRequest>
        synchronized(thumbQueue) {
            drained = thumbQueue.toList()
            thumbQueue.clear()
        }
        for (req in drained) {
            val bmp: Bitmap? = try {
                val px = IntArray(engineWidth * engineHeight)
                val n = EngineHost.nFrameCompositeInto(
                    handle, req.index, px, engineWidth, engineHeight
                )
                if (n <= 0) {
                    null
                } else {
                    val full = Bitmap.createBitmap(
                        engineWidth, engineHeight, Bitmap.Config.ARGB_8888
                    )
                    full.setPixels(px, 0, engineWidth, 0, 0, engineWidth, engineHeight)
                    if (req.full) {
                        full
                    } else {
                        val tw = 96
                        val th = (96f * engineHeight / engineWidth).toInt().coerceAtLeast(1)
                        val small = Bitmap.createScaledBitmap(full, tw, th, true)
                        full.recycle()
                        small
                    }
                }
            } catch (_: Exception) {
                null
            }
            post { req.onResult(bmp) }
        }
    }

    fun canvasWidth(): Int = engineWidth
    fun canvasHeight(): Int = engineHeight

    fun moveLayer(from: Int, to: Int) =
        if (handle != 0L) EngineHost.nMoveLayer(handle, from, to)
            .also { if (it) needsComposite = true } else false

    fun appendChild(parent: Int, name: String) =
        if (handle != 0L) EngineHost.nAppendChild(handle, parent, name)
            .also { if (it >= 0) needsComposite = true } else -1

    fun setLayerClipped(index: Int, clipped: Boolean) =
        if (handle != 0L) EngineHost.nSetLayerClipped(handle, index, clipped)
            .also { if (it) needsComposite = true } else false

    fun addLayerMask(index: Int) =
        if (handle != 0L) EngineHost.nAddLayerMask(handle, index)
            .also { if (it) needsComposite = true } else false

    fun maskTarget(index: Int, onMask: Boolean) =
        if (handle != 0L) EngineHost.nMaskTarget(handle, index, onMask) else false

    fun layerCount() = if (handle != 0L) EngineHost.nLayerCount(handle) else 0

    fun setActiveLayer(index: Int) = if (handle != 0L) EngineHost.nSetActiveLayer(handle, index) else -1

    fun undo(): Boolean = if (handle != 0L) EngineHost.nUndo(handle).also { if (it) needsComposite = true } else false

    fun redo(): Boolean = if (handle != 0L) EngineHost.nRedo(handle).also { if (it) needsComposite = true } else false

    // M4: document save/load run on the render thread like everything
    // else that touches engine state (ARCHITECTURE.md threading). The
    // SDK/SAF layer copies bytes to a real path first; the engine only
    // ever sees a plain filesystem path. onResult posts on the UI thread.
    // M5: every layer op funnels through runOnEngine so panel actions
    // (visibility/opacity/blend/rename/move/mask/clip) execute on the
    // render thread like strokes and filters — never on the UI thread.
    private data class EngineOp(val run: () -> Unit)
    private val engineQueue = ArrayDeque<EngineOp>()

    private fun runOnEngine(op: EngineSurface.() -> Unit) {
        if (handle == 0L || !running.get()) return
        synchronized(engineQueue) { engineQueue.add(EngineOp { op() }) }
    }

    private fun drainEngineQueue() {
        val drained: List<EngineOp>
        synchronized(engineQueue) {
            drained = engineQueue.toList()
            engineQueue.clear()
        }
        for (op in drained) {
            try {
                op.run()
            } catch (_: Exception) {
            }
        }
    }

    private data class DocRequest(val kind: String, val path: String, val format: String, val onResult: (Boolean) -> Unit)
    private val docQueue = ArrayDeque<DocRequest>()

    fun loadFrom(path: String, onResult: (Boolean) -> Unit) {
        if (handle == 0L || !running.get()) { post { onResult(false) }; return }
        synchronized(docQueue) { docQueue.add(DocRequest("load", path, "", onResult)) }
    }

    fun saveTo(path: String, format: String, onResult: (Boolean) -> Unit) {
        if (handle == 0L || !running.get()) { post { onResult(false) }; return }
        synchronized(docQueue) { docQueue.add(DocRequest("save", path, format, onResult)) }
    }

    private fun drainDocQueue() {
        val drained: List<DocRequest>
        synchronized(docQueue) {
            drained = docQueue.toList()
            docQueue.clear()
        }
        for (r in drained) {
            val ok = when (r.kind) {
                "load" -> {
                    val b = EngineHost.nLoadDocument(handle, r.path)
                    if (b) needsComposite = true
                    b
                }
                "save" -> EngineHost.nSaveDocument(handle, r.path, r.format)
                else -> false
            }
            post { r.onResult(ok) }
        }
    }

    // M3: enqueue a named filter over the full active layer. runs on the
    // render thread like strokes (nCompositeInto is render-thread only);
    // needsComposite is set on success so the UI refreshes. onResult is
    // posted on the UI thread (SurfaceView.post is UI-thread safe).
    fun applyFilter(name: String, paramsJson: String, onResult: (Boolean) -> Unit) {
        if (handle == 0L || !running.get()) {
            post { onResult(false) }
            return
        }
        synchronized(filterQueue) {
            filterQueue.add(FilterRequest(name, paramsJson, onResult))
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (handle == 0L || !running.get()) return false
        // Digitizers report 0..1; finger touch reports 0..1 too but weaker —
        // uniform scale of 2 is applied then clamped (M2 adds a curve editor).
        val pressure = (event.getPressure(0) * 2f).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                strokeId = EngineHost.nStrokeBegin(
                    handle, event.getX(0), event.getY(0), pressure
                )
                enqueue(event, pressure, MotionEvent.ACTION_DOWN)
            }
            MotionEvent.ACTION_MOVE -> {
                enqueue(event, pressure, MotionEvent.ACTION_MOVE)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> {
                enqueue(event, pressure, MotionEvent.ACTION_UP)
                EngineHost.nStrokeEnd(handle, strokeId)
                strokeId = 0
                notifyStrokeChanged()
            }
        }
        requestParentDisallowInterceptTouchEvent()
        return true
    }

    private fun enqueue(event: MotionEvent, pressure: Float, action: Int) {
        synchronized(queue) {
            if (action == MotionEvent.ACTION_MOVE) {
                for (i in 0 until event.historySize) {
                    queue.add(
                        QueuedPoint(
                            event.getHistoricalX(0, i),
                            event.getHistoricalY(0, i),
                            pressure,
                            action
                        )
                    )
                }
            }
            queue.add(QueuedPoint(event.getX(0), event.getY(0), pressure, action))
        }
    }

    private fun notifyStrokeChanged() {
        val cb = onStrokeChanged ?: return
        // Called from the render thread; the listener posts to UI itself
        // if it needs Compose state. SurfaceView.post is UI-thread safe.
        post { cb() }
    }

    private fun requestParentDisallowInterceptTouchEvent() {
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun renderLoop() {
        var bitmapW = 0
        var bitmapH = 0
        val pixels = IntArray(engineWidth * engineHeight)
        while (running.get()) {
            val surface: Surface? = holder.surface
            if (surface == null || !surface.isValid) {
                Thread.sleep(8)
                continue
            }

            // Drain queued input onto the engine in received order.
            val drained: List<QueuedPoint>
            synchronized(queue) {
                drained = queue.toList()
                queue.clear()
            }
            if (drained.isNotEmpty()) {
                for (pt in drained) {
                    if (pt.action == MotionEvent.ACTION_DOWN) {
                        // Already applied at Begin; only accounting here.
                        needsComposite = true
                        continue
                    }
                    if (strokeId != 0L) {
                        EngineHost.nStrokeMove(
                            handle, strokeId, pt.x, pt.y, pt.pressure
                        )
                        needsComposite = true
                        if (pt.action == MotionEvent.ACTION_UP) {
                            EngineHost.nStrokeEnd(handle, strokeId)
                            strokeId = 0
                            notifyStrokeChanged()
                        }
                    }
                }
            }

            // M3: drain queued filter requests onto the engine in order,
            // on this same render thread, before compositing.
            val filters: List<FilterRequest>
            synchronized(filterQueue) {
                filters = filterQueue.toList()
                filterQueue.clear()
            }
            if (filters.isNotEmpty() && handle != 0L) {
                for (req in filters) {
                    val ok = try {
                        EngineHost.nApplyFilter(handle, req.name, req.paramsJson)
                    } catch (_: Exception) {
                        false
                    }
                    if (ok) needsComposite = true
                    val cb = req.onResult
                    post { cb(ok) }
                }
            }

            // M4: drain queued document save/load requests on this render
            // thread before compositing (engine state stays single-thread).
            drainDocQueue()
            // M5: drain queued panel ops on this render thread too.
            drainEngineQueue()
            // M7: drain queued thumbnail pulls (frame composites for the
            // timeline strip; engine stays render-thread only).
            drainThumbQueue()

            if (!needsComposite) {
                Thread.sleep(4)
                continue
            }
            needsComposite = false

            if (bitmapW != engineWidth || bitmapH != engineHeight) {
                bitmapW = engineWidth
                bitmapH = engineHeight
            }
            val frame = Bitmap.createBitmap(
                engineWidth, engineHeight, Bitmap.Config.ARGB_8888
            )
            EngineHost.nCompositeInto(handle, pixels, engineWidth, engineHeight)
            frame.setPixels(pixels, 0, engineWidth, 0, 0, engineWidth, engineHeight)
            val c = holder.lockCanvas()
            if (c != null) {
                c.drawBitmap(frame, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), null)
                holder.unlockCanvasAndPost(c)
            }
            frame.recycle()
        }
    }
}
