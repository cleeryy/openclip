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

    companion object {
        private const val DEFAULT_BRUSH =
            """{"radius":8,"hardness":0.7,"opaque":1.0,"spacing":0.15,"color":"#141414"}"""
    }

    private val engineWidth: Int
    private val engineHeight: Int
    private var handle: Long = 0
    private var strokeId = 0L
    private val queue = ArrayDeque<QueuedPoint>()
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

    // M2: feed a .myb document to the engine; false = malformed, brush kept.
    fun loadMyb(xml: String): Boolean =
        if (handle != 0L) EngineHost.nLoadMyb(handle, xml) else false

    fun addLayer() = if (handle != 0L) EngineHost.nAddLayer(handle, "Layer") else -1

    fun layerCount() = if (handle != 0L) EngineHost.nLayerCount(handle) else 0

    fun setActiveLayer(index: Int) = if (handle != 0L) EngineHost.nSetActiveLayer(handle, index) else -1

    fun undo(): Boolean = if (handle != 0L) EngineHost.nUndo(handle).also { if (it) needsComposite = true } else false

    fun redo(): Boolean = if (handle != 0L) EngineHost.nRedo(handle).also { if (it) needsComposite = true } else false

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
