/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider

import java8.nio.file.Files
import java8.nio.file.ProviderNotFoundException
import java8.nio.file.spi.FileSystemProvider
import me.zhanghai.android.files.provider.archive.ArchiveFileSystemProvider
import me.zhanghai.android.files.provider.common.AndroidFileTypeDetector
import me.zhanghai.android.files.provider.content.ContentFileSystemProvider
import me.zhanghai.android.files.provider.document.DocumentFileSystemProvider
import me.zhanghai.android.files.provider.ftp.FtpFileSystemProvider
import me.zhanghai.android.files.provider.ftp.FtpesFileSystemProvider
import me.zhanghai.android.files.provider.ftp.FtpsFileSystemProvider
import me.zhanghai.android.files.provider.linux.LinuxFileSystemProvider
import me.zhanghai.android.files.provider.root.isRunningAsRoot
import me.zhanghai.android.files.provider.sftp.SftpFileSystemProvider
import me.zhanghai.android.files.provider.smb.SmbFileSystemProvider
import me.zhanghai.android.files.provider.webdav.WebDavFileSystemProvider
import me.zhanghai.android.files.provider.webdav.WebDavsFileSystemProvider

object FileSystemProviders {
    /**
     * If set, WatchService implementations will skip processing any event data and simply send an
     * overflow event to all the registered keys upon successful read from the inotify fd. This can
     * help reducing the JNI and GC overhead when large amount of inotify events are generated.
     * Simply sending an overflow event to all the keys is okay because we use only one key per
     * service for WatchServicePathObservable.
     */
    @Volatile
    var overflowWatchEvents = false

    fun install() {
        // Debug builds log what each provider costs (tag OMStartup): together they are most of
        // the app's own start-up work on the main thread.
        val timed = me.zhanghai.android.files.BuildConfig.DEBUG
        fun step(name: String, block: () -> Unit) {
            if (!timed) return block()
            val start = android.os.SystemClock.elapsedRealtimeNanos()
            block()
            val us = (android.os.SystemClock.elapsedRealtimeNanos() - start) / 1_000
            android.util.Log.i("OMStartup", "  provider $name: ${us / 1000}.${us % 1000 / 100} ms")
        }
        step("linux") { FileSystemProvider.installDefaultProvider(LinuxFileSystemProvider) }
        step("archive") { FileSystemProvider.installProvider(ArchiveFileSystemProvider) }
        if (!isRunningAsRoot) {
            step("content") { FileSystemProvider.installProvider(ContentFileSystemProvider) }
            step("document") { FileSystemProvider.installProvider(DocumentFileSystemProvider) }
            step("ftp") { FileSystemProvider.installProvider(FtpFileSystemProvider) }
            step("ftps") { FileSystemProvider.installProvider(FtpsFileSystemProvider) }
            step("ftpes") { FileSystemProvider.installProvider(FtpesFileSystemProvider) }
            step("sftp") { FileSystemProvider.installProvider(SftpFileSystemProvider) }
            step("smb") { FileSystemProvider.installProvider(SmbFileSystemProvider) }
            step("webdav") { FileSystemProvider.installProvider(WebDavFileSystemProvider) }
            step("webdavs") { FileSystemProvider.installProvider(WebDavsFileSystemProvider) }
        }
        step("type detector") { Files.installFileTypeDetector(AndroidFileTypeDetector) }
    }

    operator fun get(scheme: String): FileSystemProvider {
        for (provider in FileSystemProvider.installedProviders()) {
            if (provider.scheme.equals(scheme, ignoreCase = true)) {
                return provider
            }
        }
        throw ProviderNotFoundException(scheme)
    }
}
