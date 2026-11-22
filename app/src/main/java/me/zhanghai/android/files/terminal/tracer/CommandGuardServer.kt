/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.tracer

import android.content.Context
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.root.CommandGuard
import me.zhanghai.android.files.provider.root.DeadManProtectionManager
import me.zhanghai.android.files.terminal.ScriptExecutionManager
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Answers the guarded shell commands of OpenManager terminals *before* they run.
 *
 * The shell functions in om_shell_rc.sh write `req.<id>` (cwd and argv, NUL separated) into
 * [guardDir] and wait for `ans.<id>` ("allow" / "block <reason>"). Requests are handled one at a
 * time on a background thread; "Ask" decisions block that thread until the user answers.
 */
object CommandGuardServer {
    private const val TAG = "OMCommandGuard"
    private const val DIR_NAME = "om_guard"
    private const val REQUEST_PREFIX = "req."
    private const val ANSWER_PREFIX = "ans."

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "OMCommandGuard") }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null

    // Must be strongly referenced or it stops delivering events once collected.
    @Volatile
    private var observer: FileObserver? = null

    /** "Allow all (this session)" from the prompt; cleared when a new session starts. */
    private val sessionAllowAll = AtomicBoolean(false)

    fun guardDir(context: Context): File = File(context.filesDir, DIR_NAME)

    /** Starts watching (idempotent) and returns the directory shells must use. */
    @Synchronized
    fun ensureStarted(context: Context): File {
        val app = context.applicationContext
        appContext = app
        val dir = guardDir(app)
        dir.mkdirs()
        if (observer == null) {
            @Suppress("DEPRECATION")
            val fileObserver = object : FileObserver(dir.absolutePath, CLOSE_WRITE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null && path.startsWith(REQUEST_PREFIX)) {
                        val file = File(dir, path)
                        executor.execute { handle(file) }
                    }
                }
            }
            fileObserver.startWatching()
            observer = fileObserver
            // Requests written before we were watching (or by a previous app process).
            executor.execute {
                dir.listFiles()?.forEach { file ->
                    when {
                        file.name.startsWith(REQUEST_PREFIX) -> handle(file)
                        file.name.startsWith(ANSWER_PREFIX) &&
                            System.currentTimeMillis() - file.lastModified() > 10 * 60_000L -> file.delete()
                    }
                }
            }
        }
        return dir
    }

    fun onNewSession() {
        sessionAllowAll.set(false)
    }

    private fun handle(request: File) {
        val context = appContext ?: return
        val id = request.name.removePrefix(REQUEST_PREFIX)
        val bytes = runCatching { request.readBytes() }.getOrNull() ?: return
        // Consuming the request tells the shell that OpenManager is alive and deciding.
        if (!request.delete() && request.exists()) return
        val fields = String(bytes, Charsets.UTF_8).split('\u0000').dropLastWhile { it.isEmpty() }
        if (fields.size < 2) {
            answer(context, id, false, context.getString(R.string.om_guard_reason_malformed))
            return
        }
        val cwd = fields[0]
        val argv = fields.drop(1)
        // Interactive terminals carry their start folder here, not a script: only files count.
        val scriptPath = ScriptExecutionManager.activeScriptPath?.takeIf { runCatching { File(it).isFile }.getOrDefault(false) }
        val tracerOn = ScriptTracerPreferences.isTracerEnabled(context, scriptPath)
        val policy = when {
            // Typing "rm" yourself (or pkg/apt maintainer scripts) must not ask every time: the
            // per-script choice applies to scripts; Dead Man and DFRoot apply to everything.
            scriptPath.isNullOrEmpty() -> CommandGuard.Policy.ALLOW
            sessionAllowAll.get() || !tracerOn -> CommandGuard.Policy.ALLOW
            else -> when (ScriptTracerPreferences.getDeletionPolicy(context, scriptPath)) {
                ScriptTracerPreferences.DeletionPolicy.ASK -> CommandGuard.Policy.ASK
                ScriptTracerPreferences.DeletionPolicy.BLOCK_ALL -> CommandGuard.Policy.BLOCK
                ScriptTracerPreferences.DeletionPolicy.ALLOW_ALL -> CommandGuard.Policy.ALLOW
            }
        }
        // Real filesystem path as well: a symlink in user storage may point into /system.
        val analysis = CommandGuard.analyze(cwd, argv) { path ->
            runCatching { File(path).canonicalPath }.getOrNull()?.takeIf { it != path }
        }
        val decision = CommandGuard.decide(
            analysis,
            policy,
            deadMan = DeadManProtectionManager.isDeadManProtectionActive(context),
            dfroot = DeadManProtectionManager.isDfrootProtectionActive(context)
        )
        val commandLine = argv.joinToString(" ") { if (it.any(Char::isWhitespace)) "'$it'" else it }

        var allowed = decision.verdict == CommandGuard.Verdict.ALLOW
        var finalReason = decision.reason
        if (decision.verdict == CommandGuard.Verdict.ASK) {
            when (GuardPrompter.ask(context, commandLine, cwd, analysis)) {
                GuardPrompter.Answer.ALLOW -> allowed = true
                GuardPrompter.Answer.ALLOW_SESSION -> {
                    sessionAllowAll.set(true)
                    allowed = true
                }
                GuardPrompter.Answer.BLOCK -> allowed = false
                GuardPrompter.Answer.NO_SCREEN -> {
                    allowed = false
                    finalReason = CommandGuard.Reason.POLICY_BLOCK
                    toast(context, context.getString(R.string.om_guard_blocked_no_screen, commandLine))
                }
            }
        }
        val reasonText = reasonText(context, finalReason, allowed)
        answer(context, id, allowed, reasonText)
        if (decision.analysis.destructive) {
            BinaryTracerEngine.recordGuardDecision(commandLine, cwd, analysis, allowed, finalReason, reasonText)
        }
        if (!allowed && (finalReason == CommandGuard.Reason.DEAD_MAN || finalReason == CommandGuard.Reason.DFROOT)) {
            val first = analysis.catastrophicTargets.firstOrNull()?.path ?: analysis.targets.firstOrNull()?.path ?: cwd
            GuardPrompter.notifyProtectionBlocked(context, first, commandLine, finalReason)
        }
        Log.i(TAG, "${if (allowed) "allow" else "block"} [$finalReason] $commandLine (cwd=$cwd)")
    }

    fun reasonText(context: Context, reason: CommandGuard.Reason, allowed: Boolean): String =
        context.getString(
            when (reason) {
                CommandGuard.Reason.DEAD_MAN -> R.string.om_guard_reason_dead_man
                CommandGuard.Reason.DFROOT -> R.string.om_guard_reason_dfroot
                CommandGuard.Reason.POLICY_BLOCK -> R.string.om_guard_reason_policy_block
                CommandGuard.Reason.POLICY_ALLOW -> R.string.om_guard_reason_policy_allow
                CommandGuard.Reason.NOT_DESTRUCTIVE -> R.string.om_guard_reason_not_destructive
                CommandGuard.Reason.POLICY_ASK ->
                    if (allowed) R.string.om_guard_reason_user_allowed else R.string.om_guard_reason_user_blocked
            }
        )

    private fun answer(context: Context, id: String, allow: Boolean, reason: String) {
        val dir = guardDir(context)
        val tmp = File(dir, ".$ANSWER_PREFIX$id")
        runCatching {
            tmp.writeText(if (allow) "allow\n" else "block $reason\n")
            // Atomic for the waiting shell: it never sees a half-written answer.
            if (!tmp.renameTo(File(dir, "$ANSWER_PREFIX$id"))) tmp.delete()
        }.onFailure { Log.w(TAG, "Cannot answer $id", it) }
    }

    private fun toast(context: Context, text: String) {
        mainHandler.post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }
}
