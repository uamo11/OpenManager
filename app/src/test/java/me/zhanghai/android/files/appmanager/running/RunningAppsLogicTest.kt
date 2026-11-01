/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.appmanager.running

import me.zhanghai.android.files.appmanager.AppDetailsViewerHelper.ComponentItem
import me.zhanghai.android.files.appmanager.AppDetailsViewerHelper.ComponentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunningAppsLogicTest {

    private val sampleProcesses = listOf(
        RunningAppModel(
            pid = 1200,
            ppid = 1,
            uid = 1000,
            processName = "system_server",
            packageName = null,
            label = "system_server",
            isApp = false,
            isSystem = true,
            memoryBytes = 350_000_000L,
            state = "S"
        ),
        RunningAppModel(
            pid = 14500,
            ppid = 1200,
            uid = 10180,
            processName = "com.whatsapp",
            packageName = "com.whatsapp",
            label = "WhatsApp",
            isApp = true,
            isSystem = false,
            memoryBytes = 180_000_000L,
            state = "R"
        ),
        RunningAppModel(
            pid = 16200,
            ppid = 1200,
            uid = 10190,
            processName = "org.telegram.messenger",
            packageName = "org.telegram.messenger",
            label = "Telegram",
            isApp = true,
            isSystem = false,
            memoryBytes = 220_000_000L,
            state = "R"
        ),
        RunningAppModel(
            pid = 2500,
            ppid = 1,
            uid = 1000,
            processName = "com.android.settings",
            packageName = "com.android.settings",
            label = "Ajustes",
            isApp = true,
            isSystem = true,
            memoryBytes = 90_000_000L,
            state = "S"
        )
    )

    @Test
    fun testRamStatsCalculation() {
        val total = 6_000_000_000L
        val avail = 3_600_000_000L
        val used = (total - avail).coerceAtLeast(0L)
        val percent = if (total > 0L) ((used * 100L) / total).toInt() else 0

        val stats = RunningAppsLoader.RamStats(
            totalBytes = total,
            availBytes = avail,
            usedBytes = used,
            usedPercent = percent
        )

        assertEquals(2_400_000_000L, stats.usedBytes)
        assertEquals(40, stats.usedPercent)
    }

    @Test
    fun testFilterUserProcesses() {
        val userApps = sampleProcesses.filter { it.isApp && !it.isSystem }
        assertEquals(2, userApps.size)
        assertTrue(userApps.any { it.packageName == "com.whatsapp" })
        assertTrue(userApps.any { it.packageName == "org.telegram.messenger" })
    }

    @Test
    fun testFilterSystemProcesses() {
        val systemProcesses = sampleProcesses.filter { it.isSystem }
        assertEquals(2, systemProcesses.size)
        assertTrue(systemProcesses.any { it.processName == "system_server" })
        assertTrue(systemProcesses.any { it.packageName == "com.android.settings" })
    }

    @Test
    fun testSearchFilterByLabel() {
        val query = "what"
        val matched = sampleProcesses.filter {
            it.label.lowercase().contains(query) ||
                it.processName.lowercase().contains(query) ||
                (it.packageName?.lowercase()?.contains(query) == true)
        }
        assertEquals(1, matched.size)
        assertEquals("WhatsApp", matched.first().label)
    }

    @Test
    fun testSearchFilterByPid() {
        val query = "16200"
        val matched = sampleProcesses.filter {
            it.pid.toString().contains(query)
        }
        assertEquals(1, matched.size)
        assertEquals("Telegram", matched.first().label)
    }

    @Test
    fun testSortByMemoryDescending() {
        val sorted = sampleProcesses.sortedByDescending { it.memoryBytes }
        assertEquals("system_server", sorted[0].processName) // 350 MB
        assertEquals("Telegram", sorted[1].label)            // 220 MB
        assertEquals("WhatsApp", sorted[2].label)            // 180 MB
        assertEquals("Ajustes", sorted[3].label)             // 90 MB
    }

    @Test
    fun testSortByLabelAscending() {
        val sorted = sampleProcesses.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        assertEquals("Ajustes", sorted[0].label)
        assertEquals("system_server", sorted[1].label)
        assertEquals("Telegram", sorted[2].label)
        assertEquals("WhatsApp", sorted[3].label)
    }

    @Test
    fun testSortByPidAscending() {
        val sorted = sampleProcesses.sortedBy { it.pid }
        assertEquals(1200, sorted[0].pid)
        assertEquals(2500, sorted[1].pid)
        assertEquals(14500, sorted[2].pid)
        assertEquals(16200, sorted[3].pid)
    }

    @Test
    fun testComponentItemClassification() {
        val items = listOf(
            ComponentItem("com.example.MainActivity", "Exported: true", ComponentType.ACTIVITY, isExported = true),
            ComponentItem("com.example.MyService", "Exported: false", ComponentType.SERVICE, isExported = false),
            ComponentItem("android.permission.CAMERA", "Concedido", ComponentType.PERMISSION, isGranted = true)
        )

        val activities = items.filter { it.type == ComponentType.ACTIVITY }
        val services = items.filter { it.type == ComponentType.SERVICE }
        val permissions = items.filter { it.type == ComponentType.PERMISSION }

        assertEquals(1, activities.size)
        assertTrue(activities.first().isExported)
        assertEquals(1, services.size)
        assertFalse(services.first().isExported)
        assertEquals(1, permissions.size)
        assertTrue(permissions.first().isGranted)
    }
}
