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
        if (BuildConfig.DEBUG && savedInstanceState == null) {
            intent.getStringExtra("DEMO_LOCALE")?.let { loc ->
                when (loc) {
                    "es" -> {
                        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != "es") {
                            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("es"))
                        }
                    }
                    "reset" -> {
                        if (!AppCompatDelegate.getApplicationLocales().isEmpty) {
                            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
                        }
                    }
                }
            }
            intent.getStringExtra("DEMO_THEME")?.let { theme ->
                when (theme) {
                    "dark" -> {
                        Settings.BLACK_NIGHT_MODE.putValue(false)
                        Settings.NIGHT_MODE.putValue(NightMode.ON)
                        delegate.localNightMode = NightMode.ON.value
                        NightModeHelper.sync()
                        CustomThemeHelper.sync()
                    }
                    "amoled" -> {
                        Settings.NIGHT_MODE.putValue(NightMode.ON)
                        Settings.BLACK_NIGHT_MODE.putValue(true)
                        delegate.localNightMode = NightMode.ON.value
                        NightModeHelper.sync()
                        CustomThemeHelper.sync()
                    }
                    "light" -> {
                        Settings.BLACK_NIGHT_MODE.putValue(false)
                        Settings.NIGHT_MODE.putValue(NightMode.OFF)
                        delegate.localNightMode = NightMode.OFF.value
                        NightModeHelper.sync()
                        CustomThemeHelper.sync()
                    }
                }
            }
        }
        super.onCreate(savedInstanceState)

        // Calls ensureSubDecor().
        findViewById<View>(android.R.id.content)
        if (savedInstanceState == null) {
            supportFragmentManager.commit { add<SettingsFragment>(android.R.id.content) }
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
        // manually call restart().
        restart()
    }

    override fun onNightModeChangedFromHelper(nightMode: Int) {
        // ActivityCompat.recreate() may call ActivityRecreator.recreate() without calling
        // Activity.recreate(), so we cannot simply override it. To work around this, we just
        // manually call restart().
        restart()
    }

    private fun restart() {
        if (isRestarting) return
        val savedInstanceState = Bundle().apply {
            onSaveInstanceState(this)
        }
        finish()
        val intent = SettingsActivity::class.createIntent().putArgs(Args(savedInstanceState))
        startActivitySafe(intent)
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
