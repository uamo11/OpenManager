/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Dialog
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.Fragment
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.getQuantityString
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show
import me.zhanghai.android.files.util.valueCompat

class ConfirmDeleteFilesDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private val listener: Listener
        get() = requireParentFragment() as Listener

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val files = args.files
        val isAlreadyInRecycleBin = files.any { it.isInsideRecycleBin }
        val isRecycleBinEnabled = Settings.ENABLE_RECYCLE_BIN.valueCompat

        val message = if (files.size == 1) {
            val file = files.single()
            val messageRes = if (file.attributesNoFollowLinks.isDirectory) {
                R.string.file_delete_message_directory_format
            } else {
                R.string.file_delete_message_file_format
            }
            getString(messageRes, file.name)
        } else {
            val allDirectories = files.all { it.attributesNoFollowLinks.isDirectory }
            val allFiles = files.none { it.attributesNoFollowLinks.isDirectory }
            val messageRes = when {
                allDirectories -> R.plurals.file_delete_message_multiple_directories_format
                allFiles -> R.plurals.file_delete_message_multiple_files_format
                else -> R.plurals.file_delete_message_multiple_mixed_format
            }
            getQuantityString(messageRes, files.size, files.size)
        }

        val builder = MaterialAlertDialogBuilder(requireContext(), theme)

        if (isAlreadyInRecycleBin) {
            builder.setTitle(R.string.recycle_bin_delete_permanently_title)
            builder.setMessage(R.string.recycle_bin_delete_permanently_message)
            builder.setPositiveButton(R.string.recycle_bin_delete_permanently_action) { _, _ ->
                listener.deleteFiles(files, false)
            }
        } else if (isRecycleBinEnabled) {
            val context = requireContext()
            val dp16 = (16 * resources.displayMetrics.density).toInt()
            val dp24 = (24 * resources.displayMetrics.density).toInt()
            val dp8 = (8 * resources.displayMetrics.density).toInt()

            val checkBox = MaterialCheckBox(context).apply {
                setText(R.string.move_to_recycle_bin_checkbox)
                isChecked = Settings.MOVE_TO_RECYCLE_BIN_DEFAULT.valueCompat
            }
            val tv = TextView(context).apply {
                text = message
                setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Body1)
            }
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp24, dp16, dp24, dp8)
                addView(tv)
                addView(checkBox, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp8
                })
            }
            builder.setView(container)
            builder.setPositiveButton(android.R.string.ok) { _, _ ->
                listener.deleteFiles(files, checkBox.isChecked)
            }
        } else {
            builder.setMessage(message)
            builder.setPositiveButton(android.R.string.ok) { _, _ ->
                listener.deleteFiles(files, false)
            }
        }
        return builder.setNegativeButton(android.R.string.cancel, null).create()
    }

    companion object {
        fun show(files: FileItemSet, fragment: Fragment) {
            val validFiles = files.filterNotTo(fileItemSetOf()) { it.isParentDirectoryItem }
            if (validFiles.isEmpty()) return
            ConfirmDeleteFilesDialogFragment().putArgs(Args(validFiles)).show(fragment)
        }
    }

    @Parcelize
    class Args(val files: FileItemSet) : ParcelableArgs

    interface Listener {
        fun deleteFiles(files: FileItemSet)
        fun deleteFiles(files: FileItemSet, moveToRecycleBin: Boolean) {
            deleteFiles(files)
        }
    }
}
