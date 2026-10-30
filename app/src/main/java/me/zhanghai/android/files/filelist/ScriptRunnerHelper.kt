/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.view.LayoutInflater
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.ScriptPreferencesDialogBinding
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.provider.common.PosixFileAttributes
import me.zhanghai.android.files.provider.common.PosixFileModeBit
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.terminal.TerminalActivity
import me.zhanghai.android.files.terminal.TerminalExpansionManager
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.viewer.hex.HexEditorActivity
import me.zhanghai.android.files.viewer.text.TextEditorActivity
import java.io.File

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
     * Displays the MT Manager style Action Dialog when clicking a script or ELF binary.
     */
    fun showScriptActionDialog(activity: Activity, fileItem: FileItem) {
        showScriptActionDialog(activity, fileItem.path)
    }

    fun showScriptActionDialog(activity: Activity, path: Path) {
        val fileName = path.fileName.toString()

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(fileName)
            .setMessage(R.string.script_action_select_action)
            .setNegativeButton(R.string.script_action_preferences, null)
            .setNeutralButton(R.string.script_action_edit) { _, _ ->
                editScriptOrBinary(activity, path)
            }
            .setPositiveButton(R.string.script_action_execute) { _, _ ->
                executeScriptOrBinary(activity, path)
            }
            .create()

        dialog.setOnShowListener {
            val prefsBtn = dialog.getButton(DialogInterface.BUTTON_NEGATIVE)
            prefsBtn.setOnClickListener {
                showPreferencesDialog(activity)
            }
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
                "chmod 777 \"$scriptPath\" 2>/dev/null; \"$scriptPath\""
            } else {
                if (scriptPath.startsWith("/storage/") || scriptPath.startsWith("/sdcard/")) {
                    val execDir = File(activity.cacheDir, "bin_exec").apply { mkdirs() }
                    val target = File(execDir, File(scriptPath).name)
                    try {
                        File(scriptPath).copyTo(target, overwrite = true)
                        target.setExecutable(true, false)
                        "\"${target.absolutePath}\""
                    } catch (e: Exception) {
                        "\"$scriptPath\""
                    }
                } else {
                    "\"$scriptPath\""
                }
            }
        } else {
            val shell = if (env == "termex") "bash" else "sh"
            execCmd = if (withRoot) {
                "chmod 777 \"$scriptPath\" 2>/dev/null; $shell \"$scriptPath\""
            } else {
                "$shell \"$scriptPath\""
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
