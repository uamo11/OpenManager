/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.appmanager.running

import kotlin.math.abs

/**
 * What is warming the phone up, from one cheap sample (pure, for tests): CPU and battery
 * temperatures, screen brightness, GPU load, charging current and the apps using the most CPU.
 */
object HeatAnalysis {

    data class Sample(
        val cpuTempC: Float?,
        val batteryTempC: Float?,
        val gpuBusyPercent: Int?,
        val brightnessPercent: Int?,
        val autoBrightness: Boolean,
        val charging: Boolean,
        val chargeCurrentMa: Int?,
        /** Label and CPU % of the busiest processes, busiest first. */
        val topCpu: List<Pair<String, Float>>
    )

    enum class Level { COOL, WARM, HOT }

    sealed class Cause {
        data class App(val label: String, val cpuPercent: Float) : Cause()
        data class Brightness(val percent: Int) : Cause()
        data class Charging(val currentMa: Int?) : Cause()
        data class Gpu(val busyPercent: Int) : Cause()
    }

    data class Verdict(val level: Level, val causes: List<Cause>)

    /** The hotter of the CPU and the battery decides the level. */
    fun level(cpuTempC: Float?, batteryTempC: Float?): Level {
        val cpuLevel = when {
            cpuTempC == null -> Level.COOL
            cpuTempC >= 75f -> Level.HOT
            cpuTempC >= 55f -> Level.WARM
            else -> Level.COOL
        }
        val batteryLevel = when {
            batteryTempC == null -> Level.COOL
            batteryTempC >= 42f -> Level.HOT
            batteryTempC >= 37f -> Level.WARM
            else -> Level.COOL
        }
        return maxOf(cpuLevel, batteryLevel)
    }

    /** At most [max] causes, the strongest first; nothing when nothing stands out. */
    fun analyze(sample: Sample, max: Int = 3): Verdict {
        val scored = ArrayList<Pair<Float, Cause>>()
        for ((label, cpu) in sample.topCpu) {
            if (cpu >= APP_CPU_THRESHOLD) scored += cpu to Cause.App(label, cpu)
        }
        sample.gpuBusyPercent?.let { if (it >= GPU_THRESHOLD) scored += it * 0.8f to Cause.Gpu(it) }
        sample.brightnessPercent?.let {
            // High brightness heats the display and the battery; less so when it is automatic.
            if (it >= BRIGHTNESS_THRESHOLD) scored += (it - 40) * (if (sample.autoBrightness) 0.5f else 0.7f) to Cause.Brightness(it)
        }
        if (sample.charging) {
            val current = sample.chargeCurrentMa?.let { abs(it) }
            val weight = when {
                current == null -> 20f
                current >= 2000 -> 45f
                current >= 1000 -> 30f
                else -> 12f
            }
            scored += weight to Cause.Charging(current)
        }
        val causes = scored.sortedByDescending { it.first }.take(max).map { it.second }
        return Verdict(level(sample.cpuTempC, sample.batteryTempC), causes)
    }

    /**
     * Thermal zones as read from /sys/class/thermal (`type|temp` lines): the CPU temperature is
     * the hottest CPU-like zone. Values are millidegrees on most kernels, degrees on a few.
     */
    fun cpuTempFromZones(lines: List<String>): Float? {
        var hottest: Float? = null
        for (line in lines) {
            val type = line.substringBefore('|').trim().lowercase()
            val raw = line.substringAfter('|', "").trim().toFloatOrNull() ?: continue
            if (!CPU_ZONE.containsMatchIn(type)) continue
            val celsius = toCelsius(raw) ?: continue
            if (hottest == null || celsius > hottest) hottest = celsius
        }
        return hottest
    }

    fun toCelsius(raw: Float): Float? {
        val c = if (abs(raw) >= 1000f) raw / 1000f else if (abs(raw) >= 200f) raw / 10f else raw
        return c.takeIf { it in 5f..130f }
    }

    /** Brightness percent from the backlight's raw value and its maximum. */
    fun brightnessPercent(raw: Int?, max: Int?): Int? {
        if (raw == null || max == null || max <= 0 || raw < 0) return null
        return (raw * 100 / max).coerceIn(0, 100)
    }

    const val APP_CPU_THRESHOLD = 12f
    const val GPU_THRESHOLD = 50
    const val BRIGHTNESS_THRESHOLD = 70

    private val CPU_ZONE = Regex("cpu|tsens|soc|cluster|mtktscpu|apc|big|little|core")
}
