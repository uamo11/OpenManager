/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.annotation.AttrRes
import androidx.appcompat.widget.AppCompatImageView
import me.zhanghai.android.files.util.getFloatByAttr
import kotlin.math.roundToInt

/**
 * Same disabled-alpha behavior as [DisabledAlphaImageView], built on a plain [AppCompatImageView].
 *
 * File list rows never use a shape appearance or stroke, so the `ShapeableImageView` base used by
 * [DisabledAlphaImageView] only adds per-view overhead there: a shadow `MaterialShapeDrawable`, a
 * `ShadowRenderer` with four `Paint`s, two extra `Paint`s and two `Path`s, all backed by native
 * allocations. With ~5 icon views per row this was the largest per-row cost on the explorer
 * screen. Rendering is pixel-identical.
 */
class LiteDisabledAlphaImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {
    private var disabledAlpha = Float.NaN

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)

        updateImageAlpha()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()

        updateImageAlpha()
    }

    private fun updateImageAlpha() {
        var alpha = 0xFF
        val enabled = android.R.attr.state_enabled in drawableState
        if (!enabled) {
            if (disabledAlpha.isNaN()) {
                disabledAlpha = context.getFloatByAttr(android.R.attr.disabledAlpha)
            }
            alpha = (disabledAlpha * alpha).roundToInt()
        }
        if (imageAlpha != alpha) {
            imageAlpha = alpha
        }
    }
}
