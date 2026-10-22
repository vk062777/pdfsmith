package com.vk062777.pdfsmith

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/** Initialises PDFBox and applies the saved theme before any UI is created. */
class PdfSmithApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(when (themePref(this)) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        })
        PDFBoxResourceLoader.init(applicationContext)
    }

    companion object {
        const val PREFS = "novapdf"
        const val KEY_THEME = "theme"
        const val KEY_ACCENT = "accent"

        fun themePref(context: android.content.Context): Int =
            context.getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_THEME, 0)

        fun setThemePref(context: android.content.Context, mode: Int) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_THEME, mode).apply()
        }

        fun accentPref(context: android.content.Context): Int =
            context.getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_ACCENT, 0)

        fun setAccentPref(context: android.content.Context, index: Int) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_ACCENT, index).apply()
        }
    }
}
