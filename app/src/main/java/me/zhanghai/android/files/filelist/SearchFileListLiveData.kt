/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.AsyncTask
import java8.nio.file.Path
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.provider.common.newDirectoryStream
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.search
import me.zhanghai.android.files.util.CloseableLiveData
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.valueCompat
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

class SearchFileListLiveData(
    private val path: Path,
    private val query: String
) : CloseableLiveData<Stateful<List<FileItem>>>() {
    private var future: Future<Unit>? = null

    init {
        loadValue()
    }

    fun loadValue() {
        future?.cancel(true)
        if (me.zhanghai.android.files.BuildConfig.DEBUG) android.util.Log.d("OMSearch", "Search start query=$query")
        value = Loading(emptyList())
        future = (AsyncTask.THREAD_POOL_EXECUTOR as ExecutorService).submit<Unit> {
            val fileList = mutableListOf<FileItem>()
            val search = SearchQuery.decode(query)
            try {
                val onPaths = { paths: List<Path> ->
                    for (path in paths) {
                        if (Thread.currentThread().isInterrupted) break
                        val fileItem = try {
                            path.loadFileItem()
                        } catch (e: IOException) {
                            e.printStackTrace()
                            // TODO: Support file without information.
                            continue
                        }
                        if (accepts(search, fileItem)) fileList.add(fileItem)
                    }
                    postValue(Loading(fileList.toList()))
                }
                if (search.recursive) {
                    // A plain name goes to the provider's own (fast) search; anything more lists
                    // every file and is filtered here.
                    path.search(search.providerText, INTERVAL_MILLIS, onPaths)
                } else {
                    // This folder only.
                    val children = path.newDirectoryStream().use { it.toList() }
                    onPaths(children)
                }
                postValue(Success(fileList))
            } catch (e: Exception) {
                // TODO: Retrieval of previous value is racy.
                postValue(Failure(valueCompat.value, e))
            }
        }
    }

    /** Name (when the provider did not filter it), size and contents of [item]. */
    private fun accepts(search: SearchQuery, item: FileItem): Boolean {
        val name = item.path.fileName?.toString() ?: return false
        if (!search.isPlain && !search.matchesName(name)) return false
        val sized = search.minSize != null || search.maxSize != null
        if (sized || search.needsContent) {
            // Size and contents are about files, not folders.
            if (!item.attributes.isRegularFile) return false
            if (sized && !search.matchesSize(item.attributes.size())) return false
            if (search.needsContent && !contentMatches(search, item)) return false
        }
        return true
    }

    private fun contentMatches(search: SearchQuery, item: FileItem): Boolean {
        if (item.attributes.size() > MAX_CONTENT_BYTES) return false
        return try {
            val bytes = item.path.newInputStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (out.size() < MAX_CONTENT_BYTES) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            // Binary files (a NUL early on) are not text to search.
            if (bytes.take(8192).any { it == 0.toByte() }) return false
            search.matchesContent(String(bytes, Charsets.UTF_8))
        } catch (e: IOException) {
            false
        }
    }

    override fun close() {
        future?.cancel(true)
    }

    companion object {
        private const val INTERVAL_MILLIS = 500L
        private const val MAX_CONTENT_BYTES = 32L * 1024 * 1024
    }
}
