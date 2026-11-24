/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Activity
import android.system.Os
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.Shell
import java8.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.theme.custom.OpenManagerPalette
import me.zhanghai.android.files.ui.bindToLifecycle
import me.zhanghai.android.files.ui.isUiAlive
import me.zhanghai.android.files.ui.runWithProgressDialog
import me.zhanghai.android.files.util.escapeShellArg

object CreateSymlinkHelper {

    fun showDialog(activity: Activity, fileItem: FileItem, currentDir: Path, onComplete: () -> Unit) {
        if (!activity.isUiAlive) return
        val targetPath = fileItem.path.toUserFriendlyString()
        val defaultLinkName = "link_${fileItem.name}"

        val context = activity
        val palette = OpenManagerPalette.from(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }

        val targetLabel = TextView(context).apply {
            text = context.getString(R.string.create_symlink_target_label)
            textSize = 13f
            setTextColor(palette.textSecondary)
        }
        container.addView(targetLabel)

        val targetInput = EditText(context).apply {
            setText(targetPath)
            setTextColor(palette.textPrimary)
            setHintTextColor(palette.textSecondary)
        }
        container.addView(targetInput)

        val nameLabel = TextView(context).apply {
            text = context.getString(R.string.create_symlink_name_label)
            textSize = 13f
            setTextColor(palette.textSecondary)
            setPadding(0, 16, 0, 0)
        }
        container.addView(nameLabel)

        val nameInput = EditText(context).apply {
            setText(defaultLinkName)
            setTextColor(palette.textPrimary)
            setHintTextColor(palette.textSecondary)
        }
        container.addView(nameInput)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.create_symlink_title)
            .setView(container)
            .setPositiveButton(R.string.create_symlink_btn_create) { _, _ ->
                val finalTarget = targetInput.text.toString().trim()
                val finalLinkName = nameInput.text.toString().trim()
                if (finalLinkName.isEmpty()) {
                    Toast.makeText(activity, R.string.om_link_name_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val linkPathStr = currentDir.resolve(finalLinkName).toUserFriendlyString()
                createSymlinkAsync(activity, finalTarget, linkPathStr, finalLinkName, onComplete)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        if (activity is LifecycleOwner) {
            dialog.bindToLifecycle(activity)
        }
        if (activity.isUiAlive) {
            dialog.show()
        }
    }

    private fun performSymlinkWork(target: String, linkPathStr: String): Throwable? {
        return try {
            Os.symlink(target, linkPathStr)
            null
        } catch (e: Exception) {
            if (ApkInstallHelper.isRootAvailable()) {
                val res = runCatching {
                    Shell.cmd("ln -s ${target.escapeShellArg()} ${linkPathStr.escapeShellArg()}").exec()
                }.getOrNull()
                if (res != null && (res.isSuccess || res.code == 0)) {
                    return null
                }
            }
            e
        }
    }

    private fun createSymlinkAsync(
        activity: Activity,
        target: String,
        linkPathStr: String,
        linkDisplayName: String,
        onComplete: () -> Unit
    ) {
        val handleResult: (Throwable?) -> Unit = { error ->
            if (activity.isUiAlive) {
                if (error == null) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.create_symlink_success, linkDisplayName),
                        Toast.LENGTH_SHORT
                    ).show()
                    onComplete()
                } else {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.create_symlink_failed, error.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        if (activity is FragmentActivity) {
            activity.runWithProgressDialog(
                title = activity.getString(R.string.create_symlink_title),
                initialMessage = activity.getString(R.string.loading)
            ) {
                val error = withContext(Dispatchers.IO) {
                    performSymlinkWork(target, linkPathStr)
                }
                handleResult(error)
            }
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val error = performSymlinkWork(target, linkPathStr)
            withContext(Dispatchers.Main) {
                handleResult(error)
            }
        }
    }
}
