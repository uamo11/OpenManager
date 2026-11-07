/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist.duplicate

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale

data class DuplicateFileItem(
    val file: File,
    val size: Long,
    val lastModified: Long,
    val path: String,
    var isMarkedForDeletion: Boolean = false
) {
    val name: String get() = file.name
    val formattedSize: String get() = formatBytes(size)
    val extension: String get() = file.extension.lowercase(Locale.ROOT)
}

data class DuplicateGroup(
    val hash: String,
    val sizePerFile: Long,
    val files: MutableList<DuplicateFileItem>
) {
    val totalCopies: Int get() = files.size
    val wastedBytes: Long get() = (files.size - 1).coerceAtLeast(0) * sizePerFile
    val formattedWastedBytes: String get() = formatBytes(wastedBytes)
}

data class DuplicateScanResult(
    val rootPath: String,
    val groups: List<DuplicateGroup>,
    val totalFilesScanned: Int,
    val totalDuplicateFilesCount: Int,
    val totalWastedBytes: Long
) {
    val formattedTotalWastedBytes: String get() = formatBytes(totalWastedBytes)
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var b = bytes.toDouble()
    var unitIndex = 0
    while (b >= 1024 && unitIndex < units.size - 1) {
        b /= 1024
        unitIndex++
    }
    return if (unitIndex == 0) "${bytes} B" else String.format(Locale.US, "%.1f %s", b, units[unitIndex])
}

object DuplicateFinderEngine {

    suspend fun scanDuplicates(
        rootDir: File,
        recursive: Boolean = true,
        minSizeBytes: Long = 1024L, // Default > 1 KB to skip empty/trivial files
        extensionFilter: String? = null,
        onProgress: ((scanned: Int, duplicatesFound: Int) -> Unit)? = null
    ): DuplicateScanResult = withContext(Dispatchers.IO) {
        if (!rootDir.exists() || !rootDir.isDirectory) {
            return@withContext DuplicateScanResult(rootDir.absolutePath, emptyList(), 0, 0, 0L)
        }

        var scannedCount = 0
        var duplicatesCount = 0

        val allowedExtensions: Set<String>? = extensionFilter
            ?.takeIf { it.isNotBlank() && it != "*" }
            ?.split(",", ";", " ")
            ?.map { it.trim().trimStart('.').lowercase(Locale.ROOT) }
            ?.filter { it.isNotEmpty() }
            ?.toSet()

        // -------------------------------------------------------------
        // Phase 1: Fast Size Indexing (Files with unique size are skipped)
        // -------------------------------------------------------------
        val sizeMap = mutableMapOf<Long, MutableList<File>>()

        fun collectFiles(dir: File) {
            val list = dir.listFiles() ?: return
            for (f in list) {
                if (f.isDirectory) {
                    if (recursive) {
                        collectFiles(f)
                    }
                } else if (f.isFile) {
                    scannedCount++
                    val len = f.length()
                    if (len >= minSizeBytes) {
                        if (allowedExtensions == null || f.extension.lowercase(Locale.ROOT) in allowedExtensions) {
                            val bucket = sizeMap.getOrPut(len) { mutableListOf() }
                            bucket.add(f)
                        }
                    }
                    if (scannedCount % 100 == 0) {
                        onProgress?.invoke(scannedCount, duplicatesCount)
                    }
                }
            }
        }

        collectFiles(rootDir)

        // Keep only size collisions (size >= 2)
        val collisionBuckets = sizeMap.values.filter { it.size >= 2 }

        // -------------------------------------------------------------
        // Phase 2: Partial Hash (First 4KB + Last 4KB)
        // -------------------------------------------------------------
        val partialHashMap = mutableMapOf<String, MutableList<File>>()
        for (bucket in collisionBuckets) {
            for (file in bucket) {
                val partialHash = computePartialHash(file)
                if (partialHash != null) {
                    val key = "${file.length()}_$partialHash"
                    partialHashMap.getOrPut(key) { mutableListOf() }.add(file)
                }
            }
        }

        val partialCollisions = partialHashMap.values.filter { it.size >= 2 }

        // -------------------------------------------------------------
        // Phase 3: Full SHA-256 Hash Confirmation
        // -------------------------------------------------------------
        val fullHashMap = mutableMapOf<String, MutableList<File>>()
        for (bucket in partialCollisions) {
            for (file in bucket) {
                val fullHash = computeFullSha256(file)
                if (fullHash != null) {
                    val key = "${file.length()}_$fullHash"
                    fullHashMap.getOrPut(key) { mutableListOf() }.add(file)
                }
            }
        }

        // Build duplicate groups
        val duplicateGroups = mutableListOf<DuplicateGroup>()
        var totalWastedBytes = 0L

        for ((hashKey, files) in fullHashMap) {
            if (files.size >= 2) {
                val sizePerFile = files[0].length()
                val sortedFiles = files.sortedBy { it.lastModified() }
                
                val groupItems = sortedFiles.mapIndexed { index, file ->
                    DuplicateFileItem(
                        file = file,
                        size = sizePerFile,
                        lastModified = file.lastModified(),
                        path = file.absolutePath,
                        // By default, mark duplicates (keep index 0 unselected, mark index 1..N)
                        isMarkedForDeletion = (index > 0)
                    )
                }.toMutableList()

                val group = DuplicateGroup(
                    hash = hashKey,
                    sizePerFile = sizePerFile,
                    files = groupItems
                )
                duplicateGroups.add(group)
                duplicatesCount += (files.size - 1)
                totalWastedBytes += (files.size - 1) * sizePerFile
            }
        }

        // Sort groups by wasted bytes descending (largest wasted space first)
        duplicateGroups.sortByDescending { it.wastedBytes }

        onProgress?.invoke(scannedCount, duplicatesCount)

        DuplicateScanResult(
            rootPath = rootDir.absolutePath,
            groups = duplicateGroups,
            totalFilesScanned = scannedCount,
            totalDuplicateFilesCount = duplicatesCount,
            totalWastedBytes = totalWastedBytes
        )
    }

    private fun computePartialHash(file: File): String? {
        return try {
            val length = file.length()
            val md = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(4096)
            FileInputStream(file).use { fis ->
                val readFirst = fis.read(buffer, 0, buffer.size)
                if (readFirst > 0) {
                    md.update(buffer, 0, readFirst)
                }
                if (length > 8192) {
                    val skipTarget = length - 4096
                    fis.channel.position(skipTarget)
                    val readLast = fis.read(buffer, 0, buffer.size)
                    if (readLast > 0) {
                        md.update(buffer, 0, readLast)
                    }
                }
            }
            bytesToHex(md.digest())
        } catch (_: Throwable) {
            null
        }
    }

    private fun computeFullSha256(file: File): String? {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            FileInputStream(file).use { fis ->
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    md.update(buffer, 0, bytesRead)
                }
            }
            bytesToHex(md.digest())
        } catch (_: Throwable) {
            null
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        val hexArray = "0123456789abcdef".toCharArray()
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = hexArray[v ushr 4]
            hexChars[j * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }

    /**
     * Smart selection options
     */
    fun selectOldestDuplicates(groups: List<DuplicateGroup>) {
        // Keeps oldest file, marks all newer files for deletion
        for (group in groups) {
            val sorted = group.files.sortedBy { it.lastModified }
            for ((index, item) in sorted.withIndex()) {
                item.isMarkedForDeletion = (index > 0)
            }
        }
    }

    fun selectNewestDuplicates(groups: List<DuplicateGroup>) {
        // Keeps newest file, marks all older files for deletion
        for (group in groups) {
            val sorted = group.files.sortedByDescending { it.lastModified }
            for ((index, item) in sorted.withIndex()) {
                item.isMarkedForDeletion = (index > 0)
            }
        }
    }

    fun selectAllDuplicates(groups: List<DuplicateGroup>) {
        // Marks all duplicate copies (except 1 copy per group)
        for (group in groups) {
            for ((index, item) in group.files.withIndex()) {
                item.isMarkedForDeletion = (index > 0)
            }
        }
    }

    fun clearAllSelections(groups: List<DuplicateGroup>) {
        for (group in groups) {
            for (item in group.files) {
                item.isMarkedForDeletion = false
            }
        }
    }

    suspend fun deleteMarkedDuplicates(
        items: List<DuplicateFileItem>,
        context: Context
    ): Pair<Int, Long> = withContext(Dispatchers.IO) {
        var deletedCount = 0
        var freedBytes = 0L

        for (item in items) {
            if (item.isMarkedForDeletion) {
                val ok = try {
                    if (item.file.delete()) {
                        true
                    } else {
                        // Fallback root deletion if needed
                        val cmd = "rm -f \"${item.file.absolutePath}\""
                        val res = com.topjohnwu.superuser.Shell.cmd(cmd).exec()
                        res.isSuccess
                    }
                } catch (_: Throwable) {
                    false
                }
                if (ok) {
                    deletedCount++
                    freedBytes += item.size
                }
            }
        }
        Pair(deletedCount, freedBytes)
    }
}
