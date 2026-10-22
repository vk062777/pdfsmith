package com.vk062777.pdfsmith.ui

import android.graphics.Bitmap
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
 * Pages of the reader. Bitmaps are cached by size (so memory stays bounded no matter
 * how large the supersampled render is) and re-rendered at higher resolution on demand
 * when the user zooms in, which is what keeps text crisp.
 */
class ViewerAdapter(
    private val pageCount: Int,
    private val renderer: PageRenderer,
    private val scope: CoroutineScope,
    private val rotationProvider: () -> Int,
    private val baseWidthProvider: () -> Int,
    private val onZoom: (Boolean) -> Unit,
    private val onZoomSettled: (Int, Float) -> Unit
) : RecyclerView.Adapter<ViewerAdapter.VH>() {

    private val cache = object : LruCache<Int, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    private val renderedWidth = HashMap<Int, Int>()
    private val inFlight = HashSet<Int>()

    class VH(val b: ItemPageBinding) : RecyclerView.ViewHolder(b.root) {
        var boundPosition = -1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = pageCount

    override fun onBindViewHolder(holder: VH, position: Int) {
        val iv = holder.b.pageImage
        iv.onZoomChanged = onZoom
        iv.onZoomSettled = { z -> onZoomSettled(position, z) }
        val samePage = holder.boundPosition == position
        holder.boundPosition = position

        val cached = cache.get(position)
        if (cached != null) {
            if (samePage) iv.setImageBitmapPreservingTransform(cached) else iv.setImageBitmap(cached)
            return
        }
        if (!samePage) iv.setImageBitmap(null)
        renderInto(position, baseWidthProvider()) { bmp ->
            if (bmp == null) return@renderInto
            if (holder.bindingAdapterPosition != position) return@renderInto
            if (holder.boundPosition == position) {
                iv.setImageBitmapPreservingTransform(bmp)
            } else {
                iv.setImageBitmap(bmp)
                iv.alpha = 0.25f
                iv.animate().alpha(1f).setDuration(160).start()
            }
        }
    }

    /** Render (if needed) at least [targetWidth] px wide and refresh that page. */
    fun upgradePage(position: Int, targetWidth: Int) {
        val current = renderedWidth[position] ?: 0
        if (current >= targetWidth) return
        renderInto(position, targetWidth) { bmp ->
            if (bmp != null) notifyItemChanged(position)
        }
    }

    private fun renderInto(position: Int, width: Int, onReady: (Bitmap?) -> Unit) {
        if (inFlight.contains(position)) return
        inFlight.add(position)
        val rot = rotationProvider()
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching { renderer.render(position, width, rot) }.getOrNull()
            }
            inFlight.remove(position)
            if (bmp != null) {
                cache.put(position, bmp)
                renderedWidth[position] = width
            }
            onReady(bmp)
        }
    }

    fun invalidateAll() {
        cache.evictAll()
        renderedWidth.clear()
        notifyDataSetChanged()
    }

    override fun onViewRecycled(holder: VH) {
        holder.boundPosition = -1
        holder.b.pageImage.setImageBitmap(null)
    }

    companion object {
        private const val MAX_CACHE_BYTES = 96 * 1024 * 1024
    }
}
