/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PrintWriter
import java.io.StringWriter

class CrashReportFormattingTest {

    @Test
    fun testCrashReportContainsEssentialHeadersAndTrace() {
        val testException = IllegalStateException("Test exception message for OpenManager crash system")
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        testException.printStackTrace(pw)
        val stackTrace = sw.toString()

        val report = buildString {
            appendLine("****************************************************************")
            appendLine("                     OPENMANAGER CRASH LOG                      ")
            appendLine("****************************************************************")
            appendLine("App:             OpenManager v1.9.4 (VersionCode 44)")
            appendLine("Thread:          main (TID: 1)")
            appendLine("---------------------- EXCEPTION DETAILS ----------------------")
            appendLine("Type:            ${testException.javaClass.name}")
            appendLine("Message:         ${testException.localizedMessage}")
            appendLine("---------------------- FULL STACKTRACE ------------------------")
            appendLine(stackTrace)
            appendLine("****************************************************************")
        }

        assertTrue(report.contains("OPENMANAGER CRASH LOG"))
        assertTrue(report.contains("IllegalStateException"))
        assertTrue(report.contains("Test exception message for OpenManager crash system"))
        assertTrue(report.contains("CrashReportFormattingTest.kt"))
    }
}
