/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.theme.custom

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import me.zhanghai.android.files.R
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.theme.night.NightMode
import me.zhanghai.android.files.theme.night.NightModeHelper
import me.zhanghai.android.files.util.valueCompat

/**
 * Semantic color palette for OpenManager across Light Mode, Default Dark Mode (MT style),
 * and True Black AMOLED Mode, combined with user accent color and dual-panel contrast settings.
 */
data class OpenManagerPalette(
    val isDark: Boolean,
    val isAmoled: Boolean,
    val isLightStatusBar: Boolean,
    @ColorInt val accentColor: Int,
    @ColorInt val toolbarBg: Int,
    @ColorInt val appBarBg: Int,
    @ColorInt val activePanelBg: Int,
    @ColorInt val inactivePanelBg: Int,
    @ColorInt val paneDivider: Int,
    @ColorInt val bottomBarBg: Int,
    @ColorInt val drawerBg: Int,
    @ColorInt val drawerHeaderBg: Int,
    @ColorInt val drawerCircleBg: Int,
    @ColorInt val dialogBg: Int,
    @ColorInt val cardBg: Int,
    @ColorInt val screenBg: Int,
    @ColorInt val textPrimary: Int,
    @ColorInt val filePrimaryText: Int,
    @ColorInt val textSecondary: Int,
    @ColorInt val iconTint: Int,
    @ColorInt val drawerIconTint: Int,
    @ColorInt val dividerLineColor: Int
) {
    /**
     * Contrasting foreground color to draw on top of [accentColor] (e.g. FAB icons).
     */
    @get:ColorInt
    val onAccentColor: Int
        get() {
            val r = ((accentColor shr 16) and 0xFF) / 255.0
            val g = ((accentColor shr 8) and 0xFF) / 255.0
            val b = (accentColor and 0xFF) / 255.0
            val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
            return if (luminance > 0.55) 0xFF121212.toInt() else 0xFFFFFFFF.toInt()
        }

    companion object {
        const val PANEL_CONTRAST_STANDARD = 0
        const val PANEL_CONTRAST_HIGH = 1
        const val PANEL_CONTRAST_UNIFORM = 2

        /**
         * Pure computation of [OpenManagerPalette] without Android framework dependencies,
         * suitable for deterministic rendering and JVM unit tests.
         */
        fun compute(
            isDark: Boolean,
            isAmoled: Boolean,
            @ColorInt accentColor: Int,
            dualPanelContrast: Int = PANEL_CONTRAST_STANDARD
        ): OpenManagerPalette {
            val effectiveAmoled = isDark && isAmoled
            val toolbarBg: Int
            val appBarBg: Int
            val baseActivePanel: Int
            val baseInactivePanel: Int
            val basePaneDivider: Int
            val bottomBarBg: Int
            val drawerBg: Int
            val drawerHeaderBg: Int
            val drawerCircleBg: Int
            val dialogBg: Int
            val cardBg: Int
            val screenBg: Int
            val textPrimary: Int
            val filePrimaryText: Int
            val textSecondary: Int
            val iconTint: Int
            val drawerIconTint: Int
            val dividerLineColor: Int
            val isLightStatusBar: Boolean

            when {
                effectiveAmoled -> {
                    toolbarBg = 0xFF000000.toInt()
                    appBarBg = 0xFF000000.toInt()
                    baseActivePanel = 0xFF0C0C0C.toInt()
                    baseInactivePanel = 0xFF000000.toInt()
                    basePaneDivider = 0xFF1A1A1A.toInt()
                    bottomBarBg = 0xFF000000.toInt()
                    drawerBg = 0xFF000000.toInt()
                    drawerHeaderBg = 0xFF000000.toInt()
                    drawerCircleBg = 0xFF141414.toInt()
                    dialogBg = 0xFF0A0A0A.toInt()
                    cardBg = 0xFF080808.toInt()
                    screenBg = 0xFF000000.toInt()
                    textPrimary = 0xFFFFFFFF.toInt()
                    filePrimaryText = 0xFFFFFFFF.toInt()
                    textSecondary = 0xFF9E9E9E.toInt()
                    iconTint = 0xFFFFFFFF.toInt()
                    drawerIconTint = 0xFFE0E0E0.toInt()
                    dividerLineColor = 0xFF181818.toInt()
                    isLightStatusBar = false
                }
                isDark -> {
                    toolbarBg = 0xFF151515.toInt()
                    appBarBg = 0xFF1F1F1F.toInt()
                    baseActivePanel = 0xFF363636.toInt()
                    baseInactivePanel = 0xFF242424.toInt()
                    basePaneDivider = 0xFF1D1D1D.toInt()
                    bottomBarBg = 0xFF202020.toInt()
                    drawerBg = 0xFF303030.toInt()
                    drawerHeaderBg = 0xFF151515.toInt()
                    drawerCircleBg = 0xFF282828.toInt()
                    dialogBg = 0xFF303030.toInt()
                    cardBg = 0xFF191919.toInt()
                    screenBg = 0xFF151515.toInt()
                    textPrimary = 0xFFFFFFFF.toInt()
                    filePrimaryText = 0xFFEAEAEA.toInt()
                    textSecondary = 0xFF888888.toInt()
                    iconTint = 0xFFFFFFFF.toInt()
                    drawerIconTint = 0xFFDCDCDC.toInt()
                    dividerLineColor = 0xFF2B2B2B.toInt()
                    isLightStatusBar = false
                }
                else -> {
                    toolbarBg = 0xFFF5F5F5.toInt()
                    appBarBg = 0xFFFFFFFF.toInt()
                    baseActivePanel = 0xFFFFFFFF.toInt()
                    baseInactivePanel = 0xFFECEFF2.toInt()
                    basePaneDivider = 0xFFD0D5DD.toInt()
                    bottomBarBg = 0xFFF5F5F5.toInt()
                    drawerBg = 0xFFFFFFFF.toInt()
                    drawerHeaderBg = 0xFFF0F2F5.toInt()
                    drawerCircleBg = 0xFFE8EAED.toInt()
                    dialogBg = 0xFFFFFFFF.toInt()
                    cardBg = 0xFFF1F3F4.toInt()
                    screenBg = 0xFFF8F9FA.toInt()
                    textPrimary = 0xFF1C1B1F.toInt()
                    filePrimaryText = 0xFF1F1F1F.toInt()
                    textSecondary = 0xFF5F6368.toInt()
                    iconTint = 0xFF202124.toInt()
                    drawerIconTint = 0xFF3C4043.toInt()
                    dividerLineColor = 0xFFE0E3E7.toInt()
                    isLightStatusBar = true
                }
            }

            val (activePanelBg, inactivePanelBg, paneDivider) = when (dualPanelContrast) {
                PANEL_CONTRAST_HIGH -> when {
                    effectiveAmoled -> Triple(0xFF141414.toInt(), 0xFF000000.toInt(), accentColor)
                    isDark -> Triple(0xFF3E3E3E.toInt(), 0xFF1C1C1C.toInt(), accentColor)
                    else -> Triple(0xFFFFFFFF.toInt(), 0xFFDCE2E8.toInt(), accentColor)
                }
                PANEL_CONTRAST_UNIFORM -> Triple(baseActivePanel, baseActivePanel, basePaneDivider)
                else -> Triple(baseActivePanel, baseInactivePanel, basePaneDivider)
            }

            return OpenManagerPalette(
                isDark = isDark,
                isAmoled = effectiveAmoled,
                isLightStatusBar = isLightStatusBar,
                accentColor = accentColor,
                toolbarBg = toolbarBg,
                appBarBg = appBarBg,
                activePanelBg = activePanelBg,
                inactivePanelBg = inactivePanelBg,
                paneDivider = paneDivider,
                bottomBarBg = bottomBarBg,
                drawerBg = drawerBg,
                drawerHeaderBg = drawerHeaderBg,
                drawerCircleBg = drawerCircleBg,
                dialogBg = dialogBg,
                cardBg = cardBg,
                screenBg = screenBg,
                textPrimary = textPrimary,
                filePrimaryText = filePrimaryText,
                textSecondary = textSecondary,
                iconTint = iconTint,
                drawerIconTint = drawerIconTint,
                dividerLineColor = dividerLineColor
            )
        }

        fun isDarkModeActive(context: Context): Boolean =
            if (context is AppCompatActivity) {
                NightModeHelper.isInNightMode(context)
            } else {
                when (Settings.NIGHT_MODE.valueCompat) {
                    NightMode.ON -> true
                    NightMode.OFF -> false
                    else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                        Configuration.UI_MODE_NIGHT_YES
                }
            }

        fun resolveAccentColor(context: Context, isDark: Boolean): Int {
            val useM3 = try {
                Settings.MATERIAL_DESIGN_3.valueCompat
            } catch (_: Throwable) {
                false
            }
            if (useM3) {
                val tv = TypedValue()
                if (context.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tv, true)) {
                    if (tv.resourceId != 0) {
                        return ContextCompat.getColor(context, tv.resourceId)
                    }
                    if (tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
                        return tv.data
                    }
                }
            }
            val themeColor = try {
                Settings.THEME_COLOR.valueCompat
            } catch (_: Throwable) {
                ThemeColor.COLOR_PRIMARY
            }
            if (themeColor == ThemeColor.COLOR_PRIMARY) {
                return ContextCompat.getColor(
                    context,
                    if (isDark) R.color.color_primary_dark else R.color.color_primary_light
                )
            }
            return ContextCompat.getColor(context, themeColor.resourceId)
        }

        fun from(context: Context): OpenManagerPalette {
            val isDark = isDarkModeActive(context)
            val isAmoled = try {
                isDark && Settings.BLACK_NIGHT_MODE.valueCompat
            } catch (_: Throwable) {
                false
            }
            val dualPanelContrast = try {
                Settings.DUAL_PANEL_CONTRAST.valueCompat.toIntOrNull() ?: PANEL_CONTRAST_STANDARD
            } catch (_: Throwable) {
                PANEL_CONTRAST_STANDARD
            }
            val accentColor = resolveAccentColor(context, isDark)
            return compute(
                isDark = isDark,
                isAmoled = isAmoled,
                accentColor = accentColor,
                dualPanelContrast = dualPanelContrast
            )
        }
    }
}
