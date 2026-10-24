package com.vk062777.pdfsmith.ui

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.vk062777.pdfsmith.databinding.ItemPageBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pages of the reader. Bitmaps are cached by size (memory stays bounded no matter how
 * large the document is), and zooming re-renders the current page at higher resolution
 * and swaps it in **without** rebinding, so the zoom and pan are never lost.
 */
class ViewerAdapter(
    private val pageCount: Int,
    private val renderer: PageRenderer,
    private val scope: CoroutineScope,
    private val rotationProvider: () -> Int,
    private val baseWidthProvider: () -> Int,
    private val onZoom: (Boolean) -> Unit,
    private val onZoomSettled: (Int, Float) -> Unit,
    /** Called on a long press with the page and the point as a fraction of it. */
    private val onLongPress: (Int, Float, Float) -> Unit
) : RecyclerView.Adapter<ViewerAdapter.VH>() {

    private val cache = object : LruCache<Int, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    private val renderedWidth = HashMap<Int, Int>()
    private val inFlight = HashSet<Int>()
    private val holders = HashMap<Int, VH>()
    private val highlights = HashMap<Int, RectF>()

    class VH(val b: ItemPageBinding) : RecyclerView.ViewHolder(b.root) {
        var boundPosition = -1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = pageCount

    override fun onBindViewHolder(holder: VH, position: Int) {
        val iv = holder.b.pageImage
        holder.b.highlight.imageView = iv
        holder.b.highlight.norm = highlights[position]
        iv.onZoomChanged = onZoom
        iv.onZoomSettled = { z -> onZoomSettled(position, z) }
        // long press: work out which point of the page the finger is on
        var downX = 0f
        var downY = 0f
        iv.setOnTouchListener { _, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                downX = e.x; downY = e.y
            }
            false
        }
        iv.setOnLongClickListener {
            val d = iv.drawable
            if (d == null || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return@setOnLongClickListener false
            val m = android.graphics.Matrix()
            iv.imageMatrix.invert(m)
            val pts = floatArrayOf(downX, downY)
            m.mapPoints(pts)
            val fx = (pts[0] / d.intrinsicWidth).coerceIn(0f, 1f)
            val fy = (pts[1] / d.intrinsicHeight).coerceIn(0f, 1f)
            onLongPress(position, fx, fy)
            true
        }
        val samePage = holder.boundPosition == position
        holder.boundPosition = position
        holders[position] = holder

        val cached = cache.get(position)
        if (cached != null) {
            if (samePage) iv.setImageBitmapPreservingTransform(cached)
            else iv.setImageBitmap(cached)
            return
        }
        iv.setImageBitmap(null)
        renderInto(position, baseWidthProvider()) { bmp ->
            if (bmp == null) return@renderInto
            cache.put(position, bmp)
            if (holder.boundPosition == position) {
                iv.setImageBitmap(bmp)
                iv.alpha = 0.3f
                iv.animate().alpha(1f).setDuration(150).start()
            }
        }
    }

    override fun onViewRecycled(holder: VH) {
        if (holder.boundPosition >= 0) holders.remove(holder.boundPosition)
        holder.b.highlight.norm = null
        holder.boundPosition = -1
        holder.b.pageImage.setImageBitmap(null)
    }

    /** Re-render a page at a higher resolution and swap it in without losing the zoom. */
    fun upgradePage(position: Int, targetWidth: Int) {
        val rendered = renderedWidth[position] ?: 0
        if (rendered >= targetWidth) return
        if (!inFlight.add(position)) return
        renderInto(position, targetWidth) { bmp ->
            inFlight.remove(position)
            if (bmp != null) {
                cache.put(position, bmp)
                renderedWidth[position] = targetWidth
                val h = holders[position]
                if (h != null && h.boundPosition == position) {
                    h.b.pageImage.setImageBitmapPreservingTransform(bmp)
                }
            }
        }
    }

    /** Show (or clear) the search highlight for one page. */
    fun setHighlight(position: Int, norm: RectF?) {
        if (position < 0) {
            highlights.clear()
            holders.values.forEach { it.b.highlight.norm = null }
            return
        }
        if (norm == null) highlights.remove(position) else highlights.put(position, norm)
        holders[position]?.let {
            if (it.boundPosition == position) it.b.highlight.norm = norm
        }
    }

    fun invalidateAll() {
        cache.evictAll()
        renderedWidth.clear()
        inFlight.clear()
        notifyDataSetChanged()
    }

    private fun renderInto(position: Int, width: Int, onReady: (Bitmap?) -> Unit) {
        val rot = rotationProvider()
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching { renderer.render(position, width, rot) }.getOrNull()
            }
            onReady(bmp)
        }
    }

    private companion object {
        const val MAX_CACHE_BYTES = 72 * 1024 * 1024
    }
}
