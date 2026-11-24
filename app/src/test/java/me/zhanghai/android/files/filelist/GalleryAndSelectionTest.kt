/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GalleryAndSelectionTest {

    @Test
    fun resolutionLabelsForPhotosAndVideos() {
        assertEquals("4K", MediaResolution.label(3840, 2160))
        assertEquals("4K", MediaResolution.label(2160, 3840)) // portrait video
        assertEquals("4K", MediaResolution.label(4000, 3000)) // 12 MP photo
        assertEquals("8K", MediaResolution.label(7680, 4320))
        assertEquals("2K", MediaResolution.label(2560, 1440))
        assertEquals("1080p", MediaResolution.label(1920, 1080))
        assertEquals("720p", MediaResolution.label(1280, 720))
        assertNull(MediaResolution.label(640, 480))
        assertNull(MediaResolution.label(0, 0))
    }

    @Test
    fun galleryTilesAreLargerThanTheGrid() {
        // Half a phone screen (~205 dp): two big squares per row.
        assertEquals(2, FileListEngine.galleryColumns(205))
        // Whole phone screen: three; a tablet: more, but never tiny.
        assertEquals(3, FileListEngine.galleryColumns(411))
        assertEquals(8, FileListEngine.galleryColumns(1600))
        assertEquals(2, FileListEngine.galleryColumns(100))
    }
}
