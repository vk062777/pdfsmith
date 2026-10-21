package com.vk062777.pdfsmith

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.vk062777.pdfsmith.databinding.ActivityToolsBinding
import com.vk062777.pdfsmith.engine.PdfEngine
import com.vk062777.pdfsmith.model.Action
import com.vk062777.pdfsmith.util.Files
import java.io.File

/**
 * A single screen that drives the one-shot document tools: compress, watermark,
 * protect, unlock, edit metadata, extract images, page numbers and split.
 */
class ToolsActivity : PdfActivity() {

    companion object {
        const val EXTRA_TOOL = "tool"
    }

    private lateinit var binding: ActivityToolsBinding
    private lateinit var tool: Action
    private var source: File? = null
    private var sourceName: String = "document.pdf"
    private var treeUri: Uri? = null
    private var pendingRun = false

    private var fText: EditText? = null
    private var fFont: EditText? = null
    private var fOpacity: EditText? = null
    private var fPw: EditText? = null
    private var fPw2: EditText? = null
    private var fRanges: EditText? = null
    private var fFormat: EditText? = null
    private var fTitle: EditText? = null
    private var fAuthor: EditText? = null
    private var fSubject: EditText? = null
    private var fKeywords: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityToolsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        tool = try {
            Action.valueOf(intent.getStringExtra(EXTRA_TOOL) ?: Action.COMPRESS.name)
        } catch (_: Exception) { Action.COMPRESS }

        applyToolChrome()
        binding.btnChoose.setOnClickListener { pickPdf() }
        binding.btnRun.setOnClickListener { onRun() }
        pickPdf()
    }

    private fun applyToolChrome() {
        val (title, desc) = when (tool) {
            Action.COMPRESS -> R.string.tool_compress to R.string.tool_compress_desc
            Action.WATERMARK -> R.string.tool_watermark to R.string.tool_watermark_desc
            Action.PROTECT -> R.string.tool_protect to R.string.tool_protect_desc
            Action.UNLOCK -> R.string.tool_unlock to R.string.tool_unlock_desc
            Action.METADATA -> R.string.tool_metadata to R.string.tool_metadata_desc
            Action.EXTRACT -> R.string.tool_extract to R.string.tool_extract_desc
            Action.PAGENUM -> R.string.tool_pagenum to R.string.tool_pagenum_desc
            Action.SPLIT -> R.string.tool_split to R.string.tool_split_desc
            else -> R.string.app_name to R.string.app_tagline
        }
        binding.toolbar.setTitle(title)
        binding.toolDesc.setText(desc)
    }

    override fun onPdfChosen(uri: Uri) {
        sourceName = Files.displayName(this, uri)
        runTask(getString(R.string.msg_processing)) {
            val f = Files.copyToCache(this, uri)
            if (PdfEngine.isEncrypted(f)) error(getString(R.string.msg_encrypted))
            source = f
            if (tool == Action.METADATA) PdfEngine.getMetadata(f) else emptyMap<String, String>()
        } result@{ meta ->
            binding.fileName.text = sourceName
            buildParams(meta ?: emptyMap())
            binding.btnRun.isEnabled = true
        }
    }

    override fun onTreeChosen(uri: Uri) {
        treeUri = uri
        val name = uri.lastPathSegment ?: "folder"
        binding.outputHint.visibility = android.view.View.VISIBLE
        binding.outputHint.text = "Output folder: $name"
        if (pendingRun) { pendingRun = false; onRun() }
    }

    private fun addField(hint: String, inputType: Int = InputType.TYPE_CLASS_TEXT, prefill: String = ""): EditText {
        val til = TextInputLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(12) }
            this.hint = hint
        }
        val et = TextInputEditText(this).apply {
            this.inputType = inputType
            setText(prefill)
        }
        til.addView(et)
        binding.params.addView(til)
        return et
    }

    private fun buildParams(meta: Map<String, String>) {
        binding.params.removeAllViews()
        fText = null; fFont = null; fOpacity = null; fPw = null; fPw2 = null
        fRanges = null; fFormat = null
        fTitle = null; fAuthor = null; fSubject = null; fKeywords = null

        when (tool) {
            Action.WATERMARK -> {
                fText = addField(getString(R.string.hint_watermark_text))
                fFont = addField(getString(R.string.hint_font_size), InputType.TYPE_CLASS_NUMBER, "60")
                fOpacity = addField(getString(R.string.hint_opacity), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, "0.25")
            }
            Action.PROTECT -> {
                fPw = addField(getString(R.string.hint_password), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
                fPw2 = addField(getString(R.string.hint_password_confirm), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            }
            Action.UNLOCK -> {
                fPw = addField(getString(R.string.hint_password), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            }
            Action.METADATA -> {
                fTitle = addField(getString(R.string.hint_title), InputType.TYPE_CLASS_TEXT, meta["title"] ?: "")
                fAuthor = addField(getString(R.string.hint_author), InputType.TYPE_CLASS_TEXT, meta["author"] ?: "")
                fSubject = addField(getString(R.string.hint_subject), InputType.TYPE_CLASS_TEXT, meta["subject"] ?: "")
                fKeywords = addField(getString(R.string.hint_keywords), InputType.TYPE_CLASS_TEXT, meta["keywords"] ?: "")
            }
            Action.PAGENUM -> {
                fFormat = addField(getString(R.string.hint_page_format), InputType.TYPE_CLASS_TEXT, "Page {n} of {total}")
                fFont = addField(getString(R.string.hint_font_size), InputType.TYPE_CLASS_NUMBER, "10")
            }
            Action.SPLIT -> {
                fRanges = addField(getString(R.string.hint_ranges), InputType.TYPE_CLASS_TEXT, "1-3, 4-6")
                binding.outputHint.visibility = android.view.View.VISIBLE
                binding.outputHint.text = "Choose a folder, then Run."
            }
            Action.EXTRACT -> {
                binding.outputHint.visibility = android.view.View.VISIBLE
                binding.outputHint.text = "Choose a folder, then Run."
            }
            Action.COMPRESS -> { }
            else -> { }
        }
    }

    private fun onRun() {
        when (tool) {
            Action.COMPRESS -> single { PdfEngine.compress(it.first, it.second) }
            Action.WATERMARK -> {
                val text = fText?.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) return toast(getString(R.string.msg_enter_text))
                val size = fFont?.text?.toString()?.toFloatOrNull() ?: 60f
                val op = (fOpacity?.text?.toString()?.toFloatOrNull() ?: 0.25f).coerceIn(0.05f, 1f)
                single { PdfEngine.watermarkText(it.first, text, size, op, 0xFFFF0000.toInt(), 45f, it.second) }
            }
            Action.PROTECT -> {
                val p1 = fPw?.text?.toString().orEmpty()
                val p2 = fPw2?.text?.toString().orEmpty()
                if (p1.isEmpty()) return toast(getString(R.string.msg_enter_password))
                if (p1 != p2) return toast(getString(R.string.msg_passwords_mismatch))
                single { PdfEngine.encrypt(it.first, p1, p1, it.second) }
            }
            Action.UNLOCK -> {
                val p = fPw?.text?.toString().orEmpty()
                if (p.isEmpty()) return toast(getString(R.string.msg_enter_password))
                single { PdfEngine.decrypt(it.first, p, it.second) }
            }
            Action.METADATA -> {
                val t = fTitle?.text?.toString(); val a = fAuthor?.text?.toString()
                val s = fSubject?.text?.toString(); val k = fKeywords?.text?.toString()
                single { PdfEngine.setMetadata(it.first, t, a, s, k, null, it.second) }
            }
            Action.PAGENUM -> {
                val fmt = fFormat?.text?.toString()?.ifBlank { "Page {n} of {total}" } ?: "Page {n} of {total}"
                val size = fFont?.text?.toString()?.toFloatOrNull() ?: 10f
                single { PdfEngine.addPageNumbers(it.first, fmt, size, 0xFF333333.toInt(), it.second) }
            }
            Action.EXTRACT -> {
                val src = source ?: return toast(getString(R.string.msg_pick_file_first))
                if (treeUri == null) { pendingRun = true; pickTree(); return }
                multi { dir -> PdfEngine.extractImages(src, dir) }
            }
            Action.SPLIT -> {
                val src = source ?: return toast(getString(R.string.msg_pick_file_first))
                val ranges = parseRanges(fRanges?.text?.toString().orEmpty())
                if (ranges.isEmpty()) return toast(getString(R.string.msg_invalid_ranges))
                if (treeUri == null) { pendingRun = true; pickTree(); return }
                val base = Files.baseName(sourceName)
                multi { dir -> PdfEngine.splitToFiles(src, ranges, dir, base) }
            }
            else -> { }
        }
    }

    /** Single-file output through the system "Save as" dialog. */
    private fun single(produce: (Pair<File, File>) -> Unit) {
        val src = source ?: return toast(getString(R.string.msg_pick_file_first))
        runAndSave(Files.suggestName(sourceName, suffixFor(tool))) { out ->
            produce(src to out)
        }
    }

    /** Multi-file output into a chosen folder. */
    private fun multi(produce: (File) -> List<File>) {
        val tree = treeUri ?: return
        runTask(getString(R.string.msg_processing)) {
            val dir = Files.newFile(this, "out").apply { if (exists()) delete(); mkdirs() }
            val files = produce(dir)
            val root = DocumentFile.fromTreeUri(this, tree) ?: error("Cannot open the folder")
            var saved = 0
            files.forEach { f ->
                val df = root.createFile(mimeFor(f.name), f.name)
                if (df != null) {
                    contentResolver.openOutputStream(df.uri)?.use { os -> f.inputStream().copyTo(os) }
                    saved++
                }
            }
            saved
        } result@{ n ->
            if (n != null) toast(getString(R.string.msg_saved_to, "$n files"))
        }
    }

    private fun suffixFor(tool: Action) = when (tool) {
        Action.COMPRESS -> "_compressed"
        Action.WATERMARK -> "_watermarked"
        Action.PROTECT -> "_protected"
        Action.UNLOCK -> "_unlocked"
        Action.METADATA -> "_metadata"
        Action.PAGENUM -> "_numbered"
        else -> "_out"
    }

    private fun mimeFor(name: String) = when {
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        else -> "application/pdf"
    }

    private fun parseRanges(s: String): List<IntArray> {
        val out = ArrayList<IntArray>()
        s.split(',').forEach { part ->
            val p = part.trim()
            if (p.isEmpty()) return@forEach
            if (p.contains('-')) {
                val a = p.split('-')
                if (a.size == 2) {
                    val f = a[0].trim().toIntOrNull()
                    val t = a[1].trim().toIntOrNull()
                    if (f != null && t != null && f >= 1 && t >= f) out.add(intArrayOf(f, t))
                }
            } else {
                val v = p.toIntOrNull()
                if (v != null && v >= 1) out.add(intArrayOf(v, v))
            }
        }
        return out
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
