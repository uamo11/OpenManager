/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException

class UpdateDownloadRetryTest {

    @Test
    fun droppedConnectionsResume() {
        // User report: minimizing the app gave "Software caused connection abort".
        assertTrue(UpdateChecker.isRetryableDownloadError(SocketException("Software caused connection abort")))
        assertTrue(UpdateChecker.isRetryableDownloadError(SocketTimeoutException("timeout")))
        assertTrue(UpdateChecker.isRetryableDownloadError(EOFException()))
        assertTrue(UpdateChecker.isRetryableDownloadError(IOException("Incomplete download (5 / 10 bytes)")))
        assertTrue(UpdateChecker.isRetryableDownloadError(IOException("HTTP 503")))
    }

    @Test
    fun realFailuresAreShownAtOnce() {
        assertFalse(UpdateChecker.isRetryableDownloadError(IOException("HTTP 404")))
        assertFalse(UpdateChecker.isRetryableDownloadError(IOException("The downloaded file is not a valid APK")))
    }
}
