/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.provider.common.moveTo
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.ui.bindToLifecycle
import me.zhanghai.android.files.ui.isUiAlive
import java.io.File
import java.io.IOException

object FileSwapNamesHelper {

    fun swap(context: Context, pathA: Path, pathB: Path, onComplete: () -> Unit) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (pathA.isLinuxPath && pathB.isLinuxPath) {
                    val fileA = pathA.toFile()
                    val fileB = pathB.toFile()
                    val parentDir = fileA.parentFile ?: throw IOException("No parent directory")
                    val tempFile = File(parentDir, ".swap_tmp_${System.currentTimeMillis()}_${fileA.name}")

                    if (!fileA.renameTo(tempFile)) {
                        throw IOException("Could not rename first file to temp")
                    }
                    if (!fileB.renameTo(fileA)) {
                        tempFile.renameTo(fileA)
                        throw IOException("Could not rename second file to first file")
                    }
                    if (!tempFile.renameTo(fileB)) {
                        fileA.renameTo(fileB)
                        tempFile.renameTo(fileA)
                        throw IOException("Could not rename temp file to second file")
                    }
                } else {
                    val parent = pathA.parent ?: throw IOException("No parent directory")
                    val tempPath = parent.resolve(".swap_tmp_${System.currentTimeMillis()}_${pathA.fileName}")
                    pathA.moveTo(tempPath)
                    try {
                        pathB.moveTo(pathA)
                    } catch (e: Exception) {
                        runCatching { tempPath.moveTo(pathA) }
                        throw e
                    }
                    try {
                        tempPath.moveTo(pathB)
                    } catch (e: Exception) {
                        runCatching { pathA.moveTo(pathB) }
                        runCatching { tempPath.moveTo(pathA) }
                        throw e
                    }
                }

                withContext(Dispatchers.Main) {
                    if ((context as? Activity)?.isUiAlive == false) return@withContext
                    Toast.makeText(appContext, R.string.swap_file_names_success, Toast.LENGTH_SHORT).show()
                    onComplete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    if ((context as? Activity)?.isUiAlive == false) return@withContext
                    Toast.makeText(appContext, R.string.swap_file_names_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun showPickSecondFileDialog(
        activity: Activity,
        firstFile: FileItem,
        candidateFiles: List<FileItem>,
        onComplete: () -> Unit
    ) {
        if (!activity.isUiAlive) return
        val candidates = candidateFiles.filter { it.path != firstFile.path && !it.isParentDirectoryItem }
        if (candidates.isEmpty()) {
            Toast.makeText(activity, "No other files available in this folder", Toast.LENGTH_SHORT).show()
            return
        }

        val names = candidates.map { it.name }.toTypedArray()
        var selectedIndex = 0

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.swap_file_names_title))
            .setSingleChoiceItems(names, 0) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton(R.string.action_swap_file_names) { _, _ ->
                val secondFile = candidates[selectedIndex]
                swap(activity, firstFile.path, secondFile.path, onComplete)
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
}
