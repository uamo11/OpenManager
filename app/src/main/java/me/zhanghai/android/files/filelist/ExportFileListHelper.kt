/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import java8.nio.file.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.provider.common.newOutputStream
import me.zhanghai.android.files.theme.custom.OpenManagerPalette
import me.zhanghai.android.files.ui.bindToLifecycle
import me.zhanghai.android.files.ui.isUiAlive
import me.zhanghai.android.files.ui.runWithProgressDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExportFileListHelper {

    fun showDialog(
        activity: Activity,
        currentDir: Path,
        allFiles: List<FileItem>,
        selectedFiles: List<FileItem>?,
        onComplete: () -> Unit
    ) {
        if (!activity.isUiAlive) return
        val filesToExport = if (!selectedFiles.isNullOrEmpty()) {
            selectedFiles.filter { !it.isParentDirectoryItem }
        } else {
            allFiles.filter { !it.isParentDirectoryItem }
        }

        if (filesToExport.isEmpty()) {
            Toast.makeText(activity, R.string.om_export_no_files, Toast.LENGTH_SHORT).show()
            return
        }

        val context = activity
        val palette = OpenManagerPalette.from(context)
        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }

        val scopeInfo = TextView(context).apply {
            text = if (!selectedFiles.isNullOrEmpty()) {
                context.getString(R.string.export_file_list_selected, filesToExport.size)
            } else {
                context.getString(R.string.export_file_list_all, filesToExport.size)
            }
            textSize = 15f
            setTextColor(palette.textPrimary)
            setPadding(0, 0, 0, 16)
        }
        container.addView(scopeInfo)

        val formatLabel = TextView(context).apply {
            text = context.getString(R.string.export_file_list_format)
            textSize = 13f
            setTextColor(palette.textSecondary)
        }
        container.addView(formatLabel)

        val radioGroup = RadioGroup(context)
        val formats = listOf(
            0 to context.getString(R.string.export_file_list_names_only),
            1 to context.getString(R.string.export_file_list_names_sizes),
            2 to context.getString(R.string.export_file_list_full_paths),
            3 to context.getString(R.string.export_file_list_full_details)
        )

        var selectedFormat = 0
        val tintList = ColorStateList.valueOf(palette.accentColor)
        for ((id, label) in formats) {
            val rb = RadioButton(context).apply {
                text = label
                this.id = id
                setTextColor(palette.textPrimary)
                buttonTintList = tintList
                if (id == 0) isChecked = true
            }
            radioGroup.addView(rb)
        }
        container.addView(radioGroup)

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            selectedFormat = checkedId
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.export_file_list_title)
            .setView(container)
            .setPositiveButton(R.string.export_file_list_copy_clipboard) { _, _ ->
                val text = generateExportText(filesToExport, selectedFormat)
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("File List", text))
                Toast.makeText(
                    activity,
                    context.getString(R.string.export_file_list_copied, filesToExport.size),
                    Toast.LENGTH_SHORT
                ).show()
                onComplete()
            }
            .setNeutralButton(R.string.export_file_list_save_file) { _, _ ->
                saveExportToFileAsync(activity, currentDir, filesToExport, selectedFormat, onComplete)
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

    private fun saveExportToFileAsync(
        activity: Activity,
        currentDir: Path,
        filesToExport: List<FileItem>,
        selectedFormat: Int,
        onComplete: () -> Unit
    ) {
        val destPath = if (currentDir.isArchivePath) {
            Paths.get("/storage/emulated/0/Download/file_list_${System.currentTimeMillis()}.txt")
        } else {
            currentDir.resolve("file_list.txt")
        }
        val destName = destPath.fileName.toString()

        val writeWorker = {
            val text = generateExportText(filesToExport, selectedFormat)
            destPath.newOutputStream().use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            }
        }

        if (activity is FragmentActivity) {
            activity.runWithProgressDialog(
                title = activity.getString(R.string.export_file_list_title),
                initialMessage = activity.getString(R.string.loading)
            ) {
                val res = runCatching {
                    withContext(Dispatchers.IO) { writeWorker() }
                }
                if (!activity.isUiAlive) return@runWithProgressDialog
                res.fold(
                    onSuccess = {
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.export_file_list_saved, destName),
                            Toast.LENGTH_SHORT
                        ).show()
                        onComplete()
                    },
                    onFailure = { e ->
                        Toast.makeText(activity, activity.getString(R.string.om_export_save_failed, e.message.orEmpty()), Toast.LENGTH_SHORT).show()
                    }
                )
            }
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val res = runCatching { writeWorker() }
            withContext(Dispatchers.Main) {
                if (!activity.isUiAlive) return@withContext
                res.fold(
                    onSuccess = {
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.export_file_list_saved, destName),
                            Toast.LENGTH_SHORT
                        ).show()
                        onComplete()
                    },
                    onFailure = { e ->
                        Toast.makeText(activity, activity.getString(R.string.om_export_save_failed, e.message.orEmpty()), Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    internal fun generateExportText(files: List<FileItem>, format: Int): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()

        for (file in files) {
            val name = file.name
            val size = formatSize(file.attributes.size())
            val path = file.path.toUserFriendlyString()
            val date = dateFormat.format(Date(file.attributes.lastModifiedTime().toMillis()))

            when (format) {
                0 -> sb.appendLine(name)
                1 -> sb.appendLine("$name\t$size")
                2 -> sb.appendLine(path)
                3 -> sb.appendLine("$name\t$size\t$date\t$path")
            }
        }
        return sb.toString().trimEnd()
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes.toDouble() / 1024
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024
        return String.format(Locale.US, "%.2f GB", gb)
    }
}
