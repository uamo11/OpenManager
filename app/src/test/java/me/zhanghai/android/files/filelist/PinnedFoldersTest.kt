/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import org.junit.Assert.assertEquals
import org.junit.Test

class PinnedFoldersTest {

    private fun order(items: List<String>, pinned: Set<String>) =
        PinnedFolders.pinnedFirst(items, pinned, pathOf = { "/s/$it" }, nameOf = { it })

    @Test
    fun pinnedComeFirstByNameAndTheRestKeepsItsOrder() {
        val sortedByDate = listOf("Music", "zeta", "Alpha", "docs", "beta")
        assertEquals(
            listOf("beta", "zeta", "Music", "Alpha", "docs"),
            order(sortedByDate, setOf("/s/zeta", "/s/beta"))
        )
    }

    @Test
    fun nothingPinnedLeavesTheListAlone() {
        val list = listOf("b", "a")
        assertEquals(list, order(list, emptySet()))
        assertEquals(list, order(list, setOf("/elsewhere/a")))
    }
}
