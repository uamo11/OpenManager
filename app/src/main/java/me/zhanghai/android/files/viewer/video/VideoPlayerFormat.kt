/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.video

import java.util.Locale

/** `mm:ss`, or `h:mm:ss` from one hour on. */
fun formatTime(millis: Long): String {
    val totalSec = (millis / 1000).coerceAtLeast(0)
    val hours = totalSec / 3600
    val min = (totalSec % 3600) / 60
    val sec = totalSec % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, min, sec)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", min, sec)
    }
}

/** `1×`, `1.5×`... */
fun formatSpeed(speed: Float): String =
    (if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()) + "×"

/** Android's own software codecs; anything else is a vendor (hardware) decoder. */
fun isSoftwareDecoder(name: String?): Boolean =
    name != null && (name.startsWith("OMX.google.") || name.startsWith("c2.android.") ||
        name.startsWith("OMX.ffmpeg.") || name.startsWith("c2.ffmpeg."))

/**
 * True when a software decoder clearly cannot keep up: after at least [minFrames] frames, more
 * than a third of them were dropped. Only worth telling the user about for big videos.
 */
fun isDecoderStruggling(rendered: Int, dropped: Int, software: Boolean, minFrames: Int = 60): Boolean {
    val total = rendered + dropped
    return software && total >= minFrames && dropped * 3 > total
}

/** Resume videos of at least 3 minutes, unless they were just started or nearly finished. */
const val RESUME_MIN_DURATION_MS = 3 * 60_000L
const val RESUME_EDGE_MS = 10_000L

fun shouldRememberPosition(positionMs: Long, durationMs: Long): Boolean =
    durationMs >= RESUME_MIN_DURATION_MS &&
        positionMs >= RESUME_EDGE_MS &&
        positionMs <= durationMs - RESUME_EDGE_MS
