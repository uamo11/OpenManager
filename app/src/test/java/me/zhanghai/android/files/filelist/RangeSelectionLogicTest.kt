/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeSelectionLogicTest {

    data class MockFile(val name: String, val isParent: Boolean = false, val selectable: Boolean = true)

    private fun computeRangeSelection(
        items: List<MockFile>,
        anchor: Int,
        target: Int
    ): List<Int> {
        val start = minOf(anchor, target).coerceAtLeast(0)
        val end = maxOf(anchor, target).coerceAtMost(items.size - 1)
        val selectedIndices = mutableListOf<Int>()
        for (i in start..end) {
            val item = items[i]
            if (!item.isParent && item.selectable) {
                selectedIndices.add(i)
            }
        }
        return selectedIndices
    }

    private fun toggleSelectAll(
        totalSelectable: Int,
        currentSelectedCount: Int
    ): Boolean {
        // Returns true if new state should be "select all", false if "clear selection"
        return !(currentSelectedCount >= totalSelectable && totalSelectable > 0)
    }

    @Test
    fun testRangeSelectionDownwards() {
        // Files: [.., file1, file2, file3, file4, file5]
        val items = listOf(
            MockFile("..", isParent = true),
            MockFile("file1.txt"),
            MockFile("file2.txt"),
            MockFile("file3.txt"),
            MockFile("file4.txt"),
            MockFile("file5.txt")
        )
        // User selected file1 (index 1), then swiped file4 (index 4) downwards
        val selected = computeRangeSelection(items, 1, 4)
        assertEquals(listOf(1, 2, 3, 4), selected)
        assertEquals(4, selected.size)
    }

    @Test
    fun testRangeSelectionUpwards() {
        val items = listOf(
            MockFile("..", isParent = true),
            MockFile("file1.txt"),
            MockFile("file2.txt"),
            MockFile("file3.txt"),
            MockFile("file4.txt"),
            MockFile("file5.txt")
        )
        // User selected file5 (index 5), then swiped file2 (index 2) upwards
        val selected = computeRangeSelection(items, 5, 2)
        assertEquals(listOf(2, 3, 4, 5), selected)
        assertEquals(4, selected.size)
    }

    @Test
    fun testParentDirectoryIsNeverSelected() {
        val items = listOf(
            MockFile("..", isParent = true),
            MockFile("file1.txt"),
            MockFile("file2.txt")
        )
        // Swiping across parent directory index 0
        val selected = computeRangeSelection(items, 0, 2)
        assertFalse(selected.contains(0))
        assertEquals(listOf(1, 2), selected)
    }

    @Test
    fun testToggleSelectAllLogic() {
        val totalSelectable = 10
        // When 0 selected: clicking Todo selects all
        assertTrue(toggleSelectAll(totalSelectable, 0))
        // When 5 selected: clicking Todo selects all
        assertTrue(toggleSelectAll(totalSelectable, 5))
        // When all 10 selected: clicking Todo deselects all
        assertFalse(toggleSelectAll(totalSelectable, 10))
    }

    @Test
    fun testSingleItemSwipeToggle() {
        val selectedSet = mutableSetOf<String>()
        val item = "test.apk"

        // First swipe selects
        if (selectedSet.isEmpty()) {
            selectedSet.add(item)
        }
        assertTrue(selectedSet.contains(item))

        // Second swipe on lone item deselects
        if (selectedSet.contains(item) && selectedSet.size == 1) {
            selectedSet.remove(item)
        }
        assertTrue(selectedSet.isEmpty())
    }
}
