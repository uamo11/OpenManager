/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.pdf

import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.annotation.StringRes
import me.zhanghai.android.files.R

enum class PdfReadingTheme(@StringRes val titleRes: Int) {
    DAY(R.string.pdf_theme_day) {
        override val colorFilter: ColorFilter? = null
        override val backgroundColor: Int = Color.parseColor("#181818")
        override val pageContainerBackgroundColor: Int = Color.parseColor("#2C2C2C")
    },
    NIGHT(R.string.pdf_theme_night) {
        override val colorFilter: ColorFilter = ColorMatrixColorFilter(
            floatArrayOf(
                -1.0f,  0.0f,  0.0f, 0.0f, 255.0f,
                 0.0f, -1.0f,  0.0f, 0.0f, 255.0f,
                 0.0f,  0.0f, -1.0f, 0.0f, 255.0f,
                 0.0f,  0.0f,  0.0f, 1.0f,   0.0f
            )
        )
        override val backgroundColor: Int = Color.parseColor("#121212")
        override val pageContainerBackgroundColor: Int = Color.parseColor("#1E1E1E")
    },
    SEPIA(R.string.pdf_theme_sepia) {
        override val colorFilter: ColorFilter by lazy {
            val cm = ColorMatrix().apply {
                setSaturation(0.35f)
                val warmMatrix = ColorMatrix(
                    floatArrayOf(
                        1.02f, 0.0f, 0.0f, 0.0f, 20f,
                        0.0f, 0.95f, 0.0f, 0.0f, 10f,
                        0.0f, 0.0f, 0.80f, 0.0f, -20f,
                        0.0f, 0.0f, 0.0f, 1.0f, 0f
                    )
                )
                postConcat(warmMatrix)
            }
            ColorMatrixColorFilter(cm)
        }
        override val backgroundColor: Int = Color.parseColor("#231F1C")
        override val pageContainerBackgroundColor: Int = Color.parseColor("#332D28")
    };

    abstract val colorFilter: ColorFilter?
    abstract val backgroundColor: Int
    abstract val pageContainerBackgroundColor: Int
}
