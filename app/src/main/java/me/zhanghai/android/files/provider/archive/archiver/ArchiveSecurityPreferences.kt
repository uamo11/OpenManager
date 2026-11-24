/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import android.content.Context
import java.util.concurrent.atomic.AtomicLong

/**
 * Archive protections are always on; there is nothing for users to switch.
 *
 * - CVE-2026-15028 (TAR PAX SUN.holesdata out-of-bounds read) is fixed upstream only in libarchive
 *   3.8.9, newer than the bundled 3.8.8, so the PAX sanitizer always runs.
 * - CVE-2026-14164 (RAR5 double free) is fixed in the bundled libarchive 3.8.8, so RAR5 archives
 *   open normally; their headers are still validated before the native reader sees them.
 */
object ArchiveSecurityPreferences {
    /** libarchive bundled by me.zhanghai.android.libarchive (checked in the native library). */
    const val BUNDLED_LIBARCHIVE_VERSION = "3.8.8"

    private val cve2026_15028BlockedCount = AtomicLong(0)
    private val cve2026_14164BlockedCount = AtomicLong(0)

    @Suppress("UNUSED_PARAMETER")
    fun isTarPaxSanitizationEnabled(context: Context? = null): Boolean = true

    @Suppress("UNUSED_PARAMETER")
    fun isRar5Enabled(context: Context? = null): Boolean = true

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
