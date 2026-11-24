/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.theme.custom

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatActivity
import me.zhanghai.android.files.R
import me.zhanghai.android.files.compat.recreateCompat
import me.zhanghai.android.files.compat.setThemeCompat
import me.zhanghai.android.files.compat.themeResIdCompat
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.theme.night.NightModeHelper
import me.zhanghai.android.files.util.SimpleActivityLifecycleCallbacks
import me.zhanghai.android.files.util.valueCompat

object CustomThemeHelper {
    private val activityBaseThemes = mutableMapOf<Activity, Int>()
    /** Theme color overlay applied on top of Material 3 (0 = wallpaper colors). */
    private val activityAccentOverlays = mutableMapOf<Activity, Int>()

    fun initialize(application: Application) {
        application.registerActivityLifecycleCallbacks(object : SimpleActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                check(activityBaseThemes.containsKey(activity)) {
                    "Activity must extend AppActivity: $activity"
                }
            }

            override fun onActivityDestroyed(activity: Activity) {
                activityBaseThemes.remove(activity)
                activityAccentOverlays.remove(activity)
            }
        })
    }

    fun apply(activity: Activity) {
        val baseThemeRes = activity.themeResIdCompat
        activityBaseThemes[activity] = baseThemeRes
        val customThemeRes = getCustomThemeRes(baseThemeRes, activity)
        activity.setThemeCompat(customThemeRes)
        val accentOverlay = getAccentOverlayRes(activity)
        if (accentOverlay != 0) {
            activity.theme.applyStyle(accentOverlay, true)
        }
        if (me.zhanghai.android.files.BuildConfig.DEBUG) {
            val primary = android.util.TypedValue()
            activity.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, primary, true)
            android.util.Log.i(
                "OMTheme", "${activity.javaClass.simpleName} md3=${Settings.MATERIAL_DESIGN_3.valueCompat} " +
                    "color=${Settings.THEME_COLOR.valueCompat} overlay=$accentOverlay " +
                    "colorPrimary=#%08X".format(primary.data)
            )
        }
        activityAccentOverlays[activity] = accentOverlay
    }

    fun sync() {
        for ((activity, baseThemeRes) in activityBaseThemes.entries.toList()) {
            val currentThemeRes = activity.themeResIdCompat
            val customThemeRes = getCustomThemeRes(baseThemeRes, activity)
            if (currentThemeRes != customThemeRes) {
                // Ignore ".Black" theme changes when not in night mode.
                if (!NightModeHelper.isInNightMode(activity as AppCompatActivity)
                    && isBlackThemeChange(currentThemeRes, customThemeRes, activity)) {
                    continue
                }
                if (activity is OnThemeChangedListener) {
                    (activity as OnThemeChangedListener).onThemeChanged(customThemeRes)
                } else {
                    activity.recreateCompat()
                }
            } else if (activityAccentOverlays[activity] != getAccentOverlayRes(activity)) {
                // Same base theme, different color on top of Material 3.
                if (activity is OnThemeChangedListener) {
                    (activity as OnThemeChangedListener).onThemeChanged(customThemeRes)
                } else {
                    activity.recreateCompat()
                }
            }
        }
    }

    /**
     * Material 3 normally takes its colors from the wallpaper; a theme color other than the
     * default one is laid on top, so picking a color works in both styles.
     */
    @StyleRes
    private fun getAccentOverlayRes(context: Context): Int {
        if (!Settings.MATERIAL_DESIGN_3.valueCompat) return 0
        val themeColor = Settings.THEME_COLOR.valueCompat
        if (themeColor == ThemeColor.COLOR_PRIMARY) return 0
        val resources = context.resources
        val packagePrefix = resources.getResourceName(R.style.Theme_MaterialFiles).substringBefore(':')
        val colorName = resources.getResourceEntryName(themeColor.resourceId)
        return resources.getIdentifier(
            "$packagePrefix:style/ThemeOverlay.MaterialFiles.Material3Accent.$colorName", null, null
        )
    }

    private fun getCustomThemeRes(@StyleRes baseThemeRes: Int, context: Context): Int {
        val resources = context.resources
        val baseThemeName = resources.getResourceName(baseThemeRes)
        val customThemeName = if (Settings.MATERIAL_DESIGN_3.valueCompat) {
            val defaultThemeName = resources.getResourceEntryName(R.style.Theme_MaterialFiles)
            val material3ThemeName =
                resources.getResourceEntryName(R.style.Theme_MaterialFiles_Material3)
            baseThemeName.replace(defaultThemeName, material3ThemeName)
        } else {
            val themeColorName =
                resources.getResourceEntryName(Settings.THEME_COLOR.valueCompat.resourceId)
            "$baseThemeName.$themeColorName"
        } + if (Settings.BLACK_NIGHT_MODE.valueCompat) ".Black" else ""
        return resources.getIdentifier(customThemeName, null, null)
    }

    private fun isBlackThemeChange(
        @StyleRes themeRes1: Int,
        @StyleRes themeRes2: Int,
        context: Context
    ): Boolean {
        val resources = context.resources
        val themeName1 = resources.getResourceName(themeRes1)
        val themeName2 = resources.getResourceName(themeRes2)
        return themeName1 == "$themeName2.Black" || themeName2 == "$themeName1.Black"
    }

    interface OnThemeChangedListener {
        fun onThemeChanged(@StyleRes theme: Int)
    }
}
