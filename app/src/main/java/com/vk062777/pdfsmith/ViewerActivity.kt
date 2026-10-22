package com.vk062777.pdfsmith

import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Menu
import android.view.MenuItem
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.vk062777.pdfsmith.databinding.ActivityViewerBinding
import com.vk062777.pdfsmith.engine.PdfEngine
import com.vk062777.pdfsmith.ui.PageRenderer
import com.vk062777.pdfsmith.ui.ViewerAdapter
import com.vk062777.pdfsmith.util.Files
import java.io.File

/** Fast, smooth, offline PDF reader with pinch-zoom, rotation and crisp zoom. */
class ViewerActivity : PdfActivity() {

    private lateinit var binding: ActivityViewerBinding
    private var renderer: PageRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var current: File? = null
    private var adapter: ViewerAdapter? = null
    private var viewRotation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val uri: Uri? = intent?.data
        if (uri != null) openUri(uri) else pickPdf()
    }

    override fun onPdfChosen(uri: Uri) = openUri(uri)

    private fun openUri(uri: Uri) {
        openPdf(uri) { f ->
            current = f
            binding.toolbar.title = Files.displayName(this, uri)
            show(f)
        }
    }

    private fun show(file: File) {
        try {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd = descriptor
            val pr = PageRenderer(PdfRenderer(descriptor))
            renderer = pr
            val adapter = ViewerAdapter(
                pr.pageCount, pr, lifecycleScope,
                rotationProvider = { viewRotation },
                baseWidthProvider = { (resources.displayMetrics.widthPixels * BASE_FACTOR).toInt() },
                onZoom = { zoomed -> binding.pager.isUserInputEnabled = !zoomed },
                onZoomSettled = { position, z -> upgrade(position, z) }
            )
            this.adapter = adapter
            binding.pager.adapter = adapter
            binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) = updateIndicator(position, pr.pageCount)
            })
            updateIndicator(0, pr.pageCount)
        } catch (t: Throwable) {
            toast(getString(R.string.msg_failed, t.message ?: "cannot open"))
        }
    }

    /** Re-render the current page at a resolution matching how far the user zoomed in. */
    private fun upgrade(position: Int, zoom: Float) {
        val factor = zoom.coerceIn(BASE_FACTOR, MAX_FACTOR)
        val width = (resources.displayMetrics.widthPixels * factor).toInt().coerceAtMost(MAX_WIDTH)
        adapter?.upgradePage(position, width)
    }

    private fun rotate() {
        viewRotation = (viewRotation + 90) % 360
        adapter?.invalidateAll()
    }

    private fun updateIndicator(position: Int, count: Int) {
        binding.pageIndicator.text = "${position + 1} / $count"
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.action_rotate)
            .setIcon(R.drawable.ic_rotate)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 2, 1, R.string.action_share)
            .setIcon(R.drawable.ic_share)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> { rotate(); return true }
            2 -> { current?.let { Files.share(this, it, "application/pdf") }; return true }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer?.close()
        try { pfd?.close() } catch (_: Exception) { }
    }

    companion object {
        private const val BASE_FACTOR = 2.0f
        private const val MAX_FACTOR = 4.0f
        private const val MAX_WIDTH = 2800
    }
}
