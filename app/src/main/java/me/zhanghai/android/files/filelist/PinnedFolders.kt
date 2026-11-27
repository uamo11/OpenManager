/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Context

/**
 * Folders pinned to the top of the folder they are in (an idea from MP Manager's users: bookmarks
 * are elsewhere, this keeps often used folders first in the list itself).
 */
object PinnedFolders {

    private const val PREFS = "om_pinned_folders"
    private const val KEY = "paths"

    fun all(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, null)?.toSet()
            ?: emptySet()

    /** Pins or unpins [path]; true when it is pinned afterwards. */
    fun toggle(context: Context, path: String): Boolean {
        val current = all(context).toMutableSet()
        val pinned = if (path in current) {
            current.remove(path)
            false
        } else {
            current.add(path)
            true
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, current).apply()
        return pinned
    }

    /**
     * [items] already in the chosen order: pinned folders come first, by name, and the rest keeps
     * its order.
     */
    fun <T> pinnedFirst(items: List<T>, pinned: Set<String>, pathOf: (T) -> String, nameOf: (T) -> String): List<T> {
        if (pinned.isEmpty()) return items
        val (top, rest) = items.partition { pathOf(it) in pinned }
        if (top.isEmpty()) return items
        return top.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { nameOf(it) }) + rest
    }
}
