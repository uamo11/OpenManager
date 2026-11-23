/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.remote

import android.content.Intent
import java8.nio.file.Files
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.provider.sftp.client.Authentication
import me.zhanghai.android.files.provider.sftp.client.Authority
import me.zhanghai.android.files.provider.sftp.client.Client
import me.zhanghai.android.files.provider.sftp.client.PasswordAuthentication
import me.zhanghai.android.files.provider.sftp.client.PublicKeyAuthentication
import me.zhanghai.android.files.provider.sftp.createSftpRootPath
import me.zhanghai.android.files.provider.sftp.SftpPath
import me.zhanghai.android.files.storage.SftpServer
import me.zhanghai.android.files.storage.SftpServerAuthenticator

/**
 * Opens a connected VPS in the dual-panel explorer over SFTP, with the profile's credentials:
 * the VPS on one side and the phone on the other, and the built-in viewers for its photos,
 * music, video and text. Reuses the app's SFTP client, so it adds nothing to the APK.
 */
object RemoteFilesLauncher {

    fun authority(profile: RemoteProfile): Authority = Authority(profile.host.trim(), profile.port, profile.username)

    /** Credentials for SFTP from the profile, or null when a password has to be asked first. */
    fun authentication(profile: RemoteProfile, typedPassword: String? = null): Authentication? {
        val key = profile.privateKey
        if (profile.authType == RemoteAuthType.PRIVATE_KEY && !key.isNullOrBlank()) {
            return PublicKeyAuthentication(key, profile.keyPassphrase?.takeIf { it.isNotEmpty() })
        }
        val password = typedPassword ?: profile.password
        return password?.takeIf { it.isNotEmpty() }?.let { PasswordAuthentication(it) }
    }

    /**
     * Folder to start in, from the shell title ("user@host: ~/dir", "~/dir" or "/srv/app").
     * Pure, for tests: `~` is returned as-is and resolved against the home folder later.
     */
    fun remoteDirFromTitle(title: String?): String? {
        val raw = title?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val afterHost = if (raw.contains(": ")) raw.substringAfter(": ") else raw
        val dir = afterHost.trim()
        return dir.takeIf { it.startsWith("~") || it.startsWith("/") }
    }

    /**
     * Connects (verifying the host key like the terminal), resolves the start folder and returns it,
     * or throws with the reason (wrong password, unreachable host...).
     */
    suspend fun resolveStartDirectory(profile: RemoteProfile, auth: Authentication, title: String?): Path =
        withContext(Dispatchers.IO) {
            val authority = authority(profile)
            // Kept in memory for this run only: nothing is added to the saved storages.
            SftpServerAuthenticator.addTransientServer(
                SftpServer(null, profile.displayName, authority, auth, "")
            )
            val root = authority.createSftpRootPath()
            val home: Path = runCatching {
                Client.realpath(root.fileSystem.getPath(".") as SftpPath) as Path
            }.getOrNull() ?: root
            val wanted = remoteDirFromTitle(title)
            val candidate = when {
                wanted == null || wanted == "~" -> home
                wanted.startsWith("~/") -> home.resolve(wanted.removePrefix("~/"))
                else -> root.resolve(wanted)
            }
            when {
                Files.isDirectory(candidate) -> candidate
                Files.isDirectory(home) -> home
                // Also surfaces the connection/auth error to the caller.
                else -> {
                    Files.newDirectoryStream(root).close()
                    root
                }
            }
        }

    fun explorerIntent(dir: Path): Intent = FileListActivity.createViewIntent(dir)
}
