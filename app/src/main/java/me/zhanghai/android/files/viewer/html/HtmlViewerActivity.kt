/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.html

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.app.ensureWebViewDebuggingIfDebug
import me.zhanghai.android.files.file.asMimeType
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.theme.custom.OpenManagerPalette
import me.zhanghai.android.files.ui.applyEdgeToEdgeInsets
import me.zhanghai.android.files.util.createViewIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.withChooser
import me.zhanghai.android.files.viewer.text.TextEditorActivity

/**
 * HTML viewer on the system WebView: nothing added to the APK. A page in local storage is loaded
 * from its folder, so its CSS, scripts and images next to it work; pages elsewhere (SFTP, inside
 * archives) are read into memory. Scripts run, but a page cannot read other local files, and
 * links to the web open in the browser.
 */
class HtmlViewerActivity : AppActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var toolbar: MaterialToolbar
    private var path: Path? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureWebViewDebuggingIfDebug()
        val path = intent.extraPath ?: run {
            finish()
            return
        }
        this.path = path
        val palette = OpenManagerPalette.from(this)

        toolbar = MaterialToolbar(this).apply {
            title = path.fileName?.toString()
            setBackgroundColor(palette.toolbarBg)
            setTitleTextColor(palette.textPrimary)
            setSubtitleTextColor(palette.textSecondary)
            setNavigationIcon(R.drawable.arrow_back_icon_white_24dp)
            navigationIcon?.setTint(palette.iconTint)
            setNavigationContentDescription(R.string.abc_action_bar_up_description)
            setNavigationOnClickListener { finish() }
            overflowIcon?.setTint(palette.iconTint)
            minimumHeight = (56 * resources.displayMetrics.density).toInt()
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
        }
        webView = WebView(this)
        val content = FrameLayout(this).apply {
            addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (3 * resources.displayMetrics.density).toInt()))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.toolbarBg)
            addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        toolbar.applyEdgeToEdgeInsets(applyTop = true, applyHorizontal = true)
        content.applyEdgeToEdgeInsets(applyBottom = true, applyHorizontal = true)
        setSupportActionBar(toolbar)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // The page's own folder only: no reading other files, no content:// access.
            allowFileAccess = path.isLinuxPath
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            allowContentAccess = false
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return when (uri.scheme?.lowercase()) {
                    // Pages next to this one (and anchors) stay here.
                    "file" -> false
                    "javascript", "about", "data", "blob" -> false
                    // The web, mail, phone: the apps for them.
                    else -> {
                        startActivitySafe(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
                        true
                    }
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                progress.isVisible = true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progress.isVisible = false
                invalidateOptionsMenu()
                if (BuildConfig.DEBUG) Log.d(TAG, "Finished title=${view.title} url=$url")
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.isVisible = newProgress < 100
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                val fileName = path.fileName?.toString()
                toolbar.subtitle = title?.takeIf { it.isNotBlank() && it != fileName && !it.startsWith("file:") }
                if (BuildConfig.DEBUG) Log.d(TAG, "Title=$title")
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
        if (savedInstanceState != null && webView.restoreState(savedInstanceState) != null) return
        load(path)
    }

    private fun load(path: Path) {
        if (path.isLinuxPath) {
            webView.loadUrl(Uri.fromFile(path.toFile()).toString())
            return
        }
        // SFTP, SMB, inside an archive...: read the page (up to 20 MB) and show it as it is.
        lifecycleScope.launch {
            val html = withContext(Dispatchers.IO) {
                try {
                    path.newInputStream().use { input ->
                        val bytes = input.readNBytesCompat(MAX_REMOTE_BYTES)
                        String(bytes, Charsets.UTF_8)
                    }
                } catch (e: Exception) {
                    null
                }
            }
            if (html == null) {
                showToast(R.string.om_html_open_failed)
                finish()
                return@launch
            }
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_RELOAD, Menu.NONE, R.string.reload)
        menu.add(Menu.NONE, MENU_SOURCE, Menu.NONE, R.string.om_html_edit_source)
        menu.add(Menu.NONE, MENU_BROWSER, Menu.NONE, R.string.om_html_open_browser)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val path = path ?: return super.onOptionsItemSelected(item)
        return when (item.itemId) {
            MENU_RELOAD -> {
                webView.reload()
                true
            }
            MENU_SOURCE -> {
                startActivity(Intent(this, TextEditorActivity::class.java).apply { extraPath = path })
                true
            }
            MENU_BROWSER -> {
                val intent = path.fileProviderUri.createViewIntent("text/html".asMimeType())
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .withChooser()
                startActivitySafe(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        if (this::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "OMHtml"
        private const val MAX_REMOTE_BYTES = 20 * 1024 * 1024
        private const val MENU_RELOAD = 1
        private const val MENU_SOURCE = 2
        private const val MENU_BROWSER = 3

        val EXTENSIONS = setOf("html", "htm", "xhtml", "shtml")

        fun start(context: Context, path: Path) {
            context.startActivity(Intent(context, HtmlViewerActivity::class.java).apply { extraPath = path })
        }
    }
}
