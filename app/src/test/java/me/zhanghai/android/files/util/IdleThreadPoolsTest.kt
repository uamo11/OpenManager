/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class IdleThreadPoolsTest {

    @Test
    fun runsUpToItsSizeAtOnceThenLetsIdleThreadsEnd() {
        val pool = IdleThreadPools.fixed("OMTest", 3, keepAliveSeconds = 1) as ThreadPoolExecutor
        val started = CountDownLatch(3)
        val release = CountDownLatch(1)
        repeat(5) {
            pool.execute {
                started.countDown()
                release.await()
            }
        }
        assertTrue("three tasks run in parallel", started.await(5, TimeUnit.SECONDS))
        assertEquals(3, pool.poolSize)
        release.countDown()
        // Fixed pools never shrank; these let their threads go once idle.
        val deadline = System.currentTimeMillis() + 10_000
        while (pool.poolSize > 0 && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertEquals(0, pool.poolSize)
        assertEquals(5L, pool.completedTaskCount)
        pool.shutdown()
    }

    @Test
    fun threadsAreNamed() {
        val pool = IdleThreadPools.fixed("OMNamed", 1, keepAliveSeconds = 1)
        var name = ""
        pool.submit { name = Thread.currentThread().name }.get(5, TimeUnit.SECONDS)
        assertEquals("OMNamed-1", name)
        pool.shutdown()
    }
}
