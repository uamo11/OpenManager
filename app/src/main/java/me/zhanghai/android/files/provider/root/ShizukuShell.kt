/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import android.util.Log
import rikka.shizuku.ShizukuProcessFactory
import rikka.shizuku.ShizukuRemoteProcess
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object ShizukuShell {
    private const val TAG = "ShizukuShell"

    data class Result(
        val isSuccess: Boolean,
        val out: String,
        val err: String,
        val code: Int
    )

    fun exec(command: String, timeoutSeconds: Long = 30): Result {
        if (!ShizukuManager.ready()) {
            return Result(false, "", "Shizuku not ready or permission missing", -1)
        }
        return try {
            val p = ShizukuProcessFactory().newProcess(arrayOf("sh", "-c", command), null, "/")
            val outLines = StringBuilder()
            val errLines = StringBuilder()

            val outThread = Thread {
                try {
                    BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            if (outLines.isNotEmpty()) outLines.append("\n")
                            outLines.append(line)
                        }
                    }
                } catch (ignored: Exception) {}
            }

            val errThread = Thread {
                try {
                    BufferedReader(InputStreamReader(p.errorStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            if (errLines.isNotEmpty()) errLines.append("\n")
                            errLines.append(line)
                        }
                    }
                } catch (ignored: Exception) {}
            }

            outThread.start()
            errThread.start()

            val finished = if (p is ShizukuRemoteProcess) {
                p.waitForTimeout(timeoutSeconds, TimeUnit.SECONDS)
            } else {
                p.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            }

            if (!finished) {
                try {
                    p.destroy()
                } catch (ignored: Exception) {}
                return Result(false, outLines.toString(), "Command timed out", -1)
            }

            outThread.join(1000)
            errThread.join(1000)

            val exitCode = p.exitValue()
            Result(exitCode == 0, outLines.toString(), errLines.toString(), exitCode)
        } catch (t: Throwable) {
            Log.e(TAG, "exec failed: $command", t)
            Result(false, "", t.message ?: t.javaClass.simpleName, -1)
        }
    }
}
