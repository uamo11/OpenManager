/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.zhanghai.android.files.R
import me.zhanghai.android.files.theme.custom.OpenManagerPalette
import java.util.concurrent.ConcurrentHashMap

/**
 * Provides semantic badged icons for all Settings preferences and renders
 * rich, themed, icon-backed choice dialogs replacing plain dropdowns.
 */
object SettingsIconHelper {

    data class ChoiceOptionSpec(
        @DrawableRes val iconRes: Int,
        @ColorInt val color: Int,
        val subtitle: String? = null,
        val badgeText: String? = null,
        val preserveOriginalColors: Boolean = false
    )

    private data class IconSpec(
        @DrawableRes val iconRes: Int,
        @ColorInt val semanticColor: Int,
        val preserveOriginalColors: Boolean = false
    )

    // Semantic accent colors
    private const val COLOR_BLUE = 0xFF3B82F6.toInt()
    private const val COLOR_INDIGO = 0xFF6366F1.toInt()
    private const val COLOR_VIOLET = 0xFF8B5CF6.toInt()
    private const val COLOR_PINK = 0xFFEC4899.toInt()
    private const val COLOR_EMERALD = 0xFF10B981.toInt()
    private const val COLOR_TEAL = 0xFF14B8A6.toInt()
    private const val COLOR_AMBER = 0xFFF59E0B.toInt()
    private const val COLOR_ORANGE = 0xFFF97316.toInt()
    private const val COLOR_RED = 0xFFEF4444.toInt()
    private const val COLOR_CYAN = 0xFF06B6D4.toInt()
    private const val COLOR_SLATE = 0xFF64748B.toInt()

    private val vectorStateCache = ConcurrentHashMap<Int, Drawable.ConstantState>()
    private val badgeColorCache = LruCache<Long, SettingsUiLogic.IconBadgeColors>(64)

    fun clearCaches() {
        vectorStateCache.clear()
        badgeColorCache.evictAll()
    }

    private fun resolveVectorDrawable(context: Context, @DrawableRes resId: Int): Drawable? {
        if (PerformanceModeManager.isEnabled) {
            return ContextCompat.getDrawable(context, resId)?.mutate()
        }
        val cached = vectorStateCache[resId]
        if (cached != null) {
            return cached.newDrawable(context.resources).mutate()
        }
        val fresh = ContextCompat.getDrawable(context, resId) ?: return null
        fresh.constantState?.let { vectorStateCache.putIfAbsent(resId, it) }
        return fresh.mutate()
    }

    private fun getCachedBadgeColors(
        @ColorInt semanticColor: Int,
        palette: OpenManagerPalette
    ): SettingsUiLogic.IconBadgeColors {
        val modeBits = when {
            !palette.isDark -> 0L
            palette.isAmoled -> 2L
            else -> 1L
        }
        val key = (semanticColor.toLong() and 0xFFFFFFFFL) or (modeBits shl 32)
        badgeColorCache.get(key)?.let { return it }
        val computed = SettingsUiLogic.computeIconBadgeColors(
            semanticColor = semanticColor,
            isDark = palette.isDark,
            isAmoled = palette.isAmoled
        )
        badgeColorCache.put(key, computed)
        return computed
    }

    fun createBadgedIcon(
        context: Context,
        @DrawableRes iconRes: Int,
        @ColorInt semanticColor: Int,
        palette: OpenManagerPalette,
        preserveOriginalColors: Boolean = false
    ): Drawable {
        val density = context.resources.displayMetrics.density
        val badgeSizePx = (36f * density).toInt()
        val insetPx = (8f * density).toInt()
        val cornerPx = 10f * density
        val strokePx = (1f * density).toInt().coerceAtLeast(1)

        val colors = getCachedBadgeColors(semanticColor, palette)

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerPx
            setColor(colors.bgColor)
            setStroke(strokePx, colors.strokeColor)
        }

        val icon = resolveVectorDrawable(context, iconRes) ?: GradientDrawable()
        val wrapped = DrawableCompat.wrap(icon).mutate()
        if (preserveOriginalColors) {
            DrawableCompat.setTintList(wrapped, null)
        } else {
            DrawableCompat.setTint(wrapped, colors.fgColor)
        }

        return LayerDrawable(arrayOf(bg, wrapped)).apply {
            setLayerSize(0, badgeSizePx, badgeSizePx)
            val innerPx = badgeSizePx - (insetPx * 2)
            setLayerSize(1, innerPx, innerPx)
            setLayerInset(1, insetPx, insetPx, insetPx, insetPx)
        }
    }

    fun applyIconsToScreen(
        context: Context,
        screen: PreferenceScreen,
        palette: OpenManagerPalette
    ) {
        val queue = ArrayDeque<PreferenceGroup>()
        queue.addLast(screen)
        while (queue.isNotEmpty()) {
            val group = queue.removeFirst()
            for (i in 0 until group.preferenceCount) {
                val pref = group.getPreference(i)
                if (pref is PreferenceGroup) {
                    queue.addLast(pref)
                    continue
                }
                if (pref is SuperuserStatusPreference ||
                    pref is AppearancePerformanceHubPreference ||
                    pref.key == "key_search_empty_state"
                ) {
                    pref.isIconSpaceReserved = false
                    continue
                }
                val spec = resolvePreferenceIconSpec(pref)
                pref.isIconSpaceReserved = true
                pref.icon = createBadgedIcon(
                    context = context,
                    iconRes = spec.iconRes,
                    semanticColor = spec.semanticColor,
                    palette = palette,
                    preserveOriginalColors = spec.preserveOriginalColors
                )
            }
        }
    }

    private fun resolvePreferenceIconSpec(pref: Preference): IconSpec {
        return when (pref) {
            is StoragesPreference -> IconSpec(R.drawable.sd_card_icon_white_24dp, COLOR_TEAL)
            is StandardDirectoriesPreference -> IconSpec(R.drawable.directory_icon_white_24dp, COLOR_AMBER)
            is BookmarkDirectoriesPreference -> IconSpec(R.drawable.bookmark_icon_white_24dp, COLOR_PINK)
            else -> getPreferenceIconSpec(pref.key)
        }
    }

    private fun getPreferenceIconSpec(key: String?): IconSpec {
        return when (key) {
            // Startup & Root
            "key_request_root_permission" -> IconSpec(R.drawable.shield_icon_white_24dp, COLOR_EMERALD)
            "key_request_shell_permission" -> IconSpec(R.drawable.ic_shizuku, COLOR_BLUE)
            "key_customize_su_command" -> IconSpec(R.drawable.terminal_icon_white_24dp, COLOR_SLATE)
            "key_install_shizuku" -> IconSpec(R.drawable.ic_shizuku, COLOR_INDIGO)
            "key_file_list_default_directory",
            "key_file_list_default_directory_right" -> IconSpec(R.drawable.ic_dual_panels_24dp, COLOR_TEAL)
            "key_auto_start_services" -> IconSpec(R.drawable.ic_perf_bolt_24dp, COLOR_AMBER)
            "key_root_strategy" -> IconSpec(R.drawable.lock_icon_white_24dp, COLOR_VIOLET)

            // Dead Man & Archive Security
            "key_dead_man_dfroot_protection" -> IconSpec(R.drawable.shield_icon_white_24dp, COLOR_ORANGE)
            "key_dead_man_dfroot_protected_files" -> IconSpec(R.drawable.ic_root_dfroot, COLOR_ORANGE, preserveOriginalColors = true)
            "key_dead_man_root_protection" -> IconSpec(R.drawable.ic_dead_man_skull, COLOR_RED, preserveOriginalColors = true)
            "key_dead_man_root_rules" -> IconSpec(R.drawable.ic_dead_man_skull, COLOR_RED, preserveOriginalColors = true)

            // Appearance
            "key_launcher_icon" -> IconSpec(R.drawable.ic_vector_shapes_24dp, COLOR_CYAN)
            "key_theme_color" -> IconSpec(R.drawable.ic_palette_swatch_24dp, COLOR_PINK)
            "key_material_design_3" -> IconSpec(R.drawable.ic_palette_swatch_24dp, COLOR_VIOLET)
            "key_night_mode" -> IconSpec(R.drawable.ic_night_mode_24dp, COLOR_INDIGO)
            "key_black_night_mode" -> IconSpec(R.drawable.ic_amoled_oled_24dp, COLOR_VIOLET)
            "key_dual_panel_contrast" -> IconSpec(R.drawable.ic_dual_panels_24dp, COLOR_BLUE)

            // Visual Performance & File List Display
            "key_visual_performance_mode" -> IconSpec(R.drawable.ic_perf_bolt_24dp, COLOR_EMERALD)
            "key_thumbnail_load_mode" -> IconSpec(R.drawable.image_icon_white_24dp, COLOR_BLUE)
            "key_file_list_dividers" -> IconSpec(R.drawable.ic_list_dividers_24dp, COLOR_SLATE)
            "key_file_list_size" -> IconSpec(R.drawable.ic_format_size_24dp, COLOR_TEAL)
            "key_max_lines_file_name" -> IconSpec(R.drawable.ic_list_dividers_24dp, COLOR_INDIGO)
            "key_file_list_time_preference" -> IconSpec(R.drawable.alarm_icon_white_24dp, COLOR_CYAN)
            "key_disable_permission_in_file_list" -> IconSpec(R.drawable.lock_icon_white_24dp, COLOR_AMBER)
            "key_date_time_format" -> IconSpec(R.drawable.alarm_icon_white_24dp, COLOR_BLUE)
            "key_file_list_animation" -> IconSpec(R.drawable.ic_perf_bolt_24dp, COLOR_VIOLET)
            "key_file_name_ellipsize" -> IconSpec(R.drawable.document_icon_white_24dp, COLOR_ORANGE)
            "key_file_list_show_hidden_files" -> IconSpec(R.drawable.visibility_icon_white_24dp, COLOR_PINK)

            // General
            "key_generate_backup_file" -> IconSpec(R.drawable.copy_icon_white_24dp, COLOR_BLUE)
            "key_preserve_file_time" -> IconSpec(R.drawable.alarm_icon_white_24dp, COLOR_TEAL)
            "key_enable_input_box_favorites" -> IconSpec(R.drawable.bookmark_icon_white_24dp, COLOR_AMBER)
            "key_sort_file_menu" -> IconSpec(R.drawable.sort_icon_control_normal_24dp, COLOR_INDIGO)
            "key_archive_file_name_encoding" -> IconSpec(R.drawable.ic_code_24dp, COLOR_VIOLET)
            "key_custom_openmanager_dir" -> IconSpec(R.drawable.directory_icon_white_24dp, COLOR_EMERALD)

            // Recycle Bin
            "key_move_to_recycle_bin_on_delete" -> IconSpec(R.drawable.delete_icon_white_24dp, COLOR_EMERALD)
            "key_move_to_recycle_bin_default" -> IconSpec(R.drawable.delete_icon_white_24dp, COLOR_TEAL)
            "key_auto_clean_recycle_bin" -> IconSpec(R.drawable.alarm_icon_white_24dp, COLOR_ORANGE)
            "key_show_deletion_warning" -> IconSpec(R.drawable.ic_crash_alert_24dp, COLOR_RED)

            // Installation & APK
            "key_apk_installation_verification" -> IconSpec(R.drawable.ic_apktool_verify, COLOR_EMERALD, preserveOriginalColors = true)
            "key_apk_prevents_deletion" -> IconSpec(R.drawable.shield_icon_white_24dp, COLOR_BLUE)
            "key_double_confirmation_apk" -> IconSpec(R.drawable.lock_icon_white_24dp, COLOR_ORANGE)
            "key_use_shizuku_install" -> IconSpec(R.drawable.ic_shizuku, COLOR_CYAN)
            "key_use_root_install" -> IconSpec(R.drawable.ic_apktool, COLOR_VIOLET, preserveOriginalColors = true)
            "key_open_apk_default_action" -> IconSpec(R.drawable.ic_open_as_apk, COLOR_EMERALD, preserveOriginalColors = true)
            "key_installation_priority_description" -> IconSpec(R.drawable.information_icon_white_24dp, COLOR_SLATE)

            // Bookmarks & Bottom Bar
            "key_show_bookmarks_sidebar" -> IconSpec(R.drawable.bookmark_icon_white_24dp, COLOR_BLUE)
            "key_add_bookmarks_top" -> IconSpec(R.drawable.ic_arrow_upward_24dp, COLOR_CYAN)
            "key_bookmark_swipe_aware" -> IconSpec(R.drawable.ic_dual_panels_24dp, COLOR_VIOLET)
            "key_add_bottom_margin" -> IconSpec(R.drawable.ic_aspect_ratio_24dp, COLOR_TEAL)
            "key_bottom_margin_size" -> IconSpec(R.drawable.ic_aspect_ratio_24dp, COLOR_INDIGO)

            // External Storage
            "key_load_thumbnails_external" -> IconSpec(R.drawable.sd_card_icon_white_24dp, COLOR_EMERALD)
            "key_optimize_external_transfer" -> IconSpec(R.drawable.ic_perf_bolt_24dp, COLOR_AMBER)
            "key_show_pdf_thumbnail_pre_28" -> IconSpec(R.drawable.document_icon_white_24dp, COLOR_RED)

            // Network Storage
            "key_read_remote_files_for_thumbnail" -> IconSpec(R.drawable.image_icon_white_24dp, COLOR_CYAN)
            "key_network_wifi_only" -> IconSpec(R.drawable.web_icon_white_24dp, COLOR_BLUE)
            "key_network_image_size_limit" -> IconSpec(R.drawable.ic_aspect_ratio_24dp, COLOR_ORANGE)
            "key_network_loading_time_limit" -> IconSpec(R.drawable.alarm_icon_white_24dp, COLOR_VIOLET)

            // Cloud Backup
            "key_cloud_backup" -> IconSpec(R.drawable.ic_arrow_upward_24dp, COLOR_BLUE)
            "key_cloud_restore" -> IconSpec(R.drawable.download_icon_white_24dp, COLOR_EMERALD)

            // Other & System
            "key_locale" -> IconSpec(R.drawable.web_icon_white_24dp, COLOR_INDIGO)
            "key_view_error_logs" -> IconSpec(R.drawable.ic_database_table_24dp, COLOR_ORANGE)
            "key_auto_check_updates" -> IconSpec(R.drawable.ic_perf_bolt_24dp, COLOR_EMERALD)
            "key_check_for_update" -> IconSpec(R.drawable.download_icon_white_24dp, COLOR_BLUE)
            "key_telegram_channel" -> IconSpec(R.drawable.ic_telegram_24dp, COLOR_CYAN)
            "key_acknowledgement" -> IconSpec(R.drawable.information_icon_white_24dp, COLOR_TEAL)
            "key_about_settings" -> IconSpec(R.drawable.about_icon_white_24dp, COLOR_CYAN)
            "key_user_agreement" -> IconSpec(R.drawable.document_icon_white_24dp, COLOR_SLATE)

            else -> IconSpec(R.drawable.settings_icon_white_24dp, COLOR_SLATE)
        }
    }

    fun buildSpecsForPreference(context: Context, key: String?, count: Int): List<ChoiceOptionSpec> {
        val base = when (key) {
            "key_thumbnail_load_mode" -> listOf(
                ChoiceOptionSpec(
                    iconRes = R.drawable.image_icon_white_24dp,
                    color = COLOR_BLUE,
                    subtitle = context.getString(R.string.settings_thumb_opt_0_desc),
                    badgeText = context.getString(R.string.settings_thumb_opt_0_tag)
                ),
                ChoiceOptionSpec(
                    iconRes = R.drawable.ic_perf_bolt_24dp,
                    color = COLOR_EMERALD,
                    subtitle = context.getString(R.string.settings_thumb_opt_1_desc),
                    badgeText = context.getString(R.string.settings_thumb_opt_1_tag)
                ),
                ChoiceOptionSpec(
                    iconRes = R.drawable.ic_vector_shapes_24dp,
                    color = COLOR_AMBER,
                    subtitle = context.getString(R.string.settings_thumb_opt_2_desc),
                    badgeText = context.getString(R.string.settings_thumb_opt_2_tag)
                )
            )
            "key_night_mode" -> listOf(
                ChoiceOptionSpec(R.drawable.settings_icon_white_24dp, COLOR_CYAN),
                ChoiceOptionSpec(R.drawable.ic_day_mode_24dp, COLOR_AMBER),
                ChoiceOptionSpec(R.drawable.ic_night_mode_24dp, COLOR_INDIGO),
                ChoiceOptionSpec(R.drawable.alarm_icon_white_24dp, COLOR_VIOLET),
                ChoiceOptionSpec(R.drawable.ic_perf_bolt_24dp, COLOR_EMERALD)
            )
            "key_dual_panel_contrast" -> listOf(
                ChoiceOptionSpec(
                    iconRes = R.drawable.ic_dual_panels_24dp,
                    color = COLOR_BLUE,
                    subtitle = context.getString(R.string.settings_contrast_opt_0_desc)
                ),
                ChoiceOptionSpec(
                    iconRes = R.drawable.ic_palette_swatch_24dp,
                    color = COLOR_PINK,
                    subtitle = context.getString(R.string.settings_contrast_opt_1_desc)
                ),
                ChoiceOptionSpec(
                    iconRes = R.drawable.ic_perf_bolt_24dp,
                    color = COLOR_EMERALD,
                    subtitle = context.getString(R.string.settings_contrast_opt_2_desc)
                )
            )
            "key_file_list_size" -> listOf(
                ChoiceOptionSpec(
                    iconRes = R.drawable.ic_format_size_24dp,
                    color = COLOR_TEAL,
                    subtitle = context.getString(R.string.settings_size_opt_0_desc)
                ),
                ChoiceOptionSpec(
                    iconRes = R.drawable.ic_format_size_24dp,
                    color = COLOR_BLUE,
                    subtitle = context.getString(R.string.settings_size_opt_1_desc)
                ),
                ChoiceOptionSpec(
                    iconRes = R.drawable.ic_format_size_24dp,
                    color = COLOR_INDIGO,
                    subtitle = context.getString(R.string.settings_size_opt_2_desc)
                )
            )
            "key_launcher_icon" -> listOf(
                ChoiceOptionSpec(R.drawable.ic_day_mode_24dp, COLOR_CYAN),
                ChoiceOptionSpec(R.drawable.ic_vector_shapes_24dp, COLOR_AMBER),
                ChoiceOptionSpec(R.drawable.ic_palette_swatch_24dp, COLOR_BLUE)
            )
            "key_max_lines_file_name" -> listOf(
                ChoiceOptionSpec(R.drawable.ic_list_dividers_24dp, COLOR_BLUE),
                ChoiceOptionSpec(R.drawable.ic_list_dividers_24dp, COLOR_INDIGO)
            )
            "key_file_name_ellipsize" -> listOf(
                ChoiceOptionSpec(R.drawable.document_icon_white_24dp, COLOR_SLATE),
                ChoiceOptionSpec(R.drawable.document_icon_white_24dp, COLOR_BLUE),
                ChoiceOptionSpec(R.drawable.document_icon_white_24dp, COLOR_VIOLET),
                ChoiceOptionSpec(R.drawable.ic_perf_bolt_24dp, COLOR_ORANGE)
            )
            "key_file_list_time_preference" -> listOf(
                ChoiceOptionSpec(R.drawable.alarm_icon_white_24dp, COLOR_BLUE),
                ChoiceOptionSpec(R.drawable.alarm_icon_white_24dp, COLOR_CYAN)
            )
            "key_date_time_format" -> listOf(
                ChoiceOptionSpec(R.drawable.alarm_icon_white_24dp, COLOR_INDIGO),
                ChoiceOptionSpec(R.drawable.alarm_icon_white_24dp, COLOR_TEAL),
                ChoiceOptionSpec(R.drawable.alarm_icon_white_24dp, COLOR_BLUE)
            )
            "key_open_apk_default_action" -> listOf(
                ChoiceOptionSpec(R.drawable.ic_view_mode_24dp, COLOR_BLUE),
                ChoiceOptionSpec(R.drawable.ic_open_as_apk, COLOR_EMERALD, preserveOriginalColors = true),
                ChoiceOptionSpec(R.drawable.settings_icon_white_24dp, COLOR_VIOLET)
            )
            "key_auto_start_services" -> listOf(
                ChoiceOptionSpec(R.drawable.lock_icon_white_24dp, COLOR_SLATE),
                ChoiceOptionSpec(R.drawable.web_icon_white_24dp, COLOR_EMERALD)
            )
            "key_auto_clean_recycle_bin" -> listOf(
                ChoiceOptionSpec(R.drawable.delete_icon_white_24dp, COLOR_SLATE),
                ChoiceOptionSpec(R.drawable.alarm_icon_white_24dp, COLOR_AMBER),
                ChoiceOptionSpec(R.drawable.alarm_icon_white_24dp, COLOR_ORANGE)
            )
            "key_bottom_margin_size" -> listOf(
                ChoiceOptionSpec(R.drawable.ic_aspect_ratio_24dp, COLOR_SLATE),
                ChoiceOptionSpec(R.drawable.ic_aspect_ratio_24dp, COLOR_TEAL),
                ChoiceOptionSpec(R.drawable.ic_aspect_ratio_24dp, COLOR_BLUE),
                ChoiceOptionSpec(R.drawable.ic_aspect_ratio_24dp, COLOR_INDIGO),
                ChoiceOptionSpec(R.drawable.ic_aspect_ratio_24dp, COLOR_VIOLET)
            )
            "key_network_image_size_limit" -> listOf(
                ChoiceOptionSpec(R.drawable.image_icon_white_24dp, COLOR_EMERALD),
                ChoiceOptionSpec(R.drawable.image_icon_white_24dp, COLOR_BLUE),
                ChoiceOptionSpec(R.drawable.image_icon_white_24dp, COLOR_AMBER),
                ChoiceOptionSpec(R.drawable.image_icon_white_24dp, COLOR_ORANGE)
            )
            "key_network_loading_time_limit" -> listOf(
                ChoiceOptionSpec(R.drawable.alarm_icon_white_24dp, COLOR_EMERALD),
                ChoiceOptionSpec(R.drawable.alarm_icon_white_24dp, COLOR_BLUE),
                ChoiceOptionSpec(R.drawable.alarm_icon_white_24dp, COLOR_AMBER),
                ChoiceOptionSpec(R.drawable.alarm_icon_white_24dp, COLOR_ORANGE)
            )
            else -> emptyList()
        }
        if (base.isNotEmpty() || count <= 0) return base
        val headerSpec = getPreferenceIconSpec(key)
        return List(count) {
            ChoiceOptionSpec(headerSpec.iconRes, headerSpec.semanticColor)
        }
    }

    fun showIconChoiceDialog(
        context: Context,
        preference: ListPreference,
        specs: List<ChoiceOptionSpec>?
    ) {
        val entries = preference.entries ?: return
        val entryValues = preference.entryValues ?: return
        val count = minOf(entries.size, entryValues.size)
        if (count == 0) return

        val palette = OpenManagerPalette.from(context)
        val density = context.resources.displayMetrics.density
        val padH = (18f * density).toInt()
        val padV = (16f * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padV, padH, (8f * density).toInt())
        }

        // Header row with semantic icon badge + title
        val headerSpec = getPreferenceIconSpec(preference.key)
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (12f * density).toInt())
        }
        val headerIcon = ImageView(context).apply {
            val sizePx = (34f * density).toInt()
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
            setImageDrawable(
                createBadgedIcon(
                    context = context,
                    iconRes = headerSpec.iconRes,
                    semanticColor = headerSpec.semanticColor,
                    palette = palette,
                    preserveOriginalColors = headerSpec.preserveOriginalColors
                )
            )
        }
        val headerTitle = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (12f * density).toInt()
            }
            text = preference.dialogTitle ?: preference.title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.textPrimary)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        headerRow.addView(headerIcon)
        headerRow.addView(headerTitle)
        root.addView(headerRow)

        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1f * density).toInt().coerceAtLeast(1)
            ).apply {
                bottomMargin = (10f * density).toInt()
            }
            setBackgroundColor(palette.dividerLineColor)
        }
        root.addView(divider)

        val optionsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(optionsContainer)
        }
        root.addView(scrollView)

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        val rippleTyped = TypedValue()
        val hasRipple = context.theme.resolveAttribute(
            android.R.attr.selectableItemBackground, rippleTyped, true
        )

        val currentValue = preference.value
        val cardGapPx = (8f * density).toInt()
        val cardPadH = (12f * density).toInt()
        val cardPadV = (10f * density).toInt()

        for (i in 0 until count) {
            val entryTitle = entries[i]
            val entryVal = entryValues[i].toString()
            val isSelected = entryVal == currentValue
            val optionSpec = specs?.getOrNull(i)
                ?: ChoiceOptionSpec(headerSpec.iconRes, headerSpec.semanticColor)
            val optionBadgeColors = getCachedBadgeColors(optionSpec.color, palette)

            val card = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (i < count - 1) bottomMargin = cardGapPx
                }
                setPadding(cardPadH, cardPadV, cardPadH, cardPadV)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12f * density
                    if (isSelected) {
                        setColor(
                            SettingsUiLogic.withAlpha(
                                palette.accentColor,
                                if (palette.isDark) 0x26 else 0x1A
                            )
                        )
                        setStroke((1.5f * density).toInt().coerceAtLeast(2), palette.accentColor)
                    } else {
                        setColor(palette.cardBg)
                        setStroke((1f * density).toInt().coerceAtLeast(1), palette.dividerLineColor)
                    }
                }
                if (hasRipple) {
                    foreground = ContextCompat.getDrawable(context, rippleTyped.resourceId)
                }
                isClickable = true
                isFocusable = true
            }

            val iconView = ImageView(context).apply {
                val sizePx = (36f * density).toInt()
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
                setImageDrawable(
                    createBadgedIcon(
                        context = context,
                        iconRes = optionSpec.iconRes,
                        semanticColor = optionSpec.color,
                        palette = palette,
                        preserveOriginalColors = optionSpec.preserveOriginalColors
                    )
                )
            }
            card.addView(iconView)

            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply {
                    marginStart = (12f * density).toInt()
                    marginEnd = (10f * density).toInt()
                }
            }

            val titleRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val titleView = TextView(context).apply {
                layoutParams = if (!optionSpec.badgeText.isNullOrBlank()) {
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                } else {
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                text = entryTitle
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(palette.textPrimary)
            }
            titleRow.addView(titleView)

            if (!optionSpec.badgeText.isNullOrBlank()) {
                val pillView = TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = (6f * density).toInt()
                    }
                    text = optionSpec.badgeText
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(optionBadgeColors.fgColor)
                    setPadding(
                        (6f * density).toInt(),
                        (2f * density).toInt(),
                        (6f * density).toInt(),
                        (2f * density).toInt()
                    )
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 6f * density
                        setColor(optionBadgeColors.bgColor)
                        setStroke((1f * density).toInt().coerceAtLeast(1), optionBadgeColors.strokeColor)
                    }
                }
                titleRow.addView(pillView)
            }
            textCol.addView(titleRow)

            if (!optionSpec.subtitle.isNullOrBlank()) {
                val subView = TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (2f * density).toInt()
                    }
                    text = optionSpec.subtitle
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(palette.textSecondary)
                }
                textCol.addView(subView)
            }
            card.addView(textCol)

            val radioView = ImageView(context).apply {
                val radioPx = (20f * density).toInt()
                layoutParams = LinearLayout.LayoutParams(radioPx, radioPx)
                if (isSelected) {
                    val outerRing = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(palette.accentColor)
                    }
                    val checkIcon = resolveVectorDrawable(
                        context, R.drawable.check_icon_control_normal_24dp
                    )?.let {
                        DrawableCompat.wrap(it).mutate().apply {
                            DrawableCompat.setTint(this, palette.onAccentColor)
                        }
                    } ?: GradientDrawable()
                    val inset = (3f * density).toInt()
                    setImageDrawable(
                        LayerDrawable(arrayOf(outerRing, checkIcon)).apply {
                            setLayerSize(0, radioPx, radioPx)
                            val inner = radioPx - (inset * 2)
                            setLayerSize(1, inner, inner)
                            setLayerInset(1, inset, inset, inset, inset)
                        }
                    )
                } else {
                    setImageDrawable(
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(0x00000000)
                            setStroke((1.5f * density).toInt().coerceAtLeast(2), palette.dividerLineColor)
                        }
                    )
                }
            }
            card.addView(radioView)

            card.setOnClickListener {
                if (preference.callChangeListener(entryVal)) {
                    preference.value = entryVal
                }
                dialog.dismiss()
            }
            optionsContainer.addView(card)
        }

        if (!palette.isDark || palette.isAmoled) {
            dialog.setOnShowListener {
                dialog.window?.setBackgroundDrawable(
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 18f * density
                        setColor(palette.dialogBg)
                        if (palette.isAmoled) {
                            setStroke((1f * density).toInt().coerceAtLeast(1), palette.dividerLineColor)
                        }
                    }
                )
            }
        }
        dialog.show()
    }
}
