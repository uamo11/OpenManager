/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.Process
import android.util.Log
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

object CrashHandler : Thread.UncaughtExceptionHandler {

    private const val TAG = "CrashHandler"
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private lateinit var app: Application

    fun install(application: Application) {
        app = application
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)

        // Pre-create OpenManager logs directory if external storage is accessible
        try {
            ensureLogsDirectory()
        } catch (ignored: Throwable) {}
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val (logFile, report, stackTrace) = generateAndSaveLog(thread, throwable, isCrash = true)
            launchCrashReportActivity(thread, throwable, logFile, report, stackTrace)
        } catch (e: Exception) {
            Log.e(TAG, "Fatal failure inside CrashHandler.uncaughtException", e)
            defaultHandler?.uncaughtException(thread, throwable) ?: run {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        } finally {
            try {
                // Brief pause so ActivityManager can process the startActivity IPC before this process terminates
                Thread.sleep(400)
            } catch (ignored: InterruptedException) {}
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    /**
     * Public method to log handled exceptions (e.g. background tasks, file operations, editor errors)
     * into /storage/emulated/0/OpenManager/logs/error_yyyyMMdd_HHmmss.log.
     */
    fun logHandledException(throwable: Throwable, tag: String = "Error"): File? {
        return try {
            val (logFile, _, _) = generateAndSaveLog(Thread.currentThread(), throwable, isCrash = false, tag = tag)
            logFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write handled exception log", e)
            null
        }
    }

    private data class LogResult(val file: File?, val fullReport: String, val stackTrace: String)

    private fun generateAndSaveLog(
        thread: Thread,
        throwable: Throwable,
        isCrash: Boolean,
        tag: String = "Crash"
    ): LogResult {
        val now = System.currentTimeMillis()
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        val readableTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(now))

        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        val stackTrace = sw.toString()

        val runtime = Runtime.getRuntime()
        val freeHeapMb = runtime.freeMemory() / (1024 * 1024)
        val totalHeapMb = runtime.totalMemory() / (1024 * 1024)
        val maxHeapMb = runtime.maxMemory() / (1024 * 1024)

        var totalRamMb = 0L
        var availRamMb = 0L
        try {
            val mi = ActivityManager.MemoryInfo()
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.getMemoryInfo(mi)
            totalRamMb = mi.totalMem / (1024 * 1024)
            availRamMb = mi.availMem / (1024 * 1024)
        } catch (ignored: Throwable) {}

        val report = buildString {
            appendLine("****************************************************************")
            if (isCrash) {
                appendLine("                     OPENMANAGER CRASH LOG                      ")
            } else {
                appendLine("                 OPENMANAGER ERROR LOG ($tag)                   ")
            }
            appendLine("****************************************************************")
            appendLine("Time:            $readableTime")
            appendLine("App:             OpenManager v${BuildConfig.VERSION_NAME} (VersionCode ${BuildConfig.VERSION_CODE})")
            appendLine("Package:         ${app.packageName}")
            appendLine("Process:         ${getProcessNameSafe()} (PID: ${Process.myPid()})")
            appendLine("Thread:          ${thread.name} (TID: ${thread.id}, Priority: ${thread.priority})")
            appendLine()
            appendLine("------------------------- DEVICE INFO -------------------------")
            appendLine("Brand:           ${Build.BRAND}")
            appendLine("Manufacturer:    ${Build.MANUFACTURER}")
            appendLine("Model:           ${Build.MODEL}")
            appendLine("Device:          ${Build.DEVICE}")
            appendLine("Product:         ${Build.PRODUCT}")
            appendLine("Board:           ${Build.BOARD}")
            appendLine("Hardware:        ${Build.HARDWARE}")
            appendLine("Android Version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Fingerprint:     ${Build.FINGERPRINT}")
            appendLine("Supported ABIs:  ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine()
            appendLine("------------------------ MEMORY STATUS ------------------------")
            appendLine("Device Total RAM: ${totalRamMb}MB")
            appendLine("Device Avail RAM: ${availRamMb}MB")
            appendLine("Allocated Heap:   ${totalHeapMb}MB")
            appendLine("Free Heap:        ${freeHeapMb}MB")
            appendLine("Max Heap Limit:   ${maxHeapMb}MB")
            appendLine()
            appendLine("---------------------- EXCEPTION DETAILS ----------------------")
            appendLine("Type:            ${throwable.javaClass.name}")
            appendLine("Message:         ${throwable.localizedMessage ?: "(No message)"}")
            appendLine()
            appendLine("---------------------- FULL STACKTRACE ------------------------")
            appendLine(stackTrace)
            appendLine("****************************************************************")
        }

        val prefix = if (isCrash) "crash" else "error"
        val fileName = "${prefix}_${timeStamp}.log"

        val targetDir = ensureLogsDirectory()
        var savedFile: File? = null

        if (targetDir != null && targetDir.canWrite()) {
            try {
                val file = File(targetDir, fileName)
                FileWriter(file).use { writer ->
                    writer.write(report)
                    writer.flush()
                }
                savedFile = file
                Log.i(TAG, "Log successfully written to: ${file.absolutePath}")

                // Also maintain a copy in the base OpenManager directory for immediate visibility
                val baseDir = File("/storage/emulated/0/OpenManager")
                if (baseDir.exists() && baseDir.canWrite() && targetDir != baseDir) {
                    val directFile = File(baseDir, fileName)
                    FileWriter(directFile).use { writer ->
                        writer.write(report)
                        writer.flush()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed writing to target directory: ${targetDir.absolutePath}", e)
            }
        }

        // Fallback to internal storage if external was not writable
        if (savedFile == null) {
            val fallbackDirs = listOf(
                app.getExternalFilesDir("logs"),
                File(app.filesDir, "logs")
            )
            for (fallback in fallbackDirs) {
                if (fallback == null) continue
                try {
                    fallback.mkdirs()
                    val file = File(fallback, fileName)
                    FileWriter(file).use { writer ->
                        writer.write(report)
                        writer.flush()
                    }
                    savedFile = file
                    break
                } catch (ignored: Throwable) {}
            }
        }

        return LogResult(savedFile, report, stackTrace)
    }

    private fun launchCrashReportActivity(
        thread: Thread,
        throwable: Throwable,
        logFile: File?,
        report: String,
        stackTrace: String
    ) {
        try {
            val intent = Intent(app, CrashReportActivity::class.java).apply {
                putExtra(CrashReportActivity.EXTRA_LOG_PATH, logFile?.absolutePath)
                putExtra(CrashReportActivity.EXTRA_ERROR_MESSAGE, throwable.localizedMessage ?: throwable.toString())
                putExtra(CrashReportActivity.EXTRA_STACK_TRACE, stackTrace)
                putExtra(CrashReportActivity.EXTRA_THREAD_NAME, thread.name)
                putExtra(CrashReportActivity.EXTRA_REPORT, report)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            app.startActivity(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start CrashReportActivity", t)
        }
    }

    fun ensureLogsDirectory(): File? {
        val basePath = try {
            Settings.CUSTOM_OPENMANAGER_DIR.valueCompat.let {
                if (it.startsWith("/sdcard")) {
                    @Suppress("DEPRECATION")
                    it.replaceFirst("/sdcard", Environment.getExternalStorageDirectory().absolutePath)
                } else {
                    it.ifEmpty { "/storage/emulated/0/OpenManager" }
                }
            }
        } catch (t: Throwable) {
            "/storage/emulated/0/OpenManager"
        }

        val candidates = listOf(
            File(basePath, "logs"),
            File("/storage/emulated/0/OpenManager/logs"),
            File(Environment.getExternalStorageDirectory(), "OpenManager/logs"),
            File(basePath),
            File("/storage/emulated/0/OpenManager"),
            app.getExternalFilesDir("logs"),
            File(app.filesDir, "logs")
        )

        for (dir in candidates) {
            try {
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                if (dir.exists() && dir.canWrite()) {
                    return dir
                }
            } catch (ignored: Throwable) {}
        }
        return null
    }

    private fun getProcessNameSafe(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            app.packageName
        }
    }
}
