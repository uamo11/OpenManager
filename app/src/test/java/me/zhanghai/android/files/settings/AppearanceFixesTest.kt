/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import me.zhanghai.android.files.app.ExitReasonLogger
import me.zhanghai.android.files.settings.UltraRamVisualDefaults.Visual
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppearanceFixesTest {

    private val md3Standard = Visual(materialDesign3 = true, dualPanelContrast = "0")
    private val classicHigh = Visual(materialDesign3 = false, dualPanelContrast = "1")

    @Test
    fun ultraRamPicksTheLightestLookAndRemembersTheUsersOne() {
        val on = UltraRamVisualDefaults.plan(enabled = true, current = md3Standard, remembered = null)
        assertEquals(Visual(materialDesign3 = false, dualPanelContrast = "2", thumbnailLoadMode = "2"), on.apply)
        assertEquals(md3Standard, on.remember)
        // Turning it off puts everything back.
        val off = UltraRamVisualDefaults.plan(enabled = false, current = on.apply, remembered = on.remember)
        assertEquals(md3Standard, off.apply)
        assertNull(off.remember)
    }

    @Test
    fun choicesMadeWhileUltraRamIsOnAreKept() {
        // While on, the user turned Material 3 back on by hand: keep it, restore only the panels.
        val off = UltraRamVisualDefaults.plan(
            enabled = false,
            current = Visual(materialDesign3 = true, dualPanelContrast = "2"),
            remembered = classicHigh
        )
        assertEquals(Visual(materialDesign3 = true, dualPanelContrast = "1"), off.apply)
    }

    @Test
    fun thumbnailsTurnedOnInsideUltraRamStayOnAfterwards() {
        // Ultra RAM starts with icons only; the user asked for thumbnails while it was on.
        val off = UltraRamVisualDefaults.plan(
            enabled = false,
            current = Visual(materialDesign3 = false, dualPanelContrast = "2", thumbnailLoadMode = "1"),
            remembered = Visual(materialDesign3 = true, dualPanelContrast = "0", thumbnailLoadMode = "0")
        )
        assertEquals(Visual(materialDesign3 = true, dualPanelContrast = "0", thumbnailLoadMode = "1"), off.apply)
    }

    @Test
    fun ultraRamLoadsThumbnailsLightlyOnlyWhenChosen() {
        val fle = me.zhanghai.android.files.filelist.FileListEngine
        // Icons only: never.
        assertFalse(fle.canDecodeThumbnails(2, ultraRam = true, flinging = false, itemCount = 10))
        // Chosen inside Ultra RAM: yes, but never while flinging (even a short list).
        assertTrue(fle.canDecodeThumbnails(0, ultraRam = true, flinging = false, itemCount = 10))
        assertFalse(fle.canDecodeThumbnails(0, ultraRam = true, flinging = true, itemCount = 10))
        // Normal mode, short list: also while flinging, as before.
        assertTrue(fle.canDecodeThumbnails(0, ultraRam = false, flinging = true, itemCount = 10))
    }

    @Test
    fun thumbnailDiskCacheDropsTheOldestPastItsBudget() {
        val dir = java.nio.file.Files.createTempDirectory("om_thumbs_test").toFile()
        try {
            for (i in 0 until 10) {
                java.io.File(dir, "t$i.webp").apply {
                    writeBytes(ByteArray(1000))
                    setLastModified(1_000_000L + i * 1000L)
                }
            }
            me.zhanghai.android.files.coil.ThumbnailDiskCache.trim(dir, maxBytes = 5000)
            val left = dir.listFiles()!!.map { it.name }.sorted()
            assertTrue(left.size <= 4)
            assertTrue("newest kept", "t9.webp" in left)
            assertFalse("oldest dropped", "t0.webp" in left)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun turningOnTwiceKeepsTheFirstRememberedLook() {
        val again = UltraRamVisualDefaults.plan(
            enabled = true, current = UltraRamVisualDefaults.ULTRA, remembered = md3Standard
        )
        assertEquals(md3Standard, again.remember)
    }

    @Test
    fun turningOffWithoutMemoryChangesNothing() {
        val off = UltraRamVisualDefaults.plan(enabled = false, current = classicHigh, remembered = null)
        assertEquals(classicHigh, off.apply)
    }

    @Test
    fun everyThemeColorHasAMaterial3OverlayForDayAndNight() {
        val res = File("src/main/res").takeIf { it.isDirectory } ?: File("app/src/main/res")
        val day = File(res, "values/themes_material3_accent.xml").readText()
        val night = File(res, "values-night/themes_material3_accent.xml").readText()
        for (color in me.zhanghai.android.files.theme.custom.ThemeColor.entries) {
            if (color == me.zhanghai.android.files.theme.custom.ThemeColor.COLOR_PRIMARY) continue
            val name = "ThemeOverlay.MaterialFiles.Material3Accent.${color.name.lowercase()}"
            assertTrue("missing $name", day.contains("\"$name\""))
            assertTrue("missing night $name", night.contains("\"$name\""))
        }
    }

    @Test
    fun onlyAbnormalExitsAreReported() {
        assertTrue(ExitReasonLogger.isWorthReporting(6)) // ANR
        assertTrue(ExitReasonLogger.isWorthReporting(5)) // native crash
        assertTrue(ExitReasonLogger.isWorthReporting(3)) // low memory
        assertFalse(ExitReasonLogger.isWorthReporting(10)) // user requested
        assertFalse(ExitReasonLogger.isWorthReporting(1)) // exit self
        assertFalse(ExitReasonLogger.isWorthReporting(4)) // Java crash: CrashHandler logs it
    }
}
