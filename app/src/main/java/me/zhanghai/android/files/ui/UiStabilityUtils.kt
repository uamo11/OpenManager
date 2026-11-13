/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.util.dpToDimensionPixelSize
import me.zhanghai.android.files.util.getColorByAttr

/**
 * Reusable UI & visual stability utilities:
 * - Lifecycle-safe dialog attachment & dismissal (prevents WindowLeaked / BadTokenException)
 * - Async progress dialog runner bound to the host Activity lifecycle
 * - Theme attribute color resolution & responsive card dialog window sizing
 * - Edge-to-edge WindowInsets padding without clobbering XML base padding
 */

val Activity.isUiAlive: Boolean
    get() = !isFinishing && !isDestroyed

val Fragment.isUiAlive: Boolean
    get() = isAdded && activity?.isUiAlive == true

fun Dialog.dismissSafely() {
    try {
        if (isShowing) {
            dismiss()
        }
    } catch (_: IllegalArgumentException) {
        // Window already detached from WindowManager during teardown
    } catch (_: IllegalStateException) {
        // Ignore teardown state exceptions
    }
}

/**
 * Automatically dismisses this dialog when [owner] hits ON_DESTROY, preventing WindowLeaked.
 */
fun <T : Dialog> T.bindToLifecycle(owner: LifecycleOwner): T = apply {
    val lifecycle = owner.lifecycle
    if (lifecycle.currentState == Lifecycle.State.DESTROYED) {
        dismissSafely()
        return@apply
    }
    val observer = object : DefaultLifecycleObserver {
        override fun onDestroy(lifecycleOwner: LifecycleOwner) {
            lifecycle.removeObserver(this)
            dismissSafely()
        }
    }
    lifecycle.addObserver(observer)
    setOnDismissListener {
        lifecycle.removeObserver(observer)
    }
}

fun FragmentActivity.showSafeDialog(
    build: MaterialAlertDialogBuilder.() -> Unit
): AlertDialog? {
    if (!isUiAlive) return null
    val dialog = MaterialAlertDialogBuilder(this).apply(build).create()
    dialog.bindToLifecycle(this)
    if (isUiAlive) {
        dialog.show()
    }
    return dialog
}

/**
 * Executes an asynchronous operation on the host [FragmentActivity]'s [lifecycleScope] with a
 * lifecycle-bound Material progress dialog that can never outlive or leak the Activity window.
 */
fun FragmentActivity.runWithProgressDialog(
    title: CharSequence,
    initialMessage: CharSequence,
    cancelable: Boolean = false,
    onCancelled: (() -> Unit)? = null,
    task: suspend (updateMessage: (CharSequence) -> Unit) -> Unit
): Job {
    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setMessage(initialMessage)
        .setCancelable(cancelable)
        .create()
        .bindToLifecycle(this)

    if (isUiAlive) {
        dialog.show()
    }

    val job = lifecycleScope.launch {
        try {
            task { msg ->
                launch(Dispatchers.Main.immediate) {
                    if (isUiAlive && dialog.isShowing) {
                        dialog.setMessage(msg)
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } finally {
            withContext(Dispatchers.Main.immediate) {
                dialog.dismissSafely()
            }
        }
    }

    if (cancelable) {
        dialog.setOnCancelListener {
            job.cancel()
            onCancelled?.invoke()
        }
    }
    return job
}

@ColorInt
fun Context.resolveThemeColor(@AttrRes attr: Int, @ColorInt fallback: Int): Int =
    try {
        val tv = TypedValue()
        if (theme.resolveAttribute(attr, tv, true)) {
            getColorByAttr(attr)
        } else {
            fallback
        }
    } catch (_: Throwable) {
        fallback
    }

fun Dialog.applyCardWindowSizing(
    widthFraction: Float = 0.92f,
    maxWidthDp: Int = 460
) {
    if (this is AppCompatDialog) {
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
    } else {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
    }
    window?.let { w ->
        w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val dm = context.resources.displayMetrics
        val targetWidth = (dm.widthPixels * widthFraction).toInt()
            .coerceAtMost(context.dpToDimensionPixelSize(maxWidthDp))
        w.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

fun View.applyEdgeToEdgeInsets(
    applyTop: Boolean = false,
    applyBottom: Boolean = false,
    applyHorizontal: Boolean = true,
    includeIme: Boolean = false
) {
    val baseLeft = paddingLeft
    val baseTop = paddingTop
    val baseRight = paddingRight
    val baseBottom = paddingBottom
    val typeMask = WindowInsetsCompat.Type.systemBars() or
        WindowInsetsCompat.Type.displayCutout() or
        (if (includeIme) WindowInsetsCompat.Type.ime() else 0)
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(typeMask)
        view.setPadding(
            if (applyHorizontal) baseLeft + bars.left else baseLeft,
            if (applyTop) baseTop + bars.top else baseTop,
            if (applyHorizontal) baseRight + bars.right else baseRight,
            if (applyBottom) baseBottom + bars.bottom else baseBottom
        )
        insets
    }
    if (isAttachedToWindow) {
        ViewCompat.requestApplyInsets(this)
    } else {
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                ViewCompat.requestApplyInsets(v)
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }
}
