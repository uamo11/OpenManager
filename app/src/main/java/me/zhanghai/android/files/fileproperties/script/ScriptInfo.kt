/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties.script

import me.zhanghai.android.files.filelist.ScriptRunnerHelper

class ScriptInfo(
    val scriptType: String,
    val shebang: String?,
    val interpreter: String,
    val lineEnding: LineEnding,
    val lineEndingDescription: String,
    val lineCount: Int,
    val wordCount: Int,
    val charCount: Long,
    val charset: String,
    val posixPermissions: String?,
    val isExecutable: Boolean,
    val recommendedEnvironment: String,
    val auditReport: ScriptRunnerHelper.ScriptAuditReport? = null
) {
    enum class LineEnding {
        LF,
        CRLF,
        CR,
        MIXED,
        EMPTY
    }
}
