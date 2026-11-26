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

    /**
     * Volume only starts after a longer, clearly vertical swipe: a brush of the thumb on the right
     * half must not turn the sound up (owner request; also for people watching without sound).
     */
    private const val VOLUME_START_SLOPS = 2.5f

    /** Horizontal wins only when clearly horizontal; left half = brightness, right = volume. */
    fun classify(dx: Float, dy: Float, slop: Float, startX: Float, width: Int): VideoDrag = when {
        abs(dx) < slop && abs(dy) < slop -> VideoDrag.NONE
        abs(dx) > abs(dy) * 1.2f -> VideoDrag.SEEK
        abs(dy) > abs(dx) && startX < width / 2f -> VideoDrag.BRIGHTNESS
        // Right half: wait until the swipe is long and straight enough to mean "volume".
        abs(dy) >= slop * VOLUME_START_SLOPS && abs(dy) > abs(dx) * 1.5f -> VideoDrag.VOLUME
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

    /**
     * Stream volume step after dragging [dy] pixels. Slower than brightness (the whole height for
     * the whole range), and from silence the first [MUTED_DEAD_ZONE] of the height does nothing,
     * so a muted video does not start playing aloud by accident.
     */
    fun volume(start: Int, dy: Float, height: Int, max: Int): Int {
        if (height <= 0 || max <= 0) return start
        var travel = -dy
        if (start == 0 && travel > 0) {
            travel = (travel - height * MUTED_DEAD_ZONE).coerceAtLeast(0f)
        }
        return (start + travel / height * max).roundToInt().coerceIn(0, max)
    }

    /** Share of the height to swipe up from silence before the sound comes on. */
    const val MUTED_DEAD_ZONE = 0.15f

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
