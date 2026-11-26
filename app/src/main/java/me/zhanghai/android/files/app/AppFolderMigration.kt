/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

import java.io.File

/**
 * The app's folder in shared storage is "OM2" (owner request, v2.0.39); it used to be
 * "OpenManager". At start-up an old folder is renamed in one step, so backups, logs, the recycle
 * bin and Apktool projects come along. It never merges: when both exist, both are left alone.
 * Without storage access yet, the rename simply happens at a later start.
 */
object AppFolderMigration {

    const val OLD_NAME = "OpenManager"
    const val NEW_NAME = "OM2"

    /** True when [storageRoot]/OpenManager was renamed to [storageRoot]/OM2. */
    fun migrate(storageRoot: File): Boolean {
        val old = File(storageRoot, OLD_NAME)
        val new = File(storageRoot, NEW_NAME)
        if (!old.isDirectory || new.exists()) return false
        return old.renameTo(new)
    }

    /**
     * The custom app folder setting, if it still names the old default folder: its new value.
     * Null when it names any other folder (the user chose it).
     */
    fun migratedSetting(value: String?): String? {
        val trimmed = value?.trimEnd('/') ?: return null
        for (prefix in listOf("/storage/emulated/0", "/sdcard")) {
            if (trimmed == "$prefix/$OLD_NAME") return "$prefix/$NEW_NAME"
        }
        return null
    }
}
