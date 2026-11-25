/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.pdf

import android.graphics.Bitmap
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.ItemPdfPageBinding

class PdfPageAdapter(
    private val pdfEngine: PdfDocumentEngine,
    private val coroutineScope: CoroutineScope,
    private val renderScale: Float,
    private val onPageTapListener: () -> Unit
) : RecyclerView.Adapter<PdfPageAdapter.PageViewHolder>() {

    var currentTheme: PdfReadingTheme = PdfReadingTheme.DAY
        private set

    var isPagedMode: Boolean = false
        private set

    var containerWidth: Int = 0

    private val lruCache: LruCache<Int, Bitmap>

    /**
     * Sharpness level of the pages: 1 at normal size, 2-3 while the document is zoomed in, so
     * zoomed text is rendered for the zoom instead of being an enlarged blurry bitmap.
     */
    var qualityTier: Int = 1
        private set

    init {
        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSizeKb = (maxMemoryKb / 6).coerceIn(32 * 1024, 128 * 1024)
        lruCache = object : LruCache<Int, Bitmap>(cacheSizeKb) {
            override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    override fun getItemCount(): Int = pdfEngine.pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val binding = ItemPdfPageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PageViewHolder(binding).apply {
            binding.photoView.setOnPhotoTapListener { _, _, _ -> onPageTapListener() }
            binding.photoView.setOnViewTapListener { _, _, _ -> onPageTapListener() }
        }
    }

    /**
     * Paged mode: each page zooms on its own (and taps toggle the bars there). Continuous mode:
     * the whole list zooms ([PdfZoomRecyclerView]) and handles taps, so pages stay passive.
     */
    private fun applyInteraction(holder: PageViewHolder) {
        val photoView = holder.binding.photoView
        if (isPagedMode) {
            photoView.isZoomable = true
            photoView.setOnClickListener { onPageTapListener() }
            holder.binding.pageContainer.setOnClickListener { onPageTapListener() }
            holder.binding.pageImage.visibility = View.GONE
            holder.binding.pageImage.setImageDrawable(null)
        } else {
            // PhotoView with its own zoom turned off stops fitting the picture to the page frame
            // and draws the (larger than the screen) render at full size: half a page. Continuous
            // mode uses a plain picture that always fills its frame; the list does the zoom.
            photoView.setOnClickListener(null)
            photoView.isClickable = false
            photoView.visibility = View.GONE
            photoView.setImageDrawable(null)
            holder.binding.pageContainer.setOnClickListener(null)
            holder.binding.pageContainer.isClickable = false
        }
    }

    /** The view that shows the page in the current mode. */
    private fun pageView(holder: PageViewHolder): ImageView =
        if (isPagedMode) holder.binding.photoView else holder.binding.pageImage

    override fun onBindViewHolder(holder: PageViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_THEME || it == PAYLOAD_QUALITY }) {
            if (payloads.contains(PAYLOAD_THEME)) applyThemeToHolder(holder)
            if (payloads.contains(PAYLOAD_QUALITY)) renderInto(holder, position, keepCurrent = true)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        applyThemeToHolder(holder)
        applyInteraction(holder)

        val context = holder.itemView.context
        val density = context.resources.displayMetrics.density

        if (isPagedMode) {
            holder.itemView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
                leftMargin = 0
                rightMargin = 0
                topMargin = 0
                bottomMargin = 0
            }
            holder.binding.photoView.updateLayoutParams<ViewGroup.LayoutParams> {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        } else {
            val horizontalMarginPx = (4 * density).toInt()
            val verticalMarginPx = (6 * density).toInt()

            holder.itemView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                leftMargin = horizontalMarginPx
                rightMargin = horizontalMarginPx
                topMargin = verticalMarginPx
                bottomMargin = verticalMarginPx
            }

            val parentW = if (containerWidth > 0) containerWidth else context.resources.displayMetrics.widthPixels
            val availableWidth = (parentW - horizontalMarginPx * 2).coerceAtLeast(100)
            val dim = pdfEngine.getCachedPageDimension(position)
            val calculatedH = (availableWidth / dim.aspectRatio).toInt().coerceAtLeast(100)

            holder.itemView.updateLayoutParams<ViewGroup.LayoutParams> {
                height = calculatedH
            }
            holder.binding.photoView.updateLayoutParams<ViewGroup.LayoutParams> {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }

        renderInto(holder, position, keepCurrent = false)
    }

    /**
     * Shows page [position] at the current [qualityTier]: from the cache, or rendered in the
     * background. With [keepCurrent] (a sharper version of a page already on screen) the old
     * image stays until the new one is ready, so zooming never flashes a spinner.
     */
    private fun renderInto(holder: PageViewHolder, position: Int, keepCurrent: Boolean) {
        val tier = qualityTier
        val cachedBitmap = lruCache.get(cacheKey(position, tier))
        if (cachedBitmap != null && !cachedBitmap.isRecycled) {
            holder.renderJob?.cancel()
            showBitmap(holder, cachedBitmap)
            return
        }
        if (!keepCurrent) {
            // A lower-quality version is better than a spinner while the sharp one renders.
            val fallback = (tier - 1 downTo 1).firstNotNullOfOrNull { t ->
                lruCache.get(cacheKey(position, t))?.takeIf { !it.isRecycled }
            }
            if (fallback != null) {
                showBitmap(holder, fallback)
            } else {
                pageView(holder).setImageDrawable(null)
                pageView(holder).visibility = View.INVISIBLE
                holder.binding.pageLoading.visibility = View.VISIBLE
                holder.binding.pageErrorText.visibility = View.GONE
            }
        }

        holder.renderJob?.cancel()
        holder.renderJob = coroutineScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                pdfEngine.renderPage(position, scaleFor(position, tier))
            }
            if (isActive) {
                if (bitmap != null) {
                    if (me.zhanghai.android.files.BuildConfig.DEBUG && tier > 1) {
                        android.util.Log.i("OMPdf", "render page=$position tier=$tier ${bitmap.width}x${bitmap.height}")
                    }
                    lruCache.put(cacheKey(position, tier), bitmap)
                    if (holder.bindingAdapterPosition == position) {
                        showBitmap(holder, bitmap)
                    }
                } else if (holder.bindingAdapterPosition == position && !keepCurrent) {
                    holder.binding.pageLoading.visibility = View.GONE
                    holder.binding.pageErrorText.text = holder.binding.pageErrorText.context.getString(
                        R.string.om_pdf_page_error_format, position + 1
                    )
                    holder.binding.pageErrorText.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun showBitmap(holder: PageViewHolder, bitmap: Bitmap) {
        pageView(holder).setImageBitmap(bitmap)
        if (me.zhanghai.android.files.BuildConfig.DEBUG) {
            // Emulator check: the page must be drawn across its whole frame, not at render size.
            val view = pageView(holder)
            view.post {
                val drawn = view.drawable?.bounds?.width() ?: -1
                android.util.Log.i(
                    "OMPdf", "shown page=${holder.bindingAdapterPosition} frame=${view.width}x${view.height} drawn=$drawn " +
                        "bitmap=${bitmap.width} paged=$isPagedMode"
                )
            }
        }
        applyThemeToHolder(holder)
        pageView(holder).visibility = View.VISIBLE
        holder.binding.pageLoading.visibility = View.GONE
        holder.binding.pageErrorText.visibility = View.GONE
    }

    /** Render scale (pixels per PDF point) for a page at a quality tier. */
    private fun scaleFor(position: Int, tier: Int): Float {
        if (tier <= 1) return renderScale
        val pageWidth = pdfEngine.getCachedPageDimension(position).width.coerceAtLeast(1)
        val viewWidth = containerWidth.takeIf { it > 0 } ?: return renderScale
        return maxOf(renderScale, viewWidth * tier / pageWidth.toFloat())
    }

    private fun cacheKey(position: Int, tier: Int): Int = position * 8 + tier

    /**
     * After a zoom: the pages on screen ([first]..[last]) are rendered again for [zoom] once it
     * needs more detail. Normal size drops the large bitmaps.
     */
    fun onZoomChanged(zoom: Float, first: Int, last: Int) {
        val tier = PdfZoomQuality.tierFor(zoom, maxTier)
        if (tier == qualityTier) return
        qualityTier = tier
        if (tier == 1) {
            // Free the sharp versions: at normal size they are just memory.
            lruCache.snapshot().keys.filter { it % 8 > 1 }.forEach { lruCache.remove(it) }
        }
        if (first >= 0 && last >= first) {
            notifyItemRangeChanged(first, last - first + 1, PAYLOAD_QUALITY)
        }
    }

    /** Older Android keeps bitmaps in the small Java heap: less detail there, never a crash. */
    private val maxTier: Int = PdfZoomQuality.maxTier(
        android.os.Build.VERSION.SDK_INT, Runtime.getRuntime().maxMemory()
    )

    override fun onViewRecycled(holder: PageViewHolder) {
        super.onViewRecycled(holder)
        holder.renderJob?.cancel()
        holder.renderJob = null
        holder.binding.photoView.setImageDrawable(null)
        holder.binding.pageImage.setImageDrawable(null)
    }

    private fun applyThemeToHolder(holder: PageViewHolder) {
        val filter = currentTheme.colorFilter
        for (view in arrayOf<ImageView>(holder.binding.photoView, holder.binding.pageImage)) {
            view.colorFilter = filter
            view.drawable?.colorFilter = filter
        }
        holder.binding.pageContainer.setBackgroundColor(currentTheme.pageContainerBackgroundColor)
    }

    fun setTheme(theme: PdfReadingTheme) {
        if (currentTheme != theme) {
            currentTheme = theme
            notifyItemRangeChanged(0, itemCount, PAYLOAD_THEME)
        }
    }

    fun setPagedMode(paged: Boolean) {
        if (isPagedMode != paged) {
            isPagedMode = paged
            notifyDataSetChanged()
        }
    }

    fun clearCache() {
        lruCache.evictAll()
    }

    class PageViewHolder(val binding: ItemPdfPageBinding) : RecyclerView.ViewHolder(binding.root) {
        var renderJob: Job? = null
    }

    companion object {
        private const val PAYLOAD_THEME = "PAYLOAD_THEME"
        private const val PAYLOAD_QUALITY = "PAYLOAD_QUALITY"
    }
}
