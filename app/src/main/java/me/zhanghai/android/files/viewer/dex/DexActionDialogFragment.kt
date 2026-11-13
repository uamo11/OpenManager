/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.dex

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.DialogDexOpenChoiceBinding
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.ui.isUiAlive
import me.zhanghai.android.files.ui.runWithProgressDialog
import me.zhanghai.android.files.ui.showSafeDialog
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.viewer.binary.DexInfoDialogFragment
import me.zhanghai.android.files.viewer.hex.HexEditorActivity
import me.zhanghai.android.files.viewer.text.TextEditorActivity
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DexActionDialogFragment : AppCompatDialogFragment() {

    private val args by args<Args>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val path = args.path
        val fileName = path.fileName.toString()
        val binding = DialogDexOpenChoiceBinding.inflate(layoutInflater)

        binding.dexFileName.text = fileName
        binding.dexFileDetails.text = getString(R.string.loading)

        val appContext = requireContext().applicationContext
        lifecycleScope.launch {
            val details = withContext(Dispatchers.IO) {
                var version = "DEX"
                var classCount = 0
                var methodCount = 0
                var fileSize = 0
                try {
                    path.newInputStream().use { input ->
                        val header = ByteArray(112)
                        var read = 0
                        while (read < header.size) {
                            val r = input.read(header, read, header.size - read)
                            if (r <= 0) break
                            read += r
                        }
                        if (read >= 112 && header[0] == 0x64.toByte() && header[1] == 0x65.toByte() && header[2] == 0x78.toByte()) {
                            version = "DEX " + String(header, 4, 3)
                            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                            fileSize = bb.getInt(32)
                            methodCount = bb.getInt(88)
                            classCount = bb.getInt(96)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                val sizeStr = if (fileSize > 0) String.format("%.2f MB", fileSize / (1024.0 * 1024.0)) else ""
                appContext.getString(R.string.om_dex_action_details_format, version, sizeStr, classCount, methodCount)
            }
            if (isUiAlive) {
                binding.dexFileDetails.text = details
            }
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(binding.root)
            .create()

        // 1. Editor DEX+ (Completo)
        binding.btnModeEditorPlus.setOnClickListener {
            val hostActivity = activity as? FragmentActivity
            dialog.dismiss()
            if (hostActivity != null) {
                val intent = Intent(hostActivity, DexEditorActivity::class.java).apply {
                    extraPath = path
                    putExtra(DexEditorActivity.EXTRA_INITIAL_TAB, 0)
                }
                hostActivity.startActivity(intent)
            }
        }

        // 2. Descompilar con JADX
        binding.btnModeDecompileJava.setOnClickListener {
            val hostActivity = activity as? FragmentActivity ?: return@setOnClickListener
            dialog.dismiss()
            val options = arrayOf(
                "Explorar y descompilar clases a Java",
                "Exportar todas las clases a .zip de fuentes Java"
            )
            hostActivity.showSafeDialog {
                setTitle(R.string.dex_decompile_java)
                setItems(options) { _, which ->
                    if (which == 0) {
                        val intent = Intent(hostActivity, DexEditorActivity::class.java).apply {
                            extraPath = path
                            putExtra(DexEditorActivity.EXTRA_INITIAL_TAB, 0)
                        }
                        hostActivity.startActivity(intent)
                    } else {
                        exportJavaZip(hostActivity, path)
                    }
                }
            }
        }

        // 3. Editor de Cadenas (String Pool)
        binding.btnModeStringPool.setOnClickListener {
            val hostActivity = activity as? FragmentActivity
            dialog.dismiss()
            if (hostActivity != null) {
                val intent = Intent(hostActivity, DexEditorActivity::class.java).apply {
                    extraPath = path
                    putExtra(DexEditorActivity.EXTRA_INITIAL_TAB, 1)
                }
                hostActivity.startActivity(intent)
            }
        }

        // 4. Resumen e Información
        binding.btnModeSummary.setOnClickListener {
            val fm = parentFragmentManager
            dialog.dismiss()
            DexInfoDialogFragment.show(path, fm)
        }

        // 5. Editor Hexadecimal
        binding.btnModeHex.setOnClickListener {
            val hostActivity = activity as? FragmentActivity
            dialog.dismiss()
            if (hostActivity != null) {
                HexEditorActivity.start(hostActivity, path)
            }
        }

        // 6. Abrir como Texto
        binding.btnModeText.setOnClickListener {
            val hostActivity = activity as? FragmentActivity
            dialog.dismiss()
            if (hostActivity != null) {
                val intent = Intent(hostActivity, TextEditorActivity::class.java).apply {
                    extraPath = path
                }
                hostActivity.startActivity(intent)
            }
        }

        return dialog
    }

    private fun exportJavaZip(hostActivity: FragmentActivity, path: Path) {
        hostActivity.runWithProgressDialog(
            title = hostActivity.getString(R.string.dex_decompile_progress),
            initialMessage = hostActivity.getString(R.string.loading)
        ) { updateMessage ->
            try {
                val outZip = withContext(Dispatchers.IO) {
                    val prepared = DexEngine.prepareLocalDexFile(hostActivity, path)
                    val baseName = path.fileName.toString().removeSuffix(".dex")
                    val parentDir = if (path.isLinuxPath) path.toFile().parentFile ?: hostActivity.cacheDir else hostActivity.cacheDir
                    val zipFile = File(parentDir, "${baseName}_java_sources.zip")

                    JadxDecompilerEngine.decompileToZip(prepared, zipFile) { current, total, clsName ->
                        if (current % 15 == 0 || current == total) {
                            updateMessage(hostActivity.getString(R.string.om_dex_decompile_class_format, current, total, clsName))
                        }
                    }
                    zipFile
                }

                if (hostActivity.isUiAlive) {
                    hostActivity.showSafeDialog {
                        setTitle(R.string.dex_export_java_sources)
                        setMessage(hostActivity.getString(R.string.dex_decompile_success, outZip.name))
                        setPositiveButton(android.R.string.ok, null)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                e.printStackTrace()
                if (hostActivity.isUiAlive) {
                    Toast.makeText(
                        hostActivity,
                        hostActivity.getString(R.string.om_dex_decompile_error_format, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    companion object {
        fun show(path: Path, fragmentManager: FragmentManager) {
            DexActionDialogFragment().putArgs(Args(path)).show(fragmentManager, null)
        }
    }

    @Parcelize
    class Args(val path: @WriteWith<ParcelableParceler> Path) : ParcelableArgs
}
