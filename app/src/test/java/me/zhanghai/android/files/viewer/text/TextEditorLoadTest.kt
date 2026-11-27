/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class TextEditorLoadTest {

    private val mb = 1024 * 1024L

    @Test
    fun theLimitFollowsTheHeap() {
        // User crash: a 128 MB heap could not hold 20 MB of binary decoded as text.
        assertEquals(12 * mb, TextEditorViewModel.maxFileSizeFor(128 * mb))
        assertEquals(TextEditorViewModel.MAX_FILE_SIZE, TextEditorViewModel.maxFileSizeFor(256 * mb))
        assertEquals(TextEditorViewModel.MAX_FILE_SIZE, TextEditorViewModel.maxFileSizeFor(512 * mb))
        assertEquals(2 * mb, TextEditorViewModel.maxFileSizeFor(8 * mb))
    }

    @Test
    fun lineEndingsAreNormalizedInPlace() {
        val (text, crlf) = TextEditorViewModel.decodeNormalizingLineEndings(
            "a\r\nb\r\n\r\nc\rd\n".toByteArray(), StandardCharsets.UTF_8
        )
        assertTrue(crlf)
        // A lone \r stays (only \r\n pairs are line endings to fold).
        assertEquals("a\nb\n\nc\rd\n", text)
        val (plain, none) = TextEditorViewModel.decodeNormalizingLineEndings(
            "x\ny".toByteArray(), StandardCharsets.UTF_8
        )
        assertFalse(none)
        assertEquals("x\ny", plain)
    }

    @Test
    fun binaryDecodesAsStringDoes() {
        val bytes = ByteArray(4096) { (it * 37 + 11).toByte() }
        val expected = String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n")
        val (text, _) = TextEditorViewModel.decodeNormalizingLineEndings(bytes, StandardCharsets.UTF_8)
        assertEquals(expected, text)
        val (latin, _) = TextEditorViewModel.decodeNormalizingLineEndings(bytes, StandardCharsets.ISO_8859_1)
        assertEquals(String(bytes, StandardCharsets.ISO_8859_1).replace("\r\n", "\n"), latin)
    }

    @Test
    fun endsOnAPendingCarriageReturn() {
        val (text, crlf) = TextEditorViewModel.decodeNormalizingLineEndings("a\r".toByteArray(), StandardCharsets.UTF_8)
        assertEquals("a\r", text)
        assertFalse(crlf)
    }
}
