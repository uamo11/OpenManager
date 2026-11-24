/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Dialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.show

class CreateDirectoryDialogFragment : FileNameDialogFragment() {
    override val listener: Listener
        get() = super.listener as Listener

    @StringRes
    override val titleRes: Int = R.string.file_create_directory_title

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        binding.nameLayout.apply {
            setStartIconDrawable(R.drawable.directory_icon_white_24dp)
            setStartIconTintList(ColorStateList.valueOf(0xFFFFB300.toInt()))
            hint = getString(R.string.om_name_folder_hint)
            helperText = getString(R.string.om_new_folder_helper)
        }
        binding.extensionsScrollView?.visibility = View.GONE
        return dialog
    }

    override fun onOk(name: String) {
        listener.createDirectory(name)
    }

    companion object {
        fun show(fragment: Fragment) {
            CreateDirectoryDialogFragment().show(fragment)
        }
    }

    interface Listener : FileNameDialogFragment.Listener {
        fun createDirectory(name: String)
    }
}
