/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

/**
 * Android's own bugs that reach our process from its threads and would otherwise close the app,
 * although nothing of ours failed. Matched narrowly (thread, type, message and frames) so real
 * crashes are never hidden.
 */
object FrameworkGlitches {

    /**
     * MediaScannerConnection releases its MediaProvider connection on the system "android.bg"
     * thread; on some Android 14+ builds the system then answers "stableCount < 0" and the
     * exception would kill the app. The scan itself is lost, nothing else.
     */
    fun isHarmless(threadName: String, throwable: Throwable): Boolean {
        if (threadName != "android.bg") return false
        if (throwable !is IllegalStateException) return false
        if (throwable.message?.startsWith("stableCount < 0") != true) return false
        return throwable.stackTrace.any { it.className.startsWith("android.media.MediaScannerConnection") }
    }
}
