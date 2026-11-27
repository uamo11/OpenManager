/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Files opened from archives were copied to open_cache and never removed (user report). */
class OpenCacheTrimTest {

    private fun file(dir: File, name: String, bytes: Int, ageMillis: Long, now: Long) =
        File(dir, name).apply {
            writeBytes(ByteArray(bytes))
            setLastModified(now - ageMillis)
        }

    @Test
    fun copiesOlderThanADayGo() {
        val dir = Files.createTempDirectory("open_cache").toFile()
        try {
            val now = 10_000_000_000L
            file(dir, "old.jpg", 10, 2 * 24 * 3600 * 1000L, now)
            file(dir, "new.jpg", 10, 60_000L, now)
            trimOpenCache(dir, now = now)
            assertFalse(File(dir, "old.jpg").exists())
            assertTrue(File(dir, "new.jpg").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theOldestGoUntilUnderTheCap() {
        val dir = Files.createTempDirectory("open_cache").toFile()
        try {
            val now = 10_000_000_000L
            file(dir, "a.png", 400, 30_000L, now)
            file(dir, "b.png", 400, 20_000L, now)
            file(dir, "c.png", 400, 10_000L, now)
            trimOpenCache(dir, maxBytes = 900, now = now)
            assertEquals(setOf("b.png", "c.png"), dir.list()!!.toSet())
        } finally {
            dir.deleteRecursively()
        }
    }
}
