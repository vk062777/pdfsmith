package com.vk062777.pdfsmith

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vk062777.pdfsmith.util.Busy
import com.vk062777.pdfsmith.util.Files
import com.vk062777.pdfsmith.util.ThemeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shared base for every tool screen. It wires up the Storage Access Framework
 * launchers (pick PDF(s), pick images, pick a folder, create an output document)
 * and runs engine work off the main thread behind a progress dialog.
 */
abstract class PdfActivity : AppCompatActivity() {

    protected lateinit var busy: Busy
    private var pendingProducer: ((File) -> Unit)? = null
    private var pendingName: String = "output.pdf"

    protected val openPdfLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) { takePersist(uri); onPdfChosen(uri) }
        }

    protected val openPdfsLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            uris.forEach { takePersist(it) }
            if (uris.isNotEmpty()) onPdfsChosen(uris)
        }

    protected val openImagesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            uris.forEach { takePersist(it) }
            if (uris.isNotEmpty()) onImagesChosen(uris)
        }

    protected val openTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) onTreeChosen(uri)
        }

    private val createDocLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            val producer = pendingProducer
            pendingProducer = null
            if (uri == null || producer == null) return@registerForActivityResult
            val out = Files.newFile(this, pendingName)
            runTask(getString(R.string.msg_processing), onDone = saved@{ result ->
                if (result == null) return@saved
                showSuccess(getString(R.string.msg_saved_to, pendingName), result)
                onSaved(result)
            }) {
                producer(out)
                Files.writeToUri(this, out, uri)
                out
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.apply(this)
        super.onCreate(savedInstanceState)
        busy = Busy(this)
    }

    /**
     * Copy the picked PDF into the cache, asking for a password when the document is
     * protected. [onReady] always receives a file that can actually be opened.
     */
    protected fun openPdf(uri: Uri, onReady: (File) -> Unit) {
        runTask(getString(R.string.msg_processing), onDone = checked@{ res ->
            if (res == null) return@checked
            if (!res.second) { onReady(res.first); return@checked }
            askPassword(res.first, onReady)
        }) {
            val f = Files.copyToCache(this, uri)
            f to com.vk062777.pdfsmith.engine.PdfEngine.needsPassword(f)
        }
    }

    private fun askPassword(file: File, onReady: (File) -> Unit) {
        val v = layoutInflater.inflate(R.layout.dialog_password, null)
        val input = v.findViewById<android.widget.EditText>(R.id.pwInput)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.msg_password_required)
            .setView(v)
            .setPositiveButton(R.string.action_open, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pw = input.text?.toString().orEmpty()
                if (pw.isEmpty()) {
                    toast(getString(R.string.msg_enter_password))
                    return@setOnClickListener
                }
                dialog.dismiss()
                runTask(getString(R.string.msg_processing), onDone = opened@{ out ->
                    if (out != null) {
                        toast(getString(R.string.msg_unlocked_note))
                        onReady(out)
                    }
                }) {
                    val dec = Files.newFile(this, "unlocked.pdf")
                    try {
                        com.vk062777.pdfsmith.engine.PdfEngine.decrypt(file, pw, dec)
                    } catch (e: Exception) {
                        error(getString(R.string.msg_wrong_password))
                    }
                    dec
                }
            }
        }
        dialog.show()
    }

    /** A friendly "all done" popup with Open / Share / Close. */
    protected fun showSuccess(message: String, file: File?, mime: String = "application/pdf") {
        val view = layoutInflater.inflate(R.layout.dialog_success, null)
        view.findViewById<TextView>(R.id.successMsg).text = message

        val icon = view.findViewById<View>(R.id.successIcon)
        icon.scaleX = 0.5f
        icon.scaleY = 0.5f
        icon.alpha = 0f
        icon.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(260)
            .setInterpolator(OvershootInterpolator())
            .start()

        val dialog = AlertDialog.Builder(this).setView(view).setCancelable(true).create()

        val open = view.findViewById<View>(R.id.successOpen)
        val share = view.findViewById<View>(R.id.successShare)
        open.isEnabled = file != null
        share.isEnabled = file != null
        open.setOnClickListener {
            file?.let { Files.openWith(this, it, mime) }
            dialog.dismiss()
        }
        share.setOnClickListener { file?.let { Files.share(this, it, mime) } }
        view.findViewById<View>(R.id.successClose).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    // ---- overridable hooks -------------------------------------------------
    protected open fun onPdfChosen(uri: Uri) {}
    protected open fun onPdfsChosen(uris: List<Uri>) {}
    protected open fun onImagesChosen(uris: List<Uri>) {}
    protected open fun onTreeChosen(uri: Uri) {}
    protected open fun onSaved(file: File) {}

    // ---- launchers ---------------------------------------------------------
    protected fun pickPdf() = openPdfLauncher.launch(arrayOf("application/pdf"))
    protected fun pickPdfs() = openPdfsLauncher.launch(arrayOf("application/pdf"))
    protected fun pickImages() = openImagesLauncher.launch(arrayOf("image/*"))
    protected fun pickTree() = openTreeLauncher.launch(null)

    /** Ask the user where to save, then run [produce] to build the output file. */
    protected fun runAndSave(name: String, produce: (File) -> Unit) {
        pendingName = name
        pendingProducer = produce
        createDocLauncher.launch(name)
    }

    /** Run [block] on a background thread with a progress dialog; [onDone] gets null on failure. */
    protected fun <T> runTask(status: String, onDone: (T?) -> Unit, block: () -> T) {
        busy.show(status)
        lifecycleScope.launch {
            val result: T? = try {
                withContext(Dispatchers.IO) { block() }
            } catch (t: Throwable) {
                toast(getString(R.string.msg_failed, t.message ?: t.javaClass.simpleName))
                null
            }
            busy.dismiss()
            onDone(result)
        }
    }

    protected fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun takePersist(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) { }
    }
}
