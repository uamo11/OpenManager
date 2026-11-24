/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.pdf

import android.content.Context

/**
 * The page each PDF was left at, so every document reopens where it was being read. Keyed by
 * location and size (a different file saved under the same name starts from page 1); only the
 * most recent [MAX_ENTRIES] documents are kept.
 */
class PdfResumeStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Saved page (1-based) for [key], or null. */
    fun get(key: String): Int? = prefs.getString(prefKey(key), null)
        ?.substringBefore(',')?.toIntOrNull()?.takeIf { it >= 1 }

    fun put(key: String, page: Int, pageCount: Int) {
        if (!shouldRemember(page, pageCount)) {
            remove(key)
            return
        }
        val name = prefKey(key)
        val editor = prefs.edit().putString(name, "$page,${System.currentTimeMillis()}")
        val entries = prefs.all.mapNotNull { (k, v) ->
            if (k == name) null else (v as? String)?.substringAfter(',', "")?.toLongOrNull()?.let { k to it }
        }
        if (entries.size >= MAX_ENTRIES) {
            entries.sortedBy { it.second }.take(entries.size - MAX_ENTRIES + 1).forEach { editor.remove(it.first) }
        }
        editor.apply()
    }

    fun remove(key: String) {
        val name = prefKey(key)
        if (prefs.contains(name)) prefs.edit().remove(name).apply()
    }

    private fun prefKey(key: String) = "p_" + key.hashCode().toUInt().toString(16) + "_" + key.length

    companion object {
        private const val PREFS = "om_pdf_resume"
        const val MAX_ENTRIES = 300

        /** Page 1 needs no memory; a page past the end means the file changed. */
        fun shouldRemember(page: Int, pageCount: Int): Boolean = page in 2..pageCount

        fun keyFor(location: String, size: Long): String = "$location#$size"
    }
}
