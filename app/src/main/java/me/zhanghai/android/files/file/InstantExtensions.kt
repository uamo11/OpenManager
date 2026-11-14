/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.file

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Time
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/* @see com.android.documentsui.base.Shared#formatTime(Context, long) */
@Suppress("DEPRECATION")
fun Instant.formatShort(context: Context): String {
    val time = toEpochMilli()
    val then = Time().apply { set(time) }
    val now = Time().apply { setToNow() }
    val flags = DateUtils.FORMAT_NO_NOON or DateUtils.FORMAT_NO_MIDNIGHT or
        DateUtils.FORMAT_ABBREV_ALL or when {
            then.year != now.year -> DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_DATE
            then.yearDay != now.yearDay -> DateUtils.FORMAT_SHOW_DATE
            else -> DateUtils.FORMAT_SHOW_TIME
        }
    return DateUtils.formatDateTime(context, time, flags)
}

fun Instant.formatLong(): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withZone(ZoneId.systemDefault())
        .format(this)

private val MT_DATE_FULL_ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    .withZone(ZoneId.systemDefault())
private val MT_DATE_FULL_DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
    .withZone(ZoneId.systemDefault())
private val MT_DATE_FULL_MDY = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss")
    .withZone(ZoneId.systemDefault())

private val MT_DATE_SHORT_ISO = DateTimeFormatter.ofPattern("yy-MM-dd HH:mm")
    .withZone(ZoneId.systemDefault())
private val MT_DATE_SHORT_DMY = DateTimeFormatter.ofPattern("dd/MM/yy HH:mm")
    .withZone(ZoneId.systemDefault())
private val MT_DATE_SHORT_MDY = DateTimeFormatter.ofPattern("MM/dd/yy HH:mm")
    .withZone(ZoneId.systemDefault())

fun Instant.formatMtDateWithOptions(
    timePref: String,
    dateFormat: String,
    zoneId: ZoneId? = null
): String {
    val hideSeconds = timePref != "0"
    val baseFormatter = when (dateFormat) {
        "1" -> if (hideSeconds) MT_DATE_SHORT_DMY else MT_DATE_FULL_DMY
        "2" -> if (hideSeconds) MT_DATE_SHORT_MDY else MT_DATE_FULL_MDY
        else -> if (hideSeconds) MT_DATE_SHORT_ISO else MT_DATE_FULL_ISO
    }
    val formatter = if (zoneId != null) baseFormatter.withZone(zoneId) else baseFormatter
    return formatter.format(this)
}

fun Instant.formatMtDate(): String {
    val timePref = try {
        Settings.FILE_LIST_TIME_PREFERENCE.valueCompat
    } catch (_: Throwable) {
        "1"
    }
    val dateFormat = try {
        Settings.DATE_TIME_FORMAT.valueCompat
    } catch (_: Throwable) {
        "0"
    }
    return formatMtDateWithOptions(timePref, dateFormat)
}
