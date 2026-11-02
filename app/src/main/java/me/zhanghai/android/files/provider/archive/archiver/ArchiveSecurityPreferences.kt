/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import me.zhanghai.android.files.app.application
import java.util.concurrent.atomic.AtomicLong

object ArchiveSecurityPreferences {
    const val PREF_KEY_SANITIZE_TAR_PAX = "pref_key_archive_security_sanitize_tar_pax"
    const val PREF_KEY_ENABLE_RAR5 = "pref_key_archive_security_enable_rar5"

    private val cve2026_15028BlockedCount = AtomicLong(0)
    private val cve2026_14164BlockedCount = AtomicLong(0)

    fun isTarPaxSanitizationEnabled(context: Context? = null): Boolean {
        return try {
            val ctx = context ?: try { application } catch (_: Throwable) { null }
            if (ctx == null) return true
            val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
            sp.getBoolean(PREF_KEY_SANITIZE_TAR_PAX, true)
        } catch (_: Throwable) {
            true
        }
    }

    fun setTarPaxSanitizationEnabled(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(PREF_KEY_SANITIZE_TAR_PAX, enabled)
        }
    }

    fun isRar5Enabled(context: Context? = null): Boolean {
        return try {
            val ctx = context ?: try { application } catch (_: Throwable) { null }
            if (ctx == null) return false
            val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
            sp.getBoolean(PREF_KEY_ENABLE_RAR5, false)
        } catch (_: Throwable) {
            false
        }
    }

    fun setRar5Enabled(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(PREF_KEY_ENABLE_RAR5, enabled)
        }
    }

    fun recordCve2026_15028Blocked(count: Long = 1) {
        cve2026_15028BlockedCount.addAndGet(count)
    }

    fun recordCve2026_14164Blocked(count: Long = 1) {
        cve2026_14164BlockedCount.addAndGet(count)
    }

    fun getCve2026_15028Count(): Long = cve2026_15028BlockedCount.get()
    fun getCve2026_14164Count(): Long = cve2026_14164BlockedCount.get()

    fun resetStats() {
        cve2026_15028BlockedCount.set(0)
        cve2026_14164BlockedCount.set(0)
    }
}
