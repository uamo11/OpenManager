/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.video

import kotlin.math.abs
import kotlin.math.roundToInt

/** What a one-finger drag on the video controls, decided once it leaves the touch slop. */
enum class VideoDrag { NONE, SEEK, BRIGHTNESS, VOLUME }

object VideoGestures {
    /** A swipe across the whole width moves this far (or the whole video, if shorter). */
    const val FULL_WIDTH_SEEK_MS = 120_000L

    /** A swipe over this share of the height goes from minimum to maximum. */
    private const val FULL_RANGE_HEIGHT = 0.7f

    /** Horizontal wins only when clearly horizontal; left half = brightness, right = volume. */
    fun classify(dx: Float, dy: Float, slop: Float, startX: Float, width: Int): VideoDrag = when {
        abs(dx) < slop && abs(dy) < slop -> VideoDrag.NONE
        abs(dx) > abs(dy) * 1.2f -> VideoDrag.SEEK
        abs(dy) > abs(dx) -> if (startX < width / 2f) VideoDrag.BRIGHTNESS else VideoDrag.VOLUME
        else -> VideoDrag.NONE
    }

    fun seekTarget(startMs: Long, dx: Float, width: Int, durationMs: Long): Long {
        if (width <= 0) return startMs
        val span = if (durationMs > 0) minOf(durationMs, FULL_WIDTH_SEEK_MS) else FULL_WIDTH_SEEK_MS
        val target = startMs + (dx / width * span).toLong()
        val max = if (durationMs > 0) durationMs else Long.MAX_VALUE
        return target.coerceIn(0L, max)
    }

    /** Window brightness (0..1) after dragging [dy] pixels (up is negative, i.e. brighter). */
    fun brightness(start: Float, dy: Float, height: Int): Float {
        if (height <= 0) return start
        return (start - dy / (height * FULL_RANGE_HEIGHT)).coerceIn(MIN_BRIGHTNESS, 1f)
    }

    /** Stream volume step after dragging [dy] pixels. */
    fun volume(start: Int, dy: Float, height: Int, max: Int): Int {
        if (height <= 0 || max <= 0) return start
        return (start - dy / (height * FULL_RANGE_HEIGHT) * max).roundToInt().coerceIn(0, max)
    }

    /** `+0:15  1:23 / 45:00` */
    fun seekLabel(startMs: Long, targetMs: Long, durationMs: Long): String {
        val delta = targetMs - startMs
        val sign = if (delta < 0) "−" else "+"
        val total = if (durationMs > 0) " / " + formatTime(durationMs) else ""
        return sign + formatTime(abs(delta)) + "\n" + formatTime(targetMs) + total
    }

    const val MIN_BRIGHTNESS = 0.01f
    const val HOLD_SPEED = 2f
}
