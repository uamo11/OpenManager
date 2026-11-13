package me.zhanghai.android.files.filelist

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.content.getSystemService
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.HexViewerDialogFragmentBinding
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.ui.isUiAlive
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.showToast

class HexViewerDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()
    private lateinit var binding: HexViewerDialogFragmentBinding

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = HexViewerDialogFragmentBinding.inflate(layoutInflater)

        try {
            val monoTypeface = Typeface.createFromAsset(requireContext().assets, "Mono.ttf")
            binding.hexText.typeface = monoTypeface
        } catch (e: Exception) {
            binding.hexText.typeface = Typeface.MONOSPACE
        }

        binding.fileNameText.text = args.path.fileName.toString()
        loadHexData()

        return MaterialAlertDialogBuilder(requireContext(), theme)
            .setView(binding.root)
            .setPositiveButton(R.string.open_as_hex_copy) { _, _ ->
                val clipboard = requireContext().getSystemService<ClipboardManager>()
                val clip = ClipData.newPlainText("hex", binding.hexText.text)
                clipboard?.setPrimaryClip(clip)
                showToast(R.string.open_as_hex_copied)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun loadHexData() {
        val appContext = requireContext().applicationContext
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val hexString = withContext(Dispatchers.IO) {
                try {
                    args.path.newInputStream().use { stream ->
                        val maxBytes = 32 * 1024
                        val buffer = ByteArray(maxBytes)
                        var totalRead = 0
                        while (totalRead < maxBytes) {
                            val r = stream.read(buffer, totalRead, maxBytes - totalRead)
                            if (r <= 0) break
                            totalRead += r
                        }
                        if (totalRead <= 0) {
                            appContext.getString(R.string.om_hex_empty_file_line)
                        } else {
                            formatHexDump(buffer, totalRead)
                        }
                    }
                } catch (e: Exception) {
                    appContext.getString(R.string.om_file_read_error_format, e.message ?: "")
                }
            }
            if (isUiAlive) {
                binding.progressBar.visibility = View.GONE
                binding.hexText.text = hexString
            }
        }
    }

    private fun formatHexDump(bytes: ByteArray, length: Int): String {
        val sb = StringBuilder()
        val asciiSb = StringBuilder()
        var offset = 0
        while (offset < length) {
            sb.append(String.format("%08X  ", offset))
            asciiSb.setLength(0)
            val chunk = minOf(16, length - offset)
            for (i in 0 until 16) {
                if (i < chunk) {
                    val b = bytes[offset + i].toInt() and 0xFF
                    sb.append(String.format("%02X ", b))
                    if (b in 32..126) {
                        asciiSb.append(b.toChar())
                    } else {
                        asciiSb.append('.')
                    }
                } else {
                    sb.append("   ")
                }
                if (i == 7) {
                    sb.append(" ")
                }
            }
            sb.append(" |").append(asciiSb).append("|\n")
            offset += 16
        }
        return sb.toString()
    }

    companion object {
        fun show(path: Path, fragmentManager: androidx.fragment.app.FragmentManager) {
            HexViewerDialogFragment().putArgs(Args(path)).show(fragmentManager, null)
        }
    }

    @Parcelize
    class Args(val path: @WriteWith<ParcelableParceler> Path) : ParcelableArgs
}
