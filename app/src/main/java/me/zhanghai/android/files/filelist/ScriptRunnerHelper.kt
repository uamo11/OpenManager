/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
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
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.terminal.TerminalActivity
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
     * Guaranteed never to match .txt or regular document/media files.
     */
    fun isExecutableOrScript(file: FileItem): Boolean {
        val fileName = file.path.fileName.toString()
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
        val fileName = path.fileName.toString()
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
        } catch (e: Exception) {
            try {
                path.toFile().canExecute()
            } catch (e2: Exception) {
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
        } catch (e: Exception) {
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
        tvSubtitle.text = if (isElf) "Binario Ejecutable ELF" else "Script Ejecutable (/system/bin/sh)"

        fun copyText(label: String, value: CharSequence?) {
            val str = value?.toString() ?: return
            if (str.isNotBlank() && str != "-") {
                val cb = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cb?.setPrimaryClip(ClipData.newPlainText(label, str))
                Toast.makeText(activity, "$label copiado", Toast.LENGTH_SHORT).show()
            }
        }

        tvTitle.setOnClickListener { copyText("Nombre", tvTitle.text) }

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
        addRow("Ruta", fullPathStr, singleLineMiddle = true)

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
        val (_, tvSizeVal) = addRow("Tamaño", formattedSize)

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
        addRow("Modificado", dateStr)

        // Interactive Permissions Row ("Permisos: 777 (rwxrwxrwx)")
        val permInfo = MtPermissionsHelper.getPermissions(activity, path, fileItem)
        addRow(
            label = "Permisos",
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
            addRow("Propietario", ownerGroup)
        }

        val (_, tvInterpreter) = addRow("Intérprete", if (isElf) "ELF Nativo" else "/system/bin/sh")
        val (rowLineEnding, tvLineEnding) = addRow("Formato línea", if (isElf) "Binario" else "Analizando...")
        val (rowLines, tvLines) = addRow("Contenido", if (isElf) "Ejecutable compilado" else "Analizando...")
        if (isElf) {
            rowLineEnding.visibility = View.GONE
            rowLines.visibility = View.GONE
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

                withContext(Dispatchers.Main) {
                    if (!shebang.isNullOrBlank()) {
                        tvInterpreter.text = shebang
                        tvSubtitle.text = "Script (${shebang!!.removePrefix("#!").trim()})"
                    }
                    tvLines.text = "$lineCount líneas · UTF-8"
                    if (hasCrlf) {
                        tvLineEnding.text = "CRLF (Windows) · Tocar para convertir a LF"
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

        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor("#303030")))

        // Button 1: PREFERENCIAS
        btnAction1.visibility = View.VISIBLE
        btnAction1.text = activity.getString(R.string.script_action_preferences)
        btnAction1.setTextColor(Color.parseColor("#2196F3"))
        btnAction1.setOnClickListener {
            showPreferencesDialog(activity)
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
            dialog.dismiss()
            editScriptOrBinary(activity, path)
        }

        // Button 4: EJECUTAR
        btnAction4.visibility = View.VISIBLE
        btnAction4.text = activity.getString(R.string.script_action_execute)
        btnAction4.setTextColor(Color.parseColor("#4CAF50"))
        btnAction4.setOnClickListener {
            dialog.dismiss()
            executeScriptOrBinary(activity, path)
        }

        dialog.show()
    }

    fun showPreferencesDialog(activity: Activity) {
        val sp = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaultEnv = if (TerminalExpansionManager.isTermexInstalled(activity)) "termex" else "system"
        val savedEnv = sp.getString(KEY_EXEC_ENV, defaultEnv) ?: defaultEnv
        val isRoot = TerminalExpansionManager.isRootAvailable()
        val savedRoot = if (isRoot) sp.getBoolean(KEY_EXEC_ROOT, false) else false

        val binding = ScriptPreferencesDialogBinding.inflate(LayoutInflater.from(activity))
        if (savedEnv == "termex") {
            binding.radioTermex.isChecked = true
        } else {
            binding.radioSystem.isChecked = true
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

    private fun executeScriptOrBinary(activity: Activity, path: Path) {
        val sp = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaultEnv = if (TerminalExpansionManager.isTermexInstalled(activity)) "termex" else "system"
        val env = sp.getString(KEY_EXEC_ENV, defaultEnv) ?: defaultEnv
        val isRoot = TerminalExpansionManager.isRootAvailable()
        val withRoot = isRoot && sp.getBoolean(KEY_EXEC_ROOT, false)

        val scriptPath = path.toFile().absolutePath
        val parentDir = path.toFile().parent ?: "/storage/emulated/0"

        val execCmd: String
        val isElf = isElfBinary(path)

        if (isElf) {
            execCmd = if (withRoot) {
                "chmod 777 ${scriptPath.escapeShellArg()} 2>/dev/null; ${scriptPath.escapeShellArg()}"
            } else {
                if (scriptPath.startsWith("/storage/") || scriptPath.startsWith("/sdcard/")) {
                    val execDir = File(activity.cacheDir, "bin_exec").apply { mkdirs() }
                    val target = File(execDir, File(scriptPath).name)
                    try {
                        File(scriptPath).copyTo(target, overwrite = true)
                        target.setExecutable(true, false)
                        target.absolutePath.escapeShellArg()
                    } catch (e: Exception) {
                        scriptPath.escapeShellArg()
                    }
                } else {
                    scriptPath.escapeShellArg()
                }
            }
        } else {
            val shell = if (env == "termex") "bash" else "sh"
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
            forceTermex = env == "termex"
        )
        activity.startActivity(intent)
    }
}
