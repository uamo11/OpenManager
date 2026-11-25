/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crashes in Kotlin/Java code are already written by [CrashHandler], but freezes (ANR), native
 * crashes and kills for lack of memory leave no trace in the app. On Android 11+ the system keeps
 * the reason of each past exit: at start-up the new ones are written to the same logs folder,
 * so "it just closed" can be diagnosed. Nothing is shown to the user.
 */
object ExitReasonLogger {
    private const val TAG = "OMExitReason"
    private const val PREFS = "om_exit_reasons"
    private const val KEY_LAST_SEEN = "last_seen_timestamp"
    private const val MAX_TRACE_BYTES = 256 * 1024

    /**
     * Exits worth a report: the app did not close by itself or by the user. A kill for memory
     * while OpenManager sat in the background (cached, importance 400) is Android's normal way of
     * making room, not a failure: only kills while it was on screen or working are reported.
     */
    fun isWorthReporting(reason: Int, importance: Int = IMPORTANCE_FOREGROUND): Boolean =
        reason in REPORTED_REASONS && (reason != REASON_LOW_MEMORY || importance <= IMPORTANCE_PERCEPTIBLE)

    private const val REASON_LOW_MEMORY = 3
    private const val IMPORTANCE_FOREGROUND = 100
    // ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE: playing audio, a service...
    private const val IMPORTANCE_PERCEPTIBLE = 230

    private val REPORTED_REASONS = setOf(
        6, // REASON_ANR
        5, // REASON_CRASH_NATIVE
        3, // REASON_LOW_MEMORY
        9, // REASON_EXCESSIVE_RESOURCE_USAGE
        7, // REASON_INITIALIZATION_FAILURE
    )

    fun reasonName(reason: Int): String = when (reason) {
        6 -> "ANR (the screen froze)"
        5 -> "Native crash"
        3 -> "Killed for low memory"
        9 -> "Excessive resource usage"
        7 -> "Initialization failure"
        else -> "Reason $reason"
    }

    fun logNewExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            logNewExitsApi30(context)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read exit reasons", t)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun logNewExitsApi30(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(KEY_LAST_SEEN, 0L)
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val exits = activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 10)
        if (exits.isEmpty()) return
        val newest = exits.maxOf { it.timestamp }
        // First run: remember where we are, do not dump old history.
        if (lastSeen == 0L) {
            prefs.edit().putLong(KEY_LAST_SEEN, newest).apply()
            return
        }
        val fresh = exits.filter { it.timestamp > lastSeen && isWorthReporting(it.reason, it.importance) }
        prefs.edit().putLong(KEY_LAST_SEEN, maxOf(newest, lastSeen)).apply()
        if (fresh.isEmpty()) return
        val dir = CrashHandler.ensureLogsDirectory() ?: return
        for (exit in fresh) {
            write(dir, exit)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun write(dir: File, exit: ApplicationExitInfo) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date(exit.timestamp))
        val file = File(dir, "exit_${stamp}_${exit.reason}.txt")
        if (file.exists()) return
        val text = buildString {
            append("OpenManager closed unexpectedly\n")
            append("Reason: ").append(reasonName(exit.reason)).append('\n')
            append("Time: ").append(Date(exit.timestamp)).append('\n')
            append("Process: ").append(exit.processName).append(" (pid ").append(exit.pid).append(")\n")
            append("Importance: ").append(exit.importance).append('\n')
            append("Memory (PSS / RSS): ").append(exit.pss / 1024).append(" / ")
                .append(exit.rss / 1024).append(" MB\n")
            exit.description?.let { append("Description: ").append(it).append('\n') }
            append("Android: ").append(Build.VERSION.RELEASE).append(" (API ")
                .append(Build.VERSION.SDK_INT).append(")  Device: ")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            // ANRs and native crashes carry the thread dump / tombstone.
            val trace = try {
                exit.traceInputStream?.use { input ->
                    val buffer = ByteArray(MAX_TRACE_BYTES)
                    var total = 0
                    while (total < buffer.size) {
                        val read = input.read(buffer, total, buffer.size - total)
                        if (read <= 0) break
                        total += read
                    }
                    String(buffer, 0, total, Charsets.UTF_8)
                }
            } catch (_: Throwable) {
                null
            }
            if (!trace.isNullOrEmpty()) {
                append("\n--- Trace ---\n").append(trace)
            }
        }
        try {
            file.writeText(text)
            Log.i(TAG, "Wrote ${file.name}")
        } catch (t: Throwable) {
            Log.w(TAG, "Could not write ${file.name}", t)
        }
    }
}
