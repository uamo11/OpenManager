/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The progress of running file jobs (copy, move, extract, compress, delete), as their
 * notifications show it, so the explorer can also show it on screen: a big extraction used to
 * give no sign of life outside the notification shade (user report).
 */
object FileJobProgress {

    data class Progress(
        val jobId: Int,
        val title: CharSequence,
        val text: CharSequence?,
        val max: Int,
        val progress: Int,
        val indeterminate: Boolean,
        val cancellable: Boolean,
        val startedAtMs: Long
    )

    /** How a job ended; [failed] when it threw. */
    data class Finished(
        val jobId: Int,
        val title: CharSequence?,
        val failed: Boolean,
        val atMs: Long,
        val durationMs: Long = 0
    )

    private val _running = MutableStateFlow<Map<Int, Progress>>(emptyMap())
    val running: StateFlow<Map<Int, Progress>> = _running

    private val _finished = MutableStateFlow<Finished?>(null)
    val finished: StateFlow<Finished?> = _finished

    private val startTimes = HashMap<Int, Long>()

    @Synchronized
    fun update(jobId: Int, title: CharSequence, text: CharSequence?, max: Int, progress: Int, indeterminate: Boolean, cancellable: Boolean) {
        val start = startTimes.getOrPut(jobId) { System.currentTimeMillis() }
        _running.value = _running.value + (jobId to Progress(jobId, title, text, max, progress, indeterminate, cancellable, start))
    }

    @Synchronized
    fun finish(jobId: Int, failed: Boolean) {
        val last = _running.value[jobId]
        startTimes.remove(jobId)
        _running.value = _running.value - jobId
        if (last != null) {
            val now = System.currentTimeMillis()
            _finished.value = Finished(jobId, last.title, failed, now, now - last.startedAtMs)
        }
    }
}
