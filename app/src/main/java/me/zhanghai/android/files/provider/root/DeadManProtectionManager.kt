/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import android.app.Activity
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Locale
import java.util.regex.Pattern
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

/**
 * Dead Man's Switch Protection Manager (Hombre Muerto).
 * Safeguards users under root and temporary DFRoot environments against
 * accidental bricks, bootloops, and malicious destructive operations.
 */
object DeadManProtectionManager {

    private val CRITICAL_BOOT_IMG_PATTERN = Pattern.compile(
        "^(boot|init_boot|vendor_boot|vbmeta|dtbo|recovery|abl|xbl|tz|hyp|rpm)([-_].*)?\\.img$",
        Pattern.CASE_INSENSITIVE
    )

    private val CRITICAL_PARTITION_NAMES = listOf(
        "boot", "boot_a", "boot_b",
        "init_boot", "init_boot_a", "init_boot_b",
        "vendor_boot", "vendor_boot_a", "vendor_boot_b",
        "vbmeta", "vbmeta_a", "vbmeta_b",
        "vbmeta_system", "vbmeta_system_a", "vbmeta_system_b",
        "vbmeta_vendor", "vbmeta_vendor_a", "vbmeta_vendor_b",
        "dtbo", "dtbo_a", "dtbo_b",
        "recovery", "recovery_a", "recovery_b",
        "abl", "abl_a", "abl_b",
        "xbl", "xbl_a", "xbl_b",
        "tz", "tz_a", "tz_b",
        "hyp", "hyp_a", "hyp_b",
        "rpm", "rpm_a", "rpm_b",
        "persist", "misc"
    )

    private val CRITICAL_SYSTEM_STARTUP_FILES = listOf(
        "/init",
        "/system/bin/init",
        "/default.prop"
    )

    private val CRITICAL_FLASH_PATTERNS = listOf(
        Pattern.compile("dd\\s+.*\\bof=\\S*(boot|init_boot|vendor_boot|vbmeta|dtbo|recovery)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("dd\\s+.*\\bof=/dev/block/", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bflash_image\\s+\\S*(boot|recovery)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bblockdev\\s+--setrw", Pattern.CASE_INSENSITIVE)
    )

    fun isDfrootProtectionActive(): Boolean {
        return try {
            Settings.DEAD_MAN_DFROOT_PROTECTION.valueCompat
        } catch (_: Throwable) {
            true
        }
    }

    fun isRootProtectionActive(): Boolean {
        return try {
            Settings.DEAD_MAN_ROOT_PROTECTION.valueCompat
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * Checks if a given file name or path is a critical boot/kernel component
     * that would prevent the phone from booting after a restart under DFRoot.
     */
    fun isCriticalBootFile(name: String, fullPath: String = ""): Boolean {
        val cleanName = File(name).name.trim().lowercase(Locale.ROOT)

        // 1. Check filename against critical boot image pattern (e.g. boot.img, init_boot.img, vbmeta.img)
        if (CRITICAL_BOOT_IMG_PATTERN.matcher(cleanName).matches()) {
            return true
        }

        // 2. Check full path against critical system startup files
        val normalizedPath = fullPath.trim().replace('\\', '/')
        if (normalizedPath.isNotEmpty()) {
            for (startupFile in CRITICAL_SYSTEM_STARTUP_FILES) {
                if (normalizedPath == startupFile || normalizedPath.endsWith(startupFile)) {
                    return true
                }
            }
            if (normalizedPath.matches(Regex(".*/(system|vendor|odm)/etc/fstab\\..*")) ||
                normalizedPath.matches(Regex(".*/fstab\\..*"))
            ) {
                return true
            }

            // 3. Check block devices
            if (isCriticalBootDevice(normalizedPath)) {
                return true
            }
        }

        return false
    }

    fun isCriticalBootFile(path: Path): Boolean {
        val name = path.fileName?.toString() ?: ""
        val fullPath = try {
            path.toFile().absolutePath
        } catch (_: Throwable) {
            path.toString()
        }
        return isCriticalBootFile(name, fullPath)
    }

    fun isCriticalBootDevice(path: String): Boolean {
        val normalized = path.replace('\\', '/').lowercase(Locale.ROOT)
        if (!normalized.contains("/dev/block/")) {
            return false
        }
        val partName = File(normalized).name
        return CRITICAL_PARTITION_NAMES.any { it.equals(partName, ignoreCase = true) }
    }

    /**
     * Checks whether a script or command contains destructive commands that flash
     * or overwrite boot/kernel partitions.
     */
    fun containsCriticalBootModification(content: String): Boolean {
        if (content.isBlank()) return false
        return CRITICAL_FLASH_PATTERNS.any { it.matcher(content).find() }
    }

    fun findFirstCriticalBootFile(paths: Iterable<Path>): Path? {
        return paths.firstOrNull { isCriticalBootFile(it) }
    }

    /**
     * Intercepts a dangerous file operation with a Dead Man DFRoot Protection Alert Dialog.
     * If user chooses to block (recommended), the operation is halted.
     * If user explicitly overrides, [onProceedAnyway] is executed.
     */
    fun showDfrootProtectionDialog(
        activity: Activity,
        targetName: String,
        operationName: String,
        onProceedAnyway: () -> Unit
    ) {
        val cleanName = File(targetName).name
        val title = activity.getString(R.string.dead_man_dfroot_dialog_title)
        val message = activity.getString(R.string.dead_man_dfroot_dialog_message, cleanName, operationName)

        MaterialAlertDialogBuilder(activity)
            .setIcon(R.drawable.ic_shield_alert)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(activity.getString(R.string.dead_man_dialog_block_recommended)) { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(
                    activity,
                    activity.getString(R.string.dead_man_dfroot_blocked_toast),
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton(activity.getString(R.string.dead_man_dialog_proceed_anyway)) { dialog, _ ->
                dialog.dismiss()
                onProceedAnyway()
            }
            .show()
    }
}
