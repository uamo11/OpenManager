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
                val isSpanish = Locale.getDefault().language.equals("es", ignoreCase = true)
                val reason = formatPackageInstallerFailure(status, rawMessage, isSpanish)
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

        /**
         * Pure formatter mapping [PackageInstaller] status codes and installer strings into
         * clear user diagnostics.
         */
        fun formatPackageInstallerFailure(
            status: Int,
            rawMessage: String?,
            isSpanish: Boolean = false
        ): String {
            val cleanRaw = rawMessage?.trim()?.takeIf { it.isNotEmpty() }
            val upper = cleanRaw?.uppercase(Locale.ROOT) ?: ""

            val baseReason = when {
                upper.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") ||
                    upper.contains("SIGNATURES_DO_NOT_MATCH") ||
                    upper.contains("INSTALL_FAILED_SHARED_USER_INCOMPATIBLE") -> {
                    if (isSpanish) {
                        "Firma diferente a la app ya instalada (desinstala la versión anterior primero)"
                    } else {
                        "Signature conflicts with installed app (uninstall previous version first)"
                    }
                }
                upper.contains("INSTALL_FAILED_VERSION_DOWNGRADE") ||
                    upper.contains("DOWNGRADE") -> {
                    if (isSpanish) {
                        "La versión instalada actualmente es más reciente (downgrade bloqueado)"
                    } else {
                        "A newer version is already installed (downgrade blocked)"
                    }
                }
                upper.contains("INSTALL_FAILED_NO_MATCHING_ABIS") -> {
                    if (isSpanish) {
                        "No contiene splits nativos compatibles con la arquitectura CPU de este dispositivo"
                    } else {
                        "No native ABI splits match this device CPU architecture"
                    }
                }
                upper.contains("INSTALL_FAILED_OLDER_SDK") -> {
                    if (isSpanish) {
                        "Requiere una versión de Android más reciente (minSdk incompatible)"
                    } else {
                        "Requires a newer Android version (minSdk incompatible)"
                    }
                }
                upper.contains("INSTALL_FAILED_DEPRECATED_SDK_VERSION") ||
                    upper.contains("LOW_TARGET_SDK") -> {
                    if (isSpanish) {
                        "Bloqueado por Android por targetSdk antiguo; usa Root, Shizuku o AntiSplit"
                    } else {
                        "Blocked by Android due to low targetSdk; try Root, Shizuku, or AntiSplit"
                    }
                }
                status == PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                    if (isSpanish) {
                        "Conflicto con una app o autoridad ya instalada (prueba desinstalando la versión previa)"
                    } else {
                        "Conflict with an already installed app or authority (try uninstalling existing app)"
                    }
                }
                status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                    if (isSpanish) {
                        "Paquete incompatible con este dispositivo o versión de Android"
                    } else {
                        "Package is incompatible with this device or Android version"
                    }
                }
                status == PackageInstaller.STATUS_FAILURE_INVALID -> {
                    if (isSpanish) {
                        "Uno o más splits APK están dañados o son inválidos"
                    } else {
                        "One or more APK splits are corrupted or invalid"
                    }
                }
                status == PackageInstaller.STATUS_FAILURE_STORAGE -> {
                    if (isSpanish) {
                        "Espacio de almacenamiento insuficiente"
                    } else {
                        "Insufficient storage space"
                    }
                }
                status == PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                    if (isSpanish) {
                        "Instalación bloqueada por el sistema o Play Protect"
                    } else {
                        "Installation blocked by system or Play Protect"
                    }
                }
                else -> {
                    if (isSpanish) {
                        "Error del instalador del sistema (código $status)"
                    } else {
                        "System installer error (code $status)"
                    }
                }
            }

            if (cleanRaw == null) return baseReason
            val compactDetail = if (cleanRaw.length > 140) cleanRaw.take(137) + "..." else cleanRaw
            return "$baseReason ($compactDetail)"
        }
    }
}
