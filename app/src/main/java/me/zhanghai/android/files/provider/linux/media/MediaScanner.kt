/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.linux.media

import android.media.MediaScannerConnection
import android.mtp.MtpConstants
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java8.nio.channels.FileChannel
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.app.contentResolver
import me.zhanghai.android.files.hiddenapi.RestrictedHiddenApi
import me.zhanghai.android.files.provider.common.DelegateFileChannel
import me.zhanghai.android.files.provider.root.isRunningAsRoot
import me.zhanghai.android.files.util.lazyReflectedMethod
import java.io.File
import java.io.IOException

/*
 * @see com.android.internal.content.FileSystemProvider
 * @see com.android.providers.media.scan.ModernMediaScanner.java
 */
object MediaScanner {
    /**
     * Tells the media scanner about [file]. Requests are collected for a moment and sent as one
     * batch, and only one batch is in flight at a time: many overlapping scanFile() calls make
     * Android release its MediaProvider connection twice ("stableCount < 0"), which crashes the
     * app from a system thread on recent Samsung/Android versions.
     */
    fun scan(file: File, isDeleted: Boolean = false) {
        if (isRunningAsRoot) {
            return
        }
        if (me.zhanghai.android.files.BuildConfig.DEBUG) {
            android.util.Log.d("OMMediaScan", "queued ${file.path} deleted=$isDeleted")
        }
        synchronized(lock) {
            val path = file.path
            pending[path] = (pending[path] == true) || isDeleted
            if (!flushScheduled) {
                flushScheduled = true
                scanHandler.postDelayed(::flush, BATCH_DELAY_MS)
            }
        }
    }

    private val lock = Any()
    private val pending = LinkedHashMap<String, Boolean>()
    private var flushScheduled = false
    private var inFlightGeneration = 0
    private var inFlight = false

    private val scanHandler by lazy {
        val thread = HandlerThread("MediaScanQueue")
        thread.start()
        Handler(thread.looper)
    }

    private fun flush() {
        val batch: Map<String, Boolean>
        val generation: Int
        synchronized(lock) {
            if (inFlight) {
                // The previous batch is still being scanned: try again shortly.
                scanHandler.postDelayed(::flush, BATCH_DELAY_MS)
                return
            }
            flushScheduled = false
            if (pending.isEmpty()) return
            batch = LinkedHashMap(pending)
            pending.clear()
            inFlight = true
            generation = ++inFlightGeneration
        }
        var remaining = batch.size
        try {
            MediaScannerConnection.scanFile(application, batch.keys.toTypedArray(), null) { path, _ ->
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && batch[path] == true) {
                    // ModernMediaScanner has a bug on Android 10 that may prevent it from removing
                    // certain files after their deletion. This has been fixed on Android 11 by
                    // https://android.googlesource.com/platform/packages/providers/MediaProvider/+/637d133d90f49dd18bda5de219184bfa9d6c2deb
                    // , but we still have to work around it for Android 10 by always trying to
                    // delete the MediaStore entry ourselves.
                    deleteMediaStoreEntryAsync(File(path))
                }
                synchronized(lock) {
                    if (--remaining <= 0 && inFlightGeneration == generation) inFlight = false
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace()
            synchronized(lock) { if (inFlightGeneration == generation) inFlight = false }
        }
        // Never wait forever for callbacks that may not come.
        scanHandler.postDelayed({
            synchronized(lock) { if (inFlightGeneration == generation) inFlight = false }
        }, BATCH_TIMEOUT_MS)
    }

    private const val BATCH_DELAY_MS = 500L
    private const val BATCH_TIMEOUT_MS = 15_000L

    @get:RequiresApi(Build.VERSION_CODES.Q)
    private val deleteMediaStoreEntryHandler by lazy {
        val thread = HandlerThread("DeleteMediaStoreEntry")
        thread.start()
        Handler(thread.looper)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun deleteMediaStoreEntryAsync(file: File) {
        deleteMediaStoreEntryHandler.post {
            try {
                deleteMediaStoreEntrySync(file)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @RestrictedHiddenApi
    @get:RequiresApi(Build.VERSION_CODES.Q)
    private val mediaStoreGetVolumeName by lazyReflectedMethod(
        MediaStore::class.java, "getVolumeName", File::class.java
    )

    // @see com.android.providers.media.scan.ModernMediaScanner.reconcileAndClean
    // @see https://android.googlesource.com/platform/packages/providers/MediaProvider/+/android10-release/src/com/android/providers/media/scan/ModernMediaScanner.java
    // @see https://android.googlesource.com/platform/packages/providers/MediaProvider/+/android11-release/src/com/android/providers/media/scan/ModernMediaScanner.java
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun deleteMediaStoreEntrySync(file: File) {
        val file = file.canonicalFile
        val volumeName = mediaStoreGetVolumeName.invoke(null, file) as String
        val uri = MediaStore.Files.getContentUri(volumeName)
            .buildUpon()
            .appendQueryParameter("includePending", "1")
            .appendQueryParameter("deletedata", "false")
            .build()
        @Suppress("DEPRECATION")
        val where = "ifnull(format, ${MtpConstants.FORMAT_UNDEFINED}) != ${
            MtpConstants.FORMAT_ABSTRACT_AV_PLAYLIST} AND ${MediaStore.Files.FileColumns.DATA} = ?"
        val selectionArgs = arrayOf(file.absolutePath)
        contentResolver.delete(uri, where, selectionArgs)
    }

    fun createScanOnCloseFileChannel(fileChannel: FileChannel, file: File): FileChannel =
        if (isRunningAsRoot) {
            fileChannel
        } else {
            object : DelegateFileChannel(fileChannel) {
                @Throws(IOException::class)
                override fun implCloseChannel() {
                    super.implCloseChannel()

                    scan(file)
                }
            }
        }
}
