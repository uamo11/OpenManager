/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.AsyncTask
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
import java.util.concurrent.Future

class FileListLiveData(private val path: Path) : CloseableLiveData<Stateful<List<FileItem>>>() {
    private var future: Future<Unit>? = null

    private val observer: PathObserver

    @Volatile
    private var isChangedWhileInactive = false

    init {
        loadValue()
        observer = PathObserver(path) { onChangeObserved() }
    }

    fun loadValue() {
        future?.cancel(true)
        value = Loading(value?.value)
        future = (AsyncTask.THREAD_POOL_EXECUTOR as ExecutorService).submit<Unit> {
            val value = try {
                path.newDirectoryStream().use { directoryStream ->
                    val fileList = mutableListOf<FileItem>()
                    for (childPath in directoryStream) {
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
                    Success(fileList as List<FileItem>)
                }
            } catch (e: Exception) {
                val fallbackList = mutableListOf<FileItem>()
                val localFile = try {
                    (path as? LinuxPath)?.toFile() ?: java.io.File(path.toString())
                } catch (_: Throwable) {
                    null
                }
                val files = try { localFile?.listFiles() } catch (_: Throwable) { null }
                if (files != null && files.isNotEmpty()) {
                    for (file in files) {
                        val childPath: Path = (path as? LinuxPath)?.resolve(file.name.toByteString())
                            ?: Paths.get(file.absolutePath)
                        try {
                            fallbackList.add(childPath.loadFileItem())
                        } catch (_: Throwable) {
                            childPath.createFallbackFileItem(file)?.let { fallbackList.add(it) }
                        }
                    }
                    Success(fallbackList as List<FileItem>)
                } else {
                    val shellEntries = try {
                        val process = ProcessBuilder("ls", "-1a", path.toString())
                            .redirectErrorStream(true)
                            .start()
                        val completed = process.waitFor(800, java.util.concurrent.TimeUnit.MILLISECONDS)
                        if (completed && process.exitValue() == 0) {
                            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
                            val names = mutableListOf<String>()
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                val trimmed = line?.trim()
                                if (!trimmed.isNullOrEmpty() && trimmed != "." && trimmed != "..") {
                                    names.add(trimmed)
                                }
                            }
                            names
                        } else {
                            process.destroyForcibly()
                            null
                        }
                    } catch (_: Throwable) {
                        null
                    }

                    if (shellEntries != null && shellEntries.isNotEmpty()) {
                        for (name in shellEntries) {
                            val file = java.io.File(path.toString(), name)
                            val childPath: Path = (path as? LinuxPath)?.resolve(name.toByteString())
                                ?: Paths.get(file.absolutePath)
                            try {
                                fallbackList.add(childPath.loadFileItem())
                            } catch (_: Throwable) {
                                childPath.createFallbackFileItem(file)?.let { fallbackList.add(it) }
                            }
                        }
                        Success(fallbackList as List<FileItem>)
                    } else {
                        // Consult UnprivilegedDirectoryResolver (MT Manager style virtual/known hierarchy)
                        val virtualEntries = UnprivilegedDirectoryResolver.resolveEntriesForPath(path)
                        if (virtualEntries.isNotEmpty()) {
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
            }
            postValue(value)
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
}
