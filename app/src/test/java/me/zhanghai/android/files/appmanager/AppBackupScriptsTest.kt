/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.appmanager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBackupScriptsTest {

    @Test
    fun backupArchivesEveryPartWithoutCaches() {
        val script = AppBackupScripts.backup("com.example.app", "/data/app/~~x==/com.example.app-1", "/sdcard/OpenManager/Backups/com.example.app/1")
        assertTrue(script.contains("cp '/data/app/~~x==/com.example.app-1'/*.apk"))
        for (part in listOf("data", "user_de", "external", "obb", "media")) {
            assertTrue(part, script.contains("\$OUT/$part.tar.gz"))
        }
        assertTrue(script.contains("--exclude=\"\$P/cache\""))
        assertTrue(script.contains("--exclude=\"\$P/code_cache\""))
        assertTrue(script.trimEnd().endsWith("echo OM_BACKUP_OK"))
    }

    @Test
    fun restoreReinstallsFixesOwnersAndLabels() {
        val script = AppBackupScripts.restore("com.example.app", "/sdcard/b")
        assertTrue(script.contains("pm install-create"))
        assertTrue(script.contains("pm install-commit"))
        assertTrue(script.contains("chown -R \$uid:\$uid '/data/user/0'/\"\$P\""))
        assertTrue(script.contains("restorecon -RF '/data/user/0'/\"\$P\""))
        // Shared storage keeps its media group.
        assertTrue(script.contains("chown -R \$uid:\$(stat -c %g '/data/media/0/Android/data')"))
        assertTrue(script.trimEnd().endsWith("echo OM_RESTORE_OK"))
    }

    @Test
    fun quotesCannotBreakOut() {
        val script = AppBackupScripts.backup("a'b", "/x", "/y")
        assertTrue(script.contains("P='a'\\''b'"))
        assertFalse(script.contains("P='a'b'"))
    }
}
