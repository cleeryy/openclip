// OpenClip — canvas view: SurfaceView hosting the render thread.
// Input (stylus/touch/mouse) is mapped from the letterboxed 4:3 paper
// viewport to engine coordinates (2048x1536) and queued to the render
// thread. All JNI calls synchronize on engineLock so UI actions and
// render-thread compositing never race.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.openclip.engine.EngineHost
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class EngineSurface(context: Context) : SurfaceView(context) {

    private data class QueuedPoint(
        val x: Float,
        val y: Float,
        val pressure: Float,
        val action: Int
    )

    private data class FilterRequest(
        val name: String,
        val paramsJson: String,
        val onResult: (Boolean) -> Unit
    )

    companion object {
        private const val DEFAULT_BRUSH =
            """{"radius":8,"hardness":0.7,"opaque":1.0,"spacing":0.15,"color":"#141414"}"""
    }

    private val engineWidth: Int = 2048
    private val engineHeight: Int = 1536
    private val engineLock = Any()
    @Volatile private var handle: Long = 0
    private var strokeId = 0L
    private val queue = ArrayDeque<QueuedPoint>()
    private val filterQueue = ArrayDeque<FilterRequest>()
    private val running = AtomicBoolean(false)
    @Volatile private var needsComposite = true

    var onStrokeChanged: (() -> Unit)? = null

    private fun computePaperRect(viewW: Int, viewH: Int): RectF {
        if (viewW <= 0 || viewH <= 0) {
            return RectF(0f, 0f, engineWidth.toFloat(), engineHeight.toFloat())
        }
        val pad = 12f
        val availW = (viewW - pad * 2f).coerceAtLeast(1f)
        val availH = (viewH - pad * 2f).coerceAtLeast(1f)
        val scale = min(availW / engineWidth.toFloat(), availH / engineHeight.toFloat())
        val drawW = engineWidth * scale
        val drawH = engineHeight * scale
        val left = (viewW - drawW) * 0.5f
        val top = (viewH - drawH) * 0.5f
        return RectF(left, top, left + drawW, top + drawH)
    }

    private fun mapToEngineX(vx: Float): Float {
        val rect = computePaperRect(width, height)
        if (rect.width() <= 1f) return vx.coerceIn(0f, engineWidth - 1f)
        return (((vx - rect.left) / rect.width()) * engineWidth)
            .coerceIn(0f, engineWidth - 1f)
    }

    private fun mapToEngineY(vy: Float): Float {
        val rect = computePaperRect(width, height)
        if (rect.height() <= 1f) return vy.coerceIn(0f, engineHeight - 1f)
        return (((vy - rect.top) / rect.height()) * engineHeight)
            .coerceIn(0f, engineHeight - 1f)
    }

    fun start(dpi: Float) {
        synchronized(engineLock) {
            if (handle == 0L) {
                handle = EngineHost.nInit(engineWidth, engineHeight, dpi)
                EngineHost.nSetBrush(handle, DEFAULT_BRUSH)
            }
        }
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                synchronized(engineLock) {
                    if (handle == 0L) {
                        handle = EngineHost.nInit(engineWidth, engineHeight, dpi)
                        EngineHost.nSetBrush(handle, DEFAULT_BRUSH)
                    }
                }
                needsComposite = true
                if (!running.getAndSet(true)) {
                    Thread(::renderLoop, "openclip-render").apply {
                        isDaemon = true
                        start()
                    }
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
        synchronized(engineLock) {
            if (handle != 0L) EngineHost.nDispose(handle)
            handle = 0
        }
    }

    fun setBrush(json: String) {
        synchronized(engineLock) {
            if (handle != 0L) EngineHost.nSetBrush(handle, json)
        }
    }

    fun loadMyb(xml: String): Boolean = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nLoadMyb(handle, xml) else false
    }

    fun addLayerNamed(name: String): Int = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nAddLayer(handle, name)
            .also { if (it >= 0) needsComposite = true } else -1
    }

    fun addLayer(): Int = addLayerNamed("Layer")

    fun addGroup(name: String): Int = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nAddGroup(handle, name)
            .also { if (it >= 0) needsComposite = true } else -1
    }

    fun layerState(): String = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nLayerState(handle)
        else "{\"active\":0,\"maskTarget\":-1,\"layers\":[]}"
    }

    fun setLayerVisibility(index: Int, visible: Boolean): Boolean = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nSetLayerVisibility(handle, index, visible)
            .also { if (it) needsComposite = true } else false
    }

    fun setLayerOpacity(index: Int, opacity: Float): Boolean = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nSetLayerOpacity(handle, index, opacity)
            .also { if (it) needsComposite = true } else false
    }

    fun setLayerBlend(index: Int, name: String): Boolean = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nSetLayerBlend(handle, index, name)
            .also { if (it) needsComposite = true } else false
    }

    fun setLayerName(index: Int, name: String): Boolean = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nSetLayerName(handle, index, name) else false
    }

    fun setLayerAdjustment(index: Int, json: String?): Boolean = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nSetLayerAdjustment(handle, index, json)
            .also { if (it) needsComposite = true } else false
    }

    fun loadGlbReference(path: String): Int = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nLoadGlbReference(handle, path)
            .also { if (it >= 0) needsComposite = true } else -1
    }

    fun setRefView(json: String): Boolean = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nSetRefView(handle, json)
            .also { if (it) needsComposite = true } else false
    }

    fun setRefVisible(on: Boolean): Boolean = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nSetRefVisible(handle, on)
            .also { if (it) needsComposite = true } else false
    }

    fun applyTone(name: String): Boolean = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nApplyTone(handle, name)
            .also {
                if (it) {
                    needsComposite = true
                    notifyStrokeChanged()
                }
            } else false
    }

    fun layerOp(op: EngineSurface.() -> Unit) = runOnEngine(op)

    fun addVectorLayer(name: String): Int = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nAddVectorLayer(handle, name)
            .also { if (it >= 0) needsComposite = true } else -1
    }

    fun setVectorMode(index: Int, on: Boolean): Boolean = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nSetVectorMode(handle, index, on)
            .also { if (it) needsComposite = true } else false
    }

    fun appendVectorPath(index: Int, pathJson: String): Int = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nAppendVectorPath(handle, index, pathJson)
            .also { if (it >= 0) needsComposite = true } else -1
    }

    fun vectorEraser(index: Int, pathJson: String): Boolean = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nVectorEraser(handle, index, true, pathJson)
            .also { if (it) needsComposite = true } else false
    }

    fun undoVectorPath(index: Int): Boolean = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nUndoVector(handle, index)
            .also { if (it) needsComposite = true } else false
    }

    fun setRulers(kindsJson: String): Boolean = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nSetRulers(handle, kindsJson) else false
    }

    fun frameCount(): Int = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nFrameCount(handle) else 0
    }

    fun addFrame(): Int = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nAddFrame(handle)
            .also { if (it >= 0) needsComposite = true } else -1
    }

    fun duplicateFrame(): Int = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nDuplicateFrame(handle)
            .also { if (it >= 0) needsComposite = true } else -1
    }

    fun deleteFrame(index: Int): Boolean = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nDeleteFrame(handle, index)
            .also { if (it) needsComposite = true } else false
    }

    fun setFrame(index: Int): Boolean = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nSetFrame(handle, index)
            .also { if (it) needsComposite = true } else false
    }

    fun frameOp(op: EngineSurface.() -> Unit) = runOnEngine(op)

    fun setOnion(previous: Boolean, next: Boolean, strength: Float) {
        synchronized(engineLock) {
            if (handle != 0L && EngineHost.nSetOnion(handle, previous, next, strength)) {
                needsComposite = true
            }
        }
    }

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
                val n = synchronized(engineLock) {
                    EngineHost.nFrameCompositeInto(
                        handle, req.index, px, engineWidth, engineHeight
                    )
                }
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

    fun moveLayer(from: Int, to: Int): Boolean = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nMoveLayer(handle, from, to)
            .also { if (it) needsComposite = true } else false
    }

    fun appendChild(parent: Int, name: String): Int = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nAppendChild(handle, parent, name)
            .also { if (it >= 0) needsComposite = true } else -1
    }

    fun setLayerClipped(index: Int, clipped: Boolean): Boolean = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nSetLayerClipped(handle, index, clipped)
            .also { if (it) needsComposite = true } else false
    }

    fun addLayerMask(index: Int): Boolean = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nAddLayerMask(handle, index)
            .also { if (it) needsComposite = true } else false
    }

    fun maskTarget(index: Int, onMask: Boolean): Boolean = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nMaskTarget(handle, index, onMask) else false
    }

    fun layerCount(): Int = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nLayerCount(handle) else 0
    }

    fun setActiveLayer(index: Int): Int = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nSetActiveLayer(handle, index) else -1
    }

    fun undo(): Boolean = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nUndo(handle).also { if (it) needsComposite = true } else false
    }

    fun redo(): Boolean = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nRedo(handle).also { if (it) needsComposite = true } else false
    }

    private data class EngineOp(val run: () -> Unit)
    private val engineQueue = ArrayDeque<EngineOp>()

    private fun runOnEngine(op: EngineSurface.() -> Unit) {
        if (handle == 0L) return
        synchronized(engineLock) {
            try {
                op()
            } catch (_: Exception) {
            }
        }
    }

    private fun drainEngineQueue() {
        val drained: List<EngineOp>
        synchronized(engineQueue) {
            drained = engineQueue.toList()
            engineQueue.clear()
        }
        for (op in drained) {
            try {
                synchronized(engineLock) { op.run() }
            } catch (_: Exception) {
            }
        }
    }

    private data class DocRequest(
        val kind: String,
        val path: String,
        val format: String,
        val onResult: (Boolean) -> Unit
    )
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
            val ok = synchronized(engineLock) {
                when (r.kind) {
                    "load" -> {
                        val b = EngineHost.nLoadDocument(handle, r.path)
                        if (b) needsComposite = true
                        b
                    }
                    "save" -> EngineHost.nSaveDocument(handle, r.path, r.format)
                    else -> false
                }
            }
            post { r.onResult(ok) }
        }
    }

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
        val rawP = event.getPressure(0)
        val pressure = (if (rawP <= 0f) 0.5f else rawP * 2f).coerceIn(0.05f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val rect = computePaperRect(width, height)
                if (width > 0 && height > 0 && !rect.contains(event.getX(0), event.getY(0))) {
                    return false
                }
                enqueue(event, pressure, MotionEvent.ACTION_DOWN)
            }
            MotionEvent.ACTION_MOVE -> {
                enqueue(event, pressure, MotionEvent.ACTION_MOVE)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> {
                enqueue(event, pressure, MotionEvent.ACTION_UP)
            }
        }
        requestParentDisallowInterceptTouchEvent()
        return true
    }

    private fun enqueue(event: MotionEvent, pressure: Float, action: Int) {
        synchronized(queue) {
            if (action == MotionEvent.ACTION_MOVE) {
                for (i in 0 until event.historySize) {
                    val hx = mapToEngineX(event.getHistoricalX(0, i))
                    val hy = mapToEngineY(event.getHistoricalY(0, i))
                    val hp = (event.getHistoricalPressure(0, i) * 2f).coerceIn(0.05f, 1f)
                    queue.add(QueuedPoint(hx, hy, hp, MotionEvent.ACTION_MOVE))
                }
            }
            val cx = mapToEngineX(event.getX(0))
            val cy = mapToEngineY(event.getY(0))
            queue.add(QueuedPoint(cx, cy, pressure, action))
        }
    }

    private fun notifyStrokeChanged() {
        val cb = onStrokeChanged ?: return
        post { cb() }
    }

    private fun requestParentDisallowInterceptTouchEvent() {
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun renderLoop() {
        val pixels = IntArray(engineWidth * engineHeight)
        val frame = Bitmap.createBitmap(
            engineWidth, engineHeight, Bitmap.Config.ARGB_8888
        )
        val borderPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = 0xFF3E3E4A.toInt()
            isAntiAlias = true
        }
        val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        while (running.get()) {
            val surface: Surface? = holder.surface
            if (surface == null || !surface.isValid) {
                Thread.sleep(8)
                continue
            }

            val drained: List<QueuedPoint>
            synchronized(queue) {
                drained = queue.toList()
                queue.clear()
            }
            if (drained.isNotEmpty()) {
                synchronized(engineLock) {
                    for (pt in drained) {
                        when (pt.action) {
                            MotionEvent.ACTION_DOWN -> {
                                if (strokeId != 0L) {
                                    EngineHost.nStrokeEnd(handle, strokeId)
                                }
                                strokeId = EngineHost.nStrokeBegin(
                                    handle, pt.x, pt.y, pt.pressure
                                )
                                needsComposite = true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                if (strokeId == 0L) {
                                    strokeId = EngineHost.nStrokeBegin(
                                        handle, pt.x, pt.y, pt.pressure
                                    )
                                } else {
                                    EngineHost.nStrokeMove(
                                        handle, strokeId, pt.x, pt.y, pt.pressure
                                    )
                                }
                                needsComposite = true
                            }
                            MotionEvent.ACTION_UP -> {
                                if (strokeId != 0L) {
                                    EngineHost.nStrokeMove(
                                        handle, strokeId, pt.x, pt.y, pt.pressure
                                    )
                                    EngineHost.nStrokeEnd(handle, strokeId)
                                    strokeId = 0L
                                    notifyStrokeChanged()
                                }
                                needsComposite = true
                            }
                        }
                    }
                }
            }

            val filters: List<FilterRequest>
            synchronized(filterQueue) {
                filters = filterQueue.toList()
                filterQueue.clear()
            }
            if (filters.isNotEmpty() && handle != 0L) {
                for (req in filters) {
                    val ok = try {
                        synchronized(engineLock) {
                            EngineHost.nApplyFilter(handle, req.name, req.paramsJson)
                        }
                    } catch (_: Exception) {
                        false
                    }
                    if (ok) {
                        needsComposite = true
                        notifyStrokeChanged()
                    }
                    val cb = req.onResult
                    post { cb(ok) }
                }
            }

            drainDocQueue()
            drainEngineQueue()
            drainThumbQueue()

            if (!needsComposite) {
                Thread.sleep(4)
                continue
            }
            needsComposite = false

            synchronized(engineLock) {
                EngineHost.nCompositeInto(handle, pixels, engineWidth, engineHeight)
            }
            frame.setPixels(pixels, 0, engineWidth, 0, 0, engineWidth, engineHeight)
            val c = holder.lockCanvas()
            if (c != null) {
                try {
                    c.drawColor(0xFF141418.toInt())
                    val dst = computePaperRect(c.width, c.height)
                    c.drawBitmap(frame, null, dst, bitmapPaint)
                    c.drawRect(dst, borderPaint)
                } finally {
                    holder.unlockCanvasAndPost(c)
                }
            }
        }
        frame.recycle()
    }
}
