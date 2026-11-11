/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.File

data class PageDimension(val width: Int, val height: Int) {
    val aspectRatio: Float
        get() = if (height > 0) width.toFloat() / height.toFloat() else 0.707f // Default A4 ratio
}

class PdfDocumentEngine private constructor(
    private val pfd: ParcelFileDescriptor
) : Closeable {

    private val renderer = PdfRenderer(pfd)
    private val mutex = Mutex()
    @Volatile
    private var isClosed = false

    val pageCount: Int
        get() = if (isClosed) 0 else renderer.pageCount

    private val dimensionsCache = mutableMapOf<Int, PageDimension>()

    init {
        // Read page 0 dimension synchronously to avoid initial jump
        if (pageCount > 0) {
            try {
                val page = renderer.openPage(0)
                dimensionsCache[0] = PageDimension(page.width, page.height)
                page.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun getPageDimension(pageIndex: Int): PageDimension = mutex.withLock {
        if (isClosed || pageIndex !in 0 until pageCount) {
            return dimensionsCache[0] ?: PageDimension(595, 842)
        }
        dimensionsCache[pageIndex]?.let { return it }

        return try {
            val page = renderer.openPage(pageIndex)
            val dim = PageDimension(page.width, page.height)
            page.close()
            dimensionsCache[pageIndex] = dim
            dim
        } catch (e: Exception) {
            dimensionsCache[0] ?: PageDimension(595, 842)
        }
    }

    fun getCachedPageDimension(pageIndex: Int): PageDimension {
        return dimensionsCache[pageIndex] ?: dimensionsCache[0] ?: PageDimension(595, 842)
    }

    suspend fun renderPage(pageIndex: Int, renderScale: Float): Bitmap? = mutex.withLock {
        if (isClosed || pageIndex !in 0 until pageCount) return null
        try {
            val page = renderer.openPage(pageIndex)
            try {
                val naturalWidth = page.width
                val naturalHeight = page.height
                dimensionsCache[pageIndex] = PageDimension(naturalWidth, naturalHeight)

                var scale = renderScale.coerceIn(1.0f, 4.0f)
                val maxDim = 3840
                if (naturalWidth * scale > maxDim || naturalHeight * scale > maxDim) {
                    scale = minOf(maxDim.toFloat() / naturalWidth, maxDim.toFloat() / naturalHeight)
                }

                val targetWidth = (naturalWidth * scale).toInt().coerceAtLeast(1)
                val targetHeight = (naturalHeight * scale).toInt().coerceAtLeast(1)

                val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } finally {
                page.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        try {
            renderer.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            pfd.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        fun open(file: File): Result<PdfDocumentEngine> = runCatching {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfDocumentEngine(pfd)
        }

        fun open(pfd: ParcelFileDescriptor): Result<PdfDocumentEngine> = runCatching {
            PdfDocumentEngine(pfd)
        }
    }
}
