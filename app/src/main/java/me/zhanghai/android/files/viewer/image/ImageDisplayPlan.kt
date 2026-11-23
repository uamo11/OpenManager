/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import kotlin.math.max

/**
 * How a photo is shown in the viewer.
 *
 * Decoding a 12 MP photo or a 4K screenshot whole costs 33–48 MB per page (and the pager keeps
 * the neighbours too), although the screen only shows a fraction of those pixels. Anything clearly
 * bigger than the screen is therefore shown with tiles: a screen-sized base image, plus
 * full-resolution tiles of just the zoomed area. Formats the tile decoder cannot read are decoded
 * as one bitmap capped at twice the screen, which keeps zoom sharp without holding the original.
 */
object ImageDisplayPlan {

    enum class Mode { BITMAP, TILES }

    /** @see android.graphics.RecordingCanvas.MAX_BITMAP_SIZE */
    const val MAX_BITMAP_BYTES = 100L * 1024 * 1024

    /** Tiles start when the photo has more than this many screens' worth of pixels. */
    const val TILES_ABOVE_SCREENS = 2L

    private val REGION_DECODABLE = setOf("image/jpeg", "image/png", "image/webp")

    fun isRegionDecodable(mimeType: String?): Boolean = mimeType?.lowercase() in REGION_DECODABLE

    fun mode(width: Int, height: Int, mimeType: String?, screenWidth: Int, screenHeight: Int): Mode {
        val type = mimeType?.lowercase()
        if (type == "image/gif" || type == "image/svg+xml") return Mode.BITMAP
        if (width <= 0 || height <= 0) return Mode.BITMAP
        val pixels = width.toLong() * height.toLong()
        // Too big for one bitmap at all: tiles are the only way to show it.
        if (pixels * 4L > MAX_BITMAP_BYTES) return Mode.TILES
        if (!isRegionDecodable(type)) return Mode.BITMAP
        val screenPixels = max(1L, screenWidth.toLong() * screenHeight.toLong())
        if (pixels > screenPixels * TILES_ABOVE_SCREENS) return Mode.TILES
        // Long panoramas and screenshots: zooming into them needs the full detail.
        if (width > 2048 || height > 2048) {
            val ratio = width.toFloat() / height
            if (ratio < 0.5f || ratio > 2f) return Mode.TILES
        }
        return Mode.BITMAP
    }

    /** Longest side of a bitmap-mode decode: twice the screen, never less than 2048. */
    fun bitmapTargetPx(screenWidth: Int, screenHeight: Int): Int =
        max(2048, 2 * max(screenWidth, screenHeight))

    /** EXIF orientation → clockwise degrees (mirrored variants use their rotation). */
    fun exifRotation(exifOrientation: Int): Int = when (exifOrientation) {
        3, 4 -> 180
        5, 6 -> 90
        7, 8 -> 270
        else -> 0
    }
}
