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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Terminal sessions running in OpenManager. Several can run at once (scripts, shells, SSH);
 * the terminal screen shows the *focused* one, and the `active*` properties describe it, which
 * keeps every existing caller working unchanged.
 */
object ScriptExecutionManager {

    class Entry(
        val session: TerminalSession,
        name: String,
        val scriptPath: String?,
        val command: String?,
        val isRoot: Boolean,
        val isTermex: Boolean,
        val startTimeMillis: Long
    ) {
        @Volatile var name: String = name
        @Volatile var pid: Int = -1
        @Volatile var exitCode: Int? = null

        val isRunning: Boolean
            get() = session.isRunning
    }

    private val entries = CopyOnWriteArrayList<Entry>()

    @Volatile
    private var focused: Entry? = null

    /** All sessions, oldest first. */
    val sessions: List<Entry>
        get() = entries.toList()

    val focusedEntry: Entry?
        get() = focused

    val runningCount: Int
        get() = entries.count { it.isRunning }

    fun entryFor(session: TerminalSession): Entry? = entries.firstOrNull { it.session === session }

    val activeSession: TerminalSession?
        get() = focused?.session

    val activeScriptName: String
        get() = focused?.name.orEmpty()

    val activeScriptPath: String?
        get() = focused?.scriptPath

    val activeCommand: String?
        get() = focused?.command

    /** Pid of the focused session; setting it also drives the /proc syscall sampler. */
    var activePid: Int
        get() = focused?.pid ?: -1
        set(value) {
            focused?.pid = value
            onFocusedPidChanged(value)
        }

    val isRoot: Boolean
        get() = focused?.isRoot == true

    val isTermex: Boolean
        get() = focused?.isTermex == true

    val startTimeMillis: Long
        get() = focused?.startTimeMillis ?: 0L

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

    /** The focused session is running. */
    fun isRunning(): Boolean = focused?.isRunning == true

    fun hasActiveSession(): Boolean = isRunning()

    private fun onFocusedPidChanged(pid: Int) {
        if (pid > 0) {
            notifyPidAssigned(pid)
            runCatching { me.zhanghai.android.files.terminal.tracer.SyscallReaderMonitor.startMonitoring(pid) }
        } else {
            runCatching { me.zhanghai.android.files.terminal.tracer.SyscallReaderMonitor.stopMonitoring() }
        }
    }

    /** A session reported its pid (each session has its own client callback). */
    fun onSessionPid(session: TerminalSession, pid: Int) {
        val entry = entryFor(session) ?: return
        entry.pid = pid
        if (entry === focused) onFocusedPidChanged(pid)
    }

    /** Registers a new session and focuses it. Other sessions keep running. */
    fun startExecution(
        context: Context,
        session: TerminalSession,
        scriptName: String,
        path: String? = null,
        command: String? = null,
        root: Boolean = false,
        termex: Boolean = false
    ) {
        val entry = Entry(
            session = session,
            name = scriptName.ifEmpty { "Script" },
            scriptPath = path,
            command = command,
            isRoot = root,
            isTermex = termex,
            startTimeMillis = System.currentTimeMillis()
        )
        entry.pid = session.pid
        entries.add(entry)
        focus(entry)
        lastExitCode = null
        isTerminatedByUser = false
        ScriptExecutionService.start(context.applicationContext, entry.name, entry.pid)
    }

    fun focus(entry: Entry) {
        if (entry !in entries) return
        focused = entry
        onFocusedPidChanged(if (entry.isRunning) entry.pid else -1)
    }

    fun keepInBackground(context: Context) {
        isBackgroundKept = true
        acquireWakeLock(context)
        ScriptExecutionService.updateNotification(context.applicationContext)
    }

    private val isTerminating = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Ends the focused session (and only it); focus moves to another live session if any. */
    fun terminateShell(context: Context): Boolean {
        val entry = focused ?: return false
        return terminate(context, entry)
    }

    /** Ends every session (notification "stop" action). */
    fun terminateAll(context: Context) {
        for (entry in entries.toList()) terminate(context, entry)
    }

    fun terminate(context: Context, entry: Entry): Boolean {
        if (!isTerminating.compareAndSet(false, true)) {
            return false
        }
        try {
            val pid = entry.pid
            entries.remove(entry)
            if (entry.session.isRunning) {
                runCatching { entry.session.finishIfRunning() }
            }
            if (pid > 0) {
                runCatching { Os.kill(pid, OsConstants.SIGKILL) }
                runCatching { Os.kill(-pid, OsConstants.SIGKILL) }
                if (entry.isRoot) {
                    CoroutineScope(Dispatchers.IO).launch {
                        runCatching {
                            com.topjohnwu.superuser.Shell.cmd("kill -9 $pid 2>/dev/null || true").exec()
                        }
                    }
                }
            }
            lastExitCode = -9
            if (focused === entry) {
                focused = entries.lastOrNull { it.isRunning } ?: entries.lastOrNull()
                onFocusedPidChanged(focused?.takeIf { it.isRunning }?.pid ?: -1)
            }
            if (runningCount == 0) {
                isTerminatedByUser = true
                releaseWakeLock()
                notifySessionFinished(-9)
            } else {
                ScriptExecutionService.updateNotification(context.applicationContext)
            }
            return true
        } finally {
            isTerminating.set(false)
        }
    }

    /** A session's process ended on its own. */
    fun onSessionFinished(session: TerminalSession, exitCode: Int, context: Context?) {
        val entry = entryFor(session)
        entry?.exitCode = exitCode
        if (entry === focused) {
            runCatching { me.zhanghai.android.files.terminal.tracer.SyscallReaderMonitor.stopMonitoring() }
        }
        if (isTerminating.get() || entry == null) {
            return
        }
        lastExitCode = exitCode
        if (runningCount > 0) {
            // Other sessions keep the service and its notification alive.
            if (context != null) ScriptExecutionService.updateNotification(context.applicationContext)
            return
        }
        releaseWakeLock()
        notifySessionFinished(exitCode)
        if (context != null) {
            ScriptExecutionService.onExecutionFinished(context.applicationContext, exitCode)
        }
    }

    /** Compatibility: the focused session finished. */
    fun onSessionFinished(exitCode: Int, context: Context?) {
        focused?.session?.let { onSessionFinished(it, exitCode, context) }
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

    /** Forgets finished sessions; running ones are kept. */
    fun clearSession() {
        entries.removeAll { !it.isRunning }
        if (focused != null && focused !in entries) {
            focused = entries.lastOrNull()
        }
        if (entries.isEmpty()) {
            focused = null
            releaseWakeLock()
            isBackgroundKept = false
        }
    }

    /** Removes one finished session from the list. */
    fun dismiss(entry: Entry) {
        if (entry.isRunning) return
        entries.remove(entry)
        if (focused === entry) focused = entries.lastOrNull { it.isRunning } ?: entries.lastOrNull()
    }
}
