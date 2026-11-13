/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.binary

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.ui.isUiAlive
import me.zhanghai.android.files.ui.runWithProgressDialog
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show
import me.zhanghai.android.files.viewer.text.TextEditorActivity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// DEX Info Dialog
class DexInfoDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val path = args.path
        val fileName = path.fileName.toString()

        val dialog = MaterialAlertDialogBuilder(context)
            .setIcon(R.drawable.file_dex_icon)
            .setTitle(fileName)
            .setMessage(getString(R.string.loading))
            .setNeutralButton(R.string.om_bin_view_strings) { _, _ ->
                extractAndShowDexStrings(path)
            }
            .setNegativeButton(R.string.om_bin_open_as_text) { _, _ ->
                val intent = Intent(context, TextEditorActivity::class.java).apply {
                    extraPath = path
                }
                context.startActivity(intent)
            }
            .setPositiveButton(R.string.close, null)
            .create()

        val appContext = context.applicationContext
        lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) {
                var version = "DEX"
                var classCount = 0
                var methodCount = 0
                var fieldCount = 0
                var stringCount = 0
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
                            stringCount = bb.getInt(56)
                            fieldCount = bb.getInt(80)
                            methodCount = bb.getInt(88)
                            classCount = bb.getInt(96)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                appContext.getString(R.string.om_bin_dex_info_format, version, classCount, methodCount, fieldCount, stringCount)
            }
            if (isUiAlive) {
                (dialog as? AlertDialog)?.setMessage(message)
            }
        }

        return dialog
    }

    private fun extractAndShowDexStrings(path: Path) {
        val hostActivity = activity as? FragmentActivity ?: return
        val appContext = hostActivity.applicationContext
        hostActivity.runWithProgressDialog(
            title = path.fileName.toString(),
            initialMessage = appContext.getString(R.string.loading)
        ) {
            try {
                val outFile = withContext(Dispatchers.IO) {
                    val strings = mutableListOf<String>()
                    path.newInputStream().use { input ->
                        val bytes = input.readBytes()
                        if (bytes.size >= 112) {
                            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                            val stringCount = bb.getInt(56).coerceAtLeast(0).coerceAtMost(5000)
                            val stringIdsOff = bb.getInt(60)
                            if (stringIdsOff >= 0 && stringIdsOff + 4 <= bytes.size) {
                                for (i in 0 until stringCount) {
                                    val idPos = stringIdsOff.toLong() + i.toLong() * 4L
                                    if (idPos + 4L > bytes.size) break
                                    val off = bb.getInt(idPos.toInt())
                                    if (off in 0 until bytes.size) {
                                        var pos = off
                                        while (pos < bytes.size && (bytes[pos].toInt() and 0x80) != 0) {
                                            pos++
                                        }
                                        pos++
                                        val sb = StringBuilder()
                                        while (pos < bytes.size && bytes[pos].toInt() != 0) {
                                            val c = bytes[pos].toInt().toChar()
                                            if (c in ' '..'~' || c == '\n' || c == '\t') {
                                                sb.append(c)
                                            }
                                            pos++
                                        }
                                        if (sb.isNotEmpty()) {
                                            strings.add(sb.toString())
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val temp = File(appContext.cacheDir, "${path.fileName}_strings.txt")
                    temp.writeText(strings.joinToString("\n"))
                    temp
                }
                if (hostActivity.isUiAlive) {
                    val intent = Intent(hostActivity, TextEditorActivity::class.java).apply {
                        extraPath = java8.nio.file.Paths.get(outFile.absolutePath)
                    }
                    hostActivity.startActivity(intent)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                e.printStackTrace()
                if (hostActivity.isUiAlive) {
                    Toast.makeText(
                        hostActivity,
                        hostActivity.getString(R.string.om_bin_extract_strings_error_format, e.message ?: ""),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    companion object {
        fun show(path: Path, fragmentManager: FragmentManager) {
            DexInfoDialogFragment().putArgs(Args(path)).show(fragmentManager, null)
        }
    }

    @Parcelize
    class Args(val path: @WriteWith<ParcelableParceler> Path) : ParcelableArgs
}

// ARSC Info Dialog
class ArscInfoDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val path = args.path
        val fileName = path.fileName.toString()

        val dialog = MaterialAlertDialogBuilder(context)
            .setIcon(R.drawable.file_arsc_icon)
            .setTitle(fileName)
            .setMessage(getString(R.string.loading))
            .setNeutralButton(R.string.om_bin_arsc_editor) { _, _ ->
                val intent = Intent(context, me.zhanghai.android.files.viewer.arsc.ArscEditorActivity::class.java).apply {
                    extraPath = path
                }
                context.startActivity(intent)
            }
            .setNegativeButton(R.string.om_bin_open_as_text) { _, _ ->
                val intent = Intent(context, TextEditorActivity::class.java).apply {
                    extraPath = path
                }
                context.startActivity(intent)
            }
            .setPositiveButton(R.string.close, null)
            .create()

        val appContext = context.applicationContext
        lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) {
                var isValidArsc = false
                var packageCount = 0
                try {
                    path.newInputStream().use { input ->
                        val header = ByteArray(12)
                        var read = 0
                        while (read < header.size) {
                            val r = input.read(header, read, header.size - read)
                            if (r <= 0) break
                            read += r
                        }
                        if (read >= 12 && header[0] == 0x02.toByte() && header[1] == 0x00.toByte()) {
                            isValidArsc = true
                            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                            packageCount = bb.getInt(8)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                if (isValidArsc) {
                    appContext.getString(R.string.om_bin_arsc_valid_format, packageCount)
                } else {
                    appContext.getString(R.string.om_bin_arsc_fallback)
                }
            }
            if (isUiAlive) {
                (dialog as? AlertDialog)?.setMessage(message)
            }
        }

        return dialog
    }

    companion object {
        fun show(path: Path, fragmentManager: FragmentManager) {
            ArscInfoDialogFragment().putArgs(Args(path)).show(fragmentManager, null)
        }
    }

    @Parcelize
    class Args(val path: @WriteWith<ParcelableParceler> Path) : ParcelableArgs
}

// ELF / SO Info Dialog
class ElfInfoDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val path = args.path
        val fileName = path.fileName.toString()

        val dialog = MaterialAlertDialogBuilder(context)
            .setIcon(R.drawable.file_so_icon)
            .setTitle(fileName)
            .setMessage(getString(R.string.loading))
            .setNegativeButton(R.string.om_bin_open_as_text) { _, _ ->
                val intent = Intent(context, TextEditorActivity::class.java).apply {
                    extraPath = path
                }
                context.startActivity(intent)
            }
            .setPositiveButton(R.string.close, null)
            .create()

        val appContext = context.applicationContext
        lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) {
                var arch = appContext.getString(R.string.unknown)
                var bitClass = ""
                var endian = ""
                val neededLibs = mutableListOf<String>()

                try {
                    path.newInputStream().use { input ->
                        val sample = ByteArray(32768)
                        var len = 0
                        while (len < sample.size) {
                            val r = input.read(sample, len, sample.size - len)
                            if (r <= 0) break
                            len += r
                        }
                        if (len >= 20 && sample[0] == 0x7F.toByte() && sample[1] == 'E'.code.toByte() && sample[2] == 'L'.code.toByte() && sample[3] == 'F'.code.toByte()) {
                            bitClass = if (sample[4] == 2.toByte()) "64-bit" else "32-bit"
                            endian = if (sample[5] == 1.toByte()) "Little Endian" else "Big Endian"
                            val bb = ByteBuffer.wrap(sample).order(if (sample[5] == 1.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
                            val machine = bb.getShort(18).toInt() and 0xFFFF
                            arch = when (machine) {
                                0x28 -> "ARM (armeabi-v7a)"
                                0xB7 -> "AArch64 (arm64-v8a)"
                                0x03 -> "x86 (i386)"
                                0x3E -> "x86_64 (amd64)"
                                0xF3 -> "RISC-V"
                                else -> appContext.getString(R.string.om_bin_elf_code_format, machine)
                            }
                        }
                        if (len > 0) {
                            val text = String(sample, 0, len, Charsets.ISO_8859_1)
                            val matches = Regex("""[a-zA-Z0-9_\-\.]+\.so""").findAll(text)
                            for (m in matches) {
                                val lib = m.value
                                if (lib != fileName && !neededLibs.contains(lib)) {
                                    neededLibs.add(lib)
                                    if (neededLibs.size >= 8) break
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                StringBuilder().apply {
                    append(appContext.getString(R.string.om_bin_elf_arch_format, arch, bitClass))
                    append(appContext.getString(R.string.om_bin_elf_format_format, endian))
                    if (neededLibs.isNotEmpty()) {
                        append(appContext.getString(R.string.om_bin_elf_deps_header))
                        append(neededLibs.joinToString("\n  - "))
                        append("\n")
                    }
                }.toString()
            }
            if (isUiAlive) {
                (dialog as? AlertDialog)?.setMessage(message)
            }
        }

        return dialog
    }

    companion object {
        fun show(path: Path, fragmentManager: FragmentManager) {
            ElfInfoDialogFragment().putArgs(Args(path)).show(fragmentManager, null)
        }
    }

    @Parcelize
    class Args(val path: @WriteWith<ParcelableParceler> Path) : ParcelableArgs
}
