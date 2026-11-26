/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.video

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoGesturesTest {
    @Test
    fun classifiesDirectionAndSide() {
        assertEquals(VideoDrag.NONE, VideoGestures.classify(3f, 4f, 10f, 100f, 1000))
        assertEquals(VideoDrag.SEEK, VideoGestures.classify(80f, 10f, 10f, 100f, 1000))
        assertEquals(VideoDrag.BRIGHTNESS, VideoGestures.classify(5f, -80f, 10f, 200f, 1000))
        assertEquals(VideoDrag.VOLUME, VideoGestures.classify(5f, 80f, 10f, 800f, 1000))
        // A short or slanted brush on the right half is not a volume change.
        assertEquals(VideoDrag.NONE, VideoGestures.classify(5f, 20f, 10f, 800f, 1000))
        assertEquals(VideoDrag.NONE, VideoGestures.classify(25f, 30f, 10f, 800f, 1000))
        // The left half (brightness) still answers at once.
        assertEquals(VideoDrag.BRIGHTNESS, VideoGestures.classify(5f, 20f, 10f, 200f, 1000))
    }

    @Test
    fun seekScalesWithWidthAndStaysInside() {
        // Half the width of a long video: one minute.
        assertEquals(70_000L, VideoGestures.seekTarget(10_000L, 500f, 1000, 3_600_000L))
        // A short video: the whole width is the whole video.
        assertEquals(15_000L, VideoGestures.seekTarget(5_000L, 500f, 1000, 20_000L))
        assertEquals(0L, VideoGestures.seekTarget(5_000L, -900f, 1000, 3_600_000L))
        assertEquals(20_000L, VideoGestures.seekTarget(5_000L, 2_000f, 1000, 20_000L))
    }

    @Test
    fun brightnessAndVolumeFollowTheFinger() {
        assertEquals(1f, VideoGestures.brightness(0.5f, -700f, 1000), 0.001f)
        assertEquals(VideoGestures.MIN_BRIGHTNESS, VideoGestures.brightness(0.5f, 700f, 1000), 0.001f)
        assertEquals(15, VideoGestures.volume(5, -700f, 1000, 15))
        assertEquals(8, VideoGestures.volume(5, -233f, 1000, 15))
        // From silence the first 15% of the height does nothing.
        assertEquals(0, VideoGestures.volume(0, -100f, 1000, 15))
        assertEquals(4, VideoGestures.volume(0, -400f, 1000, 15))
        assertEquals(0, VideoGestures.volume(5, 700f, 1000, 15))
    }

    @Test
    fun seekLabelShowsDeltaAndTarget() {
        assertEquals("+00:15\n01:25 / 45:00", VideoGestures.seekLabel(70_000L, 85_000L, 2_700_000L))
        assertEquals("−00:10\n01:00", VideoGestures.seekLabel(70_000L, 60_000L, 0L))
    }
}
