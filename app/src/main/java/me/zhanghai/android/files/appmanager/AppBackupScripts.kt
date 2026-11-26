/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.appmanager

/**
 * Root shell scripts that back up and restore an app, the way Android-DataBackup does: the APKs as
 * they are, and each data folder as a tar.gz (internal data without its caches, device-protected
 * data, Android/data, Android/obb, Android/media). Restoring puts the files back, gives them to the
 * app's (possibly new) user id and resets their SELinux labels, or the app could not read them.
 *
 * The scripts are plain text built here so they can be read, tested on a real Android shell (E2E)
 * and run with `su`.
 */
object AppBackupScripts {

    /** One data folder: its name in the backup, its parent and whether it is app-private. */
    class Part(val name: String, val parent: String, val private: Boolean)

    fun parts(user: Int = 0): List<Part> = listOf(
        Part("data", "/data/user/$user", private = true),
        Part("user_de", "/data/user_de/$user", private = true),
        Part("external", "/data/media/$user/Android/data", private = false),
        Part("obb", "/data/media/$user/Android/obb", private = false),
        Part("media", "/data/media/$user/Android/media", private = false)
    )

    private fun q(value: String) = "'" + value.replace("'", "'\\''") + "'"

    /**
     * Copies the APKs of [packageName] (from [apkDir]) and archives each data folder into
     * [backupDir]. Prints `OM_BACKUP_OK` at the end; a part that does not exist is skipped.
     */
    fun backup(packageName: String, apkDir: String, backupDir: String, user: Int = 0): String = buildString {
        appendLine("set -u")
        appendLine("P=${q(packageName)}")
        appendLine("OUT=${q(backupDir)}")
        appendLine("mkdir -p \"\$OUT/apk\" || exit 10")
        appendLine("cp ${q(apkDir)}/*.apk \"\$OUT/apk/\" || exit 11")
        // The app stops writing while it is copied: a consistent backup.
        appendLine("am force-stop \"\$P\"")
        for (part in parts(user)) {
            appendLine("if [ -d ${q(part.parent)}/\"\$P\" ]; then")
            val exclude = if (part.name == "data" || part.name == "user_de") {
                " --exclude=\"\$P/cache\" --exclude=\"\$P/code_cache\""
            } else {
                ""
            }
            appendLine("  tar -czf \"\$OUT/${part.name}.tar.gz\"$exclude -C ${q(part.parent)} \"\$P\" || exit 12")
            appendLine("fi")
        }
        appendLine("echo OM_BACKUP_OK")
    }

    /**
     * Installs the APKs in [backupDir] if the app is missing (or [reinstall]), then extracts each
     * archived part, gives the files to the app's user id and restores their SELinux labels.
     * Prints `OM_RESTORE_OK` at the end.
     */
    fun restore(packageName: String, backupDir: String, user: Int = 0, reinstall: Boolean = false): String = buildString {
        appendLine("set -u")
        appendLine("P=${q(packageName)}")
        appendLine("IN=${q(backupDir)}")
        appendLine("installed=\$(pm path \"\$P\" 2>/dev/null)")
        val install = if (reinstall) "true" else "[ -z \"\$installed\" ]"
        appendLine("if $install; then")
        // Base + splits in one session, so split APKs install as one app.
        appendLine("  total=0; for f in \"\$IN\"/apk/*.apk; do total=\$((total + \$(stat -c %s \"\$f\"))); done")
        appendLine("  sid=\$(pm install-create -r -S \$total | grep -o '[0-9]\\+' | head -n 1)")
        appendLine("  [ -n \"\$sid\" ] || exit 20")
        appendLine("  i=0; for f in \"\$IN\"/apk/*.apk; do")
        appendLine("    pm install-write -S \$(stat -c %s \"\$f\") \$sid \$i \"\$f\" >/dev/null || exit 21")
        appendLine("    i=\$((i + 1))")
        appendLine("  done")
        appendLine("  pm install-commit \$sid | grep -q Success || exit 22")
        appendLine("fi")
        appendLine("am force-stop \"\$P\"")
        appendLine("uid=\$(stat -c %u /data/user/$user/\"\$P\" 2>/dev/null)")
        appendLine("[ -n \"\$uid\" ] || exit 23")
        for (part in parts(user)) {
            appendLine("if [ -f \"\$IN/${part.name}.tar.gz\" ]; then")
            appendLine("  mkdir -p ${q(part.parent)}")
            if (part.private) {
                // Replace, not merge: old files of the installed app would mix with the backup.
                appendLine("  find ${q(part.parent)}/\"\$P\" -mindepth 1 -maxdepth 1 ! -name lib -exec rm -rf {} + 2>/dev/null")
            }
            appendLine("  tar -xzf \"\$IN/${part.name}.tar.gz\" -C ${q(part.parent)} || exit 24")
            if (part.private) {
                appendLine("  chown -R \$uid:\$uid ${q(part.parent)}/\"\$P\"")
            } else {
                // Shared storage keeps the media group so the app and the system can both use it.
                appendLine("  chown -R \$uid:\$(stat -c %g ${q(part.parent)}) ${q(part.parent)}/\"\$P\"")
            }
            appendLine("  restorecon -RF ${q(part.parent)}/\"\$P\" 2>/dev/null")
            appendLine("fi")
        }
        appendLine("echo OM_RESTORE_OK")
    }
}
