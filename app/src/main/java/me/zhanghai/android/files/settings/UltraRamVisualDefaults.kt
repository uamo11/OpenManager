/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.content.Context
import me.zhanghai.android.files.theme.custom.OpenManagerPalette
import me.zhanghai.android.files.util.valueCompat

/**
 * Ultra RAM mode also picks the lightest look: classic (non Material 3) theme and the same
 * background on both panels (less overdraw). The user's own choices are remembered and put back
 * when the mode is turned off, unless they changed them while it was on.
 */
object UltraRamVisualDefaults {

    data class Visual(val materialDesign3: Boolean, val dualPanelContrast: String)

    val ULTRA = Visual(
        materialDesign3 = false,
        dualPanelContrast = OpenManagerPalette.PANEL_CONTRAST_UNIFORM.toString()
    )

    /** What to apply now, and what to keep remembered for later. */
    data class Plan(val apply: Visual, val remember: Visual?)

    fun plan(enabled: Boolean, current: Visual, remembered: Visual?): Plan =
        if (enabled) {
            Plan(apply = ULTRA, remember = remembered ?: current)
        } else if (remembered == null) {
            Plan(apply = current, remember = null)
        } else {
            // Only undo what Ultra RAM set; keep anything the user changed in the meantime.
            Plan(
                apply = Visual(
                    materialDesign3 = if (current.materialDesign3 == ULTRA.materialDesign3) {
                        remembered.materialDesign3
                    } else {
                        current.materialDesign3
                    },
                    dualPanelContrast = if (current.dualPanelContrast == ULTRA.dualPanelContrast) {
                        remembered.dualPanelContrast
                    } else {
                        current.dualPanelContrast
                    }
                ),
                remember = null
            )
        }

    /** Applies the look for [enabled]; returns what was applied. */
    fun onUltraRamChanged(context: Context, enabled: Boolean): Visual {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val remembered = if (prefs.getBoolean(KEY_SAVED, false)) {
            Visual(
                prefs.getBoolean(KEY_MD3, false),
                prefs.getString(KEY_CONTRAST, null) ?: OpenManagerPalette.PANEL_CONTRAST_STANDARD.toString()
            )
        } else {
            null
        }
        val current = Visual(Settings.MATERIAL_DESIGN_3.valueCompat, Settings.DUAL_PANEL_CONTRAST.valueCompat)
        val plan = plan(enabled, current, remembered)
        prefs.edit().apply {
            val keep = plan.remember
            if (keep == null) {
                clear()
            } else {
                putBoolean(KEY_SAVED, true)
                putBoolean(KEY_MD3, keep.materialDesign3)
                putString(KEY_CONTRAST, keep.dualPanelContrast)
            }
        }.apply()
        if (plan.apply.dualPanelContrast != current.dualPanelContrast) {
            Settings.DUAL_PANEL_CONTRAST.putValue(plan.apply.dualPanelContrast)
        }
        if (plan.apply.materialDesign3 != current.materialDesign3) {
            Settings.MATERIAL_DESIGN_3.putValue(plan.apply.materialDesign3)
        }
        return plan.apply
    }

    private const val PREFS = "om_ultra_ram_visual"
    private const val KEY_SAVED = "saved"
    private const val KEY_MD3 = "material_design_3"
    private const val KEY_CONTRAST = "dual_panel_contrast"
}
