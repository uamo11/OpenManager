/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package com.termux.view

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalEchoPredictorTest {
    private var now = 1_000L
    private lateinit var emulator: TerminalEmulator
    private lateinit var predictor: LocalEchoPredictor

    private object NoOutput : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {}
        override fun titleChanged(oldTitle: String?, newTitle: String?) {}
        override fun onCopyTextToClipboard(text: String?) {}
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() {}
    }

    private object NoClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {}
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
        override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    private fun server(text: String) {
        val bytes = text.toByteArray()
        emulator.append(bytes, bytes.size)
        predictor.onScreenUpdated(emulator)
    }

    private fun type(text: String) {
        for (c in text) predictor.onPrintable(emulator, c.code)
    }

    @Before
    fun setUp() {
        emulator = TerminalEmulator(NoOutput, 80, 24, 10, 20, 100, NoClient)
        predictor = LocalEchoPredictor()
        predictor.mClock = LocalEchoPredictor.Clock { now }
        predictor.setEnabled(true)
        server("user@vps:~$ ")
    }

    @Test
    fun keysAppearOnlyAfterTheServerConfirmedTheEpoch() {
        type("c")
        assertFalse("First key of an epoch is never shown", predictor.shouldDisplay())
        now += 120
        server("c")
        assertEquals(0, predictor.pendingCount)
        assertEquals(120L, predictor.smoothedRttMs)

        type("laude")
        assertTrue("Echo confirmed and RTT high: predict the rest", predictor.shouldDisplay())
        assertEquals(5, predictor.pendingCount)
        assertEquals(13, predictor.getCol(0))
        now += 110
        server("lau")
        assertEquals(2, predictor.pendingCount)
        server("de")
        assertEquals(0, predictor.pendingCount)
        assertEquals("All 5 predicted keys were shown early and confirmed", 5, predictor.shownConfirmedCount)
    }

    @Test
    fun passwordPromptsNeverDisplayTypedCharacters() {
        type("s")
        now += 100
        server("s")
        type("udo -i")
        server("udo -i")
        predictor.resetEpoch() // Enter
        server("\r\n[sudo] password for user: ")
        type("hunter2")
        now += 2_000
        predictor.onScreenUpdated(emulator)
        assertFalse(predictor.shouldDisplay())
        assertEquals("No echo: predictions dropped when stale", 0, predictor.pendingCount)
    }

    @Test
    fun mismatchDropsPredictionsAndStartsANewEpoch() {
        type("v")
        now += 90
        server("v")
        type("im")
        assertTrue(predictor.shouldDisplay())
        // The application answered with something else (e.g. completion menu).
        server("\r\nDisplay all 42 possibilities? ")
        assertEquals(0, predictor.pendingCount)
        type("y")
        assertFalse(predictor.shouldDisplay())
    }

    @Test
    fun fastLinksDoNotDrawPredictions() {
        type("l")
        now += 5
        server("l")
        type("s")
        assertFalse("Below 20 ms the real echo is already instant", predictor.shouldDisplay())
    }

    @Test
    fun backspaceUnpredictsAndRightMarginStopsPredicting() {
        type("a")
        now += 80
        server("a")
        type("bc")
        predictor.onBackspace()
        assertEquals(1, predictor.pendingCount)
        type("x".repeat(66)) // "b" sits at column 13, so the 66th "x" would land on the last column
        assertEquals("Wrapping is never predicted", 0, predictor.pendingCount)
    }
}
