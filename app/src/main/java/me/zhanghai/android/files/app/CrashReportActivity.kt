/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.widget.Toast
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.ActivityCrashReportBinding
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.viewer.text.TextEditorActivity
import java.io.File
import java8.nio.file.Paths
import kotlin.system.exitProcess

class CrashReportActivity : AppActivity() {

    private lateinit var binding: ActivityCrashReportBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCrashReportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val logPath = intent.getStringExtra(EXTRA_LOG_PATH)
        val errorMessage = intent.getStringExtra(EXTRA_ERROR_MESSAGE) ?: "Error desconocido"
        val stackTrace = intent.getStringExtra(EXTRA_STACK_TRACE) ?: "No stacktrace available"
        val threadName = intent.getStringExtra(EXTRA_THREAD_NAME) ?: "Unknown"
        val fullReport = intent.getStringExtra(EXTRA_REPORT) ?: stackTrace

        binding.toolbar.title = getString(R.string.crash_report_title)
        setSupportActionBar(binding.toolbar)

        if (!logPath.isNullOrEmpty()) {
            binding.logPathText.text = getString(R.string.crash_report_saved_format, logPath)
        } else {
            binding.logPathText.text = "Registro guardado en /storage/emulated/0/OpenManager/logs/"
        }

        binding.systemInfoText.text = buildString {
            append("App: OpenManager ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
            append("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n")
            append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("Hilo: $threadName\n")
            append("Error: $errorMessage")
        }

        binding.stackTraceText.text = stackTrace

        binding.copyButton.setOnClickListener {
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("OpenManager Crash Report", fullReport)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, R.string.crash_report_copied_toast, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Error al copiar: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        binding.openLogButton.setOnClickListener {
            if (!logPath.isNullOrEmpty() && File(logPath).exists()) {
                try {
                    val editIntent = Intent(this, TextEditorActivity::class.java).apply {
                        extraPath = Paths.get(logPath)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(editIntent)
                } catch (e: Exception) {
                    try {
                        val file = File(logPath)
                        val uri = Uri.parse("content://${BuildConfig.FILE_PROVIDIER_AUTHORITY}/" + Uri.encode(file.toURI().toString()))
                        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "text/plain")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(Intent.createChooser(viewIntent, getString(R.string.crash_report_open_log)))
                    } catch (e2: Exception) {
                        Toast.makeText(this, "No se pudo abrir el log: ${e2.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(this, "El archivo de log no existe aún", Toast.LENGTH_SHORT).show()
            }
        }

        binding.shareButton.setOnClickListener {
            try {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "OpenManager Error Report - v${BuildConfig.VERSION_NAME}")
                    putExtra(Intent.EXTRA_TEXT, fullReport)
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.crash_report_share)))
            } catch (e: Exception) {
                Toast.makeText(this, "Error al compartir: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        binding.restartButton.setOnClickListener {
            restartApp()
        }

        binding.closeButton.setOnClickListener {
            closeApp()
        }
    }

    private fun restartApp() {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            if (launchIntent != null) {
                startActivity(launchIntent)
            }
        } catch (ignored: Exception) {}
        closeApp()
    }

    private fun closeApp() {
        finishAffinity()
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        closeApp()
    }

    companion object {
        const val EXTRA_LOG_PATH = "me.zhanghai.android.files.app.extra.LOG_PATH"
        const val EXTRA_ERROR_MESSAGE = "me.zhanghai.android.files.app.extra.ERROR_MESSAGE"
        const val EXTRA_STACK_TRACE = "me.zhanghai.android.files.app.extra.STACK_TRACE"
        const val EXTRA_THREAD_NAME = "me.zhanghai.android.files.app.extra.THREAD_NAME"
        const val EXTRA_REPORT = "me.zhanghai.android.files.app.extra.REPORT"
    }
}
