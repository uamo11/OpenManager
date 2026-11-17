/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Cleans up previously downloaded OpenManager update APKs once Android has finished replacing
 * the application package, preventing premature deletion while PackageInstaller is staging.
 */
class UpdateCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        kotlin.concurrent.thread(name = "om-update-cleanup") {
            try {
                UpdateChecker.cleanupInstalledUpdateApks(appContext)
            } catch (_: Throwable) {
            } finally {
                try {
                    pendingResult.finish()
                } catch (_: Throwable) {}
            }
        }
    }
}
