/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.pdf

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * The continuous PDF list zooms as a whole (pinch, double tap), like a document and not page by
 * page: the list itself is scaled (pivot at its top-left corner) and moved with a translation.
 * Vertical movement stays the list's own scrolling, so long documents scroll as before, and
 * the translation covers the rest: sideways, and the edges of the first and last page.
 *
 * Touch events arrive in the list's own (unscaled) coordinates; the gestures are read in the
 * parent's, so that the zoom stays under the fingers.
 */
class PdfZoomRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    /** Off in paged mode, where each page zooms on its own. */
    var zoomEnabled = true
        set(value) {
            field = value
            if (!value) resetZoom(animate = false)
        }

    var onSingleTap: (() -> Unit)? = null

    /** Called when a zoom gesture or animation ends, with the final scale. */
    var onZoomSettled: ((Float) -> Unit)? = null

    var scale = 1f
        private set

    private var animator: ValueAnimator? = null
    private var isScaling = false
    private var lastParentX = 0f
    private var lastParentY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var velocityTracker: VelocityTracker? = null

    init {
        pivotX = 0f
        pivotY = 0f
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                if (!zoomEnabled) return false
                animator?.cancel()
                isScaling = true
                stopScroll()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomAround(scale * detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
                onZoomSettled?.invoke(scale)
            }
        }
    ).apply { isQuickScaleEnabled = false }

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!zoomEnabled) return false
                val target = if (scale > 1.05f) 1f else DOUBLE_TAP_SCALE
                animateZoom(target, e.x, e.y)
                return true
            }
        }
    )

    /** Back to the normal size (switching modes, a new document...). */
    fun resetZoom(animate: Boolean) {
        if (animate) {
            animateZoom(1f, 0f, 0f)
        } else {
            animator?.cancel()
            applyTransform(1f, 0f, 0f)
        }
    }

    private fun zoomAround(newScale: Float, focusX: Float, focusY: Float) {
        val target = newScale.coerceIn(MIN_SCALE, MAX_SCALE)
        // The point under the focus stays there.
        val localX = (focusX - translationX) / scale
        val localY = (focusY - translationY) / scale
        applyTransform(target, focusX - localX * target, focusY - localY * target)
    }

    private fun applyTransform(newScale: Float, tx: Float, ty: Float) {
        scale = newScale
        scaleX = newScale
        scaleY = newScale
        translationX = clampTranslation(tx, width)
        translationY = clampTranslation(ty, height)
    }

    /** The scaled list always covers its own area: no empty band at any edge. */
    private fun clampTranslation(value: Float, size: Int): Float {
        val min = size - size * scale
        return if (min >= 0f) 0f else value.coerceIn(min, 0f)
    }

    private fun animateZoom(target: Float, focusX: Float, focusY: Float) {
        animator?.cancel()
        val from = scale
        val localX = (focusX - translationX) / scale
        val localY = (focusY - translationY) / scale
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val s = from + (target - from) * (it.animatedValue as Float)
                applyTransform(s, focusX - localX * s, focusY - localY * s)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onZoomSettled?.invoke(scale)
                }
            })
            start()
        }
    }

    private fun flingSideways(velocityX: Float) {
        if (abs(velocityX) < MIN_FLING_VELOCITY || scale <= 1f) return
        animator?.cancel()
        val start = translationX
        val end = clampTranslation(start + velocityX * FLING_DISTANCE_FACTOR, width)
        if (end == start) return
        animator = ValueAnimator.ofFloat(start, end).apply {
            duration = 350
            interpolator = DecelerateInterpolator()
            addUpdateListener { translationX = it.animatedValue as Float }
            start()
        }
    }

    private val toParentMatrix = android.graphics.Matrix()

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!zoomEnabled) return super.dispatchTouchEvent(ev)
        // The gesture detectors work in the parent's coordinates: in the list's own ones the
        // distance between the fingers would change with the zoom being applied, one frame late.
        toParentMatrix.setScale(scale, scale)
        toParentMatrix.postTranslate(translationX, translationY)
        val parentEvent = MotionEvent.obtain(ev)
        parentEvent.transform(toParentMatrix)
        // Pan first: its ACTION_DOWN stops a running animation, and must not stop the zoom a
        // double tap starts on that same ACTION_DOWN.
        trackPan(parentEvent)
        scaleDetector.onTouchEvent(parentEvent)
        tapDetector.onTouchEvent(parentEvent)
        parentEvent.recycle()
        if (isScaling || ev.pointerCount > 1) {
            // Two fingers zoom: the list must not scroll or fling meanwhile.
            if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Sideways drags move the zoomed page; vertical drags scroll the list, and move the page
     * only when the list cannot scroll further (top of the first page, bottom of the last).
     * [ev] is in the parent's coordinates.
     */
    private fun trackPan(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                activePointerId = ev.getPointerId(0)
                lastParentX = ev.x
                lastParentY = ev.y
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                val index = ev.findPointerIndex(activePointerId)
                if (index < 0) return
                val x = ev.getX(index)
                val y = ev.getY(index)
                if (!isScaling && ev.pointerCount == 1 && scale > 1f) {
                    translationX = clampTranslation(translationX + (x - lastParentX), width)
                    val dy = y - lastParentY
                    val direction = if (dy > 0) -1 else 1
                    if (dy != 0f && !canScrollVertically(direction)) {
                        translationY = clampTranslation(translationY + dy, height)
                    }
                }
                lastParentX = x
                lastParentY = y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val upIndex = ev.actionIndex
                if (ev.getPointerId(upIndex) == activePointerId) {
                    val newIndex = if (upIndex == 0) 1 else 0
                    activePointerId = ev.getPointerId(newIndex)
                    lastParentX = ev.getX(newIndex)
                    lastParentY = ev.getY(newIndex)
                }
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker?.let {
                    it.addMovement(ev)
                    it.computeCurrentVelocity(1000)
                    if (!isScaling) flingSideways(it.getXVelocity(activePointerId))
                    it.recycle()
                }
                velocityTracker = null
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
            MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.recycle()
                velocityTracker = null
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Rotation: keep the zoom but stay inside the new bounds.
        translationX = clampTranslation(translationX, w)
        translationY = clampTranslation(translationY, h)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        velocityTracker?.recycle()
        velocityTracker = null
        super.onDetachedFromWindow()
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 4f
        const val DOUBLE_TAP_SCALE = 2.5f
        private const val MIN_FLING_VELOCITY = 600f
        private const val FLING_DISTANCE_FACTOR = 0.2f
    }
}
