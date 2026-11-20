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

    fun loadIcon(context: Context, packageName: String): Drawable? {
        memoryCache.get(packageName)?.let { return it }
        val appContext = context.applicationContext
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
        }
        return drawable
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
