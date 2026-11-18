/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.StyleRes
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import me.zhanghai.android.files.R
import me.zhanghai.android.files.settings.SettingsUiLogic.ThemeQuickPreset
import me.zhanghai.android.files.theme.custom.OpenManagerPalette
import me.zhanghai.android.files.util.valueCompat

/**
 * Interactive visual & performance quick-hub displayed inside Appearance Settings,
 * allowing 1-tap switching across Light / Dark / AMOLED / System themes, Accent color,
 * Thumbnail & Icon rendering mode, and Smooth low-power UI mode.
 */
class AppearancePerformanceHubPreference : Preference {

    var onThemePresetSelected: ((ThemeQuickPreset) -> Unit)? = null
    var onAccentClicked: (() -> Unit)? = null
    var onThumbnailModeSelected: ((String) -> Unit)? = null
    var onVisualPerformanceToggled: ((Boolean) -> Unit)? = null

    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?, @AttrRes defStyleAttr: Int) : super(
        context, attrs, defStyleAttr
    ) {
        init()
    }

    constructor(
        context: Context,
        attrs: AttributeSet?,
        @AttrRes defStyleAttr: Int,
        @StyleRes defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes) {
        init()
    }

    private fun init() {
        layoutResource = R.layout.preference_appearance_performance_hub
        isSelectable = false
        isPersistent = false
        isIconSpaceReserved = false
    }

    fun refreshState() {
        notifyChanged()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val ctx = holder.itemView.context
        val palette = OpenManagerPalette.from(ctx)
        val density = ctx.resources.displayMetrics.density

        val cardBgColor = when {
            palette.isAmoled -> 0xFF0A0A0A.toInt()
            palette.isDark -> 0xFF232326.toInt()
            else -> 0xFFF4F6F9.toInt()
        }
        val unselectedTileBg = when {
            palette.isAmoled -> 0xFF131315.toInt()
            palette.isDark -> 0xFF2B2B30.toInt()
            else -> 0xFFFFFFFF.toInt()
        }

        val layoutHubCard = holder.findViewById(R.id.layoutHubCard) as? LinearLayout ?: return
        layoutHubCard.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * density
            setColor(cardBgColor)
            setStroke((1f * density).toInt().coerceAtLeast(1), palette.dividerLineColor)
        }

        val tvThemeHeader = holder.findViewById(R.id.tvHubThemeHeaderTitle) as? TextView
        val ivThemeHeader = holder.findViewById(R.id.ivHubThemeHeaderIcon) as? ImageView
        tvThemeHeader?.setTextColor(palette.textPrimary)
        ivThemeHeader?.let { ImageViewCompat.setImageTintList(it, ColorStateList.valueOf(palette.accentColor)) }

        val btnAccent = holder.findViewById(R.id.btnHubAccent)
        val dotAccent = holder.findViewById(R.id.viewHubAccentDot)
        val tvAccentLabel = holder.findViewById(R.id.tvHubAccentLabel) as? TextView
        btnAccent?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * density
            setColor(SettingsUiLogic.withAlpha(palette.accentColor, if (palette.isDark) 0x29 else 0x1A))
            setStroke((1f * density).toInt().coerceAtLeast(1), SettingsUiLogic.withAlpha(palette.accentColor, 0x55))
        }
        dotAccent?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(palette.accentColor)
        }
        tvAccentLabel?.setTextColor(if (palette.isDark) palette.textPrimary else palette.accentColor)
        btnAccent?.setOnClickListener { onAccentClicked?.invoke() }

        val activePreset = SettingsUiLogic.resolveThemeQuickPreset(
            Settings.NIGHT_MODE.valueCompat,
            Settings.BLACK_NIGHT_MODE.valueCompat
        )

        val rippleTyped = TypedValue()
        val hasRipple = ctx.theme.resolveAttribute(
            android.R.attr.selectableItemBackground, rippleTyped, true
        )

        fun styleTile(
            tileView: View?,
            iconView: ImageView?,
            titleView: TextView?,
            subView: TextView?,
            selected: Boolean,
            @ColorInt activeColor: Int = palette.accentColor
        ) {
            if (tileView == null) return
            tileView.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 11f * density
                if (selected) {
                    setColor(SettingsUiLogic.withAlpha(activeColor, if (palette.isDark) 0x2E else 0x1C))
                    setStroke((1.5f * density).toInt().coerceAtLeast(2), activeColor)
                } else {
                    setColor(unselectedTileBg)
                    setStroke((1f * density).toInt().coerceAtLeast(1), palette.dividerLineColor)
                }
            }
            if (hasRipple) {
                tileView.foreground = ContextCompat.getDrawable(ctx, rippleTyped.resourceId)
            }
            iconView?.let {
                ImageViewCompat.setImageTintList(
                    it,
                    ColorStateList.valueOf(if (selected) activeColor else palette.textSecondary)
                )
            }
            titleView?.apply {
                setTextColor(palette.textPrimary)
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            subView?.setTextColor(if (selected) activeColor else palette.textSecondary)
        }

        val tileLight = holder.findViewById(R.id.tileThemeLight)
        val tileDark = holder.findViewById(R.id.tileThemeDark)
        val tileAmoled = holder.findViewById(R.id.tileThemeAmoled)
        val tileSystem = holder.findViewById(R.id.tileThemeSystem)

        styleTile(
            tileLight,
            holder.findViewById(R.id.ivTileThemeLight) as? ImageView,
            holder.findViewById(R.id.tvTileThemeLight) as? TextView,
            null,
            activePreset == ThemeQuickPreset.LIGHT
        )
        styleTile(
            tileDark,
            holder.findViewById(R.id.ivTileThemeDark) as? ImageView,
            holder.findViewById(R.id.tvTileThemeDark) as? TextView,
            null,
            activePreset == ThemeQuickPreset.DARK
        )
        styleTile(
            tileAmoled,
            holder.findViewById(R.id.ivTileThemeAmoled) as? ImageView,
            holder.findViewById(R.id.tvTileThemeAmoled) as? TextView,
            null,
            activePreset == ThemeQuickPreset.AMOLED
        )
        styleTile(
            tileSystem,
            holder.findViewById(R.id.ivTileThemeSystem) as? ImageView,
            holder.findViewById(R.id.tvTileThemeSystem) as? TextView,
            null,
            activePreset == ThemeQuickPreset.SYSTEM
        )

        tileLight?.setOnClickListener { onThemePresetSelected?.invoke(ThemeQuickPreset.LIGHT) }
        tileDark?.setOnClickListener { onThemePresetSelected?.invoke(ThemeQuickPreset.DARK) }
        tileAmoled?.setOnClickListener { onThemePresetSelected?.invoke(ThemeQuickPreset.AMOLED) }
        tileSystem?.setOnClickListener { onThemePresetSelected?.invoke(ThemeQuickPreset.SYSTEM) }

        holder.findViewById(R.id.viewHubDivider)?.setBackgroundColor(palette.dividerLineColor)

        val thumbMode = Settings.THUMBNAIL_LOAD_MODE.valueCompat
        val visualPerf = Settings.VISUAL_PERFORMANCE_MODE.valueCompat

        val emeraldColor = 0xFF10B981.toInt()
        val blueColor = 0xFF3B82F6.toInt()
        val amberColor = 0xFFF59E0B.toInt()

        val ivPerfHeader = holder.findViewById(R.id.ivHubPerfHeaderIcon) as? ImageView
        val tvPerfHeader = holder.findViewById(R.id.tvHubPerfHeaderTitle) as? TextView
        val tvPerfStatus = holder.findViewById(R.id.tvHubPerfStatus) as? TextView
        ivPerfHeader?.let {
            ImageViewCompat.setImageTintList(it, ColorStateList.valueOf(emeraldColor))
        }
        tvPerfHeader?.setTextColor(palette.textPrimary)

        val (statusTextRes, statusColor) = when {
            thumbMode == "2" -> R.string.settings_hub_status_ultra to emeraldColor
            thumbMode == "1" || visualPerf -> R.string.settings_hub_status_smooth to blueColor
            else -> R.string.settings_hub_status_standard to palette.textSecondary
        }
        tvPerfStatus?.apply {
            text = ctx.getString(statusTextRes)
            val statusBadgeColors = SettingsUiLogic.computeIconBadgeColors(
                semanticColor = statusColor,
                isDark = palette.isDark,
                isAmoled = palette.isAmoled
            )
            setTextColor(if (thumbMode == "0" && !visualPerf) palette.textSecondary else statusBadgeColors.fgColor)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10f * density
                setColor(statusBadgeColors.bgColor)
                setStroke((1f * density).toInt().coerceAtLeast(1), statusBadgeColors.strokeColor)
            }
        }

        val tileFull = holder.findViewById(R.id.tileThumbFull)
        val tileOpt = holder.findViewById(R.id.tileThumbOptimized)
        val tileVec = holder.findViewById(R.id.tileThumbVector)

        styleTile(
            tileFull,
            holder.findViewById(R.id.ivTileThumbFull) as? ImageView,
            holder.findViewById(R.id.tvTileThumbFull) as? TextView,
            holder.findViewById(R.id.tvTileThumbFullSub) as? TextView,
            thumbMode == "0",
            blueColor
        )
        styleTile(
            tileOpt,
            holder.findViewById(R.id.ivTileThumbOptimized) as? ImageView,
            holder.findViewById(R.id.tvTileThumbOptimized) as? TextView,
            holder.findViewById(R.id.tvTileThumbOptimizedSub) as? TextView,
            thumbMode == "1",
            emeraldColor
        )
        styleTile(
            tileVec,
            holder.findViewById(R.id.ivTileThumbVector) as? ImageView,
            holder.findViewById(R.id.tvTileThumbVector) as? TextView,
            holder.findViewById(R.id.tvTileThumbVectorSub) as? TextView,
            thumbMode == "2",
            amberColor
        )

        tileFull?.setOnClickListener { onThumbnailModeSelected?.invoke("0") }
        tileOpt?.setOnClickListener { onThumbnailModeSelected?.invoke("1") }
        tileVec?.setOnClickListener { onThumbnailModeSelected?.invoke("2") }

        val rowTurbo = holder.findViewById(R.id.rowHubTurbo)
        val ivTurbo = holder.findViewById(R.id.ivHubTurboIcon) as? ImageView
        val tvTurbo = holder.findViewById(R.id.tvHubTurboTitle) as? TextView
        val switchTurbo = holder.findViewById(R.id.switchHubTurbo) as? SwitchCompat

        rowTurbo?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10f * density
            setColor(
                if (visualPerf) {
                    SettingsUiLogic.withAlpha(emeraldColor, if (palette.isDark) 0x24 else 0x18)
                } else {
                    unselectedTileBg
                }
            )
            setStroke(
                (1f * density).toInt().coerceAtLeast(1),
                if (visualPerf) emeraldColor else palette.dividerLineColor
            )
        }
        ivTurbo?.let {
            ImageViewCompat.setImageTintList(
                it, ColorStateList.valueOf(if (visualPerf) emeraldColor else palette.textSecondary)
            )
        }
        tvTurbo?.setTextColor(palette.textPrimary)

        switchTurbo?.let { sw ->
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = visualPerf
            sw.setOnCheckedChangeListener { _, isChecked ->
                onVisualPerformanceToggled?.invoke(isChecked)
            }
            rowTurbo?.setOnClickListener { sw.toggle() }
        }
    }
}
