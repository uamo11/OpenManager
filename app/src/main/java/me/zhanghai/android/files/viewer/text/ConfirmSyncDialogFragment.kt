/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show

class ConfirmSyncDialogFragment : AppCompatDialogFragment() {

    private val args by args<Args>()
    private val listener: Listener
        get() = (parentFragment as? Listener) ?: (activity as? Listener) ?: object : Listener {
            override fun onSyncConfirmed() {}
            override fun onSyncIgnored() {}
        }

    private var actionTaken = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val hasUnsavedChanges = args.hasUnsavedChanges
        val messageRes = if (hasUnsavedChanges) {
            R.string.text_editor_file_changed_unsaved_message
        } else {
            R.string.text_editor_file_changed_message
        }
        val negativeBtnText = if (hasUnsavedChanges) {
            R.string.text_editor_keep_changes
        } else {
            android.R.string.cancel
        }

        return MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(R.string.text_editor_file_changed_title)
            .setMessage(messageRes)
            .setPositiveButton(R.string.text_editor_sync) { _, _ ->
                actionTaken = true
                listener.onSyncConfirmed()
            }
            .setNegativeButton(negativeBtnText) { _, _ ->
                actionTaken = true
                listener.onSyncIgnored()
            }
            .create()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (!actionTaken) {
            actionTaken = true
            listener.onSyncIgnored()
        }
    }

    companion object {
        fun show(hasUnsavedChanges: Boolean, fragment: Fragment) {
            ConfirmSyncDialogFragment().putArgs(Args(hasUnsavedChanges)).show(fragment)
        }
    }

    @Parcelize
    class Args(val hasUnsavedChanges: Boolean) : ParcelableArgs

    interface Listener {
        fun onSyncConfirmed()
        fun onSyncIgnored()
    }
}
