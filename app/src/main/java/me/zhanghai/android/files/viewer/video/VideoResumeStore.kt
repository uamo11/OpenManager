/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.video

import android.content.Context

/** Last position of the most recent long videos, so they reopen where they were left. */
class VideoResumeStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(key: String): Long? = prefs.getString(prefKey(key), null)
        ?.substringBefore(',')?.toLongOrNull()

    fun put(key: String, positionMs: Long, durationMs: Long) {
        if (!shouldRememberPosition(positionMs, durationMs)) {
            remove(key)
            return
        }
        val editor = prefs.edit().putString(prefKey(key), "$positionMs,${System.currentTimeMillis()}")
        // Keep only the newest entries.
        val entries = prefs.all.mapNotNull { (k, v) ->
            if (k == prefKey(key)) null
            else (v as? String)?.substringAfter(',', "")?.toLongOrNull()?.let { k to it }
        }
        if (entries.size >= MAX_ENTRIES) {
            entries.sortedBy { it.second }.take(entries.size - MAX_ENTRIES + 1).forEach { editor.remove(it.first) }
        }
        editor.apply()
    }

    fun remove(key: String) {
        if (prefs.contains(prefKey(key))) prefs.edit().remove(prefKey(key)).apply()
    }

    private fun prefKey(key: String) = "v_" + key.hashCode().toUInt().toString(16) + "_" + key.length

    companion object {
        private const val PREFS = "om_video_resume"
        private const val MAX_ENTRIES = 30
    }
}
