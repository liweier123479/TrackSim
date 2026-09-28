package com.example.tracksim

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class MapCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var engine: TrackEngine? = null
    var onTrackChanged: (() -> Unit)? = null

    var currentPos: GeoPoint? = null
        set(value) {
            field = value
            invalidate()
        }

    var showDirection: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density

    private var anchorLat = 39.9087
    private var anchorLng = 116.3975
    private var scale = 0.08f
    private var ox = 0f
    private var oy = 0f

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x0DFFFFFF
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xD94DABF7.toInt()
    }
    private val loopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        color = 0x804DABF7.toInt()
        pathEffect = DashPathEffect(floatArrayOf(7f * density, 6f * density), 0f)
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nodeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0xFF11141A.toInt()
    }
    private val nodeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 11f * density
        typeface = Typeface.DEFAULT_BOLD
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB38CC8FF.toInt()
        style = Paint.Style.FILL
    }
    private val posPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF8A00.toInt()
        style = Paint.Style.FILL
    }
    private val posStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.WHITE
    }
    private val scaleBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0x59FFFFFF
    }
    private val scaleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x73FFFFFF
        textSize = 11f * density
    }

    private val touchSlop = 8f * density

    private var dragIndex = -1
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var multiTouch = false
    private var lastPinchDist = 0f

    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

    private fun toScreen(lat: Double, lng: Double): PointF {
        val mx = (lng - anchorLng) * GeoUtils.metersPerDegLng(anchorLat)
        val my = -(lat - anchorLat) * GeoUtils.M_PER_DEG_LAT
        return PointF((ox + mx * scale).toFloat(), (oy + my * scale).toFloat())
    }

    private fun screenToGeo(sx: Float, sy: Float): GeoPoint {
        val mx = (sx - ox) / scale
        val my = (sy - oy) / scale
        return GeoPoint(
            anchorLat - my / GeoUtils.M_PER_DEG_LAT,
            anchorLng + mx / GeoUtils.metersPerDegLng(anchorLat)
        )
    }

    fun fitView() {
        if (width == 0 || height == 0) return
        val pts = engine?.points ?: return

        if (pts.isEmpty()) {
            anchorLat = 39.9087
            anchorLng = 116.3975
            scale = 0.08f
            ox = width / 2f
            oy = height / 2f
            invalidate()
            return
        }

        if (pts.size == 1) {
            anchorLat = pts[0].lat
            anchorLng = pts[0].lng
            scale = 0.08f
            ox = width / 2f
            oy = height / 2f
            invalidate()
            return
        }

        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLng = Double.MAX_VALUE
        var maxLng = -Double.MAX_VALUE
        for (p in pts) {
            minLat = min(minLat, p.lat); maxLat = max(maxLat, p.lat)
            minLng = min(minLng, p.lng); maxLng = max(maxLng, p.lng)
        }

        anchorLat = (minLat + maxLat) / 2.0
        anchorLng = (minLng + maxLng) / 2.0

        val spanX = (maxLng - minLng) * GeoUtils.metersPerDegLng(anchorLat)
        val spanY = (maxLat - minLat) * GeoUtils.M_PER_DEG_LAT
        val pad = 0.72

        val sx = if (spanX > 1e-6) (width * pad / spanX).toFloat() else Float.MAX_VALUE
        val sy = if (spanY > 1e-6) (height * pad / spanY).toFloat() else Float.MAX_VALUE

        var s = min(sx, sy)
        if (!s.isFinite() || s <= 0f) s = 0.1f

        scale = s.coerceIn(0.002f, 100f)
        ox = width / 2f
        oy = height / 2f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw == 0 || oldh == 0) {
            fitView()
        } else {
            ox += (w - oldw) / 2f
            oy += (h - oldh) / 2f
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFF11141A.toInt())
        drawGrid(canvas)

        val pts = engine?.points ?: return
        if (pts.isEmpty()) return

        if (pts.size > 1) {
            val path = Path()
            val p0 = toScreen(pts[0].lat, pts[0].lng)
            path.moveTo(p0.x, p0.y)
            for (i in 1 until pts.size) {
                val p = toScreen(pts[i].lat, pts[i].lng)
                path.lineTo(p.x, p.y)
            }
            canvas.drawPath(path, trackPaint)
        }

        if (engine?.loop == true && pts.size > 2) {
            val a = toScreen(pts.last().lat, pts.last().lng)
            val b = toScreen(pts[0].lat, pts[0].lng)
            canvas.drawLine(a.x, a.y, b.x, b.y, loopPaint)
        }

        if (showDirection && pts.size > 1) {
            for (i in 0 until pts.size - 1) {
                val a = toScreen(pts[i].lat, pts[i].lng)
                val b = toScreen(pts[i + 1].lat, pts[i + 1].lng)
                drawArrow(canvas, (a.x + b.x) / 2f, (a.y + b.y) / 2f,
                    atan2(b.y - a.y, b.x - a.x))
            }
            if (engine?.loop == true && pts.size > 2) {
                val a = toScreen(pts.last().lat, pts.last().lng)
                val b = toScreen(pts[0].lat, pts[0].lng)
                drawArrow(canvas, (a.x + b.x) / 2f, (a.y + b.y) / 2f,
                    atan2(b.y - a.y, b.x - a.x))
            }
        }

        for (i in pts.indices) {
            val s = toScreen(pts[i].lat, pts[i].lng)
            val isFirst = i == 0
            val isLast = i == pts.size - 1 && engine?.loop != true

            nodePaint.color = when {
                isFirst -> 0xFF3FB950.toInt()
                isLast -> 0xFFE5484D.toInt()
                else -> 0xFF4DABF7.toInt()
            }
            canvas.drawCircle(s.x, s.y, 7f * density, nodePaint)
            canvas.drawCircle(s.x, s.y, 7f * density, nodeStrokePaint)

            val label = when {
                isFirst -> "起"
                isLast -> "终"
                else -> (i + 1).toString()
            }
            val fm = nodeTextPaint.fontMetrics
            canvas.drawText(label, s.x, s.y - (fm.ascent + fm.descent) / 2f, nodeTextPaint)
        }

        currentPos?.let { cp ->
            val s = toScreen(cp.lat, cp.lng)
            val t = System.currentTimeMillis() / 1000.0
            val r = (14 + sin(t * 4) * 2).toFloat() * density

            val grad = RadialGradient(
                s.x, s.y, r * 2.2f,
                intArrayOf(0x8CFF8A00.toInt(), 0x00FF8A00),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
            val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = grad }
            canvas.drawCircle(s.x, s.y, r * 2.2f, gp)

            canvas.drawCircle(s.x, s.y, r, posPaint)
            canvas.drawCircle(s.x, s.y, r, posStrokePaint)
        }
    }

    private fun drawArrow(canvas: Canvas, x: Float, y: Float, angle: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(Math.toDegrees(angle.toDouble()).toFloat())
        val L = 8f * density
        val path = Path()
        path.moveTo(-L / 2f, -4f * density)
        path.lineTo(L / 2f + 2f * density, 0f)
        path.lineTo(-L / 2f, 4f * density)
        path.close()
        canvas.drawPath(path, arrowPaint)
        canvas.restore()
    }

    private fun drawGrid(canvas: Canvas) {
        val targetPx = 70f * density
        var step = (targetPx / scale).toDouble()
        if (step <= 0 || !step.isFinite()) return

        val p = 10.0.pow(floor(log10(step)))
        val n = step / p
        step = (if (n < 2) 2.0 else if (n < 5) 5.0 else 10.0) * p

        val left = (0f - ox) / scale
        val right = (width - ox) / scale
        val top = (0f - oy) / scale
        val bottom = (height - oy) / scale

        var x = ceil(left / step) * step
        while (x < right) {
            val sx = ox + (x * scale).toFloat()
            canvas.drawLine(sx, 0f, sx, height.toFloat(), gridPaint)
            x += step
        }
        var y = ceil(top / step) * step
        while (y < bottom) {
            val sy = oy + (y * scale).toFloat()
            canvas.drawLine(0f, sy, width.toFloat(), sy, gridPaint)
            y += step
        }

        val barPx = (step * scale).toFloat()
        if (barPx < 20f * density || barPx > width * 0.7f) return
        val bx = 16f * density
        val by = height - 20f * density
        canvas.drawLine(bx, by, bx + barPx, by, scaleBarPaint)
        canvas.drawLine(bx, by - 5f * density, bx, by + 5f * density, scaleBarPaint)
        canvas.drawLine(bx + barPx, by - 5f * density, bx + barPx, by + 5f * density, scaleBarPaint)
        val label = if (step >= 1000) "${(step / 1000).toInt()} km" else "${step.toInt()} m"
        canvas.drawText(label, bx + 4f * density, by - 7f * density, scaleTextPaint)
    }

    private fun cancelLongPress() {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x; downY = event.y
                lastX = event.x; lastY = event.y
                moved = false
                multiTouch = false
                lastPinchDist = 0f
                dragIndex = hitTest(event.x, event.y)

                if (dragIndex >= 0) {
                    val idx = dragIndex
                    val r = Runnable {
                        val e = engine ?: return@Runnable
                        if (idx < e.points.size) {
                            e.points.removeAt(idx)
                            e.rebuild()
                            onTrackChanged?.invoke()
                            fitView()
                            invalidate()
                        }
                        dragIndex = -1
                    }
                    longPressRunnable = r
                    handler.postDelayed(r, 550)
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                cancelLongPress()
                dragIndex = -1
                lastPinchDist = 0f
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    handlePinch(event)
                } else if (dragIndex >= 0) {
                    if (!moved && hypot(event.x - downX, event.y - downY) > touchSlop) {
                        moved = true
                        cancelLongPress()
                    }
                    if (moved) {
                        engine?.points?.set(dragIndex, screenToGeo(event.x, event.y))
                        invalidate()
                    }
                } else {
                    if (!moved && hypot(event.x - downX, event.y - downY) > touchSlop) {
                        moved = true
                    }
                    if (moved) {
                        ox += event.x - lastX
                        oy += event.y - lastY
                        invalidate()
                    }
                }
                lastX = event.x; lastY = event.y
            }

            MotionEvent.ACTION_POINTER_UP -> {
                lastPinchDist = 0f
                if (event.pointerCount <= 2) multiTouch = false
            }

            MotionEvent.ACTION_UP -> {
                cancelLongPress()
                if (!moved && !multiTouch) {
                    if (dragIndex < 0) {
                        val gp = screenToGeo(event.x, event.y)
                        val e = engine
                        if (e != null) {
                            e.points.add(gp)
                            e.rebuild()
                            onTrackChanged?.invoke()
                            fitView()
                            invalidate()
                        }
                    }
                } else if (dragIndex >= 0 && moved) {
                    engine?.rebuild()
                    onTrackChanged?.invoke()
                    fitView()
                    invalidate()
                }
                dragIndex = -1
                moved = false
                multiTouch = false
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelLongPress()
                dragIndex = -1
                moved = false
                multiTouch = false
            }
        }
        return true
    }

    private fun handlePinch(event: MotionEvent) {
        val x0 = event.getX(0); val y0 = event.getY(0)
        val x1 = event.getX(1); val y1 = event.getY(1)
        val dist = hypot(x1 - x0, y1 - y0)
        val midX = (x0 + x1) / 2f
        val midY = (y0 + y1) / 2f

        if (lastPinchDist > 0f && dist > 0f) {
            var k = dist / lastPinchDist
            k = k.coerceIn(0.5f, 2f)
            val newScale = (scale * k).coerceIn(0.002f, 100f)
            val realK = newScale / scale
            ox = midX + (ox - midX) * realK
            oy = midY + (oy - midY) * realK
            scale = newScale
            invalidate()
        }
        lastPinchDist = dist
    }

    private fun hitTest(x: Float, y: Float): Int {
        val pts = engine?.points ?: return -1
        val r = 28f * density
        for (i in pts.indices.reversed()) {
            val s = toScreen(pts[i].lat, pts[i].lng)
            if ((s.x - x) * (s.x - x) + (s.y - y) * (s.y - y) <= r * r) return i
        }
        return -1
    }
}
