/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties.script

import android.os.AsyncTask
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.fileproperties.PathObserverLiveData
import me.zhanghai.android.files.provider.common.PosixFileAttributes
import me.zhanghai.android.files.provider.common.PosixFileModeBit
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.toModeString
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class ScriptInfoLiveData(path: Path) : PathObserverLiveData<Stateful<ScriptInfo>>(path) {
    init {
        loadValue()
        observe()
    }

    override fun loadValue() {
        value = Loading(value?.value)
        AsyncTask.THREAD_POOL_EXECUTOR.execute {
            val value = try {
                val scriptInfo = parseScript(path)
                Success(scriptInfo)
            } catch (e: Exception) {
                Failure(value?.value, e)
            }
            postValue(value)
        }
    }

    private fun parseScript(path: Path): ScriptInfo {
        val isElf = me.zhanghai.android.files.filelist.ScriptRunnerHelper.isElfBinary(path)
        if (isElf) {
            val elfReport = me.zhanghai.android.files.filelist.ElfInspector.inspect(path)
            val scriptType = application.getString(R.string.om_script_elf_compiled_format, elfReport.machineArch)
            val interpreter = elfReport.interpreter ?: application.getString(
                if (elfReport.isDynamic) R.string.om_prop_elf_dynamic else R.string.om_prop_elf_static
            )
            val lineEndingDesc = application.getString(
                if (elfReport.isPie) R.string.om_script_elf_pie_ok else R.string.om_script_elf_non_pie
            )
            val recommendedEnvironment = when {
                !elfReport.isAbiCompatible -> application.getString(R.string.om_script_env_abi_incompatible)
                elfReport.rootCalls.isNotEmpty() -> application.getString(R.string.om_script_env_root)
                else -> application.getString(R.string.om_script_env_native)
            }
            return ScriptInfo(
                scriptType = scriptType,
                shebang = null,
                interpreter = interpreter,
                lineEnding = ScriptInfo.LineEnding.EMPTY,
                lineEndingDescription = lineEndingDesc,
                lineCount = elfReport.findings.size,
                wordCount = elfReport.totalFindings,
                charCount = runCatching { java8.nio.file.Files.size(path) }.getOrDefault(0L),
                charset = application.getString(R.string.om_script_elf_charset_format, if (elfReport.is64Bit) "64-bit" else "32-bit"),
                posixPermissions = application.getString(R.string.om_script_native_executable),
                isExecutable = true,
                recommendedEnvironment = recommendedEnvironment,
                auditReport = elfReport.toScriptAuditReport()
            )
        }

        var shebang: String? = null
        var lineCount = 0
        var wordCount = 0
        var charCount = 0L
        var crlfCount = 0
        var lfCount = 0
        var crOnlyCount = 0

        // 1. Analyze line endings and character counts using raw byte stream
        try {
            path.newInputStream().use { stream ->
                val buf = ByteArray(8192)
                var bytesRead: Int
                var lastWasCr = false
                var totalBytesRead = 0L
                val maxBytes = 2 * 1024 * 1024L // 2MB max analysis to ensure instant UI and 0 RAM leak

                while (stream.read(buf).also { bytesRead = it } != -1 && totalBytesRead < maxBytes) {
                    totalBytesRead += bytesRead
                    for (i in 0 until bytesRead) {
                        val b = buf[i]
                        charCount++
                        if (b == '\r'.code.toByte()) {
                            if (lastWasCr) {
                                crOnlyCount++
                            }
                            lastWasCr = true
                        } else if (b == '\n'.code.toByte()) {
                            lineCount++
                            if (lastWasCr) {
                                crlfCount++
                            } else {
                                lfCount++
                            }
                            lastWasCr = false
                        } else {
                            if (lastWasCr) {
                                crOnlyCount++
                                lastWasCr = false
                            }
                        }
                    }
                }
                if (lastWasCr) {
                    crOnlyCount++
                }
            }
        } catch (_: Throwable) {}

        // 2. Read first lines for Shebang and word count
        try {
            path.newInputStream().use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                    val firstLine = reader.readLine()
                    if (firstLine != null) {
                        if (firstLine.startsWith("#!")) {
                            shebang = firstLine.trim()
                        }
                        val words = firstLine.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
                        wordCount += words
                        var linesRead = 1
                        while (linesRead < 1000) {
                            val line = reader.readLine() ?: break
                            linesRead++
                            val lineWords = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
                            wordCount += lineWords
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        if (lineCount == 0 && charCount > 0) {
            lineCount = 1
        }

        val lineEnding = when {
            charCount == 0L -> ScriptInfo.LineEnding.EMPTY
            crlfCount > 0 && lfCount == 0 && crOnlyCount == 0 -> ScriptInfo.LineEnding.CRLF
            lfCount > 0 && crlfCount == 0 && crOnlyCount == 0 -> ScriptInfo.LineEnding.LF
            crOnlyCount > 0 && crlfCount == 0 && lfCount == 0 -> ScriptInfo.LineEnding.CR
            crlfCount > 0 || lfCount > 0 || crOnlyCount > 0 -> ScriptInfo.LineEnding.MIXED
            else -> ScriptInfo.LineEnding.LF
        }

        val lineEndingDesc = application.getString(
            when (lineEnding) {
                ScriptInfo.LineEnding.LF -> R.string.om_script_le_lf
                ScriptInfo.LineEnding.CRLF -> R.string.om_script_le_crlf
                ScriptInfo.LineEnding.CR -> R.string.om_script_le_cr
                ScriptInfo.LineEnding.MIXED -> R.string.om_script_le_mixed
                ScriptInfo.LineEnding.EMPTY -> R.string.om_script_le_empty
            }
        )

        val fileName = try { path.fileName.toString().lowercase() } catch (_: Throwable) { "" }
        val ext = fileName.substringAfterLast('.', "")

        val interpreter = when {
            shebang != null -> {
                val clean = shebang.removePrefix("#!").trim()
                if (clean.startsWith("/usr/bin/env ")) {
                    clean.removePrefix("/usr/bin/env ").trim()
                } else {
                    clean.substringBefore(" ")
                }
            }
            ext in listOf("sh", "bash", "zsh", "ksh", "csh", "rc") -> "/system/bin/sh"
            ext in listOf("py", "pyw") -> "python3"
            ext in listOf("js", "mjs", "cjs") -> "node"
            ext == "lua" -> "lua"
            ext == "rb" -> "ruby"
            ext == "pl" -> "perl"
            ext == "php" -> "php"
            ext in listOf("bat", "cmd") -> "cmd.exe"
            else -> "/system/bin/sh"
        }

        val scriptType = application.getString(
            when {
                shebang?.contains("bash", ignoreCase = true) == true || ext == "bash" ->
                    R.string.om_script_type_bash
                shebang?.contains("zsh", ignoreCase = true) == true || ext == "zsh" ->
                    R.string.om_script_type_zsh
                shebang?.contains("python", ignoreCase = true) == true || ext in listOf("py", "pyw") ->
                    R.string.om_script_type_python
                shebang?.contains("node", ignoreCase = true) == true || ext in listOf("js", "mjs") ->
                    R.string.om_script_type_node
                shebang?.contains("lua", ignoreCase = true) == true || ext == "lua" ->
                    R.string.om_script_type_lua
                shebang?.contains("ruby", ignoreCase = true) == true || ext == "rb" ->
                    R.string.om_script_type_ruby
                shebang?.contains("perl", ignoreCase = true) == true || ext == "pl" ->
                    R.string.om_script_type_perl
                shebang?.contains("php", ignoreCase = true) == true || ext == "php" ->
                    R.string.om_script_type_php
                ext == "rc" ->
                    R.string.om_script_type_rc
                ext in listOf("bat", "cmd") ->
                    R.string.om_script_type_batch
                else ->
                    R.string.om_script_type_posix
            }
        )

        var posixModeStr: String? = null
        var isExecutable = false
        try {
            val fileObj = path.toFile()
            isExecutable = fileObj.canExecute()
        } catch (_: Throwable) {}

        try {
            val posixAttrs = java8.nio.file.Files.readAttributes(
                path,
                PosixFileAttributes::class.java,
                java8.nio.file.LinkOption.NOFOLLOW_LINKS
            )
            val mode = posixAttrs.mode()
            if (mode != null) {
                posixModeStr = mode.toModeString()
                if (mode.contains(PosixFileModeBit.OWNER_EXECUTE) ||
                    mode.contains(PosixFileModeBit.GROUP_EXECUTE) ||
                    mode.contains(PosixFileModeBit.OTHERS_EXECUTE)
                ) {
                    isExecutable = true
                }
            }
        } catch (_: Throwable) {}

        val permissionsFormatted = when {
            posixModeStr != null -> {
                val execTag = application.getString(if (isExecutable) R.string.om_script_perm_exec_tag else R.string.om_script_perm_nonexec_tag)
                "$posixModeStr$execTag"
            }
            isExecutable -> application.getString(R.string.om_script_perm_exec)
            else -> application.getString(R.string.om_script_perm_rw)
        }

        val recommendedEnvironment = application.getString(
            when {
                shebang?.contains("su", ignoreCase = true) == true || path.toString().startsWith("/data/adb") ->
                    R.string.om_script_env_root
                ext in listOf("py", "pyw", "js", "lua", "rb", "pl") ->
                    R.string.om_script_env_termex
                else ->
                    R.string.om_script_env_android_sh
            }
        )

        val auditReport = try {
            path.newInputStream().use { stream ->
                val buf = ByteArray(1024 * 1024) // 1MB max for static audit
                val read = stream.read(buf)
                if (read > 0) {
                    val content = String(buf, 0, read, StandardCharsets.UTF_8)
                    me.zhanghai.android.files.filelist.ScriptRunnerHelper.auditScriptText(content)
                } else null
            }
        } catch (_: Throwable) {
            null
        }

        return ScriptInfo(
            scriptType = scriptType,
            shebang = shebang,
            interpreter = interpreter,
            lineEnding = lineEnding,
            lineEndingDescription = lineEndingDesc,
            lineCount = lineCount,
            wordCount = wordCount,
            charCount = charCount,
            charset = "UTF-8",
            posixPermissions = permissionsFormatted,
            isExecutable = isExecutable,
            recommendedEnvironment = recommendedEnvironment,
            auditReport = auditReport
        )
    }
}
