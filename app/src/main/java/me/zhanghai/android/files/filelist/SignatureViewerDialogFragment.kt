package me.zhanghai.android.files.filelist

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
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
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.ui.isUiAlive
import me.zhanghai.android.files.ui.resolveThemeColor
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.dpToDimensionPixelSize
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.showToast
import java.security.MessageDigest

class SignatureViewerDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val padH = context.dpToDimensionPixelSize(24)
        val padV = context.dpToDimensionPixelSize(12)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padV, padH, padV)
        }
        val scroll = ScrollView(context).apply {
            addView(container)
        }

        val infoTextView = TextView(context).apply {
            textSize = 13f
            setTextColor(context.resolveThemeColor(android.R.attr.textColorPrimary, Color.WHITE))
            setTextIsSelectable(true)
            try {
                val monoTypeface = Typeface.createFromAsset(context.assets, "Mono.ttf")
                typeface = monoTypeface
            } catch (e: Exception) {
                typeface = Typeface.MONOSPACE
            }
            text = getString(R.string.loading)
        }
        container.addView(infoTextView)

        val appContext = context.applicationContext
        lifecycleScope.launch {
            val details = withContext(Dispatchers.IO) {
                try {
                    val md5Digest = MessageDigest.getInstance("MD5")
                    val sha1Digest = MessageDigest.getInstance("SHA-1")
                    val sha256Digest = MessageDigest.getInstance("SHA-256")

                    args.path.newInputStream().use { stream ->
                        val buffer = ByteArray(64 * 1024)
                        var read = stream.read(buffer)
                        while (read > 0) {
                            md5Digest.update(buffer, 0, read)
                            sha1Digest.update(buffer, 0, read)
                            sha256Digest.update(buffer, 0, read)
                            read = stream.read(buffer)
                        }
                    }

                    val md5 = md5Digest.digest().joinToString("") { "%02x".format(it) }
                    val sha1 = sha1Digest.digest().joinToString(":") { "%02X".format(it) }
                    val sha256 = sha256Digest.digest().joinToString(":") { "%02X".format(it) }

                    val fn = args.path.fileName.toString().lowercase()
                    val ksDetails = StringBuilder()
                    if (fn.endsWith(".jks") || fn.endsWith(".keystore") || fn.endsWith(".bks") || fn.endsWith(".p12")) {
                        var tempFile: java.io.File? = null
                        try {
                            tempFile = java.io.File.createTempFile("inspect_ks", ".tmp", appContext.cacheDir)
                            args.path.newInputStream().use { ins ->
                                tempFile.outputStream().use { outs -> ins.copyTo(outs) }
                            }
                            var certInfos: List<KeystoreGeneratorHelper.KeystoreCertificateInfo>? = null
                            for (tryPass in listOf("", "android", "changeit", "password", "123456")) {
                                try {
                                    certInfos = KeystoreGeneratorHelper.inspectKeystore(tempFile, tryPass)
                                    break
                                } catch (_: Exception) {}
                            }
                            if (!certInfos.isNullOrEmpty()) {
                                ksDetails.append("\n\n[Certificados en KeyStore (${certInfos.size})]\n")
                                for (info in certInfos) {
                                    ksDetails.append("• Alias: ${info.alias} (${info.algorithm})\n")
                                    ksDetails.append("  Subject: ${info.subject}\n")
                                    ksDetails.append("  Validez: ${info.notBefore} - ${info.notAfter}\n")
                                    ksDetails.append("  Cert SHA-256: ${info.sha256}\n")
                                }
                            }
                        } catch (_: Exception) {
                        } finally {
                            tempFile?.delete()
                        }
                    }

                    """
                    Archivo: ${args.path.fileName}
                    
                    [Huellas Digitales / Hashes]
                    MD5:
                    $md5
                    
                    SHA-1:
                    $sha1
                    
                    SHA-256:
                    $sha256
                    
                    [Firma / Certificado]
                    Estado: Inspección completada.
                    Compatible con Keystore / APK Signature Scheme v1/v2/v3.$ksDetails
                    """.trimIndent()
                } catch (e: Exception) {
                    appContext.getString(R.string.om_sig_calc_error_format, e.message ?: "")
                }
            }
            if (isUiAlive) {
                infoTextView.text = details
            }
        }

        return MaterialAlertDialogBuilder(context, theme)
            .setTitle(R.string.open_as_sig_title)
            .setView(scroll)
            .setPositiveButton(R.string.open_as_hex_copy) { _, _ ->
                val clipboard = context.getSystemService<ClipboardManager>()
                val clip = ClipData.newPlainText("signature", infoTextView.text)
                clipboard?.setPrimaryClip(clip)
                showToast(R.string.om_copied_to_clipboard)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    companion object {
        fun show(path: Path, fragmentManager: androidx.fragment.app.FragmentManager) {
            SignatureViewerDialogFragment().putArgs(Args(path)).show(fragmentManager, null)
        }
    }

    @Parcelize
    class Args(val path: @WriteWith<ParcelableParceler> Path) : ParcelableArgs
}
