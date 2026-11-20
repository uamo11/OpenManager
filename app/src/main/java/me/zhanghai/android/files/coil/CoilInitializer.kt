/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import android.os.Build
import coil.Coil
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import coil.memory.MemoryCache
import coil.request.CachePolicy
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.settings.PerformanceModeManager

private val coilLock = Any()

@Volatile
private var activeImageLoader: ImageLoader? = null

fun initializeCoil() {
    reconfigureCoilForPerformanceMode(PerformanceModeManager.isEnabled)
}

fun reconfigureCoilForPerformanceMode(ultraLowRam: Boolean) {
    val previous = synchronized(coilLock) {
        activeImageLoader.also { activeImageLoader = null }
    }
    Coil.setImageLoader(ImageLoaderFactory { buildImageLoader(ultraLowRam) })
    if (previous != null) {
        try {
            previous.memoryCache?.clear()
        } catch (_: Throwable) {}
        try {
            previous.shutdown()
        } catch (_: Throwable) {}
    }
}

fun trimCoilMemory(level: Int) {
    activeImageLoader?.memoryCache?.trimMemory(level)
}

fun clearCoilMemory() {
    activeImageLoader?.memoryCache?.clear()
}

private fun buildImageLoader(ultraLowRam: Boolean): ImageLoader =
    synchronized(coilLock) {
        activeImageLoader?.let { return it }
        val budget = PerformanceModeManager.budgetFor(ultraLowRam)
        ImageLoader.Builder(application)
            .apply {
                if (ultraLowRam) {
                    memoryCache {
                        MemoryCache.Builder(application)
                            .maxSizeBytes(budget.coilMemoryCacheBytes)
                            .build()
                    }
                    diskCachePolicy(CachePolicy.DISABLED)
                    diskCache { null }
                    crossfade(false)
                } else {
                    memoryCache {
                        MemoryCache.Builder(application)
                            .maxSizePercent(0.15)
                            .build()
                    }
                }
            }
            .allowRgb565(true)
            .components {
                add(AppIconApplicationInfoKeyer())
                add(AppIconApplicationInfoFetcherFactory(application))
                add(AppIconPackageNameKeyer())
                add(AppIconPackageNameFetcherFactory(application))
                add(InstalledAppIconKeyer())
                add(InstalledAppIconFetcherFactory(application))
                add(PathAttributesKeyer())
                add(PathAttributesFetcher.Factory(application))
                add(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ImageDecoderDecoder.Factory()
                    } else {
                        GifDecoder.Factory()
                    }
                )
                add(SvgDecoder.Factory(false))
            }
            .build()
            .also { activeImageLoader = it }
    }
