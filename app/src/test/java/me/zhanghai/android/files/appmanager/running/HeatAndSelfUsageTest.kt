/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.appmanager.running

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatAndSelfUsageTest {

    private fun sample(
        cpu: Float? = 45f,
        battery: Float? = 33f,
        gpu: Int? = null,
        brightness: Int? = 40,
        auto: Boolean = false,
        charging: Boolean = false,
        current: Int? = null,
        top: List<Pair<String, Float>> = emptyList()
    ) = HeatAnalysis.Sample(cpu, battery, gpu, brightness, auto, charging, current, top)

    @Test
    fun theHotterOfCpuAndBatteryDecides() {
        assertEquals(HeatAnalysis.Level.COOL, HeatAnalysis.level(45f, 33f))
        assertEquals(HeatAnalysis.Level.WARM, HeatAnalysis.level(60f, 33f))
        assertEquals(HeatAnalysis.Level.WARM, HeatAnalysis.level(45f, 38f))
        assertEquals(HeatAnalysis.Level.HOT, HeatAnalysis.level(80f, 33f))
        assertEquals(HeatAnalysis.Level.HOT, HeatAnalysis.level(null, 43f))
    }

    @Test
    fun theBusiestAppComesFirstAndQuietThingsAreLeftOut() {
        val verdict = HeatAnalysis.analyze(
            sample(
                brightness = 95, charging = true, current = 2400,
                top = listOf("Game" to 48f, "Chat" to 3f)
            )
        )
        assertEquals(HeatAnalysis.Cause.App("Game", 48f), verdict.causes.first())
        assertTrue(verdict.causes.any { it is HeatAnalysis.Cause.Charging })
        assertTrue(verdict.causes.any { it is HeatAnalysis.Cause.Brightness })
        // 3% CPU is not a cause.
        assertTrue(verdict.causes.none { it is HeatAnalysis.Cause.App && it.label == "Chat" })
        assertTrue(verdict.causes.size <= 3)
        // Nothing stands out: no causes at all.
        assertTrue(HeatAnalysis.analyze(sample()).causes.isEmpty())
    }

    @Test
    fun thermalZonesInMillidegreesOrDegrees() {
        val zones = listOf("cpu-0-0-usr|61000", "cpu-1-0-usr|58000", "battery|36000", "xo-therm|42000")
        assertEquals(61f, HeatAnalysis.cpuTempFromZones(zones)!!, 0.01f)
        assertEquals(47f, HeatAnalysis.cpuTempFromZones(listOf("mtktscpu|47"))!!, 0.01f)
        // Nonsense readings are ignored.
        assertNull(HeatAnalysis.cpuTempFromZones(listOf("cpu0|-273000", "battery|35000")))
        assertEquals(90, HeatAnalysis.brightnessPercent(3686, 4095))
        assertNull(HeatAnalysis.brightnessPercent(10, 0))
    }

    @Test
    fun aRecordedRootReadingBecomesASample() {
        val sample = HeatMonitor.sampleFrom(
            listOf("Z|cpu-0-0-usr|61000", "Z|battery|36000", "B|3686|4095", "G|64"), null,
            listOf("Game" to 41f)
        )
        assertEquals(61f, sample.cpuTempC!!, 0.01f)
        assertEquals(90, sample.brightnessPercent)
        assertEquals(64, sample.gpuBusyPercent)
    }

    @Test
    fun selfUsageSplitsTheAppFromItsScripts() {
        val uid = 10123
        val list = listOf(
            RunningAppModel(pid = 100, uid = uid, processName = "com.y.om", label = "OpenManager", isApp = true, memoryBytes = 80L shl 20),
            RunningAppModel(pid = 200, uid = uid, processName = "bash", label = "backup.sh", isApp = false, memoryBytes = 4L shl 20),
            RunningAppModel(pid = 201, uid = uid, processName = "sh", label = "sh", isApp = false, memoryBytes = 2L shl 20),
            // Another user's process and a "recent app" row (no pid) are not ours.
            RunningAppModel(pid = 300, uid = 10200, processName = "com.other", label = "Other", isApp = true, memoryBytes = 500L shl 20),
            RunningAppModel(pid = -1, uid = uid, processName = "recent", label = "Recent", isApp = true, memoryBytes = 0)
        )
        val usage = RunningAppsLoader.selfUsage(list, uid)
        assertEquals(80L shl 20, usage.appBytes)
        assertEquals(6L shl 20, usage.scriptBytes)
        assertEquals(2, usage.scriptCount)
        assertEquals(86L shl 20, usage.totalBytes)
        assertEquals(1234L, RunningAppsLoader.parseKb("Pss:            1234 kB"))
    }
}
