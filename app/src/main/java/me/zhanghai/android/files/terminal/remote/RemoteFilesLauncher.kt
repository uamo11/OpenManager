/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.remote

import android.content.Context
import android.content.Intent
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java8.nio.file.Files
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
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
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.UserAuthException

/**
 * Opens a connected VPS in the dual-panel explorer over SFTP, with the profile's credentials:
 * the VPS on one side and the phone on the other, and the built-in viewers for its photos,
 * music, video and text. Reuses the app's SFTP client, so it adds nothing to the APK.
 */
object RemoteFilesLauncher {

    /** Home folder of each VPS opened this run, for the explorer's home button. */
    private val homes = ConcurrentHashMap<Authority, Path>()

    /** Home folder of the VPS [path] belongs to, or null if it is not a VPS opened from a session. */
    fun homeOf(path: Path): Path? {
        val authority = (path as? Client.Path)?.authority ?: return null
        return homes[authority] ?: path.root
    }

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
    suspend fun resolveStartDirectory(
        profile: RemoteProfile,
        auth: Authentication?,
        title: String?,
        sharedConnection: SSHClient? = null
    ): Path =
        withContext(Dispatchers.IO) {
            val authority = authority(profile)
            SftpServerAuthenticator.setTransientName(authority, profile.displayName)
            // Best path: an SFTP channel on the terminal's own connection (already logged in,
            // host key already checked, kept alive by the terminal).
            sharedConnection?.let { Client.shareConnection(authority, it) }
            // Fallback credentials, kept in memory for this run only: nothing is added to the
            // saved storages.
            if (auth != null) {
                SftpServerAuthenticator.addTransientServer(
                    SftpServer(null, profile.displayName, authority, auth, "")
                )
            }
            try {
                openAndRead(authority, title)
            } catch (e: Exception) {
                // A wrong password is not retried: servers count failed attempts.
                if (generateSequence<Throwable>(e) { it.cause }.any { it is UserAuthException }) throw e
                // A connection left over from before a network change or server restart fails with
                // odd internal errors: retry once over a fresh one before reporting anything.
                Client.disconnect(authority)
                openAndRead(authority, title)
            }
        }

    /** Resolves the start folder and actually lists it, so the explorer only opens if it can read. */
    private fun openAndRead(authority: Authority, title: String?): Path {
        val root = authority.createSftpRootPath()
        val home: Path = runCatching {
            Client.realpath(root.fileSystem.getPath(".") as SftpPath) as Path
        }.getOrNull() ?: root
        homes[authority] = home
        val wanted = remoteDirFromTitle(title)
        val candidate = when {
            wanted == null || wanted == "~" -> home
            wanted.startsWith("~/") -> home.resolve(wanted.removePrefix("~/"))
            else -> root.resolve(wanted)
        }
        val start = when {
            Files.isDirectory(candidate) -> candidate
            Files.isDirectory(home) -> home
            else -> root
        }
        // Throws the connection, permission or authentication error instead of opening an
        // explorer that cannot show anything.
        Files.newDirectoryStream(start).use { it.iterator().hasNext() }
        return start
    }

    /** A message the user can act on, instead of the SSH library's internal error. */
    fun describeError(context: Context, profile: RemoteProfile, error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.toList()
        return when {
            chain.any { it is UserAuthException } ->
                context.getString(R.string.om_remote_error_auth, profile.username)
            chain.any { it is UnknownHostException } ->
                context.getString(R.string.om_remote_error_unknown_host, profile.host)
            chain.any { it is SocketTimeoutException } ->
                context.getString(R.string.om_remote_error_timeout, profile.endpoint)
            chain.any { it is ConnectException } ->
                context.getString(R.string.om_remote_error_refused, profile.endpoint)
            chain.any { it is java8.nio.file.AccessDeniedException } ->
                context.getString(R.string.om_remote_files_error_denied)
            else -> context.getString(R.string.om_remote_files_error_lost, profile.displayName)
        }
    }

    /** Explorer at [dir]; back from there returns to the terminal instead of climbing the VPS. */
    fun explorerIntent(dir: Path): Intent = FileListActivity.createViewIntent(dir)
        .putExtra(FileListActivity.EXTRA_RETURN_ON_BACK_AT, dir.toUri().toString())
}
