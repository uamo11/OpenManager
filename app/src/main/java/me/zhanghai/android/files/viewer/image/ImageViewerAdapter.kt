/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import android.graphics.BitmapFactory
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import coil.size.Precision
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.DefaultOnImageEventListener
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.coil.fadeIn
import me.zhanghai.android.files.databinding.ImageViewerItemBinding
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asMimeType
import me.zhanghai.android.files.file.asMimeTypeOrNull
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.provider.common.AndroidFileTypeDetector
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.ui.SimpleAdapter
import me.zhanghai.android.files.util.fadeInUnsafe
import me.zhanghai.android.files.util.fadeOutUnsafe
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.shortAnimTime
import me.zhanghai.android.files.viewer.image.ImageDisplayPlan.Mode
import kotlin.math.max

class ImageViewerAdapter(
    private val lifecycleOwner: LifecycleOwner,
    private val listener: (View) -> Unit
) : SimpleAdapter<Path, ImageViewerAdapter.ViewHolder>() {
    override val hasStableIds: Boolean
        get() = true

    override fun getItemId(position: Int): Long = getItem(position).hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ImageViewerItemBinding.inflate(parent.context.layoutInflater, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val path = getItem(position)
        val binding = holder.binding
        binding.image.setOnPhotoTapListener { view, _, _ -> listener(view) }
        binding.largeImage.setOnClickListener(listener)
        loadImage(holder, binding, path)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)

        holder.loadJob?.cancel()
        holder.loadJob = null
        val binding = holder.binding
        binding.image.dispose()
        binding.largeImage.recycle()
    }

    private fun loadImage(holder: ViewHolder, binding: ImageViewerItemBinding, path: Path) {
        holder.loadJob?.cancel()
        binding.progress.fadeInUnsafe(true)
        binding.errorText.fadeOutUnsafe()
        binding.image.isVisible = false
        binding.largeImage.isVisible = false
        holder.loadJob = lifecycleOwner.lifecycleScope.launch {
            val imageInfo = try {
                withContext(Dispatchers.IO) { path.loadImageInfo() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                showError(binding, e)
                return@launch
            }
            loadImageWithInfo(binding, path, imageInfo)
        }
    }

    private fun Path.loadImageInfo(): ImageInfo {
        val attributes = readAttributes(BasicFileAttributes::class.java)
        val mimeType = AndroidFileTypeDetector.getMimeType(this, attributes).asMimeType()
        val bitmapOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        newInputStream().use { BitmapFactory.decodeStream(it, null, bitmapOptions) }
        val actualMimeType = bitmapOptions.outMimeType?.asMimeTypeOrNull() ?: mimeType
        // The tile view cannot read EXIF through our file provider, so rotation is passed to it.
        val rotation = if (ImageDisplayPlan.isRegionDecodable(actualMimeType.value)) {
            try {
                newInputStream().use {
                    ImageDisplayPlan.exifRotation(
                        ExifInterface(it).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                        )
                    )
                }
            } catch (e: Exception) {
                0
            }
        } else {
            0
        }
        return ImageInfo(
            attributes, bitmapOptions.outWidth, bitmapOptions.outHeight, actualMimeType, rotation
        )
    }

    private fun loadImageWithInfo(
        binding: ImageViewerItemBinding,
        path: Path,
        imageInfo: ImageInfo
    ) {
        val metrics = binding.root.resources.displayMetrics
        val mode = ImageDisplayPlan.mode(
            imageInfo.width, imageInfo.height, imageInfo.mimeType.value,
            metrics.widthPixels, metrics.heightPixels
        )
        Log.i(
            TAG, "${imageInfo.width}x${imageInfo.height} ${imageInfo.mimeType.value} " +
                "rotation=${imageInfo.rotation} mode=$mode"
        )
        if (mode == Mode.BITMAP) {
            loadAsBitmap(binding, path, imageInfo)
        } else {
            loadAsTiles(binding, path, imageInfo)
        }
    }

    private fun loadAsBitmap(binding: ImageViewerItemBinding, path: Path, imageInfo: ImageInfo) {
        val metrics = binding.root.resources.displayMetrics
        val targetPx = ImageDisplayPlan.bitmapTargetPx(metrics.widthPixels, metrics.heightPixels)
        binding.largeImage.isVisible = false
        binding.image.apply {
            isVisible = true
            load(path to imageInfo.attributes) {
                // Never more than twice the screen: sharp when zoomed, never the full original.
                size(targetPx, targetPx)
                precision(Precision.INEXACT)
                fadeIn(context.shortAnimTime)
                listener(
                    onSuccess = { _, _ -> binding.progress.fadeOutUnsafe() },
                    onError = { _, result -> showError(binding, result.throwable) }
                )
            }
        }
    }

    private fun loadAsTiles(binding: ImageViewerItemBinding, path: Path, imageInfo: ImageInfo) {
        binding.largeImage.apply {
            setDoubleTapZoomDuration(300)
            orientation = imageInfo.rotation
            // Otherwise OnImageEventListener.onReady() is never called.
            isVisible = true
            alpha = 0f
            setOnImageEventListener(object : DefaultOnImageEventListener() {
                override fun onReady() {
                    setDoubleTapZoomScale(binding.largeImage.cropScale)
                    binding.progress.fadeOutUnsafe()
                    binding.largeImage.fadeInUnsafe(true)
                    Log.i(TAG, "tiles ready ${sWidth}x$sHeight orientation=$appliedOrientation")
                }

                override fun onImageLoadError(e: Exception) {
                    Log.w(TAG, "tile decoder failed, falling back to a bitmap", e)
                    binding.largeImage.recycle()
                    if (imageInfo.width.toLong() * imageInfo.height * 4L <=
                        ImageDisplayPlan.MAX_BITMAP_BYTES) {
                        loadAsBitmap(binding, path, imageInfo)
                    } else {
                        showError(binding, e)
                    }
                }
            })
            setImageRestoringSavedState(ImageSource.uri(path.fileProviderUri))
        }
    }

    private val SubsamplingScaleImageView.cropScale: Float
        get() {
            val viewWidth = (width - paddingLeft - paddingRight)
            val viewHeight = (height - paddingTop - paddingBottom)
            val orientation = appliedOrientation
            val rotated90Or270 = orientation == SubsamplingScaleImageView.ORIENTATION_90
                || orientation == SubsamplingScaleImageView.ORIENTATION_270
            val imageWidth = if (rotated90Or270) sHeight else sWidth
            val imageHeight = if (rotated90Or270) sWidth else sHeight
            return max(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
        }

    private fun showError(binding: ImageViewerItemBinding, throwable: Throwable) {
        binding.progress.fadeOutUnsafe()
        binding.errorText.text = throwable.toString()
        binding.errorText.fadeInUnsafe(true)
        binding.image.isVisible = false
        binding.largeImage.isVisible = false
    }

    companion object {
        private const val TAG = "OMImageViewer"
    }

    class ViewHolder(val binding: ImageViewerItemBinding) : RecyclerView.ViewHolder(binding.root) {
        var loadJob: Job? = null
    }

    private class ImageInfo(
        val attributes: BasicFileAttributes,
        val width: Int,
        val height: Int,
        val mimeType: MimeType,
        /** Clockwise degrees from EXIF, as [SubsamplingScaleImageView] orientation. */
        val rotation: Int
    )
}
