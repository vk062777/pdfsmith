package com.vk062777.pdfsmith

import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.vk062777.pdfsmith.databinding.ActivityLinksBinding
import com.vk062777.pdfsmith.engine.PdfEngine
import com.vk062777.pdfsmith.ui.PageRenderer
import com.vk062777.pdfsmith.util.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Turns the web / e-mail addresses already written in a PDF into real, clickable
 * link annotations — or lets you draw one yourself.
 *
 * Detection reads the actual glyph positions, so a link covers exactly the text it
 * came from (verified by rendering the result and measuring where the ink is).
 */
class LinksActivity : PdfActivity() {

    private lateinit var binding: ActivityLinksBinding
    private var renderer: PageRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var source: File? = null
    private var sourceName: String = "document.pdf"
    private var pageCount = 0
    private var current = 0
    private val sizes = mutableListOf<FloatArray>()
    private var found: List<PdfEngine.FoundLink> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLinksBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.rectPick.imageView = binding.pageImage

        binding.btnChoose.setOnClickListener { pickPdf() }
        binding.prevPage.setOnClickListener { goTo(current - 1) }
        binding.nextPage.setOnClickListener { goTo(current + 1) }
        binding.btnRun.setOnClickListener { run() }
        binding.modeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) applyMode(id == R.id.modeManual)
        }
        binding.modeGroup.check(R.id.modeAuto)
    }

    override fun onPdfChosen(uri: Uri) {
        sourceName = Files.displayName(this, uri)
        openPdf(uri) { f ->
            binding.fileName.text = sourceName
            binding.toolbar.title = Files.baseName(sourceName)
            runTask(getString(R.string.msg_processing), onDone = opened@{ res ->
                if (res == null) return@opened
                pageCount = res.first
                found = res.second
                binding.btnRun.isEnabled = true
                showFound()
                if (binding.modeManual.isChecked) goTo(0)
            }) {
                source = f
                sizes.clear()
                sizes.addAll(PdfEngine.pageDisplaySizes(f))
                val d = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                try { pfd?.close() } catch (_: Exception) { }
                pfd = d
                val r = PdfRenderer(d)
                renderer = PageRenderer(r)
                r.pageCount to PdfEngine.findLinks(f)
            }
        }
    }

    private fun showFound() {
        binding.info.text = if (found.isEmpty()) getString(R.string.links_none)
        else getString(R.string.links_found, found.size)
        binding.linkList.removeAllViews()
        found.take(200).forEach { l ->
            val row = android.widget.TextView(this).apply {
                text = "Page ${l.page}  \\u2022  ${l.url}"
                setTextColor(getColor(R.color.text_primary))
                textSize = 13f
                setPadding(dp(10), dp(9), dp(10), dp(9))
            }
            binding.linkList.addView(row)
        }
    }

    private fun applyMode(manual: Boolean) {
        binding.manualWrap.visibility = if (manual) View.VISIBLE else View.GONE
        binding.listWrap.visibility = if (manual) View.GONE else View.VISIBLE
        binding.info.text = if (manual) getString(R.string.links_manual_hint) else {
            if (found.isEmpty()) getString(R.string.links_none)
            else getString(R.string.links_found, found.size)
        }
        if (manual) {
            if (pageCount > 0) goTo(current) else pickPdf()
        }
    }

    private fun goTo(index: Int) {
        if (index < 0 || index >= pageCount) return
        current = index
        binding.pageLabel.text = "${index + 1} / $pageCount"
        binding.prevPage.isEnabled = index > 0
        binding.nextPage.isEnabled = index < pageCount - 1
        binding.rectPick.reset()
        val r = renderer ?: return
        val targetW = (resources.displayMetrics.widthPixels * 1.7f).toInt().coerceAtMost(2400)
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching { r.render(index, targetW) }.getOrNull()
            }
            if (bmp != null && current == index) {
                binding.pageImage.setImageBitmap(bmp)
                binding.rectPick.post { binding.rectPick.invalidate() }
            }
        }
    }

    private fun run() {
        val src = source ?: return toast(getString(R.string.msg_pick_file_first))
        if (binding.modeManual.isChecked) {
            val url = binding.urlInput.text?.toString().orEmpty().trim()
            if (PdfEngine.normaliseUrl(url) == null) return toast(getString(R.string.msg_invalid_link))
            val size = sizes.getOrNull(current) ?: floatArrayOf(595f, 842f)
            val rect = binding.rectPick.cropRect()
            val x = rect[0] * size[0]
            val y = rect[1] * size[1]
            val w = rect[2] * size[0]
            val h = rect[3] * size[1]
            if (w < 6f || h < 6f) return toast(getString(R.string.msg_area_too_small))
            runAndSave(Files.suggestName(sourceName, "_links")) { out ->
                PdfEngine.addLink(src, current + 1, x, y, w, h, url, out)
            }
            return
        }
        if (found.isEmpty()) return toast(getString(R.string.links_none))
        val n = found.size
        runAndSave(Files.suggestName(sourceName, "_links")) { out ->
            PdfEngine.addLinks(src, found, out)
            n
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (binding.modeManual.isChecked) goTo(current)
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer?.close()
        try { pfd?.close() } catch (_: Exception) { }
    }
}
