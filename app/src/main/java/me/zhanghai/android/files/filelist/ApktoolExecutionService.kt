/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.CrashHandler
import me.zhanghai.android.files.app.NotificationIds
import me.zhanghai.android.files.file.AxmlDecoder
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.newOutputStream
import me.zhanghai.android.files.viewer.dex.JadxDecompilerEngine
import com.android.tools.smali.baksmali.Adaptors.ClassDefinition
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java8.nio.file.Files
import java8.nio.file.Path
import java8.nio.file.Paths

class ApktoolExecutionService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var activeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val action = intent.action
        if (action == ACTION_CANCEL) {
            cancelCurrentExecution()
            return START_NOT_STICKY
        }

        if (action == ACTION_START_TASK) {
            val taskType = intent.getStringExtra(EXTRA_TASK_TYPE) ?: TASK_TYPE_DECOMPILE_APK
            val inputPathStr = intent.getStringExtra(EXTRA_INPUT_PATH) ?: ""
            val outputPathStr = intent.getStringExtra(EXTRA_OUTPUT_PATH) ?: ""
            val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: "app"
            val displayTitle = intent.getStringExtra(EXTRA_DISPLAY_TITLE) ?: getString(R.string.apktool_decompile_apk)

            startTask(taskType, inputPathStr, outputPathStr, packageName, displayTitle)
        }

        return START_NOT_STICKY
    }

    private fun startTask(
        taskType: String,
        inputPathStr: String,
        outputPathStr: String,
        packageName: String,
        displayTitle: String
    ) {
        isCancelled.set(false)

        val initialState = TaskState(
            taskType = taskType,
            title = displayTitle,
            packageName = packageName,
            inputPath = inputPathStr,
            outputPath = outputPathStr,
            status = "Iniciando operación...",
            detail = "",
            progress = 0,
            isIndeterminate = true,
            isRunning = true,
            isCancelled = false,
            isCompleted = false
        )
        _taskState.value = initialState

        val initialNotification = buildProgressNotification(
            title = "$displayTitle: $packageName",
            text = "Iniciando operación...",
            progress = 0,
            indeterminate = true,
            outputPath = outputPathStr
        )
        startForeground(NotificationIds.APKTOOL, initialNotification)

        activeJob = serviceScope.launch {
            var success = false
            var errorMsg: String? = null

            try {
                when (taskType) {
                    TASK_TYPE_DECOMPILE_APK -> {
                        executeDecompileApk(inputPathStr, outputPathStr, packageName)
                    }
                    TASK_TYPE_DECOMPILE_JAVA -> {
                        executeDecompileJava(inputPathStr, outputPathStr, packageName)
                    }
                    TASK_TYPE_RECOMPILE_APK -> {
                        executeRecompileApk(inputPathStr, outputPathStr, packageName)
                    }
                }
                success = true
            } catch (ie: InterruptedException) {
                // Cancelled by user
                isCancelled.set(true)
            } catch (t: Throwable) {
                t.printStackTrace()
                CrashHandler.logHandledException(t, "ApktoolExecutionService")
                var current: Throwable? = t
                var rootCause: Throwable = t
                while (current != null) {
                    rootCause = current
                    current = if (current is java.lang.ExceptionInInitializerError) {
                        current.exception ?: current.cause
                    } else {
                        current.cause
                    }
                }
                val detail = rootCause.localizedMessage ?: rootCause.message ?: t.localizedMessage ?: t.message
                val simpleName = rootCause.javaClass.simpleName
                val userFriendlyHint = when {
                    rootCause is java.lang.UnsupportedOperationException -> "Operación no soportada por el sistema o formato incompatible"
                    rootCause is java.io.FileNotFoundException -> "Archivo o directorio no encontrado"
                    rootCause is java.util.zip.ZipException -> "Archivo APK corrupto o protegido con compresión incompatible"
                    rootCause is java.lang.OutOfMemoryError -> "Memoria insuficiente para procesar la aplicación"
                    else -> null
                }
                errorMsg = if (userFriendlyHint != null) {
                    if (!detail.isNullOrBlank()) {
                        "$userFriendlyHint ($simpleName: $detail)"
                    } else {
                        val frame = rootCause.stackTrace.firstOrNull()
                        val loc = if (frame != null) "${frame.className.substringAfterLast('.')}.${frame.methodName}(L:${frame.lineNumber})" else "execute"
                        "$userFriendlyHint ($simpleName en $loc)"
                    }
                } else if (!detail.isNullOrBlank()) {
                    "$simpleName: $detail"
                } else {
                    val frame = rootCause.stackTrace.firstOrNull()
                    val loc = if (frame != null) "${frame.className.substringAfterLast('.')}.${frame.methodName}(L:${frame.lineNumber})" else "execute"
                    "$simpleName en $loc"
                }
            }

            withContext(Dispatchers.Main) {
                if (isCancelled.get()) {
                    // Clean up output files if task was aborted mid-flight
                    try {
                        val out = File(outputPathStr)
                        if (out.exists()) {
                            out.deleteRecursively()
                        }
                    } catch (ignored: Throwable) {}

                    _taskState.value = _taskState.value?.copy(
                        isRunning = false,
                        isCancelled = true,
                        status = getString(R.string.apktool_cancelled)
                    )
                    NotificationManagerCompat.from(this@ApktoolExecutionService).cancel(NotificationIds.APKTOOL)
                } else if (success) {
                    _taskState.value = _taskState.value?.copy(
                        isRunning = false,
                        isCompleted = true,
                        progress = 100,
                        isIndeterminate = false,
                        status = getString(R.string.apktool_completed)
                    )
                    showCompletionNotification(displayTitle, packageName, outputPathStr)
                } else {
                    _taskState.value = _taskState.value?.copy(
                        isRunning = false,
                        errorMessage = errorMsg ?: "Error en la ejecución de Apktool",
                        status = "Error al completar la tarea"
                    )
                    NotificationManagerCompat.from(this@ApktoolExecutionService).cancel(NotificationIds.APKTOOL)
                }

                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun executeDecompileApk(inputApkPathStr: String, outputDirStr: String, packageName: String) {
        val apkFile = File(inputApkPathStr)
        val projectFile = File(outputDirStr)

        ApktoolEngine.decompileApk(
            inputApk = apkFile,
            outputDir = projectFile,
            options = ApktoolEngine.DecompileOptions(
                disassembleDex = true,
                decodeResources = true,
                forceOverwrite = true
            ),
            isCancelled = { isCancelled.get() },
            listener = object : ApktoolEngine.ApktoolListener {
                override fun onProgress(message: String, detail: String, percentage: Int) {
                    updateNotificationAndState(message, detail, percentage, percentage < 0)
                }
                override fun onLog(message: String) {}
            }
        )
    }

    private fun executeDecompileJava(inputApkPathStr: String, outputDirStr: String, packageName: String) {
        val inputFile = File(inputApkPathStr)
        val outputDir = File(outputDirStr)

        updateNotificationAndState("Inicializando motor JADX...", "", -1, true)

        JadxDecompilerEngine.decompileToDirectory(
            inputFile = inputFile,
            outputDir = outputDir,
            isCancelled = { isCancelled.get() },
            onProgress = { current, total, currentClass ->
                val pct = if (total > 0) (current * 100) / total else 0
                val simpleName = currentClass.substringAfterLast('.')
                if (current % 10 == 0 || current == total) {
                    updateNotificationAndState(
                        "Descompilando clases Java ($pct%)",
                        "$current/$total: $simpleName.java",
                        pct,
                        false
                    )
                }
            }
        )
    }

    private fun executeRecompileApk(projectDirStr: String, outputApkPathStr: String, packageName: String) {
        val projectDir = File(projectDirStr)
        val outApkFile = File(outputApkPathStr)

        ApktoolEngine.recompileApk(
            projectDir = projectDir,
            outputApk = outApkFile,
            options = ApktoolEngine.RecompileOptions(
                forceOverwrite = true,
                signAfterBuild = false
            ),
            isCancelled = { isCancelled.get() },
            listener = object : ApktoolEngine.ApktoolListener {
                override fun onProgress(message: String, detail: String, percentage: Int) {
                    updateNotificationAndState(message, detail, percentage, percentage < 0)
                }
                override fun onLog(message: String) {}
            }
        )
    }

    private var lastNotificationTime = 0L

    private fun updateNotificationAndState(
        status: String,
        detail: String,
        progress: Int,
        isIndeterminate: Boolean
    ) {
        _taskState.value = _taskState.value?.copy(
            status = status,
            detail = detail,
            progress = progress,
            isIndeterminate = isIndeterminate
        )

        val now = System.currentTimeMillis()
        if (now - lastNotificationTime > 300L || progress == 100 || progress == 0) {
            lastNotificationTime = now
            val state = _taskState.value ?: return
            val notification = buildProgressNotification(
                title = "${state.title}: ${state.packageName}",
                text = if (detail.isNotEmpty()) "$status • $detail" else status,
                progress = progress.coerceIn(0, 100),
                indeterminate = isIndeterminate,
                outputPath = state.outputPath
            )
            try {
                NotificationManagerCompat.from(this).notify(NotificationIds.APKTOOL, notification)
            } catch (ignored: Throwable) {}
        }
    }

    private fun buildProgressNotification(
        title: String,
        text: String,
        progress: Int,
        indeterminate: Boolean,
        outputPath: String
    ): Notification {
        val clickIntent = Intent(this, FileListActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(FileListActivity.EXTRA_SHOW_APKTOOL_PROGRESS, true)
        }
        val pClick = PendingIntent.getActivity(
            this,
            201,
            clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val cancelIntent = Intent(this, ApktoolExecutionService::class.java).apply {
            action = ACTION_CANCEL
        }
        val pCancel = PendingIntent.getService(
            this,
            202,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return apktoolNotificationTemplate.createBuilder(this)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pClick)
            .setOngoing(true)
            .setProgress(100, progress, indeterminate)
            .addAction(R.drawable.close_icon_white_24dp, getString(R.string.apktool_cancel), pCancel)
            .build()
    }

    private fun showCompletionNotification(title: String, packageName: String, outputPath: String) {
        val clickIntent = Intent(this, FileListActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(FileListActivity.EXTRA_NAVIGATE_TO_PATH, outputPath)
        }
        val pClick = PendingIntent.getActivity(
            this,
            203,
            clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val completedNotification = NotificationCompat.Builder(this, "apktool_operations")
            .setColor(ContextCompat.getColor(this, R.color.color_primary))
            .setSmallIcon(R.drawable.ic_apktool_notification)
            .setContentTitle("$title completado")
            .setContentText("Guardado en: $outputPath")
            .setContentIntent(pClick)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(NotificationIds.APKTOOL, completedNotification)
        } catch (ignored: Throwable) {}
    }

    private fun cancelCurrentExecution() {
        isCancelled.set(true)
        activeJob?.cancel()
        _taskState.value = _taskState.value?.copy(
            isRunning = false,
            isCancelled = true,
            status = getString(R.string.apktool_cancelled)
        )
        NotificationManagerCompat.from(this).cancel(NotificationIds.APKTOOL)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    data class TaskState(
        val taskType: String,
        val title: String,
        val packageName: String,
        val inputPath: String,
        val outputPath: String,
        val status: String,
        val detail: String,
        val progress: Int,
        val isIndeterminate: Boolean,
        val isRunning: Boolean,
        val isCancelled: Boolean,
        val isCompleted: Boolean,
        val errorMessage: String? = null
    )

    companion object {
        const val ACTION_START_TASK = "me.zhanghai.android.files.filelist.action.START_APKTOOL_TASK"
        const val ACTION_CANCEL = "me.zhanghai.android.files.filelist.action.CANCEL_APKTOOL_TASK"

        const val EXTRA_TASK_TYPE = "EXTRA_TASK_TYPE"
        const val EXTRA_INPUT_PATH = "EXTRA_INPUT_PATH"
        const val EXTRA_OUTPUT_PATH = "EXTRA_OUTPUT_PATH"
        const val EXTRA_PACKAGE_NAME = "EXTRA_PACKAGE_NAME"
        const val EXTRA_DISPLAY_TITLE = "EXTRA_DISPLAY_TITLE"

        const val TASK_TYPE_DECOMPILE_APK = "TASK_TYPE_DECOMPILE_APK"
        const val TASK_TYPE_DECOMPILE_JAVA = "TASK_TYPE_DECOMPILE_JAVA"
        const val TASK_TYPE_RECOMPILE_APK = "TASK_TYPE_RECOMPILE_APK"

        val isCancelled = AtomicBoolean(false)

        private val _taskState = MutableStateFlow<TaskState?>(null)
        val taskState: StateFlow<TaskState?> = _taskState.asStateFlow()

        fun isRunning(): Boolean = _taskState.value?.isRunning == true

        fun cancelCurrentTask(context: Context) {
            isCancelled.set(true)
            val intent = Intent(context, ApktoolExecutionService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }

        fun startTask(
            context: Context,
            taskType: String,
            inputPath: String,
            outputPath: String,
            packageName: String,
            displayTitle: String
        ) {
            val intent = Intent(context, ApktoolExecutionService::class.java).apply {
                action = ACTION_START_TASK
                putExtra(EXTRA_TASK_TYPE, taskType)
                putExtra(EXTRA_INPUT_PATH, inputPath)
                putExtra(EXTRA_OUTPUT_PATH, outputPath)
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                putExtra(EXTRA_DISPLAY_TITLE, displayTitle)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
