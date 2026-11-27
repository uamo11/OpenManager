/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import me.zhanghai.android.files.settings.Settings

/**
 * OM's vibrations, each behind a setting (users asked for more of them, as in MT, but optional):
 * touch feedback (selecting, long presses, copied text) and a buzz when a file task finishes.
 */
object Haptics {

    private const val TAG = "OMHaptics"

    fun touchEnabled(): Boolean =
        runCatching { Settings.VIBRATE_ON_TOUCH.valueCompat }.getOrDefault(true)

    fun jobEndEnabled(): Boolean =
        runCatching { Settings.VIBRATE_ON_JOB_FINISHED.valueCompat }.getOrDefault(false)

    /** A file added to or removed from the selection. */
    fun tick(view: View?) = perform(view, HapticFeedbackConstants.CLOCK_TICK)

    /** A button-like action: select all, copied a path. */
    fun tap(view: View?) = perform(view, HapticFeedbackConstants.VIRTUAL_KEY)

    /** A long press that did something (menu, swipe selection started). */
    fun longPress(view: View?) = perform(view, HapticFeedbackConstants.LONG_PRESS)

    private fun perform(view: View?, constant: Int) {
        if (view == null || !touchEnabled()) return
        view.performHapticFeedback(constant)
    }

    /** One short buzz when a task is done, two when it failed. */
    fun jobFinishedPattern(failed: Boolean): LongArray =
        if (failed) longArrayOf(0, 70, 90, 70) else longArrayOf(0, 45)

    fun jobFinished(context: Context, failed: Boolean) {
        if (!jobEndEnabled()) return
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator == null || !vibrator.hasVibrator()) {
                android.util.Log.i(TAG, "Task finished, no vibrator")
                return
            }
            val pattern = jobFinishedPattern(failed)
            android.util.Log.i(TAG, "Task finished buzz failed=$failed")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        }
    }
}
