/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.util.Log
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.appmanager.InstalledAppsActivity
import me.zhanghai.android.files.appmanager.NativeAppIconLoader
import me.zhanghai.android.files.coil.clearCoilMemory
import me.zhanghai.android.files.coil.reconfigureCoilForPerformanceMode
import me.zhanghai.android.files.filelist.AppMetadataCache
import me.zhanghai.android.files.filelist.DirectorySnapshotCache
import me.zhanghai.android.files.provider.linux.syscall.Syscall
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Central orchestrator for Ultra Performance & Low-RAM Mode (`Settings.VISUAL_PERFORMANCE_MODE`).
 * When disabled (`isEnabled == false`), every subsystem preserves 100% of OpenManager's normal
 * defaults and rich visual caches. When enabled (`isEnabled == true`), every heavy cache, pool,
 * decoder, transcript buffer, and transition animation drops to a strict minimal-RAM budget.
 */
object PerformanceModeManager {

    data class PerformanceBudget(
        val coilMemoryCacheBytes: Int,
        val coilUseDiskCache: Boolean,
        val coilMaxParallelism: Int,
        val nativeAppIconCacheEntries: Int,
        val nativeAppIconTargetPx: Int,
        val directorySnapshotMaxItems: Int,
        val fileListViewCacheSize: Int,
        val fileListPoolList: Int,
        val fileListPoolGrid: Int,
        val fileListDateCacheSize: Int,
        val fileListDescriptionCacheSize: Int,
        val appListViewCacheSize: Int,
        val appListPoolSize: Int,
        val runningAppsRefreshIntervalMs: Long,
        val settingsViewCacheSize: Int,
        val terminalTranscriptRows: Int,
        val editorMaxHighlightChars: Int,
        val editorLiveHighlightChars: Int,
        val editorMaxUndoHistory: Int,
        val editorUseCustomFont: Boolean,
        val enableUiAnimations: Boolean,
        val forceVectorFileIcons: Boolean
    )

    data class LiveHeapSnapshot(
        val javaUsedBytes: Long,
        val nativeAllocatedBytes: Long
    ) {
        val javaUsedMb: Float
            get() = javaUsedBytes.coerceAtLeast(0L) / BYTES_PER_MIB

        val nativeAllocatedMb: Float
            get() = nativeAllocatedBytes.coerceAtLeast(0L) / BYTES_PER_MIB

        val totalActiveMb: Float
            get() = (javaUsedBytes.coerceAtLeast(0L) + nativeAllocatedBytes.coerceAtLeast(0L)) / BYTES_PER_MIB

        fun formatCompact(): String =
            String.format(Locale.US, "%.1f MB", totalActiveMb)

        fun formatJavaCompact(): String =
            String.format(Locale.US, "%.1f MB", javaUsedMb)

        companion object {
            private const val BYTES_PER_MIB = 1024f * 1024f
        }
    }

    val NORMAL_BUDGET = PerformanceBudget(
        coilMemoryCacheBytes = 0, // 0 => default 15% of app memory class
        coilUseDiskCache = true,
        coilMaxParallelism = 4,
        nativeAppIconCacheEntries = 200,
        nativeAppIconTargetPx = 0, // 0 => keep original drawable dimensions
        directorySnapshotMaxItems = 12_000,
        fileListViewCacheSize = 28,
        fileListPoolList = 48,
        fileListPoolGrid = 24,
        fileListDateCacheSize = 512,
        fileListDescriptionCacheSize = 1024,
        appListViewCacheSize = 30,
        appListPoolSize = 24,
        runningAppsRefreshIntervalMs = 2500L,
        settingsViewCacheSize = 24,
        terminalTranscriptRows = 5000,
        editorMaxHighlightChars = 500_000,
        editorLiveHighlightChars = 300_000,
        editorMaxUndoHistory = 200,
        editorUseCustomFont = true,
        enableUiAnimations = true,
        forceVectorFileIcons = false
    )

    val ULTRA_BUDGET = PerformanceBudget(
        coilMemoryCacheBytes = 1 * 1024 * 1024, // Strict 1 MiB cap when any viewer needs Coil
        coilUseDiskCache = false,
        coilMaxParallelism = 2,
        nativeAppIconCacheEntries = 16,
        nativeAppIconTargetPx = 48,
        directorySnapshotMaxItems = 0,
        fileListViewCacheSize = 2,
        fileListPoolList = 10,
        fileListPoolGrid = 6,
        fileListDateCacheSize = 64,
        fileListDescriptionCacheSize = 96,
        appListViewCacheSize = 2,
        appListPoolSize = 8,
        runningAppsRefreshIntervalMs = 6000L,
        settingsViewCacheSize = 2,
        terminalTranscriptRows = 500,
        editorMaxHighlightChars = 64_000,
        editorLiveHighlightChars = 48_000,
        editorMaxUndoHistory = 16,
        editorUseCustomFont = false,
        enableUiAnimations = false,
        forceVectorFileIcons = true
    )

    @Volatile
    var isEnabled: Boolean = false
        private set

    fun budgetFor(enabled: Boolean): PerformanceBudget =
        if (enabled) ULTRA_BUDGET else NORMAL_BUDGET

    val currentBudget: PerformanceBudget
        get() = budgetFor(isEnabled)

    fun initialize(enabled: Boolean) {
        isEnabled = enabled
        DirectorySnapshotCache.setUltraLowRamMode(enabled)
        AppMetadataCache.setUltraLowRamMode(enabled)
        NativeAppIconLoader.setUltraLowRamMode(enabled)
        configureNativeAllocator(enabled)
    }

    fun applyMode(context: Context, enabled: Boolean, triggerTrim: Boolean = true) {
        isEnabled = enabled
        DirectorySnapshotCache.setUltraLowRamMode(enabled)
        AppMetadataCache.setUltraLowRamMode(enabled)
        NativeAppIconLoader.setUltraLowRamMode(enabled)
        reconfigureCoilForPerformanceMode(enabled)
        configureNativeAllocator(enabled)
        if (enabled && triggerTrim) {
            trimTransientCaches(aggressive = true)
            try {
                Runtime.getRuntime().gc()
            } catch (_: Throwable) {}
            purgeNativeHeapSafe()
        }
    }

    fun configureNativeAllocator(enabled: Boolean) {
        try {
            Syscall.configureNativeHeapDecay(enabled)
            if (enabled) {
                Syscall.purgeNativeHeap()
            }
        } catch (_: Throwable) {}
    }

    private val hiddenUiReleasePending = AtomicBoolean(false)

    /**
     * Called whenever a screen is opened, closed or covered while Ultra RAM mode is on. Once the
     * new screen has settled it:
     *  1. collects garbage, so Cleaners free the native RenderNodes/Paints/text layouts of views
     *     that are gone (they only count as "allocated" until then);
     *  2. trims the renderer's global caches (Skia glyph/path caches, unused GPU resources) the
     *     same way Android does when the app goes to background, so caches warmed by a previous
     *     screen don't stay resident;
     *  3. returns the freed dirty pages to the kernel.
     * Requests arriving while one is pending are coalesced.
     */
    fun scheduleHiddenUiRelease() {
        if (!isEnabled || !hiddenUiReleasePending.compareAndSet(false, true)) {
            return
        }
        mainHandler.postDelayed({
            Thread({
                try {
                    Runtime.getRuntime().gc()
                    System.runFinalization()
                    // Cleaners run on the ReferenceQueueDaemon shortly after the GC.
                    Thread.sleep(HIDDEN_UI_CLEANER_WAIT_MS)
                    mainHandler.post { trimRendererCaches() }
                    // The renderer frees its caches asynchronously on the RenderThread.
                    Thread.sleep(HIDDEN_UI_RENDERER_WAIT_MS)
                    purgeNativeHeapSafe()
                    if (BuildConfig.DEBUG) {
                        val heap = readLiveHeapSnapshot()
                        Log.i(
                            LOG_TAG, "UI release done: java=${heap.formatJavaCompact()} " +
                                "java+native=${heap.formatCompact()}"
                        )
                    }
                } catch (_: Throwable) {
                } finally {
                    hiddenUiReleasePending.set(false)
                }
            }, "UltraRamRelease").start()
        }, HIDDEN_UI_RELEASE_DELAY_MS)
    }

    private fun trimRendererCaches() {
        if (!isEnabled) {
            return
        }
        try {
            // Hidden API (checks are disabled by HiddenApi at startup); this is exactly what
            // ActivityThread does on TRIM_MEMORY_UI_HIDDEN. It never destroys the EGL context at
            // this level, so visible windows simply re-warm what they actually draw.
            val globalClass = Class.forName("android.view.WindowManagerGlobal")
            val global = globalClass.getMethod("getInstance").invoke(null)
            globalClass.getMethod("trimMemory", Int::class.javaPrimitiveType)
                .invoke(global, ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        } catch (_: Throwable) {}
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private const val LOG_TAG = "UltraRam"
    private const val HIDDEN_UI_RELEASE_DELAY_MS = 1500L
    private const val HIDDEN_UI_CLEANER_WAIT_MS = 300L
    private const val HIDDEN_UI_RENDERER_WAIT_MS = 400L

    fun purgeNativeHeapSafe() {
        try {
            Syscall.purgeNativeHeap()
        } catch (_: Throwable) {}
    }

    fun trimTransientCaches(aggressive: Boolean = false) {
        clearCoilMemory()
        NativeAppIconLoader.clear()
        DirectorySnapshotCache.clear()
        SettingsIconHelper.clearCaches()
        if (aggressive) {
            AppMetadataCache.clear()
            InstalledAppsActivity.clearCache()
        }
        purgeNativeHeapSafe()
    }

    fun setEnabledForTesting(enabled: Boolean) {
        isEnabled = enabled
    }

    fun readLiveHeapSnapshot(): LiveHeapSnapshot {
        val runtime = Runtime.getRuntime()
        val javaUsed = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
        val nativeAlloc = try {
            Debug.getNativeHeapAllocatedSize().coerceAtLeast(0L)
        } catch (_: Throwable) {
            0L
        }
        return LiveHeapSnapshot(
            javaUsedBytes = javaUsed,
            nativeAllocatedBytes = nativeAlloc
        )
    }

    fun formatStatusBadge(
        isUltraPerf: Boolean,
        thumbMode: String,
        activeMb: Float,
        standardLabel: String,
        smoothLabel: String,
        ultraLabel: String,
        turboFormat: String
    ): String = when {
        isUltraPerf -> {
            val formattedMb = String.format(Locale.US, "%.1f MB", activeMb.coerceAtLeast(0f))
            try {
                String.format(Locale.getDefault(), turboFormat, formattedMb)
            } catch (_: Throwable) {
                "Ultra RAM · $formattedMb"
            }
        }
        thumbMode == "2" -> ultraLabel
        thumbMode == "1" -> smoothLabel
        else -> standardLabel
    }
}
