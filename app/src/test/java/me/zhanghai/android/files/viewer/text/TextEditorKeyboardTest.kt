/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Gboard's spacebar swipe (move the cursor) and backspace swipe (delete words) need the text around
 * the cursor and setSelection. sora's disallowSuggestions switches both off, so suggestions are
 * turned off through the input type instead (user report: the swipes did nothing in the editor).
 */
class TextEditorKeyboardTest {

    private val fragment = (File("src/main").takeIf { it.isDirectory } ?: File("app/src/main"))
        .resolve("java/me/zhanghai/android/files/viewer/text/TextEditorFragment.kt").readText()

    @Test
    fun keyboardGesturesAreNotDisabled() {
        assertFalse(fragment.contains("disallowSuggestions = true"))
        assertTrue(fragment.contains("disallowSuggestions = false"))
    }

    @Test
    fun suggestionsAreOffThroughTheInputType() {
        assertTrue(fragment.contains("TYPE_TEXT_FLAG_NO_SUGGESTIONS"))
        assertTrue(fragment.contains("TYPE_TEXT_FLAG_MULTI_LINE"))
    }
}
