/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticsTest {

    @Test
    fun aFinishedTaskBuzzesOnceAndAFailedOneTwice() {
        val ok = Haptics.jobFinishedPattern(failed = false)
        val failed = Haptics.jobFinishedPattern(failed = true)
        // Waveform: [wait, on, wait, on, ...]; the "on" parts are the odd indices.
        assertEquals(1, ok.indices.count { it % 2 == 1 })
        assertEquals(2, failed.indices.count { it % 2 == 1 })
        assertArrayEquals(longArrayOf(0, 45), ok)
        // Short enough not to annoy: under a quarter of a second in all.
        assertTrue(failed.sum() < 250)
    }
}
