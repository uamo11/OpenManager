/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.hex

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * One HEX row drawn straight on the canvas: offset, hex bytes, divider and ASCII, with the same
 * geometry as the three TextViews it replaces (so the column titles still line up). A new row only
 * redraws: no text layout, no spans and no measuring pass (user report: scrolling was laggy).
 */
class HexRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val padStart = 8 * density
    private val padEnd = 12 * density
    private val padVertical = 1.5f * density
    private val gap = 8 * density
    private val dividerWidth = 1 * density

    private val offsetPaint = textPaint(OFFSET_FG)
    private val hexPaint = textPaint(HEX_FG)
    private val asciiPaint = textPaint(ASCII_FG)
    private val markTextPaint = textPaint(Color.WHITE)
    private val modifiedPaint = textPaint(MODIFIED_FG)
    private val fillPaint = Paint()

    private var charWidth = 0f
    private var lineHeight = 0
    private var baseline = 0f

    private var offsetDigits = 8
    private var bytesPerRow = 16
    private var count = 0
    private val offsetChars = CharArray(16)
    private val hexChars = CharArray(16 * 3 + 1)
    private val asciiChars = CharArray(16)
    private val marks = ByteArray(16)

    init {
        setTextSizeSp(11.5f)
    }

    private fun textPaint(color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        this.color = color
        typeface = Typeface.MONOSPACE
    }

    private val textPaints get() = listOf(offsetPaint, hexPaint, asciiPaint, markTextPaint, modifiedPaint)

    fun setTypeface(typeface: Typeface) {
        textPaints.forEach { it.typeface = typeface }
        updateMetrics()
    }

    fun setTextSizeSp(sp: Float) {
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        if (offsetPaint.textSize == px) return
        textPaints.forEach { it.textSize = px }
        updateMetrics()
    }

    private fun updateMetrics() {
        charWidth = offsetPaint.measureText("0")
        val fm = offsetPaint.fontMetricsInt
        lineHeight = fm.bottom - fm.top
        baseline = padVertical - fm.top
        requestLayout()
        invalidate()
    }

    private val hexLength get() = bytesPerRow * 3 + 1
    private val hexStart get() = padStart + offsetDigits * charWidth + gap
    private val dividerStart get() = hexStart + hexLength * charWidth + gap
    private val asciiStart get() = dividerStart + dividerWidth + gap

    /**
     * Shows [count] bytes of [bytes] at [rowOffset]; [markOf] gives each byte's look
     * ([MARK_NONE], [MARK_SELECTED], [MARK_MATCH] or [MARK_MODIFIED]).
     */
    fun setRow(
        rowOffset: Long,
        offsetDigits: Int,
        bytesPerRow: Int,
        bytes: ByteArray,
        count: Int,
        markOf: (index: Int) -> Byte
    ) {
        val geometryChanged = offsetDigits != this.offsetDigits || bytesPerRow != this.bytesPerRow
        this.offsetDigits = offsetDigits
        this.bytesPerRow = bytesPerRow
        this.count = count
        var value = rowOffset
        for (i in offsetDigits - 1 downTo 0) {
            offsetChars[i] = HEX[(value and 0xF).toInt()]
            value = value ushr 4
        }
        val half = bytesPerRow / 2
        var h = 0
        for (i in 0 until bytesPerRow) {
            if (i < count) {
                val v = bytes[i].toInt() and 0xFF
                hexChars[h] = HEX[v ushr 4]
                hexChars[h + 1] = HEX[v and 0xF]
                asciiChars[i] = if (v in 32..126) v.toChar() else '·'
                marks[i] = markOf(i)
            } else {
                hexChars[h] = ' '
                hexChars[h + 1] = ' '
                asciiChars[i] = ' '
                marks[i] = MARK_NONE
            }
            hexChars[h + 2] = ' '
            h += 3
            if (i == half - 1) hexChars[h++] = ' '
        }
        if (geometryChanged) requestLayout()
        invalidate()
    }

    /** Text of the row, for accessibility and the screen checks. */
    fun rowText(): String = buildString {
        append(offsetChars, 0, offsetDigits).append("  ")
        append(hexChars, 0, hexLength).append("| ")
        append(asciiChars, 0, bytesPerRow)
    }

    /** Index in the row of the byte under [x] in the hex or ASCII column, or -1. */
    fun byteIndexAt(x: Float): Int {
        if (charWidth <= 0f) return -1
        val inRow = when {
            x >= hexStart && x < dividerStart -> {
                val charPos = ((x - hexStart) / charWidth).toInt()
                val half = bytesPerRow / 2
                if (charPos < half * 3) charPos / 3 else half + (charPos - half * 3 - 1).coerceAtLeast(0) / 3
            }
            x >= asciiStart -> ((x - asciiStart) / charWidth).toInt()
            else -> return -1
        }
        return if (inRow in 0 until bytesPerRow) inRow else -1
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = asciiStart + bytesPerRow * charWidth + padEnd
        val height = lineHeight + 2 * padVertical
        setMeasuredDimension(kotlin.math.ceil(width).toInt(), kotlin.math.ceil(height).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawText(offsetChars, 0, offsetDigits, padStart, baseline, offsetPaint)
        val top = padVertical
        val bottom = top + lineHeight
        val half = bytesPerRow / 2
        // Backgrounds of the selected byte and of search matches, under the text.
        for (i in 0 until count) {
            val mark = marks[i]
            if (mark != MARK_SELECTED && mark != MARK_MATCH) continue
            fillPaint.color = if (mark == MARK_SELECTED) SELECTED_BG else MATCH_BG
            val hx = hexStart + hexColumn(i, half) * charWidth
            canvas.drawRect(hx, top, hx + 2 * charWidth, bottom, fillPaint)
            val ax = asciiStart + i * charWidth
            canvas.drawRect(ax, top, ax + charWidth, bottom, fillPaint)
        }
        canvas.drawText(hexChars, 0, hexLength, hexStart, baseline, hexPaint)
        canvas.drawText(asciiChars, 0, bytesPerRow, asciiStart, baseline, asciiPaint)
        // Marked bytes drawn again in their colour.
        for (i in 0 until count) {
            val paint = when (marks[i]) {
                MARK_SELECTED, MARK_MATCH -> markTextPaint
                MARK_MODIFIED -> modifiedPaint
                else -> continue
            }
            canvas.drawText(hexChars, hexColumn(i, half), 2, hexStart + hexColumn(i, half) * charWidth, baseline, paint)
            canvas.drawText(asciiChars, i, 1, asciiStart + i * charWidth, baseline, paint)
        }
        fillPaint.color = DIVIDER
        canvas.drawRect(dividerStart, 0f, dividerStart + dividerWidth, height.toFloat(), fillPaint)
    }

    private fun hexColumn(index: Int, half: Int): Int = index * 3 + if (index >= half) 1 else 0

    companion object {
        const val MARK_NONE: Byte = 0
        const val MARK_SELECTED: Byte = 1
        const val MARK_MATCH: Byte = 2
        const val MARK_MODIFIED: Byte = 3

        private val HEX = "0123456789ABCDEF".toCharArray()
        private val OFFSET_FG = Color.parseColor("#757575")
        private val HEX_FG = Color.parseColor("#CCCCCC")
        private val ASCII_FG = Color.parseColor("#E0E0E0")
        private val DIVIDER = Color.parseColor("#383838")
        private val SELECTED_BG = Color.parseColor("#1976D2")
        private val MATCH_BG = Color.parseColor("#388E3C")
        private val MODIFIED_FG = Color.parseColor("#FFA726")
    }
}
