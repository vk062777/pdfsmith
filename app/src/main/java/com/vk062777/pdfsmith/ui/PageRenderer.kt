package com.vk062777.pdfsmith.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer

/** Renders PDF pages to bitmaps using the platform renderer (fast, offline). */
class PageRenderer(private val renderer: PdfRenderer) {

    val pageCount: Int get() = renderer.pageCount

    @Synchronized
    fun render(index: Int, targetWidth: Int): Bitmap {
        val page = renderer.openPage(index)
        try {
            val ratio = page.height.toFloat() / page.width.toFloat()
            val w = targetWidth.coerceAtLeast(1)
            val h = (w * ratio).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            page.close()
        }
    }

    @Synchronized
    fun close() {
        try { renderer.close() } catch (_: Exception) { }
    }
}
