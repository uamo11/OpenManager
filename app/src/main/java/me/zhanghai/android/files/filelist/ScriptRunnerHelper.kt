/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan


import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Files
import java8.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.ScriptPreferencesDialogBinding
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.fileproperties.permission.MtPermissionsHelper
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.provider.root.DeadManProtectionManager
import me.zhanghai.android.files.terminal.ScriptExecutionManager
import me.zhanghai.android.files.terminal.TerminalActivity
import me.zhanghai.android.files.terminal.tracer.ScriptTracerPreferences
import me.zhanghai.android.files.terminal.TerminalExpansionManager
import me.zhanghai.android.files.util.escapeShellArg
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.viewer.hex.HexEditorActivity
import me.zhanghai.android.files.viewer.text.TextEditorActivity
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32

object ScriptRunnerHelper {

    data class ScriptAuditReport(
        val deletions: List<String>,
        val rootCalls: List<String>,
        val networkCalls: List<String>,
        val systemPaths: List<String>
    ) {
        val totalFindings: Int
            get() = deletions.size + rootCalls.size + networkCalls.size + systemPaths.size
    }

    fun auditScriptText(content: String): ScriptAuditReport {
        val lines = content.lines()
        val deletions = mutableListOf<String>()
        val rootCalls = mutableListOf<String>()
        val networkCalls = mutableListOf<String>()
        val systemPaths = mutableListOf<String>()

        lines.forEachIndexed { idx, rawLine ->
            val line = rawLine.trim()
            val lineNum = idx + 1
            if (line.startsWith("#")) return@forEachIndexed

            // Split line into chained sub-commands (;, &&, ||, |, \n) for granular inspection
            val subCommands = line.split(Regex("[;&|]+")).map { it.trim() }.filter { it.isNotEmpty() }
            val targetsToCheck = if (subCommands.isNotEmpty()) subCommands + line else listOf(line)

            val hasDeletion = targetsToCheck.any {
                Regex("""\b(rm|rmdir|unlink|truncate|shred|wipe|toybox\s+rm|busybox\s+rm)\b""").containsMatchIn(it)
            }
            if (hasDeletion) {
                deletions.add("Line $lineNum: $line")
            }

            val hasBlockOrFormat = targetsToCheck.any {
                Regex("""\bdd\s+.*of=|\b(mkfs|mke2fs|fdisk|sfdisk|blkdiscard|wipefs|make_ext4fs)\b""").containsMatchIn(it)
            }
            if (hasBlockOrFormat) {
                deletions.add("Line $lineNum (format/block): $line")
            }

            val hasRoot = targetsToCheck.any {
                Regex("""\b(su|sudo|magisk|ksud|setenforce|chown\s+root)\b""").containsMatchIn(it)
            }
            if (hasRoot) {
                rootCalls.add("Line $lineNum: $line")
            }

            val hasNetwork = targetsToCheck.any {
                Regex("""\b(curl|wget|nc|netcat|ncat|socat|ssh|scp|rsync|ping|traceroute|nmap)\b""").containsMatchIn(it) ||
                    it.contains("/dev/tcp") || it.contains("/dev/udp")
            }
            if (hasNetwork) {
                networkCalls.add("Line $lineNum: $line")
            }

            val hasSystemOrSymlink = targetsToCheck.any {
                Regex("""(/system|/vendor|/product|/apex|/dev/block|/data/data|/data/user|/data/local/tmp|\bpm\s+uninstall|\breboot|\bsys_reboot|\bshutdown|\bpoweroff|\bhalt|\bchroot\b|\bpivot_root\b|\bmount\s+.*remount|\bln\s+(-[a-zA-Z0-9_-]+\s+)*.*)""").containsMatchIn(it)
            }
            if (hasSystemOrSymlink) {
                systemPaths.add("Line $lineNum: $line")
            }
        }

        return ScriptAuditReport(deletions, rootCalls, networkCalls, systemPaths)
    }

    fun showScriptAuditDetailsDialog(
        activity: Activity,
        path: Path,
        report: ScriptAuditReport,
        scriptContent: String,
        onExecuteDirect: (() -> Unit)? = null
    ) {
        val density = activity.resources.displayMetrics.density
        val scroll = android.widget.ScrollView(activity)
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val tvSummary = TextView(activity).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            text = if (report.totalFindings == 0) {
                activity.getString(R.string.om_script_runner_audit_safe)
            } else {
                activity.getString(R.string.om_script_runner_audit_attention_format, report.totalFindings)
            }
        }
        container.addView(tvSummary)

        fun addSection(title: String, items: List<String>, colorHex: String) {
            if (items.isEmpty()) return
            val tvSectionTitle = TextView(activity).apply {
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(colorHex))
                val topMargin = (14 * density).toInt()
                setPadding(0, topMargin, 0, (4 * density).toInt())
                text = title
            }
            container.addView(tvSectionTitle)

            for (item in items) {
                val tvItem = TextView(activity).apply {
                    textSize = 12f
                    setTypeface(android.graphics.Typeface.MONOSPACE)
                    setTextColor(Color.parseColor("#CCCCCC"))
                    setBackgroundColor(Color.parseColor("#222222"))
                    val p = (6 * density).toInt()
                    setPadding(p, p, p, p)
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.topMargin = (2 * density).toInt()
                    layoutParams = lp
                    text = item
                }
                container.addView(tvItem)
            }
        }

        addSection(activity.getString(R.string.om_script_runner_section_deletions_format, report.deletions.size), report.deletions, "#FF8A80")
        addSection(activity.getString(R.string.om_script_runner_section_root_format, report.rootCalls.size), report.rootCalls, "#FFD54F")
        addSection(activity.getString(R.string.om_script_runner_section_network_format, report.networkCalls.size), report.networkCalls, "#80D8FF")
        addSection(activity.getString(R.string.om_script_runner_section_system_format, report.systemPaths.size), report.systemPaths, "#B388FF")

        val btnViewCode = Button(activity).apply {
            text = activity.getString(R.string.om_script_runner_view_full_code)
            setTextColor(Color.parseColor("#64B5F6"))
            setBackgroundColor(Color.TRANSPARENT)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (14 * density).toInt()
            layoutParams = lp
            setOnClickListener {
                showFullCodeDialog(activity, path, scriptContent, report)
            }
        }
        container.addView(btnViewCode)

        val btnTracerPolicy = Button(activity).apply {
            text = activity.getString(R.string.om_script_runner_tracer_policy)
            setTextColor(Color.parseColor("#FFB74D"))
            setBackgroundColor(Color.TRANSPARENT)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (4 * density).toInt()
            layoutParams = lp
            setOnClickListener {
                val scriptPathStr = if (path.isLinuxPath) path.toFile().absolutePath else path.toString()
                (activity as? androidx.fragment.app.FragmentActivity)?.let {
                    me.zhanghai.android.files.terminal.tracer.ScriptAuditDialogFragment.show(it, scriptPathStr)
                }
            }
        }
        container.addView(btnTracerPolicy)

        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.om_script_runner_audit_title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)

        if (onExecuteDirect != null) {
            builder.setNeutralButton(R.string.om_script_runner_run_script) { _, _ ->
                onExecuteDirect()
            }
        }

        builder.show()
    }

    fun showFullCodeDialog(
        activity: Activity,
        title: String,
        content: String,
        report: ScriptAuditReport? = null
    ) {
        val density = activity.resources.displayMetrics.density
        val scroll = android.widget.ScrollView(activity)
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (14 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val flaggedLines = mutableSetOf<Int>()
        report?.let { r ->
            val allFindings = r.deletions + r.rootCalls + r.networkCalls + r.systemPaths
            for (f in allFindings) {
                val match = Regex("""(?:Line|Línea)\s+(\d+)""").find(f)
                match?.groupValues?.get(1)?.toIntOrNull()?.let { flaggedLines.add(it) }
            }
        }

        val lines = content.lines()
        val ssb = SpannableStringBuilder()
        lines.forEachIndexed { idx, line ->
            val lineNum = idx + 1
            val isFlagged = lineNum in flaggedLines
            val prefix = if (isFlagged) String.format("%3d ⚠️ | ", lineNum) else String.format("%3d    | ", lineNum)
            val fullLine = prefix + line + "\n"
            val start = ssb.length
            ssb.append(fullLine)
            val end = ssb.length

            if (isFlagged) {
                ssb.setSpan(ForegroundColorSpan(Color.parseColor("#FFD54F")), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.setSpan(BackgroundColorSpan(Color.parseColor("#3E2723")), start, end - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                ssb.setSpan(ForegroundColorSpan(Color.parseColor("#90A4AE")), start, start + prefix.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.setSpan(ForegroundColorSpan(Color.parseColor("#E0E0E0")), start + prefix.length, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        val tvCode = TextView(activity).apply {
            textSize = 12f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            text = ssb
        }
        container.addView(tvCode)

        val btnCopy = Button(activity).apply {
            text = activity.getString(R.string.om_script_runner_copy_code)
            setTextColor(Color.parseColor("#64B5F6"))
            setBackgroundColor(Color.TRANSPARENT)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (12 * density).toInt()
            layoutParams = lp
            setOnClickListener {
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("Script Content", content))
                Toast.makeText(activity, R.string.om_script_runner_code_copied, Toast.LENGTH_SHORT).show()
            }
        }
        container.addView(btnCopy)
        scroll.addView(container)

        val displayTitle = title + if (flaggedLines.isNotEmpty()) activity.getString(R.string.om_script_runner_flagged_lines_suffix_format, flaggedLines.size) else ""
        MaterialAlertDialogBuilder(activity)
            .setTitle(displayTitle)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    fun showFullCodeDialog(
        activity: Activity,
        path: Path,
        content: String,
        report: ScriptAuditReport? = null
    ) {
        showFullCodeDialog(activity, path.fileName?.toString() ?: "Script", content, report)
    }

    fun showPreExecutionAuditWarningDialog(
        activity: Activity,
        path: Path,
        report: ScriptAuditReport,
        scriptContent: String,
        onProceed: () -> Unit
    ) {
        val summaryItems = mutableListOf<String>()
        if (report.deletions.isNotEmpty()) summaryItems.add(activity.getString(R.string.om_script_runner_pre_deletions_format, report.deletions.size))
        if (report.rootCalls.isNotEmpty()) summaryItems.add(activity.getString(R.string.om_script_runner_pre_root_format, report.rootCalls.size))
        if (report.networkCalls.isNotEmpty()) summaryItems.add(activity.getString(R.string.om_script_runner_pre_network_format, report.networkCalls.size))
        if (report.systemPaths.isNotEmpty()) summaryItems.add(activity.getString(R.string.om_script_runner_pre_system_format, report.systemPaths.size))

        val message = activity.getString(
            R.string.om_script_runner_pre_message_format,
            report.totalFindings,
            summaryItems.joinToString("\n")
        )

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.om_script_runner_pre_title)
            .setMessage(message)
            .setPositiveButton(R.string.om_script_runner_execute) { _, _ ->
                onProceed()
            }
            .setNegativeButton(R.string.om_script_runner_audit_lines) { _, _ ->
                showScriptAuditDetailsDialog(activity, path, report, scriptContent, onExecuteDirect = onProceed)
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private const val PREFS_NAME = "script_exec_prefs"
    private const val KEY_EXEC_ENV = "exec_env"
    private const val KEY_EXEC_ROOT = "exec_root"

    private val NON_EXECUTABLE_EXTENSIONS = setOf(
        "txt", "text", "log", "md", "markdown", "json", "xml", "html", "htm", "css", "csv",
        "properties", "ini", "conf", "cfg", "yaml", "yml", "toml",
        "java", "kt", "kts", "smali", "c", "cpp", "h", "hpp", "cs", "dart", "swift", "go", "rs",
        "py", "js", "ts", "lua", "sql", "gradle", "groovy", "pdf", "doc", "docx",
        "png", "jpg", "jpeg", "webp", "gif", "svg", "bmp",
        "mp3", "m4a", "wav", "flac", "ogg", "aac",
        "mp4", "mkv", "avi", "webm", "mov"
    )

    fun isNonExecutableDocument(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext.isNotEmpty() && ext in NON_EXECUTABLE_EXTENSIONS
    }

    /**
     * Checks if a file is an executable binary (ELF) or a shell script.
     * Guaranteed never to match .txt or regular document/media files,
     * and always returns false for files inside archives (virtual filesystems).
     */
    fun isExecutableOrScript(file: FileItem): Boolean {
        if (file.path.isArchivePath) {
            return false
        }
        val fileName = file.path.fileName?.toString() ?: file.name
        if (isNonExecutableDocument(fileName)) {
            return false
        }
        if (fileName.endsWith(".sh", ignoreCase = true) ||
            fileName.endsWith(".bash", ignoreCase = true) ||
            fileName.endsWith(".zsh", ignoreCase = true) ||
            fileName.endsWith(".bin", ignoreCase = true) ||
            fileName.endsWith(".run", ignoreCase = true)) {
            return true
        }

        return isElfOrShebang(file.path)
    }

    fun isExecutableOrScript(path: Path): Boolean {
        if (path.isArchivePath) {
            return false
        }
        val fileName = path.fileName?.toString() ?: ""
        if (isNonExecutableDocument(fileName)) {
            return false
        }
        if (fileName.endsWith(".sh", ignoreCase = true) ||
            fileName.endsWith(".bash", ignoreCase = true) ||
            fileName.endsWith(".zsh", ignoreCase = true) ||
            fileName.endsWith(".bin", ignoreCase = true) ||
            fileName.endsWith(".run", ignoreCase = true)) {
            return true
        }
        return isElfOrShebang(path)
    }

    fun isElfOrShebang(path: Path): Boolean {
        if (path.isArchivePath) {
            return false
        }
        return try {
            path.newInputStream().use { stream ->
                val b0 = stream.read()
                val b1 = stream.read()
                val b2 = stream.read()
                val b3 = stream.read()
                // ELF magic "\x7FELF"
                if (b0 == 0x7F && b1 == 'E'.code && b2 == 'L'.code && b3 == 'F'.code) {
                    return@use true
                }
                // Shebang "#!"
                if (b0 == '#'.code && b1 == '!'.code) {
                    return@use true
                }
                false
            }
        } catch (_: Throwable) {
            try {
                if (path.isLinuxPath) path.toFile().canExecute() else false
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun isElfBinary(path: Path): Boolean {
        return try {
            path.newInputStream().use { stream ->
                val b0 = stream.read()
                val b1 = stream.read()
                val b2 = stream.read()
                val b3 = stream.read()
                b0 == 0x7F && b1 == 'E'.code && b2 == 'L'.code && b3 == 'F'.code
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Displays the MT Manager APK-style Info & Action Dialog when clicking a script or ELF binary.
     * Shows basic info + interactive permissions (e.g. "777 (rwxrwxrwx)") at the top,
     * and Preferences / Hash / Edit / Execute buttons at the bottom.
     */
    fun showScriptActionDialog(activity: Activity, fileItem: FileItem) {
        showScriptActionDialogInternal(activity, fileItem.path, fileItem)
    }

    fun showScriptActionDialog(activity: Activity, path: Path) {
        showScriptActionDialogInternal(activity, path, null)
    }

    private fun showScriptActionDialogInternal(
        activity: Activity,
        path: Path,
        fileItem: FileItem?
    ) {
        // Scripts or binaries inside virtual archive systems cannot be executed directly;
        // open directly in TextEditorActivity matching MT Manager behavior.
        if (path.isArchivePath) {
            try {
                val intent = Intent(activity, TextEditorActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    extraPath = path
                }
                activity.startActivity(intent)
            } catch (t: Throwable) {
                t.printStackTrace()
            }
            return
        }

        try {
            showScriptActionDialogUi(activity, path, fileItem)
        } catch (t: Throwable) {
            t.printStackTrace()
            try {
                val intent = Intent(activity, TextEditorActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    extraPath = path
                }
                activity.startActivity(intent)
            } catch (_: Throwable) {
                Toast.makeText(activity, "Error: ${t.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showScriptActionDialogUi(
        activity: Activity,
        path: Path,
        fileItem: FileItem?
    ) {
        var currentAuditReport: ScriptAuditReport? = null
        var currentScriptText = ""
        var dialog: androidx.appcompat.app.AlertDialog? = null

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_mt_file_properties, null)

        val ivIcon = view.findViewById<ImageView>(R.id.ivPropIcon)
        val tvTitle = view.findViewById<TextView>(R.id.tvPropTitle)
        val tvSubtitle = view.findViewById<TextView>(R.id.tvPropSubtitle)
        val table = view.findViewById<TableLayout>(R.id.tableProperties)

        val btnAction1 = view.findViewById<Button>(R.id.btnPropAction1)
        val btnAction2 = view.findViewById<Button>(R.id.btnPropAction2)
        val btnAction3 = view.findViewById<Button>(R.id.btnPropAction3)
        val btnAction4 = view.findViewById<Button>(R.id.btnPropAction4)

        val fileName = try { path.fileName?.toString() ?: path.toString() } catch (_: Throwable) { "script.sh" }
        val isElf = isElfBinary(path)

        ivIcon.setImageResource(R.drawable.file_sh_icon)
        tvTitle.text = fileName
        tvSubtitle.text = activity.getString(if (isElf) R.string.om_script_runner_subtitle_elf else R.string.om_script_runner_subtitle_sh)

        fun copyText(label: String, value: CharSequence?) {
            val str = value?.toString() ?: return
            if (str.isNotBlank() && str != "-") {
                val cb = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cb?.setPrimaryClip(ClipData.newPlainText(label, str))
                Toast.makeText(activity, activity.getString(R.string.om_script_runner_copied_label_format, label), Toast.LENGTH_SHORT).show()
            }
        }

        tvTitle.setOnClickListener { copyText(activity.getString(R.string.om_script_runner_label_name), tvTitle.text) }

        val density = activity.resources.displayMetrics.density
        fun addRow(
            label: String,
            initialValue: String,
            valueColor: Int = Color.parseColor("#E0E0E0"),
            boldValue: Boolean = false,
            singleLineMiddle: Boolean = false,
            onClick: ((TextView) -> Unit)? = null
        ): Pair<TableRow, TextView> {
            val row = TableRow(activity).apply {
                val vPad = (4 * density).toInt()
                setPadding(0, vPad, 0, vPad)
                gravity = Gravity.CENTER_VERTICAL
            }
            val tvLabel = TextView(activity).apply {
                layoutParams = TableRow.LayoutParams((115 * density).toInt(), TableRow.LayoutParams.WRAP_CONTENT)
                text = label
                setTextColor(Color.parseColor("#9E9E9E"))
                textSize = 13f
            }
            val tvVal = TextView(activity).apply {
                layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
                text = initialValue
                setTextColor(valueColor)
                textSize = 13f
                if (boldValue) {
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
                if (singleLineMiddle) {
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                }
            }
            if (onClick != null) {
                row.isClickable = true
                row.isFocusable = true
                val clickListener = View.OnClickListener { onClick(tvVal) }
                row.setOnClickListener(clickListener)
                tvVal.setOnClickListener(clickListener)
            } else {
                val copyListener = View.OnClickListener { copyText(label, tvVal.text) }
                row.setOnClickListener(copyListener)
                tvVal.setOnClickListener(copyListener)
            }
            row.addView(tvLabel)
            row.addView(tvVal)
            table.addView(row)
            return row to tvVal
        }

        val fullPathStr = try {
            if (path.isLinuxPath) path.toFile().absolutePath else path.toString()
        } catch (_: Throwable) {
            path.toString()
        }
        addRow(activity.getString(R.string.om_script_runner_label_path), fullPathStr, singleLineMiddle = true)

        val localFile = try {
            if (path.isLinuxPath) path.toFile() else File(path.toString())
        } catch (_: Throwable) {
            null
        }

        val byteSize = try {
            fileItem?.attributes?.size() ?: localFile?.length() ?: Files.size(path)
        } catch (_: Throwable) {
            0L
        }
        val formattedSize = try {
            "${byteSize.asFileSize().formatHumanReadable(activity)} ($byteSize B)"
        } catch (_: Throwable) {
            "$byteSize B"
        }
        val (_, tvSizeVal) = addRow(activity.getString(R.string.om_script_runner_label_size), formattedSize)

        val lastModMillis = try {
            fileItem?.attributes?.lastModifiedTime()?.toMillis()
                ?: localFile?.lastModified()
                ?: Files.getLastModifiedTime(path).toMillis()
        } catch (_: Throwable) {
            0L
        }
        val dateStr = if (lastModMillis > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(lastModMillis))
        } else {
            "-"
        }
        addRow(activity.getString(R.string.om_script_runner_label_modified), dateStr)

        // Interactive Permissions Row ("Permisos: 777 (rwxrwxrwx)")
        val permInfo = MtPermissionsHelper.getPermissions(activity, path, fileItem)
        addRow(
            label = activity.getString(R.string.om_script_runner_label_permissions),
            initialValue = "${permInfo.displayString}  ✎",
            valueColor = Color.parseColor("#64B5F6"),
            boldValue = true
        ) { tvPerm ->
            MtPermissionsHelper.showPermissionsDialog(
                context = activity,
                path = path,
                fileItem = fileItem
            ) { updated ->
                tvPerm.text = "${updated.displayString}  ✎"
            }
        }

        if (!permInfo.ownerDisplay.isNullOrBlank()) {
            val ownerGroup = if (!permInfo.groupDisplay.isNullOrBlank()) {
                "${permInfo.ownerDisplay} / ${permInfo.groupDisplay}"
            } else {
                permInfo.ownerDisplay
            }
            addRow(activity.getString(R.string.om_script_runner_label_owner), ownerGroup)
        }

        val (_, tvInterpreter) = addRow(
            activity.getString(R.string.om_script_runner_label_interpreter),
            if (isElf) activity.getString(R.string.om_script_runner_interpreter_elf) else "/system/bin/sh"
        )
        val (rowLineEnding, tvLineEnding) = addRow(
            activity.getString(R.string.om_script_runner_label_line_format),
            activity.getString(if (isElf) R.string.om_script_runner_value_binary else R.string.om_script_runner_value_analyzing)
        )
        val (rowLines, tvLines) = addRow(
            activity.getString(R.string.om_script_runner_label_content),
            activity.getString(if (isElf) R.string.om_script_runner_value_compiled_exec else R.string.om_script_runner_value_analyzing)
        )
        val (rowAudit, tvAudit) = addRow(
            activity.getString(R.string.om_script_runner_label_audit),
            activity.getString(if (isElf) R.string.om_script_runner_audit_analyzing_elf else R.string.om_script_runner_audit_analyzing_script),
            valueColor = Color.parseColor("#90CAF9"),
            boldValue = true
        )
        if (isElf) {
            rowLineEnding.visibility = View.GONE
            CoroutineScope(Dispatchers.IO).launch {
                val elfReport = ElfInspector.inspect(path)
                val scriptReport = elfReport.toScriptAuditReport()
                val summaryText = buildString {
                    appendLine("/* C/C++ COMPILED ELF BINARY AUDIT REPORT */")
                    appendLine("Architecture: ${elfReport.machineArch}")
                    appendLine("Bitness: ${if (elfReport.is64Bit) "64-bit" else "32-bit"}")
                    appendLine("Endianness: ${if (elfReport.isLittleEndian) "Little-Endian (LSB)" else "Big-Endian (MSB)"}")
                    appendLine("PIE (Position Independent): ${if (elfReport.isPie) "Yes (Android 5.0+ Compatible)" else "No (Fixed - May fail on Android)"}")
                    appendLine("Linker / Interpreter: ${elfReport.interpreter ?: if (elfReport.isDynamic) "Dynamic" else "Static"}")
                    appendLine("ABI Compatibility: ${if (elfReport.isAbiCompatible) "Compatible with this device" else "Incompatible"}")
                    appendLine()
                    appendLine("/* DETECTED SYSCALLS & SYMBOLS (${elfReport.totalFindings}) */")
                    for (f in elfReport.findings) {
                        appendLine("- [${f.category.displayName}] ${f.symbol}: ${f.description} (Risk: ${f.risk.levelName})")
                    }
                }
                withContext(Dispatchers.Main) {
                    currentAuditReport = scriptReport
                    currentScriptText = summaryText

                    tvInterpreter.text = elfReport.interpreter ?: activity.getString(if (elfReport.isDynamic) R.string.om_script_runner_elf_dynamic else R.string.om_script_runner_elf_static)
                    tvSubtitle.text = activity.getString(R.string.om_script_runner_elf_subtitle_format, elfReport.machineArch)
                    ivIcon.setImageResource(if (elfReport.is64Bit) R.drawable.ic_exec_elf_64 else R.drawable.ic_exec_elf_32)
                    tvLines.text = "${elfReport.machineArch} · ${if (elfReport.isPie) "PIE ✓" else "Non-PIE ⚠️"}"
                    rowLines.visibility = View.VISIBLE

                    if (elfReport.totalFindings == 0) {
                        tvAudit.text = activity.getString(R.string.om_script_runner_elf_safe)
                        tvAudit.setTextColor(Color.parseColor("#81C784"))
                    } else {
                        tvAudit.text = activity.getString(R.string.om_script_runner_elf_risks_format, elfReport.totalFindings)
                        tvAudit.setTextColor(Color.parseColor("#FFB74D"))
                    }

                    val auditClick = View.OnClickListener {
                        showScriptAuditDetailsDialog(activity, path, scriptReport, summaryText, onExecuteDirect = {
                            dialog?.dismiss()
                            executeScriptOrBinary(activity, path)
                        })
                    }
                    rowAudit.setOnClickListener(auditClick)
                    tvAudit.setOnClickListener(auditClick)
                }
            }
        }

        // Asynchronously inspect shebang, line count, and line endings for text scripts
        if (!isElf) {
            CoroutineScope(Dispatchers.IO).launch {
                var shebang: String? = null
                var lineCount = 0
                var hasCrlf = false
                try {
                    path.newInputStream().use { stream ->
                        val bytes = stream.readBytes()
                        for (i in bytes.indices) {
                            if (bytes[i] == '\n'.code.toByte()) {
                                lineCount++
                                if (i > 0 && bytes[i - 1] == '\r'.code.toByte()) {
                                    hasCrlf = true
                                }
                            }
                        }
                        if (lineCount == 0 && bytes.isNotEmpty()) lineCount = 1
                    }
                    path.newInputStream().use { stream ->
                        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                            val first = reader.readLine()
                            if (first != null && first.startsWith("#!")) {
                                shebang = first.trim()
                            }
                        }
                    }
                } catch (_: Throwable) {}

                var fullScriptContent = ""
                try {
                    path.newInputStream().use { stream ->
                        fullScriptContent = String(stream.readBytes(), StandardCharsets.UTF_8)
                    }
                } catch (_: Throwable) {}
                val auditReport = auditScriptText(fullScriptContent)

                withContext(Dispatchers.Main) {
                    currentAuditReport = auditReport
                    currentScriptText = fullScriptContent

                    if (!shebang.isNullOrBlank()) {
                        tvInterpreter.text = shebang
                        tvSubtitle.text = "Script (${shebang!!.removePrefix("#!").trim()})"
                    }
                    tvLines.text = activity.getString(R.string.om_script_runner_lines_utf8_format, lineCount)

                    if (auditReport.totalFindings == 0) {
                        tvAudit.text = activity.getString(R.string.om_script_runner_script_safe)
                        tvAudit.setTextColor(Color.parseColor("#81C784"))
                    } else {
                        tvAudit.text = activity.getString(R.string.om_script_runner_script_warnings_format, auditReport.totalFindings)
                        tvAudit.setTextColor(Color.parseColor("#FFB74D"))
                    }
                    val auditClick = View.OnClickListener {
                        showScriptAuditDetailsDialog(activity, path, auditReport, fullScriptContent, onExecuteDirect = {
                            dialog?.dismiss()
                            executeScriptOrBinary(activity, path)
                        })
                    }
                    rowAudit.setOnClickListener(auditClick)
                    tvAudit.setOnClickListener(auditClick)

                    if (hasCrlf) {
                        tvLineEnding.text = activity.getString(R.string.om_script_runner_crlf_tap_convert)
                        tvLineEnding.setTextColor(Color.parseColor("#FFB74D"))
                        val convertClick = View.OnClickListener {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val raw = Files.readAllBytes(path)
                                    val converted = String(raw, StandardCharsets.UTF_8)
                                        .replace("\r\n", "\n")
                                        .replace("\r", "\n")
                                        .toByteArray(StandardCharsets.UTF_8)
                                    Files.write(path, converted)
                                    withContext(Dispatchers.Main) {
                                        tvLineEnding.text = "LF (Unix)"
                                        tvLineEnding.setTextColor(Color.parseColor("#81C784"))
                                        tvSizeVal.text = "${converted.size.toLong().asFileSize().formatHumanReadable(activity)} (${converted.size} B)"
                                        Toast.makeText(activity, R.string.file_properties_script_convert_success, Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(activity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                        rowLineEnding.setOnClickListener(convertClick)
                        tvLineEnding.setOnClickListener(convertClick)
                    } else {
                        tvLineEnding.text = "LF (Unix)"
                        tvLineEnding.setTextColor(Color.parseColor("#81C784"))
                    }
                }
            }
        }

        val createdDialog = MaterialAlertDialogBuilder(activity)
            .setView(view)
            .create()
        dialog = createdDialog
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor("#303030")))

        // Button 1: PREFERENCIAS
        btnAction1.visibility = View.VISIBLE
        btnAction1.text = activity.getString(R.string.script_action_preferences)
        btnAction1.setTextColor(Color.parseColor("#2196F3"))
        btnAction1.setOnClickListener {
            showPreferencesDialog(activity, path)
        }

        // Button 2: HASH (Calculates MD5 / SHA-1 / SHA-256 / CRC32 inline)
        var hashesCalculated = false
        btnAction2.visibility = View.VISIBLE
        btnAction2.text = "HASH"
        btnAction2.setTextColor(Color.parseColor("#FF9800"))
        btnAction2.setOnClickListener {
            if (hashesCalculated) return@setOnClickListener
            hashesCalculated = true
            btnAction2.isEnabled = false
            btnAction2.alpha = 0.5f
            val (_, tvCrc) = addRow("CRC32", "Calculando...")
            val (_, tvMd5) = addRow("MD5", "Calculando...")
            val (_, tvSha1) = addRow("SHA-1", "Calculando...")
            val (_, tvSha256) = addRow("SHA-256", "Calculando...")

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val crc = CRC32()
                    val md5 = MessageDigest.getInstance("MD5")
                    val sha1 = MessageDigest.getInstance("SHA-1")
                    val sha256 = MessageDigest.getInstance("SHA-256")
                    path.newInputStream().use { inp ->
                        val buf = ByteArray(16384)
                        var n: Int
                        while (inp.read(buf).also { n = it } != -1) {
                            crc.update(buf, 0, n)
                            md5.update(buf, 0, n)
                            sha1.update(buf, 0, n)
                            sha256.update(buf, 0, n)
                        }
                    }
                    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
                    val crcHex = "%08x".format(crc.value)
                    val md5Hex = md5.digest().toHex()
                    val sha1Hex = sha1.digest().toHex()
                    val sha256Hex = sha256.digest().toHex()
                    withContext(Dispatchers.Main) {
                        tvCrc.text = crcHex
                        tvMd5.text = md5Hex
                        tvSha1.text = sha1Hex
                        tvSha256.text = sha256Hex
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        tvMd5.text = "Error: ${e.message}"
                    }
                }
            }
        }

        // Button 3: EDITAR
        btnAction3.visibility = View.VISIBLE
        btnAction3.text = activity.getString(R.string.script_action_edit)
        btnAction3.setTextColor(Color.parseColor("#FFFFFF"))
        btnAction3.setOnClickListener {
            dialog?.dismiss()
            editScriptOrBinary(activity, path)
        }

        // Button 4: EJECUTAR
        btnAction4.visibility = View.VISIBLE
        btnAction4.text = activity.getString(R.string.script_action_execute)
        btnAction4.setTextColor(Color.parseColor("#4CAF50"))
        btnAction4.setOnClickListener {
            val report = currentAuditReport
            if (report != null && report.totalFindings > 0) {
                showPreExecutionAuditWarningDialog(
                    activity = activity,
                    path = path,
                    report = report,
                    scriptContent = currentScriptText
                ) {
                    dialog?.dismiss()
                    executeScriptOrBinary(activity, path)
                }
            } else {
                dialog?.dismiss()
                executeScriptOrBinary(activity, path)
            }
        }

        dialog?.show()
    }

    fun showPreferencesDialog(activity: Activity, path: Path? = null) {
        val sp = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaultEnv = if (TerminalExpansionManager.isTermexInstalled(activity)) "termex" else "system"
        val savedEnv = sp.getString(KEY_EXEC_ENV, defaultEnv) ?: defaultEnv
        val isRoot = TerminalExpansionManager.isRootAvailable()
        val savedRoot = if (isRoot) sp.getBoolean(KEY_EXEC_ROOT, false) else false

        val scriptPath = path?.let { if (it.isLinuxPath) it.toFile().absolutePath else it.toString() }
        val binding = ScriptPreferencesDialogBinding.inflate(LayoutInflater.from(activity))
        binding.checkTracer.isChecked = ScriptTracerPreferences.isTracerEnabled(activity, scriptPath)
        if (savedEnv == "termex" && TerminalExpansionManager.isTermexInstalled(activity)) {
            binding.radioTermex.isChecked = true
        } else {
            binding.radioSystem.isChecked = true
        }

        binding.radioTermex.setOnClickListener {
            if (!TerminalExpansionManager.isTermexInstalled(activity)) {
                TerminalExpansionManager.showExpansionInstallerDialog(activity) {
                    if (TerminalExpansionManager.isTermexInstalled(activity)) {
                        binding.radioTermex.isChecked = true
                    } else {
                        binding.radioSystem.isChecked = true
                    }
                }
            }
        }

        // Hide root option completely when no root is available on the device
        if (isRoot) {
            binding.checkRoot.visibility = android.view.View.VISIBLE
            binding.checkRoot.isChecked = savedRoot
        } else {
            binding.checkRoot.visibility = android.view.View.GONE
            binding.checkRoot.isChecked = false
        }

        MaterialAlertDialogBuilder(activity)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val chosenEnv = if (binding.radioTermex.isChecked) "termex" else "system"
                val chosenRoot = if (isRoot) binding.checkRoot.isChecked else false
                sp.edit()
                    .putString(KEY_EXEC_ENV, chosenEnv)
                    .putBoolean(KEY_EXEC_ROOT, chosenRoot)
                    .apply()
                ScriptTracerPreferences.setTracerEnabled(activity, scriptPath, binding.checkTracer.isChecked)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editScriptOrBinary(activity: Activity, path: Path) {
        if (isElfBinary(path)) {
            HexEditorActivity.start(activity, path)
        } else {
            val intent = Intent(activity, TextEditorActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                extraPath = path
            }
            activity.startActivity(intent)
        }
    }

    fun checkElfCompatibility(path: Path): Pair<Boolean, String> {
        return try {
            val report = ElfInspector.inspect(path)
            report.isAbiCompatible to report.machineArch
        } catch (_: Throwable) {
            true to "ELF"
        }
    }

    private fun checkCrlfAndConvert(activity: Activity, path: Path) {
        if (path.isArchivePath) return
        try {
            var hasCrlf = false
            path.newInputStream().use { stream ->
                val buf = ByteArray(32768)
                val read = stream.read(buf)
                for (i in 1 until read) {
                    if (buf[i] == '\n'.code.toByte() && buf[i - 1] == '\r'.code.toByte()) {
                        hasCrlf = true
                        break
                    }
                }
            }
            if (hasCrlf) {
                val raw = Files.readAllBytes(path)
                val converted = String(raw, StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .replace("\r", "\n")
                    .toByteArray(StandardCharsets.UTF_8)
                Files.write(path, converted)
                Toast.makeText(activity, R.string.om_script_runner_crlf_auto_converted, Toast.LENGTH_SHORT).show()
            }
        } catch (_: Throwable) {}
    }

    private fun executeScriptOrBinary(activity: Activity, path: Path) {
        if (path.isArchivePath) {
            Toast.makeText(activity, R.string.om_script_runner_archive_cannot_run, Toast.LENGTH_LONG).show()
            return
        }
        val fileSize = runCatching {
            if (path.isLinuxPath) path.toFile().length() else Files.size(path)
        }.getOrDefault(0L)

        if (fileSize == 0L) {
            Toast.makeText(activity, R.string.script_exec_empty_file_error, Toast.LENGTH_LONG).show()
            return
        }

        if (DeadManProtectionManager.isDfrootProtectionActive(activity)) {
            val isCriticalScript = runCatching {
                if (!isElfBinary(path)) {
                    val snippet = path.newInputStream().use { stream ->
                        val buf = ByteArray(65536)
                        val read = stream.read(buf)
                        if (read > 0) String(buf, 0, read, StandardCharsets.UTF_8) else ""
                    }
                    DeadManProtectionManager.containsDangerousSystemCommand(snippet)
                } else false
            }.getOrDefault(false)

            if (DeadManProtectionManager.isProtectedFromModification(path) || isCriticalScript) {
                DeadManProtectionManager.showDirtyFlagProtectionDialog(activity)
                return
            }
        }

        if (isElfBinary(path)) {
            val (compatible, elfArch) = checkElfCompatibility(path)
            if (!compatible) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.script_exec_incompatible_abi_title)
                    .setMessage(activity.getString(
                        R.string.script_exec_incompatible_abi_message,
                        elfArch,
                        Build.SUPPORTED_ABIS.joinToString(", ")
                    ))
                    .setPositiveButton(R.string.script_exec_incompatible_abi_proceed) { _, _ ->
                        checkConflictAndExecute(activity, path)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return
            }
        } else {
            checkCrlfAndConvert(activity, path)
        }

        checkConflictAndExecute(activity, path)
    }

    private fun checkConflictAndExecute(activity: Activity, path: Path) {
        if (ScriptExecutionManager.isRunning()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.script_exec_conflict_title)
                .setMessage(activity.getString(
                    R.string.script_exec_conflict_message,
                    ScriptExecutionManager.activeScriptName,
                    ScriptExecutionManager.activePid
                ))
                .setPositiveButton(R.string.script_exec_conflict_view_existing) { _, _ ->
                    val intent = Intent(activity, TerminalActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    }
                    activity.startActivity(intent)
                }
                .setNegativeButton(R.string.script_exec_conflict_kill_and_run) { _, _ ->
                    ScriptExecutionManager.terminateShell(activity)
                    performExecuteScriptOrBinary(activity, path)
                }
                .setNeutralButton(android.R.string.cancel, null)
                .show()
            return
        }

        performExecuteScriptOrBinary(activity, path)
    }

    private fun performExecuteScriptOrBinary(activity: Activity, path: Path) {
        val sp = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaultEnv = if (TerminalExpansionManager.isTermexInstalled(activity)) "termex" else "system"
        val env = sp.getString(KEY_EXEC_ENV, defaultEnv) ?: defaultEnv
        val isRoot = TerminalExpansionManager.isRootAvailable()
        val withRoot = isRoot && sp.getBoolean(KEY_EXEC_ROOT, false)

        val scriptPath = if (path.isLinuxPath) path.toFile().absolutePath else path.toString()
        val parentDir = if (path.isLinuxPath) path.toFile().parent ?: "/storage/emulated/0" else "/storage/emulated/0"

        val execCmd: String
        val isElf = isElfBinary(path)

        val isTermex = (env == "termex")
        val isBashUsable = isTermex && TerminalExpansionManager.isBashAvailable(activity)
        if (isTermex && !isBashUsable) {
            Toast.makeText(activity, R.string.om_script_runner_termex_unavailable, Toast.LENGTH_SHORT).show()
        }

        if (isElf) {
            val binName = File(scriptPath).name
            execCmd = if (withRoot) {
                if (scriptPath.startsWith("/storage/") || scriptPath.startsWith("/sdcard/")) {
                    val tmpPath = "/data/local/tmp/$binName"
                    "cp -f ${scriptPath.escapeShellArg()} ${tmpPath.escapeShellArg()} 2>/dev/null && chmod 755 ${tmpPath.escapeShellArg()} 2>/dev/null; ${tmpPath.escapeShellArg()}"
                } else {
                    "chmod 755 ${scriptPath.escapeShellArg()} 2>/dev/null; ${scriptPath.escapeShellArg()}"
                }
            } else if (isBashUsable) {
                val termexDir = File(activity.filesDir, "termex")
                val termexTmp = File(termexDir, "tmp").apply { mkdirs() }
                val target = File(termexTmp, binName)
                try {
                    File(scriptPath).copyTo(target, overwrite = true)
                    target.setExecutable(true, false)
                    target.absolutePath.escapeShellArg()
                } catch (e: Exception) {
                    scriptPath.escapeShellArg()
                }
            } else {
                if (scriptPath.startsWith("/storage/") || scriptPath.startsWith("/sdcard/")) {
                    val execDir = File(activity.cacheDir, "bin_exec").apply { mkdirs() }
                    val target = File(execDir, binName)
                    try {
                        File(scriptPath).copyTo(target, overwrite = true)
                        target.setExecutable(true, false)
                        if (File("/system/bin/linker64").exists()) {
                            "/system/bin/linker64 ${target.absolutePath.escapeShellArg()}"
                        } else if (File("/system/bin/linker").exists()) {
                            "/system/bin/linker ${target.absolutePath.escapeShellArg()}"
                        } else {
                            target.absolutePath.escapeShellArg()
                        }
                    } catch (e: Exception) {
                        scriptPath.escapeShellArg()
                    }
                } else {
                    scriptPath.escapeShellArg()
                }
            }
        } else {
            val shell = if (isBashUsable) "bash" else "sh"
            execCmd = if (withRoot) {
                "chmod 777 ${scriptPath.escapeShellArg()} 2>/dev/null; $shell ${scriptPath.escapeShellArg()}"
            } else {
                "$shell ${scriptPath.escapeShellArg()}"
            }
        }

        val intent = TerminalActivity.createIntent(
            activity,
            path = parentDir,
            command = execCmd,
            forceRoot = withRoot,
            forceTermex = isBashUsable,
            activeScriptPath = scriptPath
        )
        activity.startActivity(intent)
    }
}
