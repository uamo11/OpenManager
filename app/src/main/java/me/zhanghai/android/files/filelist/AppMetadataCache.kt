/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap

object AppMetadataCache {
    private const val MAX_CACHE_ENTRIES = 2000

    private val labelCache = ConcurrentHashMap<String, String>(256)
    private val installedCache = ConcurrentHashMap<String, Boolean>(256)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val lookupExecutor =
        me.zhanghai.android.files.util.IdleThreadPools.fixed("OMAppMeta", 2)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var isWarmedUp = false

    @Volatile
    private var ultraLowRamMode = false

    fun setUltraLowRamMode(enabled: Boolean) {
        ultraLowRamMode = enabled
        if (enabled) {
            clear()
        }
    }

    fun warmUp(context: Context) {
        if (ultraLowRamMode || isWarmedUp) return
        try {
            val pm = context.applicationContext.packageManager
            val apps = pm.getInstalledApplications(0)
            for (info in apps) {
                val pkg = info.packageName ?: continue
                val label = try {
                    pm.getApplicationLabel(info).toString()
                } catch (_: Exception) {
                    pkg
                }
                labelCache[pkg] = label
                installedCache[pkg] = true
            }
            isWarmedUp = true
        } catch (_: Throwable) {}
    }

    fun getCachedAppLabel(packageName: String): String? {
        if (packageName.isEmpty()) return null
        val cached = labelCache[packageName] ?: return null
        return cached.ifEmpty { null }
    }

    fun hasLabelEntry(packageName: String): Boolean =
        packageName.isNotEmpty() && labelCache.containsKey(packageName)

    fun prefetchAppLabelAsync(
        context: Context,
        packageName: String,
        onResolved: (String) -> Unit
    ) {
        if (ultraLowRamMode || packageName.isEmpty() || labelCache.containsKey(packageName)) return
        if (!inFlight.add(packageName)) return
        val appContext = context.applicationContext
        lookupExecutor.execute {
            try {
                if (labelCache.size > MAX_CACHE_ENTRIES) {
                    labelCache.clear()
                    installedCache.clear()
                }
                val pm = appContext.packageManager
                val info = pm.getApplicationInfo(packageName, 0)
                val label = pm.getApplicationLabel(info).toString()
                labelCache[packageName] = label
                installedCache[packageName] = true
                if (label.isNotEmpty()) {
                    mainHandler.post { onResolved(label) }
                }
            } catch (_: Exception) {
                labelCache[packageName] = ""
                installedCache[packageName] = false
            } finally {
                inFlight.remove(packageName)
            }
        }
    }

    fun getAppLabel(context: Context, packageName: String): String? {
        if (packageName.isEmpty()) return null
        val cached = labelCache[packageName]
        if (cached != null) return cached.ifEmpty { null }
        return try {
            if (labelCache.size > MAX_CACHE_ENTRIES) {
                labelCache.clear()
                installedCache.clear()
            }
            val pm = context.applicationContext.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            val label = pm.getApplicationLabel(info).toString()
            labelCache[packageName] = label
            installedCache[packageName] = true
            label
        } catch (_: Exception) {
            labelCache[packageName] = ""
            installedCache[packageName] = false
            null
        }
    }

    fun isInstalled(context: Context, packageName: String): Boolean {
        if (packageName.isEmpty()) return false
        val cached = installedCache[packageName]
        if (cached != null) return cached
        return try {
            context.applicationContext.packageManager.getPackageInfo(packageName, 0)
            installedCache[packageName] = true
            true
        } catch (_: Exception) {
            installedCache[packageName] = false
            false
        }
    }

    fun clear() {
        labelCache.clear()
        installedCache.clear()
        inFlight.clear()
        isWarmedUp = false
    }
}
