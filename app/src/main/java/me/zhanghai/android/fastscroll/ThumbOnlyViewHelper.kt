/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.fastscroll

import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView

/**
 * The fast scroller only follows a finger that lands on its visible thumb.
 *
 * The library also jumps the list when a vertical swipe starts anywhere in the 48 dp strip of the
 * track, and with two panels the left panel's strip is the middle of the screen, right where a
 * thumb scrolls one-handed: lists jumped by accident (user report). Gestures that do not start on
 * the shown thumb never reach the scroller, so they scroll the list as usual.
 */
class ThumbOnlyViewHelper private constructor(
    private val delegate: RecyclerViewHelper
) : FastScroller.ViewHelper by delegate {

    constructor(view: RecyclerView) : this(RecyclerViewHelper(view, null))

    override fun addOnTouchEventListener(onTouchEvent: Predicate<MotionEvent>) {
        var gestureOnThumb = false
        delegate.addOnTouchEventListener { event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // The scroller takes the gesture at once only when the finger is on the shown thumb.
                    gestureOnThumb = onTouchEvent.test(event)
                    gestureOnThumb
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val handled = gestureOnThumb && onTouchEvent.test(event)
                    gestureOnThumb = false
                    handled
                }
                else -> gestureOnThumb && onTouchEvent.test(event)
            }
        }
    }
}
