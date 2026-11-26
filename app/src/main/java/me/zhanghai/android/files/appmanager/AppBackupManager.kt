/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.appmanager

import android.os.Environment
import com.topjohnwu.superuser.Shell
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App backups (root): /sdcard/OpenManager/Backups/<package>/<date>/ with the APKs, one tar.gz
 * per data folder and an info.json. The work is done by [AppBackupScripts] in a root shell.
 */
object AppBackupManager {

    class Backup(val dir: File, val label: String, val versionName: String, val date: Long, val sizeBytes: Long)

    class Result(val isSuccess: Boolean, val message: String)

    val root: File get() = File(Environment.getExternalStorageDirectory(), "OpenManager/Backups")

    fun isAvailable(): Boolean = Shell.isAppGrantedRoot() == true

    /** Blocking: call it off the main thread. */
    fun backup(app: AppModel): Result {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val dir = File(File(root, app.packageName), stamp)
        val apkDir = File(app.sourceDir).parent ?: return Result(false, "no APK folder")
        val script = AppBackupScripts.backup(app.packageName, apkDir, dir.absolutePath)
        val shell = Shell.cmd(script).exec()
        val out = (shell.out + shell.err).joinToString("\n")
        if (!out.contains("OM_BACKUP_OK")) {
            Shell.cmd("rm -rf '${dir.absolutePath}'").exec()
            return Result(false, out.lines().lastOrNull { it.isNotBlank() } ?: "exit ${shell.code}")
        }
        runCatching {
            File(dir, "info.json").writeText(
                JSONObject()
                    .put("package", app.packageName)
                    .put("label", app.label)
                    .put("versionName", app.versionName)
                    .put("versionCode", app.versionCode)
                    .put("date", System.currentTimeMillis())
                    .toString(1)
            )
        }
        return Result(true, dir.absolutePath)
    }

    /** Backups of [packageName], newest first. */
    fun list(packageName: String): List<Backup> =
        File(root, packageName).listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { dir ->
            val info = runCatching { JSONObject(File(dir, "info.json").readText()) }.getOrNull()
            val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            Backup(
                dir,
                info?.optString("label").orEmpty().ifEmpty { packageName },
                info?.optString("versionName").orEmpty(),
                info?.optLong("date") ?: dir.lastModified(),
                size
            )
        }.sortedByDescending { it.date }

    /** Blocking: call it off the main thread. */
    fun restore(packageName: String, backup: Backup): Result {
        val script = AppBackupScripts.restore(packageName, backup.dir.absolutePath)
        val shell = Shell.cmd(script).exec()
        val out = (shell.out + shell.err).joinToString("\n")
        return if (out.contains("OM_RESTORE_OK")) {
            Result(true, "")
        } else {
            Result(false, out.lines().lastOrNull { it.isNotBlank() } ?: "exit ${shell.code}")
        }
    }

    fun delete(backup: Backup): Boolean = backup.dir.deleteRecursively() ||
        Shell.cmd("rm -rf '${backup.dir.absolutePath}'").exec().isSuccess
}
