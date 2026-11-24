/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import me.zhanghai.android.files.R
import java.util.Locale

/**
 * Receives installation status callbacks from [PackageInstaller.Session] for multi-APK
 * split bundles (`.apks`, `.xapk`, `.apkm`), launches the system confirmation prompt when
 * required, and formats actionable failure diagnostics.
 */
class SplitApkInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SPLIT_INSTALL_STATUS) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (confirmIntent != null) {
                confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirmIntent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return
        }

        val bundleName = intent.getStringExtra(EXTRA_BUNDLE_NAME)
            ?: intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
            ?: "APKS"

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(
                    context,
                    context.getString(R.string.om_split_install_success_format, bundleName),
                    Toast.LENGTH_LONG
                ).show()
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // User cancelled the system installation prompt; no error toast needed.
            }
            else -> {
                val rawMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                val reason = formatPackageInstallerFailure(status, rawMessage) { failure ->
                    if (failure == InstallFailure.OTHER) {
                        context.getString(failure.text, status)
                    } else {
                        context.getString(failure.text)
                    }
                }
                Toast.makeText(
                    context,
                    context.getString(R.string.om_split_install_failed_format, bundleName, reason),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    companion object {
        const val ACTION_SPLIT_INSTALL_STATUS = "me.zhanghai.android.files.action.SPLIT_INSTALL_STATUS"
        const val EXTRA_BUNDLE_NAME = "extra_bundle_name"

        /** Why an install failed, from [PackageInstaller]'s status and message (pure, for tests). */
        enum class InstallFailure(@androidx.annotation.StringRes val text: Int) {
            SIGNATURE(R.string.om_install_fail_signature),
            DOWNGRADE(R.string.om_install_fail_downgrade),
            NO_MATCHING_ABI(R.string.om_install_fail_abi),
            OLDER_SDK(R.string.om_install_fail_older_sdk),
            LOW_TARGET_SDK(R.string.om_install_fail_low_target),
            CONFLICT(R.string.om_install_fail_conflict),
            INCOMPATIBLE(R.string.om_install_fail_incompatible),
            INVALID(R.string.om_install_fail_invalid),
            STORAGE(R.string.om_install_fail_storage),
            BLOCKED(R.string.om_install_fail_blocked),
            OTHER(R.string.om_install_fail_other)
        }

        fun classifyPackageInstallerFailure(status: Int, rawMessage: String?): InstallFailure {
            val upper = rawMessage?.uppercase(Locale.ROOT).orEmpty()
            return when {
                upper.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") ||
                    upper.contains("SIGNATURES_DO_NOT_MATCH") ||
                    upper.contains("INSTALL_FAILED_SHARED_USER_INCOMPATIBLE") -> InstallFailure.SIGNATURE
                upper.contains("INSTALL_FAILED_VERSION_DOWNGRADE") || upper.contains("DOWNGRADE") ->
                    InstallFailure.DOWNGRADE
                upper.contains("INSTALL_FAILED_NO_MATCHING_ABIS") -> InstallFailure.NO_MATCHING_ABI
                upper.contains("INSTALL_FAILED_OLDER_SDK") -> InstallFailure.OLDER_SDK
                upper.contains("INSTALL_FAILED_DEPRECATED_SDK_VERSION") || upper.contains("LOW_TARGET_SDK") ->
                    InstallFailure.LOW_TARGET_SDK
                status == PackageInstaller.STATUS_FAILURE_CONFLICT -> InstallFailure.CONFLICT
                status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> InstallFailure.INCOMPATIBLE
                status == PackageInstaller.STATUS_FAILURE_INVALID -> InstallFailure.INVALID
                status == PackageInstaller.STATUS_FAILURE_STORAGE -> InstallFailure.STORAGE
                status == PackageInstaller.STATUS_FAILURE_BLOCKED -> InstallFailure.BLOCKED
                else -> InstallFailure.OTHER
            }
        }

        /**
         * A clear reason in the user's language ([describe] turns the kind into text), followed
         * by the installer's own message, shortened, for whoever needs the details.
         */
        fun formatPackageInstallerFailure(
            status: Int,
            rawMessage: String?,
            describe: (InstallFailure) -> String
        ): String {
            val cleanRaw = rawMessage?.trim()?.takeIf { it.isNotEmpty() }
            val baseReason = describe(classifyPackageInstallerFailure(status, cleanRaw))
            if (cleanRaw == null) return baseReason
            val compactDetail = if (cleanRaw.length > 140) cleanRaw.take(137) + "..." else cleanRaw
            return "$baseReason ($compactDetail)"
        }
    }
}
