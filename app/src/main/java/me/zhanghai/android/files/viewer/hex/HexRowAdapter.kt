/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.hex

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
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
        val row = binding.hexRow
        row.setTypeface(typeface)
        val holder = ViewHolder(binding)

        fun byteAt(e: MotionEvent): Long {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return -1
            val inRow = row.byteIndexAt(e.x)
            if (inRow < 0) return -1
            val target = pos.toLong() * bytesPerRow + inRow
            return if (target < buffer.size) target else -1
        }

        val detector = GestureDetector(parent.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val target = byteAt(e)
                if (target < 0) return false
                onByteTapped(target)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val target = byteAt(e)
                if (target >= 0) onByteLongPressed(target)
            }
        })
        row.setOnTouchListener { v, event ->
            detector.onTouchEvent(event).also { if (it && event.action == MotionEvent.ACTION_UP) v.performClick() }
        }
        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val rowOffset = position.toLong() * bytesPerRow
        val row = holder.binding.hexRow
        row.setTextSizeSp(textSizeSp)
        if (rowBytes.size < bytesPerRow) rowBytes = ByteArray(bytesPerRow)
        val count = buffer.read(rowOffset, rowBytes, 0, bytesPerRow)
        val range = highlightRange
        val selected = selectedOffset
        row.setRow(rowOffset, offsetDigits, bytesPerRow, rowBytes, count) { i ->
            val offset = rowOffset + i
            when {
                offset == selected -> HexRowView.MARK_SELECTED
                range != null && offset in range -> HexRowView.MARK_MATCH
                buffer.isModifiedAt(offset) -> HexRowView.MARK_MODIFIED
                else -> HexRowView.MARK_NONE
            }
        }
    }

    class ViewHolder(val binding: ItemHexRowBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        /** Characters of one row: offset, hex column (3 per byte plus the middle gap) and ASCII. */
        fun rowChars(bytesPerRow: Int, offsetDigits: Int): Int = offsetDigits + bytesPerRow * 3 + 1 + bytesPerRow
    }
}
