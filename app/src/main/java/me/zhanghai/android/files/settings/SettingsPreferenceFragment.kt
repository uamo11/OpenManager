/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.os.Build
import android.os.Bundle
import android.view.View
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.root.DeadManProtectionManager
import me.zhanghai.android.files.provider.root.DfrootProtectedFilesDialogFragment
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
            requireContext().startActivitySafe(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/")))
            true
        }

        findPreference<androidx.preference.Preference>(getString(R.string.pref_key_check_for_update))?.setOnPreferenceClickListener {
            requireContext().startActivitySafe(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/anhot11/OpenManager")))
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
            requireContext().startActivitySafe(me.zhanghai.android.files.about.AboutActivity::class.createIntent())
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
    }

    private fun onRequestRootPermissionChanged(enabled: Boolean) {
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
    }

    private fun onBlackNightModeChanged(blackNightMode: Boolean) {
        CustomThemeHelper.sync()
    }

    override fun onResume() {
        super.onResume()

        findPreference<SuperuserStatusPreference>("key_superuser_status")?.refresh()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Refresh locale preference summary because we aren't notified for an external change
            // between system default and the locale that's the current system default.
            localePreference.notifyChanged()
        }
    }
}
