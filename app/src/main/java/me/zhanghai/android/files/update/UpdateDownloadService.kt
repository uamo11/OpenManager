/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.update

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.NotificationIds

val updateDownloadNotificationTemplate =
    me.zhanghai.android.files.util.NotificationTemplate(
        me.zhanghai.android.files.util.NotificationChannelTemplate(
            "update_download",
            R.string.notification_channel_update_download_name,
            NotificationManagerCompat.IMPORTANCE_LOW,
            showBadge = false
        ),
        colorRes = R.color.color_primary,
        smallIcon = R.drawable.notification_icon,
        contentTitleRes = R.string.notification_update_download_title,
        ongoing = true,
        onlyAlertOnce = true,
        category = NotificationCompat.CATEGORY_PROGRESS,
        priority = NotificationCompat.PRIORITY_LOW
    )

/**
 * Keeps the app in the foreground while it downloads its own update (user report: minimizing the
 * app aborted the download, "Software caused connection abort", as phones cut background network).
 * The download itself runs in [UpdateChecker]; this only holds the notification with its progress.
 */
class UpdateDownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                val name = intent?.getStringExtra(EXTRA_NAME).orEmpty()
                val notification = progressBuilder(this, name, -1).build()
                try {
                    ServiceCompat.startForeground(
                        this, NotificationIds.UPDATE_DOWNLOAD, notification,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        } else {
                            0
                        }
                    )
                } catch (e: Exception) {
                    // Not allowed right now (rare): the download still runs, only less protected.
                    e.printStackTrace()
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val ACTION_STOP = "me.zhanghai.android.files.update.action.STOP_DOWNLOAD"
        private const val EXTRA_NAME = "name"

        fun start(context: Context, name: String) {
            val intent = Intent(context, UpdateDownloadService::class.java).putExtra(EXTRA_NAME, name)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { it.printStackTrace() }
        }

        fun stop(context: Context) {
            val intent = Intent(context, UpdateDownloadService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }

        /** [percent] below 0: not known yet. */
        @SuppressLint("MissingPermission")
        fun progress(context: Context, name: String, percent: Int) {
            runCatching {
                NotificationManagerCompat.from(context)
                    .notify(NotificationIds.UPDATE_DOWNLOAD, progressBuilder(context, name, percent).build())
            }
        }

        /** Downloaded while the app was in the background: Android only opens the installer from the front. */
        @SuppressLint("MissingPermission")
        fun readyToInstall(context: Context, version: String) {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
            val pending = PendingIntent.getActivity(
                context, NotificationIds.UPDATE_READY, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, updateDownloadNotificationTemplate.channelTemplate.id)
                .setSmallIcon(R.drawable.notification_icon)
                .setColor(ContextCompat.getColor(context, R.color.color_primary))
                .setContentTitle(context.getString(R.string.notification_update_ready_title))
                .setContentText(context.getString(R.string.notification_update_ready_text, version))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(NotificationIds.UPDATE_READY, notification) }
        }

        fun cancelReady(context: Context) {
            runCatching { NotificationManagerCompat.from(context).cancel(NotificationIds.UPDATE_READY) }
        }

        private fun progressBuilder(context: Context, name: String, percent: Int): NotificationCompat.Builder =
            updateDownloadNotificationTemplate.createBuilder(context)
                .setContentText(if (percent >= 0) "$name · $percent%" else name)
                .setProgress(100, percent.coerceAtLeast(0), percent < 0)
    }
}
