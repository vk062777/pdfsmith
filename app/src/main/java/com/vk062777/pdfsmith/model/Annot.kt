package com.vk062777.pdfsmith.model

import android.graphics.Bitmap
import android.graphics.PointF

/**
 * An annotation, stored in normalised page coordinates (fractions of page width /
 * height, origin top-left). Preview and export use the same numbers, so what you
 * see is what you get.
 */
sealed class Annot {

    abstract fun copy(): Annot

    class Text(
        var fx: Float, var fy: Float, var sizePt: Float, var color: Int, var text: String
    ) : Annot() {
        override fun copy() = Text(fx, fy, sizePt, color, text)
    }

    class Highlight(
        var fx: Float, var fy: Float, var fw: Float, var fh: Float, var color: Int
    ) : Annot() {
        override fun copy() = Highlight(fx, fy, fw, fh, color)
    }

    /** Opaque rectangle used to cover existing text before retyping over it. */
    class Cover(
        var fx: Float, var fy: Float, var fw: Float, var fh: Float, var color: Int
    ) : Annot() {
        override fun copy() = Cover(fx, fy, fw, fh, color)
    }

    class Stroke(
        val points: MutableList<PointF>, var widthPt: Float, var color: Int, var opacity: Float = 1f
    ) : Annot() {
        override fun copy() =
            Stroke(points.map { PointF(it.x, it.y) }.toMutableList(), widthPt, color, opacity)
    }

    class Stamp(
        var fx: Float, var fy: Float, var fw: Float, var fh: Float, val bitmap: Bitmap
    ) : Annot() {
        override fun copy() = Stamp(fx, fy, fw, fh, bitmap)
    }
}
