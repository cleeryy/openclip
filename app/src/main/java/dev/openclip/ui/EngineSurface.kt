// OpenClip — canvas view: SurfaceView hosting the render thread.
// Input (stylus/touch/mouse) is mapped from the letterboxed 4:3 paper
// viewport to engine coordinates (2048x1536) and queued to the render
// thread. All JNI calls synchronize on engineLock so UI actions and
// render-thread compositing never race.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.openclip.R
import dev.openclip.engine.EngineHost
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class EngineSurface(context: Context) : SurfaceView(context) {

    private data class QueuedPoint(
        val x: Float,
        val y: Float,
        val pressure: Float,
        val action: Int,
        val t: Long = 0L
    )

    private data class FilterRequest(
        val name: String,
        val paramsJson: String,
        val onResult: (Boolean) -> Unit
    )

    companion object {
        private const val DEFAULT_BRUSH =
            """{"radius":8,"hardness":0.7,"opaque":1.0,"spacing":0.15,"color":"#141414"}"""

        // Slice 1 gesture thresholds (spec docs/slice1-gestures.md §2/§8).
        const val TAP_MAX_MS = 250L
        const val TAP_MAX_DP = 24f
        const val TAP_DEBOUNCE_MS = 400L
        const val DRAG_MIN_DP = 24f
        const val ROTATE_MIN_DEG = 8f
        const val EDGE_BAND_DP = 32f
        const val EDGE_SWIPE_MIN_DP = 64f
        const val LOUPE_MS = 800L
        const val HUD_TTL_MS = 800L
        const val SCALE_MIN = 0.25f
        const val SCALE_MAX = 8f
        const val EDGE_LEFT = 0
        const val EDGE_RIGHT = 1
        const val EDGE_BOTTOM = 2
    }

    private val engineWidth: Int = 2048
    private val engineHeight: Int = 1536
    private val engineLock = Any()
    @Volatile private var handle: Long = 0
    @Volatile private var navigating = false
    @Volatile private var rollbackOpen = false
    private var strokeId = 0L
    private val queue = ArrayDeque<QueuedPoint>()
    private val filterQueue = ArrayDeque<FilterRequest>()
    private val running = AtomicBoolean(false)
    @Volatile private var needsComposite = true

    var onStrokeChanged: (() -> Unit)? = null

    // ---- Slice 3 solo-render (app-side only, no new JNI) ----
    // While true the render loop skips compositing (needsComposite stays
    // armed) so a thumbnail worker hiding/restoring layer visibilities
    // never flashes the on-screen canvas. Guard + engineLock in the spec.
    @Volatile var suspendRender = false

    /** Test-only spy: successful moveLayer calls since the last reset. */
    @Volatile var moveCallCount = 0

    // ---- Slice 5 brush feeling (app-side only, overlays composite-only)
    // Cursor ring: real-size dab annulus (motor -> screen via the Slice 1
    // view matrix), dashed amber for the eraser. HUD: live size/pressure
    // pastille (Compose overlay, 10 Hz throttle, 800 ms TTL). No JNI.
    @Volatile var brushRadiusEngine = 8f
        private set
    @Volatile var brushOpaqueEngine = 1f
        private set
    @Volatile var brushIsEraser = false
        private set

    @Volatile private var hoverX = 0f
    @Volatile private var hoverY = 0f
    @Volatile private var hoverActive = false
    @Volatile private var hoverPressure = 0.6f
    @Volatile private var lastHoverArmMs = 0L

    @Volatile var hudVisible = false
    @Volatile var hudDiamPx = 0
    @Volatile var hudPressurePct = 0
    @Volatile var onHudChanged: (() -> Unit)? = null
    /** Compose-observable HUD (UI-thread writes only, via post). */
    data class HudData(val diamPx: Int, val pct: Int)
    var hudUi by mutableStateOf<HudData?>(null)
        private set
    @Volatile private var hudUntilMs = 0L
    @Volatile private var lastHudPostMs = 0L

    /** Test-only hook: ACTION_DOWN -> first stamp latency on the render thread. */
    @Volatile var lastStrokeLatencyMs: Long = -1

    data class CursorRing(
        val x: Float,
        val y: Float,
        val radiusScreen: Float,
        val eraser: Boolean,
        val dimmed: Boolean,
        val barred: Boolean
    )

    private fun parseBrushJson(json: String) {
        try {
            val r = Regex("\"radius\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)")
                .find(json)?.groupValues?.get(1)?.toFloatOrNull()
            if (r != null) brushRadiusEngine = r.coerceIn(1f, 128f)
            val o = Regex("\"opaque\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)")
                .find(json)?.groupValues?.get(1)?.toFloatOrNull()
            if (o != null) brushOpaqueEngine = o.coerceIn(0f, 1f)
            val mode = Regex("\"mode\"\\s*:\\s*\"([^\"]*)\"")
                .find(json)?.groupValues?.get(1)
            if (mode == "eraser") brushIsEraser = true
            else if (mode == "brush") brushIsEraser = false
        } catch (_: Exception) {
        }
    }

    /** Active layer index, or -1 when it is a tracked vector layer. */
    private fun activeVectorLayer(): Int {
        return try {
            val st = org.json.JSONObject(layerState())
            val a = st.optInt("active", 0)
            if (vectorLayers.contains(a)) a else -1
        } catch (_: Exception) {
            -1
        }
    }

    private fun activeLayerVisible(): Boolean {
        return try {
            val st = org.json.JSONObject(layerState())
            val a = st.optInt("active", 0)
            val arr = st.optJSONArray("layers") ?: return true
            val e = arr.optJSONObject(a) ?: return true
            val s = e.optJSONObject("self") ?: e
            s.optBoolean("visible", true)
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Test-only cursor probe: the ring the render loop would draw (null
     * when masked). Radius proves the motor -> screen matrix (Slice 1).
     */
    fun cursorRingForTest(): CursorRing? {
        if (!hoverActive) return null
        if (navigating || suspendRender || navigateMode || pipetteArmed) return null
        val vw = width
        val vh = height
        if (vw <= 0 || vh <= 0) return null
        val s = fitScale(vw, vh) * viewScaleMul
        if (s <= 1e-6f) return null
        val pr = if (strokeOpenUi) hoverPressure else 0.6f
        val dab = brushRadiusEngine * (0.25f + 0.75f * pr)
        val (ex, ey) = screenToEngine(hoverX, hoverY)
        val onPaper = ex >= 0f && ex < engineWidth && ey >= 0f && ey < engineHeight
        return CursorRing(hoverX, hoverY, dab * s, brushIsEraser, !onPaper, !activeLayerVisible())
    }

    /** Test-only hover injector (the emulator has no stylus hover). */
    fun hoverForTest(x: Float, y: Float) {
        hoverX = x
        hoverY = y
        hoverPressure = 0.6f
        hoverActive = true
        needsComposite = true
    }

    fun clearHoverForTest() {
        hoverActive = false
        needsComposite = true
    }

    private fun armHoverFrame() {
        val now = SystemClock.uptimeMillis()
        if (now - lastHoverArmMs > 33) {
            lastHoverArmMs = now
            needsComposite = true
        }
    }

    /** True while a stroke is open (UI tail or engine-side). */
    fun isDrawingNow(): Boolean = strokeOpenUi || strokeId != 0L

    /**
     * Composite of a single layer for the Slice 3 thumbnail pipeline.
     * Hides every other layer, composites, downscales to a centered
     * square [thumbPx] bitmap, then restores the snapshot visibilities.
     * Returns null for hidden/failed layers (UI shows the placeholder).
     * Runs fully under engineLock; never call from the render thread.
     */
    fun soloRenderLayer(index: Int, thumbPx: Int = 48): Bitmap? {
        if (handle == 0L) return null
        suspendRender = true
        try {
            synchronized(engineLock) {
                if (handle == 0L) return null
                val vis = mutableListOf<Pair<Int, Boolean>>()
                try {
                    val root = JSONObject(layerState())
                    val arr = root.optJSONArray("layers") ?: return null
                    for (i in 0 until arr.length()) {
                        val e = arr.optJSONObject(i) ?: continue
                        val s = e.optJSONObject("self") ?: e
                        vis.add(Pair(i, s.optBoolean("visible", true)))
                    }
                } catch (_: Exception) {
                    return null
                }
                if (index < 0 || index >= vis.size || !vis[index].second) return null
                for ((i, v) in vis) {
                    if (v && i != index) EngineHost.nSetLayerVisibility(handle, i, false)
                }
                val px = IntArray(engineWidth * engineHeight)
                val n = try {
                    EngineHost.nCompositeInto(handle, px, engineWidth, engineHeight)
                } catch (_: Exception) {
                    0
                }
                for ((i, v) in vis) {
                    if (v && i != index) EngineHost.nSetLayerVisibility(handle, i, true)
                }
                needsComposite = true
                if (n <= 0) return null
                val full = Bitmap.createBitmap(
                    engineWidth, engineHeight, Bitmap.Config.ARGB_8888
                )
                full.setPixels(px, 0, engineWidth, 0, 0, engineWidth, engineHeight)
                val side = minOf(engineWidth, engineHeight)
                val sx = (engineWidth - side) / 2
                val sy = (engineHeight - side) / 2
                val square = Bitmap.createBitmap(full, sx, sy, side, side)
                full.recycle()
                val small = Bitmap.createScaledBitmap(square, thumbPx, thumbPx, true)
                square.recycle()
                return small
            }
        } finally {
            suspendRender = false
            needsComposite = true
        }
    }

    // ---- Slice 1 view matrix (app-side only, never engine pixels) ----
    @Volatile private var viewScaleMul = 1f
    @Volatile private var viewAngleDeg = 0f
    @Volatile private var viewOffX = 0f
    @Volatile private var viewOffY = 0f
    @Volatile var navigateMode = false
    @Volatile var edgeBottomEnabled = false
    @Volatile private var lastNavTime = 0L

    // ---- Slice 2 pipette (app-side only, no JNI): when armed, the next
    // canvas tap samples the displayed composite instead of drawing.
    // Tap mode samples once then the host disarms; loupe mode streams
    // MOVE samples and the host finalizes on UP. Multitouch/navigation
    // still wins over an armed pipette (no sample, stays armed).
    @Volatile var pipetteArmed = false
    @Volatile var pipetteLoupe = false
    var onPipetteDown: ((vx: Float, vy: Float) -> Unit)? = null
    var onPipetteMove: ((vx: Float, vy: Float) -> Unit)? = null
    var onPipetteUp: (() -> Unit)? = null
    @Volatile private var currentFrameIdx = 0

    @Volatile var onGestureUndo: (() -> Unit)? = null
    @Volatile var onGestureRedo: (() -> Unit)? = null
    @Volatile var onEdgeSwipe: ((edge: Int) -> Unit)? = null
    @Volatile var onViewChanged: (() -> Unit)? = null

    fun viewOffset(): Pair<Float, Float> = Pair(viewOffX, viewOffY)
    fun viewScale(): Float = viewScaleMul
    fun viewAngle(): Float = viewAngleDeg
    fun viewZoomPercent(): Int = (viewScaleMul * 100f).toInt()
    fun isLoupeVisible(): Boolean =
        SystemClock.uptimeMillis() - lastNavTime < LOUPE_MS

    fun resetView() {
        viewScaleMul = 1f
        viewAngleDeg = 0f
        viewOffX = 0f
        viewOffY = 0f
        needsComposite = true
        val cb = onViewChanged
        if (cb != null) post { cb() }
    }

    private fun fitScale(viewW: Int, viewH: Int): Float {
        if (viewW <= 0 || viewH <= 0) return 1f
        val pad = 12f
        val availW = (viewW - pad * 2f).coerceAtLeast(1f)
        val availH = (viewH - pad * 2f).coerceAtLeast(1f)
        return min(availW / engineWidth.toFloat(), availH / engineHeight.toFloat())
    }

    private fun computePaperRect(viewW: Int, viewH: Int): RectF {
        if (viewW <= 0 || viewH <= 0) {
            return RectF(0f, 0f, engineWidth.toFloat(), engineHeight.toFloat())
        }
        val fit = fitScale(viewW, viewH)
        val drawW = engineWidth * fit * viewScaleMul
        val drawH = engineHeight * fit * viewScaleMul
        val cx = viewW * 0.5f + viewOffX
        val cy = viewH * 0.5f + viewOffY
        return RectF(cx - drawW * 0.5f, cy - drawH * 0.5f, cx + drawW * 0.5f, cy + drawH * 0.5f)
    }

    private fun clampView(viewW: Int, viewH: Int) {
        viewScaleMul = viewScaleMul.coerceIn(SCALE_MIN, SCALE_MAX)
        var a = viewAngleDeg % 360f
        if (a <= -180f) a += 360f
        if (a > 180f) a -= 360f
        viewAngleDeg = a
        if (viewW > 0 && viewH > 0) {
            val fit = fitScale(viewW, viewH)
            val drawW = engineWidth * fit * viewScaleMul
            val drawH = engineHeight * fit * viewScaleMul
            // Keep at least 1/4 of the sheet reachable on screen.
            val cx = (viewW * 0.5f + viewOffX).coerceIn(-drawW / 4f, viewW + drawW / 4f)
            val cy = (viewH * 0.5f + viewOffY).coerceIn(-drawH / 4f, viewH + drawH / 4f)
            viewOffX = cx - viewW * 0.5f
            viewOffY = cy - viewH * 0.5f
        }
    }

    /** Screen -> engine through the inverse view matrix (rotation-aware). */
    private fun viewToEngine(vx: Float, vy: Float, viewW: Int, viewH: Int): Pair<Float, Float> {
        val (ex, ey) = screenToEngine(vx, vy)
        return Pair(
            ex.coerceIn(0f, engineWidth - 1f),
            ey.coerceIn(0f, engineHeight - 1f)
        )
    }

    /** Unclamped inverse mapping (for pipette on-paper tests). */
    fun screenToEngine(vx: Float, vy: Float): Pair<Float, Float> {
        val viewW = width
        val viewH = height
        if (viewW <= 0 || viewH <= 0) return Pair(0f, 0f)
        val s = fitScale(viewW, viewH) * viewScaleMul
        if (s <= 1e-6f) return Pair(0f, 0f)
        val cx = viewW * 0.5f + viewOffX
        val cy = viewH * 0.5f + viewOffY
        val dx = vx - cx
        val dy = vy - cy
        val r = Math.toRadians(viewAngleDeg.toDouble())
        val cosA = cos(r).toFloat()
        val sinA = sin(r).toFloat()
        // Inverse of canvas rotate(a): rotate by -a.
        val rx = dx * cosA + dy * sinA
        val ry = -dx * sinA + dy * cosA
        return Pair(rx / s + engineWidth * 0.5f, ry / s + engineHeight * 0.5f)
    }

    /** True when the view point lands on engine paper (pipette hit). */
    fun isOnPaper(vx: Float, vy: Float): Boolean {
        val (ex, ey) = screenToEngine(vx, vy)
        return ex >= 0f && ex < engineWidth && ey >= 0f && ey < engineHeight
    }

    /**
     * Sample the displayed composite at a view point (Slice 2 pipette).
     * Calls back on the UI thread with an #RRGGBB hex, or (null, false)
     * when the point misses the paper. Falls back to frame 0 when the
     * tracked frame index yields nothing (e.g. after a document load).
     */
    fun sampleCompositeAt(vx: Float, vy: Float, cb: (hex: String?, onPaper: Boolean) -> Unit) {
        if (!isOnPaper(vx, vy)) {
            post { cb(null, false) }
            return
        }
        val (ex, ey) = screenToEngine(vx, vy)
        val px = ex.toInt().coerceIn(0, engineWidth - 1)
        val py = ey.toInt().coerceIn(0, engineHeight - 1)
        val idx = currentFrameIdx
        pullFrameFull(idx) { bmp ->
            val got = bmp
            if (got != null) {
                val argb = got.getPixel(
                    px.coerceIn(0, got.width - 1),
                    py.coerceIn(0, got.height - 1)
                )
                got.recycle()
                val hex = String.format("#%06X", argb and 0xFFFFFF)
                post { cb(hex, true) }
            } else if (idx != 0) {
                pullFrameFull(0) { fb ->
                    if (fb == null) {
                        post { cb(null, true) }
                    } else {
                        val argb = fb.getPixel(
                            px.coerceIn(0, fb.width - 1),
                            py.coerceIn(0, fb.height - 1)
                        )
                        fb.recycle()
                        val hex = String.format("#%06X", argb and 0xFFFFFF)
                        post { cb(hex, true) }
                    }
                }
            } else {
                post { cb(null, true) }
            }
        }
    }

    private fun markNavChanged() {
        lastNavTime = SystemClock.uptimeMillis()
        needsComposite = true
        val cb = onViewChanged
        if (cb != null) post { cb() }
    }

    private fun panBy(dx: Float, dy: Float) {
        viewOffX += dx
        viewOffY += dy
        clampView(width, height)
        markNavChanged()
    }

    private fun recenterOn(fx: Float, fy: Float, ex: Float, ey: Float) {
        // Solve the new center so the engine point under the fingers
        // stays fixed on screen after a zoom/rotate step.
        val s = fitScale(width, height) * viewScaleMul
        val r = Math.toRadians(viewAngleDeg.toDouble())
        val cosA = cos(r).toFloat()
        val sinA = sin(r).toFloat()
        val lx = (ex - engineWidth * 0.5f) * s
        val ly = (ey - engineHeight * 0.5f) * s
        val rx = lx * cosA - ly * sinA
        val ry = lx * sinA + ly * cosA
        viewOffX = fx - rx - width * 0.5f
        viewOffY = fy - ry - height * 0.5f
        clampView(width, height)
    }

    private fun zoomAt(fx: Float, fy: Float, factor: Float) {
        if (factor <= 0f || !factor.isFinite()) return
        val (ex, ey) = viewToEngine(fx, fy, width, height)
        viewScaleMul = (viewScaleMul * factor).coerceIn(SCALE_MIN, SCALE_MAX)
        recenterOn(fx, fy, ex, ey)
        markNavChanged()
    }

    private fun rotateAt(fx: Float, fy: Float, dDeg: Float) {
        if (dDeg == 0f || !dDeg.isFinite()) return
        val (ex, ey) = viewToEngine(fx, fy, width, height)
        var a = (viewAngleDeg + dDeg) % 360f
        if (a <= -180f) a += 360f
        if (a > 180f) a -= 360f
        viewAngleDeg = a
        recenterOn(fx, fy, ex, ey)
        markNavChanged()
    }

    private var lastSurfW = 0
    private var lastSurfH = 0

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
                if ((w != lastSurfW || he != lastSurfH) && w > 0 && he > 0) {
                    lastSurfW = w
                    lastSurfH = he
                    resetView()
                } else {
                    needsComposite = true
                }
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
        parseBrushJson(json)
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
            .also {
                if (it >= 0) {
                    vectorLayers.add(it)
                    needsComposite = true
                }
            } else -1
    }

    fun setVectorMode(index: Int, on: Boolean): Boolean = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nSetVectorMode(handle, index, on)
            .also {
                if (it) {
                    if (on) vectorLayers.add(index) else vectorLayers.remove(index)
                    needsComposite = true
                }
            } else false
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
            .also { if (it >= 0) { currentFrameIdx = it; needsComposite = true } } else -1
    }

    fun duplicateFrame(): Int = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nDuplicateFrame(handle)
            .also { if (it >= 0) { currentFrameIdx = it; needsComposite = true } } else -1
    }

    fun deleteFrame(index: Int): Boolean = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nDeleteFrame(handle, index)
            .also {
                if (it) {
                    currentFrameIdx = currentFrameIdx.coerceIn(
                        0, (frameCount() - 1).coerceAtLeast(0)
                    )
                    needsComposite = true
                }
            } else false
    }

    fun setFrame(index: Int): Boolean = synchronized(engineLock) {
        if (handle != 0L) EngineHost.nSetFrame(handle, index)
            .also { if (it) { currentFrameIdx = index; needsComposite = true } } else false
    }

    fun currentFrameIndex(): Int = currentFrameIdx

    /** True once the render thread runs (strokes/samples accepted). */
    fun isReady(): Boolean = running.get() && handle != 0L

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
            .also {
                if (it) {
                    needsComposite = true
                    moveCallCount++
                    // Keep the vector-layer index set in step with the move.
                    val shifted = vectorLayers.toList()
                    vectorLayers.clear()
                    for (i in shifted) {
                        val j = when {
                            i == from -> to
                            from < to && i in (from + 1)..to -> i - 1
                            from > to && i in to..(from - 1) -> i + 1
                            else -> i
                        }
                        vectorLayers.add(j)
                    }
                }
            } else false
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

    // Slice 0 shell contract: exactly one pointer draws; two or more
    // pointers are a view-navigation gesture and must never start or
    // corrupt a stroke. Navigation consumes the gesture (no stroke is
    // queued); the render loop drops any in-flight points and rolls the
    // partial stroke back via end + undo.
    //
    // Slice 1 adds the app-side gesture machine on top (spec
    // docs/slice1-gestures.md §2): stylus-first drawing, 1-finger
    // draw-or-pan toggle, 2-finger tap = undo, 3-finger tap = redo,
    // 2-finger drag = pan, pinch = zoom, 2-finger twist = rotate view,
    // edge-swipe = reopen a folded panel. Priority is evaluated before
    // any stroke; nothing here touches engine pixels.
    private var stylusId = -1
    // Slice 5: app-side index of vector layers (for explicit eraser
    // routing to nVectorEraser). Guarded by engineLock like all engine
    // calls; indexes shift on moveLayer (adjusted below).
    private val vectorLayers = mutableSetOf<Int>()
    // Slice 5: in-flight vector-erase accumulation (render thread only).
    private var vecEraseActive = false
    private var vecEraseIndex = -1
    private val vecErasePts = mutableListOf<Pair<Float, Float>>()
    private var strokePointerId = -1
    private var strokeOpenUi = false
    private var downTimeMs = 0L
    private var mtActive = false
    private var mtCount = 0
    private var mtDownTime = 0L
    private var mtMoved = false
    private var mtAccumAngle = 0f
    private var mtTotalAngle = 0f
    private var mtPrevDist = 0f
    private var mtPrevAngle = 0f
    private var mtPrevCX = 0f
    private var mtPrevCY = 0f
    private var lastSysTapTime = 0L
    private var edgeArmed = -1
    private var edgeDownX = 0f
    private var edgeDownY = 0f
    private var edgeFired = false
    private var pendingStroke = false
    private var pendingId = -1
    private var pendingDownX = 0f
    private var pendingDownY = 0f
    private var pendingDownP = 0.5f
    private var lastPanX = 0f
    private var lastPanY = 0f

    private data class GestureFrame(
        val cx: Float,
        val cy: Float,
        val dist: Float,
        val ang: Float
    )

    private fun gestureFrame(event: MotionEvent): GestureFrame {
        var sx = 0f
        var sy = 0f
        val n = event.pointerCount
        for (i in 0 until n) {
            sx += event.getX(i)
            sy += event.getY(i)
        }
        val cx = sx / n
        val cy = sy / n
        var dist = 0f
        for (i in 0 until n) {
            val dx = event.getX(i) - cx
            val dy = event.getY(i) - cy
            dist += sqrt(dx * dx + dy * dy)
        }
        dist /= n
        val ang = if (n >= 2) {
            Math.toDegrees(
                atan2(
                    (event.getY(1) - event.getY(0)).toDouble(),
                    (event.getX(1) - event.getX(0)).toDouble()
                )
            ).toFloat()
        } else 0f
        return GestureFrame(cx, cy, dist, ang)
    }

    private fun angDiff(a: Float, b: Float): Float {
        var d = (a - b) % 360f
        if (d <= -180f) d += 360f
        if (d > 180f) d -= 360f
        return d
    }

    private fun pressureOf(raw: Float): Float =
        (if (raw <= 0f) 0.5f else raw * 2f).coerceIn(0.05f, 1f)

    private fun disarmEdge() {
        edgeArmed = -1
    }

    private fun edgeAt(x: Float, y: Float, bandPx: Float): Int {
        if (width <= 0 || height <= 0) return -1
        if (x <= bandPx) return EDGE_LEFT
        if (x >= width - bandPx) return EDGE_RIGHT
        if (edgeBottomEnabled && y >= height - bandPx) return EDGE_BOTTOM
        return -1
    }

    private fun checkEdgeSwipe(x: Float, y: Float, swipePx: Float): Boolean {
        val dx = x - edgeDownX
        val dy = y - edgeDownY
        return when (edgeArmed) {
            EDGE_LEFT -> dx >= swipePx && abs(dy) < dx
            EDGE_RIGHT -> -dx >= swipePx && abs(dy) < -dx
            EDGE_BOTTOM -> -dy >= swipePx && abs(dx) < -dy
            else -> false
        }
    }

    private fun edgeBroken(x: Float, y: Float, slopPx: Float): Boolean {
        val dx = x - edgeDownX
        val dy = y - edgeDownY
        return when (edgeArmed) {
            EDGE_LEFT -> (dx < -slopPx) || (abs(dy) > 2f * abs(dx) && abs(dy) > slopPx)
            EDGE_RIGHT -> (dx > slopPx) || (abs(dy) > 2f * abs(dx) && abs(dy) > slopPx)
            EDGE_BOTTOM -> (dy > slopPx) || (abs(dx) > 2f * abs(dy) && abs(dx) > slopPx)
            else -> true
        }
    }

    private fun fireEdge(edge: Int) {
        val cb = onEdgeSwipe
        if (cb != null) post { cb(edge) }
    }

    private fun finishMultiTouch(eventTime: Long) {
        // Flush a sub-threshold rotation remainder when the gesture as a
        // whole clearly rotated (total >= 8°): stepwise twists would
        // otherwise always lose their last step.
        if (abs(mtTotalAngle) >= ROTATE_MIN_DEG && abs(mtAccumAngle) >= 0.5f) {
            rotateAt(mtPrevCX, mtPrevCY, mtAccumAngle)
            mtAccumAngle = 0f
            mtMoved = true
        }
        if (!mtMoved && (mtCount == 2 || mtCount == 3) &&
            (eventTime - mtDownTime) < TAP_MAX_MS &&
            (eventTime - lastSysTapTime) >= TAP_DEBOUNCE_MS
        ) {
            lastSysTapTime = eventTime
            if (mtCount == 2) {
                if (undo()) {
                    notifyStrokeChanged()
                    val cb = onGestureUndo
                    if (cb != null) post { cb() }
                }
            } else {
                if (redo()) {
                    notifyStrokeChanged()
                    val cb = onGestureRedo
                    if (cb != null) post { cb() }
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (handle == 0L || !running.get()) return false
        val density = resources.displayMetrics.density
        val tapSlopPx = TAP_MAX_DP * density
        val dragSlopPx = DRAG_MIN_DP * density
        val edgeBandPx = EDGE_BAND_DP * density
        val edgeSwipePx = EDGE_SWIPE_MIN_DP * density
        val action = event.actionMasked

        // Palm rejection: a palm contact alone is navigation (consumed,
        // never ink). Stylus-first: while the stylus is down every finger
        // contact is ignored.
        var sawPalm = false
        var stylusIdx = -1
        for (i in 0 until event.pointerCount) {
            when (event.getToolType(i)) {
                MotionEvent.TOOL_TYPE_STYLUS -> if (stylusIdx < 0) stylusIdx = i
                // No TOOL_TYPE_PALM on this API level: a large UNKNOWN
                // contact with a real touch major is treated as a palm
                // (navigation, never ink). Synthetic test events carry
                // size=1 but touchMajor=0 and must not match.
                MotionEvent.TOOL_TYPE_UNKNOWN ->
                    if (event.getSize(i) > 0.4f && event.getTouchMajor(i) > 1f) {
                        sawPalm = true
                    }
            }
        }
        if (stylusIdx >= 0) {
            val sid = event.getPointerId(stylusIdx)
            val p = pressureOf(event.getPressure(stylusIdx))
            when (action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    // Slice 2 pipette: an armed pipette samples even from the
                    // stylus (no parasite ink) and consumes the tap.
                    if (pipetteArmed) {
                        onPipetteDown?.invoke(event.getX(stylusIdx), event.getY(stylusIdx))
                        requestParentDisallowInterceptTouchEvent()
                        return true
                    }
                    // Stylus-first: drop the interrupted finger tail and
                    // roll the open finger stroke back on the render
                    // thread; the stylus points below start a clean stroke.
                    if (strokeOpenUi) {
                        synchronized(queue) { queue.clear() }
                        strokeOpenUi = false
                    }
                    rollbackOpen = true
                    navigating = false
                    mtActive = false
                    disarmEdge()
                    pendingStroke = false
                    stylusId = sid
                    strokePointerId = sid
                    strokeOpenUi = true
                    enqueueMove(event, stylusIdx, p, MotionEvent.ACTION_DOWN)
                }
                MotionEvent.ACTION_MOVE -> {
                    // Slice 2 pipette loupe: drag streams live samples; a
                    // tap-armed pipette swallows moves (no stroke starts).
                    if (pipetteArmed) {
                        if (pipetteLoupe) {
                            onPipetteMove?.invoke(event.getX(stylusIdx), event.getY(stylusIdx))
                        }
                        requestParentDisallowInterceptTouchEvent()
                        return true
                    }
                    if (stylusId == sid) enqueueMove(event, stylusIdx, p, MotionEvent.ACTION_MOVE)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (pipetteArmed) {
                        onPipetteUp?.invoke()
                        requestParentDisallowInterceptTouchEvent()
                        return true
                    }
                    if (stylusId == sid || action == MotionEvent.ACTION_CANCEL) {
                        enqueueMove(event, stylusIdx, p, MotionEvent.ACTION_UP)
                        stylusId = -1
                        strokePointerId = -1
                        strokeOpenUi = false
                    }
                }
            }
            requestParentDisallowInterceptTouchEvent()
            return true
        }
        if (stylusId != -1) {
            // Stylus lifted but stale finger events remain: swallow
            // everything until a fresh ACTION_DOWN.
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                stylusId = -1
                strokePointerId = -1
            }
            return true
        }
        if (sawPalm) {
            navigating = true
            if (strokeOpenUi) {
                synchronized(queue) { queue.clear() }
                strokeOpenUi = false
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                navigating = false
            }
            return true
        }

        if (event.pointerCount >= 2) {
            // Slice 0 guard: two or more pointers = navigation, never a
            // stroke. Slice 1 adds the real pan/zoom/rotate on top. An
            // interrupted stroke's queued tail is dropped here on the UI
            // thread (dispatch is synchronous, the render thread may not
            // have run yet); an already-begun stroke is rolled back in
            // the render loop. A stroke completed before the gesture
            // (UP already seen) is left alone so it still commits.
            if (!mtActive && strokeOpenUi) {
                synchronized(queue) { queue.clear() }
                strokeOpenUi = false
            }
            navigating = true
            pendingStroke = false
            disarmEdge()
            strokePointerId = -1
            if (!mtActive) {
                mtActive = true
                mtCount = event.pointerCount
                mtDownTime = downTimeMs
                mtMoved = false
                mtAccumAngle = 0f
                mtTotalAngle = 0f
                val g = gestureFrame(event)
                mtPrevCX = g.cx
                mtPrevCY = g.cy
                mtPrevDist = g.dist
                mtPrevAngle = g.ang
            } else {
                mtCount = maxOf(mtCount, event.pointerCount)
                if (event.pointerCount > 3) mtMoved = true
            }
            if (action == MotionEvent.ACTION_MOVE && event.pointerCount <= 3) {
                val g = gestureFrame(event)
                val moved = sqrt(
                    (g.cx - mtPrevCX) * (g.cx - mtPrevCX) +
                        (g.cy - mtPrevCY) * (g.cy - mtPrevCY)
                )
                if (moved > dragSlopPx) mtMoved = true
                if (moved > 1f) panBy(g.cx - mtPrevCX, g.cy - mtPrevCY)
                if (mtPrevDist > 1f && g.dist > 1f) {
                    val f = g.dist / mtPrevDist
                    if (abs(f - 1f) > 0.005f) {
                        zoomAt(g.cx, g.cy, f)
                        mtMoved = true
                    }
                }
                mtAccumAngle += angDiff(g.ang, mtPrevAngle)
                mtTotalAngle += angDiff(g.ang, mtPrevAngle)
                if (abs(mtAccumAngle) >= ROTATE_MIN_DEG) {
                    rotateAt(g.cx, g.cy, mtAccumAngle)
                    mtAccumAngle = 0f
                    mtMoved = true
                }
                mtPrevCX = g.cx
                mtPrevCY = g.cy
                mtPrevDist = g.dist
                mtPrevAngle = g.ang
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                finishMultiTouch(event.eventTime)
                mtActive = false
                navigating = false
            }
            requestParentDisallowInterceptTouchEvent()
            return true
        }

        if (mtActive) {
            // A finger remains after a multitouch gesture: consume it,
            // never resume the cancelled stroke.
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                finishMultiTouch(event.eventTime)
                mtActive = false
                navigating = false
            }
            return true
        }

        val isMouse = event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
        val mousePan = isMouse && (
            (event.buttonState and MotionEvent.BUTTON_TERTIARY) != 0 ||
                (event.buttonState and MotionEvent.BUTTON_SECONDARY) != 0
            )
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                downTimeMs = event.downTime
                edgeFired = false
                val x = event.getX(0)
                val y = event.getY(0)
                // Slice 2 pipette: armed tap samples instead of drawing.
                if (pipetteArmed) {
                    onPipetteDown?.invoke(x, y)
                    requestParentDisallowInterceptTouchEvent()
                    return true
                }
                val edge = edgeAt(x, y, edgeBandPx)
                if (edge >= 0) {
                    // Armed: no stroke starts from the band until the
                    // detector either fires (swipe) or disarms (drawing).
                    edgeArmed = edge
                    edgeDownX = x
                    edgeDownY = y
                    pendingStroke = true
                    pendingId = event.getPointerId(0)
                    pendingDownX = x
                    pendingDownY = y
                    pendingDownP = pressureOf(event.getPressure(0))
                } else if ((navigateMode && !isMouse) || mousePan) {
                    lastPanX = x
                    lastPanY = y
                    strokePointerId = -1
                } else {
                    strokePointerId = event.getPointerId(0)
                    strokeOpenUi = true
                    enqueueMove(event, 0, pressureOf(event.getPressure(0)), MotionEvent.ACTION_DOWN)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val x = event.getX(0)
                val y = event.getY(0)
                // Slice 2 pipette loupe: drag streams live samples; a
                // tap-armed pipette swallows moves (no stroke starts).
                if (pipetteArmed) {
                    if (pipetteLoupe) onPipetteMove?.invoke(x, y)
                    requestParentDisallowInterceptTouchEvent()
                    return true
                }
                if (edgeArmed >= 0) {
                    if (checkEdgeSwipe(x, y, edgeSwipePx)) {
                        fireEdge(edgeArmed)
                        disarmEdge()
                        pendingStroke = false
                        edgeFired = true
                    } else if (edgeBroken(x, y, tapSlopPx)) {
                        disarmEdge()
                        flushPending()
                        handleSingleMove(event, x, y, mousePan)
                    }
                    // Else: still armed, swallow the move.
                } else if (edgeFired) {
                    // Swallow the rest of the fired edge gesture.
                } else {
                    handleSingleMove(event, x, y, mousePan)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> {
                // Slice 2 pipette: UP finalizes a loupe drag (or is a no-op
                // after a tap sample already disarmed); never draws.
                if (pipetteArmed) {
                    onPipetteUp?.invoke()
                    edgeArmed = -1
                    pendingStroke = false
                    edgeFired = false
                    requestParentDisallowInterceptTouchEvent()
                    return true
                }
                if (edgeArmed >= 0) {
                    disarmEdge()
                    if (pendingStroke) {
                        // Tap inside the band without a swipe: keep edge
                        // drawing alive with a normal tap.
                        flushPending()
                        enqueueMove(event, 0, pressureOf(event.getPressure(0)), MotionEvent.ACTION_UP)
                        strokeOpenUi = false
                    }
                    pendingStroke = false
                } else if (edgeFired) {
                    if (action != MotionEvent.ACTION_POINTER_UP) edgeFired = false
                } else if ((navigateMode && !isMouse) || mousePan) {
                    // Pan ends: nothing to enqueue.
                    strokePointerId = -1
                } else {
                    if (event.getPointerId(0) == strokePointerId) {
                        enqueueMove(event, 0, pressureOf(event.getPressure(0)), MotionEvent.ACTION_UP)
                    }
                    strokePointerId = -1
                    strokeOpenUi = false
                    // Slice 5: finger lifted = ring hidden (no capacitive hover).
                    hoverActive = false
                    needsComposite = true
                }
            }
        }
        requestParentDisallowInterceptTouchEvent()
        return true
    }

    private fun handleSingleMove(event: MotionEvent, x: Float, y: Float, mousePan: Boolean) {
        val isMouse = event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
        if ((navigateMode && !isMouse) || mousePan) {
            panBy(x - lastPanX, y - lastPanY)
            lastPanX = x
            lastPanY = y
        } else {
            if (event.getPointerId(0) == strokePointerId) {
                enqueueMove(event, 0, pressureOf(event.getPressure(0)), MotionEvent.ACTION_MOVE)
            }
        }
    }

    private fun flushPending() {
        if (!pendingStroke) return
        pendingStroke = false
        // The edge detector disarmed: the held-down finger becomes a
        // normal stroke (or pan) start.
        if (navigateMode) {
            lastPanX = pendingDownX
            lastPanY = pendingDownY
            strokePointerId = -1
        } else {
            strokePointerId = pendingId
            strokeOpenUi = true
            enqueueAt(pendingDownX, pendingDownY, pendingDownP, MotionEvent.ACTION_DOWN)
        }
    }

    // Slice 5: stylus/mouse hover drives the real-size cursor ring
    // (overlay-only). Throttled to 30 Hz (armHoverFrame).
    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (handle == 0L || !running.get()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> {
                hoverX = event.getX()
                hoverY = event.getY()
                hoverActive = true
                armHoverFrame()
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                hoverActive = false
                needsComposite = true
            }
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (handle != 0L && running.get() &&
            (event.source and InputDevice.SOURCE_CLASS_POINTER) != 0 &&
            event.actionMasked == MotionEvent.ACTION_SCROLL
        ) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f) {
                val factor = (1f - 0.1f * v).coerceIn(0.5f, 2f)
                zoomAt(event.getX(), event.getY(), factor)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    private fun enqueueMove(event: MotionEvent, idx: Int, pressure: Float, action: Int) {
        // Slice 5: every drawing point also feeds the cursor ring (finger
        // or stylus tip position + live pressure).
        if (!navigating && !pipetteArmed) {
            hoverX = event.getX(idx)
            hoverY = event.getY(idx)
            hoverPressure = pressure
            hoverActive = true
            armHoverFrame()
        }
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_MOVE) {
            for (i in 0 until event.historySize) {
                val (hx, hy) = viewToEngine(
                    event.getHistoricalX(idx, i),
                    event.getHistoricalY(idx, i),
                    width, height
                )
                val hp = (event.getHistoricalPressure(idx, i) * 2f).coerceIn(0.05f, 1f)
                synchronized(queue) {
                    queue.add(QueuedPoint(hx, hy, hp, MotionEvent.ACTION_MOVE, now))
                }
            }
        }
        val (cx, cy) = viewToEngine(event.getX(idx), event.getY(idx), width, height)
        synchronized(queue) {
            queue.add(QueuedPoint(cx, cy, pressure, action, now))
        }
    }

    private fun enqueueAt(vx: Float, vy: Float, pressure: Float, action: Int) {
        if (!navigating && !pipetteArmed) {
            hoverX = vx
            hoverY = vy
            hoverPressure = pressure
            hoverActive = true
            armHoverFrame()
        }
        val (ex, ey) = viewToEngine(vx, vy, width, height)
        synchronized(queue) {
            queue.add(QueuedPoint(ex, ey, pressure, action, SystemClock.uptimeMillis()))
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
        // Slice 1 loupe overlay (composite-only, never engine pixels).
        val loupeBgPaint = Paint().apply {
            style = Paint.Style.FILL
            color = 0xCC101014.toInt()
        }
        val loupeTextPaint = Paint().apply {
            style = Paint.Style.FILL
            color = 0xFFECECF1.toInt()
            textSize = 40f
            isAntiAlias = true
        }
        val loupeDotPaint = Paint().apply {
            style = Paint.Style.FILL
            color = 0xFF80E27E.toInt()
            isAntiAlias = true
        }
        var loupeDrawn = false

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
            if (drained.isNotEmpty() || (strokeId != 0L && (navigating || rollbackOpen)) || (vecEraseActive && (navigating || rollbackOpen))) {
                synchronized(engineLock) {
                    if (strokeId != 0L && (navigating || rollbackOpen)) {
                        // A navigation gesture (or the stylus taking over)
                        // interrupted the stroke: end it and roll it back
                        // so the gesture leaves no ink — even when the
                        // queue already drained before the second finger
                        // landed (in-flight stroke with an empty queue).
                        EngineHost.nStrokeEnd(handle, strokeId)
                        strokeId = 0L
                        EngineHost.nUndo(handle)
                        notifyStrokeChanged()
                        needsComposite = true
                    }
                    if (vecEraseActive && (navigating || rollbackOpen)) {
                        // Same rollback for an in-flight vector erase.
                        vecEraseActive = false
                        vecEraseIndex = -1
                        vecErasePts.clear()
                        needsComposite = true
                    }
                    rollbackOpen = false
                    if (!navigating) {
                    for (pt in drained) {
                        when (pt.action) {
                            MotionEvent.ACTION_DOWN -> {
                                // Slice 5: eraser on a vector layer routes
                                // to the real vector eraser (path removal),
                                // never to raster stamps.
                                val vIdx = if (brushIsEraser) activeVectorLayer() else -1
                                if (vIdx >= 0) {
                                    vecEraseActive = true
                                    vecEraseIndex = vIdx
                                    vecErasePts.clear()
                                    vecErasePts.add(Pair(pt.x, pt.y))
                                    lastStrokeLatencyMs =
                                        SystemClock.uptimeMillis() - pt.t
                                    showHud(pt.pressure)
                                } else if (strokeId != 0L) {
                                    EngineHost.nStrokeEnd(handle, strokeId)
                                    strokeId = EngineHost.nStrokeBegin(
                                        handle, pt.x, pt.y, pt.pressure
                                    )
                                    lastStrokeLatencyMs =
                                        SystemClock.uptimeMillis() - pt.t
                                    showHud(pt.pressure)
                                    needsComposite = true
                                } else {
                                    strokeId = EngineHost.nStrokeBegin(
                                        handle, pt.x, pt.y, pt.pressure
                                    )
                                    lastStrokeLatencyMs =
                                        SystemClock.uptimeMillis() - pt.t
                                    showHud(pt.pressure)
                                    needsComposite = true
                                }
                            }
                            MotionEvent.ACTION_MOVE -> {
                                if (vecEraseActive) {
                                    vecErasePts.add(Pair(pt.x, pt.y))
                                    showHud(pt.pressure)
                                } else if (strokeId == 0L) {
                                    strokeId = EngineHost.nStrokeBegin(
                                        handle, pt.x, pt.y, pt.pressure
                                    )
                                    showHud(pt.pressure)
                                } else {
                                    EngineHost.nStrokeMove(
                                        handle, strokeId, pt.x, pt.y, pt.pressure
                                    )
                                    showHud(pt.pressure)
                                }
                                needsComposite = true
                            }
                            MotionEvent.ACTION_UP -> {
                                if (vecEraseActive) {
                                    vecErasePts.add(Pair(pt.x, pt.y))
                                    fireVectorErase()
                                    vecEraseActive = false
                                    vecEraseIndex = -1
                                    vecErasePts.clear()
                                    notifyStrokeChanged()
                                } else if (strokeId != 0L) {
                                    EngineHost.nStrokeMove(
                                        handle, strokeId, pt.x, pt.y, pt.pressure
                                    )
                                    EngineHost.nStrokeEnd(handle, strokeId)
                                    strokeId = 0L
                                    notifyStrokeChanged()
                                }
                                hudUntilMs = SystemClock.uptimeMillis() + HUD_TTL_MS
                                postHud(force = true)
                                needsComposite = true
                            }
                        }
                    }
                    } else {
                        // Navigation: commit completed strokes, roll back
                        // only the interrupted tail so gestures leave no ink.
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
                        if (strokeId != 0L) {
                            EngineHost.nStrokeEnd(handle, strokeId)
                            strokeId = 0L
                            EngineHost.nUndo(handle)
                            notifyStrokeChanged()
                        }
                        needsComposite = true
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
                if (isLoupeVisible()) {
                    needsComposite = true
                } else if (loupeDrawn) {
                    // One final clean frame so the loupe vanishes.
                    loupeDrawn = false
                    needsComposite = true
                } else if (hudVisible && strokeId == 0L &&
                    SystemClock.uptimeMillis() > hudUntilMs
                ) {
                    // Slice 5: HUD TTL expired — hide the pastille and
                    // repaint once without it.
                    hudVisible = false
                    needsComposite = true
                    val cb = onHudChanged
                    post {
                        hudUi = null
                        cb?.invoke()
                    }
                } else {
                    Thread.sleep(4)
                    continue
                }
            }
            // Slice 3 solo-render window: skip this frame (needsComposite
            // stays armed) so visibility toggling never flashes on screen.
            if (suspendRender) {
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
                    val ang = viewAngleDeg
                    c.save()
                    if (ang != 0f) c.rotate(ang, dst.centerX(), dst.centerY())
                    c.drawBitmap(frame, null, dst, bitmapPaint)
                    c.drawRect(dst, borderPaint)
                    c.restore()
                    if (SystemClock.uptimeMillis() - lastNavTime < LOUPE_MS) {
                        var label = context.getString(
                            R.string.view_zoom_fit, (viewScaleMul * 100f).toInt()
                        )
                        if (abs(viewAngleDeg) >= 0.5f) {
                            label += " " + context.getString(
                                R.string.view_angle, viewAngleDeg.toInt()
                            )
                        }
                        val tw = loupeTextPaint.measureText(label)
                        val lx = dst.left + 12f
                        val ly = dst.top + 12f
                        c.drawRect(lx, ly, lx + 44f + tw, ly + 56f, loupeBgPaint)
                        loupeDotPaint.color =
                            if (navigateMode) 0xFF5C9DFF.toInt() else 0xFF80E27E.toInt()
                        c.drawCircle(lx + 24f, ly + 28f, 10f, loupeDotPaint)
                        c.drawText(label, lx + 44f, ly + 43f, loupeTextPaint)
                        loupeDrawn = true
                    }
                    drawCursorRing(c, dst, ang)
                } finally {
                    holder.unlockCanvasAndPost(c)
                }
            }
        }
        frame.recycle()
    }

    // ---- Slice 5 cursor ring (overlay-only, never engine pixels) ----
    private val ringPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xE6FFFFFF.toInt()
        isAntiAlias = true
    }
    private val ringShadowPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = 0x80000000.toInt()
        isAntiAlias = true
    }
    private val ringEraserPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFFE0A050.toInt()
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }
    private val ringEraserShadowPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = 0x80000000.toInt()
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }
    private val ringBarPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xE6FFFFFF.toInt()
        isAntiAlias = true
    }
    private var lastRingDesc: String? = null

    private fun drawCursorRing(c: android.graphics.Canvas, dst: RectF, ang: Float) {
        if (!hoverActive || navigating || suspendRender || navigateMode || pipetteArmed) {
            postRingDesc(null)
            return
        }
        val vw = width
        val vh = height
        if (vw <= 0 || vh <= 0) {
            postRingDesc(null)
            return
        }
        val s = fitScale(vw, vh) * viewScaleMul
        if (s <= 1e-6f) {
            postRingDesc(null)
            return
        }
        // Nominal pressure at rest: the ring shows the dab at average
        // pressure, not the max (honest size).
        val pr = if (strokeOpenUi) hoverPressure else 0.6f
        val dab = brushRadiusEngine * (0.25f + 0.75f * pr)
        val r = dab * s
        if (r < 1f) {
            postRingDesc(null)
            return
        }
        val (ex, ey) = screenToEngine(hoverX, hoverY)
        val onPaper = ex >= 0f && ex < engineWidth && ey >= 0f && ey < engineHeight
        val barred = !activeLayerVisible()
        val sx = dst.centerX() + (ex - engineWidth * 0.5f) * s
        val sy = dst.centerY() + (ey - engineHeight * 0.5f) * s
        val dimmed = !onPaper
        val ring = if (brushIsEraser) ringEraserPaint else ringPaint
        val shadow = if (brushIsEraser) ringEraserShadowPaint else ringShadowPaint
        if (dimmed) {
            ring.alpha = 77
            shadow.alpha = 38
        } else {
            ring.alpha = 255
            shadow.alpha = 255
        }
        c.save()
        if (ang != 0f) c.rotate(ang, dst.centerX(), dst.centerY())
        c.drawCircle(sx, sy, r + 1f, shadow)
        c.drawCircle(sx, sy, r, ring)
        if (barred) {
            c.drawLine(sx - r, sy - r, sx + r, sy + r, ringBarPaint)
        }
        c.restore()
        val diam = dab.toInt()
        postRingDesc(
            when {
                barred -> context.getString(R.string.cursor_blocked)
                !onPaper -> context.getString(R.string.cursor_offpaper)
                brushIsEraser -> context.getString(R.string.cursor_eraser, diam)
                else -> context.getString(R.string.cursor_brush, diam)
            }
        )
    }

    private fun postRingDesc(desc: String?) {
        if (desc == lastRingDesc) return
        lastRingDesc = desc
        post { contentDescription = desc }
    }

    // ---- Slice 5 HUD + vector-erase helpers (render thread) ----
    private fun showHud(pressure: Float) {
        hudDiamPx = (brushRadiusEngine * (0.25f + 0.75f * pressure)).toInt()
        hudPressurePct = (pressure * 100f).toInt()
        if (!hudVisible) {
            hudVisible = true
            hudUntilMs = SystemClock.uptimeMillis() + HUD_TTL_MS
        }
        postHud(force = false)
    }

    private fun postHud(force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastHudPostMs < 100) return
        lastHudPostMs = now
        val cb = onHudChanged
        val d = hudDiamPx
        val p = hudPressurePct
        post {
            hudUi = HudData(d, p)
            cb?.invoke()
        }
    }

    private fun fireVectorErase() {
        if (vecEraseIndex < 0 || vecErasePts.size < 2) return
        val sb = StringBuilder("{\"pts\":[")
        for ((i, p) in vecErasePts.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append(String.format(java.util.Locale.US, "{\"x\":%.1f,\"y\":%.1f}", p.first, p.second))
        }
        sb.append("]}")
        try {
            EngineHost.nVectorEraser(handle, vecEraseIndex, true, sb.toString())
        } catch (_: Exception) {
        }
    }
}
