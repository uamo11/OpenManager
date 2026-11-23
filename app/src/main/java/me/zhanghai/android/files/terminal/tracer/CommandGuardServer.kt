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
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.root.CommandGuard
import me.zhanghai.android.files.provider.root.DeadManProtectionManager
import me.zhanghai.android.files.provider.root.KernelModuleInspector
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
        // Who wrote the request is who runs the command: a root shell creates it as uid 0.
        val ownerUid = runCatching { android.system.Os.stat(request.absolutePath).st_uid }.getOrDefault(-1)
        val bytes = runCatching { request.readBytes() }.getOrNull() ?: return
        // Consuming the request tells the shell that OpenManager is alive and deciding.
        if (!request.delete() && request.exists()) return
        var fields = String(bytes, Charsets.UTF_8).split('\u0000').dropLastWhile { it.isEmpty() }
        var simulatedRoot = false
        if (fields.firstOrNull() == "@om2") {
            // "@om2", simulated-root flag (debug builds only, for the emulator test), cwd, argv...
            simulatedRoot = BuildConfig.DEBUG && fields.getOrNull(1) == "1"
            fields = fields.drop(2)
        }
        if (fields.size < 2) {
            answer(context, id, false, context.getString(R.string.om_guard_reason_malformed))
            return
        }
        val cwd = fields[0]
        val argv = fields.drop(1)
        val privilege = if (ownerUid == 0 || simulatedRoot) CommandGuard.Privilege.ROOT else CommandGuard.Privilege.USER
        // Interactive terminals carry their start folder here, not a script: only files count.
        val scriptPath = ScriptExecutionManager.activeScriptPath?.takeIf { runCatching { File(it).isFile }.getOrDefault(false) }
        val tracerOn = ScriptTracerPreferences.isTracerEnabled(context, scriptPath)
        val policy = when {
            sessionAllowAll.get() -> CommandGuard.Policy.ALLOW
            // Typed commands, or scripts without their own setting: only the automatic protections.
            scriptPath.isNullOrEmpty() || !tracerOn ||
                !ScriptTracerPreferences.hasDeletionPolicy(context, scriptPath) -> CommandGuard.Policy.DEFAULT
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
        val deletedCount = if (analysis.destructive && analysis.kind == CommandGuard.Kind.DELETE && analysis.recursive) {
            countFiles(analysis.targets.map { it.path })
        } else null
        val moduleInfo = if (analysis.kind == CommandGuard.Kind.KERNEL_MODULE && analysis.command != "rmmod") {
            analysis.module?.takeIf { it.startsWith("/") }?.let { readModule(context, it, privilege) }
                ?.let { KernelModuleInspector.inspect(it) }
        } else null
        val moduleCheck = moduleInfo?.let {
            CommandGuard.ModuleCheck(
                KernelModuleInspector.incompatibility(it, kernelRelease(), kernelArch()),
                it.touchesPartitions
            )
        }
        val decision = CommandGuard.decide(
            analysis,
            policy,
            deadMan = DeadManProtectionManager.isDeadManProtectionActive(context),
            dfroot = DeadManProtectionManager.isDfrootProtectionActive(context),
            privilege = privilege,
            deletedCount = deletedCount,
            module = moduleCheck
        )
        val commandLine = argv.joinToString(" ") { if (it.any(Char::isWhitespace)) "'$it'" else it }

        var allowed = decision.verdict == CommandGuard.Verdict.ALLOW
        var finalReason = decision.reason
        if (decision.verdict == CommandGuard.Verdict.ASK) {
            val details = GuardPrompter.Details(
                reason = decision.reason,
                deletedCount = deletedCount,
                module = moduleInfo,
                moduleProblem = moduleCheck?.problem
            )
            when (GuardPrompter.ask(context, commandLine, cwd, analysis, details)) {
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
        val reasonText = reasonText(context, finalReason, allowed, moduleCheck?.problem)
        answer(context, id, allowed, reasonText)
        // The activity log keeps what matters: anything asked, blocked, or done as root.
        if (decision.verdict != CommandGuard.Verdict.ALLOW || privilege == CommandGuard.Privilege.ROOT) {
            BinaryTracerEngine.recordGuardDecision(commandLine, cwd, analysis, allowed, finalReason, reasonText)
        }
        if (!allowed && finalReason in PROTECTION_REASONS) {
            val first = analysis.catastrophicTargets.firstOrNull()?.path ?: analysis.targets.firstOrNull()?.path
                ?: analysis.module ?: cwd
            GuardPrompter.notifyProtectionBlocked(context, first, commandLine, finalReason)
        }
        Log.i(TAG, "${if (allowed) "allow" else "block"} [$finalReason] ${privilege.name.lowercase()} $commandLine (cwd=$cwd)")
    }

    private val PROTECTION_REASONS = setOf(
        CommandGuard.Reason.DEAD_MAN, CommandGuard.Reason.DFROOT, CommandGuard.Reason.KERNEL_MISMATCH
    )

    /**
     * Files a recursive deletion would remove, counted up to the "massive" threshold. Too slow to
     * count within the time budget means huge: reported as the threshold. Symlinks are not followed
     * (rm -r does not follow them either). Folders the app cannot read count as what it could see.
     */
    internal fun countFiles(paths: List<String>, limit: Int = CommandGuard.MASS_DELETE_THRESHOLD, budgetMs: Long = 2000L): Int {
        val deadline = System.currentTimeMillis() + budgetMs
        val stack = ArrayDeque<File>()
        paths.forEach { stack.addLast(File(it)) }
        var count = 0
        while (stack.isNotEmpty()) {
            if (count >= limit || System.currentTimeMillis() > deadline) return maxOf(count, limit)
            val file = stack.removeLast()
            val mode = runCatching { android.system.Os.lstat(file.path).st_mode }.getOrNull() ?: continue
            count++
            if (android.system.OsConstants.S_ISDIR(mode)) file.listFiles()?.forEach { stack.addLast(it) }
        }
        return count
    }

    /** First 16 MB of the module; through root when the app cannot read it (e.g. /data/local/tmp). */
    private fun readModule(context: Context, path: String, privilege: CommandGuard.Privilege): ByteArray? {
        val max = 16 * 1024 * 1024
        runCatching {
            val f = File(path)
            if (f.isFile && f.canRead()) return f.inputStream().use { it.readNBytesCompat(max) }
        }
        if (privilege != CommandGuard.Privilege.ROOT) return null
        return runCatching {
            val copy = File(context.cacheDir, "om_guard_module.ko")
            copy.delete()
            val quoted = "'" + path.replace("'", "'\\''") + "'"
            val target = "'" + copy.absolutePath + "'"
            val result = com.topjohnwu.superuser.Shell.cmd("head -c $max $quoted > $target && chmod 644 $target").exec()
            if (!result.isSuccess) return null
            copy.readBytes().also { copy.delete() }
        }.getOrNull()
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < max) {
            val r = read(buf, 0, minOf(buf.size, max - out.size()))
            if (r <= 0) break
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    private fun kernelRelease(): String = System.getProperty("os.version").orEmpty()

    private fun kernelArch(): String = System.getProperty("os.arch").orEmpty()

    fun reasonText(
        context: Context,
        reason: CommandGuard.Reason,
        allowed: Boolean,
        moduleProblem: String? = null
    ): String =
        when (reason) {
            CommandGuard.Reason.KERNEL_MISMATCH ->
                context.getString(R.string.om_guard_reason_kernel_mismatch, moduleProblem.orEmpty())
            else -> context.getString(
                when (reason) {
                    CommandGuard.Reason.DEAD_MAN -> R.string.om_guard_reason_dead_man
                    CommandGuard.Reason.DFROOT -> R.string.om_guard_reason_dfroot
                    CommandGuard.Reason.POLICY_BLOCK -> R.string.om_guard_reason_policy_block
                    CommandGuard.Reason.POLICY_ALLOW -> R.string.om_guard_reason_policy_allow
                    CommandGuard.Reason.NOT_DESTRUCTIVE -> R.string.om_guard_reason_not_destructive
                    CommandGuard.Reason.UNPRIVILEGED -> R.string.om_guard_reason_unprivileged
                    CommandGuard.Reason.POLICY_ASK, CommandGuard.Reason.MASS_DELETE, CommandGuard.Reason.KERNEL_MODULE ->
                        if (allowed) R.string.om_guard_reason_user_allowed else R.string.om_guard_reason_user_blocked
                    CommandGuard.Reason.KERNEL_MISMATCH -> R.string.om_guard_reason_dead_man
                }
            )
        }

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
