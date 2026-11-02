/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.CountDownTimer
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Locale
import java.util.regex.Pattern
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.settings.SettingsActivity
import me.zhanghai.android.files.util.valueCompat

/**
 * Dead Man's Switch Protection Manager (Hombre Muerto).
 * Safeguards users under root and temporary DFRoot / DirtyFlag environments against
 * accidental bricks, bootloops, and malicious destructive operations.
 */
object DeadManProtectionManager {

    const val EXTRA_HIGHLIGHT_PREF = "me.zhanghai.android.files.extra.HIGHLIGHT_PREF"
    const val EXTRA_FROM_DIRTYFLAG_DIALOG = "me.zhanghai.android.files.extra.FROM_DIRTYFLAG_DIALOG"

    private const val PREFS_NAME = "dead_man_prefs"
    private const val KEY_EXPLICITLY_DISABLED = "dfroot_explicitly_disabled"

    /**
     * Set to true when the user completed the 5-second countdown wait in
     * the DirtyFlag Protection dialog. Consumed in Settings to bypass the warning dialog.
     */
    @Volatile
    var hasWaitedCountdown: Boolean = false

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

    private val DANGEROUS_SYSTEM_CMD_PATTERNS = listOf(
        Pattern.compile("dd\\s+.*\\bof=\\S*(boot|init_boot|vendor_boot|vbmeta|dtbo|recovery)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("dd\\s+.*\\bof=/dev/block/", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bflash_image\\s+\\S*(boot|recovery)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bblockdev\\s+--setrw", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bmount\\s+.*\\bremount\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\brm\\s+(-[a-zA-Z]*r[a-zA-Z]*\\s+)?/(system|vendor|product|odm|apex|init)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bchmod\\s+.*\\s+/(system|vendor|product|init)", Pattern.CASE_INSENSITIVE),
        Pattern.compile(">\\s*/dev/block/\\S*(boot|recovery|system)", Pattern.CASE_INSENSITIVE)
    )

    fun recordExplicitlyDisabled(context: Context, disabled: Boolean) {
        try {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sp.edit().putBoolean(KEY_EXPLICITLY_DISABLED, disabled).apply()
        } catch (_: Throwable) {}
    }

    fun isExplicitlyDisabled(context: Context): Boolean {
        return try {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sp.getBoolean(KEY_EXPLICITLY_DISABLED, false)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Determines whether DFROOT PROTECTION should be automatically active:
     * - If dfroot or dirtyflag is detected via SuperuserDetector
     * - OR if the bootloader is locked AND (kernelsu OR dfroot is detected via SuperuserDetector).
     */
    fun shouldAutoActivate(context: Context): Boolean {
        val hasDfroot = SuperuserDetector.detectDfRoot(context.packageManager)
        val bootloaderLocked = isBootloaderLocked()
        val hasKsu = SuperuserDetector.isKernelSuDetected(context.packageManager)
        return hasDfroot || (bootloaderLocked && (hasKsu || hasDfroot))
    }

    /**
     * Reusable delegates backed by the authoritative SuperuserDetector.
     */
    fun detectDfrootOrDirtyFlag(context: Context): Boolean =
        SuperuserDetector.detectDfRoot(context.packageManager)

    fun detectKernelSu(context: Context): Boolean =
        SuperuserDetector.isKernelSuDetected(context.packageManager)

    fun isBootloaderLocked(): Boolean {
        val flashLocked = getSystemProperty("ro.boot.flash.locked")
        if (flashLocked == "1") return true

        val vbState = getSystemProperty("ro.boot.verifiedbootstate").lowercase(Locale.ROOT)
        if (vbState == "green" || vbState == "yellow") return true
        if (vbState == "orange" || vbState == "red") return false

        val devState = getSystemProperty("ro.boot.vbmeta.device_state").lowercase(Locale.ROOT)
        if (devState == "locked") return true
        if (devState == "unlocked") return false

        return false
    }

    fun getSystemProperty(key: String): String = SuperuserDetector.getSystemProperty(key)

    fun isDfrootProtectionActive(context: Context? = null): Boolean {
        if (context != null) {
            if (isExplicitlyDisabled(context)) {
                return false
            }
            if (shouldAutoActivate(context)) {
                return true
            }
        }
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
     * Verifies whether a given path is prohibited from being edited, replaced,
     * renamed, or having its permissions changed under DFROOT Protection.
     *
     * Permitted:
     * - Files created or executed in user-allowed zones like /data/local/tmp/ (e.g. test.sh),
     *   /sdcard/ (storage), or app private cache/data.
     *
     * Prohibited:
     * - System partitions (/system, /vendor, /product, /odm, /apex, /etc),
     * - Startup init files (/init, /default.prop, fstab),
     * - Block devices (/dev/block/),
     * - Any critical boot images (boot.img, vbmeta.img, etc.).
     */
    fun isProtectedFromModification(name: String, fullPath: String = ""): Boolean {
        val normPath = fullPath.trim().replace('\\', '/')
        val normName = File(name).name.trim().lowercase(Locale.ROOT)

        // 1. Critical boot images are ALWAYS protected anywhere
        if (CRITICAL_BOOT_IMG_PATTERN.matcher(normName).matches()) {
            return true
        }

        // 2. Allow user-created and temporary files (e.g. /data/local/tmp/test.sh, /sdcard/...)
        if (normPath.startsWith("/data/local/tmp", ignoreCase = true) ||
            normPath.startsWith("/storage/emulated/", ignoreCase = true) ||
            normPath.startsWith("/sdcard", ignoreCase = true) ||
            normPath.startsWith("/data/data/", ignoreCase = true) ||
            normPath.startsWith("/data/user/", ignoreCase = true)
        ) {
            // Check if it's a critical boot file inside tmp
            return isCriticalBootFile(normName, normPath)
        }

        // 3. System partitions and startup files are protected
        val isSystemPartition = normPath.startsWith("/system") ||
            normPath.startsWith("/vendor") ||
            normPath.startsWith("/product") ||
            normPath.startsWith("/system_ext") ||
            normPath.startsWith("/odm") ||
            normPath.startsWith("/apex") ||
            normPath.startsWith("/etc") ||
            normPath.startsWith("/sbin") ||
            normPath.startsWith("/bin")

        if (isSystemPartition) {
            return true
        }

        // 4. Critical system startup files
        for (startup in CRITICAL_SYSTEM_STARTUP_FILES) {
            if (normPath == startup || normPath.endsWith(startup)) {
                return true
            }
        }
        if (normPath.matches(Regex(".*/(system|vendor|odm)/etc/fstab\\..*")) ||
            normPath.matches(Regex(".*/fstab\\..*"))
        ) {
            return true
        }

        // 5. Block devices (/dev/block/...)
        if (normPath.contains("/dev/block/")) {
            return true
        }

        return false
    }

    fun isProtectedFromModification(path: Path): Boolean {
        val name = path.fileName?.toString() ?: ""
        val fullPath = try {
            path.toFile().absolutePath
        } catch (_: Throwable) {
            path.toString()
        }
        return isProtectedFromModification(name, fullPath)
    }

    /**
     * Checks whether a script or command contains destructive commands that flash
     * or overwrite boot/kernel partitions or modify system partitions.
     */
    fun containsDangerousSystemCommand(cmd: String?): Boolean {
        if (cmd.isNullOrBlank()) return false
        return DANGEROUS_SYSTEM_CMD_PATTERNS.any { it.matcher(cmd).find() }
    }

    fun containsCriticalBootModification(content: String): Boolean {
        return containsDangerousSystemCommand(content)
    }

    fun findFirstCriticalBootFile(paths: Iterable<Path>): Path? {
        return paths.firstOrNull { isCriticalBootFile(it) }
    }

    /**
     * Intercepts a prohibited action with the DirtyFlag Protection Dialog:
     * - Icon: DFROOT icon (ic_root_dfroot)
     * - Title: "DirtyFlag Protection"
     * - Message: "Estás intentando algo peligroso para DirtyFlag / DFRoot. ¿Quieres deshabilitar esta protección?"
     * - Right Button: "Cancelar"
     * - Left Button: 5-second countdown ("5" -> "4" -> "3" -> "2" -> "1" -> "Proceder")
     * - On "Proceder": navigates to Settings and highlights DFROOT PROTECTION switch.
     */
    fun showDirtyFlagProtectionDialog(
        activity: Activity,
        onCancelled: (() -> Unit)? = null
    ) {
        var timer: CountDownTimer? = null

        val dialog = MaterialAlertDialogBuilder(activity)
            .setIcon(R.drawable.ic_root_dfroot)
            .setTitle(activity.getString(R.string.dirtyflag_protection_dialog_title))
            .setMessage(activity.getString(R.string.dirtyflag_protection_dialog_message))
            .setCancelable(false)
            .setPositiveButton(activity.getString(R.string.dirtyflag_protection_cancel)) { d, _ ->
                timer?.cancel()
                d.dismiss()
                onCancelled?.invoke()
            }
            .setNeutralButton("5", null)
            .create()

        dialog.setOnShowListener {
            val rightCancelBtn = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            val leftCountdownBtn = dialog.getButton(DialogInterface.BUTTON_NEUTRAL)

            rightCancelBtn?.setTextColor(Color.parseColor("#9E9E9E"))

            leftCountdownBtn?.isEnabled = false
            leftCountdownBtn?.setTextColor(Color.parseColor("#E53935"))

            timer = object : CountDownTimer(5000, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    val sec = (millisUntilFinished / 1000 + 1).coerceAtMost(5)
                    leftCountdownBtn?.text = "$sec"
                }

                override fun onFinish() {
                    leftCountdownBtn?.text = activity.getString(R.string.dirtyflag_protection_proceed)
                    leftCountdownBtn?.isEnabled = true
                    leftCountdownBtn?.setTextColor(Color.parseColor("#2196F3"))
                    leftCountdownBtn?.setOnClickListener {
                        hasWaitedCountdown = true
                        dialog.dismiss()
                        val intent = Intent(activity, SettingsActivity::class.java).apply {
                            putExtra(EXTRA_HIGHLIGHT_PREF, activity.getString(R.string.pref_key_dead_man_dfroot_protection))
                            putExtra(EXTRA_FROM_DIRTYFLAG_DIALOG, true)
                        }
                        activity.startActivity(intent)
                    }
                }
            }.start()
        }

        dialog.show()
    }

    /**
     * Legacy compatibility wrapper.
     */
    fun showDfrootProtectionDialog(
        activity: Activity,
        targetName: String,
        operationName: String,
        onProceedAnyway: () -> Unit
    ) {
        showDirtyFlagProtectionDialog(activity, onCancelled = null)
    }
}
