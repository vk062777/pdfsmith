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
 * Pinch-zoom / pan image view used for PDF pages.
 *
 * [panEnabled] lets a drawing overlay sit on top: when it is false a single-finger
 * drag no longer pans (so the overlay can draw) while two-finger pinch still zooms.
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
    private var suppressReset = false

    /** When false, single-finger drags are ignored (used while an overlay draws). */
    var panEnabled = true

    var onZoomChanged: ((Boolean) -> Unit)? = null

    /** Fires when a zoom gesture ends, with the resulting zoom factor. */
    var onZoomSettled: ((Float) -> Unit)? = null

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
                // Keep whatever is under the fingers under the fingers. The old
                // form scaled tx by 1 instead of f, so as soon as the page had been
                // panned the zoom drifted back toward the middle.
                val cx = detector.focusX - width / 2f
                val cy = detector.focusY - height / 2f
                tx = f * tx + (1f - f) * cx
                ty = f * ty + (1f - f) * cy
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
                if (!panEnabled || zoom <= 1f) return false
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
                onZoomSettled?.invoke(zoom)
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
            MotionEvent.ACTION_POINTER_UP ->
                if (event.pointerCount <= 2) onZoomSettled?.invoke(zoom)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                onZoomSettled?.invoke(zoom)
            }
        }
        // Hand the event to the framework as well: that is what runs the click and
        // long-press detection. Without it setOnLongClickListener never fires, which
        // is why long-pressing a page to copy its text did nothing.
        super.onTouchEvent(event)
        return true
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        if (!suppressReset) reset()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        if (!suppressReset) reset()
    }

    /** Swap the bitmap in while keeping the current zoom and pan (used for hi-res re-render). */
    fun setImageBitmapPreservingTransform(bm: Bitmap?) {
        val z = zoom
        val px = tx
        val py = ty
        suppressReset = true
        super.setImageBitmap(bm)
        suppressReset = false
        zoom = z
        tx = px
        ty = py
        apply()
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

    fun currentZoom() = zoom

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
