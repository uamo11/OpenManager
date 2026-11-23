/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.webaccess

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.NotificationIds
import me.zhanghai.android.files.compat.mainExecutorCompat
import me.zhanghai.android.files.compat.stopForegroundCompat
import me.zhanghai.android.files.util.NotificationChannelTemplate
import me.zhanghai.android.files.util.NotificationTemplate
import me.zhanghai.android.files.util.WakeWifiLock
import me.zhanghai.android.files.util.getLocalAddress
import java.net.InetAddress
import java.util.concurrent.Executors

val webAccessNotificationTemplate =
    NotificationTemplate(
        NotificationChannelTemplate(
            "web_access",
            R.string.om_web_access_channel,
            NotificationManagerCompat.IMPORTANCE_LOW,
            showBadge = false
        ),
        colorRes = R.color.color_primary,
        smallIcon = R.drawable.notification_icon,
        contentTitleRes = R.string.om_web_access_title,
        ongoing = true,
        onlyAlertOnce = true,
        category = NotificationCompat.CATEGORY_SERVICE,
        priority = NotificationCompat.PRIORITY_LOW
    )

/** Keeps the browser access running in the background until the user stops it. */
class WebAccessService : Service() {

    data class Status(
        val running: Boolean = false,
        val url: String? = null,
        val code: String? = null,
        val locked: Boolean = false,
        val error: String? = null
    ) {
        /** The address with the code, to open directly (copied or shared on purpose by the user). */
        val directLink: String? get() = if (url != null && code != null) "$url/?k=$code" else null
    }

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile
    private var server: WebAccessServer? = null
    @Volatile
    private var destroyed = false
    private lateinit var wakeWifiLock: WakeWifiLock

    override fun onCreate() {
        super.onCreate()
        wakeWifiLock = WakeWifiLock(WebAccessService::class.java.simpleName)
        wakeWifiLock.isAcquired = true
        startForeground(NotificationIds.WEB_ACCESS, buildNotification(Status()))
        executor.execute { startServer() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        destroyed = true
        // Same thread as the start, so a server still starting is stopped too.
        executor.execute {
            server?.stop()
            server = null
        }
        executor.shutdown()
        wakeWifiLock.isAcquired = false
        stopForegroundCompat(ServiceCompat.STOP_FOREGROUND_REMOVE)
        _status.value = Status()
    }

    private fun startServer() {
        val status = try {
            val index = assets.open("om_web/index.html").use { it.readBytes().toString(Charsets.UTF_8) }
            val code = WebAccessServer.newCode()
            val device = listOfNotNull(Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }, Build.MODEL)
                .distinct().joinToString(" ")
            @Suppress("DEPRECATION")
            val root = Environment.getExternalStorageDirectory()
            val webServer = WebAccessServer(root, code, index, device)
            webServer.onLocked = { mainExecutorCompat.execute { publish(_status.value!!.copy(locked = true)) } }
            webServer.start()
            server = webServer
            if (destroyed) {
                webServer.stop()
                server = null
                return
            }
            val host = InetAddress::class.getLocalAddress()?.hostAddress
            Status(running = true, url = host?.let { "http://$it:${webServer.port}" }, code = code)
        } catch (e: Exception) {
            Status(error = e.message ?: e.toString())
        }
        mainExecutorCompat.execute {
            if (destroyed) return@execute
            publish(status)
            if (!status.running) stopSelf()
        }
    }

    private fun publish(status: Status) {
        _status.value = status
        // Calling it again just updates the notification (no extra permission needed).
        if (status.running && !destroyed) startForeground(NotificationIds.WEB_ACCESS, buildNotification(status))
    }

    private fun buildNotification(status: Status): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, WebAccessActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WebAccessService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = when {
            status.locked -> getString(R.string.om_web_access_locked)
            status.url != null -> getString(R.string.om_web_access_notification_text, status.url, status.code)
            status.running -> getString(R.string.om_web_access_no_network)
            else -> getString(R.string.om_web_access_starting)
        }
        return webAccessNotificationTemplate.createBuilder(this)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(R.drawable.stop_icon_white_24dp, getString(R.string.stop), stop)
            .build()
    }

    companion object {
        const val ACTION_STOP = "me.zhanghai.android.files.webaccess.action.STOP"

        private val _status = MutableLiveData(Status())
        val status: LiveData<Status> get() = _status

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WebAccessService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WebAccessService::class.java))
        }
    }
}
