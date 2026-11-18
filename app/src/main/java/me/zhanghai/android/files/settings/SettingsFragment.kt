/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.SettingsFragmentBinding
import me.zhanghai.android.files.theme.custom.OpenManagerPalette

class SettingsFragment : Fragment() {
    private lateinit var binding: SettingsFragmentBinding

    private data class QuickJumpChip(
        val categoryKey: String,
        @StringRes val labelRes: Int,
        @DrawableRes val iconRes: Int,
        @ColorInt val semanticColor: Int,
        val preserveOriginalColors: Boolean = false
    )

    private data class ChipHolder(
        val spec: QuickJumpChip,
        val container: LinearLayout,
        val icon: ImageView,
        val label: TextView
    )

    private val quickJumpSpecs = listOf(
        QuickJumpChip("key_category_visual_performance", R.string.settings_jump_performance, R.drawable.ic_perf_bolt_24dp, 0xFF10B981.toInt()),
        QuickJumpChip("key_category_appearance", R.string.settings_jump_appearance, R.drawable.ic_palette_swatch_24dp, 0xFFEC4899.toInt()),
        QuickJumpChip("key_category_startup", R.string.settings_jump_root, R.drawable.shield_icon_white_24dp, 0xFF3B82F6.toInt()),
        QuickJumpChip("key_category_general", R.string.settings_jump_files, R.drawable.directory_icon_white_24dp, 0xFFF59E0B.toInt()),
        QuickJumpChip("key_category_installation", R.string.settings_jump_apk, R.drawable.ic_open_as_apk, 0xFF10B981.toInt(), preserveOriginalColors = true),
        QuickJumpChip("key_category_external_storage", R.string.settings_jump_network, R.drawable.sd_card_icon_white_24dp, 0xFF06B6D4.toInt()),
        QuickJumpChip("key_category_other", R.string.settings_jump_system, R.drawable.information_icon_white_24dp, 0xFF8B5CF6.toInt())
    )

    private val chipHolders = mutableListOf<ChipHolder>()
    private var activeChipKey: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        SettingsFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        val activity = requireActivity() as AppCompatActivity
        activity.setTitle(R.string.settings_title)
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        val palette = OpenManagerPalette.from(requireContext())
        applySearchAndHeaderTheme(palette)
        populateQuickJumpChips(palette)
        setupSearchListeners()

        if (BuildConfig.DEBUG) {
            val actIntent = activity.intent
            actIntent?.getStringExtra("DEMO_ACTIVE_CHIP")?.takeIf { it.isNotEmpty() }?.let { chipKey ->
                activeChipKey = chipKey
                refreshChipStyles(palette)
            }
            if (actIntent?.getStringExtra("DEMO_COLLAPSE_HEADER") == "true") {
                binding.appBarLayout.post {
                    if (isAdded) {
                        binding.appBarLayout.setExpanded(false, false)
                    }
                }
            }
            actIntent?.getStringExtra("DEMO_SEARCH")?.takeIf { it.isNotEmpty() }?.let { q ->
                binding.etSettingsSearch.postDelayed({
                    if (isAdded) {
                        binding.etSettingsSearch.setText(q)
                        hideKeyboard()
                    }
                }, 250)
            }
        }
    }

    private fun applySearchAndHeaderTheme(palette: OpenManagerPalette) {
        val density = resources.displayMetrics.density
        if (palette.isAmoled || !palette.isDark) {
            binding.appBarLayout.setBackgroundColor(palette.appBarBg)
            binding.toolbar.setTitleTextColor(palette.textPrimary)
            binding.toolbar.navigationIcon?.let {
                val tinted = it.mutate()
                tinted.setTint(palette.iconTint)
                binding.toolbar.navigationIcon = tinted
            }
        }
        val pillBg = when {
            palette.isAmoled -> 0xFF0F0F12.toInt()
            palette.isDark -> 0xFF2B2B30.toInt()
            else -> 0xFFF1F4F8.toInt()
        }
        binding.layoutSearchPill.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 21f * density
            setColor(pillBg)
            setStroke((1f * density).toInt().coerceAtLeast(1), palette.dividerLineColor)
        }
        ImageViewCompat.setImageTintList(
            binding.ivSettingsSearchIcon,
            ColorStateList.valueOf(palette.textSecondary)
        )
        ImageViewCompat.setImageTintList(
            binding.btnClearSettingsSearch,
            ColorStateList.valueOf(palette.textSecondary)
        )
        binding.etSettingsSearch.apply {
            setTextColor(palette.textPrimary)
            setHintTextColor(palette.textSecondary)
        }
    }

    private fun populateQuickJumpChips(palette: OpenManagerPalette) {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        binding.layoutQuickJumpChips.removeAllViews()
        chipHolders.clear()

        val rippleTyped = TypedValue()
        val hasRipple = ctx.theme.resolveAttribute(
            android.R.attr.selectableItemBackground, rippleTyped, true
        )

        quickJumpSpecs.forEachIndexed { index, spec ->
            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (32f * density).toInt()
                ).apply {
                    if (index < quickJumpSpecs.lastIndex) {
                        marginEnd = (7f * density).toInt()
                    }
                }
                setPadding((10f * density).toInt(), 0, (12f * density).toInt(), 0)
                if (hasRipple) {
                    foreground = ContextCompat.getDrawable(ctx, rippleTyped.resourceId)
                }
                isClickable = true
                isFocusable = true
            }

            val icon = ImageView(ctx).apply {
                val iconPx = (15f * density).toInt()
                layoutParams = LinearLayout.LayoutParams(iconPx, iconPx)
                setImageResource(spec.iconRes)
            }
            val label = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = (6f * density).toInt()
                }
                text = getString(spec.labelRes)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 1
            }

            container.addView(icon)
            container.addView(label)
            val holder = ChipHolder(spec, container, icon, label)
            container.setOnClickListener { onQuickJumpChipClicked(holder) }
            binding.layoutQuickJumpChips.addView(container)
            chipHolders.add(holder)
        }
        refreshChipStyles(palette)
    }

    private fun refreshChipStyles(palette: OpenManagerPalette) {
        val density = resources.displayMetrics.density
        val unselectedBg = when {
            palette.isAmoled -> 0xFF101013.toInt()
            palette.isDark -> 0xFF28282D.toInt()
            else -> 0xFFF2F5F9.toInt()
        }
        for (holder in chipHolders) {
            val isActive = holder.spec.categoryKey == activeChipKey
            val badgeColors = SettingsUiLogic.computeIconBadgeColors(
                semanticColor = holder.spec.semanticColor,
                isDark = palette.isDark,
                isAmoled = palette.isAmoled
            )
            holder.container.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f * density
                if (isActive) {
                    setColor(
                        SettingsUiLogic.withAlpha(
                            holder.spec.semanticColor,
                            if (palette.isDark) 0x33 else 0x22
                        )
                    )
                    setStroke((1.2f * density).toInt().coerceAtLeast(2), badgeColors.fgColor)
                } else {
                    setColor(unselectedBg)
                    setStroke((1f * density).toInt().coerceAtLeast(1), palette.dividerLineColor)
                }
            }
            if (holder.spec.preserveOriginalColors) {
                ImageViewCompat.setImageTintList(holder.icon, null)
            } else {
                ImageViewCompat.setImageTintList(
                    holder.icon,
                    ColorStateList.valueOf(badgeColors.fgColor)
                )
            }
            holder.label.apply {
                setTextColor(if (isActive) palette.textPrimary else palette.textSecondary)
                typeface = if (isActive) Typeface.DEFAULT_BOLD else Typeface.DEFAULT_BOLD
            }
        }
    }

    private fun onQuickJumpChipClicked(holder: ChipHolder) {
        activeChipKey = holder.spec.categoryKey
        refreshChipStyles(OpenManagerPalette.from(requireContext()))
        hideKeyboard()
        val hadSearch = !binding.etSettingsSearch.text.isNullOrEmpty()
        if (hadSearch) {
            binding.etSettingsSearch.setText("")
            binding.root.post {
                findPrefFragment()?.scrollToCategory(holder.spec.categoryKey)
            }
        } else {
            findPrefFragment()?.scrollToCategory(holder.spec.categoryKey)
        }
    }

    private fun setupSearchListeners() {
        binding.btnClearSettingsSearch.setOnClickListener {
            binding.etSettingsSearch.setText("")
            hideKeyboard()
        }
        binding.etSettingsSearch.doAfterTextChanged { editable ->
            val query = editable?.toString().orEmpty()
            binding.btnClearSettingsSearch.isVisible = query.isNotEmpty()
            if (query.isNotEmpty() && activeChipKey != null) {
                activeChipKey = null
                refreshChipStyles(OpenManagerPalette.from(requireContext()))
            }
            findPrefFragment()?.filterPreferences(query)
        }
        binding.etSettingsSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else {
                false
            }
        }
    }

    private fun hideKeyboard() {
        val ctx = context ?: return
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etSettingsSearch.windowToken, 0)
        binding.etSettingsSearch.clearFocus()
        binding.layoutSettingsHeader.requestFocus()
    }

    private fun findPrefFragment(): SettingsPreferenceFragment? =
        (childFragmentManager.findFragmentById(R.id.preferenceFragment) as? SettingsPreferenceFragment)
            ?: (parentFragmentManager.findFragmentById(R.id.preferenceFragment) as? SettingsPreferenceFragment)
}
