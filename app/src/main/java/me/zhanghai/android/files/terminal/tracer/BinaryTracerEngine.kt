/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.tracer

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

object BinaryTracerEngine {

    private const val MAX_RING_BUFFER_SIZE = 150
    private fun runOnMainThread(block: () -> Unit) {
        try {
            Handler(Looper.getMainLooper()).post(block)
        } catch (_: Throwable) {
            block()
        }
    }

    private val idGen = AtomicLong(1L)
    private val ringBuffer = ConcurrentLinkedDeque<TraceEvent>()
    private val syscallCounters = ConcurrentHashMap<String, AtomicLong>()

    val totalDeletionsIntercepted = AtomicLong(0L)
    val totalDeletionsBlocked = AtomicLong(0L)
    val totalDeletionsAllowed = AtomicLong(0L)
    val totalSyscallsCount = AtomicLong(0L)
    val totalKernelProbes = AtomicLong(0L)

    val procIoReadSyscalls = AtomicLong(0L)
    val procIoWriteSyscalls = AtomicLong(0L)
    val procIoBytesRead = AtomicLong(0L)
    val procIoBytesWritten = AtomicLong(0L)

    @Volatile
    var currentActiveSyscall: ActiveSyscallInfo? = null
        private set

    data class ActiveSyscallInfo(
        val pid: Int,
        val number: Int,
        val name: String,
        val category: SyscallCategory,
        val risk: SyscallRisk,
        val timestamp: Long,
        val args: String
    )

    data class TracerStats(
        val totalDeletions: Long,
        val blockedDeletions: Long,
        val allowedDeletions: Long,
        val totalSyscalls: Long,
        val totalKernelReads: Long,
        val ioReadSyscalls: Long = 0L,
        val ioWriteSyscalls: Long = 0L,
        val ioBytesRead: Long = 0L,
        val ioBytesWritten: Long = 0L,
        val activeSyscall: ActiveSyscallInfo? = null
    )

    fun getStats(): TracerStats {
        return TracerStats(
            totalDeletions = totalDeletionsIntercepted.get(),
            blockedDeletions = totalDeletionsBlocked.get(),
            allowedDeletions = totalDeletionsAllowed.get(),
            totalSyscalls = totalSyscallsCount.get(),
            totalKernelReads = totalKernelProbes.get(),
            ioReadSyscalls = procIoReadSyscalls.get(),
            ioWriteSyscalls = procIoWriteSyscalls.get(),
            ioBytesRead = procIoBytesRead.get(),
            ioBytesWritten = procIoBytesWritten.get(),
            activeSyscall = currentActiveSyscall
        )
    }

    fun getEvents(): List<TraceEvent> {
        return ringBuffer.toList()
    }

    fun getSyscallCounters(): Map<String, Long> {
        return syscallCounters.mapValues { it.value.get() }
    }

    fun clear() {
        ledger.clear()
        totalAsked.set(0L)
        ringBuffer.clear()
        syscallCounters.clear()
        totalDeletionsIntercepted.set(0L)
        totalDeletionsBlocked.set(0L)
        totalDeletionsAllowed.set(0L)
        totalSyscallsCount.set(0L)
        totalKernelProbes.set(0L)
        procIoReadSyscalls.set(0L)
        procIoWriteSyscalls.set(0L)
        procIoBytesRead.set(0L)
        procIoBytesWritten.set(0L)
        currentActiveSyscall = null
    }

    fun recordProcSyscall(pid: Int, syscallNr: Int, rawArgs: String) {
        val info = SyscallResolver.resolve(syscallNr)
        val active = ActiveSyscallInfo(
            pid = pid,
            number = syscallNr,
            name = info.name,
            category = info.category,
            risk = info.risk,
            timestamp = System.currentTimeMillis(),
            args = rawArgs
        )
        currentActiveSyscall = active
        recordSyscallBurst(info.name, 1L, "[PID $pid] ${info.category.displayName} (${info.risk.levelName}) $rawArgs")

        // Sampled syscalls only feed the counters: a sample of unlinkat says nothing about what was
        // deleted or whether it was allowed, so it is no longer reported as a "deletion".
    }

    fun updateProcIo(readCalls: Long, writeCalls: Long, readBytes: Long, writeBytes: Long) {
        procIoReadSyscalls.set(readCalls)
        procIoWriteSyscalls.set(writeCalls)
        procIoBytesRead.set(readBytes)
        procIoBytesWritten.set(writeBytes)
        totalKernelProbes.incrementAndGet()
    }

    fun recordDeletionAttempt(
        command: String,
        target: String,
        decision: DeletionDecision = DeletionDecision.PENDING
    ): TraceEvent {
        totalDeletionsIntercepted.incrementAndGet()
        if (decision == DeletionDecision.BLOCKED) totalDeletionsBlocked.incrementAndGet()
        if (decision == DeletionDecision.ALLOWED) totalDeletionsAllowed.incrementAndGet()

        val event = TraceEvent(
            id = idGen.getAndIncrement(),
            timestamp = System.currentTimeMillis(),
            type = TraceEventType.DELETION_ATTEMPT,
            target = target,
            details = command,
            count = 1L,
            decision = decision
        )
        addEventToRingBuffer(event)
        return event
    }

    fun recordDecision(eventId: Long, decision: DeletionDecision) {
        val event = ringBuffer.firstOrNull { it.id == eventId }
        if (event != null) {
            val prev = event.decision
            event.decision = decision
            if (prev != DeletionDecision.BLOCKED && decision == DeletionDecision.BLOCKED) {
                totalDeletionsBlocked.incrementAndGet()
            } else if (prev != DeletionDecision.ALLOWED && decision == DeletionDecision.ALLOWED) {
                totalDeletionsAllowed.incrementAndGet()
            }
        }
    }

    /**
     * Ultra-optimized high-frequency syscall aggregator (O(1) memory and CPU).
     * Compresses thousands of repetitive syscalls into high-performance counters.
     */
    fun recordSyscallBurst(syscallName: String, count: Long, details: String = "") {
        totalSyscallsCount.addAndGet(count)
        val counter = syscallCounters.computeIfAbsent(syscallName) { AtomicLong(0L) }
        counter.addAndGet(count)

        // Check if existing recent event in ring buffer can be updated
        val recent = ringBuffer.lastOrNull()
        if (recent != null && recent.type == TraceEventType.SYSCALL_BURST && recent.target == syscallName) {
            recent.count += count
        } else {
            val event = TraceEvent(
                id = idGen.getAndIncrement(),
                timestamp = System.currentTimeMillis(),
                type = TraceEventType.SYSCALL_BURST,
                target = syscallName,
                details = details.ifEmpty { "High-frequency syscall batch" },
                count = count
            )
            addEventToRingBuffer(event)
        }
    }

    fun recordKernelProbe(target: String, details: String) {
        totalKernelProbes.incrementAndGet()
        val event = TraceEvent(
            id = idGen.getAndIncrement(),
            timestamp = System.currentTimeMillis(),
            type = TraceEventType.KERNEL_PROBE,
            target = target,
            details = details,
            count = 1L
        )
        addEventToRingBuffer(event)
    }

    private fun addEventToRingBuffer(event: TraceEvent) {
        ringBuffer.add(event)
        while (ringBuffer.size > MAX_RING_BUFFER_SIZE) {
            ringBuffer.pollFirst()
        }
    }

    // ---- Decision ledger -------------------------------------------------------------------
    // Guard decisions live in their own list so high-frequency syscall sampling can never push
    // them out (the old single ring lost every deletion event within seconds).

    private const val MAX_LEDGER_SIZE = 300
    private val ledger = ConcurrentLinkedDeque<TraceEvent>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private val notifyScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
    val totalAsked = AtomicLong(0L)

    fun getLedger(): List<TraceEvent> = ledger.toList()

    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** Coalesced to one main-thread callback per frame, whatever the event rate. */
    private fun notifyChanged() {
        if (listeners.isEmpty() || !notifyScheduled.compareAndSet(false, true)) return
        runOnMainThread {
            notifyScheduled.set(false)
            listeners.forEach { runCatching { it() } }
        }
    }

    fun recordGuardDecision(
        commandLine: String,
        cwd: String,
        analysis: me.zhanghai.android.files.provider.root.CommandGuard.Analysis,
        allowed: Boolean,
        reason: me.zhanghai.android.files.provider.root.CommandGuard.Reason,
        reasonText: String
    ) {
        totalDeletionsIntercepted.incrementAndGet()
        if (allowed) totalDeletionsAllowed.incrementAndGet() else totalDeletionsBlocked.incrementAndGet()
        if (reason == me.zhanghai.android.files.provider.root.CommandGuard.Reason.POLICY_ASK ||
            reason == me.zhanghai.android.files.provider.root.CommandGuard.Reason.MASS_DELETE ||
            reason == me.zhanghai.android.files.provider.root.CommandGuard.Reason.KERNEL_MODULE
        ) {
            totalAsked.incrementAndGet()
        }
        val targets = analysis.targets.joinToString("\n") { it.path }.ifEmpty { cwd }
        val event = TraceEvent(
            id = idGen.getAndIncrement(),
            timestamp = System.currentTimeMillis(),
            type = if (analysis.kind == me.zhanghai.android.files.provider.root.CommandGuard.Kind.DELETE) {
                TraceEventType.DELETION_ATTEMPT
            } else {
                TraceEventType.SECURITY_AUDIT
            },
            target = targets,
            details = commandLine,
            count = 1L,
            decision = if (allowed) DeletionDecision.ALLOWED else DeletionDecision.BLOCKED,
            reason = reasonText,
            cwd = cwd
        )
        ledger.add(event)
        while (ledger.size > MAX_LEDGER_SIZE) ledger.pollFirst()
        notifyChanged()
    }
}
