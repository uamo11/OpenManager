/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fixed-size pools whose threads end after a while without work. Executors.newFixedThreadPool()
 * keeps every thread it ever started for the life of the process: one remote folder with more
 * than 24 entries left 24 idle threads behind, each holding its stack and Thread objects.
 * Under load these behave exactly like a fixed pool of [threads].
 */
object IdleThreadPools {

    const val DEFAULT_KEEP_ALIVE_SECONDS = 20L

    fun fixed(
        name: String,
        threads: Int,
        keepAliveSeconds: Long = DEFAULT_KEEP_ALIVE_SECONDS
    ): ExecutorService =
        ThreadPoolExecutor(
            threads, threads, keepAliveSeconds, TimeUnit.SECONDS, LinkedBlockingQueue(),
            namedFactory(name)
        ).apply { allowCoreThreadTimeOut(true) }

    private fun namedFactory(name: String): ThreadFactory {
        val count = AtomicInteger()
        return ThreadFactory { runnable ->
            Thread(runnable, "$name-${count.incrementAndGet()}")
        }
    }
}
