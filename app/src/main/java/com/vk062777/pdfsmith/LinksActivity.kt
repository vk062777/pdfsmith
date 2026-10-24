package com.vk062777.pdfsmith

import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.vk062777.pdfsmith.databinding.ActivityLinksBinding
import com.vk062777.pdfsmith.engine.PdfEngine
import com.vk062777.pdfsmith.ui.PageRenderer
import com.vk062777.pdfsmith.util.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Turns the web / e-mail addresses already written in a PDF into real, clickable
 * link annotations — or lets you draw one yourself.
 *
 * Detection reads the actual glyph positions, so a link covers exactly the text it
 * came from (verified by rendering the result and measuring where the ink is).
 */
class LinksActivity : PdfActivity() {

    private lateinit var binding: ActivityLinksBinding
    private var renderer: PageRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var source: File? = null
    private var sourceName: String = "document.pdf"
    private var pageCount = 0
    private var current = 0
    private val sizes = mutableListOf<FloatArray>()
    private var found: List<PdfEngine.FoundLink> = emptyList()
    private var rectPlaced = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLinksBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.rectPick.imageView = binding.pageImage
        // pointing anywhere on the page should place the link there
        binding.rectPick.moveOnTapOutside = true

        binding.btnChoose.setOnClickListener { pickPdf() }
        binding.prevPage.setOnClickListener { goTo(current - 1) }
        binding.nextPage.setOnClickListener { goTo(current + 1) }
        binding.btnRun.setOnClickListener { run() }
        binding.btnPlace.setOnClickListener { togglePlacement() }
        binding.modeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) applyMode(id == R.id.modeManual)
        }
        binding.modeGroup.check(R.id.modeAuto)
        binding.kindGroup.check(R.id.kindIcon)
        binding.kindGroup.addOnButtonCheckedListener { _, _, checked ->
            if (checked) applyKind()
        }
        binding.fontSpinner.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, PdfEngine.LINK_FONTS
        )
        buildColourRow()
        pickPdf()
    }

    /** Full-screen placement: hide the controls so the page fills the screen. */
    private var placing = false

    private fun togglePlacement() {
        if (!binding.modeManual.isChecked) {
            binding.modeGroup.check(R.id.modeManual)
        }
        placing = !placing
        val chrome = if (placing) android.view.View.GONE else android.view.View.VISIBLE
        binding.info.visibility = chrome
        binding.kindGroup.visibility = chrome
        binding.textWrap.visibility = chrome
        binding.fontRow.visibility = chrome
        binding.opacityRow.visibility = chrome
        binding.colourRow.visibility = chrome
        binding.urlWrap.visibility = chrome
        binding.applyAll.visibility = chrome
        binding.btnRun.visibility = chrome
        binding.btnPlace.text = if (placing) getString(R.string.links_done)
        else getString(R.string.links_fullscreen)
        if (!placing) applyKind()
    }

    /** Icon vs custom text for the manual link. */
    private fun applyKind() {
        val text = binding.kindText.isChecked
        binding.textWrap.visibility = if (text) android.view.View.VISIBLE else android.view.View.GONE
        binding.fontRow.visibility = if (text) android.view.View.VISIBLE else android.view.View.GONE
        if (binding.modeManual.isChecked) {
            binding.info.text = if (text) getString(R.string.links_manual_text_hint)
            else getString(R.string.links_manual_hint)
        }
    }

    // ---- icon colour -------------------------------------------------------

    private val iconColours = intArrayOf(
        0xFF149E8C.toInt(), 0xFF1D4ED8.toInt(), 0xFFB91C1C.toInt(),
        0xFF111827.toInt(), 0xFFF59E0B.toInt()
    )
    private var chosenColour = iconColours[0]
    private val swatches = ArrayList<android.widget.ImageView>()

    private fun buildColourRow() {
        binding.colourRow.removeAllViews()
        swatches.clear()
        iconColours.forEach { c ->
            val v = android.widget.ImageView(this)
            val size = dp(30)
            val lp = android.widget.LinearLayout.LayoutParams(size, size)
            lp.rightMargin = dp(10)
            v.layoutParams = lp
            v.setImageDrawable(swatch(c))
            v.setOnClickListener { chosenColour = c; updateSwatches() }
            binding.colourRow.addView(v)
            swatches.add(v)
        }
        updateSwatches()
    }

    private fun updateSwatches() {
        swatches.forEachIndexed { i, v ->
            val on = iconColours[i] == chosenColour
            v.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(iconColours[i])
                setStroke(dp(if (on) 3 else 0), getColor(if (on) R.color.text_primary else R.color.divider))
            }
        }
    }

    private fun swatch(c: Int) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        setColor(c)
    }

    /** A small rounded button with a white link glyph, in the chosen colour. */
    private fun makeIcon(colour: Int, size: Int = 256): android.graphics.Bitmap {
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = colour
        val pad = size * 0.06f
        c.drawRoundRect(
            android.graphics.RectF(pad, pad, size - pad, size - pad),
            size * 0.26f, size * 0.26f, p
        )
        val d = androidx.appcompat.content.res.AppCompatResources.getDrawable(this, R.drawable.ic_link)
        d?.let {
            it.setTint(android.graphics.Color.WHITE)
            val in0 = (size * 0.24f).toInt()
            val in1 = (size * 0.76f).toInt()
            it.setBounds(in0, in0, in1, in1)
            it.draw(c)
        }
        return bmp
    }

    override fun onPdfChosen(uri: Uri) {
        sourceName = Files.displayName(this, uri)
        openPdf(uri) { f ->
            binding.fileName.text = sourceName
            binding.toolbar.title = Files.baseName(sourceName)
            runTask(getString(R.string.msg_processing), onDone = opened@{ res ->
                if (res == null) return@opened
                pageCount = res.first
                found = res.second
                binding.btnRun.isEnabled = true
                showFound()
                if (binding.modeManual.isChecked) goTo(0)
            }) {
                source = f
                sizes.clear()
                sizes.addAll(PdfEngine.pageDisplaySizes(f))
                val d = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                try { pfd?.close() } catch (_: Exception) { }
                pfd = d
                val r = PdfRenderer(d)
                renderer = PageRenderer(r)
                r.pageCount to PdfEngine.findLinks(f)
            }
        }
    }

    private fun showFound() {
        binding.info.text = if (found.isEmpty()) getString(R.string.links_none)
        else getString(R.string.links_found, found.size)
        binding.linkList.removeAllViews()
        found.take(200).forEach { l ->
            val row = android.widget.TextView(this).apply {
                text = "Page ${l.page}  \\u2022  ${l.url}"
                setTextColor(getColor(R.color.text_primary))
                textSize = 13f
                setPadding(dp(10), dp(9), dp(10), dp(9))
            }
            binding.linkList.addView(row)
        }
    }

    private fun applyMode(manual: Boolean) {
        binding.manualWrap.visibility = if (manual) View.VISIBLE else View.GONE
        binding.listWrap.visibility = if (manual) View.GONE else View.VISIBLE
        binding.info.text = if (manual) {
            if (binding.kindText.isChecked) getString(R.string.links_manual_text_hint)
            else getString(R.string.links_manual_hint)
        } else {
            if (found.isEmpty()) getString(R.string.links_none)
            else getString(R.string.links_found, found.size)
        }
        if (!manual && placing) togglePlacement()
        binding.kindGroup.visibility = if (manual) android.view.View.VISIBLE else android.view.View.GONE
        if (manual) applyKind() else {
            binding.textWrap.visibility = android.view.View.GONE
            binding.fontRow.visibility = android.view.View.GONE
            binding.opacityRow.visibility = android.view.View.GONE
        }
        binding.colourRow.visibility = if (manual) android.view.View.VISIBLE else android.view.View.GONE
        binding.opacityRow.visibility = if (manual) android.view.View.VISIBLE else android.view.View.GONE
        if (manual) {
            if (pageCount > 0) goTo(current) else pickPdf()
        }
    }

    private fun goTo(index: Int) {
        if (index < 0 || index >= pageCount) return
        current = index
        binding.pageLabel.text = "${index + 1} / $pageCount"
        binding.prevPage.isEnabled = index > 0
        binding.nextPage.isEnabled = index < pageCount - 1
        // a small, icon-sized selection the first time; keep it afterwards so the
        // same spot can be reused on other pages
        if (!rectPlaced) {
            binding.rectPick.resetTo(0.10f, 0.10f, 0.20f, 0.175f)
            rectPlaced = true
        }
        val r = renderer ?: return
        val targetW = (resources.displayMetrics.widthPixels * 1.7f).toInt().coerceAtMost(2400)
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching { r.render(index, targetW) }.getOrNull()
            }
            if (bmp != null && current == index) {
                binding.pageImage.setImageBitmap(bmp)
                binding.rectPick.post { binding.rectPick.invalidate() }
            }
        }
    }

    private fun run() {
        val src = source ?: return toast(getString(R.string.msg_pick_file_first))
        if (binding.modeManual.isChecked) {
            val url = binding.urlInput.text?.toString().orEmpty().trim()
            if (PdfEngine.normaliseUrl(url) == null) return toast(getString(R.string.msg_invalid_link))
            val size = sizes.getOrNull(current) ?: floatArrayOf(595f, 842f)
            val opacity = ((binding.opacityInput.text?.toString()?.toFloatOrNull() ?: 100f) / 100f)
                .coerceIn(0.05f, 1f)
            val rect = binding.rectPick.cropRect()
            val x = rect[0] * size[0]
            val y = rect[1] * size[1]
            val w = rect[2] * size[0]
            val h = rect[3] * size[1]
            if (w < 6f || h < 6f) return toast(getString(R.string.msg_area_too_small))
            if (binding.kindText.isChecked) {
                val body = binding.textInput.text?.toString().orEmpty().trim()
                if (body.isEmpty()) return toast(getString(R.string.msg_enter_link_text))
                val size = binding.sizeInput.text?.toString()?.toFloatOrNull() ?: 18f
                val fonts = PdfEngine.LINK_FONTS
                val font = fonts[binding.fontSpinner.selectedItemPosition.coerceIn(0, fonts.size - 1)]
                val all = binding.applyAll.isChecked
                runAndSave(Files.suggestName(sourceName, "_links")) { out ->
                    if (all) {
                        PdfEngine.addTextLinkAllPages(src, x, y, size, body, font,
                            chosenColour, url, opacity, out)
                    } else {
                        PdfEngine.addTextLink(src, current + 1, x, y, size, body, font,
                            chosenColour, url, opacity, out)
                    }
                }
                return
            }
            val side = minOf(w, h)
            val icon = Files.newFile(this, "link_icon.png")
            java.io.FileOutputStream(icon).use {
                makeIcon(chosenColour).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            val all = binding.applyAll.isChecked
            runAndSave(Files.suggestName(sourceName, "_links")) { out ->
                if (all) {
                    PdfEngine.addLinkIconAllPages(src, x, y, side, icon, url, opacity, out)
                } else {
                    PdfEngine.addLinkIcon(src, current + 1, x, y, side, icon, url, opacity, out)
                }
            }
            return
        }
        if (found.isEmpty()) return toast(getString(R.string.links_none))
        val n = found.size
        runAndSave(Files.suggestName(sourceName, "_links")) { out ->
            PdfEngine.addLinks(src, found, out)
            n
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (binding.modeManual.isChecked) goTo(current)
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer?.close()
        try { pfd?.close() } catch (_: Exception) { }
    }
}
