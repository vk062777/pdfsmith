package com.vk062777.pdfsmith

import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Menu
import android.view.inputmethod.EditorInfo
import android.view.Menu
import android.view.inputmethod.EditorInfoItem
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
    private val sizes = mutableListOf<FloatArray>()
    private var matches: List<PdfEngine.Match> = emptyList()
    private var matchIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        wireSearch()
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
            sizes.clear()
            sizes.addAll(runCatching { PdfEngine.pageDisplaySizes(file) }.getOrDefault(emptyList()))
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

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        adapter?.invalidateAll()
    }

    private fun rotate() {
        viewRotation = (viewRotation + 90) % 360
        adapter?.invalidateAll()
    }

    private fun wireSearch() {
        binding.searchClose.setOnClickListener { closeSearch() }
        binding.searchPrev.setOnClickListener { stepMatch(-1) }
        binding.searchNext.setOnClickListener { stepMatch(1) }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { runSearch(); true } else false
        }
    }

    private fun openSearch() {
        binding.searchBar.visibility = android.view.View.VISIBLE
        binding.searchInput.requestFocus()
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(binding.searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        binding.searchBar.visibility = android.view.View.GONE
        binding.searchInput.setText("")
        matches = emptyList()
        matchIndex = -1
        binding.searchCount.text = ""
        adapter?.setHighlight(-1, null)
    }

    private fun runSearch() {
        val f = current ?: return
        val q = binding.searchInput.text?.toString().orEmpty().trim()
        if (q.isEmpty()) {
            matches = emptyList(); matchIndex = -1; binding.searchCount.text = ""
            adapter?.setHighlight(-1, null)
            return
        }
        runTask(getString(R.string.search_progress), onDone = found@{ list ->
            if (list == null) return@found
            matches = list
            matchIndex = if (list.isEmpty()) -1 else 0
            updateSearchCount()
            showMatch()
        }) {
            PdfEngine.search(f, q, false)
        }
    }

    private fun stepMatch(delta: Int) {
        if (matches.isEmpty()) return
        matchIndex = ((matchIndex + delta) % matches.size + matches.size) % matches.size
        updateSearchCount()
        showMatch()
    }

    private fun updateSearchCount() {
        binding.searchCount.text = if (matches.isEmpty()) getString(R.string.search_none)
        else getString(R.string.search_count, matchIndex + 1, matches.size)
    }

    private fun showMatch() {
        val m = matches.getOrNull(matchIndex) ?: run { adapter?.setHighlight(-1, null); return }
        val size = sizes.getOrNull(m.page - 1)
        if (size == null || size[0] <= 0f || size[1] <= 0f) return
        val norm = RectF(
            m.x / size[0], m.y / size[1],
            (m.x + m.w) / size[0], (m.y + m.h) / size[1]
        )
        adapter?.setHighlight(m.page - 1, norm)
        if (binding.pager.currentItem != m.page - 1) binding.pager.setCurrentItem(m.page - 1, true)
    }

    private fun updateIndicator(position: Int, count: Int) {
        binding.pageIndicator.text = "${position + 1} / $count"
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.action_rotate)
            .setIcon(R.drawable.ic_rotate)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 3, 0, R.string.action_search)
            .setIcon(R.drawable.ic_search)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 2, 1, R.string.action_share)
            .setIcon(R.drawable.ic_share)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> { rotate(); return true }
            3 -> { openSearch(); return true }
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
