/*
 * Copyright (c) 2021 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp.client

import java8.nio.channels.SeekableByteChannel
import me.zhanghai.android.files.provider.common.LocalWatchService
import me.zhanghai.android.files.provider.common.NotifyEntryModifiedSeekableByteChannel
import me.zhanghai.android.files.util.closeSafe
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import java.io.IOException
import java.util.Collections
import java.util.WeakHashMap
import java8.nio.file.Path as Java8Path

object Client {
    @Volatile
    lateinit var authenticator: Authenticator

    private val clients = mutableMapOf<Authority, SFTPClient>()
    /** SSH connections opened here (closed with their SFTP client). */
    private val sshClients = mutableMapOf<Authority, SSHClient>()
    /** Connections owned by a terminal session: SFTP runs as a channel on them, never closed here. */
    private val sharedSshClients = mutableMapOf<Authority, SSHClient>()

    /**
     * Uses [ssh] (an open, authenticated terminal connection) for the files of [authority]:
     * an SFTP channel next to the shell instead of a second login. When it goes away, the next
     * operation falls back to a connection of its own.
     */
    fun shareConnection(authority: Authority, ssh: SSHClient) {
        synchronized(clients) {
            if (sharedSshClients[authority] === ssh && clients[authority]?.let { isAlive(authority, it) } == true) {
                return
            }
            closeLater(clients.remove(authority), sshClients.remove(authority))
            sharedSshClients[authority] = ssh
        }
    }

    /**
     * Drops the connection to [authority], so the next operation connects again. Used when the
     * link died (network change, server restart): a dead connection fails with odd internal errors.
     * A shared terminal connection is left alone; only its SFTP channel is closed.
     */
    fun disconnect(authority: Authority) {
        synchronized(clients) {
            closeLater(clients.remove(authority), sshClients.remove(authority))
            sharedSshClients[authority]?.let { if (!isUsable(it)) sharedSshClients -= authority }
        }
    }

    private fun isUsable(ssh: SSHClient): Boolean =
        ssh.isConnected && ssh.isAuthenticated && ssh.transport.isRunning

    private fun isAlive(authority: Authority, client: SFTPClient): Boolean {
        val ssh = sharedSshClients[authority] ?: sshClients[authority]
        return client.sftpEngine.subsystem.isOpen && (ssh == null || isUsable(ssh))
    }

    /** Closing over a dead link can wait for the server's reply: never on a caller's thread. */
    private val closer = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SftpCloser").apply { isDaemon = true }
    }

    private fun closeLater(client: SFTPClient?, ssh: SSHClient?) {
        if (client == null && ssh == null) return
        closer.execute {
            client?.let { SftpFailure.closeBroken(it) }
            ssh?.closeSafe()
        }
    }

    /**
     * [client] failed with [failure]: it is never handed out again. A connection whose link failed
     * is not reused either (the terminal's own one is only forgotten here, the terminal finds out
     * by itself), so the next call starts clean instead of failing with sshj's internal errors.
     */
    private fun dropBroken(authority: Authority, client: SFTPClient, failure: SftpFailure, error: Throwable) {
        var ownSsh: SSHClient? = null
        synchronized(clients) {
            if (clients[authority] === client) {
                clients -= authority
                ownSsh = sshClients.remove(authority)
                if (failure.dropsConnection) {
                    sharedSshClients -= authority
                } else {
                    // Only the channel is gone: a new one on the same connection is cheap.
                    ownSsh?.let { if (isUsable(it)) { sshClients[authority] = it; ownSsh = null } }
                }
            }
        }
        android.util.Log.w(TAG, "Dropping broken SFTP channel to ${authority.host} ($failure): $error")
        closeLater(client, ownSsh)
    }

    /**
     * Runs [block] on the client for [authority]. When the channel turns out broken it is replaced,
     * and the call is repeated once if that is safe: always for reads, and for changes only when
     * the request never left the phone.
     */
    private inline fun <T> call(authority: Authority, idempotent: Boolean, block: (SFTPClient) -> T): T {
        val first = getClient(authority)
        val failure = try {
            return block(first)
        } catch (e: Exception) {
            if (e !is IOException && e !is RuntimeException) throw e
            val kind = SftpFailure.of(e)
            if (!kind.dropsChannel) throw ClientException(e)
            dropBroken(authority, first, kind, e)
            // A change that may have reached the server is not sent twice.
            if (!idempotent && kind != SftpFailure.CORRUPT_CHANNEL) throw connectionLost(authority, e)
            kind
        }
        val second = getClient(authority)
        try {
            return block(second).also {
                android.util.Log.i(TAG, "Recovered from $failure on a fresh channel")
            }
        } catch (e: Exception) {
            if (e !is IOException && e !is RuntimeException) throw e
            val again = SftpFailure.of(e)
            if (!again.dropsChannel) throw ClientException(e)
            dropBroken(authority, second, again, e)
            throw connectionLost(authority, e)
        }
    }

    /** What the user reads (in their language) instead of sshj's internal error. */
    private fun connectionLost(authority: Authority, cause: Throwable): ClientException {
        val message = runCatching {
            val name = me.zhanghai.android.files.storage.SftpServerAuthenticator.displayName(authority)
                ?: authority.host
            me.zhanghai.android.files.app.application.getString(
                me.zhanghai.android.files.R.string.om_remote_files_error_lost, name
            )
        }.getOrNull() ?: "Connection to ${authority.host} lost"
        return ClientException(message, cause)
    }

    private const val TAG = "OMSftp"

    private val directoryFileAttributesCache =
        Collections.synchronizedMap(WeakHashMap<Path, FileAttributes>())

    @Throws(ClientException::class)
    fun access(path: Path, flags: Set<OpenMode>) {
        call(path.authority, isSideEffectFree(flags)) { client ->
            client.open(path.remotePath, flags, FileAttributes.EMPTY).close()
        }
    }

    @Throws(ClientException::class)
    fun lstat(path: Path): FileAttributes {
        synchronized(directoryFileAttributesCache) {
            directoryFileAttributesCache[path]?.let {
                return it.also { directoryFileAttributesCache -= path }
            }
        }
        return call(path.authority, idempotent = true) { it.lstat(path.remotePath) }
    }

    @Throws(ClientException::class)
    fun mkdir(path: Path, attributes: FileAttributes) {
        call(path.authority, idempotent = false) { it.sftpEngine.makeDir(path.remotePath, attributes) }
        LocalWatchService.onEntryCreated(path as Java8Path)
    }

    /** Opening without CREAT/TRUNC/EXCL changes nothing on the server, so it can be retried. */
    private fun isSideEffectFree(flags: Set<OpenMode>): Boolean =
        OpenMode.CREAT !in flags && OpenMode.TRUNC !in flags && OpenMode.EXCL !in flags

    @Throws(ClientException::class)
    fun openByteChannel(
        path: Path,
        flags: Set<OpenMode>,
        attributes: FileAttributes
    ): SeekableByteChannel {
        val authority = path.authority
        val (client, file) = call(authority, isSideEffectFree(flags)) {
            it to it.open(path.remotePath, flags, attributes)
        }
        // A read or write that breaks the link also breaks the channel the file is on.
        val onBroken = { error: Throwable ->
            val failure = SftpFailure.of(error)
            if (failure.dropsChannel) dropBroken(authority, client, failure, error)
        }
        return NotifyEntryModifiedSeekableByteChannel(
            FileByteChannel(file, flags.contains(OpenMode.APPEND), onBroken) {
                connectionLost(authority, IOException()).message.orEmpty()
            },
            path as Java8Path
        )
    }

    @Throws(ClientException::class)
    fun readlink(path: Path): String {
        return call(path.authority, idempotent = true) { it.readlink(path.remotePath) }
    }

    @Throws(ClientException::class)
    fun realpath(path: Path): Path {
        val realPath = call(path.authority, idempotent = true) { it.canonicalize(path.remotePath) }
        return path.resolve(realPath)
    }

    @Throws(ClientException::class)
    fun remove(path: Path) {
        val attributes = lstat(path)
        val isDirectory = attributes.type == FileMode.Type.DIRECTORY
        if (isDirectory) {
            rmdir(path)
        } else {
            unlink(path)
        }
    }

    // Note that unlike POSIX rename(), this won't overwrite an existing file.
    @Throws(ClientException::class)
    fun rename(path: Path, newPath: Path) {
        if (newPath.authority != path.authority) {
            throw ClientException(
                SFTPException(Response.StatusCode.FAILURE, "Paths aren't on the same authority")
            )
        }
        call(path.authority, idempotent = false) { it.rename(path.remotePath, newPath.remotePath) }
        directoryFileAttributesCache -= path
        directoryFileAttributesCache -= newPath
        LocalWatchService.onEntryDeleted(path as Java8Path)
        LocalWatchService.onEntryCreated(newPath as Java8Path)
    }

    @Throws(ClientException::class)
    fun rmdir(path: Path) {
        call(path.authority, idempotent = false) { it.rmdir(path.remotePath) }
        directoryFileAttributesCache -= path
        LocalWatchService.onEntryDeleted(path as Java8Path)
    }

    @Throws(ClientException::class)
    fun scandir(path: Path): List<Path> {
        val files = call(path.authority, idempotent = true) { it.ls(path.remotePath) }
        return files.map { file ->
            // The attributes here are from lstat().
            // https://github.com/openssh/openssh-portable/blob/71241fc05db4bbb11bb29340b44b92e2575373d8/sftp-server.c#L1110
            path.resolve(file.name).also { directoryFileAttributesCache[it] = file.attributes }
        }
    }

    @Throws(ClientException::class)
    fun setstat(path: Path, attributes: FileAttributes) {
        call(path.authority, idempotent = true) { it.setattr(path.remotePath, attributes) }
        directoryFileAttributesCache -= path
        LocalWatchService.onEntryModified(path as Java8Path)
    }

    @Throws(ClientException::class)
    fun stat(path: Path): FileAttributes {
        synchronized(directoryFileAttributesCache) {
            directoryFileAttributesCache[path]?.let {
                if (it.type != FileMode.Type.SYMLINK) {
                    return it.also { directoryFileAttributesCache -= path }
                }
            }
        }
        return call(path.authority, idempotent = true) { it.stat(path.remotePath) }
    }

    @Throws(ClientException::class)
    fun symlink(link: Path, target: String) {
        call(link.authority, idempotent = false) { it.symlink(link.remotePath, target) }
        LocalWatchService.onEntryCreated(link as Java8Path)
    }

    @Throws(ClientException::class)
    fun unlink(path: Path) {
        call(path.authority, idempotent = false) { it.rm(path.remotePath) }
        directoryFileAttributesCache -= path
        LocalWatchService.onEntryDeleted(path as Java8Path)
    }

    @Throws(ClientException::class)
    private fun getClient(authority: Authority): SFTPClient {
        synchronized(clients) {
            var client = clients[authority]
            if (client != null) {
                if (isAlive(authority, client)) {
                    return client
                } else {
                    clients -= authority
                    closeLater(client, sshClients.remove(authority))
                }
            }
            // A terminal connection to this server: open the SFTP channel on it.
            sharedSshClients[authority]?.let { shared ->
                if (isUsable(shared)) {
                    val sftp = try {
                        shared.newSFTPClient()
                    } catch (e: IOException) {
                        null
                    }
                    if (sftp != null) {
                        clients[authority] = sftp
                        return sftp
                    }
                }
                sharedSshClients -= authority
            }
            val authentication = authenticator.getAuthentication(authority)
                ?: throw ClientException("No authentication found for $authority")
            SecurityProviderHelper.ensureInitialized()
            // Verify the server like the SSH terminal does (it used to accept any key, which let a
            // machine on the same network impersonate the server and read files and passwords).
            val sshClient = SSHClient().apply {
                addHostKeyVerifier(
                    me.zhanghai.android.files.terminal.remote.KnownHostsVerifier(
                        me.zhanghai.android.files.app.application,
                        "SFTP ${authority.username}@${authority.host}:${authority.port}"
                    )
                )
            }
            try {
                sshClient.connect(authority.host, authority.port)
            } catch (e: IOException) {
                sshClient.closeSafe()
                throw ClientException(e)
            }
            try {
                sshClient.auth(authority.username, authentication.toAuthMethods())
            } catch (e: UserAuthException) {
                sshClient.closeSafe()
                throw ClientException(e)
            } catch (e: TransportException) {
                sshClient.closeSafe()
                throw ClientException(e)
            }
            client = try {
                sshClient.newSFTPClient()
            } catch (e: IOException) {
                sshClient.closeSafe()
                throw ClientException(e)
            }
            clients[authority] = client
            sshClients[authority] = sshClient
            return client
        }
    }

    interface Path {
        val authority: Authority
        val remotePath: String
        fun resolve(other: String): Path
    }
}
