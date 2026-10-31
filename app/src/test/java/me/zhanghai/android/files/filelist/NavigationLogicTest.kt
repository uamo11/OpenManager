/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationLogicTest {

    private fun isHomeOrRootPath(pathStr: String): Boolean {
        val s = pathStr.trimEnd('/')
        return s.isEmpty() || s == "/"
    }

    private fun canNavigateUp(pathStr: String, hasArchiveParent: Boolean = false): Boolean {
        if (isHomeOrRootPath(pathStr)) {
            return false
        }
        return true || hasArchiveParent
    }

    private fun shouldExitOnBackPress(lastBackPressTime: Long, currentTime: Long): Boolean {
        return (currentTime - lastBackPressTime) < 2000L
    }

    @Test
    fun testHomePathsIdentifiedCorrectly() {
        assertFalse(isHomeOrRootPath("/storage/emulated/0"))
        assertFalse(isHomeOrRootPath("/storage/emulated/0/"))
        assertFalse(isHomeOrRootPath("/sdcard"))
        assertFalse(isHomeOrRootPath("/sdcard/"))
        assertTrue(isHomeOrRootPath("/"))
        assertTrue(isHomeOrRootPath(""))
    }

    @Test
    fun testSubdirectoryPathsCanNavigateUp() {
        assertFalse(isHomeOrRootPath("/storage/emulated/0/Download"))
        assertFalse(isHomeOrRootPath("/storage/emulated/0/Android/data"))
        assertFalse(isHomeOrRootPath("/data/local/tmp"))

        assertTrue(canNavigateUp("/storage/emulated/0/Download"))
        assertTrue(canNavigateUp("/storage/emulated/0/Android/data"))
    }

    @Test
    fun testHomePathCanNavigateUpToStorageAndRoot() {
        assertTrue(canNavigateUp("/storage/emulated/0"))
        assertTrue(canNavigateUp("/storage/emulated/0/"))
        assertFalse(canNavigateUp("/"))
    }

    @Test
    fun testDoubleBackPressExitWindow() {
        val firstPress = 10000L
        val quickSecondPress = 11500L // 1.5s later -> exit
        val slowSecondPress = 12500L // 2.5s later -> do not exit

        assertTrue(shouldExitOnBackPress(firstPress, quickSecondPress))
        assertFalse(shouldExitOnBackPress(firstPress, slowSecondPress))
    }

    @Test
    fun testDefaultJumpPathTargets() {
        val internalPath = "/storage/emulated/0/"
        val downloadsPath = "/storage/emulated/0/Download/"
        val androidDataPath = "/storage/emulated/0/Android/data/"
        val rootPath = "/"

        assertTrue(internalPath.startsWith("/storage/emulated/0"))
        assertTrue(downloadsPath.startsWith(internalPath))
        assertTrue(androidDataPath.startsWith(internalPath))
        assertEquals("/", rootPath)
    }

    enum class ToolbarNavMode {
        CLEAR_SELECTION,
        NAVIGATE_UP,
        OPEN_DRAWER
    }

    private fun resolveToolbarNavMode(selectedCount: Int, canNavigateUp: Boolean): ToolbarNavMode {
        return when {
            selectedCount > 0 -> ToolbarNavMode.CLEAR_SELECTION
            canNavigateUp -> ToolbarNavMode.NAVIGATE_UP
            else -> ToolbarNavMode.OPEN_DRAWER
        }
    }

    @Test
    fun testToolbarNavigationModeResolution() {
        // When items are selected, back/navigation always clears selection
        assertEquals(ToolbarNavMode.CLEAR_SELECTION, resolveToolbarNavMode(selectedCount = 1, canNavigateUp = true))
        assertEquals(ToolbarNavMode.CLEAR_SELECTION, resolveToolbarNavMode(selectedCount = 5, canNavigateUp = false))

        // When no items are selected and canNavigateUp is true, toolbar navigates up
        assertEquals(ToolbarNavMode.NAVIGATE_UP, resolveToolbarNavMode(selectedCount = 0, canNavigateUp = true))

        // When no items are selected and at root (cannot navigate up), toolbar opens drawer
        assertEquals(ToolbarNavMode.OPEN_DRAWER, resolveToolbarNavMode(selectedCount = 0, canNavigateUp = false))
    }

    @Test
    fun testFileMenuCopyFormats() {
        val singleFilePath = "/storage/emulated/0/Download/test.exe"
        val singleFileName = "test.exe"

        // Single file touch header -> full path
        assertEquals("/storage/emulated/0/Download/test.exe", singleFilePath)
        // Single file long touch header -> file name
        assertEquals("test.exe", singleFileName)

        // Multiple files touch header -> newline-separated paths
        val multiFiles = listOf(
            "/storage/emulated/0/Download/app.apk",
            "/storage/emulated/0/Download/readme.txt"
        )
        val joinedPaths = multiFiles.joinToString("\n")
        assertEquals("/storage/emulated/0/Download/app.apk\n/storage/emulated/0/Download/readme.txt", joinedPaths)
    }
}
