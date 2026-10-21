package com.vk062777.pdfsmith.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.vk062777.pdfsmith.model.Annot

/**
 * Draws annotations. The very same routine powers the on-screen preview and the
 * exported raster layer, so preview and output are consistent.
 */
object AnnotRenderer {

    fun draw(
        canvas: Canvas,
        items: List<Annot>,
        pageW: Float,
        pageH: Float,
        pxPerPt: Float,
        ox: Float,
        oy: Float
    ) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        for (item in items) {
            when (item) {
                is Annot.Cover -> {
                    fill.style = Paint.Style.FILL
                    fill.color = item.color
                    canvas.drawRect(
                        ox + item.fx * pageW * pxPerPt, oy + item.fy * pageH * pxPerPt,
                        ox + (item.fx + item.fw) * pageW * pxPerPt,
                        oy + (item.fy + item.fh) * pageH * pxPerPt, fill
                    )
                }
                is Annot.Highlight -> {
                    fill.style = Paint.Style.FILL
                    fill.color = withAlpha(item.color, 0.35f)
                    canvas.drawRect(
                        ox + item.fx * pageW * pxPerPt, oy + item.fy * pageH * pxPerPt,
                        ox + (item.fx + item.fw) * pageW * pxPerPt,
                        oy + (item.fy + item.fh) * pageH * pxPerPt, fill
                    )
                }
                is Annot.Stroke -> {
                    if (item.points.size < 2) continue
                    val path = Path()
                    item.points.forEachIndexed { i, p ->
                        val x = ox + p.x * pageW * pxPerPt
                        val y = oy + p.y * pageH * pxPerPt
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    stroke.color = withAlpha(item.color, item.opacity)
                    stroke.strokeWidth = (item.widthPt * pxPerPt).coerceAtLeast(1f)
                    canvas.drawPath(path, stroke)
                }
                is Annot.Text -> {
                    textPaint.color = item.color
                    textPaint.textSize = (item.sizePt * pxPerPt).coerceAtLeast(4f)
                    canvas.drawText(
                        item.text,
                        ox + item.fx * pageW * pxPerPt,
                        oy + item.fy * pageH * pxPerPt,
                        textPaint
                    )
                }
                is Annot.Stamp -> {
                    val dst = RectF(
                        ox + item.fx * pageW * pxPerPt, oy + item.fy * pageH * pxPerPt,
                        ox + (item.fx + item.fw) * pageW * pxPerPt,
                        oy + (item.fy + item.fh) * pageH * pxPerPt
                    )
                    canvas.drawBitmap(item.bitmap, null, dst, fill)
                }
            }
        }
    }

    /** Rasterise the annotations for one page onto a transparent bitmap. */
    fun rasterize(items: List<Annot>, pageW: Float, pageH: Float, scale: Float): Bitmap {
        val w = (pageW * scale).toInt().coerceAtLeast(1)
        val h = (pageH * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), items, pageW, pageH, scale, 0f, 0f)
        return bmp
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (Color.alpha(color) * alpha).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }
}
