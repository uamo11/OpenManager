/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameworkGlitchesTest {

    private fun mediaScannerCrash(message: String = "stableCount < 0: -1") =
        IllegalStateException(message).apply {
            stackTrace = arrayOf(
                StackTraceElement("android.os.Parcel", "createException", "Parcel.java", 3372),
                StackTraceElement("android.content.ContentProviderClient", "close", "ContentProviderClient.java", 619),
                StackTraceElement("android.media.MediaScannerConnection", "lambda\$scanFile\$1", "MediaScannerConnection.java", 190)
            )
        }

    @Test
    fun theReportedAndroid16ScannerCrashIsIgnored() {
        assertTrue(FrameworkGlitches.isHarmless("android.bg", mediaScannerCrash()))
    }

    @Test
    fun realCrashesAreNeverHidden() {
        // Same error on our own thread, another message, another exception or frames: real crash.
        assertFalse(FrameworkGlitches.isHarmless("main", mediaScannerCrash()))
        assertFalse(FrameworkGlitches.isHarmless("android.bg", mediaScannerCrash("Something else")))
        assertFalse(FrameworkGlitches.isHarmless("android.bg", NullPointerException("stableCount < 0")))
        assertFalse(
            FrameworkGlitches.isHarmless(
                "android.bg",
                IllegalStateException("stableCount < 0: -1").apply {
                    stackTrace = arrayOf(StackTraceElement("me.zhanghai.android.files.Foo", "bar", "Foo.kt", 1))
                }
            )
        )
    }
}
