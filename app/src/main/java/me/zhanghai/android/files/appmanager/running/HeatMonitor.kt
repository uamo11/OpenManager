/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.appmanager.running

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import com.topjohnwu.superuser.Shell

/**
 * Reads what [HeatAnalysis] needs, cheaply: one root command per refresh for the thermal zones,
 * the real backlight level and the GPU load, plus the battery from Android itself. Only used
 * with root (the card is not shown without it).
 */
object HeatMonitor {

    private const val SCRIPT =
        "for z in /sys/class/thermal/thermal_zone*; do " +
            "t=\$(cat \$z/type 2>/dev/null); v=\$(cat \$z/temp 2>/dev/null); " +
            "[ -n \"\$v\" ] && echo \"Z|\$t|\$v\"; done; " +
            "for b in /sys/class/backlight/*; do " +
            "echo \"B|\$(cat \$b/brightness 2>/dev/null)|\$(cat \$b/max_brightness 2>/dev/null)\"; break; done; " +
            "g=\$(cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage 2>/dev/null); " +
            "[ -z \"\$g\" ] && g=\$(cat /sys/kernel/gpu/gpu_busy 2>/dev/null); " +
            "echo \"G|\$g\""

    fun read(context: Context, topCpu: List<Pair<String, Float>>): HeatAnalysis.Sample {
        val lines = try {
            Shell.cmd(SCRIPT).exec().out
        } catch (_: Throwable) {
            emptyList()
        }
        return sampleFrom(lines, context, topCpu)
    }

    internal fun sampleFrom(
        lines: List<String>,
        context: Context?,
        topCpu: List<Pair<String, Float>>
    ): HeatAnalysis.Sample {
        val zones = lines.filter { it.startsWith("Z|") }.map { it.removePrefix("Z|") }
        val backlight = lines.firstOrNull { it.startsWith("B|") }?.split('|')
        val gpu = lines.firstOrNull { it.startsWith("G|") }?.substringAfter("G|")?.trim()
            ?.substringBefore(' ')?.removeSuffix("%")?.toIntOrNull()?.takeIf { it in 0..100 }

        var brightness = HeatAnalysis.brightnessPercent(
            backlight?.getOrNull(1)?.trim()?.toIntOrNull(), backlight?.getOrNull(2)?.trim()?.toIntOrNull()
        )
        var auto = false
        var batteryTemp: Float? = null
        var charging = false
        var currentMa: Int? = null
        if (context != null) {
            val resolver = context.contentResolver
            auto = runCatching {
                Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            }.getOrDefault(false)
            if (brightness == null) {
                brightness = runCatching {
                    Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS) * 100 / 255
                }.getOrNull()?.coerceIn(0, 100)
            }
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }?.let { batteryTemp = it / 10f }
            val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING
            if (charging) {
                val manager = context.getSystemService(BatteryManager::class.java)
                val microAmps = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                // Some phones report mA instead of µA; both end up in a sane mA range.
                currentMa = microAmps?.takeIf { it != Int.MIN_VALUE && it != 0 }?.let {
                    if (kotlin.math.abs(it) > 20_000) it / 1000 else it
                }
            }
        }
        return HeatAnalysis.Sample(
            cpuTempC = HeatAnalysis.cpuTempFromZones(zones),
            batteryTempC = batteryTemp,
            gpuBusyPercent = gpu,
            brightnessPercent = brightness,
            autoBrightness = auto,
            charging = charging,
            chargeCurrentMa = currentMa,
            topCpu = topCpu
        )
    }
}
