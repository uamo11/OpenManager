/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.appmanager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.collection.LruCache
import me.zhanghai.android.files.settings.PerformanceModeManager

object NativeAppIconLoader {
    private val memoryCache = LruCache<String, Drawable>(PerformanceModeManager.NORMAL_BUDGET.nativeAppIconCacheEntries)

    @Volatile
    private var ultraLowRamMode = false

    fun setUltraLowRamMode(enabled: Boolean) {
        ultraLowRamMode = enabled
        val budget = PerformanceModeManager.budgetFor(enabled)
        memoryCache.resize(budget.nativeAppIconCacheEntries)
        if (enabled) {
            memoryCache.evictAll()
        }
    }

    fun getCached(packageName: String): Drawable? = memoryCache.get(packageName)

    fun put(packageName: String, drawable: Drawable) {
        memoryCache.put(packageName, drawable)
    }

    /**
     * The app's icon: from memory, else from a small copy on disk, else from the system (then saved
     * to disk). Asking the system is slow on some phones (Huawei/Honor theme every icon), and the
     * memory copies are dropped whenever Android is short of RAM, so the list used to rebuild every
     * icon from the system each time it opened. [version] (the app's last update time) renews the
     * disk copy when the app changes.
     */
    fun loadIcon(context: Context, packageName: String, version: Long = 0L): Drawable? {
        memoryCache.get(packageName)?.let { return it }
        val appContext = context.applicationContext
        val start = android.os.SystemClock.elapsedRealtime()
        val diskFile = diskFile(appContext, packageName, version)
        if (diskFile != null && diskFile.exists()) {
            val bitmap = runCatching { android.graphics.BitmapFactory.decodeFile(diskFile.path) }.getOrNull()
            if (bitmap != null) {
                val drawable = BitmapDrawable(appContext.resources, bitmap)
                memoryCache.put(packageName, drawable)
                logLoad("disk", start)
                return drawable
            }
        }
        val pm = appContext.packageManager
        val rawDrawable = try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationIcon(appInfo)
        } catch (e: Exception) {
            try {
                pm.getApplicationIcon(packageName)
            } catch (e2: Exception) {
                null
            }
        }
        val drawable = if (rawDrawable != null && ultraLowRamMode) {
            downscaleDrawableForLowRam(appContext, rawDrawable, PerformanceModeManager.ULTRA_BUDGET.nativeAppIconTargetPx)
        } else {
            rawDrawable
        }
        if (drawable != null) {
            memoryCache.put(packageName, drawable)
            if (diskFile != null) saveToDisk(appContext, drawable, diskFile, packageName)
        }
        logLoad("system", start)
        return drawable
    }

    private const val DISK_ICON_DP = 48

    /** cache/app_icons/<package>@<version>.webp; null without a version (cannot renew it). */
    private fun diskFile(context: Context, packageName: String, version: Long): java.io.File? {
        if (version <= 0L) return null
        val dir = java.io.File(context.cacheDir, "app_icons")
        return java.io.File(dir, "$packageName@$version.webp")
    }

    private fun saveToDisk(context: Context, drawable: Drawable, file: java.io.File, packageName: String) {
        runCatching {
            val px = (DISK_ICON_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(48)
            val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val oldBounds = Rect(drawable.bounds)
            drawable.setBounds(0, 0, px, px)
            drawable.draw(canvas)
            drawable.bounds = oldBounds
            val dir = file.parentFile ?: return
            dir.mkdirs()
            // Older copies of this app's icon go away.
            dir.listFiles { f -> f.name.startsWith("$packageName@") }?.forEach { it.delete() }
            val tmp = java.io.File(dir, file.name + ".tmp")
            tmp.outputStream().use {
                @Suppress("DEPRECATION")
                val format = if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                bitmap.compress(format, 90, it)
            }
            tmp.renameTo(file)
            bitmap.recycle()
        }
    }

    private var loggedLoads = 0

    private fun logLoad(source: String, start: Long) {
        if (!me.zhanghai.android.files.BuildConfig.DEBUG || loggedLoads >= 40) return
        loggedLoads++
        android.util.Log.d("OMAppIcons", "icon source=$source ms=${android.os.SystemClock.elapsedRealtime() - start}")
    }

    private fun downscaleDrawableForLowRam(
        context: Context,
        drawable: Drawable,
        targetPx: Int
    ): Drawable {
        if (targetPx <= 0) return drawable
        if (drawable is BitmapDrawable) {
            val bmp = drawable.bitmap
            if (bmp != null && bmp.width <= targetPx && bmp.height <= targetPx) {
                return drawable
            }
        }
        return try {
            val scaled = Bitmap.createBitmap(targetPx, targetPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(scaled)
            val oldBounds = Rect(drawable.bounds)
            drawable.setBounds(0, 0, targetPx, targetPx)
            drawable.draw(canvas)
            drawable.bounds = oldBounds
            BitmapDrawable(context.resources, scaled)
        } catch (_: OutOfMemoryError) {
            drawable
        }
    }

    fun trim(fraction: Float = 0.5f) {
        val target = (memoryCache.size() * (1f - fraction)).toInt().coerceAtLeast(0)
        memoryCache.trimToSize(target)
    }

    fun clear() {
        memoryCache.evictAll()
    }
}
