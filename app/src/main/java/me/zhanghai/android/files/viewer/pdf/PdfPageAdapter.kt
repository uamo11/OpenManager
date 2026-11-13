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
            binding.photoView.setOnClickListener { onPageTapListener() }
            binding.pageContainer.setOnClickListener { onPageTapListener() }
        }
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_THEME)) {
            applyThemeToHolder(holder)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        applyThemeToHolder(holder)

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

        // Check in-memory LruCache first
        val cachedBitmap = lruCache.get(position)
        if (cachedBitmap != null && !cachedBitmap.isRecycled) {
            holder.renderJob?.cancel()
            holder.binding.photoView.setImageBitmap(cachedBitmap)
            applyThemeToHolder(holder)
            holder.binding.photoView.visibility = View.VISIBLE
            holder.binding.pageLoading.visibility = View.GONE
            holder.binding.pageErrorText.visibility = View.GONE
            return
        }

        // Needs asynchronous render
        holder.binding.photoView.setImageDrawable(null)
        holder.binding.photoView.visibility = View.INVISIBLE
        holder.binding.pageLoading.visibility = View.VISIBLE
        holder.binding.pageErrorText.visibility = View.GONE

        holder.renderJob?.cancel()
        holder.renderJob = coroutineScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                pdfEngine.renderPage(position, renderScale)
            }
            if (isActive) {
                if (bitmap != null) {
                    lruCache.put(position, bitmap)
                    if (holder.bindingAdapterPosition == position) {
                        holder.binding.photoView.setImageBitmap(bitmap)
                        applyThemeToHolder(holder)
                        holder.binding.photoView.visibility = View.VISIBLE
                        holder.binding.pageLoading.visibility = View.GONE
                        holder.binding.pageErrorText.visibility = View.GONE
                    }
                } else {
                    if (holder.bindingAdapterPosition == position) {
                        holder.binding.pageLoading.visibility = View.GONE
                        holder.binding.pageErrorText.text = holder.binding.pageErrorText.context.getString(
                            R.string.om_pdf_page_error_format, position + 1
                        )
                        holder.binding.pageErrorText.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        super.onViewRecycled(holder)
        holder.renderJob?.cancel()
        holder.renderJob = null
        holder.binding.photoView.setImageDrawable(null)
    }

    private fun applyThemeToHolder(holder: PageViewHolder) {
        val filter = currentTheme.colorFilter
        holder.binding.photoView.colorFilter = filter
        holder.binding.photoView.drawable?.colorFilter = filter
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
    }
}
