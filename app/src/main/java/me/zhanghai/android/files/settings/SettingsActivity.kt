/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.add
import androidx.fragment.app.commit
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.theme.custom.CustomThemeHelper
import me.zhanghai.android.files.theme.custom.CustomThemeHelper.OnThemeChangedListener
import me.zhanghai.android.files.theme.night.NightMode
import me.zhanghai.android.files.theme.night.NightModeHelper
import me.zhanghai.android.files.theme.night.NightModeHelper.OnNightModeChangedListener
import me.zhanghai.android.files.util.BundleParceler
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.getArgsOrNull
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.valueCompat

class SettingsActivity : AppActivity(), OnThemeChangedListener, OnNightModeChangedListener {
    private var isRestarting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val args = intent.extras?.getArgsOrNull<Args>()
        val savedInstanceState = savedInstanceState ?: args?.savedInstanceState
        if (BuildConfig.DEBUG) {
            // Written before the theme is applied (in super.onCreate), so this launch uses them.
            intent.getStringExtra("DEMO_MD3")?.let { Settings.MATERIAL_DESIGN_3.putValue(it == "on") }
            intent.getStringExtra("DEMO_THEME_COLOR")?.let { name ->
                me.zhanghai.android.files.theme.custom.ThemeColor.entries
                    .firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?.let { Settings.THEME_COLOR.putValue(it) }
            }
            demoThemeTarget(intent.getStringExtra("DEMO_THEME"))?.let { (night, black) ->
                Settings.BLACK_NIGHT_MODE.putValue(black)
                Settings.NIGHT_MODE.putValue(night)
            }
            intent.removeExtra("DEMO_MD3")
            intent.removeExtra("DEMO_THEME_COLOR")
        }
        super.onCreate(savedInstanceState)

        if (BuildConfig.DEBUG) {
            val demoPerfMode = intent.getStringExtra("DEMO_PERF_MODE")
            intent.removeExtra("DEMO_PERF_MODE")
            if (demoPerfMode == "on" || demoPerfMode == "off") {
                val enabled = demoPerfMode == "on"
                val changed = Settings.VISUAL_PERFORMANCE_MODE.valueCompat != enabled
                Settings.VISUAL_PERFORMANCE_MODE.putValue(enabled)
                if (changed) UltraRamVisualDefaults.onUltraRamChanged(applicationContext, enabled)
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(applicationContext)
                    .edit()
                    .putBoolean(getString(me.zhanghai.android.files.R.string.pref_key_visual_performance_mode), enabled)
                    .commit()
                PerformanceModeManager.applyMode(applicationContext, enabled, triggerTrim = true)
            }
        }

        // Calls ensureSubDecor().
        findViewById<View>(android.R.id.content)
        if (savedInstanceState == null) {
            supportFragmentManager.commit { add<SettingsFragment>(android.R.id.content) }
        }
        if (BuildConfig.DEBUG) {
            val demoLocale = intent.getStringExtra("DEMO_LOCALE")
            val demoTheme = intent.getStringExtra("DEMO_THEME")
            intent.removeExtra("DEMO_LOCALE")
            intent.removeExtra("DEMO_THEME")
            if (!demoLocale.isNullOrEmpty() || !demoTheme.isNullOrEmpty()) {
                window.decorView.post {
                    if (isFinishing || isDestroyed) return@post
                    if (demoThemeTarget(demoTheme) != null) {
                        // The settings were written before onCreate; bring this screen in line
                        // if it was already created with the old ones.
                        NightModeHelper.sync()
                        CustomThemeHelper.sync()
                        if (isRestarting) return@post
                    }
                    if (!demoLocale.isNullOrEmpty()) {
                        val targetLocales = when (demoLocale) {
                            "reset" -> LocaleListCompat.getEmptyLocaleList()
                            else -> LocaleListCompat.forLanguageTags(demoLocale)
                        }
                        if (targetLocales != null) {
                            val currentTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
                            if (currentTags != targetLocales.toLanguageTags()) {
                                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                                    setApplicationLocalesPre33(targetLocales)
                                } else {
                                    AppCompatDelegate.setApplicationLocales(targetLocales)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun setApplicationLocalesPre33(locales: LocaleListCompat) {
        // HACK: Prevent this activity from being recreated due to locale change.
        delegate.onDestroy()
        AppCompatDelegate.setApplicationLocales(locales)
        restart()
    }

    override fun onThemeChanged(@StyleRes theme: Int) {
        // ActivityCompat.recreate() may call ActivityRecreator.recreate() without calling
        // Activity.recreate(), so we cannot simply override it. To work around this, we just
        // manually call restart(). Posted: a theme setting can change while fragments are being
        // started, and saving state in the middle of that crashes.
        window.decorView.post { if (!isFinishing && !isDestroyed) restart() }
    }

    private fun demoThemeTarget(demoTheme: String?): Pair<NightMode, Boolean>? = when (demoTheme) {
        "dark" -> NightMode.ON to false
        "amoled" -> NightMode.ON to true
        "light" -> NightMode.OFF to false
        "system_black" -> NightMode.FOLLOW_SYSTEM to true
        else -> null
    }

    override fun onNightModeChangedFromHelper(nightMode: Int) {
        // ActivityCompat.recreate() may call ActivityRecreator.recreate() without calling
        // Activity.recreate(), so we cannot simply override it. To work around this, we just
        // manually call restart().
        window.decorView.post { if (!isFinishing && !isDestroyed) restart() }
    }

    private fun restart() {
        if (isRestarting) return
        val demoKeys = arrayOf(
            "DEMO_ACTIVE_CHIP",
            "DEMO_COLLAPSE_HEADER",
            "DEMO_SEARCH",
            "DEMO_CATEGORY",
            "DEMO_OPEN_PREF"
        )
        val hasDemoExtras = BuildConfig.DEBUG && demoKeys.any { !intent.getStringExtra(it).isNullOrEmpty() }
        val nextIntent = if (hasDemoExtras) {
            SettingsActivity::class.createIntent().apply {
                for (key in demoKeys) {
                    intent.getStringExtra(key)?.takeIf { it.isNotEmpty() }?.let { putExtra(key, it) }
                }
            }
        } else {
            val savedInstanceState = Bundle().apply {
                onSaveInstanceState(this)
            }
            SettingsActivity::class.createIntent().putArgs(Args(savedInstanceState))
        }
        finish()
        startActivitySafe(nextIntent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        isRestarting = true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return isRestarting || super.dispatchKeyEvent(event)
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean {
        return isRestarting || super.dispatchKeyShortcutEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        return isRestarting || super.dispatchTouchEvent(event)
    }

    override fun dispatchTrackballEvent(event: MotionEvent): Boolean {
        return isRestarting || super.dispatchTrackballEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        return isRestarting || super.dispatchGenericMotionEvent(event)
    }

    @Parcelize
    class Args(val savedInstanceState: @WriteWith<BundleParceler> Bundle?) : ParcelableArgs
}
