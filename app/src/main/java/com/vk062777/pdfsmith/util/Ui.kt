package com.vk062777.pdfsmith.util

import android.app.Activity
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.vk062777.pdfsmith.R

/** A simple, non-cancelable progress dialog used while the engine works. */
class Busy(private val activity: Activity) {
    private var dialog: AlertDialog? = null
    private var text: TextView? = null

    private fun alive() = !activity.isFinishing && !activity.isDestroyed

    fun show(text: String) {
        if (!alive()) return
        dismiss()
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_busy, null)
        this.text = view.findViewById(R.id.busyText)
        this.text?.text = text
        dialog = AlertDialog.Builder(activity).setView(view).setCancelable(false).create()
        dialog?.show()
    }

    /** Update the message while work is in progress (e.g. "page 3 of 12"). */
    fun update(message: String) {
        if (!alive()) return
        text?.text = message
    }

    fun dismiss() {
        try { dialog?.dismiss() } catch (_: Exception) { }
        dialog = null
    }
}

