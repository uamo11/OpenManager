/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.hex

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import me.zhanghai.android.files.databinding.ItemHexRowBinding

/**
 * One row per [bytesPerRow] bytes (16, or 8 when 16 do not fit the screen). Rows read their bytes
 * from the [HexBuffer] when they are bound, so only the rows on screen touch the file.
 */
class HexRowAdapter(
    private var buffer: HexBuffer,
    private val typeface: Typeface,
    private val onByteTapped: (offset: Long) -> Unit,
    private val onByteLongPressed: (offset: Long) -> Unit
) : RecyclerView.Adapter<HexRowAdapter.ViewHolder>() {

    var bytesPerRow: Int = 16
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    /** Text size of the rows, grown to fill the screen width. */
    var textSizeSp: Float = 11.5f
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    /** Hex digits of the offset column: 8, or more for files past 4 GB. */
    val offsetDigits: Int
        get() = if (buffer.size > 0xFFFFFFFFL) 10 else 8

    var selectedOffset: Long = 0
        set(value) {
            val old = field
            field = value
            if (itemCount == 0) return
            val oldRow = rowOf(old)
            val newRow = rowOf(value)
            notifyItemChanged(oldRow)
            if (newRow != oldRow) notifyItemChanged(newRow)
        }

    var highlightRange: LongRange? = null
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    private var rowBytes = ByteArray(16)

    @SuppressLint("NotifyDataSetChanged")
    fun updateBuffer(newBuffer: HexBuffer) {
        this.buffer = newBuffer
        notifyDataSetChanged()
    }

    fun rowOf(offset: Long): Int = (offset / bytesPerRow).coerceIn(0, maxOf(itemCount - 1, 0).toLong()).toInt()

    override fun getItemCount(): Int =
        ((buffer.size + bytesPerRow - 1) / bytesPerRow).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHexRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.tvOffset.typeface = typeface
        binding.tvHex.typeface = typeface
        binding.tvAscii.typeface = typeface
        val holder = ViewHolder(binding)

        fun byteAt(view: TextView, e: MotionEvent, isHex: Boolean): Long {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return -1
            val x = e.x - view.paddingLeft
            if (x < 0) return -1
            val charWidth = view.paint.measureText("0")
            val charPos = (x / charWidth).toInt()
            val inRow = if (isHex) {
                // "XX " per byte, with one more space after the first half of the row.
                val half = bytesPerRow / 2
                if (charPos < half * 3) charPos / 3 else (half + (charPos - half * 3 - 1).coerceAtLeast(0) / 3)
            } else {
                charPos
            }.coerceIn(0, bytesPerRow - 1)
            val target = pos.toLong() * bytesPerRow + inRow
            return if (target < buffer.size) target else -1
        }

        fun detector(view: TextView, isHex: Boolean) = GestureDetector(parent.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val target = byteAt(view, e, isHex)
                if (target < 0) return false
                onByteTapped(target)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val target = byteAt(view, e, isHex)
                if (target >= 0) onByteLongPressed(target)
            }
        })

        val hexDetector = detector(binding.tvHex, isHex = true)
        val asciiDetector = detector(binding.tvAscii, isHex = false)
        binding.tvHex.setOnTouchListener { v, event ->
            hexDetector.onTouchEvent(event).also { if (it && event.action == MotionEvent.ACTION_UP) v.performClick() }
        }
        binding.tvAscii.setOnTouchListener { v, event ->
            asciiDetector.onTouchEvent(event).also { if (it && event.action == MotionEvent.ACTION_UP) v.performClick() }
        }
        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val rowOffset = position.toLong() * bytesPerRow
        val b = holder.binding
        if (b.tvHex.tag != textSizeSp) {
            for (view in listOf(b.tvOffset, b.tvHex, b.tvAscii)) {
                view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            }
            b.tvHex.tag = textSizeSp
        }
        b.tvOffset.text = String.format("%0${offsetDigits}X", rowOffset)

        if (rowBytes.size < bytesPerRow) rowBytes = ByteArray(bytesPerRow)
        val count = buffer.read(rowOffset, rowBytes, 0, bytesPerRow)
        val half = bytesPerRow / 2

        val hexSb = SpannableStringBuilder()
        val asciiSb = SpannableStringBuilder()
        for (i in 0 until bytesPerRow) {
            val offset = rowOffset + i
            if (i < count) {
                val v = rowBytes[i].toInt() and 0xFF
                val start = hexSb.length
                hexSb.append(HEX[v ushr 4]).append(HEX[v and 0xF])
                applySpans(hexSb, start, hexSb.length, offset)
                val aStart = asciiSb.length
                asciiSb.append(if (v in 32..126) v.toChar() else '·')
                applySpans(asciiSb, aStart, asciiSb.length, offset)
                hexSb.append(' ')
            } else {
                hexSb.append("   ")
                asciiSb.append(' ')
            }
            if (i == half - 1) hexSb.append(' ')
        }
        b.tvHex.text = hexSb
        b.tvAscii.text = asciiSb
    }

    private fun applySpans(sb: SpannableStringBuilder, start: Int, end: Int, offset: Long) {
        val range = highlightRange
        when {
            offset == selectedOffset -> {
                sb.setSpan(BackgroundColorSpan(SELECTED_BG), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(Color.WHITE), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            range != null && offset in range -> {
                sb.setSpan(BackgroundColorSpan(MATCH_BG), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(Color.WHITE), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            buffer.isModifiedAt(offset) ->
                sb.setSpan(ForegroundColorSpan(MODIFIED_FG), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    class ViewHolder(val binding: ItemHexRowBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private val HEX = "0123456789ABCDEF".toCharArray()
        private val SELECTED_BG = Color.parseColor("#1976D2")
        private val MATCH_BG = Color.parseColor("#388E3C")
        private val MODIFIED_FG = Color.parseColor("#FFA726")

        /** Characters of one row: offset, hex column (3 per byte plus the middle gap) and ASCII. */
        fun rowChars(bytesPerRow: Int, offsetDigits: Int): Int = offsetDigits + bytesPerRow * 3 + 1 + bytesPerRow
    }
}
