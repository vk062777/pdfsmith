package com.vk062777.pdfsmith

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.vk062777.pdfsmith.databinding.ActivityFormsBinding
import com.vk062777.pdfsmith.engine.PdfEngine
import com.vk062777.pdfsmith.util.Files
import java.io.File

/** Lists a PDF's AcroForm fields and fills them in. */
class FormsActivity : PdfActivity() {

    private lateinit var binding: ActivityFormsBinding
    private var source: File? = null
    private var sourceName: String = "form.pdf"

    private val rows = mutableListOf<Row>()

    private class Row(val field: PdfEngine.FormField, val view: View)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFormsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnSave.setOnClickListener { save() }
        pickAfterEnter { pickPdf() }
    }

    override fun onPdfChosen(uri: Uri) {
        sourceName = Files.displayName(this, uri)
        openPdf(uri) { f ->
            runTask(getString(R.string.msg_processing), onDone = loaded@{ fields ->
                if (fields == null) return@loaded
                binding.container.removeAllViews()
                rows.clear()
                if (fields.isEmpty()) {
                    binding.info.text = getString(R.string.msg_no_form_fields)
                    binding.btnSave.isEnabled = false
                    return@loaded
                }
                binding.info.text = getString(R.string.label_pages, PdfEngine.pageCount(f)) +
                        "   •   " + fields.size + " fields"
                val shown = fields.take(MAX_FORM_FIELDS)
                shown.forEach { addRow(it) }
                if (fields.size > shown.size) {
                    binding.info.text = binding.info.text.toString() +
                            "  (showing first ${shown.size} fields)"
                }
                binding.btnSave.isEnabled = true
            }) {
                source = f
                PdfEngine.listFormFields(f)
            }
        }
    }

    private val MAX_FORM_FIELDS = 300

    private fun addRow(field: PdfEngine.FormField) {
        val label = TextView(this).apply {
            text = field.name
            setTextColor(getColor(R.color.text_primary))
            textSize = 13f
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(12)
        label.layoutParams = lp
        binding.container.addView(label)

        val view: View = if (field.type == "checkbox") {
            CheckBox(this).apply {
                text = field.name
                textSize = 13f
                isChecked = field.value.equals("on", true) || field.value == "1" || field.value.equals("yes", true)
            }
        } else {
            // a plain pill field - see ToolsActivity.addField for why
            val opts = if (field.options.isNotEmpty()) " — " + field.options.joinToString(", ") else ""
            val et = EditText(this).apply {
                hint = field.type + opts
                inputType = InputType.TYPE_CLASS_TEXT
                background = getDrawable(R.drawable.bg_field_pill)
                val px = (16 * resources.displayMetrics.density).toInt()
                val py = (14 * resources.displayMetrics.density).toInt()
                setPadding(px, py, px, py)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = (10 * resources.displayMetrics.density).toInt() }
                setTextColor(getColor(R.color.text_primary))
                setHintTextColor(getColor(R.color.text_secondary))
                textSize = 15f
                maxLines = 1
                setText(field.value ?: "")
            }
            et
        }
        binding.container.addView(view)
        rows.add(Row(field, view))
    }

    private fun save() {
        val src = source ?: return toast(getString(R.string.msg_pick_file_first))
        val values = HashMap<String, String>()
        rows.forEach { row ->
            when (val v = row.view) {
                is CheckBox -> values[row.field.name] = if (v.isChecked) "true" else "false"
                is EditText -> values[row.field.name] = v.text.toString()
                else -> { }
            }
        }
        runAndSave(Files.suggestName(sourceName, "_filled")) { out ->
            PdfEngine.fillForm(src, values, out)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
