package com.vk062777.pdfsmith

import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.graphics.pdf.PdfRenderer
import androidx.recyclerview.widget.GridLayoutManager
import com.vk062777.pdfsmith.databinding.ActivityPagesBinding
import com.vk062777.pdfsmith.engine.PdfEngine
import com.vk062777.pdfsmith.model.PageEntry
import com.vk062777.pdfsmith.ui.PageRenderer
import com.vk062777.pdfsmith.ui.PageThumbAdapter
import com.vk062777.pdfsmith.util.Files
import java.io.File

/** Visual page organiser: rotate, reorder, delete and extract pages. */
class PagesActivity : PdfActivity() {

    private lateinit var binding: ActivityPagesBinding
    private var renderer: PageRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var source: File? = null
    private var sourceName: String = "document.pdf"

    private val items = mutableListOf<PageEntry>()
    private val history = ArrayDeque<List<PageEntry>>()
    private var adapter: PageThumbAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPagesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.recycler.layoutManager = GridLayoutManager(this, 3)
        binding.recycler.setHasFixedSize(false)

        binding.btnRotate.setOnClickListener { rotateSelected() }
        binding.btnDelete.setOnClickListener { deleteSelected() }
        binding.btnLeft.setOnClickListener { moveSelected(-1) }
        binding.btnRight.setOnClickListener { moveSelected(1) }
        binding.btnSelectAll.setOnClickListener { toggleSelectAll() }
        binding.btnUndo.setOnClickListener { undo() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnExtract.setOnClickListener { extract() }

        pickPdf()
    }

    override fun onPdfChosen(uri: Uri) {
        sourceName = Files.displayName(this, uri)
        runTask(getString(R.string.msg_processing)) {
            val f = Files.copyToCache(this, uri)
            if (PdfEngine.isEncrypted(f)) error(getString(R.string.msg_encrypted))
            source = f
            val d = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd = d
            val r = PdfRenderer(d)
            renderer = PageRenderer(r)
            r.pageCount
        } result@{ count ->
            if (count == null) return@result
            items.clear()
            for (i in 1..count) items.add(PageEntry(i))
            adapter = PageThumbAdapter(items, renderer!!, lifecycleScope) { pos -> toggle(pos) }
            binding.recycler.adapter = adapter
            updateInfo()
        }
    }

    private fun snapshot() {
        history.addLast(items.map { it.copy() })
        if (history.size > 30) history.removeFirst()
    }

    private fun undo() {
        val prev = history.removeLastOrNull() ?: return
        items.clear()
        items.addAll(prev)
        refresh()
    }

    private fun toggle(pos: Int) {
        if (pos !in items.indices) return
        items[pos].selected = !items[pos].selected
        adapter?.notifyItemChanged(pos)
        updateInfo()
    }

    private fun toggleSelectAll() {
        val allSelected = items.isNotEmpty() && items.all { it.selected }
        items.forEach { it.selected = !allSelected }
        refresh()
    }

    private fun rotateSelected() {
        val sel = items.filter { it.selected }
        if (sel.isEmpty()) { toast(getString(R.string.msg_need_selection)); return }
        snapshot()
        sel.forEach { it.rotation = (it.rotation + 90) % 360 }
        refresh()
    }

    private fun deleteSelected() {
        val sel = items.filter { it.selected }
        if (sel.isEmpty()) { toast(getString(R.string.msg_need_selection)); return }
        if (sel.size >= items.size) { toast(getString(R.string.msg_keep_one)); return }
        snapshot()
        items.removeAll { it.selected }
        refresh()
    }

    private fun moveSelected(direction: Int) {
        val sel = items.filter { it.selected }
        if (sel.isEmpty()) { toast(getString(R.string.msg_need_selection)); return }
        snapshot()
        if (direction > 0) {
            for (i in items.indices.reversed()) {
                if (items[i].selected && i < items.size - 1 && !items[i + 1].selected) {
                    val tmp = items[i]; items[i] = items[i + 1]; items[i + 1] = tmp
                }
            }
        } else {
            for (i in items.indices) {
                if (items[i].selected && i > 0 && !items[i - 1].selected) {
                    val tmp = items[i]; items[i] = items[i - 1]; items[i - 1] = tmp
                }
            }
        }
        refresh()
    }

    private fun save() {
        val src = source ?: return toast(getString(R.string.msg_pick_file_first))
        val order = items.map { it.original }
        val rots = items.map { it.rotation }
        runAndSave(Files.suggestName(sourceName, "_organized")) { out ->
            PdfEngine.rebuild(src, order, rots, out)
        }
    }

    private fun extract() {
        val src = source ?: return toast(getString(R.string.msg_pick_file_first))
        val selected = items.filter { it.selected }.map { it.original }
        if (selected.isEmpty()) { toast(getString(R.string.msg_need_selection)); return }
        runAndSave(Files.suggestName(sourceName, "_extract")) { out ->
            PdfEngine.keepPages(src, selected, out)
        }
    }

    private fun refresh() {
        adapter?.notifyDataSetChanged()
        updateInfo()
    }

    private fun updateInfo() {
        val sel = items.count { it.selected }
        binding.info.text = getString(R.string.label_pages, items.size) + "   •   " +
                getString(R.string.label_selected, sel)
        binding.btnSave.isEnabled = items.isNotEmpty()
        binding.btnExtract.isEnabled = sel > 0
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer?.close()
        try { pfd?.close() } catch (_: Exception) { }
    }
}
