/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.view.View
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.snackbar.Snackbar
import me.zhanghai.android.files.R
import me.zhanghai.android.files.navigation.BookmarkDirectories
import me.zhanghai.android.files.navigation.BookmarkDirectory
import me.zhanghai.android.files.navigation.EditBookmarkDirectoryDialogActivity
import me.zhanghai.android.files.navigation.EditBookmarkDirectoryDialogFragment
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.valueCompat

/**
 * The actions of one bookmark, the same in the bookmarks panel and the side menu: open in the
 * other panel, add to the home screen, edit, remove (with undo).
 */
object BookmarkMenu {
    private const val OPEN_OTHER = 1
    private const val ADD_TO_HOME = 2
    private const val EDIT = 3
    private const val REMOVE = 4

    /**
     * Shows the menu on [anchor]. [panelIndex] is the panel the bookmark would open in;
     * [onOpened] runs after a jump (to close the panel or the side menu); the undo bar of a
     * removal goes on [snackbarRoot].
     */
    fun show(
        activity: FileListActivity,
        anchor: View,
        bookmark: BookmarkDirectory,
        panelIndex: Int,
        snackbarRoot: View,
        onOpened: () -> Unit
    ) {
        val popup = PopupMenu(anchor.context, anchor)
        if (activity.canOpenInOtherPanel) {
            popup.menu.add(0, OPEN_OTHER, 0, R.string.om_bookmark_open_other_panel)
        }
        popup.menu.add(0, ADD_TO_HOME, 1, R.string.om_bookmark_add_to_home)
        popup.menu.add(0, EDIT, 2, R.string.edit)
        popup.menu.add(0, REMOVE, 3, R.string.remove)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                OPEN_OTHER -> {
                    activity.openBookmark(bookmark.path, 1 - panelIndex)
                    onOpened()
                }
                ADD_TO_HOME -> BookmarkShortcuts.pin(activity, bookmark)
                EDIT -> activity.startActivitySafe(
                    EditBookmarkDirectoryDialogActivity::class.createIntent()
                        .putArgs(EditBookmarkDirectoryDialogFragment.Args(bookmark))
                )
                REMOVE -> {
                    val before = Settings.BOOKMARK_DIRECTORIES.valueCompat
                    BookmarkDirectories.remove(bookmark)
                    Snackbar.make(snackbarRoot, R.string.om_bookmark_removed, Snackbar.LENGTH_LONG)
                        .setAction(R.string.undo) { BookmarkDirectories.setAll(before) }
                        .show()
                }
            }
            true
        }
        popup.show()
    }
}
