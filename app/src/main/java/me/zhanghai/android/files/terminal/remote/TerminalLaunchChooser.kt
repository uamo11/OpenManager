/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.remote

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.zhanghai.android.files.R

/**
 * Long-press menu of the Terminal button: local shell, recent SSH/Telnet profiles, profile manager
 * and "+ new profile".
 */
object TerminalLaunchChooser {
    private const val MAX_RECENT = 6

    fun show(activity: Activity, openLocal: () -> Unit) {
        val profiles = RemoteProfileStore.listSorted(activity).take(MAX_RECENT)
        val labels = mutableListOf<CharSequence>()
        val actions = mutableListOf<() -> Unit>()

        labels += activity.getString(R.string.om_remote_chooser_local)
        actions += openLocal
        for (profile in profiles) {
            val tag = if (profile.protocol == RemoteProtocol.SSH) "SSH" else "Telnet"
            labels += "$tag · ${profile.displayName}"
            actions += { RemoteProfilesActivity.connect(activity, profile) }
        }
        labels += activity.getString(R.string.om_remote_chooser_manage)
        actions += { activity.startActivity(RemoteProfilesActivity.createIntent(activity)) }
        labels += activity.getString(R.string.om_remote_chooser_new)
        actions += { activity.startActivity(RemoteProfileEditActivity.createIntent(activity, null)) }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.om_remote_chooser_title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
