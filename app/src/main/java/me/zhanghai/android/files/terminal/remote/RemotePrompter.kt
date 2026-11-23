/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.remote

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.CheckBox
import android.widget.EditText
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.zhanghai.android.files.R
import me.zhanghai.android.files.ui.isUiAlive
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Lets a transport running on a network thread ask the user something and wait for the answer.
 * Dialogs are shown on whichever terminal screen is currently attached; with no screen attached
 * the question is answered negatively, so a background reconnect can never trust a key on its own.
 */
object RemotePrompter {
    private const val TIMEOUT_SECONDS = 180L

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var hostRef: WeakReference<Activity>? = null

    fun attach(activity: Activity) {
        hostRef = WeakReference(activity)
    }

    fun detach(activity: Activity) {
        if (hostRef?.get() !== activity) return
        hostRef = null
        // The screen showing the questions closed: release waiting network threads right away.
        // (Other screens closing must not answer questions shown elsewhere.)
        pending.toList().forEach { it.run() }
    }

    private val pending = java.util.concurrent.CopyOnWriteArrayList<Runnable>()

    enum class HostKeyDecision { REJECT, ACCEPT_ONCE, ACCEPT_AND_SAVE }

    data class PasswordAnswer(val password: String, val remember: Boolean)

    fun confirmHostKey(
        endpoint: String,
        keyType: String,
        fingerprint: String,
        previousFingerprint: String?
    ): HostKeyDecision = ask(HostKeyDecision.REJECT) { activity, answer ->
        val changed = previousFingerprint != null
        val message = if (changed) {
            activity.getString(
                R.string.om_remote_hostkey_changed_message, endpoint, previousFingerprint, keyType,
                fingerprint
            )
        } else {
            activity.getString(R.string.om_remote_hostkey_unknown_message, endpoint, keyType, fingerprint)
        }
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(
                if (changed) R.string.om_remote_hostkey_changed_title
                else R.string.om_remote_hostkey_unknown_title
            )
            .setMessage(message)
            .setCancelable(false)
            .setNegativeButton(R.string.om_remote_hostkey_reject) { _, _ ->
                answer(HostKeyDecision.REJECT)
            }
        if (changed) {
            // A changed key is the classic man-in-the-middle signal: never offer a one-tap accept.
            builder.setNeutralButton(R.string.om_remote_hostkey_replace) { _, _ ->
                answer(HostKeyDecision.ACCEPT_AND_SAVE)
            }
        } else {
            builder
                .setPositiveButton(R.string.om_remote_hostkey_trust) { _, _ ->
                    answer(HostKeyDecision.ACCEPT_AND_SAVE)
                }
                .setNeutralButton(R.string.om_remote_hostkey_once) { _, _ ->
                    answer(HostKeyDecision.ACCEPT_ONCE)
                }
        }
        builder.show().setOnDismissListener { answer(HostKeyDecision.REJECT) }
    }

    fun askPassword(title: String, prompt: String, offerRemember: Boolean): PasswordAnswer? =
        ask<PasswordAnswer?>(null) { activity, answer ->
            val view = LayoutInflater.from(activity).inflate(R.layout.remote_password_dialog, null)
            val edit = view.findViewById<EditText>(R.id.passwordEdit)
            val remember = view.findViewById<CheckBox>(R.id.rememberCheck)
            view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.passwordLayout).hint =
                prompt
            remember.visibility = if (offerRemember) android.view.View.VISIBLE else android.view.View.GONE
            val dialog = MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setView(view)
                .setCancelable(false)
                .setPositiveButton(R.string.om_remote_connect) { _, _ ->
                    answer(PasswordAnswer(edit.text.toString(), remember.isChecked))
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> answer(null) }
                .create()
            dialog.setOnDismissListener { answer(null) }
            dialog.setOnShowListener {
                edit.requestFocus()
                dialog.window?.setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                )
            }
            dialog.show()
        }

    private fun <T> ask(fallback: T, show: (Activity, (T) -> Unit) -> Unit): T {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Prompts block; call from a network thread" }
        val result = AtomicReference<T>(fallback)
        val latch = CountDownLatch(1)
        val answered = java.util.concurrent.atomic.AtomicBoolean(false)
        val answer: (T) -> Unit = { value ->
            if (answered.compareAndSet(false, true)) {
                result.set(value)
                latch.countDown()
            }
        }
        val cancel = Runnable { answer(fallback) }
        pending.add(cancel)
        mainHandler.post {
            val activity = hostRef?.get()
            if (activity == null || !activity.isUiAlive) {
                answer(fallback)
                return@post
            }
            try {
                show(activity, answer)
            } catch (e: RuntimeException) {
                answer(fallback)
            }
        }
        try {
            return if (latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) result.get() else fallback
        } finally {
            pending.remove(cancel)
        }
    }
}
