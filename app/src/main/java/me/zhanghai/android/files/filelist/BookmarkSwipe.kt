/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

/** Which panel a swipe up on the bottom bar opens bookmarks for. Pure, for tests. */
object BookmarkSwipe {
    /**
     * With "position-aware" on and both panels on screen, the half of the bar where the swipe
     * started picks the panel (left half: left panel), so either side can jump without first
     * tapping it. Otherwise the active panel.
     */
    fun targetPanel(
        positionAware: Boolean,
        bothPanelsShown: Boolean,
        activePanel: Int,
        touchX: Float,
        screenWidth: Float,
        isRtl: Boolean = false
    ): Int {
        if (!positionAware || !bothPanelsShown || screenWidth <= 0f) return activePanel
        val startHalf = touchX < screenWidth / 2
        val first = if (isRtl) !startHalf else startHalf
        return if (first) 0 else 1
    }
}
