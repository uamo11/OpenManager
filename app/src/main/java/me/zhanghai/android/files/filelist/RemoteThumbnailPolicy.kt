/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.preference.PreferenceManager
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.application

/**
 * A thumbnail of a file on a server means downloading the whole file. The Settings "Network &
 * media" limits (maximum image size, Wi-Fi only) are applied here so browsing a VPS folder full
 * of big photos does not pull hundreds of megabytes.
 */
object RemoteThumbnailPolicy {
    private const val NETWORK_CHECK_INTERVAL_MS = 5_000L

    @Volatile private var meteredCheckedAt = 0L
    @Volatile private var metered = false

    /** Pure, for tests: [limitMb] of 0 means no limit. */
    fun isWithinSizeLimit(sizeBytes: Long, limitMb: Int): Boolean =
        limitMb <= 0 || sizeBytes <= limitMb.toLong() * 1024 * 1024

    fun allows(sizeBytes: Long, context: Context = application): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val limitMb = prefs.getString(
            context.getString(R.string.pref_key_network_image_size_limit),
            context.getString(R.string.pref_default_value_network_image_size_limit)
        )?.toIntOrNull() ?: 10
        if (!isWithinSizeLimit(sizeBytes, limitMb)) return false
        val wifiOnly = prefs.getBoolean(
            context.getString(R.string.pref_key_network_wifi_only),
            context.resources.getBoolean(R.bool.pref_default_value_network_wifi_only)
        )
        return !wifiOnly || !isMetered(context)
    }

    private fun isMetered(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - meteredCheckedAt > NETWORK_CHECK_INTERVAL_MS) {
            metered = try {
                context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false
            } catch (_: Throwable) {
                false
            }
            meteredCheckedAt = now
        }
        return metered
    }
}
