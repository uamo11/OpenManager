/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class NewlineIndexTest {

    private fun naiveLine(text: CharSequence, offset: Int): Int =
        1 + (0 until offset).count { text[it] == '\n' }

    /** Applies a replacement the way a TextWatcher reports it. */
    private fun replace(index: NewlineIndex, text: StringBuilder, start: Int, end: Int, insert: String) {
        index.beforeChange(text, start, end - start)
        text.replace(start, end, insert)
        index.afterChange(text, start, insert.length)
    }

    @Test
    fun countsLinesOfANewText() {
        val index = NewlineIndex()
        index.reset("a\nb\nc")
        assertEquals(3, index.lineCount)
        val anchor = index.newAnchor()
        assertEquals(1, index.lineAt("a\nb\nc", 0, anchor))
        assertEquals(2, index.lineAt("a\nb\nc", 2, anchor))
        assertEquals(3, index.lineAt("a\nb\nc", 5, anchor))
        assertEquals(1, index.lineAt("a\nb\nc", 1, anchor))
    }

    @Test
    fun staysRightThroughRandomEditsAndJumps() {
        val random = Random(42)
        val text = StringBuilder("first line\nsecond\n\nfourth {\n    body\n}\n")
        val index = NewlineIndex()
        val cursor = index.newAnchor()
        val gutter = index.newAnchor()
        index.reset(text)
        val pieces = listOf("x", "\n", "abc\ndef", "\n\n", "", "    ", "}\n{")
        repeat(3000) {
            val start = random.nextInt(text.length + 1)
            val end = (start + random.nextInt(6)).coerceAtMost(text.length)
            replace(index, text, start, end, pieces.random(random))
            assertEquals(text.count { it == '\n' } + 1, index.lineCount)
            val a = random.nextInt(text.length + 1)
            val b = random.nextInt(text.length + 1)
            assertEquals(naiveLine(text, a), index.lineAt(text, a, cursor))
            assertEquals(naiveLine(text, b), index.lineAt(text, b, gutter))
        }
    }

    @Test
    fun typingAtTheCursorKeepsItsLine() {
        val text = StringBuilder("one\ntwo\nthree")
        val index = NewlineIndex()
        val cursor = index.newAnchor()
        index.reset(text)
        var pos = text.indexOf("three")
        assertEquals(3, index.lineAt(text, pos, cursor))
        for (c in "\nfour\nfive") {
            replace(index, text, pos, pos, c.toString())
            pos++
            assertEquals(naiveLine(text, pos), index.lineAt(text, pos, cursor))
        }
        assertEquals(5, index.lineAt(text, pos, cursor))
        // Backspace over the newline joins the lines again.
        val nl = text.lastIndexOf("\n")
        replace(index, text, nl, nl + 1, "")
        assertEquals(naiveLine(text, nl), index.lineAt(text, nl, cursor))
        assertEquals(text.count { it == '\n' } + 1, index.lineCount)
    }

    @Test
    fun replacingTheWholeTextStartsOver() {
        val text = StringBuilder("a\nb\nc\nd")
        val index = NewlineIndex()
        val anchor = index.newAnchor()
        index.reset(text)
        index.lineAt(text, text.length, anchor)
        replace(index, text, 0, text.length, "only one line")
        assertEquals(1, index.lineCount)
        assertEquals(1, index.lineAt(text, text.length, anchor))
    }

    @Test
    fun lineStartFindsTheLogicalLine() {
        val index = NewlineIndex()
        val text = "abc\ndefgh\nij"
        assertEquals(0, index.lineStart(text, 2))
        assertEquals(4, index.lineStart(text, 4))
        assertEquals(4, index.lineStart(text, 8))
        assertEquals(10, index.lineStart(text, 12))
    }
}
