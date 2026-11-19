/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.NotificationChannelTemplate
import me.zhanghai.android.files.util.NotificationTemplate

val scriptExecutionNotificationTemplate =
    NotificationTemplate(
        NotificationChannelTemplate(
            "script_execution",
            R.string.notification_channel_script_execution_name,
            NotificationManagerCompat.IMPORTANCE_LOW,
            descriptionRes = R.string.notification_channel_script_execution_description,
            showBadge = true
        ),
        colorRes = R.color.color_primary,
        smallIcon = R.drawable.ic_terminal_notification_24dp,
        ongoing = true,
        onlyAlertOnce = true,
        category = NotificationCompat.CATEGORY_SERVICE,
        priority = NotificationCompat.PRIORITY_LOW
    )
