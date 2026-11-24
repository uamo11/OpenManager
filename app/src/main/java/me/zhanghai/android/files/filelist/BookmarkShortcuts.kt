/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import me.zhanghai.android.files.R
import me.zhanghai.android.files.navigation.BookmarkDirectory
import me.zhanghai.android.files.util.showToast

/** "Add to home screen" for a bookmark (MT Manager style): an icon that opens that folder. */
object BookmarkShortcuts {

    /** Shortcut id of a bookmark folder: one icon per folder, pinning again updates it. */
    fun shortcutId(bookmark: BookmarkDirectory): String = "bookmark:" + bookmark.path.toUri()

    fun pin(context: Context, bookmark: BookmarkDirectory) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            context.showToast(R.string.om_shortcut_unsupported)
            return
        }
        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId(bookmark))
            .setShortLabel(bookmark.name)
            .setLongLabel(bookmark.path.toUserFriendlyString())
            .setIntent(
                FileListActivity.createViewIntent(bookmark.path)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            .setIcon(IconCompat.createWithResource(context, R.mipmap.directory_shortcut_icon))
            .build()
        val requested = ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
        // Android 8+ shows its own confirmation; older launchers add it silently.
        if (!requested) {
            context.showToast(R.string.om_shortcut_unsupported)
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            context.showToast(R.string.shortcut_created)
        }
    }
}
