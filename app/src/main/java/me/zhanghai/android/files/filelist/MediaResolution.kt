/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java8.nio.file.Path
import me.zhanghai.android.files.provider.linux.isLinuxPath
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * The resolution label of photos and videos in the gallery view (8K, 4K, 2K, 1080p, 720p).
 * Only the header is read (image bounds, video metadata), one file at a time in the background,
 * and the answer is remembered, so scrolling the gallery stays smooth.
 */
object MediaResolution {

    /** Label for [width] x [height], or null below 720p (nothing worth showing). */
    fun label(width: Int, height: Int): String? {
        if (width <= 0 || height <= 0) return null
        val long = max(width, height)
        val short = min(width, height)
        return when {
            long >= 7680 || short >= 4320 -> "8K"
            long >= 3840 || short >= 2160 -> "4K"
            long >= 2560 || short >= 1440 -> "2K"
            long >= 1920 || short >= 1080 -> "1080p"
            long >= 1280 || short >= 720 -> "720p"
            else -> null
        }
    }

    private const val NONE = ""
    // Lazy: [label] stays usable without Android (unit tests).
    private val cache by lazy { LruCache<String, String>(1024) }
    private val pending = HashSet<String>()
    private val executor by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "MediaResolution").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
    }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private fun key(path: Path, lastModified: Long): String = "$path#$lastModified"

    /** Cached label: a label, "" for none, or null when not known yet. */
    fun cached(path: Path, lastModified: Long): String? = cache.get(key(path, lastModified))

    /** Reads it in the background (local files only) and calls [onResult] on the main thread. */
    fun request(path: Path, lastModified: Long, isVideo: Boolean, onResult: (String?) -> Unit) {
        if (!path.isLinuxPath) return
        val key = key(path, lastModified)
        synchronized(pending) { if (!pending.add(key)) return }
        executor.execute {
            val label = try {
                val file = path.toFile()
                if (isVideo) {
                    MediaMetadataRetriever().let { retriever ->
                        try {
                            retriever.setDataSource(file.path)
                            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                            label(w, h)
                        } finally {
                            retriever.release()
                        }
                    }
                } else {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, options)
                    label(options.outWidth, options.outHeight)
                }
            } catch (_: Throwable) {
                null
            }
            cache.put(key, label ?: NONE)
            synchronized(pending) { pending.remove(key) }
            mainHandler.post { onResult(label) }
        }
    }
}
