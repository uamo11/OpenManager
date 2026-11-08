/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import org.junit.Assert.assertEquals
import org.junit.Test

class TextEditorKeyboardInsetsTest {

    private class InsetsTracker {
        var currentImeBottom: Int = 0
        var currentNavBottom: Int = 0

        var contentLayoutBottomMargin: Int = 0
        var symbolBarBottomPadding: Int = 0
        var searchPanelBottomPadding: Int = 0

        fun updateInsets(imeInsetsBottom: Int, navInsetsBottom: Int, isImeVisible: Boolean) {
            val isKeyboardOpen = isImeVisible || imeInsetsBottom > navInsetsBottom
            val effectiveIme = if (isKeyboardOpen && imeInsetsBottom > 0) imeInsetsBottom else 0

            currentImeBottom = effectiveIme
            currentNavBottom = navInsetsBottom
            applyInsets(isSymbolBarVisible = true, isSearchPanelVisible = false)
        }

        fun applyInsets(isSymbolBarVisible: Boolean, isSearchPanelVisible: Boolean) {
            val imeBottom = currentImeBottom
            val navBottom = currentNavBottom
            val baseSearchPadding = 24

            if (imeBottom > 0) {
                // Keyboard open: content layout moves up, symbol bar sits directly on top of keyboard
                contentLayoutBottomMargin = imeBottom
                symbolBarBottomPadding = 0
                searchPanelBottomPadding = baseSearchPadding
            } else {
                // Keyboard closed: content layout rests at bottom, bar pads above navigation bar
                contentLayoutBottomMargin = 0
                if (isSymbolBarVisible) {
                    symbolBarBottomPadding = navBottom
                    searchPanelBottomPadding = baseSearchPadding
                } else if (isSearchPanelVisible) {
                    symbolBarBottomPadding = 0
                    searchPanelBottomPadding = baseSearchPadding + navBottom
                } else {
                    symbolBarBottomPadding = 0
                    searchPanelBottomPadding = baseSearchPadding
                }
            }
        }
    }

    @Test
    fun testKeyboardClosedRestsAboveNavigationBar() {
        val tracker = InsetsTracker()
        tracker.updateInsets(imeInsetsBottom = 0, navInsetsBottom = 48, isImeVisible = false)

        assertEquals("Content layout margin must be 0 when keyboard is closed", 0, tracker.contentLayoutBottomMargin)
        assertEquals("Symbol bar must pad above navigation bar (48px)", 48, tracker.symbolBarBottomPadding)
    }

    @Test
    fun testKeyboardOpenLiftsSymbolBarAboveKeyboard() {
        val tracker = InsetsTracker()
        // Keyboard opens with height 850px
        tracker.updateInsets(imeInsetsBottom = 850, navInsetsBottom = 48, isImeVisible = true)

        assertEquals("Content layout margin must equal keyboard height (850px)", 850, tracker.contentLayoutBottomMargin)
        assertEquals("Symbol bar padding must be 0 so it rests flush on top of keyboard", 0, tracker.symbolBarBottomPadding)
    }

    @Test
    fun testKeyboardDismissalRestoresState() {
        val tracker = InsetsTracker()
        // Open keyboard
        tracker.updateInsets(imeInsetsBottom = 850, navInsetsBottom = 48, isImeVisible = true)
        assertEquals(850, tracker.contentLayoutBottomMargin)

        // Dismiss keyboard
        tracker.updateInsets(imeInsetsBottom = 0, navInsetsBottom = 48, isImeVisible = false)
        assertEquals("Content layout margin must reset to 0", 0, tracker.contentLayoutBottomMargin)
        assertEquals("Symbol bar padding must restore to navigation bar height (48px)", 48, tracker.symbolBarBottomPadding)
    }

    @Test
    fun testOEMVendorQuirkResolution() {
        val tracker = InsetsTracker()
        // Some OEMs report isImeVisible = false while imeInsetsBottom is still active during transitions
        tracker.updateInsets(imeInsetsBottom = 720, navInsetsBottom = 36, isImeVisible = false)

        assertEquals("Must detect keyboard even if isImeVisible is false when imeInsets > navInsets", 720, tracker.contentLayoutBottomMargin)
        assertEquals(0, tracker.symbolBarBottomPadding)
    }
}
