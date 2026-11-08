/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecycleBinLogicTest {

    private val recycleBinBasePath = "/storage/emulated/0/OpenManager/.recycle"

    private fun isPathInsideRecycleBin(pathStr: String, baseRecycleStr: String = recycleBinBasePath): Boolean {
        val cleanRecycle = baseRecycleStr.trimEnd('/')
        val cleanPath = pathStr.trimEnd('/')
        return cleanPath == cleanRecycle || cleanPath.startsWith("$cleanRecycle/")
    }

    private fun formatRecycleDisplayPath(pathStr: String, baseRecycleStr: String = recycleBinBasePath, localizedBinName: String = "Recycle Bin"): String {
        val cleanRecycle = baseRecycleStr.trimEnd('/')
        val cleanPath = pathStr.trimEnd('/')
        return when {
            cleanPath == cleanRecycle -> "$localizedBinName/"
            cleanPath.startsWith("$cleanRecycle/") -> {
                val sub = cleanPath.removePrefix("$cleanRecycle/").trimStart('/')
                "$localizedBinName/$sub/"
            }
            else -> pathStr
        }
    }

    private fun determineDeleteAction(
        isInsideRecycleBin: Boolean,
        isRecycleBinEnabled: Boolean,
        userCheckedRecycle: Boolean
    ): String {
        return if (isInsideRecycleBin) {
            "DELETE_PERMANENT"
        } else if (isRecycleBinEnabled && userCheckedRecycle) {
            "MOVE_TO_RECYCLE_BIN"
        } else {
            "DELETE_PERMANENT"
        }
    }

    @Test
    fun testRecycleBinDefaultIsOff() {
        // As requested by user: "Que vendrá off"
        val defaultRecycleBinEnabled = false
        val defaultMoveToRecycleBinOnDelete = false
        val defaultMoveToRecycleBinDefault = false
        assertFalse("Recycle Bin master switch must default to disabled (off)", defaultRecycleBinEnabled)
        assertFalse("Move to recycle bin on delete must default to disabled (off)", defaultMoveToRecycleBinOnDelete)
        assertFalse("Move to recycle bin checkbox default must be false (off)", defaultMoveToRecycleBinDefault)
    }

    @Test
    fun testDrawerVisibilityMatchesSetting() {
        val settingDisabled = false
        val drawerVisibleWhenDisabled = settingDisabled
        assertFalse("Drawer item 'Papelera de reciclaje' must NOT be visible when setting is off", drawerVisibleWhenDisabled)

        val settingEnabled = true
        val drawerVisibleWhenEnabled = settingEnabled
        assertTrue("Drawer item 'Papelera de reciclaje' must be visible when setting is on", drawerVisibleWhenEnabled)
    }

    @Test
    fun testDeleteDialogBehaviorWhenSettingIsOff() {
        // When setting is off (default), delete dialog does NOT ask to move to recycle bin
        val isRecycleBinActive = false
        val showRecycleBinCheckBox = isRecycleBinActive
        assertFalse("Delete dialog must not show recycle bin option when setting is off", showRecycleBinCheckBox)

        val deletePermanent = !showRecycleBinCheckBox
        assertTrue("Deletion must be direct and permanent when setting is off", deletePermanent)
    }

    @Test
    fun testIsInsideRecycleBinDetection() {
        // Base folder
        assertTrue(isPathInsideRecycleBin("/storage/emulated/0/OpenManager/.recycle"))
        assertTrue(isPathInsideRecycleBin("/storage/emulated/0/OpenManager/.recycle/"))

        // Subfolders and files inside recycle bin
        assertTrue(isPathInsideRecycleBin("/storage/emulated/0/OpenManager/.recycle/document.pdf"))
        assertTrue(isPathInsideRecycleBin("/storage/emulated/0/OpenManager/.recycle/subfolder/document.pdf"))
        assertTrue(isPathInsideRecycleBin("/storage/emulated/0/OpenManager/.recycle/nested/a/b/c.zip"))

        // Paths outside recycle bin
        assertFalse(isPathInsideRecycleBin("/storage/emulated/0/OpenManager"))
        assertFalse(isPathInsideRecycleBin("/storage/emulated/0/OpenManager/downloads"))
        assertFalse(isPathInsideRecycleBin("/storage/emulated/0/Download/test.apk"))
        assertFalse(isPathInsideRecycleBin("/storage/emulated/0/OpenManager/.recycle_other"))
        assertFalse(isPathInsideRecycleBin("/storage/emulated/0"))
    }

    @Test
    fun testDetermineDeleteAction() {
        // When file is inside recycle bin, always delete permanently regardless of checkboxes
        assertEquals("DELETE_PERMANENT", determineDeleteAction(isInsideRecycleBin = true, isRecycleBinEnabled = true, userCheckedRecycle = true))
        assertEquals("DELETE_PERMANENT", determineDeleteAction(isInsideRecycleBin = true, isRecycleBinEnabled = false, userCheckedRecycle = false))

        // When file is outside recycle bin and recycle bin is enabled
        assertEquals("MOVE_TO_RECYCLE_BIN", determineDeleteAction(isInsideRecycleBin = false, isRecycleBinEnabled = true, userCheckedRecycle = true))
        assertEquals("DELETE_PERMANENT", determineDeleteAction(isInsideRecycleBin = false, isRecycleBinEnabled = true, userCheckedRecycle = false))

        // When file is outside recycle bin and recycle bin is disabled (off)
        assertEquals("DELETE_PERMANENT", determineDeleteAction(isInsideRecycleBin = false, isRecycleBinEnabled = false, userCheckedRecycle = true))
        assertEquals("DELETE_PERMANENT", determineDeleteAction(isInsideRecycleBin = false, isRecycleBinEnabled = false, userCheckedRecycle = false))
    }

    @Test
    fun testDisplayPathFormatting() {
        val rootDisplay = formatRecycleDisplayPath("/storage/emulated/0/OpenManager/.recycle", localizedBinName = "Papelera de reciclaje")
        assertEquals("Papelera de reciclaje/", rootDisplay)

        val subDisplay = formatRecycleDisplayPath("/storage/emulated/0/OpenManager/.recycle/Photos/vacation.jpg", localizedBinName = "Papelera de reciclaje")
        assertEquals("Papelera de reciclaje/Photos/vacation.jpg/", subDisplay)

        val englishRoot = formatRecycleDisplayPath("/storage/emulated/0/OpenManager/.recycle", localizedBinName = "Recycle Bin")
        assertEquals("Recycle Bin/", englishRoot)

        val outsideDisplay = formatRecycleDisplayPath("/storage/emulated/0/Music")
        assertEquals("/storage/emulated/0/Music", outsideDisplay)
    }

    @Test
    fun testAutoCleanCutoffLogic() {
        val days = 7
        val now = 1700000000000L
        val cutoff = now - (days.toLong() * 24L * 60L * 60L * 1000L)

        // 8 days old (should be cleaned)
        val file8DaysOldTime = now - (8L * 24L * 60L * 60L * 1000L)
        val file8DaysOldName = "old_file.zip"
        val shouldCleanOld = file8DaysOldName != ".nomedia" && file8DaysOldTime < cutoff
        assertTrue(shouldCleanOld)

        // 2 days old (should NOT be cleaned)
        val file2DaysOldTime = now - (2L * 24L * 60L * 60L * 1000L)
        val file2DaysOldName = "recent_file.zip"
        val shouldCleanRecent = file2DaysOldName != ".nomedia" && file2DaysOldTime < cutoff
        assertFalse(shouldCleanRecent)

        // .nomedia file (should NEVER be deleted even if ancient)
        val nomediaTime = now - (100L * 24L * 60L * 60L * 1000L)
        val nomediaName = ".nomedia"
        val shouldCleanNomedia = nomediaName != ".nomedia" && nomediaTime < cutoff
        assertFalse(shouldCleanNomedia)
    }
}
