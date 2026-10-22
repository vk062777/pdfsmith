package com.vk062777.pdfsmith

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.vk062777.pdfsmith.databinding.ActivityToolsBinding
import com.vk062777.pdfsmith.engine.PdfEngine
import com.vk062777.pdfsmith.engine.PdfSigner
import com.vk062777.pdfsmith.model.Action
import com.vk062777.pdfsmith.util.Files
import java.io.File

/**
 * One-shot document tools: rotate, compress, watermark, protect, unlock, sign,
 * verify, metadata, extract images, page numbers and split.
 */
class ToolsActivity : PdfActivity() {

    companion object {
        const val EXTRA_TOOL = "tool"

        private val FONT_NAMES = listOf("Helvetica", "Helvetica Bold", "Times", "Times Bold", "Courier")
        private val COLOUR_NAMES = listOf("Black", "Red", "Blue", "Green", "Orange", "Purple", "Grey")
        private val COLOUR_VALUES = listOf(
            0xFF111111.toInt(), 0xFFE03131.toInt(), 0xFF1C7ED6.toInt(), 0xFF2F9E44.toInt(),
            0xFFF08C00.toInt(), 0xFF7048E8.toInt(), 0xFF868E96.toInt()
        )
        private val LEVEL_NAMES = listOf("Light — best quality", "Balanced", "Strong — smallest file")
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
    private var fCreator: EditText? = null
    private var fProducer: EditText? = null
    private var fCreated: EditText? = null
    private var fModified: EditText? = null
    private var fSignName: EditText? = null
    private var fSignOrg: EditText? = null
    private var fSignReason: EditText? = null
    private var fSignLocation: EditText? = null
    private var fSignContact: EditText? = null
    private var fFontSpinner: Spinner? = null
    private var fColourSpinner: Spinner? = null
    private var fRandom: SwitchMaterial? = null
    private var fLevelSpinner: Spinner? = null
    private var fPagenumOpacity: EditText? = null
    private var fSplitAll: SwitchMaterial? = null
    private var fExtractPages: EditText? = null

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
            Action.SIGN -> R.string.tool_sign to R.string.tool_sign_desc
            Action.VERIFY -> R.string.tool_verify to R.string.tool_verify_desc
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
        if (tool == Action.UNLOCK) {
            // Unlock needs the raw, still-encrypted file.
            runTask(getString(R.string.msg_processing), onDone = loaded@{ _ ->
                binding.fileName.text = sourceName
                buildParams(emptyMap())
                binding.btnRun.isEnabled = true
            }) {
                source = Files.copyToCache(this, uri)
                Unit
            }
            return
        }
        openPdf(uri) { f ->
            runTask(getString(R.string.msg_processing), onDone = loaded@{ meta ->
                binding.fileName.text = sourceName
                buildParams(meta ?: emptyMap())
                binding.btnRun.isEnabled = true
            }) {
                source = f
                if (tool == Action.METADATA) PdfEngine.getMetadata(f) else emptyMap<String, String>()
            }
        }
    }

    override fun onTreeChosen(uri: Uri) {
        treeUri = uri
        binding.outputHint.visibility = View.VISIBLE
        binding.outputHint.text = "Output folder: " + (uri.lastPathSegment ?: "folder")
        if (pendingRun) { pendingRun = false; onRun() }
    }

    // ---- dynamic parameter UI ---------------------------------------------

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

    private fun addSpinner(label: String, options: List<String>, selected: Int = 0): Spinner {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(12) }
        }
        box.addView(TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
        })
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        sp.setSelection(selected)
        box.addView(sp)
        binding.params.addView(box)
        return sp
    }

    private fun addSwitch(label: String, checked: Boolean = false): SwitchMaterial {
        val sw = SwitchMaterial(this).apply {
            text = label
            isChecked = checked
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(12) }
        }
        binding.params.addView(sw)
        return sw
    }

    private fun buildParams(meta: Map<String, String>) {
        binding.params.removeAllViews()
        fText = null; fFont = null; fOpacity = null; fPw = null; fPw2 = null
        fRanges = null; fFormat = null
        fTitle = null; fAuthor = null; fSubject = null; fKeywords = null
        fCreator = null; fProducer = null; fCreated = null; fModified = null
        fSignName = null; fSignOrg = null; fSignReason = null; fSignLocation = null; fSignContact = null
        fFontSpinner = null; fColourSpinner = null; fRandom = null; fLevelSpinner = null
        fPagenumOpacity = null; fSplitAll = null; fExtractPages = null

        when (tool) {
            Action.WATERMARK -> {
                fText = addField(getString(R.string.hint_watermark_text))
                fFontSpinner = addSpinner(getString(R.string.label_font), FONT_NAMES, 1)
                fColourSpinner = addSpinner(getString(R.string.label_colour), COLOUR_NAMES, 1)
                fRandom = addSwitch(getString(R.string.position_random), false)
                fFont = addField(getString(R.string.hint_font_size), InputType.TYPE_CLASS_NUMBER, "0")
                fOpacity = addField(
                    getString(R.string.hint_opacity),
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, "0.25"
                )
            }
            Action.COMPRESS -> {
                fLevelSpinner = addSpinner(getString(R.string.label_level), LEVEL_NAMES, 1)
            }
            Action.PROTECT -> {
                fPw = addField(getString(R.string.hint_password), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
                fPw2 = addField(getString(R.string.hint_password_confirm), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            }
            Action.UNLOCK -> {
                fPw = addField(getString(R.string.hint_password), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            }
            Action.SIGN -> {
                fSignName = addField(getString(R.string.hint_signer_name))
                fSignOrg = addField(getString(R.string.hint_organisation))
                fSignReason = addField(getString(R.string.hint_reason))
                fSignLocation = addField(getString(R.string.hint_location))
                fSignContact = addField(getString(R.string.hint_contact))
            }
            Action.VERIFY -> {
                binding.outputHint.visibility = View.VISIBLE
                binding.outputHint.text = getString(R.string.tool_verify_desc)
            }
            Action.METADATA -> {
                fTitle = addField(getString(R.string.hint_title), InputType.TYPE_CLASS_TEXT, meta["title"] ?: "")
                fAuthor = addField(getString(R.string.hint_author), InputType.TYPE_CLASS_TEXT, meta["author"] ?: "")
                fSubject = addField(getString(R.string.hint_subject), InputType.TYPE_CLASS_TEXT, meta["subject"] ?: "")
                fKeywords = addField(getString(R.string.hint_keywords), InputType.TYPE_CLASS_TEXT, meta["keywords"] ?: "")
                fCreator = addField("Creator (application)", InputType.TYPE_CLASS_TEXT, meta["creator"] ?: "")
                fProducer = addField("Producer", InputType.TYPE_CLASS_TEXT, meta["producer"] ?: "")
                fCreated = addField("Creation date (yyyy-MM-dd HH:mm)", InputType.TYPE_CLASS_TEXT, meta["creationDate"] ?: "")
                fModified = addField("Modified date (yyyy-MM-dd HH:mm)", InputType.TYPE_CLASS_TEXT, meta["modDate"] ?: "")
            }
            Action.PAGENUM -> {
                fFormat = addField(getString(R.string.hint_page_format), InputType.TYPE_CLASS_TEXT, "Page {n} of {total}")
                fFont = addField(getString(R.string.hint_font_size), InputType.TYPE_CLASS_NUMBER, "10")
                fPagenumOpacity = addField(
                    getString(R.string.hint_opacity),
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, "1.0"
                )
            }
            Action.SPLIT -> {
                fSplitAll = addSwitch(getString(R.string.split_all_pages), false)
                fRanges = addField(getString(R.string.hint_ranges), InputType.TYPE_CLASS_TEXT, "1-3, 4-6")
                binding.outputHint.visibility = View.VISIBLE
                binding.outputHint.text = "Choose a folder, then Run."
            }
            Action.EXTRACT -> {
                fExtractPages = addField(getString(R.string.hint_extract_pages), InputType.TYPE_CLASS_TEXT, "")
                binding.outputHint.visibility = View.VISIBLE
                binding.outputHint.text = "Choose a folder, then Run."
            }
            else -> { }
        }
    }

    // ---- run ---------------------------------------------------------------

    private fun onRun() {
        when (tool) {
            Action.COMPRESS -> {
                val level = fLevelSpinner?.selectedItemPosition ?: 1
                single { PdfEngine.compress(it.first, it.second, level) }
            }
            Action.WATERMARK -> {
                val text = fText?.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) return toast(getString(R.string.msg_enter_text))
                val o = PdfEngine.WatermarkOptions()
                o.text = text
                o.randomPosition = fRandom?.isChecked ?: false
                val fontIdx = fFontSpinner?.selectedItemPosition ?: 0
                o.font = when (fontIdx) { 2, 3 -> "Times"; 4 -> "Courier"; else -> "Helvetica" }
                o.bold = fontIdx == 1 || fontIdx == 3
                o.color = COLOUR_VALUES.getOrElse(fColourSpinner?.selectedItemPosition ?: 1) { COLOUR_VALUES[1] }
                o.fontSize = fFont?.text?.toString()?.toFloatOrNull() ?: 0f
                o.opacity = (fOpacity?.text?.toString()?.toFloatOrNull() ?: 0.25f).coerceIn(0.05f, 1f)
                single { PdfEngine.watermarkText(it.first, o, it.second) }
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
                single { PdfEngine.decrypt(it.first, p, it.second) }
            }
            Action.SIGN -> {
                val name = fSignName?.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) return toast(getString(R.string.msg_enter_text))
                val params = PdfSigner.SignParams()
                params.name = name
                params.organisation = fSignOrg?.text?.toString()?.trim().orEmpty()
                params.reason = fSignReason?.text?.toString()?.trim().orEmpty()
                params.location = fSignLocation?.text?.toString()?.trim().orEmpty()
                params.contact = fSignContact?.text?.toString()?.trim().orEmpty()
                single { PdfSigner.sign(it.first, it.second, params) }
            }
            Action.VERIFY -> {
                val src = source ?: return toast(getString(R.string.msg_pick_file_first))
                runTask(getString(R.string.msg_processing), onDone = checked@{ list ->
                    if (list == null) return@checked
                    showVerification(list)
                }) { PdfSigner.verify(src) }
            }
            Action.METADATA -> {
                val info = PdfEngine.MetadataInfo()
                info.title = fTitle?.text?.toString()
                info.author = fAuthor?.text?.toString()
                info.subject = fSubject?.text?.toString()
                info.keywords = fKeywords?.text?.toString()
                info.creator = fCreator?.text?.toString()
                info.producer = fProducer?.text?.toString()
                info.creationDate = fCreated?.text?.toString()
                info.modDate = fModified?.text?.toString()
                single { PdfEngine.setMetadata(it.first, info, it.second) }
            }
            Action.PAGENUM -> {
                val fmt = fFormat?.text?.toString()?.ifBlank { "Page {n} of {total}" } ?: "Page {n} of {total}"
                val size = fFont?.text?.toString()?.toFloatOrNull() ?: 10f
                val op = (fPagenumOpacity?.text?.toString()?.toFloatOrNull() ?: 1f).coerceIn(0.05f, 1f)
                single { PdfEngine.addPageNumbers(it.first, fmt, size, 0xFF333333.toInt(), op, it.second) }
            }
            Action.EXTRACT -> {
                val src = source ?: return toast(getString(R.string.msg_pick_file_first))
                if (treeUri == null) { pendingRun = true; pickTree(); return }
                val pageList = parseRanges(fExtractPages?.text?.toString().orEmpty())
                    .flatMap { r -> (r[0]..r[1]).toList() }
                    .distinct().sorted()
                multi { dir -> PdfEngine.extractImages(src, dir, pageList.ifEmpty { null }) }
            }
            Action.SPLIT -> {
                val src = source ?: return toast(getString(R.string.msg_pick_file_first))
                val allPages = fSplitAll?.isChecked == true
                val ranges = parseRanges(fRanges?.text?.toString().orEmpty())
                if (!allPages && ranges.isEmpty()) return toast(getString(R.string.msg_invalid_ranges))
                if (treeUri == null) { pendingRun = true; pickTree(); return }
                val base = Files.baseName(sourceName)
                multi { dir ->
                    val use = if (allPages) {
                        val n = PdfEngine.pageCount(src)
                        (1..n).map { intArrayOf(it, it) }
                    } else ranges
                    PdfEngine.splitToFiles(src, use, dir, base)
                }
            }
            else -> { }
        }
    }

    private fun showVerification(list: List<PdfSigner.SignatureInfo>) {
        val msg = if (list.isEmpty()) {
            getString(R.string.msg_not_signed)
        } else {
            buildString {
                list.forEachIndexed { i, s ->
                    if (i > 0) append("\n\n")
                    append(getString(R.string.title_signature)).append(" ").append(i + 1).append("\n")
                    append("Signer: ").append(s.signerName.ifEmpty { "—" }).append("\n")
                    append("Date: ").append(s.date.ifEmpty { "—" }).append("\n")
                    if (s.reason.isNotEmpty()) append("Reason: ").append(s.reason).append("\n")
                    if (s.location.isNotEmpty()) append("Location: ").append(s.location).append("\n")
                    append("Certificate: ").append(s.subject.ifEmpty { "—" }).append("\n")
                    val whole = s.valid && s.coversWholeDocument
                    append("Status: ").append(
                        if (whole) getString(R.string.msg_sig_valid)
                        else if (!s.valid) getString(R.string.msg_sig_invalid)
                        else getString(R.string.msg_sig_partial)
                    )
                    // Only reassure about the certificate when the signature actually holds.
                    if (whole) append("\n").append(getString(R.string.msg_sig_selfsigned))
                    if (!whole && s.error.isNotEmpty()) append("\n").append(s.error)
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.tool_verify)
            .setMessage(msg)
            .setPositiveButton(R.string.action_close, null)
            .show()
    }

    private fun single(produce: (Pair<File, File>) -> Unit) {
        val src = source ?: return toast(getString(R.string.msg_pick_file_first))
        runAndSave(Files.suggestName(sourceName, suffixFor(tool))) { out ->
            produce(src to out)
        }
    }

    private fun multi(produce: (File) -> List<File>) {
        val tree = treeUri ?: return
        runTask(getString(R.string.msg_processing), onDone = done@{ n ->
            if (n != null) showSuccess(getString(R.string.msg_saved_to, "$n files"), null)
        }) {
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
        }
    }

    private fun suffixFor(tool: Action) = when (tool) {
        Action.COMPRESS -> "_compressed"
        Action.WATERMARK -> "_watermarked"
        Action.PROTECT -> "_protected"
        Action.UNLOCK -> "_unlocked"
        Action.SIGN -> "_signed"
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (tool == Action.METADATA) {
            menu.add(0, 1, 0, "Clear all metadata")
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1 && tool == Action.METADATA) {
            single { PdfEngine.clearMetadata(it.first, it.second) }
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
