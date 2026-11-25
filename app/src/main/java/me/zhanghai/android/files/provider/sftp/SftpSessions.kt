/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp

import java8.nio.file.Path
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.filelist.DirectorySnapshotCache
import me.zhanghai.android.files.provider.archive.archiveFile
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.provider.sftp.client.Authority
import me.zhanghai.android.files.provider.sftp.client.Client

/** The SFTP server [this] path lives on (also for an archive opened from it), or null. */
val Path.sftpAuthority: Authority?
    get() = when {
        this is SftpPath -> authority
        isArchivePath -> runCatching { archiveFile.sftpAuthority }.getOrNull()
        else -> null
    }

/** The root of an SFTP server ("/" there): Back from it leaves the server. */
val Path.isSftpRoot: Boolean
    get() = this is SftpPath && parent == null

object SftpSessions {
    /**
     * Closes the file connection to [authority] and forgets its cached folder listings, once no
     * panel shows that server any more. A terminal's own SSH connection stays open; only its
     * SFTP channel is closed.
     */
    fun release(authority: Authority) {
        Client.disconnect(authority)
        runCatching { authority.createSftpRootPath().toUri().toString() }.getOrNull()
            ?.let { DirectorySnapshotCache.invalidateUnder(it) }
        if (BuildConfig.DEBUG) android.util.Log.d("OMSftp", "released $authority")
    }
}
