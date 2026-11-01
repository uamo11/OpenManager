/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.appmanager.running

import android.content.pm.PackageInfo
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class RunningAppModel(
    val pid: Int,
    val ppid: Int = 0,
    val uid: Int = 0,
    val processName: String,
    val packageName: String? = null,
    val label: String,
    val isApp: Boolean = true,
    val isSystem: Boolean = false,
    val memoryBytes: Long = 0L,
    val cpuPercent: Float = 0f,
    val state: String = "R", // R: Running, S: Sleeping, D: Disk Sleep, Z: Zombie
    val threadCount: Int = 1,
    val user: String = "",
    val cmdline: String = "",
    val selinuxContext: String = ""
) : Parcelable
