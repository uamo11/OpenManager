/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.os.Build
import android.os.Bundle
import android.view.View
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.archive.archiver.ArchiveSecurityPreferences
import me.zhanghai.android.files.provider.root.DeadManProtectionManager
import me.zhanghai.android.files.provider.root.DfrootProtectedFilesDialogFragment
import me.zhanghai.android.files.provider.root.DeadManProtectedRulesDialogFragment
import me.zhanghai.android.files.theme.custom.CustomThemeHelper
import me.zhanghai.android.files.theme.custom.ThemeColor
import me.zhanghai.android.files.theme.night.NightMode
import me.zhanghai.android.files.theme.night.NightModeHelper
import me.zhanghai.android.files.ui.PreferenceFragmentCompat
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.startActivitySafe

class SettingsPreferenceFragment : PreferenceFragmentCompat() {
    private lateinit var localePreference: LocalePreference

    override fun onCreatePreferencesFix(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.settings)

        localePreference = preferenceScreen.findPreference(getString(R.string.pref_key_locale))!!
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            localePreference.setApplicationLocalesPre33 = { locales ->
                val activity = requireActivity() as SettingsActivity
                activity.setApplicationLocalesPre33(locales)
            }
        }

        findPreference<androidx.preference.Preference>(getString(R.string.pref_key_install_shizuku))?.setOnPreferenceClickListener {
            me.zhanghai.android.files.provider.root.ShizukuManager.openShizukuApp(requireContext())
            true
        }

        findPreference<androidx.preference.Preference>(getString(R.string.pref_key_check_for_update))?.setOnPreferenceClickListener {
            me.zhanghai.android.files.update.UpdateChecker.checkUpdate(requireActivity(), isManual = true)
            true
        }

        findPreference<androidx.preference.Preference>(getString(R.string.pref_key_view_error_logs))?.setOnPreferenceClickListener {
            val logsDir = me.zhanghai.android.files.app.CrashHandler.ensureLogsDirectory() ?: java.io.File("/storage/emulated/0/OpenManager/logs")
            if (!logsDir.exists()) {
                logsDir.mkdirs()
            }
            val intent = me.zhanghai.android.files.filelist.FileListActivity.createViewIntent(java8.nio.file.Paths.get(logsDir.absolutePath))
            requireContext().startActivitySafe(intent)
            true
        }

        findPreference<androidx.preference.Preference>(getString(R.string.pref_key_about_settings))?.let { pref ->
            pref.summary = "OpenManager v${me.zhanghai.android.files.BuildConfig.VERSION_NAME}"
            pref.setOnPreferenceClickListener {
                requireContext().startActivitySafe(me.zhanghai.android.files.about.AboutActivity::class.createIntent())
                true
            }
        }

        findPreference<androidx.preference.Preference>(getString(R.string.pref_key_acknowledgement))?.setOnPreferenceClickListener {
            val intent = me.zhanghai.android.files.about.AboutActivity::class.createIntent()
                .putExtra(me.zhanghai.android.files.about.AboutActivity.EXTRA_SCROLL_TO_ACKNOWLEDGEMENTS, true)
            requireContext().startActivitySafe(intent)
            true
        }

        findPreference<androidx.preference.Preference>(getString(R.string.pref_key_user_agreement))?.setOnPreferenceClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_user_agreement_title)
                .setMessage("OpenManager\n\n100% Free & Open Source Software (GPLv3).\n\nOpenManager does not collect, transmit, or share any personal data, analytics, or telemetry. All file operations remain entirely local to your device.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            true
        }

        findPreference<androidx.preference.Preference>(getString(R.string.pref_key_cloud_backup))?.setOnPreferenceClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_cloud_backup_title)
                .setMessage(R.string.settings_cloud_backup_summary)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            true
        }

        findPreference<androidx.preference.Preference>(getString(R.string.pref_key_cloud_restore))?.setOnPreferenceClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_cloud_restore_title)
                .setMessage(R.string.settings_cloud_restore_summary)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            true
        }

        findPreference<androidx.preference.SwitchPreferenceCompat>(getString(R.string.pref_key_move_to_recycle_bin_on_delete))
            ?.setOnPreferenceChangeListener { _, newValue ->
                val isEnabled = newValue as Boolean
                Settings.ENABLE_RECYCLE_BIN.putValue(isEnabled)
                true
            }

        val dfrootPref = findPreference<androidx.preference.SwitchPreferenceCompat>(
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
                    showDisableDfrootWarningDialog(preference as androidx.preference.SwitchPreferenceCompat)
                    false
                }
            } else {
                DeadManProtectionManager.recordExplicitlyDisabled(requireContext(), false)
                true
            }
        }

        findPreference<androidx.preference.Preference>(
            getString(R.string.pref_key_dead_man_dfroot_protected_files)
        )?.setOnPreferenceClickListener {
            DfrootProtectedFilesDialogFragment.show(this)
            true
        }

        val rootProtectionPref = findPreference<androidx.preference.SwitchPreferenceCompat>(
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
                    showDisableDeadManWarningDialog(preference as androidx.preference.SwitchPreferenceCompat)
                    false
                }
            } else {
                DeadManProtectionManager.recordDeadManExplicitlyDisabled(requireContext(), false)
                true
            }
        }

        findPreference<androidx.preference.Preference>(
            getString(R.string.pref_key_dead_man_root_rules)
        )?.setOnPreferenceClickListener {
            DeadManProtectedRulesDialogFragment.show(this)
            true
        }

        val rar5Pref = findPreference<androidx.preference.SwitchPreferenceCompat>(
            getString(R.string.pref_key_archive_security_enable_rar5)
        )
        rar5Pref?.setOnPreferenceChangeListener { preference, newValue ->
            val willEnable = newValue as Boolean
            if (willEnable) {
                showEnableRar5WarningDialog(preference as androidx.preference.SwitchPreferenceCompat)
                false
            } else {
                ArchiveSecurityPreferences.setRar5Enabled(requireContext(), false)
                true
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val highlightKey = activity?.intent?.getStringExtra(DeadManProtectionManager.EXTRA_HIGHLIGHT_PREF)
        if (!highlightKey.isNullOrEmpty()) {
            view.postDelayed({
                if (isAdded && !isDetached) {
                    val pref = findPreference<androidx.preference.Preference>(highlightKey)
                    if (pref != null) {
                        scrollToPreference(pref)
                    }
                }
            }, 300)
        }
    }

    private fun showDisableDeadManWarningDialog(pref: androidx.preference.SwitchPreferenceCompat) {
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

    private fun showDisableDfrootWarningDialog(pref: androidx.preference.SwitchPreferenceCompat) {
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

    private fun showEnableRar5WarningDialog(pref: androidx.preference.SwitchPreferenceCompat) {
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
        // The following may end up passing the same lambda instance to the observer because it has
        // no capture, and result in an IllegalArgumentException "Cannot add the same observer with
        // different lifecycles" if activity is finished and instantly started again. To work around
        // this, always use an instance method reference.
        // https://stackoverflow.com/a/27524543
        //Settings.THEME_COLOR.observe(viewLifecycleOwner) { CustomThemeHelper.sync() }
        //Settings.MATERIAL_DESIGN_3.observe(viewLifecycleOwner) { CustomThemeHelper.sync() }
        //Settings.NIGHT_MODE.observe(viewLifecycleOwner) { NightModeHelper.sync() }
        //Settings.BLACK_NIGHT_MODE.observe(viewLifecycleOwner) { CustomThemeHelper.sync() }
        Settings.THEME_COLOR.observe(viewLifecycleOwner, this::onThemeColorChanged)
        Settings.MATERIAL_DESIGN_3.observe(viewLifecycleOwner, this::onMaterialDesign3Changed)
        Settings.NIGHT_MODE.observe(viewLifecycleOwner, this::onNightModeChanged)
        Settings.BLACK_NIGHT_MODE.observe(viewLifecycleOwner, this::onBlackNightModeChanged)
        Settings.REQUEST_ROOT_PERMISSION.observe(viewLifecycleOwner, this::onRequestRootPermissionChanged)
        Settings.REQUEST_SHELL_PERMISSION.observe(viewLifecycleOwner, this::onRequestShellPermissionChanged)
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
        CustomThemeHelper.sync()
    }

    private fun onMaterialDesign3Changed(isMaterialDesign3: Boolean) {
        CustomThemeHelper.sync()
    }

    private fun onNightModeChanged(nightMode: NightMode) {
        NightModeHelper.sync()
        CustomThemeHelper.sync()
    }

    private fun onBlackNightModeChanged(blackNightMode: Boolean) {
        val isCurrentlyLight = (activity as? androidx.appcompat.app.AppCompatActivity)
            ?.let { !NightModeHelper.isInNightMode(it) }
            ?: (Settings.NIGHT_MODE.valueCompat == NightMode.OFF)
        if (blackNightMode && isCurrentlyLight) {
            findPreference<rikka.preference.SimpleMenuPreference>(
                getString(R.string.pref_key_night_mode)
            )?.value = NightMode.ON.ordinal.toString()
            Settings.NIGHT_MODE.putValue(NightMode.ON)
        }
        CustomThemeHelper.sync()
    }

    override fun onResume() {
        super.onResume()

        me.zhanghai.android.files.provider.root.SuperuserDetector.invalidateCache()
        findPreference<SuperuserStatusPreference>("key_superuser_status")?.refresh()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Refresh locale preference summary because we aren't notified for an external change
            // between system default and the locale that's the current system default.
            localePreference.notifyChanged()
        }
    }
}
