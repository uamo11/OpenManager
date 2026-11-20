/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import androidx.recyclerview.widget.DiffUtil
import java8.nio.file.Path
import me.zhanghai.android.files.file.FileItem

/**
 * Pure Kotlin helper holding the fast-path sorting, filtering, counting, size formatting,
 * and background diffing logic for [FileListFragment] and [FileListAdapter].
 */
object FileListEngine {
    const val ASYNC_PREPARE_THRESHOLD = 120
    const val ASYNC_DIFF_MAX_COMBINED = 2000
    const val LARGE_LIST_FLING_THRESHOLD = 100

    data class PreparedFileList(
        val displayList: List<FileItem>,
        val positionMap: HashMap<Path, Int>,
        val directoryCount: Int,
        val fileCount: Int,
        val diffResult: DiffUtil.DiffResult?
    )

    fun countDirectoriesAndFiles(files: List<FileItem>): Pair<Int, Int> {
        var dirCount = 0
        var fileCount = 0
        for (i in 0 until files.size) {
            val item = files[i]
            if (item.isParentDirectoryItem) continue
            if (item.attributes.isDirectory) {
                dirCount++
            } else {
                fileCount++
            }
        }
        return dirCount to fileCount
    }

    fun isSameOrderedList(oldList: List<FileItem>, newList: List<FileItem>): Boolean {
        if (oldList === newList) return true
        val size = oldList.size
        if (size != newList.size) return false
        for (i in 0 until size) {
            val oldItem = oldList[i]
            val newItem = newList[i]
            if (oldItem !== newItem && (oldItem.path != newItem.path || oldItem != newItem)) {
                return false
            }
        }
        return true
    }

    fun prepareDisplayList(
        rawFiles: List<FileItem>,
        showHidden: Boolean,
        sortOptions: FileSortOptions,
        isSearching: Boolean,
        parentItem: FileItem?,
        previousList: List<FileItem>?,
        computeDiff: Boolean
    ): PreparedFileList {
        val filtered = ArrayList<FileItem>(rawFiles.size)
        var dirCount = 0
        var fileCount = 0
        for (i in 0 until rawFiles.size) {
            val item = rawFiles[i]
            if (item.isParentDirectoryItem) continue
            if (!showHidden && item.isHidden) continue
            filtered.add(item)
            if (item.attributes.isDirectory) {
                dirCount++
            } else {
                fileCount++
            }
        }

        if (!isSearching && filtered.size > 1) {
            filtered.sortWith(sortOptions.createComparator())
        }

        val prependParent = !isSearching && parentItem != null
        val displayList: List<FileItem> = if (prependParent) {
            ArrayList<FileItem>(filtered.size + 1).apply {
                add(parentItem!!)
                addAll(filtered)
            }
        } else {
            filtered
        }

        val mapCapacity = ((displayList.size * 4) / 3 + 1).coerceAtLeast(16)
        val positionMap = HashMap<Path, Int>(mapCapacity)
        for (i in 0 until displayList.size) {
            positionMap[displayList[i].path] = i
        }

        val diffResult = if (
            computeDiff &&
            previousList != null &&
            previousList.isNotEmpty() &&
            displayList.isNotEmpty() &&
            previousList.size + displayList.size <= ASYNC_DIFF_MAX_COMBINED &&
            !isSameOrderedList(previousList, displayList)
        ) {
            DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = previousList.size
                override fun getNewListSize(): Int = displayList.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    previousList[oldItemPosition].path == displayList[newItemPosition].path

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    previousList[oldItemPosition] == displayList[newItemPosition]
            })
        } else {
            null
        }

        return PreparedFileList(
            displayList = displayList,
            positionMap = positionMap,
            directoryCount = dirCount,
            fileCount = fileCount,
            diffResult = diffResult
        )
    }

    fun formatCompactFileSize(bytes: Long): String {
        val safeBytes = if (bytes < 0L) 0L else bytes
        return when {
            safeBytes < 1024L -> "$safeBytes B"
            safeBytes < 1_048_576L -> {
                val tenths = (safeBytes * 10L + 512L) / 1024L
                "${tenths / 10L}.${tenths % 10L} KB"
            }
            safeBytes < 1_073_741_824L -> {
                val hundredths = (safeBytes * 100L + 524_288L) / 1_048_576L
                val frac = (hundredths % 100L).toInt()
                "${hundredths / 100L}.${if (frac < 10) "0$frac" else frac} MB"
            }
            else -> {
                val kbScaled = safeBytes / 1024L
                val hundredths = (kbScaled * 100L + 524_288L) / 1_048_576L
                val frac = (hundredths % 100L).toInt()
                "${hundredths / 100L}.${if (frac < 10) "0$frac" else frac} GB"
            }
        }
    }

    fun shouldDeferThumbnailsDuringFling(thumbnailLoadMode: Int, itemCount: Int): Boolean =
        thumbnailLoadMode == 1 || (thumbnailLoadMode == 0 && itemCount >= LARGE_LIST_FLING_THRESHOLD)
}
