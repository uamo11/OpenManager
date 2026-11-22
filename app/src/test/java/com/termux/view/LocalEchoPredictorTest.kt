/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package com.termux.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalEchoPredictorTest {
    private var now = 1_000L
    private lateinit var screen: FakeScreen
    private lateinit var predictor: LocalEchoPredictor

    /** Minimal VT: printable chars, CR and LF on an 80x24 grid. */
    private class FakeScreen : LocalEchoPredictor.Screen {
        val cells = Array(24) { IntArray(80) { -1 } }
        var row = 0
        var col = 0

        fun write(text: String) {
            for (c in text) {
                when (c) {
                    '\r' -> col = 0
                    '\n' -> row = (row + 1).coerceAtMost(23)
                    else -> {
                        cells[row][col] = c.code
                        col = (col + 1).coerceAtMost(79)
                    }
                }
            }
        }

        override fun rows() = 24
        override fun columns() = 80
        override fun cursorRow() = row
        override fun cursorCol() = col
        override fun cursorEnabled() = true
        override fun charAt(row: Int, col: Int) = cells[row][col]
    }

    private fun server(text: String) {
        screen.write(text)
        predictor.onScreenUpdated(screen)
    }

    private fun type(text: String) {
        for (c in text) predictor.onPrintable(screen, c.code)
    }

    @Before
    fun setUp() {
        screen = FakeScreen()
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
        predictor.onScreenUpdated(screen)
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
