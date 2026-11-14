/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.theme

import me.zhanghai.android.files.file.formatMtDateWithOptions
import me.zhanghai.android.files.theme.custom.OpenManagerPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class AppearanceThemeLogicTest {

    private val defaultAccent = 0xFF2196F3.toInt()
    private val brightAccent = 0xFFFFFF00.toInt()

    @Test
    fun darkDefaultPreservesOriginalColors() {
        val p = OpenManagerPalette.compute(
            isDark = true,
            isAmoled = false,
            accentColor = defaultAccent,
            dualPanelContrast = OpenManagerPalette.PANEL_CONTRAST_STANDARD
        )
        assertTrue(p.isDark)
        assertFalse(p.isAmoled)
        assertFalse(p.isLightStatusBar)
        assertEquals(0xFF151515.toInt(), p.toolbarBg)
        assertEquals(0xFF1F1F1F.toInt(), p.appBarBg)
        assertEquals(0xFF363636.toInt(), p.activePanelBg)
        assertEquals(0xFF242424.toInt(), p.inactivePanelBg)
        assertEquals(0xFF1D1D1D.toInt(), p.paneDivider)
        assertEquals(0xFF202020.toInt(), p.bottomBarBg)
        assertEquals(0xFF303030.toInt(), p.drawerBg)
        assertEquals(0xFF151515.toInt(), p.drawerHeaderBg)
        assertEquals(0xFF282828.toInt(), p.drawerCircleBg)
        assertEquals(0xFFEAEAEA.toInt(), p.filePrimaryText)
        assertEquals(0xFF888888.toInt(), p.textSecondary)
        assertEquals(0xFFDCDCDC.toInt(), p.drawerIconTint)
        assertEquals(0xFF2B2B2B.toInt(), p.dividerLineColor)
    }

    @Test
    fun amoledModeUsesPureBlackSurfacesAndSubtleElevatedActivePanel() {
        val p = OpenManagerPalette.compute(
            isDark = true,
            isAmoled = true,
            accentColor = defaultAccent,
            dualPanelContrast = OpenManagerPalette.PANEL_CONTRAST_STANDARD
        )
        assertTrue(p.isDark)
        assertTrue(p.isAmoled)
        assertFalse(p.isLightStatusBar)
        assertEquals(0xFF000000.toInt(), p.toolbarBg)
        assertEquals(0xFF000000.toInt(), p.appBarBg)
        assertEquals(0xFF0C0C0C.toInt(), p.activePanelBg)
        assertEquals(0xFF000000.toInt(), p.inactivePanelBg)
        assertEquals(0xFF000000.toInt(), p.bottomBarBg)
        assertEquals(0xFF000000.toInt(), p.drawerBg)
        assertEquals(0xFF000000.toInt(), p.screenBg)
        assertEquals(0xFFFFFFFF.toInt(), p.textPrimary)
    }

    @Test
    fun lightModeUsesLightSurfacesAndIgnoresAmoledFlag() {
        val p = OpenManagerPalette.compute(
            isDark = false,
            isAmoled = true,
            accentColor = defaultAccent,
            dualPanelContrast = OpenManagerPalette.PANEL_CONTRAST_STANDARD
        )
        assertFalse(p.isDark)
        assertFalse(p.isAmoled)
        assertTrue(p.isLightStatusBar)
        assertEquals(0xFFF5F5F5.toInt(), p.toolbarBg)
        assertEquals(0xFFFFFFFF.toInt(), p.appBarBg)
        assertEquals(0xFFFFFFFF.toInt(), p.activePanelBg)
        assertEquals(0xFFECEFF2.toInt(), p.inactivePanelBg)
        assertEquals(0xFF1C1B1F.toInt(), p.textPrimary)
        assertEquals(0xFF202124.toInt(), p.iconTint)
    }

    @Test
    fun dualPanelContrastVariantsAffectPanelsAndDivider() {
        val highDark = OpenManagerPalette.compute(
            isDark = true,
            isAmoled = false,
            accentColor = 0xFFE91E63.toInt(),
            dualPanelContrast = OpenManagerPalette.PANEL_CONTRAST_HIGH
        )
        assertEquals(0xFF3E3E3E.toInt(), highDark.activePanelBg)
        assertEquals(0xFF1C1C1C.toInt(), highDark.inactivePanelBg)
        assertEquals(0xFFE91E63.toInt(), highDark.paneDivider)

        val uniformDark = OpenManagerPalette.compute(
            isDark = true,
            isAmoled = false,
            accentColor = defaultAccent,
            dualPanelContrast = OpenManagerPalette.PANEL_CONTRAST_UNIFORM
        )
        assertEquals(uniformDark.activePanelBg, uniformDark.inactivePanelBg)
        assertEquals(0xFF363636.toInt(), uniformDark.activePanelBg)

        val highLight = OpenManagerPalette.compute(
            isDark = false,
            isAmoled = false,
            accentColor = defaultAccent,
            dualPanelContrast = OpenManagerPalette.PANEL_CONTRAST_HIGH
        )
        assertNotEquals(highLight.activePanelBg, highLight.inactivePanelBg)
        assertEquals(defaultAccent, highLight.paneDivider)
    }

    @Test
    fun onAccentColorAdaptsContrastByLuminance() {
        val darkAccentPalette = OpenManagerPalette.compute(
            isDark = true,
            isAmoled = false,
            accentColor = 0xFF1565C0.toInt()
        )
        assertEquals(0xFFFFFFFF.toInt(), darkAccentPalette.onAccentColor)

        val lightAccentPalette = OpenManagerPalette.compute(
            isDark = false,
            isAmoled = false,
            accentColor = brightAccent
        )
        assertEquals(0xFF121212.toInt(), lightAccentPalette.onAccentColor)
    }

    @Test
    fun formatMtDateWithOptionsCoversAllTimeAndDateFormatCombinations() {
        // 2026-11-13T14:05:09Z
        val instant = Instant.parse("2026-11-13T14:05:09Z")
        val utc = ZoneOffset.UTC

        assertEquals("2026-11-13 14:05:09", instant.formatMtDateWithOptions("0", "0", utc))
        assertEquals("13/11/2026 14:05:09", instant.formatMtDateWithOptions("0", "1", utc))
        assertEquals("11/13/2026 14:05:09", instant.formatMtDateWithOptions("0", "2", utc))

        assertEquals("26-11-13 14:05", instant.formatMtDateWithOptions("1", "0", utc))
        assertEquals("13/11/26 14:05", instant.formatMtDateWithOptions("1", "1", utc))
        assertEquals("11/13/26 14:05", instant.formatMtDateWithOptions("1", "2", utc))
    }
}
