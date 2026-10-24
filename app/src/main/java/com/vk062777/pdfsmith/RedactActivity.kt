package com.vk062777.pdfsmith

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.vk062777.pdfsmith.databinding.ActivityRedactBinding
import com.vk062777.pdfsmith.engine.PdfEngine
import com.vk062777.pdfsmith.ui.PageRenderer
import com.vk062777.pdfsmith.util.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Permanently removes private content from a PDF.
 *
 * Marking an area only paints a box - the text would still be in the file and could
 * be copied straight out. So a redacted page is REPLACED by a flat image with the
 * marked areas painted black, and the page's original content, resources and
 * annotations are all discarded. Only pages you mark are flattened.
 */
class RedactActivity : PdfActivity() {

    private lateinit var binding: ActivityRedactBinding
    private var renderer: PageRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var source: File? = null
    private var sourceName: String = "document.pdf"
    private var pageCount = 0
    private var current = 0
    private val sizes = mutableListOf<FloatArray>()
    private val marks = HashMap<Int, MutableList<RectF>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRedactBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.redactView.imageView = binding.pageImage
        binding.btnChoose.setOnClickListener { pickPdf() }
        binding.prevPage.setOnClickListener { goTo(current - 1) }
        binding.nextPage.setOnClickListener { goTo(current + 1) }
        binding.btnClear.setOnClickListener {
            binding.redactView.clear()
            marks.remove(current)
        }
        binding.btnRun.setOnClickListener { run() }
        pickPdf()
    }

    override fun onPdfChosen(uri: Uri) {
        sourceName = Files.displayName(this, uri)
        openPdf(uri) { f ->
            binding.fileName.text = sourceName
            binding.toolbar.title = Files.baseName(sourceName)
            runTask(getString(R.string.msg_processing), onDone = opened@{ res ->
                if (res == null) return@opened
                pageCount = res
                binding.btnRun.isEnabled = true
                goTo(0)
            }) {
                source = f
                marks.clear()
                sizes.clear()
                sizes.addAll(PdfEngine.pageDisplaySizes(f))
                val d = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                try { pfd?.close() } catch (_: Exception) { }
                pfd = d
                val r = PdfRenderer(d)
                renderer = PageRenderer(r)
                r.pageCount
            }
        }
    }

    /** Remember what was marked on the page we are leaving. */
    private fun stashCurrent() {
        if (pageCount == 0) return
        val list = marks.getOrPut(current) { ArrayList() }
        list.clear()
        list.addAll(binding.redactView.rects)
    }

    private fun goTo(index: Int) {
        if (index < 0 || index >= pageCount) return
        stashCurrent()
        current = index
        binding.pageLabel.text = "${index + 1} / $pageCount"
        binding.prevPage.isEnabled = index > 0
        binding.nextPage.isEnabled = index < pageCount - 1
        binding.redactView.clear()
        binding.redactView.rects.addAll(marks[current] ?: emptyList())
        val r = renderer ?: return
        val targetW = (resources.displayMetrics.widthPixels * 1.8f).toInt().coerceAtMost(2400)
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching { r.render(index, targetW) }.getOrNull()
            }
            if (bmp != null && current == index) {
                binding.pageImage.setImageBitmap(bmp)
                binding.redactView.post { binding.redactView.invalidate() }
            }
        }
    }

    private fun run() {
        stashCurrent()
        val src = source ?: return toast(getString(R.string.msg_pick_file_first))
        val pages = marks.filterValues { it.isNotEmpty() }.keys.sorted()
        if (pages.isEmpty()) return toast(getString(R.string.redact_need_boxes))
        val r = renderer ?: return

        lifecycleScope.launch {
            busy.show(getString(R.string.redact_working))
            val images = ArrayList<Bitmap>()
            val applied = ArrayList<Int>()
            for (page in pages) {
                val size = sizes.getOrNull(page) ?: continue
                val boxes = marks[page] ?: continue
                // render at print resolution so the flattened page stays sharp
                val w = (size[0] * PRINT_DPI / 72f).toInt().coerceIn(600, 4000)
                val bmp = withContext(Dispatchers.IO) {
                    runCatching { r.render(page, w) }.getOrNull()
                } ?: continue
                val painted = withContext(Dispatchers.Default) { paintBlack(bmp, boxes) }
                if (painted != bmp) bmp.recycle()
                images.add(painted)
                applied.add(page + 1)
            }
            busy.dismiss()
            if (images.isEmpty()) {
                toast(getString(R.string.redact_need_boxes))
                return@launch
            }
            val n = applied.size
            runAndSave(Files.suggestName(sourceName, "_redacted")) { out ->
                PdfEngine.redactPages(src, applied.toIntArray(), images.toTypedArray(), out)
                n
            }
            images.forEach { runCatching { it.recycle() } }
        }
    }

    /** Paint the marked boxes solid black, with a margin so no edge can bleed. */
    private fun paintBlack(bmp: Bitmap, boxes: List<RectF>): Bitmap {
        val out = bmp.copy(Bitmap.Config.ARGB_8888, true) ?: return bmp
        val c = Canvas(out)
        val p = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            isAntiAlias = false
        }
        val pad = 8f
        for (b in boxes) {
            c.drawRect(
                b.left * out.width - pad, b.top * out.height - pad,
                b.right * out.width + pad, b.bottom * out.height + pad, p
            )
        }
        return out
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer?.close()
        try { pfd?.close() } catch (_: Exception) { }
    }

    private companion object {
        /** Resolution used for the flattened page. */
        const val PRINT_DPI = 200f
    }
}
