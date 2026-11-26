/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

import android.os.Bundle
import android.app.Application
import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.AsyncTask
import android.os.Build
import android.webkit.WebView
import jcifs.context.SingletonContext
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.appmanager.InstalledAppsActivity
import me.zhanghai.android.files.appmanager.NativeAppIconLoader
import me.zhanghai.android.files.coil.clearCoilMemory
import me.zhanghai.android.files.coil.initializeCoil
import me.zhanghai.android.files.coil.trimCoilMemory
import me.zhanghai.android.files.filejob.fileJobNotificationTemplate
import me.zhanghai.android.files.filelist.AppMetadataCache
import me.zhanghai.android.files.filelist.DirectorySnapshotCache
import me.zhanghai.android.files.ftpserver.ftpServerServiceNotificationTemplate
import me.zhanghai.android.files.filelist.apktoolNotificationTemplate
import me.zhanghai.android.files.terminal.scriptExecutionNotificationTemplate
import me.zhanghai.android.files.hiddenapi.HiddenApi
import me.zhanghai.android.files.provider.FileSystemProviders
import me.zhanghai.android.files.settings.PerformanceModeManager
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.settings.SettingsIconHelper
import me.zhanghai.android.files.storage.FtpServerAuthenticator
import me.zhanghai.android.files.storage.SftpServerAuthenticator
import me.zhanghai.android.files.storage.SmbServerAuthenticator
import me.zhanghai.android.files.storage.StorageVolumeListLiveData
import me.zhanghai.android.files.storage.WebDavServerAuthenticator
import me.zhanghai.android.files.theme.custom.CustomThemeHelper
import me.zhanghai.android.files.theme.night.NightModeHelper
import me.zhanghai.android.files.util.valueCompat
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import me.zhanghai.android.files.provider.ftp.client.Client as FtpClient
import me.zhanghai.android.files.provider.sftp.client.Client as SftpClient
import me.zhanghai.android.files.provider.root.LibSuFileServiceLauncher
import me.zhanghai.android.files.provider.sftp.client.SecurityProviderHelper
import me.zhanghai.android.files.provider.smb.client.Client as SmbClient
import me.zhanghai.android.files.provider.webdav.client.Client as WebDavClient

val appInitializers = listOf(
    ::initializeCrashHandler,
    ::migrateAppFolder,
    ::initializeLibSu,
    ::disableHiddenApiChecks,
    ::initializeFileSystemProviders,
    ::upgradeApp,
    ::initializeLiveDataObjects,
    ::initializePerformanceMode,
    ::initializeCoil,
    ::initializeCustomTheme,
    ::initializeNightMode,
    ::createNotificationChannels,
    ::initializeTerminalBinTools,
    ::initializeMemoryTrimAndWarmUp
)

private val webViewDebuggingInitialized = AtomicBoolean(false)
private val smbContextInitialized = AtomicBoolean(false)

fun ensureWebViewDebuggingIfDebug() {
    if (BuildConfig.DEBUG && webViewDebuggingInitialized.compareAndSet(false, true)) {
        try {
            WebView.setWebContentsDebuggingEnabled(true)
        } catch (_: Throwable) {}
    }
}

fun ensureSmbSingletonContextInitialized() {
    if (smbContextInitialized.compareAndSet(false, true)) {
        try {
            SingletonContext.init(
                Properties().apply {
                    setProperty("jcifs.netbios.cachePolicy", "0")
                    setProperty("jcifs.smb.client.maxVersion", "SMB1")
                }
            )
        } catch (_: Throwable) {}
    }
}

private fun initializeCrashHandler() {
    CrashHandler.install(application)
}

private fun initializeLibSu() {
    try {
        LibSuFileServiceLauncher.initBuilder()
    } catch (e: Throwable) {
        e.printStackTrace()
    }
}

private fun disableHiddenApiChecks() {
    HiddenApi.disableHiddenApiChecks()
}

private fun migrateAppFolder() {
    // One stat when there is nothing to do; a single rename otherwise.
    try {
        @Suppress("DEPRECATION")
        AppFolderMigration.migrate(android.os.Environment.getExternalStorageDirectory())
        val key = application.getString(me.zhanghai.android.files.R.string.pref_key_custom_openmanager_dir)
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(application)
        AppFolderMigration.migratedSetting(prefs.getString(key, null))?.let {
            prefs.edit().putString(key, it).apply()
        }
    } catch (_: Throwable) {
    }
}

private fun initializeFileSystemProviders() {
    FileSystemProviders.install()
    FileSystemProviders.overflowWatchEvents = true
    // Debug builds time each client (tag OMStartup): setting an authenticator loads the
    // client object of that protocol, which may cost more than its provider.
    val timed = me.zhanghai.android.files.BuildConfig.DEBUG
    fun step(name: String, block: () -> Unit) {
        if (!timed) return block()
        val start = android.os.SystemClock.elapsedRealtimeNanos()
        block()
        val us = (android.os.SystemClock.elapsedRealtimeNanos() - start) / 1_000
        android.util.Log.i("OMStartup", "  client $name: ${us / 1000}.${us % 1000 / 100} ms")
    }
    step("ftp") { FtpClient.authenticator = FtpServerAuthenticator }
    step("sftp") { SftpClient.authenticator = SftpServerAuthenticator }
    step("smb") { SmbClient.authenticator = SmbServerAuthenticator }
    step("webdav") { WebDavClient.authenticator = WebDavServerAuthenticator }
}

private fun initializeLiveDataObjects() {
    // Force initialization of LiveData objects so that it won't happen on a background thread.
    StorageVolumeListLiveData.value
    Settings.FILE_LIST_DEFAULT_DIRECTORY.value
}

private fun initializePerformanceMode() {
    val enabled = Settings.VISUAL_PERFORMANCE_MODE.valueCompat
    PerformanceModeManager.initialize(enabled)
    // Ultra RAM keeps Material 3 off; older versions let it be switched on inside the mode.
    if (enabled && Settings.MATERIAL_DESIGN_3.valueCompat) {
        Settings.MATERIAL_DESIGN_3.putValue(false)
    }
    if (!enabled) {
        // Still registered eagerly (Ultra RAM mode does it lazily), but off the main thread:
        // building the provider and its ~1,500 algorithm entries delayed the first frame.
        // Every SFTP/SSH/BKS user calls ensureInitialized(), which waits for this if needed.
        AsyncTask.THREAD_POOL_EXECUTOR.execute { SecurityProviderHelper.ensureInitialized() }
        // SingletonContext.init() calls NameServiceClientImpl.initCache() which connects to network.
        AsyncTask.THREAD_POOL_EXECUTOR.execute {
            ensureSmbSingletonContextInitialized()
        }
    }
}

private fun initializeCustomTheme() {
    CustomThemeHelper.initialize(application)
}

private fun initializeNightMode() {
    NightModeHelper.initialize(application)
}

private fun createNotificationChannels() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        notificationManager.createNotificationChannels(
            listOf(
                backgroundActivityStartNotificationTemplate.channelTemplate,
                fileJobNotificationTemplate.channelTemplate,
                ftpServerServiceNotificationTemplate.channelTemplate,
                apktoolNotificationTemplate.channelTemplate,
                scriptExecutionNotificationTemplate.channelTemplate,
                me.zhanghai.android.files.terminal.tracer.commandGuardNotificationTemplate.channelTemplate,
                me.zhanghai.android.files.webaccess.webAccessNotificationTemplate.channelTemplate
            ).map { it.create(application) }
        )
    }
}

private fun initializeTerminalBinTools() {
    val ultraRam = PerformanceModeManager.isEnabled
    AsyncTask.THREAD_POOL_EXECUTOR.execute {
        if (!ultraRam) {
            try {
                me.zhanghai.android.files.terminal.TerminalExpansionManager.ensureBinTools(application)
            } catch (_: Throwable) {}
        }
        try {
            me.zhanghai.android.files.update.UpdateChecker.ensureAutoUpdateDefaultMigrated(application)
        } catch (_: Throwable) {}
        try {
            me.zhanghai.android.files.update.UpdateChecker.cleanupInstalledUpdateApks(application)
        } catch (_: Throwable) {}
        ExitReasonLogger.logNewExits(application)
    }
}

private fun initializeMemoryTrimAndWarmUp() {
    // Ultra RAM: whenever a screen opens, closes or gets covered, collect and trim once it has
    // settled so dead views and warmed caches don't stay resident (see scheduleHiddenUiRelease).
    application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityResumed(activity: Activity) {
            // Drops the start-up garbage of the screen that just opened.
            PerformanceModeManager.scheduleHiddenUiRelease()
        }
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {
            PerformanceModeManager.scheduleHiddenUiRelease()
        }
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {
            PerformanceModeManager.scheduleHiddenUiRelease()
        }
    })
    application.registerComponentCallbacks(object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            trimCoilMemory(level)
            if (PerformanceModeManager.isEnabled && level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                PerformanceModeManager.trimTransientCaches(aggressive = true)
                return
            }
            if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                NativeAppIconLoader.trim(0.5f)
                SettingsIconHelper.clearCaches()
            }
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                NativeAppIconLoader.clear()
                DirectorySnapshotCache.clear()
                AppMetadataCache.clear()
                InstalledAppsActivity.clearCache()
            }
        }

        override fun onConfigurationChanged(newConfig: Configuration) {}

        override fun onLowMemory() {
            clearCoilMemory()
            NativeAppIconLoader.clear()
            DirectorySnapshotCache.clear()
            AppMetadataCache.clear()
            SettingsIconHelper.clearCaches()
            InstalledAppsActivity.clearCache()
        }
    })
    if (!PerformanceModeManager.isEnabled) {
        AsyncTask.THREAD_POOL_EXECUTOR.execute {
            AppMetadataCache.warmUp(application)
        }
    }
}
