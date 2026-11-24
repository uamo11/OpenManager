/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfReaderTest {

    @Test
    fun zoomedPagesAreRenderedSharperOnlyWhenNeeded() {
        assertEquals(1, PdfZoomQuality.tierFor(1f, 3))
        assertEquals(1, PdfZoomQuality.tierFor(1.25f, 3))
        assertEquals(2, PdfZoomQuality.tierFor(1.6f, 3))
        assertEquals(3, PdfZoomQuality.tierFor(2.5f, 3))
        assertEquals(3, PdfZoomQuality.tierFor(4f, 3))
        // A device that cannot afford it stays at its maximum.
        assertEquals(2, PdfZoomQuality.tierFor(4f, 2))
        assertEquals(1, PdfZoomQuality.tierFor(4f, 1))
    }

    @Test
    fun oldAndroidWithSmallHeapGetsLessDetailInsteadOfCrashing() {
        val mb = 1024L * 1024
        assertEquals(1, PdfZoomQuality.maxTier(23, 192 * mb))
        assertEquals(2, PdfZoomQuality.maxTier(25, 512 * mb))
        assertEquals(2, PdfZoomQuality.maxTier(30, 96 * mb))
        assertEquals(3, PdfZoomQuality.maxTier(34, 512 * mb))
    }

    @Test
    fun everyPdfRemembersItsPageButNotPageOneOrAChangedFile() {
        assertFalse(PdfResumeStore.shouldRemember(1, 50))
        assertTrue(PdfResumeStore.shouldRemember(12, 50))
        assertTrue(PdfResumeStore.shouldRemember(50, 50))
        assertFalse(PdfResumeStore.shouldRemember(51, 50))
        // Same name, different file (size) = different document.
        assertNotEquals(
            PdfResumeStore.keyFor("file:///sdcard/book.pdf", 1000),
            PdfResumeStore.keyFor("file:///sdcard/book.pdf", 2000)
        )
        assertTrue(PdfResumeStore.MAX_ENTRIES >= 100)
    }
}
