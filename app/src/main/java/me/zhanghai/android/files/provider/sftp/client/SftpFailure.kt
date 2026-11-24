/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp.client

import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.TransportException

/**
 * What a failed SFTP call says about the channel it ran on.
 *
 * sshj (0.40) has a trap here: when sending a request fails after the packet was encrypted
 * (a network change, a dead link), the channel's send buffer is left with a bad position. Every
 * later request on that channel then fails with "src.length=1 srcPos=0 dst.length=256 dstPos=-4",
 * and closing it the normal way crashes sshj's reader thread, which takes down the whole SSH
 * connection, the terminal included. So a channel that failed is never used again, and is closed
 * with [closeBroken].
 */
enum class SftpFailure {
    /** The server answered (no such file, permission denied...): the channel is fine. */
    SERVER_ANSWER,
    /**
     * The calling thread was interrupted (the user left the folder): the request stays pending and
     * its late answer is simply dropped, so the channel is fine.
     */
    INTERRUPTED,
    /** The request never left: the channel's buffer is broken. Safe to retry anything. */
    CORRUPT_CHANNEL,
    /** The link itself failed while sending or waiting: the connection is gone. */
    LINK_DOWN,
    /** No answer in time, or the channel was closed: the channel is unusable, the link may be fine. */
    CHANNEL_LOST;

    /** The channel must not be used again. */
    val dropsChannel: Boolean get() = this != SERVER_ANSWER && this != INTERRUPTED

    /** The SSH connection under the channel must not be reused for new channels. */
    val dropsConnection: Boolean get() = this == CORRUPT_CHANNEL || this == LINK_DOWN

    companion object {
        fun of(error: Throwable): SftpFailure {
            // sshj reports UNKNOWN for errors that are not a status sent by the server.
            if (error is SFTPException && error.statusCode != Response.StatusCode.UNKNOWN) {
                return SERVER_ANSWER
            }
            val chain = generateSequence(error) { it.cause }.take(8).toList()
            return when {
                chain.any {
                    it is InterruptedException || it is java.io.InterruptedIOException &&
                        it !is java.net.SocketTimeoutException
                } -> INTERRUPTED
                // Not an I/O error: sshj's own buffers failed (the bug above) before sending.
                error is RuntimeException -> CORRUPT_CHANNEL
                chain.any { it is TransportException } -> LINK_DOWN
                chain.any {
                    it is java.net.SocketException || it is java.net.SocketTimeoutException ||
                        it is java.io.EOFException
                } -> LINK_DOWN
                else -> CHANNEL_LOST
            }
        }

        /**
         * Closes [client] without letting a broken send buffer reach sshj's reader thread: the
         * channel's output stream is closed here first (it marks itself closed before flushing, so
         * a failing flush only throws in this thread and the reader's later close is a no-op).
         */
        fun closeBroken(client: SFTPClient) {
            try {
                client.sftpEngine.subsystem.outputStream.close()
            } catch (_: Throwable) {
                // Expected for a broken buffer.
            }
            try {
                client.close()
            } catch (_: Throwable) {
            }
        }
    }
}
