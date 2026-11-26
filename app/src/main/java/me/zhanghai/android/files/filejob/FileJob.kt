/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import me.zhanghai.android.files.util.showToast
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Random

abstract class FileJob {
    val id = Random().nextInt()

    internal lateinit var service: FileJobService
        private set

    fun runOn(service: FileJobService) {
        this.service = service
        var failed = false
        try {
            run()
        } catch (e: InterruptedIOException) {
            // Canceled by the user.
            e.printStackTrace()
        } catch (e: Exception) {
            failed = true
            e.printStackTrace()
            val logFile = me.zhanghai.android.files.app.CrashHandler.logHandledException(e, "FileJob")
            val msg = if (logFile != null) {
                "${e.localizedMessage ?: e.javaClass.simpleName}\nLog: ${logFile.absolutePath}"
            } else {
                e.toString()
            }
            service.showToast(msg)
        } finally {
            service.notificationManager.cancel(id)
            FileJobProgress.finish(id, failed)
        }
    }

    @Throws(IOException::class)
    protected abstract fun run()
}
