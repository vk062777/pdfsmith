package com.vk062777.pdfsmith.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.vk062777.pdfsmith.model.Annot

/**
 * Transparent drawing surface laid over the page image. It converts touches into
 * normalised page coordinates (via the image view's matrix) and records annotations.
 */
class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Tool { NONE, HIGHLIGHT, DRAW, TEXT, SIGN, IMAGE }

    var imageView: ZoomableImageView? = null
    var pageW: Float = 595f
    var pageH: Float = 842f
    var tool: Tool = Tool.NONE

    var items: MutableList<Annot> = mutableListOf()

    var color: Int = Color.BLACK
    var strokeWidthPt: Float = 2.5f
    var textSizePt: Float = 14f

    var onTextAt: ((Float, Float) -> Unit)? = null
    var onStampAt: ((Float, Float) -> Unit)? = null

    private val preview = Paint(Paint.ANTI_ALIAS_FLAG)
    private var dragStart: PointF? = null
    private var dragNow: PointF? = null
    private var stroke: MutableList<PointF>? = null
    private var downAt: PointF? = null

    fun clearItems() {
        items.clear()
        invalidate()
    }

    fun refresh() = invalidate()

    private fun pageRect(): RectF {
        val r = RectF()
        val iv = imageView
        if (iv != null) {
            val d = iv.drawable
            if (d != null && d.intrinsicWidth > 0) {
                val pts = floatArrayOf(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
                iv.imageMatrix.mapPoints(pts)
                r.set(pts[0], pts[1], pts[2], pts[3])
                return r
            }
        }
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        return r
    }

    private fun normalize(x: Float, y: Float): PointF {
        val r = pageRect()
        if (r.width() <= 0f || r.height() <= 0f) return PointF(0f, 0f)
        return PointF(((x - r.left) / r.width()).coerceIn(0f, 1f), ((y - r.top) / r.height()).coerceIn(0f, 1f))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = pageRect()
        if (r.width() <= 0f) return
        val pxPerPt = r.width() / pageW
        AnnotRenderer.draw(canvas, items, pageW, pageH, pxPerPt, r.left, r.top)

        // live previews
        val hs = dragStart
        val hn = dragNow
        if (tool == Tool.HIGHLIGHT && hs != null && hn != null) {
            preview.style = Paint.Style.FILL
            preview.color = Color.argb(90, 255, 235, 59)
            canvas.drawRect(
                r.left + hs.x * r.width(), r.top + hs.y * r.height(),
                r.left + hn.x * r.width(), r.top + hn.y * r.height(), preview
            )
        }
        val s = stroke
        if (tool == Tool.DRAW && s != null && s.size >= 2) {
            preview.style = Paint.Style.STROKE
            preview.strokeCap = Paint.Cap.ROUND
            preview.strokeJoin = Paint.Join.ROUND
            preview.strokeWidth = (strokeWidthPt * pxPerPt).coerceAtLeast(1f)
            preview.color = color
            val path = android.graphics.Path()
            s.forEachIndexed { i, p ->
                val x = r.left + p.x * r.width()
                val y = r.top + p.y * r.height()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, preview)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (tool == Tool.NONE) return false
        val p = normalize(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAt = p
                when (tool) {
                    Tool.HIGHLIGHT -> { dragStart = p; dragNow = p }
                    Tool.DRAW -> stroke = mutableListOf(p)
                    else -> { }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (tool) {
                    Tool.HIGHLIGHT -> { dragNow = p }
                    Tool.DRAW -> stroke?.add(p)
                    else -> { }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                when (tool) {
                    Tool.HIGHLIGHT -> {
                        val a = dragStart
                        if (a != null) {
                            val x0 = minOf(a.x, p.x); val y0 = minOf(a.y, p.y)
                            val w = kotlin.math.abs(p.x - a.x); val h = kotlin.math.abs(p.y - a.y)
                            if (w > 0.01f && h > 0.01f) {
                                items.add(Annot.Highlight(x0, y0, w, h, Color.YELLOW))
                            }
                        }
                    }
                    Tool.DRAW -> {
                        val s = stroke
                        if (s != null && s.size >= 2) items.add(Annot.Stroke(s, strokeWidthPt, color))
                    }
                    Tool.TEXT, Tool.SIGN, Tool.IMAGE -> {
                        val d = downAt
                        val moved = d != null && (kotlin.math.abs(p.x - d.x) > 0.02f || kotlin.math.abs(p.y - d.y) > 0.02f)
                        if (!moved && d != null) {
                            if (tool == Tool.TEXT) onTextAt?.invoke(d.x, d.y)
                            else onStampAt?.invoke(d.x, d.y)
                        }
                    }
                    else -> { }
                }
                dragStart = null; dragNow = null; stroke = null; downAt = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
