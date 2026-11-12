/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.docx

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.databinding.ActivityDocxViewerBinding
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asMimeType
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.size
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.util.createSendStreamIntent
import me.zhanghai.android.files.util.createViewIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.mediumAnimTime
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.withChooser
import me.zhanghai.android.systemuihelper.SystemUiHelper
import java.io.File
import java.io.FileOutputStream

class DocxViewerActivity : AppActivity() {

    private lateinit var binding: ActivityDocxViewerBinding
    private lateinit var systemUiHelper: SystemUiHelper

    private var currentPath: Path? = null
    private var tempFile: File? = null
    private var localFile: File? = null
    private var externalUri: Uri? = null

    private var fileName: String = "document.docx"
    private var fileSize: Long = 0L
    private var filePathString: String = ""

    private var currentDocument: DocxDocument? = null
    private var currentTheme: DocxReadingTheme = DocxReadingTheme.DAY
    private var currentTextZoom: Int = 100

    private var isSearchOpen: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT

        binding = ActivityDocxViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupSystemUi()
        setupWindowInsets()
        loadPreferences()
        setupWebView()
        setupSearchBar()

        currentPath = intent.extraPath
        externalUri = intent.data

        if (currentPath == null && externalUri == null) {
            Toast.makeText(this, R.string.docx_error_open, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.btnOpenExternal.setOnClickListener { openExternal() }

        loadDocument()
    }

    private fun setupSystemUi() {
        systemUiHelper = SystemUiHelper(
            this, SystemUiHelper.LEVEL_IMMERSIVE, SystemUiHelper.FLAG_IMMERSIVE_STICKY
        ) { visible: Boolean ->
            binding.appBarLayout.animate()
                .alpha(if (visible) 1f else 0f)
                .translationY(if (visible) 0f else -binding.appBarLayout.bottom.toFloat())
                .setDuration(mediumAnimTime.toLong())
                .setInterpolator(FastOutSlowInInterpolator())
                .start()
        }
        systemUiHelper.show()
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootView) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val cutouts = insets.getInsets(WindowInsetsCompat.Type.displayCutout())

            val topInset = maxOf(statusBars.top, cutouts.top)
            val leftInset = maxOf(navBars.left, cutouts.left)
            val rightInset = maxOf(navBars.right, cutouts.right)
            val bottomInset = navBars.bottom

            binding.appBarLayout.setPadding(leftInset, topInset, rightInset, 0)
            binding.webView.setPadding(leftInset, 0, rightInset, bottomInset)
            insets
        }
    }

    private fun loadPreferences() {
        val sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val themeName = sp.getString(KEY_THEME, DocxReadingTheme.DAY.name)
        currentTheme = try {
            DocxReadingTheme.valueOf(themeName ?: DocxReadingTheme.DAY.name)
        } catch (_: Exception) {
            DocxReadingTheme.DAY
        }
        currentTextZoom = sp.getInt(KEY_TEXT_ZOOM, 100).coerceIn(60, 250)
    }

    private fun setupWebView() {
        binding.webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
                textZoom = currentTextZoom
                allowFileAccess = false
                allowContentAccess = false
            }
            setBackgroundColor(currentTheme.backgroundColor)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:")) {
                        promptOpenLink(url)
                        return true
                    }
                    return false
                }
            }

            setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
                if (numberOfMatches > 0) {
                    binding.tvMatchCount.text = getString(
                        R.string.docx_search_matches,
                        activeMatchOrdinal + 1,
                        numberOfMatches
                    )
                } else {
                    binding.tvMatchCount.text = getString(R.string.docx_search_no_matches)
                }
            }
        }
    }

    private fun setupSearchBar() {
        binding.etSearchQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""
                if (query.isNotEmpty()) {
                    binding.webView.findAllAsync(query)
                } else {
                    binding.webView.clearMatches()
                    binding.tvMatchCount.text = ""
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.etSearchQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                binding.webView.findNext(true)
                true
            } else {
                false
            }
        }

        binding.btnSearchNext.setOnClickListener {
            binding.webView.findNext(true)
        }

        binding.btnSearchPrev.setOnClickListener {
            binding.webView.findNext(false)
        }

        binding.btnSearchClose.setOnClickListener {
            closeSearchBar()
        }
    }

    private fun loadDocument() {
        binding.progressLoading.visibility = View.VISIBLE
        binding.errorLayout.visibility = View.GONE
        binding.webView.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val path = currentPath
                val uri = externalUri
                var resolvedFile: File? = null

                if (path != null) {
                    fileName = path.fileName.toString()
                    filePathString = path.toUri().toString()
                    fileSize = try { path.size() } catch (_: Exception) { 0L }

                    if (path.isLinuxPath && !path.isArchivePath) {
                        resolvedFile = path.toFile()
                    } else {
                        val temp = File(cacheDir, "docx_view_${System.currentTimeMillis()}.docx")
                        path.newInputStream().use { input ->
                            FileOutputStream(temp).use { output ->
                                input.copyTo(output)
                            }
                        }
                        tempFile = temp
                        resolvedFile = temp
                    }
                } else if (uri != null) {
                    fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "document.docx"
                    filePathString = uri.toString()

                    if (uri.scheme == "file") {
                        resolvedFile = uri.path?.let { File(it) }
                    } else {
                        val cr = contentResolver
                        val temp = File(cacheDir, "docx_view_${System.currentTimeMillis()}.docx")
                        cr.openInputStream(uri)?.use { input ->
                            FileOutputStream(temp).use { output ->
                                input.copyTo(output)
                            }
                        }
                        tempFile = temp
                        resolvedFile = temp
                    }
                }

                localFile = resolvedFile
                if (fileSize == 0L && resolvedFile != null && resolvedFile.exists()) {
                    fileSize = resolvedFile.length()
                }

                if (resolvedFile == null || !resolvedFile.exists()) {
                    throw IllegalArgumentException("Could not resolve document file")
                }

                val parseResult = DocxDocumentEngine.parse(resolvedFile, currentTheme)

                withContext(Dispatchers.Main) {
                    binding.progressLoading.visibility = View.GONE
                    parseResult.onSuccess { doc ->
                        onDocumentLoaded(doc)
                    }.onFailure { err ->
                        showError(err)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.progressLoading.visibility = View.GONE
                    showError(e)
                }
            }
        }
    }

    private fun onDocumentLoaded(doc: DocxDocument) {
        currentDocument = doc
        binding.toolbar.title = doc.metadata.fileName

        val formattedWords = String.format("%,d", doc.metadata.wordCount)
        binding.toolbar.subtitle = getString(
            R.string.docx_subtitle_format,
            formattedWords,
            doc.metadata.readingTimeMinutes
        )

        binding.rootView.setBackgroundColor(currentTheme.backgroundColor)
        binding.webView.setBackgroundColor(currentTheme.backgroundColor)
        binding.webView.visibility = View.VISIBLE

        binding.webView.loadDataWithBaseURL(
            "file:///android_asset/",
            doc.htmlContent,
            "text/html",
            "UTF-8",
            null
        )
    }

    private fun showError(error: Throwable) {
        binding.errorLayout.visibility = View.VISIBLE
        binding.webView.visibility = View.GONE
        binding.tvErrorMessage.text = getString(R.string.docx_error_open)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.docx_viewer_menu, menu)
        updateMenuThemeIcon(menu.findItem(R.id.action_theme))
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        updateMenuThemeIcon(menu.findItem(R.id.action_theme))
        return super.onPrepareOptionsMenu(menu)
    }

    private fun updateMenuThemeIcon(item: MenuItem?) {
        if (item == null) return
        when (currentTheme) {
            DocxReadingTheme.NIGHT -> item.setIcon(R.drawable.ic_day_mode_24dp)
            else -> item.setIcon(R.drawable.ic_night_mode_24dp)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_search -> {
                toggleSearchBar()
                return true
            }
            R.id.action_text_size -> {
                showTextSizeDialog()
                return true
            }
            R.id.action_theme -> {
                showThemeSelectionDialog()
                return true
            }
            R.id.action_copy_text -> {
                copyFullText()
                return true
            }
            R.id.action_print -> {
                printDocument()
                return true
            }
            R.id.action_info -> {
                showDocumentInfoDialog()
                return true
            }
            R.id.action_share -> {
                shareDocument()
                return true
            }
            R.id.action_open_external -> {
                openExternal()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun toggleSearchBar() {
        if (isSearchOpen) {
            closeSearchBar()
        } else {
            openSearchBar()
        }
    }

    private fun openSearchBar() {
        isSearchOpen = true
        binding.searchBarContainer.visibility = View.VISIBLE
        binding.etSearchQuery.requestFocus()
    }

    private fun closeSearchBar() {
        isSearchOpen = false
        binding.searchBarContainer.visibility = View.GONE
        binding.etSearchQuery.setText("")
        binding.webView.clearMatches()
    }

    private fun showTextSizeDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_docx_text_size, null)
        val tvZoomValue = dialogView.findViewById<TextView>(R.id.tvZoomValue)
        val seekBar = dialogView.findViewById<SeekBar>(R.id.seekBarZoom)
        val tvPreview = dialogView.findViewById<TextView>(R.id.tvZoomPreview)
        val btn80 = dialogView.findViewById<Button>(R.id.btnZoom80)
        val btn100 = dialogView.findViewById<Button>(R.id.btnZoom100)
        val btn125 = dialogView.findViewById<Button>(R.id.btnZoom125)
        val btn150 = dialogView.findViewById<Button>(R.id.btnZoom150)

        // SeekBar 0..100 maps to 70%..200%
        fun zoomToProgress(zoom: Int): Int = ((zoom - 70) * 100 / 130).coerceIn(0, 100)
        fun progressToZoom(progress: Int): Int = 70 + (progress * 130 / 100)

        fun updateUi(zoom: Int) {
            tvZoomValue.text = "$zoom%"
            tvPreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, (15f * zoom / 100f))
        }

        seekBar.progress = zoomToProgress(currentTextZoom)
        updateUi(currentTextZoom)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val z = progressToZoom(progress)
                updateUi(z)
                applyTextZoom(z)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        btn80.setOnClickListener {
            seekBar.progress = zoomToProgress(80)
            applyTextZoom(80)
            updateUi(80)
        }
        btn100.setOnClickListener {
            seekBar.progress = zoomToProgress(100)
            applyTextZoom(100)
            updateUi(100)
        }
        btn125.setOnClickListener {
            seekBar.progress = zoomToProgress(125)
            applyTextZoom(125)
            updateUi(125)
        }
        btn150.setOnClickListener {
            seekBar.progress = zoomToProgress(150)
            applyTextZoom(150)
            updateUi(150)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.docx_text_size_title)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_TEXT_ZOOM, currentTextZoom)
                    .apply()
            }
            .show()
    }

    private fun applyTextZoom(zoom: Int) {
        currentTextZoom = zoom
        binding.webView.settings.textZoom = zoom
    }

    private fun showThemeSelectionDialog() {
        val themes = arrayOf(
            getString(R.string.docx_theme_day),
            getString(R.string.docx_theme_night),
            getString(R.string.docx_theme_sepia)
        )
        val currentIndex = when (currentTheme) {
            DocxReadingTheme.DAY -> 0
            DocxReadingTheme.NIGHT -> 1
            DocxReadingTheme.SEPIA -> 2
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.docx_menu_theme)
            .setSingleChoiceItems(themes, currentIndex) { dialog, which ->
                val selected = when (which) {
                    1 -> DocxReadingTheme.NIGHT
                    2 -> DocxReadingTheme.SEPIA
                    else -> DocxReadingTheme.DAY
                }
                applyTheme(selected)
                invalidateOptionsMenu()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyTheme(theme: DocxReadingTheme) {
        currentTheme = theme
        binding.rootView.setBackgroundColor(theme.backgroundColor)
        binding.webView.setBackgroundColor(theme.backgroundColor)

        // Seamless dynamic CSS class update in WebView without reload!
        binding.webView.evaluateJavascript("setThemeClass('${theme.cssClass}');", null)

        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, theme.name)
            .apply()
    }

    private fun copyFullText() {
        val doc = currentDocument ?: return
        val text = doc.plainText
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.docx_error_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText(doc.metadata.fileName, text)
        cm?.setPrimaryClip(clip)
        Toast.makeText(this, R.string.docx_text_copied, Toast.LENGTH_SHORT).show()
    }

    private fun printDocument() {
        val printManager = getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        val jobName = "${getString(R.string.app_name)} - $fileName"
        val printAdapter = binding.webView.createPrintDocumentAdapter(jobName)
        printManager.print(jobName, printAdapter, PrintAttributes.Builder().build())
    }

    private fun showDocumentInfoDialog() {
        val doc = currentDocument ?: return
        val meta = doc.metadata

        val formatStr = if (meta.isLegacyDoc) {
            getString(R.string.docx_info_format_doc)
        } else {
            getString(R.string.docx_info_format_docx)
        }

        val sb = StringBuilder().apply {
            append("• ").append(getString(R.string.docx_info_filename)).append(": ").append(meta.fileName).append("\n\n")
            append("• ").append(getString(R.string.docx_info_format)).append(": ").append(formatStr).append("\n\n")
            append("• ").append(getString(R.string.docx_info_words)).append(": ")
                .append(String.format("%,d", meta.wordCount))
                .append(" (~").append(meta.readingTimeMinutes).append(" min)\n\n")
            append("• ").append(getString(R.string.docx_info_characters)).append(": ")
                .append(String.format("%,d", meta.charCount)).append("\n\n")
            append("• ").append(getString(R.string.docx_info_paragraphs)).append(": ")
                .append(meta.paragraphCount).append("\n\n")
            if (meta.tableCount > 0) {
                append("• ").append(getString(R.string.docx_info_tables)).append(": ").append(meta.tableCount).append("\n\n")
            }
            if (meta.imageCount > 0) {
                append("• ").append(getString(R.string.docx_info_images)).append(": ").append(meta.imageCount).append("\n\n")
            }
            if (fileSize > 0) {
                val formatted = Formatter.formatFileSize(this@DocxViewerActivity, fileSize)
                val exact = String.format("%,d", fileSize)
                append("• ").append(getString(R.string.docx_info_size)).append(": ").append(formatted)
                    .append(" (").append(exact).append(" bytes)\n\n")
            }
            if (filePathString.isNotEmpty()) {
                append("• ").append(getString(R.string.docx_info_path)).append(": ").append(filePathString)
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.docx_menu_info)
            .setMessage(sb.toString())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun promptOpenLink(url: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.docx_link_open_title)
            .setMessage(getString(R.string.docx_link_open_message, url))
            .setPositiveButton(R.string.docx_link_open_action) { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivitySafe(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, e.localizedMessage, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun shareDocument() {
        val path = currentPath
        if (path != null) {
            try {
                val mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document".asMimeType()
                val intent = path.fileProviderUri.createSendStreamIntent(mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .withChooser()
                startActivitySafe(intent)
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        val file = localFile ?: tempFile
        if (file != null && file.exists()) {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.file_provider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivitySafe(Intent.createChooser(intent, getString(R.string.share)))
        }
    }

    private fun openExternal() {
        val path = currentPath
        if (path != null) {
            try {
                val mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document".asMimeType()
                val intent = path.fileProviderUri.createViewIntent(mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .withChooser()
                startActivitySafe(intent)
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        val file = localFile ?: tempFile
        if (file != null && file.exists()) {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.file_provider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivitySafe(Intent.createChooser(intent, getString(R.string.file_item_action_open_with)))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.webView.destroy()
        tempFile?.delete()
        tempFile = null
    }

    companion object {
        private const val PREFS_NAME = "docx_viewer_prefs"
        private const val KEY_THEME = "reading_theme"
        private const val KEY_TEXT_ZOOM = "text_zoom"

        fun start(context: Context, path: Path) {
            val intent = Intent(context, DocxViewerActivity::class.java).apply {
                extraPath = path
            }
            context.startActivity(intent)
        }
    }
}
