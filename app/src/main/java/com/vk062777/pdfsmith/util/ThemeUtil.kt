package com.vk062777.pdfsmith.util

import android.app.Activity
import android.content.Context
import androidx.annotation.StyleRes
import com.vk062777.pdfsmith.PdfSmithApp
import com.vk062777.pdfsmith.R

/** Applies the user's chosen accent colour theme to an Activity. */
object ThemeUtil {

    val ACCENTS: List<Pair<String, Int>> = listOf(
        "Indigo" to R.style.Theme_PDFsmith,
        "Teal" to R.style.Theme_PDFsmith_Teal,
        "Violet" to R.style.Theme_PDFsmith_Violet,
        "Green" to R.style.Theme_PDFsmith_Green,
        "Crimson" to R.style.Theme_PDFsmith_Crimson
    )

    @StyleRes
    fun themeRes(context: Context): Int {
        val i = PdfSmithApp.accentPref(context).coerceIn(0, ACCENTS.size - 1)
        return ACCENTS[i].second
    }

    /** Call before setContentView(). */
    fun apply(activity: Activity) {
        activity.setTheme(themeRes(activity))
    }
}
