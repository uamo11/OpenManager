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
