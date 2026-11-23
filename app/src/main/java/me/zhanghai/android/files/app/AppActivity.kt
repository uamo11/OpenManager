/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import me.zhanghai.android.files.settings.PerformanceModeManager
import me.zhanghai.android.files.theme.custom.CustomThemeHelper
import me.zhanghai.android.files.theme.night.NightModeHelper

abstract class AppActivity : AppCompatActivity() {
    private var isDelegateCreated = false

    private val ultraPurgeRunnable = Runnable {
        if (PerformanceModeManager.isEnabled) {
            PerformanceModeManager.purgeNativeHeapSafe()
        }
    }

    override fun getDelegate(): AppCompatDelegate {
        val delegate = super.getDelegate()

        if (!isDelegateCreated) {
            isDelegateCreated = true
            NightModeHelper.apply(this)
        }
        return delegate
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CustomThemeHelper.apply(this)

        super.onCreate(savedInstanceState)
        disableActivityTransitionIfUltra(isOpen = true)
    }

    override fun onDestroy() {
        me.zhanghai.android.files.terminal.remote.RemotePrompter.detach(this)
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        disableActivityTransitionIfUltra(isOpen = false)
    }

    override fun onResume() {
        super.onResume()
        // Any screen can answer SSH/SFTP host key and password questions from network threads.
        me.zhanghai.android.files.terminal.remote.RemotePrompter.attach(this)
        if (PerformanceModeManager.isEnabled) {
            window?.decorView?.apply {
                removeCallbacks(ultraPurgeRunnable)
                postDelayed(ultraPurgeRunnable, 400L)
            }
        }
    }

    override fun onPause() {
        window?.decorView?.removeCallbacks(ultraPurgeRunnable)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (PerformanceModeManager.isEnabled && this !is me.zhanghai.android.files.filelist.FileListActivity) {
            PerformanceModeManager.trimTransientCaches(aggressive = false)
        }
    }

    protected fun disableActivityTransitionIfUltra(isOpen: Boolean) {
        if (!PerformanceModeManager.isEnabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                if (isOpen) OVERRIDE_TRANSITION_OPEN else OVERRIDE_TRANSITION_CLOSE,
                0,
                0
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!super.onSupportNavigateUp()) {
            finish()
        }
        return true
    }
}
