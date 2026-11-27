/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Names searched before, newest first, with pinned ones always on top and never dropped (MT has
 * this; a user moving from MT missed it most).
 */
object SearchHistory {

    data class Entry(val text: String, val pinned: Boolean = false)

    const val MAX_RECENT = 20
    private const val PREFS = "om_search_history"
    private const val KEY = "entries"

    /** [text] searched again: to the top of the recent ones; a pinned one stays where it is. */
    fun added(entries: List<Entry>, text: String, maxRecent: Int = MAX_RECENT): List<Entry> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return entries
        if (entries.any { it.pinned && it.text == trimmed }) return entries
        val pinned = entries.filter { it.pinned }
        val recent = listOf(Entry(trimmed)) + entries.filter { !it.pinned && it.text != trimmed }
        return pinned + recent.take(maxRecent)
    }

    fun togglePinned(entries: List<Entry>, text: String): List<Entry> {
        val entry = entries.firstOrNull { it.text == text } ?: return entries
        val others = entries.filter { it.text != text }
        return if (entry.pinned) {
            // Unpinned: first among the recent ones.
            others.filter { it.pinned } + Entry(text) + others.filter { !it.pinned }
        } else {
            others.filter { it.pinned } + Entry(text, pinned = true) + others.filter { !it.pinned }
        }
    }

    fun removed(entries: List<Entry>, text: String): List<Entry> = entries.filter { it.text != text }

    fun load(context: Context): List<Entry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map {
                val o = array.getJSONObject(it)
                Entry(o.getString("text"), o.optBoolean("pinned"))
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("text", it.text).put("pinned", it.pinned)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, array.toString()).apply()
    }
}
