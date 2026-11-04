/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import android.content.Context
import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

object ScriptExecutionManager {

    var activeSession: TerminalSession? = null
        private set

    var activeScriptName: String = ""
        private set

    var activeScriptPath: String? = null
        private set

    var activeCommand: String? = null
        private set

    var activePid: Int = -1
        set(value) {
            field = value
            if (value > 0) {
                notifyPidAssigned(value)
                runCatching {
                    me.zhanghai.android.files.terminal.tracer.SyscallReaderMonitor.startMonitoring(value)
                }
            } else {
                runCatching {
                    me.zhanghai.android.files.terminal.tracer.SyscallReaderMonitor.stopMonitoring()
                }
            }
        }

    var isRoot: Boolean = false
        private set

    var isTermex: Boolean = false
        private set

    var startTimeMillis: Long = 0L
        private set

    var isBackgroundKept: Boolean = false
        private set

    var lastExitCode: Int? = null
        private set

    @Volatile
    var isTerminatedByUser: Boolean = false
        private set

    private var wakeLock: PowerManager.WakeLock? = null

    interface SessionListener {
        fun onPidAssigned(pid: Int) {}
        fun onOutputUpdated() {}
        fun onTitleChanged(title: String) {}
        fun onSessionFinished(exitCode: Int) {}
    }

    private val listeners = CopyOnWriteArrayList<SessionListener>()

    fun addListener(listener: SessionListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: SessionListener) {
        listeners.remove(listener)
    }

    fun isRunning(): Boolean {
        return activeSession?.isRunning == true
    }

    fun hasActiveSession(): Boolean {
        return activeSession != null && activeSession?.isRunning == true
    }

    fun startExecution(
        context: Context,
        session: TerminalSession,
        scriptName: String,
        path: String? = null,
        command: String? = null,
        root: Boolean = false,
        termex: Boolean = false
    ) {
        activeSession = session
        activeScriptName = scriptName.ifEmpty { "Script" }
        activeScriptPath = path
        activeCommand = command
        activePid = session.pid
        isRoot = root
        isTermex = termex
        startTimeMillis = System.currentTimeMillis()
        isBackgroundKept = false
        lastExitCode = null
        isTerminatedByUser = false

        ScriptExecutionService.start(context.applicationContext, activeScriptName, activePid)
    }

    fun keepInBackground(context: Context) {
        isBackgroundKept = true
        acquireWakeLock(context)
        ScriptExecutionService.updateNotification(context.applicationContext)
    }

    private val isTerminating = java.util.concurrent.atomic.AtomicBoolean(false)

    fun terminateShell(context: Context): Boolean {
        isTerminatedByUser = true
        if (!isTerminating.compareAndSet(false, true)) {
            return false
        }
        try {
            val session = activeSession
            val pid = activePid
            activeSession = null
            activePid = -1
            activeCommand = null
            activeScriptName = ""

            if (session != null && session.isRunning) {
                runCatching { session.finishIfRunning() }
            }
            runCatching { me.zhanghai.android.files.terminal.tracer.SyscallReaderMonitor.stopMonitoring() }

            if (pid > 0) {
                runCatching { Os.kill(pid, OsConstants.SIGKILL) }
                runCatching { Os.kill(-pid, OsConstants.SIGKILL) }
                if (isRoot) {
                    CoroutineScope(Dispatchers.IO).launch {
                        runCatching {
                            com.topjohnwu.superuser.Shell.cmd("kill -9 $pid 2>/dev/null || true").exec()
                        }
                    }
                }
            }

            releaseWakeLock()
            lastExitCode = -9
            notifySessionFinished(-9)
            return true
        } finally {
            isTerminating.set(false)
        }
    }

    fun onSessionFinished(exitCode: Int, context: Context?) {
        runCatching { me.zhanghai.android.files.terminal.tracer.SyscallReaderMonitor.stopMonitoring() }
        if (isTerminating.get() || isTerminatedByUser) {
            return
        }
        lastExitCode = exitCode
        releaseWakeLock()
        notifySessionFinished(exitCode)
        if (context != null) {
            ScriptExecutionService.onExecutionFinished(context.applicationContext, exitCode)
        }
    }

    fun notifyOutputUpdated() {
        for (listener in listeners) {
            listener.onOutputUpdated()
        }
    }

    fun notifyTitleChanged(title: String) {
        for (listener in listeners) {
            listener.onTitleChanged(title)
        }
    }

    private fun notifyPidAssigned(pid: Int) {
        for (listener in listeners) {
            listener.onPidAssigned(pid)
        }
    }

    private fun notifySessionFinished(exitCode: Int) {
        for (listener in listeners) {
            listener.onSessionFinished(exitCode)
        }
    }

    fun acquireWakeLock(context: Context) {
        if (wakeLock == null) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenManager:ScriptExecWakeLock")
        }
        wakeLock?.let { wl ->
            if (!wl.isHeld) {
                wl.acquire(2 * 60 * 60 * 1000L) // 2 hours max
            }
        }
    }

    fun releaseWakeLock() {
        wakeLock?.let { wl ->
            if (wl.isHeld) {
                runCatching { wl.release() }
            }
        }
    }

    fun clearSession() {
        releaseWakeLock()
        activeSession = null
        activeScriptName = ""
        activeScriptPath = null
        activeCommand = null
        activePid = -1
        isRoot = false
        isTermex = false
        isBackgroundKept = false
    }
}
