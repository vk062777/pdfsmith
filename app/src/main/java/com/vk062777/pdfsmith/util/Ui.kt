package com.vk062777.pdfsmith.util

import android.app.Activity
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.snackbar.Snackbar
import com.vk062777.pdfsmith.R

/** A simple, non-cancelable progress dialog used while the engine works. */
class Busy(private val activity: Activity) {
    private var dialog: AlertDialog? = null

    fun show(text: String) {
        dismiss()
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_busy, null)
        view.findViewById<TextView>(R.id.busyText).text = text
        dialog = AlertDialog.Builder(activity).setView(view).setCancelable(false).create()
        dialog?.show()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }
}

fun Activity.snack(message: String) {
    val root = findViewById<android.view.View>(android.R.id.content)
    Snackbar.make(root, message, Snackbar.LENGTH_LONG).show()
}
