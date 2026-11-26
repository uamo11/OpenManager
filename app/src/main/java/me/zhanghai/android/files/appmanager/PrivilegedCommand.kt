/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.appmanager

import com.topjohnwu.superuser.Shell
import me.zhanghai.android.files.provider.root.ShizukuManager
import me.zhanghai.android.files.provider.root.ShizukuShell

/**
 * App manager commands (force stop, clear data, disable, enable): with root when the app has it,
 * else through Shizuku (no root needed), else not at all. They used to need root.
 */
object PrivilegedCommand {

    enum class Via { ROOT, SHIZUKU, NONE }

    class Result(val isSuccess: Boolean, val via: Via, val output: String)

    fun available(): Via = when {
        Shell.isAppGrantedRoot() == true -> Via.ROOT
        ShizukuManager.ready() -> Via.SHIZUKU
        else -> Via.NONE
    }

    /** Blocking: call it off the main thread. */
    fun run(command: String): Result = when (val via = available()) {
        Via.ROOT -> {
            val r = Shell.cmd(command).exec()
            Result(r.isSuccess, via, (r.out + r.err).joinToString("\n"))
        }
        Via.SHIZUKU -> {
            val r = ShizukuShell.exec(command)
            // Some pm commands print "Success" with exit code 0, others only fail through stderr.
            Result(r.isSuccess && !r.out.contains("Failure") && !r.err.contains("Exception"), via, r.out + r.err)
        }
        Via.NONE -> Result(false, via, "")
    }
}
