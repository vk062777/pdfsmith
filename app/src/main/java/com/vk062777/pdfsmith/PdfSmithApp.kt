package com.vk062777.pdfsmith

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * Initialises the PDFBox resource loader once for the whole process. PDFBox needs
 * this to locate the standard font metrics bundled in its assets.
 */
class PdfSmithApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
    }
}
