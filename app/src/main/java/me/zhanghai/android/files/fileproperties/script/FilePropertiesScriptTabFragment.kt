/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties.script

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.filelist.ScriptRunnerHelper
import me.zhanghai.android.files.filelist.name
import me.zhanghai.android.files.fileproperties.FilePropertiesTabFragment
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.viewModels
import me.zhanghai.android.files.viewer.text.TextEditorActivity
import java.nio.charset.StandardCharsets

class FilePropertiesScriptTabFragment : FilePropertiesTabFragment() {
    private val args by args<Args>()

    private val viewModel by viewModels { { FilePropertiesScriptTabViewModel(args.path) } }

    override fun onResume() {
        super.onResume()
        viewModel.scriptInfoLiveData.observe(viewLifecycleOwner) { onScriptInfoChanged(it) }
    }

    override fun refresh() {
        viewModel.reload()
    }

    private fun onScriptInfoChanged(stateful: Stateful<ScriptInfo>) {
        bindView(stateful) { scriptInfo ->
            addItemView(R.string.file_properties_script_type, scriptInfo.scriptType)

            addItemView(
                R.string.file_properties_script_shebang,
                scriptInfo.shebang ?: getString(R.string.file_properties_script_shebang_none)
            )

            addItemView(R.string.file_properties_script_interpreter, scriptInfo.interpreter)

            // Line Ending with interactive conversion if CRLF/Mixed
            val lineEndingListener: ((View) -> Unit)? = if (
                scriptInfo.lineEnding == ScriptInfo.LineEnding.CRLF ||
                scriptInfo.lineEnding == ScriptInfo.LineEnding.MIXED ||
                scriptInfo.lineEnding == ScriptInfo.LineEnding.CR
            ) {
                { convertToLf() }
            } else {
                null
            }
            addItemView(
                R.string.file_properties_script_line_ending,
                scriptInfo.lineEndingDescription,
                lineEndingListener
            )

            // POSIX permissions with interactive MT Manager permissions dialog
            val permInfo = me.zhanghai.android.files.fileproperties.permission.MtPermissionsHelper.getPermissions(
                requireContext(),
                args.path
            )
            addItemView(
                R.string.file_properties_script_permissions,
                "${permInfo.displayString}  ✎"
            ) {
                me.zhanghai.android.files.fileproperties.permission.MtPermissionsHelper.showPermissionsDialog(
                    context = requireContext(),
                    path = args.path
                ) {
                    viewModel.reload()
                }
            }

            // Code statistics
            addItemView(
                R.string.file_properties_script_stats,
                getString(
                    R.string.file_properties_script_stats_format,
                    scriptInfo.lineCount,
                    scriptInfo.wordCount,
                    scriptInfo.charset
                )
            )

            // Recommended environment
            addItemView(R.string.file_properties_script_environment, scriptInfo.recommendedEnvironment)

            // Quick Actions: Execute & Edit
            addItemView(
                R.string.file_properties_script_action_execute_title,
                getString(R.string.file_properties_script_action_execute_desc)
            ) {
                activity?.let { act ->
                    ScriptRunnerHelper.showScriptActionDialog(act, args.path)
                }
            }

            addItemView(
                R.string.file_properties_script_action_edit_title,
                getString(R.string.file_properties_script_action_edit_desc)
            ) {
                val intent = Intent(requireContext(), TextEditorActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    extraPath = args.path
                }
                startActivity(intent)
            }
        }
    }

    private fun convertToLf() {
        val context = requireContext()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.file_properties_script_convert_lf_title)
            .setMessage(R.string.file_properties_script_convert_lf_message)
            .setPositiveButton(R.string.file_properties_script_convert_action) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val bytes = java8.nio.file.Files.readAllBytes(args.path)
                        val text = String(bytes, StandardCharsets.UTF_8)
                        val lfText = text.replace("\r\n", "\n").replace("\r", "\n")
                        java8.nio.file.Files.write(args.path, lfText.toByteArray(StandardCharsets.UTF_8))
                        withContext(Dispatchers.Main) {
                            showToast(R.string.file_properties_script_convert_success)
                            viewModel.reload()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            showToast("Error al convertir a LF: ${e.message}")
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleExecutePermission() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = args.path.toFile()
                val current = file.canExecute()
                val success = file.setExecutable(!current, false)
                withContext(Dispatchers.Main) {
                    if (success) {
                        showToast(if (!current) R.string.file_properties_script_permission_granted else R.string.file_properties_script_permission_revoked)
                        viewModel.reload()
                    } else {
                        showToast(R.string.file_properties_script_permission_failed)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showToast("Error: ${e.message}")
                }
            }
        }
    }

    companion object {
        fun isAvailable(file: FileItem): Boolean {
            if (file.attributes.isDirectory) return false
            val name = file.path.name.lowercase()
            val scriptExts = setOf(
                "sh", "bash", "zsh", "ksh", "csh", "py", "pyw", "js", "mjs", "cjs",
                "lua", "rb", "pl", "php", "rc", "bat", "cmd"
            )
            val ext = name.substringAfterLast('.', "")
            if (ext in scriptExts) return true
            val mime = file.mimeType.value.lowercase()
            if (mime in listOf(
                    "application/x-sh",
                    "application/x-shellscript",
                    "text/x-shellscript",
                    "text/x-python",
                    "application/x-python-code",
                    "application/javascript",
                    "text/javascript"
                )
            ) {
                return true
            }
            return false
        }
    }

    @Parcelize
    class Args(val path: @WriteWith<ParcelableParceler> Path) : ParcelableArgs
}
