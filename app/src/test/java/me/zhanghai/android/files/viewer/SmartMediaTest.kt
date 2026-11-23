/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer

import me.zhanghai.android.files.viewer.image.ImageDisplayPlan
import me.zhanghai.android.files.viewer.image.ImageDisplayPlan.Mode
import me.zhanghai.android.files.viewer.video.isDecoderStruggling
import me.zhanghai.android.files.viewer.video.isSoftwareDecoder
import me.zhanghai.android.files.viewer.video.sampleBufferBytes
import me.zhanghai.android.files.viewer.video.shouldRememberPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartMediaTest {

    // A common 1080×2400 phone.
    private val w = 1080
    private val h = 2400

    @Test
    fun photosBiggerThanTheScreenUseTiles() {
        // 12 MP camera photo, 4K screenshot, 48 MP photo.
        assertEquals(Mode.TILES, ImageDisplayPlan.mode(4000, 3000, "image/jpeg", w, h))
        assertEquals(Mode.TILES, ImageDisplayPlan.mode(3840, 2160, "image/png", w, h))
        assertEquals(Mode.TILES, ImageDisplayPlan.mode(8000, 6000, "image/webp", w, h))
        // About one screen: a single bitmap is cheaper than tiles.
        assertEquals(Mode.BITMAP, ImageDisplayPlan.mode(1080, 1920, "image/jpeg", w, h))
        // A tall screenshot keeps full detail for zooming, as before.
        assertEquals(Mode.TILES, ImageDisplayPlan.mode(1080, 2400, "image/jpeg", w, h))
        assertEquals(Mode.BITMAP, ImageDisplayPlan.mode(1920, 1080, "image/jpeg", w, h))
        assertEquals(Mode.BITMAP, ImageDisplayPlan.mode(640, 480, "image/png", w, h))
    }

    @Test
    fun formatsTheTileDecoderCannotReadStayBitmaps() {
        // HEIC/AVIF/BMP are decoded whole (capped at twice the screen), never handed to the tiles.
        assertEquals(Mode.BITMAP, ImageDisplayPlan.mode(4000, 3000, "image/heic", w, h))
        assertEquals(Mode.BITMAP, ImageDisplayPlan.mode(4000, 3000, "image/avif", w, h))
        assertEquals(Mode.BITMAP, ImageDisplayPlan.mode(4000, 3000, "image/gif", w, h))
        assertEquals(Mode.BITMAP, ImageDisplayPlan.mode(0, 0, "image/jpeg", w, h))
        assertEquals(Mode.BITMAP, ImageDisplayPlan.mode(-1, -1, null, w, h))
        // Unless one bitmap could not hold it at all (over 100 MB).
        assertEquals(Mode.TILES, ImageDisplayPlan.mode(12000, 9000, "image/heic", w, h))
    }

    @Test
    fun longPanoramasUseTiles() {
        // On a 4K tablet a long screenshot is under two screens of pixels, but zooming needs detail.
        assertEquals(Mode.TILES, ImageDisplayPlan.mode(1200, 4000, "image/png", 2160, 3840))
        assertEquals(Mode.BITMAP, ImageDisplayPlan.mode(1200, 2000, "image/png", 2160, 3840))
    }

    @Test
    fun bitmapsAreCappedNearTheScreen() {
        assertEquals(4800, ImageDisplayPlan.bitmapTargetPx(w, h))
        assertEquals(2048, ImageDisplayPlan.bitmapTargetPx(480, 800))
    }

    @Test
    fun exifRotation() {
        assertEquals(0, ImageDisplayPlan.exifRotation(1))
        assertEquals(0, ImageDisplayPlan.exifRotation(0))
        assertEquals(90, ImageDisplayPlan.exifRotation(6))
        assertEquals(180, ImageDisplayPlan.exifRotation(3))
        assertEquals(270, ImageDisplayPlan.exifRotation(8))
    }

    @Test
    fun softwareDecoders() {
        assertTrue(isSoftwareDecoder("OMX.google.h264.decoder"))
        assertTrue(isSoftwareDecoder("c2.android.hevc.decoder"))
        assertFalse(isSoftwareDecoder("c2.qti.avc.decoder"))
        assertFalse(isSoftwareDecoder("OMX.Exynos.hevc.dec"))
        assertFalse(isSoftwareDecoder(null))
    }

    @Test
    fun strugglingNeedsSoftwareAndManyDrops() {
        // The CI emulator's 4K run: ~380 of ~510 frames dropped.
        assertTrue(isDecoderStruggling(rendered = 130, dropped = 380, software = true))
        assertFalse(isDecoderStruggling(rendered = 130, dropped = 380, software = false))
        // A few drops are normal.
        assertFalse(isDecoderStruggling(rendered = 500, dropped = 20, software = true))
        // Too early to judge.
        assertFalse(isDecoderStruggling(rendered = 10, dropped = 30, software = true))
    }

    @Test
    fun resumeOnlyLongVideosInTheMiddle() {
        val tenMin = 10 * 60_000L
        assertTrue(shouldRememberPosition(5 * 60_000L, tenMin))
        assertFalse(shouldRememberPosition(3_000L, tenMin))
        assertFalse(shouldRememberPosition(tenMin - 2_000L, tenMin))
        // A 20 s clip always starts from the beginning.
        assertFalse(shouldRememberPosition(10_000L, 20_000L))
    }

    @Test
    fun sampleBufferFitsTheJavaHeap() {
        val mb = 1024L * 1024
        // Normal phones (256 MB heap): 16 MB, or 6 MB in Ultra RAM mode.
        assertEquals(16 * mb, sampleBufferBytes(256 * mb, ultraRam = false).toLong())
        assertEquals(6 * mb, sampleBufferBytes(256 * mb, ultraRam = true).toLong())
        // A 16 MB heap (seen on the CI emulator) ran out of memory with 16 MB of samples.
        assertEquals(4 * mb, sampleBufferBytes(16 * mb, ultraRam = false).toLong())
        assertEquals(2 * mb, sampleBufferBytes(4 * mb, ultraRam = false).toLong())
    }
}
