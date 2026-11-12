/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.docx

import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import me.zhanghai.android.files.R

enum class DocxReadingTheme(
    val cssClass: String,
    @ColorInt val backgroundColor: Int,
    @ColorInt val contentBackgroundColor: Int,
    @ColorInt val textColor: Int,
    @StringRes val titleRes: Int
) {
    DAY(
        cssClass = "theme-day",
        backgroundColor = 0xFFECEFF1.toInt(),
        contentBackgroundColor = 0xFFFFFFFF.toInt(),
        textColor = 0xFF212121.toInt(),
        titleRes = R.string.docx_theme_day
    ),
    NIGHT(
        cssClass = "theme-night",
        backgroundColor = 0xFF121212.toInt(),
        contentBackgroundColor = 0xFF1E1E1E.toInt(),
        textColor = 0xFFE0E0E0.toInt(),
        titleRes = R.string.docx_theme_night
    ),
    SEPIA(
        cssClass = "theme-sepia",
        backgroundColor = 0xFFEFE4D0.toInt(),
        contentBackgroundColor = 0xFFFBF0D9.toInt(),
        textColor = 0xFF4F3824.toInt(),
        titleRes = R.string.docx_theme_sepia
    )
}
