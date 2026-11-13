/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.pdf

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.databinding.ActivityPdfViewerBinding
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.size
import me.zhanghai.android.files.ui.isUiAlive
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

class PdfViewerActivity : AppActivity() {

    private lateinit var binding: ActivityPdfViewerBinding
    private lateinit var systemUiHelper: SystemUiHelper

    private var pdfEngine: PdfDocumentEngine? = null
    private var adapter: PdfPageAdapter? = null
    private var snapHelper: PagerSnapHelper = PagerSnapHelper()

    private var currentPath: Path? = null
    private var tempFile: File? = null
    private var localFile: File? = null
    private var externalUri: Uri? = null

    private var fileName: String = "document.pdf"
    private var fileSize: Long = 0L
    private var filePathString: String = ""

    private var currentPage: Int = 1
    private var totalPages: Int = 0

    private var isPagedMode: Boolean = false
    private var currentTheme: PdfReadingTheme = PdfReadingTheme.DAY

    private val badgeHandler = Handler(Looper.getMainLooper())
    private var hideBadgeRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT

        binding = ActivityPdfViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupSystemUi()
        setupWindowInsets()
        loadThemePreference()

        currentPath = intent.extraPath
        externalUri = intent.data

        if (currentPath == null && externalUri == null) {
            Toast.makeText(this, R.string.pdf_error_open, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.btnOpenExternal.setOnClickListener { openExternal() }

        initPdfDocument()
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

            val statusBarHeight = maxOf(statusBars.top, cutouts.top)
            val navBarHeight = navBars.bottom
            val leftInset = maxOf(navBars.left, cutouts.left)
            val rightInset = maxOf(navBars.right, cutouts.right)

            binding.appBarLayout.setPadding(leftInset, statusBarHeight, rightInset, 0)

            val actionBarHeight = (44 * resources.displayMetrics.density).toInt()
            binding.recyclerView.setPadding(leftInset, statusBarHeight + actionBarHeight, rightInset, navBarHeight)

            val badgeMargin = (16 * resources.displayMetrics.density).toInt() + navBarHeight
            (binding.pageBadge.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                if (lp.bottomMargin != badgeMargin) {
                    lp.bottomMargin = badgeMargin
                    binding.pageBadge.layoutParams = lp
                }
            }
            insets
        }
    }

    private fun loadThemePreference() {
        val sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val themeName = sp.getString(KEY_THEME, PdfReadingTheme.DAY.name)
        currentTheme = try {
            PdfReadingTheme.valueOf(themeName ?: PdfReadingTheme.DAY.name)
        } catch (e: Exception) {
            PdfReadingTheme.DAY
        }
        applyTheme(currentTheme, savePref = false)
    }

    private fun initPdfDocument() {
        binding.progressLoading.visibility = View.VISIBLE
        binding.errorLayout.visibility = View.GONE
        binding.recyclerView.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val path = currentPath
                val uri = externalUri

                var resolvedFile: File? = null
                var pfdToOpen: ParcelFileDescriptor? = null

                if (path != null) {
                    fileName = path.fileName.toString()
                    filePathString = path.toUri().toString()
                    fileSize = try { path.size() } catch (e: Exception) { 0L }

                    if (path.isLinuxPath && !path.isArchivePath) {
                        resolvedFile = path.toFile()
                    } else {
                        // Remote, archive or virtual path - stream into cached temp file
                        val temp = File(cacheDir, "pdf_view_${System.currentTimeMillis()}.pdf")
                        path.newInputStream().use { input ->
                            FileOutputStream(temp).use { output ->
                                input.copyTo(output)
                            }
                        }
                        tempFile = temp
                        resolvedFile = temp
                    }
                } else if (uri != null) {
                    fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
                    filePathString = uri.toString()

                    if (uri.scheme == "file") {
                        resolvedFile = uri.path?.let { File(it) }
                    } else {
                        val cr = contentResolver
                        try {
                            pfdToOpen = cr.openFileDescriptor(uri, "r")
                        } catch (e: Exception) {
                            // Fallback: copy stream to temp file
                            val temp = File(cacheDir, "pdf_view_${System.currentTimeMillis()}.pdf")
                            cr.openInputStream(uri)?.use { input ->
                                FileOutputStream(temp).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            tempFile = temp
                            resolvedFile = temp
                        }
                    }
                }

                localFile = resolvedFile
                if (fileSize == 0L && resolvedFile != null && resolvedFile.exists()) {
                    fileSize = resolvedFile.length()
                }

                val engineResult = if (pfdToOpen != null) {
                    PdfDocumentEngine.open(pfdToOpen)
                } else if (resolvedFile != null) {
                    PdfDocumentEngine.open(resolvedFile)
                } else {
                    Result.failure(IllegalArgumentException("No file source available"))
                }

                withContext(Dispatchers.Main) {
                    if (!isUiAlive) {
                        engineResult.onSuccess { it.close() }
                        return@withContext
                    }
                    binding.progressLoading.visibility = View.GONE
                    engineResult.onSuccess { engine ->
                        onEngineLoaded(engine)
                    }.onFailure { error ->
                        showError(error)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    if (!isUiAlive) return@withContext
                    binding.progressLoading.visibility = View.GONE
                    showError(e)
                }
            }
        }
    }

    private fun onEngineLoaded(engine: PdfDocumentEngine) {
        pdfEngine = engine
        totalPages = engine.pageCount

        if (totalPages <= 0) {
            binding.tvErrorMessage.setText(R.string.pdf_error_open)
            binding.errorLayout.visibility = View.VISIBLE
            return
        }

        binding.toolbar.title = fileName
        updatePageIndicator(1)

        val density = resources.displayMetrics.density
        val renderScale = density.coerceIn(1.5f, 3.2f)

        adapter = PdfPageAdapter(
            pdfEngine = engine,
            coroutineScope = lifecycleScope,
            renderScale = renderScale,
            onPageTapListener = { systemUiHelper.toggle() }
        ).apply {
            setTheme(currentTheme)
        }

        binding.recyclerView.apply {
            visibility = View.VISIBLE
            layoutManager = LinearLayoutManager(this@PdfViewerActivity, RecyclerView.VERTICAL, false)
            adapter = this@PdfViewerActivity.adapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val lm = rv.layoutManager as? LinearLayoutManager ?: return
                    val pos = lm.findFirstVisibleItemPosition()
                    if (pos != RecyclerView.NO_POSITION) {
                        val pageNum = pos + 1
                        currentPage = pageNum
                        updatePageIndicator(pageNum)
                        showPageBadge(pageNum)
                    }
                }
            })
            post {
                this@PdfViewerActivity.adapter?.containerWidth = width
            }
        }
    }

    private fun showError(error: Throwable) {
        binding.errorLayout.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE
        if (error is SecurityException) {
            binding.tvErrorMessage.setText(R.string.pdf_error_encrypted)
        } else {
            binding.tvErrorMessage.setText(R.string.pdf_error_open)
        }
    }

    private fun updatePageIndicator(page: Int) {
        if (totalPages > 0) {
            binding.toolbar.subtitle = getString(R.string.pdf_page_indicator_format, page, totalPages)
        }
    }

    private fun showPageBadge(page: Int) {
        if (totalPages <= 0) return
        binding.pageBadge.text = getString(R.string.pdf_page_badge_format, page, totalPages)
        if (binding.pageBadge.visibility != View.VISIBLE) {
            binding.pageBadge.visibility = View.VISIBLE
            binding.pageBadge.animate().alpha(1f).setDuration(150).start()
        }
        hideBadgeRunnable?.let { badgeHandler.removeCallbacks(it) }
        val r = Runnable {
            binding.pageBadge.animate()
                .alpha(0f)
                .setDuration(250)
                .withEndAction { binding.pageBadge.visibility = View.GONE }
                .start()
        }
        hideBadgeRunnable = r
        badgeHandler.postDelayed(r, 1500)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.pdf_viewer_menu, menu)
        updateMenuThemeIcon(menu.findItem(R.id.action_theme))
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val viewModeItem = menu.findItem(R.id.action_view_mode)
        if (viewModeItem != null) {
            viewModeItem.title = if (isPagedMode) {
                getString(R.string.pdf_view_continuous)
            } else {
                getString(R.string.pdf_view_paged)
            }
        }
        updateMenuThemeIcon(menu.findItem(R.id.action_theme))
        return super.onPrepareOptionsMenu(menu)
    }

    private fun updateMenuThemeIcon(item: MenuItem?) {
        if (item == null) return
        when (currentTheme) {
            PdfReadingTheme.NIGHT -> item.setIcon(R.drawable.ic_day_mode_24dp)
            else -> item.setIcon(R.drawable.ic_night_mode_24dp)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_theme -> {
                showThemeSelectionDialog()
                return true
            }
            R.id.action_jump_page -> {
                showJumpToPageDialog()
                return true
            }
            R.id.action_view_mode -> {
                toggleViewMode()
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

    private fun showThemeSelectionDialog() {
        val themes = arrayOf(
            getString(R.string.pdf_theme_day),
            getString(R.string.pdf_theme_night),
            getString(R.string.pdf_theme_sepia)
        )
        val currentIndex = when (currentTheme) {
            PdfReadingTheme.DAY -> 0
            PdfReadingTheme.NIGHT -> 1
            PdfReadingTheme.SEPIA -> 2
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pdf_menu_theme)
            .setSingleChoiceItems(themes, currentIndex) { dialog, which ->
                val selected = when (which) {
                    1 -> PdfReadingTheme.NIGHT
                    2 -> PdfReadingTheme.SEPIA
                    else -> PdfReadingTheme.DAY
                }
                applyTheme(selected, savePref = true)
                invalidateOptionsMenu()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyTheme(theme: PdfReadingTheme, savePref: Boolean = true) {
        currentTheme = theme
        binding.rootView.setBackgroundColor(theme.backgroundColor)
        adapter?.setTheme(theme)
        if (savePref) {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_THEME, theme.name)
                .apply()
        }
    }

    private fun toggleViewMode() {
        if (totalPages <= 0) return
        isPagedMode = !isPagedMode
        val targetPos = (currentPage - 1).coerceIn(0, (totalPages - 1).coerceAtLeast(0))

        if (isPagedMode) {
            binding.recyclerView.layoutManager = LinearLayoutManager(
                this, RecyclerView.HORIZONTAL, false
            )
            try {
                snapHelper.attachToRecyclerView(binding.recyclerView)
            } catch (e: Exception) {
                // Already attached
            }
            adapter?.setPagedMode(true)
        } else {
            snapHelper.attachToRecyclerView(null)
            binding.recyclerView.layoutManager = LinearLayoutManager(
                this, RecyclerView.VERTICAL, false
            )
            adapter?.setPagedMode(false)
        }
        binding.recyclerView.scrollToPosition(targetPos)
        invalidateOptionsMenu()
    }

    private fun showJumpToPageDialog() {
        if (totalPages <= 0) return
        val dialogView = layoutInflater.inflate(R.layout.dialog_pdf_jump_page, null)
        val tvPrompt = dialogView.findViewById<TextView>(R.id.tvJumpPrompt)
        val etPage = dialogView.findViewById<TextInputEditText>(R.id.etPageNumber)
        val btnFirst = dialogView.findViewById<Button>(R.id.btnJumpFirst)
        val btnLast = dialogView.findViewById<Button>(R.id.btnJumpLast)

        tvPrompt.text = getString(R.string.pdf_jump_prompt, totalPages)
        etPage.setText(currentPage.toString())
        etPage.setSelection(etPage.text?.length ?: 0)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pdf_menu_jump_page)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val target = etPage.text?.toString()?.toIntOrNull()
                if (target != null && target in 1..totalPages) {
                    jumpToPage(target)
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.pdf_invalid_page_number, totalPages),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        etPage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val target = etPage.text?.toString()?.toIntOrNull()
                if (target != null && target in 1..totalPages) {
                    jumpToPage(target)
                    dialog.dismiss()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }

        btnFirst.setOnClickListener {
            etPage.setText("1")
            jumpToPage(1)
            dialog.dismiss()
        }

        btnLast.setOnClickListener {
            etPage.setText(totalPages.toString())
            jumpToPage(totalPages)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun jumpToPage(page: Int) {
        val targetPos = (page - 1).coerceIn(0, totalPages - 1)
        val lm = binding.recyclerView.layoutManager as? LinearLayoutManager
        if (lm != null) {
            lm.scrollToPositionWithOffset(targetPos, 0)
        } else {
            binding.recyclerView.scrollToPosition(targetPos)
        }
        currentPage = page
        updatePageIndicator(page)
        showPageBadge(page)
    }

    private fun showDocumentInfoDialog() {
        val sb = StringBuilder().apply {
            append("• ").append(getString(R.string.pdf_info_filename)).append(": ").append(fileName).append("\n\n")
            append("• ").append(getString(R.string.pdf_info_pages)).append(": ").append(totalPages).append("\n\n")
            if (fileSize > 0) {
                val formatted = Formatter.formatFileSize(this@PdfViewerActivity, fileSize)
                val exact = String.format("%,d", fileSize)
                append("• ").append(getString(R.string.pdf_info_size)).append(": ").append(formatted)
                    .append(" (").append(exact).append(" bytes)\n\n")
            }
            if (filePathString.isNotEmpty()) {
                append("• ").append(getString(R.string.pdf_info_path)).append(": ").append(filePathString)
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pdf_menu_info)
            .setMessage(sb.toString())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun shareDocument() {
        val path = currentPath
        if (path != null) {
            try {
                val intent = path.fileProviderUri.createSendStreamIntent(MimeType.PDF)
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
                type = "application/pdf"
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
                val intent = path.fileProviderUri.createViewIntent(MimeType.PDF)
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
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivitySafe(Intent.createChooser(intent, getString(R.string.file_item_action_open_with)))
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.recyclerView.post {
            adapter?.containerWidth = binding.recyclerView.width
            adapter?.notifyDataSetChanged()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hideBadgeRunnable?.let { badgeHandler.removeCallbacks(it) }
        try {
            pdfEngine?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        pdfEngine = null
        adapter?.clearCache()
        adapter = null
        tempFile?.delete()
        tempFile = null
    }

    companion object {
        private const val PREFS_NAME = "pdf_viewer_prefs"
        private const val KEY_THEME = "reading_theme"

        fun start(context: Context, path: Path) {
            val intent = Intent(context, PdfViewerActivity::class.java).apply {
                extraPath = path
            }
            context.startActivity(intent)
        }
    }
}
