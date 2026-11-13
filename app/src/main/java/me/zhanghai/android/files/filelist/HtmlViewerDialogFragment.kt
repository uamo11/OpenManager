package me.zhanghai.android.files.filelist

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatDialogFragment
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
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.dpToDimensionPixelSize
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.viewer.text.TextEditorActivity
import java.io.ByteArrayOutputStream

class HtmlViewerDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()
    private var webView: WebView? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val heightPx = (context.resources.displayMetrics.heightPixels * 0.55f).toInt()
            .coerceAtLeast(context.dpToDimensionPixelSize(260))
        val wv = WebView(context).apply {
            settings.apply {
                javaScriptEnabled = false
                domStorageEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                defaultTextEncodingName = "UTF-8"
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val scheme = request?.url?.scheme?.lowercase()
                    return scheme == "javascript" || scheme == "file" || scheme == "content"
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                heightPx
            )
        }
        webView = wv

        val appContext = context.applicationContext
        lifecycleScope.launch {
            val resultHtml = withContext(Dispatchers.IO) {
                try {
                    args.path.newInputStream().use { stream ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        val maxBytes = 128 * 1024
                        var total = 0
                        while (total < maxBytes) {
                            val r = stream.read(buf, 0, minOf(buf.size, maxBytes - total))
                            if (r <= 0) break
                            out.write(buf, 0, r)
                            total += r
                        }
                        out.toString("UTF-8")
                    }
                } catch (e: Exception) {
                    appContext.getString(R.string.om_html_load_error_format, e.message ?: "")
                }
            }
            if (isUiAlive) {
                wv.loadDataWithBaseURL(null, resultHtml, "text/html", "UTF-8", null)
            }
        }

        return MaterialAlertDialogBuilder(context, theme)
            .setTitle("${getString(R.string.open_as_html_title)}: ${args.path.fileName}")
            .setView(wv)
            .setPositiveButton(R.string.open_as_html_edit) { _, _ ->
                val intent = Intent(context, TextEditorActivity::class.java).apply {
                    extraPath = args.path
                    putExtra("FORCED_SYNTAX", "HTML")
                }
                context.startActivity(intent)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    override fun onDestroy() {
        webView?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        fun show(path: Path, fragmentManager: androidx.fragment.app.FragmentManager) {
            HtmlViewerDialogFragment().putArgs(Args(path)).show(fragmentManager, null)
        }
    }

    @Parcelize
    class Args(val path: @WriteWith<ParcelableParceler> Path) : ParcelableArgs
}
