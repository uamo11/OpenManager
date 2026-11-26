/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

/**
 * The checkerboard image editors draw behind transparent pixels, so a transparent PNG does not
 * look like a black (or white) one. [square] is in the drawable's own units, so it follows zoom.
 */
class CheckerboardDrawable(
    private val width: Int,
    private val height: Int,
    private val square: Float
) : Drawable() {

    override fun draw(canvas: Canvas) = drawChecker(canvas, bounds, square)

    override fun getIntrinsicWidth(): Int = width

    override fun getIntrinsicHeight(): Int = height

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    companion object {
        private val light = Paint().apply { color = Color.WHITE }
        private val dark = Paint().apply { color = Color.rgb(0xD6, 0xD6, 0xD6) }

        /** Fills [rect] with squares of [square] pixels. */
        fun drawChecker(canvas: Canvas, rect: Rect, square: Float) {
            if (rect.isEmpty || square <= 0f) return
            canvas.save()
            canvas.clipRect(rect)
            canvas.drawRect(rect, light)
            var row = 0
            var y = rect.top.toFloat()
            while (y < rect.bottom) {
                var x = rect.left + if (row % 2 == 0) square else 0f
                while (x < rect.right) {
                    canvas.drawRect(x, y, x + square, y + square, dark)
                    x += square * 2
                }
                y += square
                row++
            }
            canvas.restore()
        }
    }
}
