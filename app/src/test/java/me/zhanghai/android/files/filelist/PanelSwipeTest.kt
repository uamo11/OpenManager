/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelSwipeTest {

    @Test
    fun swipeLeftGoesToTheSecondPanelAndBack() {
        assertEquals(1, PanelSwipe.target(active = 0, dx = -200f, isRtl = false))
        assertEquals(0, PanelSwipe.target(active = 1, dx = 200f, isRtl = false))
        // Nothing further that way.
        assertNull(PanelSwipe.target(active = 0, dx = 200f, isRtl = false))
        assertNull(PanelSwipe.target(active = 1, dx = -200f, isRtl = false))
    }

    @Test
    fun rightToLeftLanguagesMirrorIt() {
        assertEquals(1, PanelSwipe.target(active = 0, dx = 200f, isRtl = true))
        assertEquals(0, PanelSwipe.target(active = 1, dx = -200f, isRtl = true))
    }

    @Test
    fun onlyClearlySidewaysMovesSwitch() {
        assertTrue(PanelSwipe.isSwipe(dx = -160f, dy = 20f, minDistance = 150f))
        assertFalse(PanelSwipe.isSwipe(dx = -100f, dy = 5f, minDistance = 150f))
        assertFalse(PanelSwipe.isSwipe(dx = -160f, dy = 90f, minDistance = 150f))
        assertTrue(PanelSwipe.isScroll(dx = 10f, dy = 80f, minDistance = 150f))
        assertFalse(PanelSwipe.isScroll(dx = 100f, dy = 80f, minDistance = 150f))
    }
}
