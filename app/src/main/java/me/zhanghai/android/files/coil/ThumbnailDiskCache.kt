/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import java.io.File
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Small disk cache of the thumbnails that are expensive to make (video frames, APK icons, PDF
 * first pages), for Ultra RAM mode: there the memory cache is tiny, so without this every scroll
 * back would extract the frame again. Each entry is a few KB of WebP at the requested size;
 * the oldest are dropped past [MAX_BYTES].
 */
object ThumbnailDiskCache {
    const val MAX_BYTES = 16L * 1024 * 1024
    private const val DIR = "om_thumbs"
    private const val TRIM_EVERY_PUTS = 25

    @Volatile
    private var putsSinceTrim = 0

    /** Same file, same modification time and size, same pixel size: same thumbnail. */
    fun key(location: String, lastModifiedMillis: Long, size: Long, px: Int): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$location|$lastModifiedMillis|$size|$px".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun dir(context: Context): File = File(context.cacheDir, DIR)

    fun get(context: Context, key: String): File? {
        val file = File(dir(context), "$key.webp")
        if (!file.isFile || file.length() == 0L) return null
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    /** Stores [drawable] scaled down to fit [px]; failures only mean no cache. */
    fun put(context: Context, key: String, drawable: Drawable, px: Int) {
        try {
            val bitmap = toBitmap(drawable, px) ?: return
            val dir = dir(context).apply { mkdirs() }
            val tmp = File(dir, "$key.tmp")
            tmp.outputStream().use { out ->
                @Suppress("DEPRECATION")
                val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    Bitmap.CompressFormat.WEBP
                }
                bitmap.compress(format, 80, out)
            }
            if (!tmp.renameTo(File(dir, "$key.webp"))) tmp.delete()
            if (bitmap !== (drawable as? BitmapDrawable)?.bitmap) bitmap.recycle()
            if (++putsSinceTrim >= TRIM_EVERY_PUTS) {
                putsSinceTrim = 0
                trim(dir)
            }
        } catch (_: Throwable) {
        }
    }

    private fun toBitmap(drawable: Drawable, px: Int): Bitmap? {
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: px
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: px
        val scale = minOf(1f, px.toFloat() / max(width, height))
        val targetW = (width * scale).roundToInt().coerceAtLeast(1)
        val targetH = (height * scale).roundToInt().coerceAtLeast(1)
        val source = (drawable as? BitmapDrawable)?.bitmap
        if (source != null && source.width == targetW && source.height == targetH) return source
        val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val oldBounds = drawable.copyBounds()
        drawable.setBounds(0, 0, targetW, targetH)
        drawable.draw(canvas)
        drawable.bounds = oldBounds
        return bitmap
    }

    /** Oldest first until the cache is back to three quarters of its budget. */
    internal fun trim(dir: File, maxBytes: Long = MAX_BYTES) {
        val files = dir.listFiles { f -> f.name.endsWith(".webp") }?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        files.sortBy { it.lastModified() }
        for (file in files) {
            if (total <= maxBytes * 3 / 4) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }
}
