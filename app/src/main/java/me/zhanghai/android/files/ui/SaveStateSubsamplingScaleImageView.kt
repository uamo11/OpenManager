/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.content.Context
import android.os.Parcelable
import android.util.AttributeSet
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.ImageViewState
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.util.ParcelableState

class SaveStateSubsamplingScaleImageView : SubsamplingScaleImageView {
    private var pendingState: ImageViewState? = null

    constructor(context: Context) : super(context)

    constructor(context: Context, attr: AttributeSet?) : super(context, attr)

    /** Draw a checkerboard under the image (it has transparent pixels). */
    var transparencyBackground: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val checkerRect = android.graphics.Rect()
    private val corner = android.graphics.PointF()
    private val otherCorner = android.graphics.PointF()

    override fun onDraw(canvas: android.graphics.Canvas) {
        if (transparencyBackground && isReady && sWidth > 0 && sHeight > 0) {
            sourceToViewCoord(0f, 0f, corner)
            sourceToViewCoord(sWidth.toFloat(), sHeight.toFloat(), otherCorner)
            checkerRect.set(
                minOf(corner.x, otherCorner.x).toInt(), minOf(corner.y, otherCorner.y).toInt(),
                maxOf(corner.x, otherCorner.x).toInt(), maxOf(corner.y, otherCorner.y).toInt()
            )
            me.zhanghai.android.files.ui.CheckerboardDrawable.drawChecker(
                canvas, checkerRect, 8 * resources.displayMetrics.density
            )
        }
        super.onDraw(canvas)
    }

    fun setImageRestoringSavedState(imageSource: ImageSource) {
        setImage(imageSource, pendingState)
        pendingState = null
    }

    override fun onSaveInstanceState(): Parcelable = State(super.onSaveInstanceState(), state)

    override fun onRestoreInstanceState(state: Parcelable) {
        state as State
        super.onRestoreInstanceState(state.superState)
        pendingState = state.state
    }

    @Parcelize
    private class State(val superState: Parcelable?, val state: ImageViewState?) : ParcelableState
}
