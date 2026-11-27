/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import me.zhanghai.android.files.filelist.SearchHistory.Entry
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchHistoryTest {

    @Test
    fun newestFirstWithoutRepeats() {
        var h = emptyList<Entry>()
        h = SearchHistory.added(h, "a")
        h = SearchHistory.added(h, "b")
        h = SearchHistory.added(h, "a")
        assertEquals(listOf("a", "b"), h.map { it.text })
    }

    @Test
    fun pinnedStayOnTopAndAreNeverDropped() {
        var h = listOf(Entry("keep"))
        h = SearchHistory.togglePinned(h, "keep")
        for (i in 1..30) h = SearchHistory.added(h, "q$i", maxRecent = 20)
        assertEquals(Entry("keep", pinned = true), h.first())
        assertEquals(21, h.size)
        assertEquals("q30", h[1].text)
        // Searching a pinned one again does not move or duplicate it.
        h = SearchHistory.added(h, "keep")
        assertEquals(1, h.count { it.text == "keep" })
        // Unpinned, it goes back among the recent ones.
        h = SearchHistory.togglePinned(h, "keep")
        assertEquals(Entry("keep"), h.first())
    }
}
