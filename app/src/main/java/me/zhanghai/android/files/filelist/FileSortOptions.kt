/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.file.FileItem

@Parcelize
data class FileSortOptions(
    val by: By,
    val order: Order,
    val isDirectoriesFirst: Boolean
) : Parcelable {
    fun createComparator(): Comparator<FileItem> {
        val sortBy = by
        val descending = order == Order.DESCENDING
        val dirsFirst = isDirectoriesFirst
        return Comparator { a, b ->
            if (dirsFirst) {
                val aDir = a.attributes.isDirectory
                val bDir = b.attributes.isDirectory
                if (aDir != bDir) {
                    return@Comparator if (aDir) -1 else 1
                }
            }
            var cmp = when (sortBy) {
                By.NAME -> 0
                By.TYPE -> String.CASE_INSENSITIVE_ORDER.compare(a.extension, b.extension)
                By.SIZE -> a.attributes.size().compareTo(b.attributes.size())
                By.LAST_MODIFIED -> a.attributes.lastModifiedTime().compareTo(b.attributes.lastModifiedTime())
            }
            if (cmp == 0) {
                val aName = a.name
                val bName = b.name
                val aUnimportant = aName.isNotEmpty() && (aName[0] == '.' || aName[0] == '#')
                val bUnimportant = bName.isNotEmpty() && (bName[0] == '.' || bName[0] == '#')
                if (aUnimportant != bUnimportant) {
                    cmp = if (aUnimportant) 1 else -1
                } else {
                    cmp = a.nameCollationKey.compareTo(b.nameCollationKey)
                }
            }
            if (descending) {
                when {
                    cmp > 0 -> -1
                    cmp < 0 -> 1
                    else -> 0
                }
            } else {
                cmp
            }
        }
    }

    enum class By {
        NAME,
        TYPE,
        SIZE,
        LAST_MODIFIED
    }

    enum class Order {
        ASCENDING,
        DESCENDING
    }
}
