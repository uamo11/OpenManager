/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.tracer

enum class TraceEventType {
    DELETION_ATTEMPT,
    SYSCALL_BURST,
    KERNEL_PROBE,
    SECURITY_AUDIT,
    PROCESS_SPAWN
}

enum class DeletionDecision {
    ALLOWED,
    BLOCKED,
    PENDING
}

data class TraceEvent(
    val id: Long,
    val timestamp: Long,
    val type: TraceEventType,
    val target: String,
    val details: String,
    var count: Long = 1L,
    var decision: DeletionDecision? = null
)
