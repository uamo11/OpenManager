/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.navigation

import java8.nio.file.Path
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.removeFirst
import me.zhanghai.android.files.util.valueCompat

object BookmarkDirectories {
    fun add(bookmarkDirectory: BookmarkDirectory) {
        Settings.BOOKMARK_DIRECTORIES.putValue(
            added(
                Settings.BOOKMARK_DIRECTORIES.valueCompat, bookmarkDirectory,
                Settings.ADD_BOOKMARKS_TOP.valueCompat
            )
        )
    }

    /** The list with [bookmarkDirectory] added at the top or at the end (pure, for tests). */
    fun <T> added(list: List<T>, item: T, atTop: Boolean): List<T> =
        if (atTop) listOf(item) + list else list + item

    fun contains(path: Path): Boolean =
        Settings.BOOKMARK_DIRECTORIES.valueCompat.any { it.path == path }

    fun removeAt(path: Path) {
        Settings.BOOKMARK_DIRECTORIES.putValue(
            Settings.BOOKMARK_DIRECTORIES.valueCompat.filter { it.path != path }
        )
    }

    /** Saves the order the user dragged the bookmarks into. */
    fun setAll(bookmarkDirectories: List<BookmarkDirectory>) {
        Settings.BOOKMARK_DIRECTORIES.putValue(bookmarkDirectories)
    }

    fun move(fromPosition: Int, toPosition: Int) {
        val bookmarkDirectories = Settings.BOOKMARK_DIRECTORIES.valueCompat.toMutableList()
            .apply { add(toPosition, removeAt(fromPosition)) }
        Settings.BOOKMARK_DIRECTORIES.putValue(bookmarkDirectories)
    }

    fun replace(bookmarkDirectory: BookmarkDirectory) {
        val bookmarkDirectories = Settings.BOOKMARK_DIRECTORIES.valueCompat.toMutableList()
            .apply { this[indexOfFirst { it.id == bookmarkDirectory.id }] = bookmarkDirectory }
        Settings.BOOKMARK_DIRECTORIES.putValue(bookmarkDirectories)
    }

    fun remove(bookmarkDirectory: BookmarkDirectory) {
        val bookmarkDirectories = Settings.BOOKMARK_DIRECTORIES.valueCompat.toMutableList()
            .apply { removeFirst { it.id == bookmarkDirectory.id } }
        Settings.BOOKMARK_DIRECTORIES.putValue(bookmarkDirectories)
    }
}
