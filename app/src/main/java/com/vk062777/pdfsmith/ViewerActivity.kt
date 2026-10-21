package com.vk062777.pdfsmith

import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Menu
import android.view.MenuItem
import android.graphics.pdf.PdfRenderer
import androidx.viewpager2.widget.ViewPager2
import com.vk062777.pdfsmith.databinding.ActivityViewerBinding
import com.vk062777.pdfsmith.engine.PdfEngine
import com.vk062777.pdfsmith.ui.PageRenderer
import com.vk062777.pdfsmith.ui.ViewerAdapter
import com.vk062777.pdfsmith.util.Files
import java.io.File

/** Fast, smooth, offline PDF reader with pinch-zoom and page swipe. */
class ViewerActivity : PdfActivity() {

    private lateinit var binding: ActivityViewerBinding
    private var renderer: PageRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var current: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val uri: Uri? = intent?.data
        if (uri != null) openUri(uri) else pickPdf()
    }

    override fun onPdfChosen(uri: Uri) = openUri(uri)

    private fun openUri(uri: Uri) {
        runTask(getString(R.string.msg_processing)) {
            val f = Files.copyToCache(this, uri)
            if (PdfEngine.isEncrypted(f)) error(getString(R.string.msg_encrypted))
            f
        } result@{ f ->
            if (f == null) return@result
            current = f
            binding.toolbar.title = Files.displayName(this, uri)
            show(f)
        }
    }

    private fun show(file: File) {
        try {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd = descriptor
            val pdfRenderer = PdfRenderer(descriptor)
            val pr = PageRenderer(pdfRenderer)
            renderer = pr
            val adapter = ViewerAdapter(pr.pageCount, pr, lifecycleScope) { zoomed ->
                binding.pager.isUserInputEnabled = !zoomed
            }
            binding.pager.adapter = adapter
            binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) = updateIndicator(position, pr.pageCount)
            })
            updateIndicator(0, pr.pageCount)
        } catch (t: Throwable) {
            toast(getString(R.string.msg_failed, t.message ?: "cannot open"))
        }
    }

    private fun updateIndicator(position: Int, count: Int) {
        binding.pageIndicator.text = getString(R.string.label_pages, count) + "   •   " + (position + 1)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.action_share)
            .setIcon(R.drawable.ic_share)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            current?.let { Files.share(this, it, "application/pdf") }
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer?.close()
        try { pfd?.close() } catch (_: Exception) { }
    }
}
