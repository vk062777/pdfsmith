package com.vk062777.pdfsmith

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.GridLayoutManager
import com.vk062777.pdfsmith.databinding.ActivityMainBinding
import com.vk062777.pdfsmith.model.Action
import com.vk062777.pdfsmith.model.ToolItem
import com.vk062777.pdfsmith.ui.HomeRow
import com.vk062777.pdfsmith.ui.ToolAdapter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val rows = buildRows()
        val adapter = ToolAdapter(rows) { dispatch(it) }
        val lm = GridLayoutManager(this, 2)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) =
                if (rows[position] is HomeRow.Header) 2 else 1
        }
        binding.recycler.layoutManager = lm
        binding.recycler.adapter = adapter

        binding.btnTheme.setOnClickListener { chooseTheme() }
    }

    private fun chooseTheme() {
        val modes = arrayOf(
            getString(R.string.theme_system),
            getString(R.string.theme_light),
            getString(R.string.theme_dark)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.action_theme)
            .setSingleChoiceItems(modes, PdfSmithApp.themePref(this)) { dialog, which ->
                PdfSmithApp.setThemePref(this, which)
                AppCompatDelegate.setDefaultNightMode(
                    when (which) {
                        1 -> AppCompatDelegate.MODE_NIGHT_NO
                        2 -> AppCompatDelegate.MODE_NIGHT_YES
                        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    }
                )
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun dispatch(action: Action) {
        val intent = when (action) {
            Action.VIEW -> Intent(this, ViewerActivity::class.java)
            Action.ANNOTATE -> Intent(this, AnnotateActivity::class.java)
            Action.FORMS -> Intent(this, FormsActivity::class.java)
            Action.PAGES -> Intent(this, PagesActivity::class.java)
            Action.MERGE -> Intent(this, MergeActivity::class.java)
            Action.IMAGES_PDF -> Intent(this, ImagesToPdfActivity::class.java)
            else -> Intent(this, ToolsActivity::class.java)
                .putExtra(ToolsActivity.EXTRA_TOOL, action.name)
        }
        startActivity(intent)
    }

    private fun buildRows(): List<HomeRow> = buildList {
        add(HomeRow.Header(R.string.section_edit))
        add(HomeRow.Tool(ToolItem(R.string.tool_view, R.string.tool_view_desc, R.drawable.ic_view, Action.VIEW)))
        add(HomeRow.Tool(ToolItem(R.string.tool_annotate, R.string.tool_annotate_desc, R.drawable.ic_annotate, Action.ANNOTATE)))
        add(HomeRow.Tool(ToolItem(R.string.tool_forms, R.string.tool_forms_desc, R.drawable.ic_forms, Action.FORMS)))

        add(HomeRow.Header(R.string.section_organize))
        add(HomeRow.Tool(ToolItem(R.string.tool_pages, R.string.tool_pages_desc, R.drawable.ic_pages, Action.PAGES)))
        add(HomeRow.Tool(ToolItem(R.string.tool_merge, R.string.tool_merge_desc, R.drawable.ic_merge, Action.MERGE)))
        add(HomeRow.Tool(ToolItem(R.string.tool_split, R.string.tool_split_desc, R.drawable.ic_split, Action.SPLIT)))
        add(HomeRow.Tool(ToolItem(R.string.tool_images_pdf, R.string.tool_images_pdf_desc, R.drawable.ic_image_pdf, Action.IMAGES_PDF)))

        add(HomeRow.Header(R.string.section_security))
        add(HomeRow.Tool(ToolItem(R.string.tool_compress, R.string.tool_compress_desc, R.drawable.ic_compress, Action.COMPRESS)))
        add(HomeRow.Tool(ToolItem(R.string.tool_watermark, R.string.tool_watermark_desc, R.drawable.ic_watermark, Action.WATERMARK)))
        add(HomeRow.Tool(ToolItem(R.string.tool_protect, R.string.tool_protect_desc, R.drawable.ic_lock, Action.PROTECT)))
        add(HomeRow.Tool(ToolItem(R.string.tool_unlock, R.string.tool_unlock_desc, R.drawable.ic_unlock, Action.UNLOCK)))
        add(HomeRow.Tool(ToolItem(R.string.tool_metadata, R.string.tool_metadata_desc, R.drawable.ic_metadata, Action.METADATA)))
        add(HomeRow.Tool(ToolItem(R.string.tool_extract, R.string.tool_extract_desc, R.drawable.ic_extract, Action.EXTRACT)))
        add(HomeRow.Tool(ToolItem(R.string.tool_pagenum, R.string.tool_pagenum_desc, R.drawable.ic_pagenum, Action.PAGENUM)))
    }
}
