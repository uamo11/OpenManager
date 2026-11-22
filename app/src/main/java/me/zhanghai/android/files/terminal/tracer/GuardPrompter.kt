/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.tracer

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.NotificationIds
import me.zhanghai.android.files.provider.root.CommandGuard
import me.zhanghai.android.files.terminal.TerminalActivity
import me.zhanghai.android.files.ui.isUiAlive
import me.zhanghai.android.files.util.NotificationChannelTemplate
import me.zhanghai.android.files.util.NotificationTemplate
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

val commandGuardNotificationTemplate =
    NotificationTemplate(
        NotificationChannelTemplate(
            "command_guard",
            R.string.om_guard_channel_name,
            NotificationManagerCompat.IMPORTANCE_HIGH,
            descriptionRes = R.string.om_guard_channel_description,
            showBadge = true
        ),
        colorRes = R.color.color_primary,
        smallIcon = R.drawable.ic_terminal_notification_24dp,
        autoCancel = true,
        category = NotificationCompat.CATEGORY_ALARM,
        priority = NotificationCompat.PRIORITY_HIGH
    )

/**
 * Asks the user whether a guarded command may run. Shows a dialog on the visible terminal; with
 * no terminal on screen it posts a notification (Allow / Block / open terminal) and waits.
 * Unanswered requests are blocked after [TIMEOUT_SECONDS].
 */
object GuardPrompter {
    const val TIMEOUT_SECONDS = 90L
    private const val ACTION_ANSWER = "me.zhanghai.android.files.action.COMMAND_GUARD_ANSWER"
    private const val EXTRA_ALLOW = "allow"

    enum class Answer { ALLOW, ALLOW_SESSION, BLOCK, NO_SCREEN }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var hostRef: WeakReference<TerminalActivity>? = null

    private class Pending(
        val commandLine: String,
        val cwd: String,
        val analysis: CommandGuard.Analysis
    ) {
        val result = AtomicReference<Answer?>(null)
        val latch = CountDownLatch(1)
        @Volatile var dialog: AlertDialog? = null

        fun complete(answer: Answer) {
            if (result.compareAndSet(null, answer)) latch.countDown()
        }
    }

    @Volatile
    private var pending: Pending? = null

    /** Called from TerminalActivity.onResume: shows a request that was waiting for a screen. */
    fun attach(activity: TerminalActivity) {
        hostRef = WeakReference(activity)
        pending?.let { request -> mainHandler.post { showDialog(activity, request) } }
    }

    fun detach(activity: TerminalActivity) {
        if (hostRef?.get() === activity) hostRef = null
        pending?.dialog?.let { dialog ->
            // The dialog dies with the screen; the request keeps waiting for the next one.
            mainHandler.post { runCatching { dialog.dismiss() } }
        }
    }

    /** Blocks the calling (guard) thread until the user decides. */
    fun ask(context: Context, commandLine: String, cwd: String, analysis: CommandGuard.Analysis): Answer {
        val request = Pending(commandLine, cwd, analysis)
        pending = request
        mainHandler.post {
            val activity = hostRef?.get()
            if (activity != null && activity.isUiAlive) {
                showDialog(activity, request)
            } else {
                postNotification(context, request)
            }
        }
        val answered = request.latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        pending = null
        mainHandler.post {
            runCatching { request.dialog?.dismiss() }
            NotificationManagerCompat.from(context).cancel(NotificationIds.COMMAND_GUARD)
        }
        return if (answered) request.result.get() ?: Answer.BLOCK else Answer.NO_SCREEN
    }

    private fun describeTargets(context: Context, analysis: CommandGuard.Analysis): String {
        val lines = analysis.targets.take(6).map { target ->
            val zone = context.getString(zoneLabel(target.zone))
            "• ${target.path}  ($zone)"
        }.toMutableList()
        if (analysis.targets.size > 6) {
            lines += context.getString(R.string.om_guard_more_targets, analysis.targets.size - 6)
        }
        return lines.joinToString("\n")
    }

    fun zoneLabel(zone: CommandGuard.Zone): Int = when (zone) {
        CommandGuard.Zone.ROOT -> R.string.om_guard_zone_root
        CommandGuard.Zone.ROOT_CHILD -> R.string.om_guard_zone_root_child
        CommandGuard.Zone.SYSTEM -> R.string.om_guard_zone_system
        CommandGuard.Zone.BOOT_CRITICAL -> R.string.om_guard_zone_boot
        CommandGuard.Zone.USER_STORAGE_ROOT -> R.string.om_guard_zone_storage_root
        CommandGuard.Zone.USER -> R.string.om_guard_zone_user
        CommandGuard.Zone.APP_PRIVATE -> R.string.om_guard_zone_app
        CommandGuard.Zone.OTHER -> R.string.om_guard_zone_other
    }

    private fun showDialog(activity: Activity, request: Pending) {
        if (request.result.get() != null || !activity.isUiAlive || request.dialog?.isShowing == true) return
        NotificationManagerCompat.from(activity).cancel(NotificationIds.COMMAND_GUARD)
        val message = activity.getString(
            R.string.om_guard_ask_message,
            request.commandLine,
            request.cwd,
            describeTargets(activity, request.analysis)
        ) + if (request.analysis.recursive) "\n\n" + activity.getString(R.string.om_guard_ask_recursive) else ""
        val dialog = MaterialAlertDialogBuilder(activity)
            .setIcon(R.drawable.delete_icon_white_24dp)
            .setTitle(R.string.om_guard_ask_title)
            .setMessage(message)
            .setCancelable(false)
            .setNegativeButton(R.string.om_guard_block) { _, _ -> request.complete(Answer.BLOCK) }
            .setPositiveButton(R.string.om_guard_allow) { _, _ -> request.complete(Answer.ALLOW) }
            .setNeutralButton(R.string.om_guard_allow_session) { _, _ -> request.complete(Answer.ALLOW_SESSION) }
            .create()
        request.dialog = dialog
        dialog.show()
    }

    private fun postNotification(context: Context, request: Pending) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        fun answerIntent(allow: Boolean, code: Int) = PendingIntent.getBroadcast(
            context, code,
            Intent(context, AnswerReceiver::class.java).setAction(ACTION_ANSWER).putExtra(EXTRA_ALLOW, allow),
            flags
        )
        val open = PendingIntent.getActivity(
            context, 40,
            Intent(context, TerminalActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            flags
        )
        val notification = commandGuardNotificationTemplate.createBuilder(context)
            .setContentTitle(context.getString(R.string.om_guard_ask_title))
            .setContentText(request.commandLine)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    request.commandLine + "\n" + describeTargets(context, request.analysis)
                )
            )
            .setContentIntent(open)
            .addAction(R.drawable.close_icon_white_24dp, context.getString(R.string.om_guard_block), answerIntent(false, 41))
            .addAction(R.drawable.check_icon_control_normal_24dp, context.getString(R.string.om_guard_allow), answerIntent(true, 42))
            .setTimeoutAfter(TIMEOUT_SECONDS * 1000)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NotificationIds.COMMAND_GUARD, notification) }
    }

    /** Dead Man / DFRoot stopped something: mark the terminal and tell the user once, no modal. */
    fun notifyProtectionBlocked(context: Context, target: String, commandLine: String, reason: CommandGuard.Reason) {
        mainHandler.post {
            val activity = hostRef?.get()
            if (activity != null && activity.isUiAlive) {
                activity.markDeadManBlocked(target, commandLine)
            }
            Toast.makeText(
                context,
                context.getString(
                    if (reason == CommandGuard.Reason.DFROOT) R.string.om_guard_dfroot_blocked_toast
                    else R.string.om_guard_dead_man_blocked_toast,
                    target
                ),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    class AnswerReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_ANSWER) return
            pending?.complete(if (intent.getBooleanExtra(EXTRA_ALLOW, false)) Answer.ALLOW else Answer.BLOCK)
            NotificationManagerCompat.from(context).cancel(NotificationIds.COMMAND_GUARD)
        }
    }
}
