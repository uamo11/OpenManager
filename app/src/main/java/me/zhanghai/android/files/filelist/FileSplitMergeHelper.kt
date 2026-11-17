/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Activity
import android.content.res.ColorStateList
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import java8.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.fileSize
import me.zhanghai.android.files.provider.common.copyTo
import me.zhanghai.android.files.provider.common.deleteIfExists
import me.zhanghai.android.files.provider.common.moveTo
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.newOutputStream
import me.zhanghai.android.files.theme.custom.OpenManagerPalette
import me.zhanghai.android.files.ui.bindToLifecycle
import me.zhanghai.android.files.ui.dismissSafely
import me.zhanghai.android.files.ui.isUiAlive
import me.zhanghai.android.files.ui.runWithProgressDialog
import java.io.OutputStream
import java.util.Locale

object FileSplitMergeHelper {

    private val PART_EXT_REGEX = Regex("\\.part\\d+$", RegexOption.IGNORE_CASE)
    private val PART_NUM_REGEX = Regex("\\.part(\\d+)$", RegexOption.IGNORE_CASE)
    private val THREE_DIGIT_EXT_REGEX = Regex("\\.(\\d{3})$")
    private val THREE_DIGITS_ONLY_REGEX = Regex("^\\d{3}$")

    fun showSplitDialog(activity: Activity, fileItem: FileItem, onComplete: () -> Unit) {
        if (!activity.isUiAlive) return
        if (fileItem.attributes.isDirectory) {
            Toast.makeText(activity, "Cannot split directories", Toast.LENGTH_SHORT).show()
            return
        }
        val parentPath = fileItem.path.parent ?: run {
            Toast.makeText(activity, R.string.om_split_apk_access_error, Toast.LENGTH_SHORT).show()
            return
        }

        val totalLength = fileItem.attributes.fileSize
        val context = activity
        val palette = OpenManagerPalette.from(context)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }

        val infoText = TextView(context).apply {
            text = "${fileItem.name} (${formatSize(totalLength)})"
            textSize = 15f
            setTextColor(palette.textPrimary)
            setPadding(0, 0, 0, 16)
        }
        container.addView(infoText)

        val radioGroup = RadioGroup(context)
        val sizes = listOf(
            5L * 1024 * 1024 to "5 MB",
            10L * 1024 * 1024 to "10 MB",
            20L * 1024 * 1024 to "20 MB",
            50L * 1024 * 1024 to "50 MB",
            100L * 1024 * 1024 to "100 MB"
        )

        var selectedSize = sizes[1].first // default 10MB
        val rbIdMap = mutableMapOf<Int, Long>()
        val tintList = ColorStateList.valueOf(palette.accentColor)

        for ((sizeBytes, label) in sizes) {
            val viewId = android.view.View.generateViewId()
            rbIdMap[viewId] = sizeBytes
            val rb = RadioButton(context).apply {
                text = label
                id = viewId
                setTextColor(palette.textPrimary)
                buttonTintList = tintList
                if (sizeBytes == selectedSize) isChecked = true
                setOnClickListener { selectedSize = sizeBytes }
            }
            radioGroup.addView(rb)
        }

        val customId = android.view.View.generateViewId()
        val rbCustom = RadioButton(context).apply {
            text = "Custom (MB)"
            id = customId
            setTextColor(palette.textPrimary)
            buttonTintList = tintList
        }
        radioGroup.addView(rbCustom)

        val customInput = EditText(context).apply {
            hint = "e.g. 15"
            setTextColor(palette.textPrimary)
            setHintTextColor(palette.textSecondary)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            visibility = android.view.View.GONE
        }
        container.addView(radioGroup)
        container.addView(customInput)

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == customId) {
                customInput.visibility = android.view.View.VISIBLE
            } else {
                customInput.visibility = android.view.View.GONE
                rbIdMap[checkedId]?.let { selectedSize = it }
            }
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.split_file_title)
            .setView(container)
            .setPositiveButton(R.string.split_file_btn_split) { _, _ ->
                val finalSize = if (rbCustom.isChecked) {
                    val mb = customInput.text.toString().toLongOrNull() ?: 10L
                    (mb * 1024 * 1024).coerceAtLeast(1024L)
                } else {
                    selectedSize
                }
                executeSplit(activity, fileItem, parentPath, finalSize, onComplete)
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

    private fun performSplitWork(
        srcPath: Path,
        parentPath: Path,
        srcName: String,
        totalBytes: Long,
        partSizeBytes: Long,
        onProgressPercent: ((Int) -> Unit)? = null
    ): Int {
        var partIndex = 0
        var output: OutputStream? = null
        val createdPartPaths = mutableListOf<Path>()
        try {
            srcPath.newInputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var currentPartBytes = 0L
                var totalRead = 0L
                var lastPct = -1
                var bytesRead: Int

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (bytesRead == 0) continue
                    if (output == null || currentPartBytes >= partSizeBytes) {
                        output?.flush()
                        output?.close()
                        output = null
                        partIndex++
                        currentPartBytes = 0L
                        val partPath = parentPath.resolve("$srcName.part$partIndex")
                        createdPartPaths.add(partPath)
                        output = partPath.newOutputStream()
                    }
                    output!!.write(buffer, 0, bytesRead)
                    currentPartBytes += bytesRead
                    totalRead += bytesRead
                    if (totalBytes > 0L && onProgressPercent != null) {
                        val pct = ((totalRead * 100L) / totalBytes).toInt().coerceIn(0, 100)
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgressPercent(pct)
                        }
                    }
                }
                output?.flush()
                output?.close()
                output = null

                if (partIndex == 0) {
                    // Preserve single empty part if source file is 0 bytes
                    partIndex = 1
                    val partPath = parentPath.resolve("$srcName.part1")
                    createdPartPaths.add(partPath)
                    partPath.newOutputStream().close()
                }
            }
            return partIndex
        } catch (e: Exception) {
            runCatching { output?.close() }
            output = null
            for (partial in createdPartPaths) {
                runCatching { partial.deleteIfExists() }
            }
            throw e
        } finally {
            runCatching { output?.close() }
        }
    }

    private fun executeSplit(
        activity: Activity,
        fileItem: FileItem,
        parentPath: Path,
        partSizeBytes: Long,
        onComplete: () -> Unit
    ) {
        val baseMsg = activity.getString(R.string.split_file_progress)
        val totalBytes = fileItem.attributes.fileSize
        val srcPath = fileItem.path
        val srcName = fileItem.name

        if (activity is FragmentActivity) {
            activity.runWithProgressDialog(
                title = activity.getString(R.string.split_file_title),
                initialMessage = baseMsg
            ) { updateMessage ->
                var lastUiUpdateMs = 0L
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        performSplitWork(srcPath, parentPath, srcName, totalBytes, partSizeBytes) { pct ->
                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdateMs >= 150L || pct == 100) {
                                lastUiUpdateMs = now
                                updateMessage("$baseMsg ($pct%)")
                            }
                        }
                    }
                }
                if (!activity.isUiAlive) return@runWithProgressDialog
                result.fold(
                    onSuccess = { totalParts ->
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.split_file_success, totalParts),
                            Toast.LENGTH_LONG
                        ).show()
                        onComplete()
                    },
                    onFailure = { e ->
                        e.printStackTrace()
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.om_split_error_format, e.message ?: ""),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }
            return
        }

        val progress = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.split_file_title)
            .setMessage(baseMsg)
            .setCancelable(false)
            .create()
        if (activity is LifecycleOwner) {
            progress.bindToLifecycle(activity)
        }
        if (activity.isUiAlive) progress.show()

        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching {
                performSplitWork(srcPath, parentPath, srcName, totalBytes, partSizeBytes)
            }
            withContext(Dispatchers.Main) {
                progress.dismissSafely()
                if (!activity.isUiAlive) return@withContext
                result.fold(
                    onSuccess = { totalParts ->
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.split_file_success, totalParts),
                            Toast.LENGTH_LONG
                        ).show()
                        onComplete()
                    },
                    onFailure = { e ->
                        e.printStackTrace()
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.om_split_error_format, e.message ?: ""),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }
        }
    }

    fun isSplitPart(fileItem: FileItem): Boolean {
        val name = fileItem.name.lowercase(Locale.ROOT)
        return name.contains(PART_EXT_REGEX) || name.contains(THREE_DIGIT_EXT_REGEX)
    }

    private fun extractPartNumber(name: String): Int {
        val partMatch = PART_NUM_REGEX.find(name)
        if (partMatch != null) {
            return partMatch.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE
        }
        val numMatch = THREE_DIGIT_EXT_REGEX.find(name)
        if (numMatch != null) {
            return numMatch.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE
        }
        return Int.MAX_VALUE
    }

    fun findSplitParts(firstPart: FileItem, allFiles: List<FileItem>): List<FileItem> {
        val name = firstPart.name
        val baseName: String
        val isPartFormat: Boolean

        if (name.contains(PART_EXT_REGEX)) {
            baseName = name.substringBeforeLast(".part")
            isPartFormat = true
        } else if (name.contains(THREE_DIGIT_EXT_REGEX)) {
            baseName = name.substringBeforeLast(".")
            isPartFormat = false
        } else {
            return listOf(firstPart)
        }

        return allFiles.filter {
            val siblingName = it.name
            if (isPartFormat) {
                siblingName.startsWith("$baseName.part", ignoreCase = true)
            } else {
                siblingName.startsWith("$baseName.", ignoreCase = true) &&
                    siblingName.substringAfterLast(".").matches(THREE_DIGITS_ONLY_REGEX)
            }
        }.sortedWith(compareBy({ extractPartNumber(it.name) }, { it.name.lowercase(Locale.ROOT) }))
    }

    fun showMergeDialog(activity: Activity, firstPart: FileItem, allFiles: List<FileItem>, onComplete: () -> Unit) {
        if (!activity.isUiAlive) return
        val parts = findSplitParts(firstPart, allFiles)
        if (parts.isEmpty()) {
            Toast.makeText(activity, "No parts found to merge", Toast.LENGTH_SHORT).show()
            return
        }
        val parentPath = firstPart.path.parent ?: run {
            Toast.makeText(activity, R.string.om_split_apk_access_error, Toast.LENGTH_SHORT).show()
            return
        }

        val firstName = firstPart.name
        val targetName = if (firstName.contains(PART_EXT_REGEX)) {
            firstName.substringBeforeLast(".part")
        } else if (firstName.contains(THREE_DIGIT_EXT_REGEX)) {
            firstName.substringBeforeLast(".")
        } else {
            "$firstName.merged"
        }

        var totalSize = 0L
        for (part in parts) {
            totalSize += part.attributes.fileSize
        }

        val context = activity
        val palette = OpenManagerPalette.from(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }

        val infoText = TextView(context).apply {
            text = context.getString(R.string.merge_files_parts_detected, parts.size, formatSize(totalSize))
            textSize = 15f
            setTextColor(palette.textPrimary)
            setPadding(0, 0, 0, 16)
        }
        container.addView(infoText)

        val nameLabel = TextView(context).apply {
            text = context.getString(R.string.merge_files_target_label)
            textSize = 13f
            setTextColor(palette.textSecondary)
        }
        container.addView(nameLabel)

        val nameInput = EditText(context).apply {
            setText(targetName)
            setTextColor(palette.textPrimary)
            setHintTextColor(palette.textSecondary)
        }
        container.addView(nameInput)

        val checkDelete = CheckBox(context).apply {
            text = context.getString(R.string.merge_files_delete_parts)
            setTextColor(palette.textPrimary)
            buttonTintList = ColorStateList.valueOf(palette.accentColor)
            isChecked = false
            setPadding(0, 16, 0, 0)
        }
        container.addView(checkDelete)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.merge_files_title)
            .setView(container)
            .setPositiveButton(R.string.merge_files_btn_merge) { _, _ ->
                val rawName = nameInput.text.toString().trim().ifEmpty { targetName }
                val finalName = rawName.replace('/', '_').replace('\\', '_')
                val destPath = parentPath.resolve(finalName)
                executeMerge(activity, parts, parentPath, destPath, finalName, totalSize, checkDelete.isChecked, onComplete)
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

    private fun performMergeWork(
        parts: List<FileItem>,
        parentPath: Path,
        destPath: Path,
        destName: String,
        totalBytes: Long,
        deleteParts: Boolean,
        onProgressPercent: ((Int) -> Unit)? = null
    ) {
        val tempDestPath = parentPath.resolve(".${destName}.om_merge_tmp_${System.currentTimeMillis()}")
        try {
            tempDestPath.newOutputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var mergedBytes = 0L
                var lastPct = -1
                for (part in parts) {
                    part.path.newInputStream().use { input ->
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            mergedBytes += read
                            if (totalBytes > 0L && onProgressPercent != null) {
                                val pct = ((mergedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgressPercent(pct)
                                }
                            }
                        }
                    }
                }
                output.flush()
            }

            try {
                tempDestPath.moveTo(destPath, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                tempDestPath.copyTo(destPath, StandardCopyOption.REPLACE_EXISTING)
                runCatching { tempDestPath.deleteIfExists() }
            }

            if (deleteParts) {
                for (part in parts) {
                    runCatching { part.path.deleteIfExists() }
                }
            }
        } catch (e: Exception) {
            runCatching { tempDestPath.deleteIfExists() }
            throw e
        }
    }

    private fun executeMerge(
        activity: Activity,
        parts: List<FileItem>,
        parentPath: Path,
        destPath: Path,
        destName: String,
        totalBytes: Long,
        deleteParts: Boolean,
        onComplete: () -> Unit
    ) {
        val baseMsg = activity.getString(R.string.merge_files_progress)

        if (activity is FragmentActivity) {
            activity.runWithProgressDialog(
                title = activity.getString(R.string.merge_files_title),
                initialMessage = baseMsg
            ) { updateMessage ->
                var lastUiUpdateMs = 0L
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        performMergeWork(parts, parentPath, destPath, destName, totalBytes, deleteParts) { pct ->
                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdateMs >= 150L || pct == 100) {
                                lastUiUpdateMs = now
                                updateMessage("$baseMsg ($pct%)")
                            }
                        }
                    }
                }
                if (!activity.isUiAlive) return@runWithProgressDialog
                result.fold(
                    onSuccess = {
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.merge_files_success, destName),
                            Toast.LENGTH_LONG
                        ).show()
                        onComplete()
                    },
                    onFailure = { e ->
                        e.printStackTrace()
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.om_merge_error_format, e.message ?: ""),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }
            return
        }

        val progress = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.merge_files_title)
            .setMessage(baseMsg)
            .setCancelable(false)
            .create()
        if (activity is LifecycleOwner) {
            progress.bindToLifecycle(activity)
        }
        if (activity.isUiAlive) progress.show()

        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching {
                performMergeWork(parts, parentPath, destPath, destName, totalBytes, deleteParts)
            }
            withContext(Dispatchers.Main) {
                progress.dismissSafely()
                if (!activity.isUiAlive) return@withContext
                result.fold(
                    onSuccess = {
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.merge_files_success, destName),
                            Toast.LENGTH_LONG
                        ).show()
                        onComplete()
                    },
                    onFailure = { e ->
                        e.printStackTrace()
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.om_merge_error_format, e.message ?: ""),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }
        }
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
