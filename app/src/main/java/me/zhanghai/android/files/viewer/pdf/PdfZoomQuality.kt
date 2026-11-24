/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.pdf

import kotlin.math.ceil

/** How sharp to render PDF pages for a zoom level (pure, for tests). */
object PdfZoomQuality {

    /**
     * Quality tier for [zoom]: 1 up to 1.3x (the normal render is already denser than the
     * screen), then the zoom rounded up, never above [maxTier].
     */
    fun tierFor(zoom: Float, maxTier: Int): Int {
        if (zoom <= 1.3f) return 1
        return ceil(zoom.toDouble()).toInt().coerceIn(2, maxTier.coerceAtLeast(1))
    }

    /**
     * Before Android 8 bitmaps live in the Java heap, so the sharp versions are limited there;
     * small heaps (low-end phones, Ultra RAM) also stop earlier.
     */
    fun maxTier(sdkInt: Int, maxHeapBytes: Long): Int = when {
        sdkInt < 26 && maxHeapBytes < 256L * 1024 * 1024 -> 1
        sdkInt < 26 -> 2
        maxHeapBytes < 128L * 1024 * 1024 -> 2
        else -> 3
    }
}
