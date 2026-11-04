/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.NotificationIds

class ScriptExecutionService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        const val ACTION_START = "me.zhanghai.android.files.terminal.action.START"
        const val ACTION_KILL_SHELL = "me.zhanghai.android.files.terminal.action.KILL_SHELL"
        const val ACTION_KEEP_BACKGROUND = "me.zhanghai.android.files.terminal.action.KEEP_BACKGROUND"
        const val ACTION_UPDATE = "me.zhanghai.android.files.terminal.action.UPDATE"
        const val ACTION_FINISHED = "me.zhanghai.android.files.terminal.action.FINISHED"
        const val ACTION_DISMISS = "me.zhanghai.android.files.terminal.action.DISMISS"

        const val EXTRA_SCRIPT_NAME = "script_name"
        const val EXTRA_PID = "pid"
        const val EXTRA_EXIT_CODE = "exit_code"

        fun start(context: Context, scriptName: String, pid: Int) {
            val intent = Intent(context, ScriptExecutionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SCRIPT_NAME, scriptName)
                putExtra(EXTRA_PID, pid)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                runCatching { context.startService(intent) }
            }
        }

        fun updateNotification(context: Context) {
            val intent = Intent(context, ScriptExecutionService::class.java).apply {
                action = ACTION_UPDATE
            }
            runCatching { context.startService(intent) }
        }

        fun onExecutionFinished(context: Context, exitCode: Int) {
            val intent = Intent(context, ScriptExecutionService::class.java).apply {
                action = ACTION_FINISHED
                putExtra(EXTRA_EXIT_CODE, exitCode)
            }
            runCatching { context.startService(intent) }
        }

        fun onTerminatedByUser(context: Context) {
            val intent = Intent(context, ScriptExecutionService::class.java).apply {
                action = ACTION_KILL_SHELL
            }
            runCatching { context.startService(intent) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScriptExecutionService::class.java).apply {
                action = ACTION_DISMISS
            }
            runCatching { context.startService(intent) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_START -> {
                val scriptName = intent.getStringExtra(EXTRA_SCRIPT_NAME)
                    ?: ScriptExecutionManager.activeScriptName.ifEmpty { "Script" }
                val pid = intent.getIntExtra(EXTRA_PID, ScriptExecutionManager.activePid)
                val notification = buildRunningNotification(scriptName, pid)
                startForeground(NotificationIds.SCRIPT_EXECUTION, notification)
            }
            ACTION_KILL_SHELL -> {
                handleKillShell()
            }
            ACTION_KEEP_BACKGROUND -> {
                handleKeepBackground()
            }
            ACTION_UPDATE -> {
                if (ScriptExecutionManager.isRunning()) {
                    val notification = buildRunningNotification(
                        ScriptExecutionManager.activeScriptName,
                        ScriptExecutionManager.activePid
                    )
                    NotificationManagerCompat.from(this).notify(NotificationIds.SCRIPT_EXECUTION, notification)
                }
            }
            ACTION_FINISHED -> {
                if (ScriptExecutionManager.isTerminatedByUser) {
                    NotificationManagerCompat.from(this).cancel(NotificationIds.SCRIPT_EXECUTION)
                    stopForeground(true)
                    stopSelf()
                } else {
                    val exitCode = intent.getIntExtra(EXTRA_EXIT_CODE, ScriptExecutionManager.lastExitCode ?: 0)
                    handleFinished(exitCode)
                }
            }
            ACTION_DISMISS -> {
                NotificationManagerCompat.from(this).cancel(NotificationIds.SCRIPT_EXECUTION)
                stopForeground(true)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun handleKillShell() {
        val pid = ScriptExecutionManager.activePid
        ScriptExecutionManager.terminateShell(this)

        mainHandler.post {
            Toast.makeText(this, getString(R.string.script_exec_killed_toast, if (pid > 0) pid else 0), Toast.LENGTH_SHORT).show()
        }

        NotificationManagerCompat.from(this).cancel(NotificationIds.SCRIPT_EXECUTION)
        stopForeground(true)
        stopSelf()
    }

    private fun handleKeepBackground() {
        ScriptExecutionManager.keepInBackground(this)

        mainHandler.post {
            Toast.makeText(this, getString(R.string.script_exec_kept_toast), Toast.LENGTH_SHORT).show()
        }

        val notification = buildRunningNotification(
            ScriptExecutionManager.activeScriptName,
            ScriptExecutionManager.activePid
        )
        NotificationManagerCompat.from(this).notify(NotificationIds.SCRIPT_EXECUTION, notification)
    }

    private fun handleFinished(exitCode: Int) {
        if (ScriptExecutionManager.isTerminatedByUser || (!ScriptExecutionManager.isRunning() && ScriptExecutionManager.activePid <= 0 && exitCode == -9)) {
            NotificationManagerCompat.from(this).cancel(NotificationIds.SCRIPT_EXECUTION)
            stopForeground(true)
            stopSelf()
            return
        }
        val scriptName = ScriptExecutionManager.activeScriptName.ifEmpty { "Script" }
        val title = if (exitCode == 0) {
            getString(R.string.script_exec_completed_success)
        } else {
            getString(R.string.script_exec_completed_error, exitCode)
        }

        val openIntent = createOpenTerminalPendingIntent()
        val dismissIntent = createActionPendingIntent(ACTION_DISMISS, 98)

        val notification = scriptExecutionNotificationTemplate.createBuilder(this)
            .setContentTitle(title)
            .setContentText("$scriptName • Toca para abrir terminal")
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.file_sh_icon, getString(R.string.script_exec_notification_view_output), openIntent)
            .addAction(R.drawable.close_icon_white_24dp, getString(R.string.script_exec_notification_dismiss), dismissIntent)
            .build()

        stopForeground(false)
        NotificationManagerCompat.from(this).notify(NotificationIds.SCRIPT_EXECUTION, notification)
    }

    private fun buildRunningNotification(scriptName: String, pid: Int): Notification {
        val openIntent = createOpenTerminalPendingIntent()
        val killIntent = createActionPendingIntent(ACTION_KILL_SHELL, 1)
        val keepIntent = createActionPendingIntent(ACTION_KEEP_BACKGROUND, 2)

        val isKept = ScriptExecutionManager.isBackgroundKept
        val pidStr = if (pid > 0) "PID: $pid" else "Iniciando"
        val statusText = if (isKept) {
            "$pidStr • Activo en 2ndo plano (WakeLock) • Toca para abrir terminal"
        } else {
            "$pidStr • En ejecución • Toca para abrir terminal"
        }

        val subText = when {
            ScriptExecutionManager.isRoot -> "Root (#)"
            ScriptExecutionManager.isTermex -> "Termex (bash)"
            else -> "Sistema (sh)"
        }

        return scriptExecutionNotificationTemplate.createBuilder(this)
            .setContentTitle(getString(R.string.script_exec_notification_running, scriptName))
            .setContentText(statusText)
            .setSubText(subText)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(
                R.drawable.close_icon_white_24dp,
                getString(R.string.script_exec_notification_kill_action),
                killIntent
            )
            .addAction(
                R.drawable.check_icon_control_normal_24dp,
                getString(R.string.script_exec_notification_keep_background),
                keepIntent
            )
            .build()
    }

    private fun createOpenTerminalPendingIntent(): PendingIntent {
        val intent = Intent(this, TerminalActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun createActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, ScriptExecutionService::class.java).apply {
            this.action = action
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getService(this, requestCode, intent, flags)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
    }
}
