/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp.client

import net.schmizz.sshj.common.SSHRuntimeException
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.TransportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

class SftpFailureTest {

    @Test
    fun serverAnswersKeepTheChannel() {
        for (code in listOf(
            Response.StatusCode.NO_SUCH_FILE, Response.StatusCode.PERMISSION_DENIED,
            Response.StatusCode.FAILURE, Response.StatusCode.FILE_ALREADY_EXISTS
        )) {
            val failure = SftpFailure.of(SFTPException(code, "x"))
            assertEquals(SftpFailure.SERVER_ANSWER, failure)
            assertFalse(failure.dropsChannel)
        }
    }

    @Test
    fun sshjsBrokenBufferErrorIsACorruptChannel() {
        // The exact error users saw after a network change.
        val error = ArrayIndexOutOfBoundsException(
            "src.length=1 srcPos=0 dst.length=256 dstPos=-4 length=1"
        )
        val failure = SftpFailure.of(error)
        assertEquals(SftpFailure.CORRUPT_CHANNEL, failure)
        assertTrue(failure.dropsChannel)
        assertTrue(failure.dropsConnection)
        assertEquals(SftpFailure.CORRUPT_CHANNEL, SftpFailure.of(SSHRuntimeException("window")))
    }

    @Test
    fun aFailedSocketWriteMeansTheLinkIsDown() {
        val failure = SftpFailure.of(TransportException(SocketException("Broken pipe")))
        assertEquals(SftpFailure.LINK_DOWN, failure)
        assertTrue(failure.dropsConnection)
        // Wrapped by the SFTP layer.
        assertEquals(
            SftpFailure.LINK_DOWN,
            SftpFailure.of(SFTPException(TransportException(IOException("Injected"))))
        )
    }

    @Test
    fun aTimeoutOrClosedChannelDropsOnlyTheChannel() {
        val timeout = SftpFailure.of(SFTPException(TimeoutException("Timeout expired: 30000 MILLISECONDS")))
        assertEquals(SftpFailure.CHANNEL_LOST, timeout)
        assertTrue(timeout.dropsChannel)
        assertFalse(timeout.dropsConnection)
        assertEquals(SftpFailure.CHANNEL_LOST, SftpFailure.of(ConnectionException("Stream closed")))
        // A socket read timeout is a slow link, not the user leaving the folder.
        assertEquals(SftpFailure.LINK_DOWN, SftpFailure.of(SocketTimeoutException("read timed out")))
    }

    @Test
    fun leavingAFolderWhileItLoadsBreaksNothing() {
        val failure = SftpFailure.of(SFTPException(InterruptedException()))
        assertEquals(SftpFailure.INTERRUPTED, failure)
        assertFalse(failure.dropsChannel)
        assertFalse(failure.dropsConnection)
    }
}
