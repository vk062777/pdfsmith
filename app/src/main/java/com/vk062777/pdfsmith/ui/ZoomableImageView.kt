package com.vk062777.pdfsmith.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * An ImageView with smooth pinch-to-zoom, pan and double-tap zoom. It keeps the
 * image centred and clamps panning so the page can never be dragged off-screen.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val m = Matrix()
    private var zoom = 1f
    private val minZoom = 1f
    private val maxZoom = 8f
    private var tx = 0f
    private var ty = 0f

    var onZoomChanged: ((Boolean) -> Unit)? = null

    init {
        scaleType = ScaleType.MATRIX
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val old = zoom
                zoom = (zoom * detector.scaleFactor).coerceIn(minZoom, maxZoom)
                val f = zoom / old
                val cx = detector.focusX - width / 2f
                val cy = detector.focusY - height / 2f
                tx -= cx * (f - 1f)
                ty -= cy * (f - 1f)
                apply()
                notifyZoom()
                return true
            }
        }
    )

    private val gesture = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (zoom <= 1f) return false
                tx -= dx
                ty -= dy
                apply()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (zoom > 1.05f) {
                    reset()
                } else {
                    zoom = 2.5f
                    tx = 0f
                    ty = 0f
                    apply()
                    notifyZoom()
                }
                return true
            }
        }
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gesture.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(zoom > 1f)
            MotionEvent.ACTION_POINTER_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        reset()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        reset()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reset()
    }

    fun reset() {
        zoom = 1f
        tx = 0f
        ty = 0f
        apply()
        notifyZoom()
    }

    fun isZoomed() = zoom > 1.05f

    private fun notifyZoom() = onZoomChanged?.invoke(isZoomed())

    private fun apply() {
        val d = drawable
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (d == null || vw <= 0f || vh <= 0f) {
            imageMatrix = m
            return
        }
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0f || dh <= 0f) {
            imageMatrix = m
            return
        }
        val base = minOf(vw / dw, vh / dh)
        val s = base * zoom
        val w = dw * s
        val h = dh * s
        val maxTx = maxOf(0f, (w - vw) / 2f)
        val maxTy = maxOf(0f, (h - vh) / 2f)
        tx = tx.coerceIn(-maxTx, maxTx)
        ty = ty.coerceIn(-maxTy, maxTy)
        m.reset()
        m.postScale(s, s)
        m.postTranslate((vw - w) / 2f + tx, (vh - h) / 2f + ty)
        imageMatrix = m
    }
}
