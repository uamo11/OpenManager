/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist.textsearch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.Locale
import java.util.regex.Pattern

data class TextOccurrence(
    val lineNumber: Int,
    val lineText: String,
    val startCol: Int,
    val endCol: Int,
    var isSelected: Boolean = true
)

data class FileTextMatches(
    val file: File,
    val relativePath: String,
    val matches: MutableList<TextOccurrence>,
    var isFileSelected: Boolean = true
) {
    val fileName: String get() = file.name
    val matchCount: Int get() = matches.size
}

data class BatchTextSearchResult(
    val rootPath: String,
    val query: String,
    val fileMatches: List<FileTextMatches>,
    val totalMatches: Int,
    val totalFilesSearched: Int
)

data class BatchReplaceResult(
    val filesModified: Int,
    val totalReplacements: Int
)

object FileTextSearchEngine {

    private val DEFAULT_TEXT_EXTENSIONS = setOf(
        "txt", "xml", "json", "smali", "java", "kt", "js", "ts", "html", "htm", "css",
        "c", "cpp", "h", "hpp", "sh", "bash", "py", "properties", "yaml", "yml", "md",
        "gradle", "ini", "conf", "cfg", "log", "rc"
    )

    suspend fun searchText(
        rootDir: File,
        query: String,
        recursive: Boolean = true,
        caseSensitive: Boolean = false,
        wholeWord: Boolean = false,
        isRegex: Boolean = false,
        extensionFilter: String? = null,
        onProgress: ((scanned: Int, matchesCount: Int) -> Unit)? = null
    ): BatchTextSearchResult = withContext(Dispatchers.IO) {
        if (!rootDir.exists() || !rootDir.isDirectory || query.isEmpty()) {
            return@withContext BatchTextSearchResult(rootDir.absolutePath, query, emptyList(), 0, 0)
        }

        var scannedCount = 0
        var totalMatchesCount = 0
        val results = mutableListOf<FileTextMatches>()

        val allowedExtensions: Set<String>? = extensionFilter
            ?.takeIf { it.isNotBlank() && it != "*" }
            ?.split(",", ";", " ")
            ?.map { it.trim().trimStart('.').lowercase(Locale.ROOT) }
            ?.filter { it.isNotEmpty() }
            ?.toSet()

        val compiledPattern: Pattern? = try {
            val flags = if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            val patternString = when {
                isRegex -> query
                wholeWord -> "\\b" + Pattern.quote(query) + "\\b"
                else -> Pattern.quote(query)
            }
            Pattern.compile(patternString, flags)
        } catch (_: Throwable) {
            null
        }

        if (compiledPattern == null) {
            return@withContext BatchTextSearchResult(rootDir.absolutePath, query, emptyList(), 0, 0)
        }

        fun scanFile(file: File) {
            if (!file.isFile || file.length() > 20 * 1024 * 1024L) { // Skip files > 20MB for safety
                return
            }
            val ext = file.extension.lowercase(Locale.ROOT)
            if (allowedExtensions != null) {
                if (ext !in allowedExtensions) return
            } else {
                // If no extension filter given, check if known text ext or test if binary
                if (ext.isNotEmpty() && ext !in DEFAULT_TEXT_EXTENSIONS && isBinaryFile(file)) {
                    return
                }
            }

            scannedCount++
            if (scannedCount % 50 == 0) {
                onProgress?.invoke(scannedCount, totalMatchesCount)
            }

            val fileOccurrences = mutableListOf<TextOccurrence>()

            try {
                BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8)).use { reader ->
                    var lineNum = 1
                    var line: String? = reader.readLine()
                    while (line != null) {
                        val matcher = compiledPattern.matcher(line)
                        while (matcher.find()) {
                            fileOccurrences.add(
                                TextOccurrence(
                                    lineNumber = lineNum,
                                    lineText = line.trim(),
                                    startCol = matcher.start(),
                                    endCol = matcher.end()
                                )
                            )
                            totalMatchesCount++
                        }
                        lineNum++
                        line = reader.readLine()
                    }
                }
            } catch (_: Throwable) {
                // Ignore unreadable or corrupt file
            }

            if (fileOccurrences.isNotEmpty()) {
                val relPath = file.relativeToOrSelf(rootDir).path
                results.add(FileTextMatches(file, relPath, fileOccurrences))
            }
        }

        fun walkDir(dir: File) {
            val list = dir.listFiles() ?: return
            for (f in list) {
                if (f.isDirectory) {
                    if (recursive) {
                        walkDir(f)
                    }
                } else if (f.isFile) {
                    scanFile(f)
                }
            }
        }

        walkDir(rootDir)
        onProgress?.invoke(scannedCount, totalMatchesCount)

        BatchTextSearchResult(
            rootPath = rootDir.absolutePath,
            query = query,
            fileMatches = results,
            totalMatches = totalMatchesCount,
            totalFilesSearched = scannedCount
        )
    }

    private fun isBinaryFile(file: File): Boolean {
        return try {
            val bytes = ByteArray(512)
            FileInputStream(file).use { fis ->
                val read = fis.read(bytes)
                if (read <= 0) return false
                var nullCount = 0
                for (i in 0 until read) {
                    if (bytes[i] == 0.toByte()) {
                        nullCount++
                    }
                }
                nullCount > 0
            }
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * Replaces occurrences in files.
     * @param selectedFilesOnly if true, only replaces in files where isFileSelected == true ("algunas palabras").
     *                          if false, replaces in all files in the list ("todas las palabras").
     */
    suspend fun replaceTextInFiles(
        fileMatches: List<FileTextMatches>,
        query: String,
        replacement: String,
        selectedFilesOnly: Boolean = true,
        caseSensitive: Boolean = false,
        wholeWord: Boolean = false,
        isRegex: Boolean = false
    ): BatchReplaceResult = withContext(Dispatchers.IO) {
        var filesModified = 0
        var totalReplacements = 0

        val flags = if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        val patternString = when {
            isRegex -> query
            wholeWord -> "\\b" + Pattern.quote(query) + "\\b"
            else -> Pattern.quote(query)
        }
        val pattern = Pattern.compile(patternString, flags)

        for (fileMatch in fileMatches) {
            if (selectedFilesOnly && !fileMatch.isFileSelected) {
                continue
            }
            val file = fileMatch.file
            if (!file.exists() || !file.canWrite()) {
                continue
            }

            try {
                val originalText = file.readText(Charsets.UTF_8)
                val matcher = pattern.matcher(originalText)
                var countInFile = 0
                while (matcher.find()) {
                    countInFile++
                }

                if (countInFile > 0) {
                    val safeReplacement = if (isRegex) replacement else java.util.regex.Matcher.quoteReplacement(replacement)
                    val replacedText = pattern.matcher(originalText).replaceAll(safeReplacement)
                    // Atomic write via temp file
                    val tempFile = File(file.parentFile, "${file.name}.tmp_${System.currentTimeMillis()}")
                    tempFile.writeText(replacedText, Charsets.UTF_8)
                    if (tempFile.renameTo(file) || (file.delete() && tempFile.renameTo(file))) {
                        filesModified++
                        totalReplacements += countInFile
                    } else {
                        tempFile.delete()
                    }
                }
            } catch (_: Throwable) {
                // Ignore file write failure
            }
        }

        BatchReplaceResult(filesModified, totalReplacements)
    }
}
