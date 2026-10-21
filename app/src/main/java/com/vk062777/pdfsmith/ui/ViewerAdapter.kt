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

class ViewerAdapter(
    private val pageCount: Int,
    private val renderer: PageRenderer,
    private val scope: CoroutineScope,
    private val onZoom: (Boolean) -> Unit
) : RecyclerView.Adapter<ViewerAdapter.VH>() {

    private val cache = object : LruCache<Int, Bitmap>(6) {
        override fun sizeOf(key: Int, value: Bitmap) = 1
    }

    class VH(val b: ItemPageBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = pageCount

    override fun onBindViewHolder(holder: VH, position: Int) {
        val iv = holder.b.pageImage
        iv.onZoomChanged = onZoom
        val cached = cache.get(position)
        if (cached != null) {
            iv.setImageBitmap(cached)
            return
        }
        iv.setImageBitmap(null)
        val targetW = holder.itemView.resources.displayMetrics.widthPixels
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching { renderer.render(position, targetW) }.getOrNull()
            }
            if (bmp != null) cache.put(position, bmp)
            if (bmp != null && holder.bindingAdapterPosition == position) {
                iv.setImageBitmap(bmp)
            }
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.b.pageImage.setImageBitmap(null)
    }
}
