/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import android.util.TypedValue
import android.widget.ImageView
import androidx.annotation.DrawableRes

/**
 * Raster resources (PNG/WebP/JPEG) are decoded at full resolution by [ImageView.setImageResource]:
 * a 512x512 `drawable-nodpi` logo costs 1 MiB of native heap and a 1024x1024 one 4 MiB, even
 * when shown in a 24dp view. This decodes them with the largest power-of-two subsampling that still
 * covers the view's fixed size, so they render identically for a fraction of the memory.
 * Vector and other non-raster drawables fall back to [ImageView.setImageResource].
 */
object SampledImageResources {
    private const val CACHE_BYTES = 512 * 1024

    private val cache = object : LruCache<Long, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.allocationByteCount
    }

    fun clear() {
        cache.evictAll()
    }

    /** Largest power-of-two sample size that keeps both dimensions >= the target. */
    fun computeInSampleSize(width: Int, height: Int, targetPx: Int): Int {
        if (targetPx <= 0 || width <= 0 || height <= 0) {
            return 1
        }
        var sampleSize = 1
        while (width / (sampleSize * 2) >= targetPx && height / (sampleSize * 2) >= targetPx) {
            sampleSize *= 2
        }
        return sampleSize
    }

    internal fun isRasterPath(path: CharSequence?): Boolean {
        if (path == null) {
            return false
        }
        val lower = path.toString().lowercase()
        return lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg")
    }

    fun load(imageView: ImageView, @DrawableRes resId: Int, targetPx: Int): Boolean {
        val resources = imageView.resources
        val value = TypedValue()
        try {
            resources.getValue(resId, value, true)
        } catch (_: Exception) {
            return false
        }
        if (!isRasterPath(value.string) || value.string.endsWith(".9.png")) {
            return false
        }
        val key = (resId.toLong() shl 20) or targetPx.toLong()
        val bitmap = cache.get(key) ?: run {
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                inScaled = false
            }
            BitmapFactory.decodeResource(resources, resId, bounds)
            val options = BitmapFactory.Options().apply {
                inScaled = false
                inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, targetPx)
            }
            val decoded = BitmapFactory.decodeResource(resources, resId, options) ?: return false
            cache.put(key, decoded)
            decoded
        }
        imageView.setImageDrawable(BitmapDrawable(resources, bitmap))
        return true
    }
}

/**
 * Like [ImageView.setImageResource], but raster resources are decoded at the view's fixed layout
 * size instead of their full resolution.
 */
fun ImageView.setImageResourceAtViewSize(@DrawableRes resId: Int) {
    val params = layoutParams
    val targetPx = when {
        params != null && params.width > 0 && params.height > 0 -> maxOf(params.width, params.height)
        width > 0 && height > 0 -> maxOf(width, height)
        else -> 0
    }
    if (targetPx <= 0 || !SampledImageResources.load(this, resId, targetPx)) {
        setImageResource(resId)
    }
}
