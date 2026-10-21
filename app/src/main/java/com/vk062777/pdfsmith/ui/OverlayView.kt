package com.vk062777.pdfsmith.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.vk062777.pdfsmith.model.Annot
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Transparent drawing surface over the page image. It converts touches into
 * normalised page coordinates, records annotations, and lets an item be selected,
 * dragged and pinched to resize. Two-finger gestures are handed to the image view
 * so zooming always works.
 */
class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Tool { PAN, PEN, HIGHLIGHT, TEXT, COVER, SIGN, IMAGE, SELECT, ERASE }

    var imageView: ZoomableImageView? = null
    var pageW: Float = 595f
    var pageH: Float = 842f
    var tool: Tool = Tool.PAN

    var items: MutableList<Annot> = mutableListOf()
    var selected: Int = -1

    var color: Int = Color.BLACK
    var penWidthPt: Float = 3f
    var highlightWidthPt: Float = 18f
    var highlightOpacity: Float = 0.35f
    var textSizePt: Float = 16f

    var onTextAt: ((Float, Float) -> Unit)? = null
    var onStampAt: ((Float, Float) -> Unit)? = null
    var onCoverRect: ((Float, Float, Float, Float) -> Unit)? = null
    var onBeforeChange: (() -> Unit)? = null
    var onChanged: (() -> Unit)? = null
    var onSelectionChanged: ((Int) -> Unit)? = null

    private val preview = Paint(Paint.ANTI_ALIAS_FLAG)
    private var dragStart: PointF? = null
    private var dragNow: PointF? = null
    private var stroke: MutableList<PointF>? = null
    private var downAt: PointF? = null
    private var moving = false
    private var moveLast: PointF? = null
    private var changeNotified = false

    private val resizeDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (selected !in items.indices) return false
                if (!changeNotified) { onBeforeChange?.invoke(); changeNotified = true }
                scaleItem(selected, detector.scaleFactor)
                invalidate()
                onChanged?.invoke()
                return true
            }
        }
    )

    fun applyTool(t: Tool) {
        tool = t
        if (t != Tool.SELECT) selectItem(-1)
        imageView?.panEnabled = (t == Tool.PAN)
        invalidate()
    }

    fun refresh() = invalidate()

    fun selectItem(index: Int) {
        selected = index
        onSelectionChanged?.invoke(index)
        invalidate()
    }

    // ---- geometry ----------------------------------------------------------

    private fun pageRect(): RectF {
        val r = RectF()
        val iv = imageView
        val d = iv?.drawable
        if (iv != null && d != null && d.intrinsicWidth > 0) {
            val pts = floatArrayOf(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
            iv.imageMatrix.mapPoints(pts)
            r.set(pts[0], pts[1], pts[2], pts[3])
            return r
        }
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        return r
    }

    private fun normalize(x: Float, y: Float): PointF {
        val r = pageRect()
        if (r.width() <= 0f || r.height() <= 0f) return PointF(0f, 0f)
        return PointF(
            ((x - r.left) / r.width()).coerceIn(0f, 1f),
            ((y - r.top) / r.height()).coerceIn(0f, 1f)
        )
    }

    private fun bounds(a: Annot): RectF = when (a) {
        is Annot.Cover -> RectF(a.fx, a.fy, a.fx + a.fw, a.fy + a.fh)
        is Annot.Highlight -> RectF(a.fx, a.fy, a.fx + a.fw, a.fy + a.fh)
        is Annot.Stamp -> RectF(a.fx, a.fy, a.fx + a.fw, a.fy + a.fh)
        is Annot.Text -> {
            val w = a.text.length * a.sizePt * 0.5f / pageW
            val h = a.sizePt / pageH
            RectF(a.fx, a.fy - h, a.fx + max(w, 0.02f), a.fy + h * 0.25f)
        }
        is Annot.Stroke -> {
            var l = 1f; var t = 1f; var rr = 0f; var b = 0f
            for (p in a.points) { l = min(l, p.x); t = min(t, p.y); rr = max(rr, p.x); b = max(b, p.y) }
            val pad = max(0.01f, a.widthPt / pageH)
            RectF(l - pad, t - pad, rr + pad, b + pad)
        }
    }

    private fun hitTest(nx: Float, ny: Float): Int {
        for (i in items.indices.reversed()) {
            val b = bounds(items[i])
            if (nx >= b.left && nx <= b.right && ny >= b.top && ny <= b.bottom) return i
        }
        return -1
    }

    private fun moveItem(i: Int, dfx: Float, dfy: Float) {
        if (i !in items.indices) return
        when (val a = items[i]) {
            is Annot.Cover -> { a.fx = (a.fx + dfx).coerceIn(-a.fw + 0.02f, 1f - 0.02f); a.fy = (a.fy + dfy).coerceIn(-a.fh + 0.02f, 1f - 0.02f) }
            is Annot.Highlight -> { a.fx = (a.fx + dfx).coerceIn(-a.fw + 0.02f, 1f - 0.02f); a.fy = (a.fy + dfy).coerceIn(-a.fh + 0.02f, 1f - 0.02f) }
            is Annot.Stamp -> { a.fx = (a.fx + dfx).coerceIn(-a.fw + 0.02f, 1f - 0.02f); a.fy = (a.fy + dfy).coerceIn(-a.fh + 0.02f, 1f - 0.02f) }
            is Annot.Text -> { a.fx = (a.fx + dfx).coerceIn(0f, 1f); a.fy = (a.fy + dfy).coerceIn(0f, 1f) }
            is Annot.Stroke -> a.points.forEach { it.x += dfx; it.y += dfy }
        }
    }

    private fun scaleItem(i: Int, f: Float) {
        if (i !in items.indices) return
        when (val a = items[i]) {
            is Annot.Cover -> { a.fw = (a.fw * f).coerceIn(0.03f, 1f); a.fh = (a.fh * f).coerceIn(0.03f, 1f) }
            is Annot.Highlight -> { a.fw = (a.fw * f).coerceIn(0.03f, 1f); a.fh = (a.fh * f).coerceIn(0.02f, 1f) }
            is Annot.Stamp -> { a.fw = (a.fw * f).coerceIn(0.05f, 1.5f); a.fh = (a.fh * f).coerceIn(0.05f, 1.5f) }
            is Annot.Text -> a.sizePt = (a.sizePt * f).coerceIn(6f, 96f)
            is Annot.Stroke -> {
                var cx = 0f; var cy = 0f
                for (p in a.points) { cx += p.x; cy += p.y }
                cx /= a.points.size; cy /= a.points.size
                a.points.forEach { it.x = cx + (it.x - cx) * f; it.y = cy + (it.y - cy) * f }
                a.widthPt = (a.widthPt * f).coerceIn(0.5f, 40f)
            }
        }
    }

    // ---- drawing -----------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = pageRect()
        if (r.width() <= 0f) return
        val pxPerPt = r.width() / pageW
        AnnotRenderer.draw(canvas, items, pageW, pageH, pxPerPt, r.left, r.top)

        // live previews
        val hs = dragStart
        val hn = dragNow
        if ((tool == Tool.HIGHLIGHT || tool == Tool.COVER) && hs != null && hn != null) {
            preview.style = Paint.Style.FILL
            preview.color = if (tool == Tool.COVER) color
            else Color.argb((highlightOpacity * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawRect(
                r.left + hs.x * r.width(), r.top + hs.y * r.height(),
                r.left + hn.x * r.width(), r.top + hn.y * r.height(), preview
            )
        }
        val s = stroke
        if (tool == Tool.PEN && s != null && s.size >= 2) {
            preview.style = Paint.Style.STROKE
            preview.strokeCap = Paint.Cap.ROUND
            preview.strokeJoin = Paint.Join.ROUND
            preview.strokeWidth = (penWidthPt * pxPerPt).coerceAtLeast(1f)
            preview.color = color
            val path = Path()
            s.forEachIndexed { i, p ->
                val x = r.left + p.x * r.width()
                val y = r.top + p.y * r.height()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, preview)
        }

        // selection outline
        if (tool == Tool.SELECT && selected in items.indices) {
            val b = bounds(items[selected])
            preview.style = Paint.Style.STROKE
            preview.strokeWidth = 2f * resources.displayMetrics.density
            preview.color = Color.argb(220, 59, 91, 219)
            preview.pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
            canvas.drawRect(
                r.left + b.left * r.width(), r.top + b.top * r.height(),
                r.left + b.right * r.width(), r.top + b.bottom * r.height(), preview
            )
            preview.pathEffect = null
        }
    }

    // ---- touch -------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (tool == Tool.SELECT && selected in items.indices && event.pointerCount >= 2) {
            resizeDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL) changeNotified = false
            return true
        }

        // everything else is handed to the image view so pinch-zoom / pan keep working
        imageView?.onTouchEvent(event)
        if (event.pointerCount >= 2) return true

        val p = normalize(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAt = p
                changeNotified = false
                when (tool) {
                    Tool.PEN -> { onBeforeChange?.invoke(); changeNotified = true; stroke = mutableListOf(p) }
                    Tool.HIGHLIGHT, Tool.COVER -> { dragStart = p; dragNow = p }
                    Tool.SELECT -> {
                        val hit = hitTest(p.x, p.y)
                        selectItem(hit)
                        if (hit >= 0) { moving = true; moveLast = p; onBeforeChange?.invoke(); changeNotified = true }
                    }
                    else -> { }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (tool) {
                    Tool.PEN -> stroke?.add(p)
                    Tool.HIGHLIGHT, Tool.COVER -> dragNow = p
                    Tool.SELECT -> {
                        val last = moveLast
                        if (moving && last != null && selected in items.indices) {
                            moveItem(selected, p.x - last.x, p.y - last.y)
                            moveLast = p
                            onChanged?.invoke()
                        }
                    }
                    else -> { }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                when (tool) {
                    Tool.PEN -> {
                        val st = stroke
                        if (st != null && st.size >= 2) {
                            items.add(Annot.Stroke(st, penWidthPt, color, 1f))
                            onChanged?.invoke()
                        }
                    }
                    Tool.HIGHLIGHT -> {
                        val a = dragStart
                        if (a != null) {
                            val x0 = min(a.x, p.x); val y0 = min(a.y, p.y)
                            val w = abs(p.x - a.x); val h = abs(p.y - a.y)
                            if (w > 0.01f && h > 0.004f) {
                                items.add(Annot.Highlight(x0, y0, w, h, color))
                                onChanged?.invoke()
                            }
                        }
                    }
                    Tool.COVER -> {
                        val a = dragStart
                        if (a != null) {
                            val x0 = min(a.x, p.x); val y0 = min(a.y, p.y)
                            val w = abs(p.x - a.x); val h = abs(p.y - a.y)
                            if (w > 0.01f && h > 0.004f) onCoverRect?.invoke(x0, y0, w, h)
                        }
                    }
                    Tool.TEXT, Tool.SIGN, Tool.IMAGE -> {
                        val d = downAt
                        val moved = d != null &&
                                (abs(p.x - d.x) > 0.02f || abs(p.y - d.y) > 0.02f)
                        if (!moved && d != null) {
                            if (tool == Tool.TEXT) onTextAt?.invoke(d.x, d.y)
                            else onStampAt?.invoke(d.x, d.y)
                        }
                    }
                    Tool.ERASE -> {
                        val hit = hitTest(p.x, p.y)
                        if (hit >= 0) {
                            onBeforeChange?.invoke()
                            items.removeAt(hit)
                            selectItem(-1)
                            onChanged?.invoke()
                        }
                    }
                    else -> { }
                }
                dragStart = null; dragNow = null; stroke = null; downAt = null
                moving = false; moveLast = null; changeNotified = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
