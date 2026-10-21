package com.vk062777.pdfsmith

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import com.vk062777.pdfsmith.databinding.ActivityAnnotateBinding
import com.vk062777.pdfsmith.databinding.DialogSignatureBinding
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

/** Annotate & sign: highlight, draw, add text, place a signature or an image. */
class AnnotateActivity : PdfActivity() {

    private lateinit var binding: ActivityAnnotateBinding
    private var renderer: PageRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var source: File? = null
    private var sourceName: String = "document.pdf"

    private var pageCount = 0
    private var current = 0
    private val pageSizes = mutableListOf<FloatArray>()
    private val stamps = HashMap<Int, MutableList<Annot>>()

    private var pendingPoint: Pair<Float, Float>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAnnotateBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.overlay.imageView = binding.pageImage

        binding.toolHighlight.setOnClickListener { selectTool(OverlayView.Tool.HIGHLIGHT) }
        binding.toolDraw.setOnClickListener { selectTool(OverlayView.Tool.DRAW) }
        binding.toolText.setOnClickListener { selectTool(OverlayView.Tool.TEXT) }
        binding.toolSign.setOnClickListener { selectTool(OverlayView.Tool.SIGN) }
        binding.toolImage.setOnClickListener { selectTool(OverlayView.Tool.IMAGE) }
        binding.toolClear.setOnClickListener {
            binding.overlay.items.clear()
            binding.overlay.refresh()
        }
        binding.prevPage.setOnClickListener { goTo(current - 1) }
        binding.nextPage.setOnClickListener { goTo(current + 1) }

        binding.overlay.onTextAt = { fx, fy -> askText(fx, fy) }
        binding.overlay.onStampAt = { fx, fy -> startStamp(fx, fy) }

        selectTool(OverlayView.Tool.HIGHLIGHT)
        pickPdf()
    }

    private fun selectTool(tool: OverlayView.Tool) {
        binding.overlay.tool = tool
        binding.overlay.color = Color.BLACK
        binding.overlay.strokeWidthPt = 2.5f
        binding.overlay.textSizePt = 14f
        val ids = mapOf(
            OverlayView.Tool.HIGHLIGHT to binding.toolHighlight,
            OverlayView.Tool.DRAW to binding.toolDraw,
            OverlayView.Tool.TEXT to binding.toolText,
            OverlayView.Tool.SIGN to binding.toolSign,
            OverlayView.Tool.IMAGE to binding.toolImage
        )
        ids.forEach { (t, v) -> v.alpha = if (t == tool) 1f else 0.45f }
    }

    override fun onPdfChosen(uri: Uri) {
        sourceName = Files.displayName(this, uri)
        runTask(getString(R.string.msg_processing)) {
            val f = Files.copyToCache(this, uri)
            if (PdfEngine.isEncrypted(f)) error(getString(R.string.msg_encrypted))
            source = f
            pageSizes.clear()
            pageSizes.addAll(PdfEngine.pageSizes(f))
            val d = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd = d
            val r = android.graphics.pdf.PdfRenderer(d)
            renderer = PageRenderer(r)
            r.pageCount
        } result@{ count ->
            if (count == null || count == 0) return@result
            pageCount = count
            binding.toolbar.title = Files.baseName(sourceName)
            goTo(0)
        }
    }

    private fun goTo(index: Int) {
        if (index < 0 || index >= pageCount) return
        current = index
        binding.pageLabel.text = "${index + 1} / $pageCount"
        binding.prevPage.isEnabled = index > 0
        binding.nextPage.isEnabled = index < pageCount - 1
        val r = renderer ?: return
        val targetW = resources.displayMetrics.widthPixels
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching { r.render(index, targetW) }.getOrNull()
            }
            if (bmp != null) {
                binding.pageImage.setImageBitmap(bmp)
                val size = pageSizes.getOrNull(index) ?: floatArrayOf(595f, 842f)
                binding.overlay.pageW = size[0]
                binding.overlay.pageH = size[1]
                binding.overlay.items = stamps.getOrPut(index) { mutableListOf() }
                binding.overlay.post { binding.overlay.refresh() }
            }
        }
    }

    private fun askText(fx: Float, fy: Float) {
        val input = EditText(this)
        input.hint = getString(R.string.hint_watermark_text)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val wrap = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(R.string.tool_annotate)
            .setView(wrap)
            .setPositiveButton(R.string.action_add) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    binding.overlay.items.add(
                        Annot.Text(fx, fy, binding.overlay.textSizePt, Color.BLACK, text)
                    )
                    binding.overlay.refresh()
                }
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
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.tool_annotate)
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
        dlg.clear.setOnClickListener { dlg.pad.clear() }
        dialog.show()
    }

    override fun onImagesChosen(uris: List<Uri>) {
        val point = pendingPoint
        if (point == null || uris.isEmpty()) { pendingPoint = null; return }
        val uri = uris.first()
        val bmp = try {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) { null }
        pendingPoint = null
        if (bmp == null) { toast(getString(R.string.msg_failed, "image")); return }
        placeStamp(bmp)
    }

    /** Place a bitmap centred on the tapped point, keeping its aspect ratio. */
    private fun placeStamp(bmp: Bitmap) {
        val point = pendingPoint ?: return
        val pw = binding.overlay.pageW
        val ph = binding.overlay.pageH
        val targetWpt = pw * 0.35f
        val targetHpt = targetWpt * bmp.height / bmp.width.toFloat()
        var fx = point.first - (targetWpt / pw) / 2f
        var fy = point.second - (targetHpt / ph) / 2f
        fx = fx.coerceIn(0f, 1f - targetWpt / pw)
        fy = fy.coerceIn(0f, 1f - targetHpt / ph)
        binding.overlay.items.add(
            Annot.Stamp(fx, fy, targetWpt / pw, targetHpt / ph, bmp)
        )
        binding.overlay.refresh()
    }

    private fun save() {
        val src = source ?: return toast(getString(R.string.msg_pick_file_first))
        val pagesWithInk = stamps.filterValues { it.isNotEmpty() }
        if (pagesWithInk.isEmpty()) { toast(getString(R.string.msg_need_selection)); return }
        runAndSave(Files.suggestName(sourceName, "_annotated")) { out ->
            val images = ArrayList<PdfEngine.ImageStamp>()
            for ((page, list) in pagesWithInk) {
                val size = pageSizes.getOrNull(page) ?: floatArrayOf(595f, 842f)
                val bmp = com.vk062777.pdfsmith.ui.AnnotRenderer.rasterize(list, size[0], size[1], 2f)
                val png = Files.newFile(this, "annot_${page + 1}.png")
                FileOutputStream(png).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                images.add(PdfEngine.ImageStamp(page + 1, 0f, 0f, size[0], size[1], png))
            }
            PdfEngine.applyStamps(src, null, images, null, out)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.action_save_as).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) { save(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer?.close()
        try { pfd?.close() } catch (_: Exception) { }
    }
}
