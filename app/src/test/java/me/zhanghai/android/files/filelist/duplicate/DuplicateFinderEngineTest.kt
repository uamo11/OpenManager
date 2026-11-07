/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist.duplicate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DuplicateFinderEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testDuplicateDetection() = runBlocking {
        val root = tempFolder.newFolder("scan_root")
        val subDir = File(root, "sub").apply { mkdirs() }

        val file1 = File(root, "original.txt").apply { writeText("Hello Duplicate Finder Test 1234567890!") }
        val file2 = File(subDir, "copy.txt").apply { writeText("Hello Duplicate Finder Test 1234567890!") }
        val file3 = File(root, "unique.txt").apply { writeText("Completely distinct content right here.") }

        val result = DuplicateFinderEngine.scanDuplicates(
            rootDir = root,
            recursive = true,
            minSizeBytes = 5
        )

        assertEquals(1, result.groups.size)
        val group = result.groups[0]
        assertEquals(2, group.files.size)
        assertEquals(1, result.totalDuplicateFilesCount)
        assertEquals(file1.length(), result.totalWastedBytes)

        val paths = group.files.map { it.file.name }
        assertTrue(paths.contains("original.txt"))
        assertTrue(paths.contains("copy.txt"))
        assertFalse(paths.contains("unique.txt"))
    }

    @Test
    fun testSelectionStrategies() {
        val f1 = File("mock1.txt")
        val f2 = File("mock2.txt")
        val item1 = DuplicateFileItem(f1, 100L, lastModified = 1000L, path = "/path/1")
        val item2 = DuplicateFileItem(f2, 100L, lastModified = 2000L, path = "/path/2")

        val group = DuplicateGroup("hash123", 100L, mutableListOf(item1, item2))
        val groups = listOf(group)

        // Select Oldest Duplicates: Keep oldest (f1), mark newer (f2)
        DuplicateFinderEngine.selectOldestDuplicates(groups)
        assertFalse(item1.isMarkedForDeletion)
        assertTrue(item2.isMarkedForDeletion)

        // Select Newest Duplicates: Keep newest (f2), mark older (f1)
        DuplicateFinderEngine.selectNewestDuplicates(groups)
        assertTrue(item1.isMarkedForDeletion)
        assertFalse(item2.isMarkedForDeletion)

        // Clear all selections
        DuplicateFinderEngine.clearAllSelections(groups)
        assertFalse(item1.isMarkedForDeletion)
        assertFalse(item2.isMarkedForDeletion)

        // Select All Duplicates: 1 copy kept, 1 marked
        DuplicateFinderEngine.selectAllDuplicates(groups)
        assertFalse(item1.isMarkedForDeletion)
        assertTrue(item2.isMarkedForDeletion)
    }

    @Test
    fun testFormatBytes() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("500 B", formatBytes(500))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.0 MB", formatBytes(1024 * 1024))
    }
}
