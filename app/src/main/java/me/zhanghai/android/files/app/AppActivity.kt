/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import me.zhanghai.android.files.util.extraPath
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
        if (openedFromOtherApp) {
            // Registered first, so screens' own back handling (unsaved text, search...) wins.
            onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = leaveToIncomingFile()
            })
        }
    }

    /** This viewer was opened for a file sent by another app (see SmartOpenActivity). */
    val openedFromOtherApp: Boolean
        get() = intent?.getBooleanExtra(EXTRA_OPENED_FROM_OTHER_APP, false) == true

    private var leavingToIncomingFile = false

    /**
     * Back/up from a file another app sent: show it in the explorer, so it can be copied or moved
     * (received without a real path it is first copied to a temporary "Received" folder).
     * Screens opened inside OpenManager simply close.
     */
    fun leaveToIncomingFile() {
        if (!openedFromOtherApp) {
            finish()
            return
        }
        if (leavingToIncomingFile) return
        leavingToIncomingFile = true
        lifecycleScope.launch {
            runCatching {
                me.zhanghai.android.files.filelist.SmartUriPathResolver.locateCurrentOrIncomingInExplorer(
                    this@AppActivity, intent, intent.extraPath
                )
            }
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (openedFromOtherApp) {
            leaveToIncomingFile()
            return true
        }
        return super.onSupportNavigateUp()
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

    companion object {
        /** Set by SmartOpenActivity on viewers it opens for files received from other apps. */
        const val EXTRA_OPENED_FROM_OTHER_APP = "me.zhanghai.android.files.extra.OPENED_FROM_OTHER_APP"
    }
}
