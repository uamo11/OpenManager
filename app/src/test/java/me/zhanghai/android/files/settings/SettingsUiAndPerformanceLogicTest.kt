/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import me.zhanghai.android.files.settings.SettingsUiLogic.ThemeQuickPreset
import me.zhanghai.android.files.theme.night.NightMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsUiAndPerformanceLogicTest {

    private fun resolveMainFile(relPath: String): File {
        val f1 = File("src/main/$relPath")
        return if (f1.exists()) f1 else File("app/src/main/$relPath")
    }

    private fun relativeLuminance(color: Int): Double {
        val r = ((color shr 16) and 0xFF) / 255.0
        val g = ((color shr 8) and 0xFF) / 255.0
        val b = (color and 0xFF) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    @Test
    fun testNormalizeQueryAndSynonymSearch() {
        assertEquals("", SettingsUiLogic.normalizeQuery(null))
        assertEquals("", SettingsUiLogic.normalizeQuery("   \n\t  "))
        assertEquals("diseno animacion icon", SettingsUiLogic.normalizeQuery("  Diseño   Animación   ICÓN "))

        // Matches diacritic-insensitive query across title/summary/entries
        assertTrue(
            SettingsUiLogic.matchesSearch(
                normalizedQuery = SettingsUiLogic.normalizeQuery("líneas diseño"),
                title = "Diseño de líneas máximas",
                summary = null,
                entries = null
            )
        )

        // Matches bilingual ES/EN synonyms stored in keywordsForKey
        val thumbQuery = SettingsUiLogic.normalizeQuery("miniaturas ram")
        assertTrue(
            SettingsUiLogic.matchesSearch(
                normalizedQuery = thumbQuery,
                title = "Thumbnail & icon rendering",
                summary = null,
                entries = arrayOf("Full", "Optimized", "Vector icons only"),
                keywords = SettingsUiLogic.keywordsForKey("key_thumbnail_load_mode")
            )
        )

        val amoledQuery = SettingsUiLogic.normalizeQuery("oled negro puro")
        assertTrue(
            SettingsUiLogic.matchesSearch(
                normalizedQuery = amoledQuery,
                title = "AMOLED mode",
                summary = "Save battery",
                entries = null,
                keywords = SettingsUiLogic.keywordsForKey("key_black_night_mode")
            )
        )

        // Multi-token AND semantics: all tokens must match
        assertFalse(
            SettingsUiLogic.matchesSearch(
                normalizedQuery = SettingsUiLogic.normalizeQuery("miniaturas bluetooth"),
                title = "Renderizado de miniaturas e iconos",
                summary = "Solo iconos vectoriales",
                entries = null,
                keywords = SettingsUiLogic.keywordsForKey("key_thumbnail_load_mode")
            )
        )
    }

    @Test
    fun testComputeIconBadgeColorsContrastAcrossThemes() {
        val brightYellow = 0xFFFFEB3B.toInt()
        val veryDarkNavy = 0xFF1A237E.toInt()
        val emerald = 0xFF10B981.toInt()

        // Light mode must darken overly bright semantic colors so icons remain sharp on white
        val lightYellow = SettingsUiLogic.computeIconBadgeColors(brightYellow, isDark = false, isAmoled = false)
        assertTrue(
            "Bright yellow fg must be darkened in Light mode, was lum=${relativeLuminance(lightYellow.fgColor)}",
            relativeLuminance(lightYellow.fgColor) <= 0.55
        )
        assertNotEquals(0, (lightYellow.bgColor ushr 24) and 0xFF)
        assertNotEquals(0, (lightYellow.strokeColor ushr 24) and 0xFF)

        // Dark and AMOLED must boost dark semantic colors so icons pop on dark surfaces
        val darkNavy = SettingsUiLogic.computeIconBadgeColors(veryDarkNavy, isDark = true, isAmoled = false)
        val amoledNavy = SettingsUiLogic.computeIconBadgeColors(veryDarkNavy, isDark = true, isAmoled = true)
        assertTrue(relativeLuminance(darkNavy.fgColor) > relativeLuminance(veryDarkNavy))
        assertEquals(darkNavy.fgColor, amoledNavy.fgColor)

        val darkEmerald = SettingsUiLogic.computeIconBadgeColors(emerald, isDark = true, isAmoled = false)
        val lightEmerald = SettingsUiLogic.computeIconBadgeColors(emerald, isDark = false, isAmoled = false)
        assertNotEquals(darkEmerald.bgColor, lightEmerald.bgColor)
    }

    @Test
    fun testResolveThemeQuickPresetAllCombinations() {
        assertEquals(ThemeQuickPreset.LIGHT, SettingsUiLogic.resolveThemeQuickPreset(NightMode.OFF, false))
        assertEquals(ThemeQuickPreset.LIGHT, SettingsUiLogic.resolveThemeQuickPreset(NightMode.OFF, true))
        assertEquals(ThemeQuickPreset.DARK, SettingsUiLogic.resolveThemeQuickPreset(NightMode.ON, false))
        assertEquals(ThemeQuickPreset.AMOLED, SettingsUiLogic.resolveThemeQuickPreset(NightMode.ON, true))
        assertEquals(ThemeQuickPreset.SYSTEM, SettingsUiLogic.resolveThemeQuickPreset(NightMode.FOLLOW_SYSTEM, false))
        assertEquals(ThemeQuickPreset.SYSTEM, SettingsUiLogic.resolveThemeQuickPreset(NightMode.AUTO_TIME, true))
    }

    @Test
    fun testComputeVisibleRefreshRangeEdgeCasesAndClamping() {
        assertNull(SettingsUiLogic.computeVisibleRefreshRange(0, 5, 0))
        assertNull(SettingsUiLogic.computeVisibleRefreshRange(-1, 5, 20))
        assertNull(SettingsUiLogic.computeVisibleRefreshRange(6, 2, 20))

        // Standard viewport with buffer = 4 clamped cleanly at bounds
        assertEquals(0..11, SettingsUiLogic.computeVisibleRefreshRange(2, 7, 50, buffer = 4))
        assertEquals(41..49, SettingsUiLogic.computeVisibleRefreshRange(45, 48, 50, buffer = 4))
        assertEquals(0..0, SettingsUiLogic.computeVisibleRefreshRange(0, 0, 1, buffer = 4))
    }

    @Test
    fun testSettingsXmlIncludesHubPerformanceCategoryAndIconMenus() {
        val settingsFile = resolveMainFile("res/xml/settings.xml")
        assertTrue("settings.xml must exist", settingsFile.exists())
        val xml = settingsFile.readText()

        assertTrue(xml.contains("android:key=\"key_appearance_performance_hub\""))
        assertTrue(xml.contains("me.zhanghai.android.files.settings.AppearancePerformanceHubPreference"))
        assertTrue(xml.contains("android:key=\"key_category_visual_performance\""))
        assertTrue(xml.contains("me.zhanghai.android.files.settings.IconMenuPreference"))
        assertFalse(
            "SimpleMenuPreference should be replaced by IconMenuPreference in settings.xml",
            xml.contains("rikka.preference.SimpleMenuPreference")
        )
        assertTrue(
            "Auto update toggle must remain SwitchPreferenceCompat",
            xml.contains("<SwitchPreferenceCompat") && xml.contains("@string/pref_key_auto_check_updates")
        )
    }
}
