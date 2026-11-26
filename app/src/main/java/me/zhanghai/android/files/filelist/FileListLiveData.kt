/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import androidx.collection.LruCache
import java8.nio.file.DirectoryIteratorException
import java8.nio.file.Path
import java8.nio.file.Paths
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.createFallbackFileItem
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.provider.common.newDirectoryStream
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.provider.linux.LinuxPath
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.util.CloseableLiveData
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.IdleThreadPools
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-memory LRU snapshot cache of directory listings so navigating back/up or switching
 * between panels displays the last known folder contents immediately (0ms blank screen)
 * while the background directory stream refreshes.
 */
object DirectorySnapshotCache {
    const val MAX_CACHED_ITEMS = 12_000
    const val MAX_SINGLE_DIR_SNAPSHOT_ITEMS = 4_000

    @Volatile
    private var ultraLowRamMode = false

    private val cache = object : LruCache<String, List<FileItem>>(MAX_CACHED_ITEMS) {
        override fun sizeOf(key: String, value: List<FileItem>): Int = value.size.coerceAtLeast(1)
    }

    fun setUltraLowRamMode(enabled: Boolean) {
        ultraLowRamMode = enabled
        if (enabled) {
            clear()
        }
    }

    fun isUltraLowRamMode(): Boolean = ultraLowRamMode

    private fun getKey(path: Path): String =
        try {
            path.toUri().toString()
        } catch (_: Throwable) {
            path.toString()
        }

    fun get(path: Path): List<FileItem>? {
        if (ultraLowRamMode) return null
        val key = getKey(path)
        synchronized(cache) {
            return cache.get(key)
        }
    }

    fun put(path: Path, items: List<FileItem>) {
        if (ultraLowRamMode) return
        val key = getKey(path)
        synchronized(cache) {
            if (items.size > MAX_SINGLE_DIR_SNAPSHOT_ITEMS) {
                cache.remove(key)
                return
            }
            cache.put(key, items)
        }
    }

    fun invalidate(path: Path) {
        val key = getKey(path)
        synchronized(cache) {
            cache.remove(key)
        }
    }

    fun clear() {
        synchronized(cache) {
            cache.evictAll()
        }
    }

    /** Drops every listing under [uriPrefix] (a whole server that was disconnected). */
    fun invalidateUnder(uriPrefix: String) {
        synchronized(cache) {
            for (key in cache.snapshot().keys) {
                if (key.startsWith(uriPrefix)) cache.remove(key)
            }
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

    private fun loadChildPathItem(childPath: Path): FileItem? =
        try {
            childPath.loadFileItem()
        } catch (e: DirectoryIteratorException) {
            childPath.createFallbackFileItem()
        } catch (e: IOException) {
            childPath.createFallbackFileItem()
        } catch (e: Exception) {
            childPath.createFallbackFileItem()
        }

    fun loadValue() {
        future?.cancel(true)
        val currentOrCached = value?.value ?: DirectorySnapshotCache.get(path)
        value = Loading(currentOrCached)
        future = DIRECTORY_LOAD_EXECUTOR.submit {
            val resultState = try {
                val childPaths = ArrayList<Path>(128)
                path.newDirectoryStream().use { directoryStream ->
                    for (childPath in directoryStream) {
                        if (Thread.currentThread().isInterrupted) return@submit
                        childPaths.add(childPath)
                    }
                }
                val fileList = ArrayList<FileItem>(childPaths.size.coerceAtLeast(16))
                // On a server each symlink costs two round trips (readlink + stat); with the
                // network as the bottleneck, many requests in flight beat a few CPU threads.
                val onServer = isServerPath(path)
                val threshold = if (onServer) REMOTE_PARALLEL_LOAD_THRESHOLD else PARALLEL_LOAD_THRESHOLD
                val executor = if (onServer) REMOTE_ITEM_LOADER_EXECUTOR else ITEM_LOADER_EXECUTOR
                if (childPaths.size < threshold) {
                    for (i in 0 until childPaths.size) {
                        if (Thread.currentThread().isInterrupted) return@submit
                        loadChildPathItem(childPaths[i])?.let { fileList.add(it) }
                    }
                } else {
                    val workerCount = if (onServer) REMOTE_WORKER_COUNT else PARALLEL_WORKER_COUNT
                    val total = childPaths.size
                    val chunkSize = (total + workerCount - 1) / workerCount
                    val cancelled = AtomicBoolean(false)
                    val chunkFutures = ArrayList<Future<ArrayList<FileItem>>>(workerCount)
                    var start = 0
                    while (start < total) {
                        val from = start
                        val to = minOf(start + chunkSize, total)
                        chunkFutures.add(executor.submit<ArrayList<FileItem>> {
                            val chunkResult = ArrayList<FileItem>(to - from)
                            for (i in from until to) {
                                if (cancelled.get() || Thread.currentThread().isInterrupted) {
                                    break
                                }
                                loadChildPathItem(childPaths[i])?.let { chunkResult.add(it) }
                            }
                            chunkResult
                        })
                        start = to
                    }
                    try {
                        for (i in 0 until chunkFutures.size) {
                            if (Thread.currentThread().isInterrupted) {
                                throw InterruptedException()
                            }
                            fileList.addAll(chunkFutures[i].get())
                        }
                    } catch (ie: InterruptedException) {
                        cancelled.set(true)
                        for (f in chunkFutures) f.cancel(true)
                        return@submit
                    } catch (t: Throwable) {
                        cancelled.set(true)
                        for (f in chunkFutures) f.cancel(true)
                        throw t
                    }
                }
                if (fileList.isEmpty() && path.isLinuxPath && !path.isArchivePath) {
                    val virtualEntries = UnprivilegedDirectoryResolver.resolveEntriesForPath(path)
                    if (virtualEntries.isNotEmpty()) {
                        fileList.addAll(virtualEntries)
                    }
                }
                DirectorySnapshotCache.put(path, fileList)
                Success(fileList as List<FileItem>)
            } catch (e: Exception) {
                if (path.isArchivePath) {
                    val resultState = Failure(value?.value, e)
                    postValue(resultState)
                    return@submit
                }
                val localFile = (path as? LinuxPath)?.toFile()
                val files = try { localFile?.listFiles() } catch (_: Throwable) { null }
                if (files != null && files.isNotEmpty()) {
                    val fallbackList = ArrayList<FileItem>(files.size)
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
                } else if (path.isLinuxPath && !path.isArchivePath) {
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
                        if (isAccessDenied) {
                            // Show read-only empty directory view with ".." navigation instead of a blocking "No tienes acceso" error screen
                            Success(emptyList())
                        } else {
                            Failure(value?.value, e)
                        }
                    }
                } else {
                    Failure(value?.value, e)
                }
            }
            if (!Thread.currentThread().isInterrupted) {
                postValue(resultState)
            }
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
        private const val PARALLEL_LOAD_THRESHOLD = 256
        private val PARALLEL_WORKER_COUNT =
            Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        // Threads end when idle (IdleThreadPools): fixed pools kept them for the whole process.
        private val DIRECTORY_LOAD_EXECUTOR: ExecutorService =
            IdleThreadPools.fixed("OMDirLoad", 4)
        private val ITEM_LOADER_EXECUTOR: ExecutorService =
            IdleThreadPools.fixed("OMItemLoad", PARALLEL_WORKER_COUNT)
        private const val REMOTE_PARALLEL_LOAD_THRESHOLD = 24
        private const val REMOTE_WORKER_COUNT = 24
        private val REMOTE_ITEM_LOADER_EXECUTOR: ExecutorService =
            IdleThreadPools.fixed("OMRemoteLoad", REMOTE_WORKER_COUNT)
    }
}
