package com.vk062777.pdfsmith

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import com.vk062777.pdfsmith.databinding.ActivityCreatePdfBinding
import com.vk062777.pdfsmith.databinding.DialogTextBinding
import com.vk062777.pdfsmith.engine.PdfEngine
import com.vk062777.pdfsmith.model.Annot
import com.vk062777.pdfsmith.ui.AnnotRenderer
import com.vk062777.pdfsmith.ui.OverlayView
import com.vk062777.pdfsmith.util.Files
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Create a PDF from scratch: pick a page size and colour, then add text, images,
 * highlights and freehand drawing on any number of pages.
 */
class CreatePdfActivity : PdfActivity() {

    private class PageData(var w: Float, var h: Float, var bg: Int) {
        val items: MutableList<Annot> = mutableListOf()
        fun copyOf(): PageData {
            val p = PageData(w, h, bg)
            items.forEach { p.items.add(it.copy()) }
            return p
        }
    }

    private lateinit var binding: ActivityCreatePdfBinding
    private val pages = mutableListOf<PageData>()
    private var current = 0
    private var pendingPoint: Pair<Float, Float>? = null

    private val sizeNames = listOf("A4", "Letter", "Legal")
    private val sizeValues = listOf(floatArrayOf(595f, 842f), floatArrayOf(612f, 792f), floatArrayOf(612f, 1008f))
    private val orientNames = listOf("Portrait", "Landscape")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreatePdfBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.overlay.imageView = binding.pageImage
        binding.overlay.onTextAt = { fx, fy -> askText(fx, fy) }
        binding.overlay.onStampAt = { fx, fy -> startImage(fx, fy) }
        binding.overlay.onSelectionChanged = { updateOptions() }

        binding.sizeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sizeNames)
        binding.orientSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, orientNames)
        binding.sizeSpinner.setOnItemSelectedListener(onItem { applyPageSize() })
        binding.orientSpinner.setOnItemSelectedListener(onItem { applyPageSize() })

        binding.toolPan.setOnClickListener { selectTool(OverlayView.Tool.PAN, binding.toolPan) }
        binding.toolPen.setOnClickListener { selectTool(OverlayView.Tool.PEN, binding.toolPen) }
        binding.toolHighlight.setOnClickListener { selectTool(OverlayView.Tool.HIGHLIGHT, binding.toolHighlight) }
        binding.toolText.setOnClickListener { selectTool(OverlayView.Tool.TEXT, binding.toolText) }
        binding.toolImage.setOnClickListener { selectTool(OverlayView.Tool.IMAGE, binding.toolImage) }
        binding.toolMove.setOnClickListener { selectTool(OverlayView.Tool.SELECT, binding.toolMove) }
        binding.toolErase.setOnClickListener { selectTool(OverlayView.Tool.ERASE, binding.toolErase) }

        binding.swatchBlack.setOnClickListener { selectColour(Color.BLACK, binding.swatchBlack) }
        binding.swatchRed.setOnClickListener { selectColour(getColor(R.color.swatch_red), binding.swatchRed) }
        binding.swatchBlue.setOnClickListener { selectColour(getColor(R.color.swatch_blue), binding.swatchBlue) }
        binding.swatchGreen.setOnClickListener { selectColour(getColor(R.color.swatch_green), binding.swatchGreen) }
        binding.swatchOrange.setOnClickListener { selectColour(getColor(R.color.swatch_orange), binding.swatchOrange) }
        binding.swatchPurple.setOnClickListener { selectColour(getColor(R.color.swatch_purple), binding.swatchPurple) }

        binding.sizeSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            when (binding.overlay.tool) {
                OverlayView.Tool.PEN -> binding.overlay.penWidthPt = value
                OverlayView.Tool.HIGHLIGHT -> binding.overlay.highlightWidthPt = value
                OverlayView.Tool.TEXT -> binding.overlay.textSizePt = value
                OverlayView.Tool.SELECT -> {
                    val i = binding.overlay.selected
                    if (i in binding.overlay.items.indices) {
                        when (val a = binding.overlay.items[i]) {
                            is Annot.Text -> a.sizePt = value
                            else -> { }
                        }
                        binding.overlay.refresh()
                    }
                }
                else -> { }
            }
        }
        binding.opacitySlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) { binding.overlay.highlightOpacity = value; binding.overlay.refresh() }
        }
        binding.selDelete.setOnClickListener {
            val i = binding.overlay.selected
            if (i in binding.overlay.items.indices) {
                binding.overlay.items.removeAt(i)
                binding.overlay.selectItem(-1)
                binding.overlay.refresh()
            }
        }

        binding.prevPage.setOnClickListener { goTo(current - 1) }
        binding.nextPage.setOnClickListener { goTo(current + 1) }
        binding.addPage.setOnClickListener { addPage(after = current) }
        binding.dupPage.setOnClickListener {
            pages.add(current + 1, pages[current].copyOf())
            goTo(current + 1)
        }
        binding.delPage.setOnClickListener { deletePage() }

        selectColour(Color.BLACK, binding.swatchBlack)
        selectTool(OverlayView.Tool.PEN, binding.toolPen)

        pages.add(PageData(595f, 842f, Color.WHITE))
        goTo(0)
    }

    private fun onItem(action: () -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = action()
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) { }
    }

    private fun applyPageSize() {
        val size = sizeValues[binding.sizeSpinner.selectedItemPosition.coerceIn(0, sizeValues.size - 1)]
        val landscape = binding.orientSpinner.selectedItemPosition == 1
        val w = if (landscape) size[1] else size[0]
        val h = if (landscape) size[0] else size[1]
        pages.forEach { it.w = w; it.h = h }
        goTo(current)
    }

    private fun addPage(after: Int) {
        val ref = pages[after.coerceIn(0, pages.size - 1)]
        pages.add(after + 1, PageData(ref.w, ref.h, ref.bg))
        goTo(after + 1)
    }

    private fun deletePage() {
        if (pages.size <= 1) { toast(getString(R.string.msg_keep_one)); return }
        pages.removeAt(current)
        goTo(current.coerceAtMost(pages.size - 1))
    }

    private fun goTo(index: Int) {
        if (index !in pages.indices) return
        current = index
        val p = pages[index]
        binding.pageLabel.text = "${getString(R.string.create_page)} ${index + 1} / ${pages.size}"
        binding.prevPage.isEnabled = index > 0
        binding.nextPage.isEnabled = index < pages.size - 1
        binding.delPage.isEnabled = pages.size > 1

        val targetW = resources.displayMetrics.widthPixels
        val h = (p.h / p.w * targetW).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(targetW, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(p.bg)
        binding.pageImage.setImageBitmap(bmp)

        binding.overlay.pageW = p.w
        binding.overlay.pageH = p.h
        binding.overlay.items = p.items
        binding.overlay.selectItem(-1)
        binding.overlay.post { binding.overlay.refresh() }
    }

    private fun selectTool(tool: OverlayView.Tool, button: View) {
        binding.overlay.applyTool(tool)
        listOf(
            binding.toolPan, binding.toolPen, binding.toolHighlight, binding.toolText,
            binding.toolImage, binding.toolMove, binding.toolErase
        ).forEach { it.alpha = 0.4f }
        button.alpha = 1f
        updateOptions()
    }

    private fun selectColour(c: Int, active: View) {
        binding.overlay.color = c
        listOf(
            binding.swatchBlack, binding.swatchRed, binding.swatchBlue,
            binding.swatchGreen, binding.swatchOrange, binding.swatchPurple
        ).forEach { it.alpha = 0.4f }
        active.alpha = 1f
    }

    private fun updateOptions() {
        val t = binding.overlay.tool
        binding.colourRow.visibility = if (t == OverlayView.Tool.PAN || t == OverlayView.Tool.ERASE) View.GONE else View.VISIBLE
        binding.sizeBox.visibility =
            if (t in listOf(OverlayView.Tool.PEN, OverlayView.Tool.HIGHLIGHT, OverlayView.Tool.TEXT)) View.VISIBLE else View.GONE
        binding.opacityBox.visibility = if (t == OverlayView.Tool.HIGHLIGHT) View.VISIBLE else View.GONE
        binding.selDelete.visibility =
            if (t == OverlayView.Tool.SELECT && binding.overlay.selected >= 0) View.VISIBLE else View.GONE
        binding.optHint.text = if (t == OverlayView.Tool.SELECT && binding.overlay.selected >= 0)
            getString(R.string.hint_selected_item) else getString(R.string.msg_create_hint)
    }

    private fun askText(fx: Float, fy: Float) {
        val dlg = DialogTextBinding.inflate(layoutInflater)
        AlertDialog.Builder(this)
            .setTitle(R.string.tool_text_box)
            .setView(dlg.root)
            .setPositiveButton(R.string.action_add) { _, _ ->
                val text = dlg.textInput.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) { toast(getString(R.string.msg_enter_text)); return@setPositiveButton }
                binding.overlay.items.add(
                    Annot.Text(fx, fy, binding.overlay.textSizePt, binding.overlay.color, text)
                )
                binding.overlay.refresh()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun startImage(fx: Float, fy: Float) {
        pendingPoint = fx to fy
        pickAfterEnter { pickImages() }
    }

    override fun onImagesChosen(uris: List<Uri>) {
        val point = pendingPoint
        if (point == null || uris.isEmpty()) { pendingPoint = null; return }
        val bmp = decodeBounded(uris.first(), 2000)
        pendingPoint = null
        if (bmp == null) { toast(getString(R.string.msg_failed, "image")); return }
        val pw = binding.overlay.pageW
        val ph = binding.overlay.pageH
        val targetWpt = pw * 0.4f
        val targetHpt = targetWpt * bmp.height / bmp.width.toFloat()
        var fx = point.first - (targetWpt / pw) / 2f
        var fy = point.second - (targetHpt / ph) / 2f
        fx = fx.coerceIn(0f, max(0f, 1f - targetWpt / pw))
        fy = fy.coerceIn(0f, max(0f, 1f - targetHpt / ph))
        binding.overlay.items.add(Annot.Stamp(fx, fy, targetWpt / pw, targetHpt / ph, bmp))
        binding.overlay.refresh()
    }

    private fun decodeBounded(uri: Uri, maxDim: Int): Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, bounds)
            }
        } catch (_: Exception) { return null }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return try {
            contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (_: Exception) { null }
    }

    private fun choosePageColour() {
        val names = arrayOf("White", "Cream", "Light blue", "Light grey", "Dark")
        val colors = intArrayOf(Color.WHITE, 0xFFFFF8E1.toInt(), 0xFFE7F0FF.toInt(), 0xFFF1F3F5.toInt(), 0xFF1B1E24.toInt())
        AlertDialog.Builder(this)
            .setTitle(R.string.create_background)
            .setSingleChoiceItems(names, -1) { d, which ->
                pages[current].bg = colors[which]
                d.dismiss()
                goTo(current)
            }
            .show()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        goTo(current)
    }

    private fun save() {
        val withInk = pages.any { it.items.isNotEmpty() }
        if (!withInk) { toast(getString(R.string.msg_need_selection)); return }
        runAndSave("novapdf_created.pdf") { out ->
            val specs = ArrayList<PdfEngine.PageSpec>()
            pages.forEachIndexed { i, p ->
                var raster: File? = null
                if (p.items.isNotEmpty()) {
                    val bmp = AnnotRenderer.rasterize(p.items, p.w, p.h, 2.5f)
                    raster = Files.newFile(this, "page_${i + 1}.png")
                    FileOutputStream(raster).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bmp.recycle()
                }
                specs.add(PdfEngine.PageSpec(p.w, p.h, p.bg, raster))
            }
            PdfEngine.createPdf(specs, out)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.create_background)
        menu.add(0, 2, 1, R.string.action_save_as)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> { choosePageColour(); return true }
            2 -> { save(); return true }
        }
        return super.onOptionsItemSelected(item)
    }
}
