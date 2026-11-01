/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.util.LruCache
import java8.nio.file.DirectoryIteratorException
import java8.nio.file.Path
import java8.nio.file.Paths
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.createFallbackFileItem
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.provider.common.newDirectoryStream
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.provider.linux.LinuxPath
import me.zhanghai.android.files.util.CloseableLiveData
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * In-memory LRU snapshot cache of directory listings so navigating back/up or switching
 * between panels displays the last known folder contents immediately (0ms blank screen)
 * while the background directory stream refreshes.
 */
object DirectorySnapshotCache {
    private val cache = LruCache<String, List<FileItem>>(80)

    fun get(path: Path): List<FileItem>? {
        val key = path.toString()
        synchronized(cache) {
            return cache.get(key)
        }
    }

    fun put(path: Path, items: List<FileItem>) {
        val key = path.toString()
        synchronized(cache) {
            cache.put(key, items)
        }
    }
}

class FileListLiveData(private val path: Path) : CloseableLiveData<Stateful<List<FileItem>>>() {
    private var future: Future<*>? = null

    private val observer: PathObserver

    @Volatile
    private var isChangedWhileInactive = false

    init {
        loadValue()
        observer = PathObserver(path) { onChangeObserved() }
    }

    fun loadValue() {
        future?.cancel(true)
        val currentOrCached = value?.value ?: DirectorySnapshotCache.get(path)
        value = Loading(currentOrCached)
        future = DIRECTORY_LOAD_EXECUTOR.submit {
            val resultState = try {
                path.newDirectoryStream().use { directoryStream ->
                    val fileList = ArrayList<FileItem>(64)
                    for (childPath in directoryStream) {
                        if (Thread.currentThread().isInterrupted) return@submit
                        try {
                            fileList.add(childPath.loadFileItem())
                        } catch (e: DirectoryIteratorException) {
                            childPath.createFallbackFileItem()?.let { fileList.add(it) }
                        } catch (e: IOException) {
                            childPath.createFallbackFileItem()?.let { fileList.add(it) }
                        } catch (e: Exception) {
                            childPath.createFallbackFileItem()?.let { fileList.add(it) }
                        }
                    }
                    if (fileList.isEmpty()) {
                        val virtualEntries = UnprivilegedDirectoryResolver.resolveEntriesForPath(path)
                        if (virtualEntries.isNotEmpty()) {
                            fileList.addAll(virtualEntries)
                        }
                    }
                    DirectorySnapshotCache.put(path, fileList)
                    Success(fileList as List<FileItem>)
                }
            } catch (e: Exception) {
                val fallbackList = ArrayList<FileItem>(64)
                val localFile = try {
                    (path as? LinuxPath)?.toFile() ?: java.io.File(path.toString())
                } catch (_: Throwable) {
                    null
                }
                val files = try { localFile?.listFiles() } catch (_: Throwable) { null }
                if (files != null && files.isNotEmpty()) {
                    for (file in files) {
                        if (Thread.currentThread().isInterrupted) return@submit
                        val childPath: Path = (path as? LinuxPath)?.resolve(file.name.toByteString())
                            ?: Paths.get(file.absolutePath)
                        try {
                            fallbackList.add(childPath.loadFileItem())
                        } catch (_: Throwable) {
                            childPath.createFallbackFileItem(file)?.let { fallbackList.add(it) }
                        }
                    }
                    DirectorySnapshotCache.put(path, fallbackList)
                    Success(fallbackList as List<FileItem>)
                } else {
                    // Immediately consult UnprivilegedDirectoryResolver (MT Manager style virtual/known hierarchy)
                    // without spawning a blocking 800ms `ls` subprocess that shares the same unprivileged UID.
                    val virtualEntries = UnprivilegedDirectoryResolver.resolveEntriesForPath(path)
                    if (virtualEntries.isNotEmpty()) {
                        DirectorySnapshotCache.put(path, virtualEntries)
                        Success(virtualEntries)
                    } else {
                        val isAccessDenied = e is java8.nio.file.AccessDeniedException ||
                            e is me.zhanghai.android.files.provider.remote.RemoteFileSystemException ||
                            e.message?.contains("Permission denied", ignoreCase = true) == true ||
                            e.message?.contains("EACCES", ignoreCase = true) == true ||
                            e.message?.contains("Root", ignoreCase = true) == true
                        if (isAccessDenied && path is LinuxPath) {
                            // Show read-only empty directory view with ".." navigation instead of a blocking "No tienes acceso" error screen
                            Success(emptyList())
                        } else {
                            Failure(value?.value, e)
                        }
                    }
                }
            }
            postValue(resultState)
        }
    }

    private fun onChangeObserved() {
        if (hasActiveObservers()) {
            loadValue()
        } else {
            isChangedWhileInactive = true
        }
    }

    override fun onActive() {
        if (isChangedWhileInactive) {
            loadValue()
            isChangedWhileInactive = false
        }
    }

    override fun close() {
        observer.close()
        future?.cancel(true)
    }

    companion object {
        private val DIRECTORY_LOAD_EXECUTOR: ExecutorService = Executors.newFixedThreadPool(4)
    }
}
