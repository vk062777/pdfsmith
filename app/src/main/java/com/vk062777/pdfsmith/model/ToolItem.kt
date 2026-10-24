package com.vk062777.pdfsmith.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/** A single entry on the home screen. */
data class ToolItem(
    @StringRes val title: Int,
    @StringRes val desc: Int,
    @DrawableRes val icon: Int,
    val action: Action
)

enum class Action {
    VIEW, ANNOTATE, FORMS, CROP, PAGES, MERGE, SPLIT, IMAGES_PDF, CREATE_PDF,
    COMPRESS, WATERMARK, PROTECT, UNLOCK, METADATA, EXTRACT, PAGENUM, OCR,
    LINKS, IMAGE_OCR,
    SIGN, VERIFY
}
