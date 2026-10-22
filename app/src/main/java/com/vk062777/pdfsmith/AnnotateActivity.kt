package com.vk062777.pdfsmith

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.vk062777.pdfsmith.databinding.ActivityAnnotateBinding
import com.vk062777.pdfsmith.databinding.DialogSignatureBinding
import com.vk062777.pdfsmith.databinding.DialogTextBinding
import com.vk062777.pdfsmith.engine.PdfEngine
import com.vk062777.pdfsmith.model.Annot
import com.vk062777.pdfsmith.ui.OverlayView
import com.vk062777.pdfsmith.ui.PageRenderer
import com.vk062777.pdfsmith.util.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Annotate & sign. Zoomable page with a drawing overlay: pen, highlighter, text,
 * cover-and-retype, signature and images — all with colour, size, opacity, undo/redo
 * and move/resize.
 */
class AnnotateActivity : PdfActivity() {

    private lateinit var binding: ActivityAnnotateBinding
    private var renderer: PageRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var source: File? = null
    private var sourceName: String = "document.pdf"

    private var pageCount = 0
    private var current = 0
    private var viewRotation = 0
    private val renderedWidth = HashMap<Int, Int>()
    private val pageSizes = mutableListOf<FloatArray>()
    private val stamps = HashMap<Int, MutableList<Annot>>()

    private val undoStack = ArrayDeque<List<Annot>>()
    private val redoStack = ArrayDeque<List<Annot>>()

    private var pendingPoint: Pair<Float, Float>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAnnotateBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.overlay.imageView = binding.pageImage
        binding.overlay.onTextAt = { fx, fy -> askText(fx, fy) }
        binding.overlay.onStampAt = { fx, fy -> startStamp(fx, fy) }
        binding.overlay.onCoverRect = { x, y, w, h -> askCover(x, y, w, h) }
        binding.overlay.onBeforeChange = { snapshotBefore() }
        binding.overlay.onChanged = { }
        binding.overlay.onSelectionChanged = { updateOptions() }
        binding.pageImage.onZoomSettled = { z -> upgradeCurrent(z) }

        binding.toolPan.setOnClickListener { selectTool(OverlayView.Tool.PAN, binding.toolPan) }
        binding.toolPen.setOnClickListener { selectTool(OverlayView.Tool.PEN, binding.toolPen) }
        binding.toolHighlight.setOnClickListener { selectTool(OverlayView.Tool.HIGHLIGHT, binding.toolHighlight) }
        binding.toolText.setOnClickListener { selectTool(OverlayView.Tool.TEXT, binding.toolText) }
        binding.toolCover.setOnClickListener { selectTool(OverlayView.Tool.COVER, binding.toolCover) }
        binding.toolSign.setOnClickListener { selectTool(OverlayView.Tool.SIGN, binding.toolSign) }
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
            applySize(value)
        }
        binding.opacitySlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) { binding.overlay.highlightOpacity = value; binding.overlay.refresh() }
        }
        binding.selDelete.setOnClickListener { deleteSelected() }
        binding.selFront.setOnClickListener { bringToFront() }

        binding.prevPage.setOnClickListener { goTo(current - 1) }
        binding.nextPage.setOnClickListener { goTo(current + 1) }

        selectColour(Color.BLACK, binding.swatchBlack)
        selectTool(OverlayView.Tool.PEN, binding.toolPen)
        pickPdf()
    }

    // ---- tools & options ---------------------------------------------------

    private fun selectTool(tool: OverlayView.Tool, button: View) {
        binding.overlay.applyTool(tool)
        val buttons = listOf(
            binding.toolPan, binding.toolPen, binding.toolHighlight, binding.toolText,
            binding.toolCover, binding.toolSign, binding.toolImage, binding.toolMove, binding.toolErase
        )
        buttons.forEach { it.alpha = 0.4f }
        button.alpha = 1f
        // sensible defaults when switching tool
        when (tool) {
            OverlayView.Tool.PEN -> binding.sizeSlider.value = binding.overlay.penWidthPt
            OverlayView.Tool.HIGHLIGHT -> binding.sizeSlider.value = binding.overlay.highlightWidthPt
            OverlayView.Tool.TEXT, OverlayView.Tool.COVER -> binding.sizeSlider.value = binding.overlay.textSizePt
            else -> { }
        }
        updateOptions()
    }

    private fun applySize(value: Float) {
        when (binding.overlay.tool) {
            OverlayView.Tool.PEN -> binding.overlay.penWidthPt = value
            OverlayView.Tool.HIGHLIGHT -> binding.overlay.highlightWidthPt = value
            OverlayView.Tool.TEXT, OverlayView.Tool.COVER -> binding.overlay.textSizePt = value
            OverlayView.Tool.SELECT -> {
                val i = binding.overlay.selected
                if (i in binding.overlay.items.indices) {
                    snapshotBefore()
                    when (val a = binding.overlay.items[i]) {
                        is Annot.Text -> a.sizePt = value
                        is Annot.Stamp -> {
                            val ratio = a.fh / a.fw
                            a.fw = (value / 100f).coerceIn(0.05f, 1.2f)
                            a.fh = a.fw * ratio
                        }
                        else -> { }
                    }
                    binding.overlay.refresh()
                }
            }
            else -> { }
        }
    }

    private fun selectColour(c: Int, active: View) {
        binding.overlay.color = c
        val swatches = listOf(
            binding.swatchBlack, binding.swatchRed, binding.swatchBlue,
            binding.swatchGreen, binding.swatchOrange, binding.swatchPurple
        )
        swatches.forEach { it.alpha = 0.4f }
        active.alpha = 1f
        if (binding.overlay.tool == OverlayView.Tool.SELECT) {
            val i = binding.overlay.selected
            if (i in binding.overlay.items.indices) {
                snapshotBefore()
                when (val a = binding.overlay.items[i]) {
                    is Annot.Text -> a.color = c
                    is Annot.Stroke -> a.color = c
                    is Annot.Highlight -> a.color = c
                    is Annot.Cover -> a.color = c
                    else -> { }
                }
                binding.overlay.refresh()
            }
        }
    }

    private fun updateOptions() {
        val t = binding.overlay.tool
        val showColour = t in listOf(
            OverlayView.Tool.PEN, OverlayView.Tool.HIGHLIGHT, OverlayView.Tool.TEXT,
            OverlayView.Tool.COVER, OverlayView.Tool.SIGN
        ) || (t == OverlayView.Tool.SELECT && binding.overlay.selected in binding.overlay.items.indices)
        binding.colourRow.visibility = if (showColour) View.VISIBLE else View.GONE
        binding.sizeBox.visibility =
            if (t in listOf(OverlayView.Tool.PEN, OverlayView.Tool.HIGHLIGHT, OverlayView.Tool.TEXT, OverlayView.Tool.COVER)) View.VISIBLE else View.GONE
        binding.opacityBox.visibility = if (t == OverlayView.Tool.HIGHLIGHT) View.VISIBLE else View.GONE
        binding.selBox.visibility =
            if (t == OverlayView.Tool.SELECT && binding.overlay.selected >= 0) View.VISIBLE else View.GONE
        binding.optHint.text = when (t) {
            OverlayView.Tool.COVER -> getString(R.string.hint_cover)
            OverlayView.Tool.SIGN -> getString(R.string.hint_sign)
            OverlayView.Tool.SELECT ->
                if (binding.overlay.selected >= 0) getString(R.string.hint_selected_item) else ""
            OverlayView.Tool.ERASE -> getString(R.string.tool_erase)
            else -> ""
        }
    }

    private fun deleteSelected() {
        val i = binding.overlay.selected
        if (i !in binding.overlay.items.indices) return
        snapshotBefore()
        binding.overlay.items.removeAt(i)
        binding.overlay.selectItem(-1)
        binding.overlay.refresh()
    }

    private fun bringToFront() {
        val i = binding.overlay.selected
        if (i !in binding.overlay.items.indices) return
        snapshotBefore()
        val item = binding.overlay.items.removeAt(i)
        binding.overlay.items.add(item)
        binding.overlay.selectItem(binding.overlay.items.size - 1)
        binding.overlay.refresh()
    }

    // ---- undo / redo -------------------------------------------------------

    private fun snapshotBefore() {
        undoStack.addLast(binding.overlay.items.map { it.copy() })
        if (undoStack.size > 40) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun restore(list: List<Annot>) {
        binding.overlay.items.clear()
        binding.overlay.items.addAll(list.map { it.copy() })
        binding.overlay.selectItem(-1)
        binding.overlay.refresh()
    }

    private fun undo() {
        val s = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(binding.overlay.items.map { it.copy() })
        restore(s)
    }

    private fun redo() {
        val s = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(binding.overlay.items.map { it.copy() })
        restore(s)
    }

    // ---- document ----------------------------------------------------------

    override fun onPdfChosen(uri: Uri) {
        sourceName = Files.displayName(this, uri)
        openPdf(uri) { f ->
            binding.toolbar.title = Files.baseName(sourceName)
            runTask(getString(R.string.msg_processing), onDone = opened@{ count ->
                if (count == null || count == 0) return@opened
                pageCount = count
                goTo(0)
            }) {
                source = f
                pageSizes.clear()
                pageSizes.addAll(PdfEngine.pageDisplaySizes(f))
                val d = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                pfd = d
                val r = PdfRenderer(d)
                renderer = PageRenderer(r)
                r.pageCount
            }
        }
    }

    private fun goTo(index: Int) {
        if (index < 0 || index >= pageCount) return
        current = index
        binding.pageLabel.text = "${index + 1} / $pageCount"
        binding.prevPage.isEnabled = index > 0
        binding.nextPage.isEnabled = index < pageCount - 1
        undoStack.clear(); redoStack.clear()
        val r = renderer ?: return
        val targetW = baseRenderWidth()
        renderedWidth[index] = targetW
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching { r.render(index, targetW, viewRotation) }.getOrNull()
            }
            if (bmp != null && current == index) {
                binding.pageImage.setImageBitmap(bmp)
                bindOverlay(index)
            }
        }
    }

    private fun bindOverlay(index: Int) {
        val size = displaySize(index)
        binding.overlay.pageW = size[0]
        binding.overlay.pageH = size[1]
        binding.overlay.items = stamps.getOrPut(index) { mutableListOf() }
        binding.overlay.selectItem(-1)
        binding.overlay.post { binding.overlay.refresh() }
    }

    /** Page size as displayed, including the in-app view rotation. */
    private fun displaySize(index: Int): FloatArray {
        val base = pageSizes.getOrNull(index) ?: floatArrayOf(595f, 842f)
        return if (viewRotation % 180 == 90) floatArrayOf(base[1], base[0]) else base
    }

    private fun baseRenderWidth() =
        (resources.displayMetrics.widthPixels * 2.0f).toInt().coerceAtMost(MAX_WIDTH)

    /** Zooming in re-renders the page sharper without disturbing the zoom. */
    private fun upgradeCurrent(zoom: Float) {
        val r = renderer ?: return
        val index = current
        val target = (resources.displayMetrics.widthPixels * zoom.coerceIn(2f, 4f))
            .toInt().coerceAtMost(MAX_WIDTH)
        if ((renderedWidth[index] ?: 0) >= target) return
        renderedWidth[index] = target
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching { r.render(index, target, viewRotation) }.getOrNull()
            }
            if (bmp != null && current == index) {
                binding.pageImage.setImageBitmapPreservingTransform(bmp)
            }
        }
    }

    // ---- adding content ----------------------------------------------------

    private fun askText(fx: Float, fy: Float) {
        val dlg = DialogTextBinding.inflate(layoutInflater)
        AlertDialog.Builder(this)
            .setTitle(R.string.tool_text_box)
            .setView(dlg.root)
            .setPositiveButton(R.string.action_add) { _, _ ->
                val text = dlg.textInput.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) { toast(getString(R.string.msg_enter_text)); return@setPositiveButton }
                snapshotBefore()
                binding.overlay.items.add(
                    Annot.Text(fx, fy, binding.overlay.textSizePt, binding.overlay.color, text)
                )
                binding.overlay.refresh()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun askCover(x: Float, y: Float, w: Float, h: Float) {
        val dlg = DialogTextBinding.inflate(layoutInflater)
        AlertDialog.Builder(this)
            .setTitle(R.string.tool_cover)
            .setView(dlg.root)
            .setPositiveButton(R.string.action_apply) { _, _ ->
                val text = dlg.textInput.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) { toast(getString(R.string.msg_enter_text)); return@setPositiveButton }
                snapshotBefore()
                binding.overlay.items.add(Annot.Cover(x, y, w, h, Color.WHITE))
                val pageH = binding.overlay.pageH
                val size = max(8f, min(h * pageH * 0.62f, binding.overlay.textSizePt * 3f))
                binding.overlay.items.add(
                    Annot.Text(x + 0.004f, y + h * 0.78f, size, binding.overlay.color, text)
                )
                binding.overlay.refresh()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun startStamp(fx: Float, fy: Float) {
        pendingPoint = fx to fy
        when (binding.overlay.tool) {
            OverlayView.Tool.SIGN -> showSignatureDialog()
            OverlayView.Tool.IMAGE -> pickImages()
            else -> { }
        }
    }

    private fun showSignatureDialog() {
        val dlg = DialogSignatureBinding.inflate(layoutInflater)
        var ink = binding.overlay.color
        dlg.pad.setInkColor(ink)
        dlg.inkBlack.setOnClickListener { ink = Color.BLACK; dlg.pad.setInkColor(ink) }
        dlg.inkBlue.setOnClickListener { ink = getColor(R.color.swatch_blue); dlg.pad.setInkColor(ink) }
        dlg.inkRed.setOnClickListener { ink = getColor(R.color.swatch_red); dlg.pad.setInkColor(ink) }
        dlg.clear.setOnClickListener { dlg.pad.clear() }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.tool_signature)
            .setView(dlg.root)
            .setPositiveButton(R.string.action_add, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (dlg.pad.isEmpty()) { toast(getString(R.string.msg_enter_text)); return@setOnClickListener }
                placeStamp(dlg.pad.toBitmap())
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    override fun onImagesChosen(uris: List<Uri>) {
        val point = pendingPoint
        if (point == null || uris.isEmpty()) { pendingPoint = null; return }
        val bmp = decodeBounded(uris.first(), 2000)
        pendingPoint = null
        if (bmp == null) { toast(getString(R.string.msg_failed, "image")); return }
        placeStamp(bmp)
    }

    private fun decodeBounded(uri: Uri, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (_: Exception) { return null }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return try {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) { null }
    }

    private fun placeStamp(bmp: Bitmap) {
        val point = pendingPoint ?: return
        val pw = binding.overlay.pageW
        val ph = binding.overlay.pageH
        val targetWpt = pw * 0.35f
        val targetHpt = targetWpt * bmp.height / bmp.width.toFloat()
        var fx = point.first - (targetWpt / pw) / 2f
        var fy = point.second - (targetHpt / ph) / 2f
        fx = fx.coerceIn(0f, max(0f, 1f - targetWpt / pw))
        fy = fy.coerceIn(0f, max(0f, 1f - targetHpt / ph))
        snapshotBefore()
        binding.overlay.items.add(Annot.Stamp(fx, fy, targetWpt / pw, targetHpt / ph, bmp))
        binding.overlay.refresh()
    }

    // ---- save --------------------------------------------------------------

    private fun save() {
        val src = source ?: return toast(getString(R.string.msg_pick_file_first))
        val pagesWithInk = stamps.filterValues { it.isNotEmpty() }
        if (pagesWithInk.isEmpty()) { toast(getString(R.string.msg_need_selection)); return }
        runAndSave(Files.suggestName(sourceName, "_annotated")) { out ->
            val pages = ArrayList<Int>()
            val rasters = ArrayList<File>()
            for ((page, list) in pagesWithInk) {
                val size = displaySize(page)
                val bmp = com.vk062777.pdfsmith.ui.AnnotRenderer.rasterize(list, size[0], size[1], 2.5f)
                val png = Files.newFile(this, "annot_${page + 1}.png")
                FileOutputStream(png).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                pages.add(page + 1)
                rasters.add(png)
            }
            // The overlay lives in the displayed frame, so give the output pages the
            // matching /Rotate first, then stamp rotation-aware.
            val target = if (viewRotation == 0) src else Files.newFile(this, "rot.pdf")
            if (viewRotation != 0) PdfEngine.rotatePages(src, null, viewRotation, target)
            PdfEngine.stampDisplayRasters(target, pages, rasters, out)
        }
    }

    /** Rotate the view 90° clockwise — instant, because only one page re-renders. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        goTo(current)
    }

    private fun rotateDocument() {
        viewRotation = (viewRotation + 90) % 360
        stamps.values.forEach { rotateItems90(it) }
        renderedWidth.clear()
        goTo(current)
    }

    /** 90° clockwise transform in normalised (display) coordinates. */
    private fun rotateItems90(list: MutableList<Annot>) {
        for (i in list.indices) {
            when (val a = list[i]) {
                is Annot.Text -> {
                    val nx = 1f - a.fy; val ny = a.fx
                    a.fx = nx; a.fy = ny
                }
                is Annot.Cover -> {
                    val nx = 1f - (a.fy + a.fh); val ny = a.fx
                    val nw = a.fh; val nh = a.fw
                    a.fx = nx; a.fy = ny; a.fw = nw; a.fh = nh
                }
                is Annot.Highlight -> {
                    val nx = 1f - (a.fy + a.fh); val ny = a.fx
                    val nw = a.fh; val nh = a.fw
                    a.fx = nx; a.fy = ny; a.fw = nw; a.fh = nh
                }
                is Annot.Stamp -> {
                    val nx = 1f - (a.fy + a.fh); val ny = a.fx
                    val nw = a.fh; val nh = a.fw
                    a.fx = nx; a.fy = ny; a.fw = nw; a.fh = nh
                }
                is Annot.Stroke -> a.points.forEach { p ->
                    val nx = 1f - p.y; val ny = p.x
                    p.x = nx; p.y = ny
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.action_undo).setIcon(R.drawable.ic_undo)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 2, 1, R.string.action_redo).setIcon(R.drawable.ic_redo)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 4, 2, R.string.action_rotate).setIcon(R.drawable.ic_rotate)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 3, 3, R.string.action_save_as).setIcon(R.drawable.ic_save)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> { undo(); return true }
            2 -> { redo(); return true }
            3 -> { save(); return true }
            4 -> { rotateDocument(); return true }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer?.close()
        try { pfd?.close() } catch (_: Exception) { }
    }

    private companion object {
        const val MAX_WIDTH = 2800
    }
}
