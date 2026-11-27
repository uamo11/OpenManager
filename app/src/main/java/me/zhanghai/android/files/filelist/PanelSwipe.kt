/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import kotlin.math.abs

/**
 * One-panel mode (user request, as X-plore/MT on small screens): a sideways swipe on the list goes
 * to the other panel. Panel 0 is on the left (on the right in right-to-left languages).
 */
object PanelSwipe {

    /** The panel a swipe of [dx] goes to from [active], or null when there is none that way. */
    fun target(active: Int, dx: Float, isRtl: Boolean): Int? {
        val towardsSecond = if (isRtl) dx > 0 else dx < 0
        return when {
            towardsSecond && active == 0 -> 1
            !towardsSecond && active == 1 -> 0
            else -> null
        }
    }

    /** Clearly sideways and long enough to be a swipe, not a scroll or a tap. */
    fun isSwipe(dx: Float, dy: Float, minDistance: Float): Boolean =
        abs(dx) >= minDistance && abs(dx) >= 2.5f * abs(dy)

    /** Mostly vertical: the list scrolls and the swipe is off for this touch. */
    fun isScroll(dx: Float, dy: Float, minDistance: Float): Boolean =
        abs(dy) >= minDistance / 2 && abs(dy) > abs(dx)
}
