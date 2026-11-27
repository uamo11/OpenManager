/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Selection by swiping across a file's icon: a sideways swipe that starts on an icon selects that
 * file, and keeping the finger down while moving up or down selects every file it passes (the
 * list scrolls by itself near its edges). A vertical move that starts on an icon is a normal
 * scroll, and a plain tap on the icon opens the file.
 */
class IconSwipeSelectHelper(
    private val adapter: () -> FileListAdapter?
) : RecyclerView.OnItemTouchListener {

    private var startX = 0f
    private var startY = 0f
    private var candidate = false
    private var strict = false
    private var active = false
    private var anchorPosition = RecyclerView.NO_POSITION
    private var lastPosition = RecyclerView.NO_POSITION
    private var touchSlop = -1

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        if (touchSlop < 0) touchSlop = ViewConfiguration.get(rv.context).scaledTouchSlop
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = false
                startX = e.x
                startY = e.y
                val child = rv.findChildViewUnder(e.x, e.y)
                val holder = child?.let { rv.getChildViewHolder(it) }
                // A touch on a list that is still moving only stops it: never a selection.
                val listMoving = rv.scrollState != RecyclerView.SCROLL_STATE_IDLE
                candidate = !listMoving && holder != null &&
                    adapter()?.isOnIcon(rv, holder, e.x, e.y) == true
                strict = candidate && adapter()?.isGalleryTile(holder!!) == true
                anchorPosition = holder?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
            }
            MotionEvent.ACTION_MOVE -> {
                if (!candidate || active) return active
                val dx = abs(e.x - startX)
                val dy = abs(e.y - startY)
                // Gallery tiles fill the screen, so a slightly slanted scroll starts on one:
                // there the swipe must be longer and mostly sideways.
                val slop = if (strict) touchSlop * 3 else touchSlop
                val ratio = if (strict) 2f else 1f
                if (dy > touchSlop && dy * ratio >= dx) {
                    candidate = false // a scroll
                } else if (dx > slop && dx > dy * ratio) {
                    if (adapter()?.selectAt(anchorPosition) == true) {
                        active = true
                        lastPosition = anchorPosition
                        rv.parent?.requestDisallowInterceptTouchEvent(true)
                        me.zhanghai.android.files.util.Haptics.longPress(rv)
                        return true
                    }
                    candidate = false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                candidate = false
                active = false
            }
        }
        return active
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!active) return
                // Near the top or bottom edge the list scrolls on by itself.
                val edge = rv.height / 10
                when {
                    e.y < edge -> rv.scrollBy(0, -(edge - e.y.toInt()).coerceAtMost(40))
                    e.y > rv.height - edge -> rv.scrollBy(0, (e.y.toInt() - (rv.height - edge)).coerceAtMost(40))
                }
                val y = e.y.coerceIn(0f, rv.height - 1f)
                val child = rv.findChildViewUnder(rv.width / 2f, y) ?: return
                val position = rv.getChildViewHolder(child).bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION || position == lastPosition) return
                val adapter = adapter() ?: return
                for (p in min(lastPosition, position)..max(lastPosition, position)) {
                    adapter.selectAt(p)
                }
                lastPosition = position
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = false
                candidate = false
            }
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (disallowIntercept && !active) candidate = false
    }
}
