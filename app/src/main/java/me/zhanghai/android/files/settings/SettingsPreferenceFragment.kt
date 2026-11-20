/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.archive.archiver.ArchiveSecurityPreferences
import me.zhanghai.android.files.provider.root.DeadManProtectionManager
import me.zhanghai.android.files.provider.root.DeadManProtectedRulesDialogFragment
import me.zhanghai.android.files.provider.root.DfrootProtectedFilesDialogFragment
import me.zhanghai.android.files.theme.custom.CustomThemeHelper
import me.zhanghai.android.files.theme.custom.OpenManagerPalette
import me.zhanghai.android.files.theme.custom.ThemeColor
import me.zhanghai.android.files.theme.night.NightMode
import me.zhanghai.android.files.theme.night.NightModeHelper
import me.zhanghai.android.files.ui.PreferenceFragmentCompat
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.valueCompat

class SettingsPreferenceFragment : PreferenceFragmentCompat() {
    private lateinit var localePreference: LocalePreference
    private val defaultVisibilityMap = LinkedHashMap<Preference, Boolean>()
    private var isUpdatingThemePreset = false

    override fun onCreatePreferencesFix(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.settings)

        SettingsIconHelper.applyIconsToScreen(
            requireContext(),
            preferenceScreen,
            OpenManagerPalette.from(requireContext())
        )
        captureDefaultVisibility(preferenceScreen)
        updateVisualPerfSummary(Settings.VISUAL_PERFORMANCE_MODE.valueCompat)

        localePreference = preferenceScreen.findPreference(getString(R.string.pref_key_locale))!!
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            localePreference.setApplicationLocalesPre33 = { locales ->
                val activity = requireActivity() as SettingsActivity
                activity.setApplicationLocalesPre33(locales)
            }
        }

        findPreference<AppearancePerformanceHubPreference>("key_appearance_performance_hub")?.apply {
            onThemePresetSelected = ::applyThemePreset
            onAccentClicked = {
                findPreference<Preference>(getString(R.string.pref_key_theme_color))?.performClick()
            }
            onThumbnailModeSelected = { mode ->
                val thumbPref = findPreference<ListPreference>(
                    getString(R.string.pref_key_thumbnail_load_mode)
                )
                thumbPref?.value = mode
                Settings.THUMBNAIL_LOAD_MODE.putValue(mode)
                refreshState()
            }
            onVisualPerformanceToggled = { enabled ->
                val perfPref = findPreference<SwitchPreferenceCompat>(
                    getString(R.string.pref_key_visual_performance_mode)
                )
                perfPref?.isChecked = enabled
                Settings.VISUAL_PERFORMANCE_MODE.putValue(enabled)
                refreshState()
            }
        }

        findPreference<Preference>(getString(R.string.pref_key_install_shizuku))?.setOnPreferenceClickListener {
            me.zhanghai.android.files.provider.root.ShizukuManager.openShizukuApp(requireContext())
            true
        }

        findPreference<Preference>(getString(R.string.pref_key_check_for_update))?.setOnPreferenceClickListener {
            me.zhanghai.android.files.update.UpdateChecker.checkUpdate(requireActivity(), isManual = true) {
                if (isAdded) {
                    refreshUpdateCheckSummary()
                }
            }
            true
        }
        refreshUpdateCheckSummary()

        findPreference<Preference>(getString(R.string.pref_key_view_error_logs))?.setOnPreferenceClickListener {
            val logsDir = me.zhanghai.android.files.app.CrashHandler.ensureLogsDirectory()
                ?: java.io.File("/storage/emulated/0/OpenManager/logs")
            if (!logsDir.exists()) {
                logsDir.mkdirs()
            }
            val intent = me.zhanghai.android.files.filelist.FileListActivity.createViewIntent(
                java8.nio.file.Paths.get(logsDir.absolutePath)
            )
            requireContext().startActivitySafe(intent)
            true
        }

        findPreference<Preference>(getString(R.string.pref_key_about_settings))?.let { pref ->
            pref.summary = "OpenManager v${me.zhanghai.android.files.BuildConfig.VERSION_NAME}"
            pref.setOnPreferenceClickListener {
                requireContext().startActivitySafe(me.zhanghai.android.files.about.AboutActivity::class.createIntent())
                true
            }
        }

        findPreference<Preference>(getString(R.string.pref_key_acknowledgement))?.setOnPreferenceClickListener {
            val intent = me.zhanghai.android.files.about.AboutActivity::class.createIntent()
                .putExtra(me.zhanghai.android.files.about.AboutActivity.EXTRA_SCROLL_TO_ACKNOWLEDGEMENTS, true)
            requireContext().startActivitySafe(intent)
            true
        }

        findPreference<Preference>(getString(R.string.pref_key_user_agreement))?.setOnPreferenceClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_user_agreement_title)
                .setMessage("OpenManager\n\n100% Free & Open Source Software (GPLv3).\n\nOpenManager does not collect, transmit, or share any personal data, analytics, or telemetry. All file operations remain entirely local to your device.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            true
        }

        findPreference<Preference>(getString(R.string.pref_key_cloud_backup))?.setOnPreferenceClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_cloud_backup_title)
                .setMessage(R.string.settings_cloud_backup_summary)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            true
        }

        findPreference<Preference>(getString(R.string.pref_key_cloud_restore))?.setOnPreferenceClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_cloud_restore_title)
                .setMessage(R.string.settings_cloud_restore_summary)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            true
        }

        findPreference<SwitchPreferenceCompat>(getString(R.string.pref_key_move_to_recycle_bin_on_delete))
            ?.setOnPreferenceChangeListener { _, newValue ->
                val isEnabled = newValue as Boolean
                Settings.ENABLE_RECYCLE_BIN.putValue(isEnabled)
                true
            }

        val dfrootPref = findPreference<SwitchPreferenceCompat>(
            getString(R.string.pref_key_dead_man_dfroot_protection)
        )
        dfrootPref?.setOnPreferenceChangeListener { preference, newValue ->
            val willEnable = newValue as Boolean
            if (!willEnable) {
                if (DeadManProtectionManager.hasWaitedCountdown) {
                    DeadManProtectionManager.hasWaitedCountdown = false
                    DeadManProtectionManager.recordExplicitlyDisabled(requireContext(), true)
                    true
                } else {
                    showDisableDfrootWarningDialog(preference as SwitchPreferenceCompat)
                    false
                }
            } else {
                DeadManProtectionManager.recordExplicitlyDisabled(requireContext(), false)
                true
            }
        }

        findPreference<Preference>(
            getString(R.string.pref_key_dead_man_dfroot_protected_files)
        )?.setOnPreferenceClickListener {
            DfrootProtectedFilesDialogFragment.show(this)
            true
        }

        val rootProtectionPref = findPreference<SwitchPreferenceCompat>(
            getString(R.string.pref_key_dead_man_root_protection)
        )
        rootProtectionPref?.setOnPreferenceChangeListener { preference, newValue ->
            val willEnable = newValue as Boolean
            if (!willEnable) {
                if (DeadManProtectionManager.hasWaitedDeadManCountdown) {
                    DeadManProtectionManager.hasWaitedDeadManCountdown = false
                    DeadManProtectionManager.recordDeadManExplicitlyDisabled(requireContext(), true)
                    true
                } else {
                    showDisableDeadManWarningDialog(preference as SwitchPreferenceCompat)
                    false
                }
            } else {
                DeadManProtectionManager.recordDeadManExplicitlyDisabled(requireContext(), false)
                true
            }
        }

        findPreference<Preference>(
            getString(R.string.pref_key_dead_man_root_rules)
        )?.setOnPreferenceClickListener {
            DeadManProtectedRulesDialogFragment.show(this)
            true
        }

        val rar5Pref = findPreference<SwitchPreferenceCompat>(
            getString(R.string.pref_key_archive_security_enable_rar5)
        )
        rar5Pref?.setOnPreferenceChangeListener { preference, newValue ->
            val willEnable = newValue as Boolean
            if (willEnable) {
                showEnableRar5WarningDialog(preference as SwitchPreferenceCompat)
                false
            } else {
                ArchiveSecurityPreferences.setRar5Enabled(requireContext(), false)
                true
            }
        }
    }

    private fun captureDefaultVisibility(group: PreferenceGroup) {
        for (i in 0 until group.preferenceCount) {
            val child = group.getPreference(i)
            if (child.key == "key_search_empty_state") continue
            defaultVisibilityMap[child] = child.isVisible
            if (child is PreferenceGroup) {
                captureDefaultVisibility(child)
            }
        }
    }

    private fun applyThemePreset(preset: SettingsUiLogic.ThemeQuickPreset) {
        val targetNightMode = when (preset) {
            SettingsUiLogic.ThemeQuickPreset.LIGHT -> NightMode.OFF
            SettingsUiLogic.ThemeQuickPreset.DARK -> NightMode.ON
            SettingsUiLogic.ThemeQuickPreset.AMOLED -> NightMode.ON
            SettingsUiLogic.ThemeQuickPreset.SYSTEM -> NightMode.FOLLOW_SYSTEM
        }
        val targetBlack = (preset == SettingsUiLogic.ThemeQuickPreset.AMOLED)
        val currentNight = Settings.NIGHT_MODE.valueCompat
        val currentBlack = Settings.BLACK_NIGHT_MODE.valueCompat
        if (currentNight == targetNightMode && currentBlack == targetBlack) return

        val nightModePref = findPreference<ListPreference>(getString(R.string.pref_key_night_mode))
        val blackPref = findPreference<SwitchPreferenceCompat>(getString(R.string.pref_key_black_night_mode))

        isUpdatingThemePreset = true
        try {
            if (currentBlack != targetBlack) {
                blackPref?.isChecked = targetBlack
                Settings.BLACK_NIGHT_MODE.putValue(targetBlack)
            }
            if (currentNight != targetNightMode) {
                nightModePref?.value = targetNightMode.ordinal.toString()
                Settings.NIGHT_MODE.putValue(targetNightMode)
            }
        } finally {
            isUpdatingThemePreset = false
        }
        NightModeHelper.sync()
        CustomThemeHelper.sync()
    }

    fun filterPreferences(rawQuery: String) {
        val normalized = SettingsUiLogic.normalizeQuery(rawQuery)
        val screen = preferenceScreen ?: return
        val emptyPref = findPreference<Preference>("key_search_empty_state")
        if (normalized.isEmpty()) {
            for ((pref, visible) in defaultVisibilityMap) {
                pref.isVisible = visible
            }
            emptyPref?.isVisible = false
            return
        }
        var totalMatches = 0
        for (i in 0 until screen.preferenceCount) {
            val top = screen.getPreference(i)
            if (top.key == "key_search_empty_state") continue
            if (top is PreferenceGroup) {
                var groupHasVisibleChild = false
                for (j in 0 until top.preferenceCount) {
                    val child = top.getPreference(j)
                    val baseVisible = defaultVisibilityMap[child] ?: true
                    if (!baseVisible) {
                        child.isVisible = false
                        continue
                    }
                    val matched = SettingsUiLogic.matchesSearch(
                        normalizedQuery = normalized,
                        title = child.title,
                        summary = child.summary,
                        entries = (child as? ListPreference)?.entries,
                        keywords = SettingsUiLogic.keywordsForKey(child.key)
                    )
                    child.isVisible = matched
                    if (matched) {
                        groupHasVisibleChild = true
                        totalMatches++
                    }
                }
                top.isVisible = (defaultVisibilityMap[top] ?: true) && groupHasVisibleChild
            } else {
                val baseVisible = defaultVisibilityMap[top] ?: true
                val matched = baseVisible && SettingsUiLogic.matchesSearch(
                    normalizedQuery = normalized,
                    title = top.title,
                    summary = top.summary,
                    entries = (top as? ListPreference)?.entries,
                    keywords = SettingsUiLogic.keywordsForKey(top.key)
                )
                top.isVisible = matched
                if (matched) {
                    totalMatches++
                }
            }
        }
        emptyPref?.isVisible = (totalMatches == 0)
    }

    fun scrollToCategory(categoryKey: String) {
        val lv = listView ?: return
        val target = findPreference<Preference>(categoryKey) ?: return
        val callback = lv.adapter as? PreferenceGroup.PreferencePositionCallback
        val pos = callback?.getPreferenceAdapterPosition(target) ?: -1
        if (pos >= 0) {
            val lm = lv.layoutManager as? LinearLayoutManager
            if (lm != null) {
                lm.scrollToPositionWithOffset(pos, 0)
                return
            }
        }
        scrollToPreference(target)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listView?.apply {
            setItemViewCacheSize(PerformanceModeManager.currentBudget.settingsViewCacheSize)
            if (PerformanceModeManager.isEnabled) {
                itemAnimator = null
            } else {
                (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
            }
        }
        val palette = OpenManagerPalette.from(requireContext())
        if (!palette.isDark || palette.isAmoled) {
            view.setBackgroundColor(palette.screenBg)
            listView?.setBackgroundColor(palette.screenBg)
        }

        val highlightKey = activity?.intent?.getStringExtra(DeadManProtectionManager.EXTRA_HIGHLIGHT_PREF)
        if (!highlightKey.isNullOrEmpty()) {
            view.postDelayed({
                if (isAdded && !isDetached) {
                    val pref = findPreference<Preference>(highlightKey)
                    if (pref != null) {
                        scrollToPreference(pref)
                    }
                }
            }, 300)
        }

        if (BuildConfig.DEBUG) {
            val demoCategory = activity?.intent?.getStringExtra("DEMO_CATEGORY")
            val demoOpenPref = activity?.intent?.getStringExtra("DEMO_OPEN_PREF")
            if (!demoCategory.isNullOrEmpty() || !demoOpenPref.isNullOrEmpty()) {
                view.postDelayed({
                    if (isAdded && !isDetached) {
                        if (!demoCategory.isNullOrEmpty()) {
                            scrollToCategory(demoCategory)
                        }
                        if (!demoOpenPref.isNullOrEmpty()) {
                            view.postDelayed({
                                if (isAdded && !isDetached) {
                                    findPreference<Preference>(demoOpenPref)?.performClick()
                                }
                            }, 250)
                        }
                    }
                }, 350)
            }
        }
    }

    private fun showDisableDeadManWarningDialog(pref: SwitchPreferenceCompat) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setIcon(R.drawable.ic_dead_man_skull)
            .setTitle(R.string.dead_man_root_warning_disable_title)
            .setMessage(R.string.dead_man_root_warning_disable_message)
            .setPositiveButton(R.string.dead_man_root_disable_confirm) { dialog, _ ->
                dialog.dismiss()
                DeadManProtectionManager.recordDeadManExplicitlyDisabled(requireContext(), true)
                pref.isChecked = false
                Settings.DEAD_MAN_ROOT_PROTECTION.putValue(false)
            }
            .setNegativeButton(R.string.dead_man_protection_cancel) { dialog, _ ->
                dialog.dismiss()
                pref.isChecked = true
            }
            .show()
    }

    private fun showDisableDfrootWarningDialog(pref: SwitchPreferenceCompat) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setIcon(R.drawable.ic_root_dfroot)
            .setTitle(R.string.dead_man_dfroot_warning_disable_title)
            .setMessage(R.string.dead_man_dfroot_warning_disable_message)
            .setPositiveButton(R.string.dead_man_dfroot_disable_confirm) { dialog, _ ->
                dialog.dismiss()
                DeadManProtectionManager.recordExplicitlyDisabled(requireContext(), true)
                pref.isChecked = false
                Settings.DEAD_MAN_DFROOT_PROTECTION.putValue(false)
            }
            .setNegativeButton(R.string.dirtyflag_protection_cancel) { dialog, _ ->
                dialog.dismiss()
                pref.isChecked = true
            }
            .show()
    }

    private fun showEnableRar5WarningDialog(pref: SwitchPreferenceCompat) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setIcon(R.drawable.ic_crash_alert_24dp)
            .setTitle(R.string.settings_archive_security_rar5_dialog_title)
            .setMessage(R.string.settings_archive_security_rar5_dialog_message)
            .setPositiveButton(R.string.settings_archive_security_rar5_dialog_confirm) { dialog, _ ->
                dialog.dismiss()
                ArchiveSecurityPreferences.setRar5Enabled(requireContext(), true)
                pref.isChecked = true
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.dismiss()
                pref.isChecked = false
            }
            .setOnCancelListener {
                pref.isChecked = false
            }
            .show()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        val viewLifecycleOwner = viewLifecycleOwner
        Settings.THEME_COLOR.observe(viewLifecycleOwner, this::onThemeColorChanged)
        Settings.MATERIAL_DESIGN_3.observe(viewLifecycleOwner, this::onMaterialDesign3Changed)
        Settings.NIGHT_MODE.observe(viewLifecycleOwner, this::onNightModeChanged)
        Settings.BLACK_NIGHT_MODE.observe(viewLifecycleOwner, this::onBlackNightModeChanged)
        Settings.THUMBNAIL_LOAD_MODE.observe(viewLifecycleOwner, this::onHubPerformanceChanged)
        Settings.VISUAL_PERFORMANCE_MODE.observe(viewLifecycleOwner, this::onVisualPerfChanged)
        Settings.REQUEST_ROOT_PERMISSION.observe(viewLifecycleOwner, this::onRequestRootPermissionChanged)
        Settings.REQUEST_SHELL_PERMISSION.observe(viewLifecycleOwner, this::onRequestShellPermissionChanged)
    }

    private fun onHubPerformanceChanged(mode: String) {
        findPreference<AppearancePerformanceHubPreference>("key_appearance_performance_hub")?.refreshState()
    }

    override fun onDestroyView() {
        if (PerformanceModeManager.isEnabled) {
            SettingsIconHelper.clearCaches()
        }
        super.onDestroyView()
    }

    private fun updateVisualPerfSummary(enabled: Boolean) {
        val ctx = context ?: return
        val perfPref = findPreference<SwitchPreferenceCompat>(
            getString(R.string.pref_key_visual_performance_mode)
        ) ?: return
        perfPref.summary = if (enabled) {
            val snap = PerformanceModeManager.readLiveHeapSnapshot()
            ctx.getString(
                R.string.settings_visual_performance_mode_active_summary_format,
                snap.formatCompact()
            )
        } else {
            ctx.getString(R.string.settings_visual_performance_mode_summary)
        }
    }

    private fun onVisualPerfChanged(enabled: Boolean) {
        val ctx = context ?: return
        PerformanceModeManager.applyMode(ctx, enabled, triggerTrim = enabled)
        listView?.apply {
            setItemViewCacheSize(PerformanceModeManager.currentBudget.settingsViewCacheSize)
            if (enabled) {
                itemAnimator = null
            }
        }
        updateVisualPerfSummary(enabled)
        findPreference<AppearancePerformanceHubPreference>("key_appearance_performance_hub")?.refreshState()
    }

    private fun onRequestRootPermissionChanged(enabled: Boolean) {
        findPreference<SuperuserStatusPreference>("key_superuser_status")?.refresh()
    }

    private fun onRequestShellPermissionChanged(enabled: Boolean) {
        if (enabled && me.zhanghai.android.files.provider.root.ShizukuManager.isAvailable() && !me.zhanghai.android.files.provider.root.ShizukuManager.hasPermission()) {
            activity?.let { act ->
                me.zhanghai.android.files.provider.root.ShizukuManager.requestPermission(act) {
                    findPreference<SuperuserStatusPreference>("key_superuser_status")?.refresh()
                }
            }
        }
        findPreference<SuperuserStatusPreference>("key_superuser_status")?.refresh()
    }

    private fun onThemeColorChanged(themeColor: ThemeColor) {
        if (isUpdatingThemePreset) return
        CustomThemeHelper.sync()
    }

    private fun onMaterialDesign3Changed(isMaterialDesign3: Boolean) {
        if (isUpdatingThemePreset) return
        CustomThemeHelper.sync()
    }

    private fun onNightModeChanged(nightMode: NightMode) {
        if (isUpdatingThemePreset) return
        NightModeHelper.sync()
        CustomThemeHelper.sync()
    }

    private fun onBlackNightModeChanged(blackNightMode: Boolean) {
        if (isUpdatingThemePreset) return
        val isCurrentlyLight = (activity as? androidx.appcompat.app.AppCompatActivity)
            ?.let { !NightModeHelper.isInNightMode(it) }
            ?: (Settings.NIGHT_MODE.valueCompat == NightMode.OFF)
        if (blackNightMode && isCurrentlyLight) {
            findPreference<ListPreference>(
                getString(R.string.pref_key_night_mode)
            )?.value = NightMode.ON.ordinal.toString()
            Settings.NIGHT_MODE.putValue(NightMode.ON)
        }
        CustomThemeHelper.sync()
    }

    private fun refreshUpdateCheckSummary() {
        val ctx = context ?: return
        me.zhanghai.android.files.update.UpdateChecker.ensureAutoUpdateDefaultMigrated(ctx)
        findPreference<SwitchPreferenceCompat>(
            getString(R.string.pref_key_auto_check_updates)
        )?.isChecked = Settings.AUTO_CHECK_UPDATES.valueCompat

        val checkPref = findPreference<Preference>(
            getString(R.string.pref_key_check_for_update)
        ) ?: return
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
        val lastCheckMs = prefs.getLong(
            me.zhanghai.android.files.update.UpdateChecker.PREF_LAST_CHECK_TIME,
            0L
        )
        val channelLabel = getString(
            if (me.zhanghai.android.files.update.UpdateChecker.isDebugBuild(ctx)) {
                R.string.update_channel_debug
            } else {
                R.string.update_channel_release
            }
        )
        val relative = me.zhanghai.android.files.update.UpdateChecker.formatRelativeLastCheck(ctx, lastCheckMs)
        checkPref.summary = getString(
            R.string.settings_check_update_dynamic_summary,
            "v${me.zhanghai.android.files.BuildConfig.VERSION_NAME}",
            channelLabel,
            relative
        )
    }

    override fun onResume() {
        super.onResume()

        me.zhanghai.android.files.provider.root.SuperuserDetector.invalidateCache()
        findPreference<SuperuserStatusPreference>("key_superuser_status")?.refresh()
        findPreference<AppearancePerformanceHubPreference>("key_appearance_performance_hub")?.refreshState()
        refreshUpdateCheckSummary()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            localePreference.notifyChanged()
        }
    }
}
