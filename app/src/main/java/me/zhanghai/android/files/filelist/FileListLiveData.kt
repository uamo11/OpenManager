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
import me.zhanghai.android.files.util.valueCompat
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
                    Success(fileList as List<FileItem>)
                }
            } catch (e: Exception) {
                // Secondary fallback: if newDirectoryStream fails or throws Syscall/Permission error,
                // try standard java.io.File.listFiles() so readable system directories like /, /vendor, /system
                // can still display their accessible entries.
                val fallbackList = mutableListOf<FileItem>()
                val localFile = try {
                    (path as? LinuxPath)?.toFile() ?: java.io.File(path.toString())
                } catch (_: Throwable) {
                    null
                }
                val files = localFile?.listFiles()
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
                    Failure(valueCompat.value, e)
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
