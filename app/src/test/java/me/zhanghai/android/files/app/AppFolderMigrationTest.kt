/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AppFolderMigrationTest {

    @Test
    fun theOldFolderIsRenamedWithEverythingInIt() {
        val root = Files.createTempDirectory("om-storage").toFile()
        try {
            File(root, "OpenManager/Backups/com.example/1").mkdirs()
            File(root, "OpenManager/Backups/com.example/1/info.json").writeText("{}")
            assertTrue(AppFolderMigration.migrate(root))
            assertFalse(File(root, "OpenManager").exists())
            assertTrue(File(root, "OM2/Backups/com.example/1/info.json").isFile)
            // Once is enough: nothing left to move.
            assertFalse(AppFolderMigration.migrate(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun neverMergesIntoAnExistingFolder() {
        val root = Files.createTempDirectory("om-storage").toFile()
        try {
            File(root, "OpenManager/logs").mkdirs()
            File(root, "OM2/ApkTool").mkdirs()
            assertFalse(AppFolderMigration.migrate(root))
            assertTrue(File(root, "OpenManager/logs").isDirectory)
            assertTrue(File(root, "OM2/ApkTool").isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun onlyTheOldDefaultSettingChanges() {
        assertEquals("/storage/emulated/0/OM2", AppFolderMigration.migratedSetting("/storage/emulated/0/OpenManager"))
        assertEquals("/sdcard/OM2", AppFolderMigration.migratedSetting("/sdcard/OpenManager/"))
        assertNull(AppFolderMigration.migratedSetting("/storage/emulated/0/MyFolder"))
        assertNull(AppFolderMigration.migratedSetting(null))
    }
}
