package com.vk062777.pdfsmith.model

import android.graphics.Bitmap
import android.graphics.PointF

/**
 * An annotation on a page, stored in normalised page coordinates:
 * fx/fy are fractions of page width/height with the origin at the top-left.
 * Keeping them normalised means preview (on screen) and export (rasterised at
 * print resolution) use exactly the same numbers, so what you see is what you get.
 */
sealed class Annot {
    class Text(
        var fx: Float, var fy: Float, var sizePt: Float, var color: Int, var text: String
    ) : Annot()

    class Highlight(
        var fx: Float, var fy: Float, var fw: Float, var fh: Float, var color: Int
    ) : Annot()

    class Stroke(
        val points: MutableList<PointF>, var widthPt: Float, var color: Int
    ) : Annot()

    class Stamp(
        var fx: Float, var fy: Float, var fw: Float, var fh: Float, val bitmap: Bitmap
    ) : Annot()
}
