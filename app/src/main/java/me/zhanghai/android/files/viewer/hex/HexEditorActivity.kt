/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.hex

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.getSystemService
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import java8.nio.file.StandardOpenOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.databinding.ActivityHexEditorBinding
import me.zhanghai.android.files.databinding.DialogHexEditByteBinding
import me.zhanghai.android.files.databinding.DialogHexFillBinding
import me.zhanghai.android.files.databinding.DialogHexGotoBinding
import me.zhanghai.android.files.databinding.DialogHexInsertBinding
import me.zhanghai.android.files.databinding.ItemHexInspectorCardBinding
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.provider.common.newByteChannel
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.root.DeadManProtectionManager
import me.zhanghai.android.files.theme.custom.OpenManagerPalette
import me.zhanghai.android.files.ui.applyEdgeToEdgeInsets
import me.zhanghai.android.files.ui.showSafeDialog
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.showToast
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.security.MessageDigest

/**
 * HEX editor for files of any size: the file is read page by page from disk ([HexSource]) and the
 * edits live in a piece table ([HexBuffer]). Saving writes only the edited bytes when the size did
 * not change, and rewrites the file through a temporary copy otherwise.
 */
class HexEditorActivity : AppActivity() {

    private lateinit var binding: ActivityHexEditorBinding
    private lateinit var path: Path

    private var buffer: HexBuffer = HexBuffer(ByteArray(0))
    private lateinit var adapter: HexRowAdapter
    private var monoTypeface: Typeface = Typeface.MONOSPACE

    private var currentOffset: Long = 0
    private var isReadOnly: Boolean = false
    private var currentCharset: Charset = Charsets.UTF_8

    // Search state
    private var isSearchHexMode = true
    private var lastSearchQuery = ""
    private var lastMatchOffset = -1L
    private var searchJob: Job? = null

    /** Set while the rewritten file waits in the cache to be copied over the original. */
    private var pendingRewrite: File? = null
    private var isSaving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHexEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.applyEdgeToEdgeInsets(applyTop = true, applyHorizontal = true)
        binding.bottomInspectorPanel.applyEdgeToEdgeInsets(applyBottom = true, applyHorizontal = true)

        path = intent.extraPath ?: run {
            finish()
            return
        }

        monoTypeface = try {
            Typeface.createFromAsset(assets, "Mono.ttf")
        } catch (e: Exception) {
            Typeface.MONOSPACE
        }

        setupToolbar()
        setupInspector()
        setupSearchPanel()
        setupRecyclerView()
        loadFileContent()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.searchBarContainer.visibility == View.VISIBLE) {
                    closeSearch()
                    return
                }
                if (buffer.isModified || pendingRewrite != null) {
                    showUnsavedChangesDialog()
                } else {
                    finish()
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        searchJob?.cancel()
        runCatching { buffer.source.close() }
    }

    private fun setupToolbar() {
        binding.toolbar.title = path.fileName.toString()
        binding.toolbar.subtitle = getString(R.string.hex_editor_bytes_count, 0)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupRecyclerView() {
        adapter = HexRowAdapter(
            buffer, monoTypeface,
            onByteTapped = { offset ->
                if (offset == currentOffset && !isReadOnly) showEditByteDialog(offset) else selectOffset(offset)
            },
            onByteLongPressed = { offset ->
                selectOffset(offset)
                if (isReadOnly) showToast(R.string.hex_editor_readonly) else showEditByteDialog(offset)
            }
        )
        binding.rvHex.layoutManager = LinearLayoutManager(this)
        binding.rvHex.adapter = adapter
        binding.rvHex.itemAnimator = null
        // A thumb to drag through big files (millions of rows), as in the explorer.
        me.zhanghai.android.files.ui.ThemedFastScroller.create(binding.rvHex)
        // 16 bytes per row when they fit the screen, 8 otherwise (portrait phones), so the ASCII
        // column is always visible without scrolling sideways.
        binding.hsvContent.addOnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) v.post { fitRowsToWidth() }
        }
    }

    private fun fitRowsToWidth() {
        val width = binding.hsvContent.width
        if (width <= 0) return
        val paint = TextView(this).paint.apply {
            typeface = monoTypeface
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11.5f, resources.displayMetrics)
        }
        val charWidth = paint.measureText("0")
        // Row paddings (8 + 12), the gap after the offset (8) and the divider with its margins (17).
        val chrome = 45f * resources.displayMetrics.density
        val digits = adapter.offsetDigits
        val perRow = if (HexRowAdapter.rowChars(16, digits) * charWidth + chrome <= width) 16 else 8
        // Rows fill the width: the font grows into the room left (up to 15 sp).
        val needed = HexRowAdapter.rowChars(perRow, digits) * charWidth + chrome
        val textSp = (11.5f * (width / needed)).coerceIn(11.5f, 15f).let { (it * 2).toInt() / 2f }
        adapter.textSizeSp = textSp
        val firstVisible = (binding.rvHex.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
            .coerceAtLeast(0).toLong() * adapter.bytesPerRow
        adapter.bytesPerRow = perRow
        // Same font and size as the rows, so the column titles sit over their bytes.
        for (header in listOf(binding.tvHeaderOffset, binding.tvHeaderHex, binding.tvHeaderAscii)) {
            header.typeface = Typeface.create(monoTypeface, Typeface.BOLD)
            header.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
        }
        binding.tvHeaderOffset.text = "OFFSET".padEnd(digits)
        binding.tvHeaderHex.text = buildString {
            for (i in 0 until perRow) {
                append(String.format("%02X", i))
                append(if (i == perRow / 2 - 1) "  " else " ")
            }
        }
        binding.tvHeaderAscii.text = "0123456789ABCDEF".substring(0, perRow)
        (binding.rvHex.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(adapter.rowOf(firstVisible), 0)
        if (BuildConfig.DEBUG) Log.d(TAG, "Rows: $perRow bytes per row, ${textSp}sp, width=$width")
    }

    private val inspectorCards: List<Pair<ItemHexInspectorCardBinding, String>> by lazy {
        listOf(
            binding.cardHex to "HEX",
            binding.cardDec to "UINT8",
            binding.cardInt8 to "INT8",
            binding.cardInt16 to "INT16 LE",
            binding.cardInt32 to "INT32 LE",
            binding.cardChar to "ASCII",
            binding.cardBin to getString(R.string.om_hex_card_binary),
            binding.cardFloat to "FLOAT32 LE"
        )
    }

    private fun setupInspector() {
        for ((card, label) in inspectorCards) {
            card.tvCardLabel.text = label
            card.root.setOnClickListener {
                val text = card.tvCardValue.text
                if (text.isNotEmpty() && text != "-") copyToClipboard(text.toString())
            }
        }
        binding.offsetCell.setOnClickListener { showGotoOffsetDialog() }
        applyBarColors()
    }

    /** The bottom strip takes the explorer's bottom bar colors, like the editor's symbol bar. */
    private fun applyBarColors() {
        val palette = OpenManagerPalette.from(this)
        binding.bottomInspectorPanel.setBackgroundColor(palette.bottomBarBg)
        binding.bottomDivider.setBackgroundColor(palette.dividerLineColor)
        binding.layoutChips.dividerDrawable = GradientDrawable().apply {
            setColor(palette.dividerLineColor)
            setSize((resources.displayMetrics.density).toInt().coerceAtLeast(1), 1)
        }
        binding.tvSelectionLabel.setTextColor(palette.textSecondary)
        binding.tvSelectionInfo.setTextColor(palette.accentColor)
        for ((card, _) in inspectorCards) {
            card.tvCardLabel.setTextColor(palette.textSecondary)
            card.tvCardValue.setTextColor(palette.textPrimary)
        }
        runCatching { window.navigationBarColor = palette.bottomBarBg }
    }

    private fun setupSearchPanel() {
        binding.btnSearchMode.setOnClickListener {
            isSearchHexMode = !isSearchHexMode
            binding.btnSearchMode.text = if (isSearchHexMode) "HEX" else "TXT"
            binding.cbMatchCase.visibility = if (isSearchHexMode) View.GONE else View.VISIBLE
            binding.etSearch.hint = if (isSearchHexMode) {
                getString(R.string.hex_editor_search_hint)
            } else {
                getString(R.string.om_hex_search_text_hint)
            }
        }

        binding.btnSearchNext.setOnClickListener { performSearch(forward = true) }
        binding.btnSearchPrev.setOnClickListener { performSearch(forward = false) }

        binding.btnToggleReplace.setOnClickListener {
            val isVisible = binding.replaceContainer.visibility == View.VISIBLE
            binding.replaceContainer.visibility = if (isVisible) View.GONE else View.VISIBLE
        }

        binding.btnCloseSearch.setOnClickListener { closeSearch() }

        binding.btnReplace.setOnClickListener { performReplaceCurrent() }
        binding.btnReplaceAll.setOnClickListener { performReplaceAll() }
    }

    // ---- opening -----------------------------------------------------------------------------

    /**
     * Small files are read whole; larger ones through a seekable channel, a page at a time. Paths
     * whose provider cannot seek (some archives, content URIs) fall back to memory up to
     * [IN_MEMORY_MAX].
     */
    private fun openSource(): HexSource {
        val size = java8.nio.file.Files.size(path)
        if (size > IN_MEMORY_THRESHOLD) {
            try {
                val channel = path.newByteChannel()
                try {
                    channel.position(0)
                    return ChannelHexSource.of(channel, size)
                } catch (e: Exception) {
                    channel.close()
                    throw e
                }
            } catch (e: Exception) {
                if (size > IN_MEMORY_MAX) throw e
            }
        }
        return ByteArrayHexSource(path.newInputStream().use { it.readBytes() })
    }

    private fun loadFileContent(keepOffset: Long = 0, onLoaded: (() -> Unit)? = null) {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val start = SystemClock.elapsedRealtime()
            val source = withContext(Dispatchers.IO) {
                try {
                    openSource()
                } catch (e: OutOfMemoryError) {
                    null
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            binding.progressBar.visibility = View.GONE
            if (source == null) {
                showToast(getString(R.string.om_hex_read_or_oom_error))
                if (buffer.size == 0L) finish()
                return@launch
            }
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG, "Open size=${source.size} source=${if (source is ChannelHexSource) "paged" else "memory"} " +
                        "ms=${SystemClock.elapsedRealtime() - start} heapMb=${usedHeapMb()}"
                )
            }
            val old = buffer
            buffer = HexBuffer(source)
            if (old.source !== source) runCatching { old.source.close() }
            adapter.updateBuffer(buffer)
            fitRowsToWidth()
            selectOffset(keepOffset)
            onLoaded?.invoke()
            invalidateOptionsMenu()
            if (BuildConfig.DEBUG && onLoaded == null) runDemo()
        }
    }

    /**
     * E2E only (debug builds): DEMO_HEX_SEARCH finds a hex pattern through the search panel, then
     * DEMO_HEX_WRITE overwrites it there (saved in place) or DEMO_HEX_INSERT inserts bytes at the
     * start (saved by rewriting the file).
     */
    private fun runDemo() {
        val search = intent.getStringExtra("DEMO_HEX_SEARCH")
        val write = intent.getStringExtra("DEMO_HEX_WRITE")?.let { parseHexPattern(it) }
        val insert = intent.getStringExtra("DEMO_HEX_INSERT")?.let { parseHexPattern(it) }
        intent.removeExtra("DEMO_HEX_SEARCH")
        intent.removeExtra("DEMO_HEX_WRITE")
        intent.removeExtra("DEMO_HEX_INSERT")
        if (insert != null) {
            buffer.insertBytes(0, insert)
            onBufferEdited(0)
            saveFile()
        }
        if (search != null) {
            binding.searchBarContainer.visibility = View.VISIBLE
            binding.etSearch.setText(search)
            performSearch(forward = true) { match ->
                if (write != null) {
                    buffer.modifyBytes(match, write)
                    onBufferEdited(match)
                    saveFile()
                }
            }
        }
    }

    private fun usedHeapMb(): Long = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / (1024 * 1024) }

    // ---- selection ---------------------------------------------------------------------------

    private fun selectOffset(offset: Long) {
        if (buffer.size == 0L) {
            updateToolbarInfo()
            return
        }
        currentOffset = offset.coerceIn(0, buffer.size - 1)
        adapter.selectedOffset = currentOffset

        val data = HexInspector.inspect(buffer, currentOffset)
        binding.cardHex.tvCardValue.text = data.hexStr
        binding.cardDec.tvCardValue.text = data.uint8Str
        binding.cardInt8.tvCardValue.text = data.int8Str
        binding.cardInt16.tvCardValue.text = data.int16LEStr
        binding.cardInt32.tvCardValue.text = data.int32LEStr
        binding.cardChar.tvCardValue.text = data.charStr
        binding.cardBin.tvCardValue.text = data.binStr
        binding.cardFloat.tvCardValue.text = data.float32LEStr

        binding.tvSelectionInfo.text = String.format("0x%0${adapter.offsetDigits}X", currentOffset)
        updateToolbarInfo()
    }

    private fun scrollTo(offset: Long) {
        (binding.rvHex.layoutManager as LinearLayoutManager)
            .scrollToPositionWithOffset((adapter.rowOf(offset) - 3).coerceAtLeast(0), 0)
    }

    private fun updateToolbarInfo() {
        val dirtyMark = if (buffer.isModified || pendingRewrite != null) "* " else ""
        binding.toolbar.title = "$dirtyMark${path.fileName}"
        binding.toolbar.subtitle = String.format("%s • 0x%0${adapter.offsetDigits}X", formatFileSize(buffer.size), currentOffset)
    }

    private fun onBufferEdited(selectAt: Long = currentOffset) {
        adapter.updateBuffer(buffer)
        selectOffset(selectAt)
        invalidateOptionsMenu()
    }

    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> String.format("%.1f KB", size / 1024.0)
            size < 1024L * 1024 * 1024 -> String.format("%.2f MB", size / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0))
        }
    }

    // ---- search ------------------------------------------------------------------------------

    private fun currentPattern(query: String): ByteArray? =
        if (isSearchHexMode) parseHexPattern(query) else query.toByteArray(currentCharset)

    private fun performSearch(forward: Boolean, onFound: ((Long) -> Unit)? = null) {
        val query = binding.etSearch.text.toString().trim()
        if (query.isEmpty()) return
        val pattern = currentPattern(query)
        if (pattern == null || pattern.isEmpty()) {
            binding.tvSearchStatus.text = getString(R.string.hex_editor_invalid_hex)
            return
        }
        val searchStart = if (lastSearchQuery == query && lastMatchOffset != -1L) {
            if (forward) lastMatchOffset + 1 else lastMatchOffset - 1
        } else {
            currentOffset
        }
        lastSearchQuery = query
        val ignoreCase = !isSearchHexMode && !binding.cbMatchCase.isChecked
        val target = buffer

        searchJob?.cancel()
        binding.tvSearchStatus.text = getString(R.string.om_hex_searching)
        searchJob = lifecycleScope.launch {
            val start = SystemClock.elapsedRealtime()
            val match = withContext(Dispatchers.IO) {
                target.search(pattern, searchStart, forward, ignoreCase) { isActive }
            }
            if (target !== buffer) return@launch
            if (BuildConfig.DEBUG) Log.d(TAG, "Search match=$match ms=${SystemClock.elapsedRealtime() - start}")
            if (match != -1L) {
                lastMatchOffset = match
                adapter.highlightRange = match until (match + pattern.size)
                selectOffset(match)
                scrollTo(match)
                binding.tvSearchStatus.text = getString(R.string.hex_editor_match_status, match)
                onFound?.invoke(match)
            } else {
                binding.tvSearchStatus.text = getString(R.string.hex_editor_not_found)
            }
        }
    }

    private fun performReplaceCurrent() {
        if (isReadOnly) {
            showToast(R.string.hex_editor_readonly)
            return
        }
        val targetQuery = binding.etSearch.text.toString().trim()
        val replaceQuery = binding.etReplace.text.toString().trim()
        if (targetQuery.isEmpty() || lastMatchOffset == -1L) return

        val targetBytes = currentPattern(targetQuery)
        val replaceBytes = currentPattern(replaceQuery)
        if (targetBytes == null || replaceBytes == null) {
            showToast(R.string.hex_editor_invalid_hex)
            return
        }

        buffer.replace(lastMatchOffset, targetBytes.size, replaceBytes)
        onBufferEdited(lastMatchOffset)
        performSearch(forward = true)
    }

    private fun performReplaceAll() {
        if (isReadOnly) {
            showToast(R.string.hex_editor_readonly)
            return
        }
        val targetQuery = binding.etSearch.text.toString().trim()
        val replaceQuery = binding.etReplace.text.toString().trim()
        if (targetQuery.isEmpty()) return

        val targetBytes = currentPattern(targetQuery)
        val replaceBytes = currentPattern(replaceQuery)
        if (targetBytes == null || targetBytes.isEmpty() || replaceBytes == null) {
            showToast(R.string.hex_editor_invalid_hex)
            return
        }
        val ignoreCase = !isSearchHexMode && !binding.cbMatchCase.isChecked
        val target = buffer

        searchJob?.cancel()
        binding.tvSearchStatus.text = getString(R.string.om_hex_searching)
        searchJob = lifecycleScope.launch {
            val matches = withContext(Dispatchers.IO) {
                target.findAll(targetBytes, ignoreCase) { isActive }
            }
            if (target !== buffer) return@launch
            // The edit itself only rewrites the piece list, on the main thread.
            target.replaceAt(matches, targetBytes.size, replaceBytes)
            onBufferEdited()
            showToast(getString(R.string.om_hex_replacements_done_format, matches.size))
            binding.tvSearchStatus.text = getString(R.string.om_hex_replacements_count_format, matches.size)
        }
    }

    private fun closeSearch() {
        searchJob?.cancel()
        binding.searchBarContainer.visibility = View.GONE
        adapter.highlightRange = null
        lastMatchOffset = -1
    }

    // ---- dialogs -----------------------------------------------------------------------------

    private fun showEditByteDialog(offset: Long) {
        if (buffer.size == 0L) return
        val dialogBinding = DialogHexEditByteBinding.inflate(layoutInflater)
        dialogBinding.tvTargetOffset.text = String.format("Offset: 0x%08X (%d)", offset, offset)

        val curByte = buffer.getByte(offset).toInt() and 0xFF
        dialogBinding.etValue.setText(String.format("%02X", curByte))
        dialogBinding.etValue.selectAll()

        dialogBinding.rgInputType.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbHex) {
                dialogBinding.tilValue.hint = getString(R.string.hex_editor_input_hex_hint)
            } else {
                dialogBinding.tilValue.hint = getString(R.string.hex_editor_input_text_hint)
            }
        }

        val updatePreview: () -> Unit = {
            val text = dialogBinding.etValue.text.toString()
            if (dialogBinding.rbHex.isChecked) {
                val bytes = parseHexPattern(text)
                if (bytes != null) {
                    val ascii = bytes.map { b ->
                        val u = b.toInt() and 0xFF
                        if (u in 32..126) u.toChar() else '.'
                    }.joinToString("")
                    dialogBinding.tvPreview.text = getString(R.string.om_hex_preview_text_format, bytes.size, ascii)
                } else {
                    dialogBinding.tvPreview.text = getString(R.string.om_hex_invalid_hex)
                }
            } else {
                val bytes = text.toByteArray(currentCharset)
                val hexStr = bytes.joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }
                dialogBinding.tvPreview.text = getString(R.string.om_hex_preview_hex_format, bytes.size, hexStr)
            }
        }

        dialogBinding.etValue.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updatePreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hex_editor_edit_byte)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = dialogBinding.etValue.text.toString()
                val bytes = if (dialogBinding.rbHex.isChecked) {
                    parseHexPattern(text)
                } else {
                    text.toByteArray(currentCharset)
                }

                if (bytes == null || bytes.isEmpty()) {
                    showToast(R.string.hex_editor_invalid_hex)
                    return@setPositiveButton
                }

                if (dialogBinding.rbOverwrite.isChecked) {
                    buffer.modifyBytes(offset, bytes)
                } else {
                    buffer.insertBytes(offset, bytes)
                }
                onBufferEdited(offset)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showGotoOffsetDialog() {
        val dialogBinding = DialogHexGotoBinding.inflate(layoutInflater)
        dialogBinding.etOffset.setText(String.format("0x%X", currentOffset))
        dialogBinding.etOffset.selectAll()
        val lastOffset = (buffer.size - 1).coerceAtLeast(0)

        dialogBinding.btnQuickStart.setOnClickListener {
            dialogBinding.etOffset.setText("0x0")
        }
        dialogBinding.btnQuickEnd.setOnClickListener {
            dialogBinding.etOffset.setText(String.format("0x%X", lastOffset))
        }
        dialogBinding.btnQuickMinus1K.setOnClickListener {
            val cur = parseOffset(dialogBinding.etOffset.text.toString()) ?: currentOffset
            dialogBinding.etOffset.setText(String.format("0x%X", (cur - 1024).coerceAtLeast(0)))
        }
        dialogBinding.btnQuickPlus1K.setOnClickListener {
            val cur = parseOffset(dialogBinding.etOffset.text.toString()) ?: currentOffset
            dialogBinding.etOffset.setText(String.format("0x%X", (cur + 1024).coerceAtMost(lastOffset)))
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hex_editor_goto_offset)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val target = parseOffset(dialogBinding.etOffset.text.toString())
                if (target == null || target < 0 || target >= buffer.size) {
                    showToast(R.string.hex_editor_invalid_offset)
                    return@setPositiveButton
                }
                selectOffset(target)
                scrollTo(target)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showInsertBytesDialog() {
        if (isReadOnly) {
            showToast(R.string.hex_editor_readonly)
            return
        }
        val dialogBinding = DialogHexInsertBinding.inflate(layoutInflater)
        dialogBinding.etOffset.setText(String.format("0x%X", currentOffset))

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hex_editor_insert_bytes)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val off = parseOffset(dialogBinding.etOffset.text.toString()) ?: currentOffset
                val count = (dialogBinding.etCount.text.toString().toLongOrNull() ?: 1).coerceIn(1, MAX_EDIT_RUN)
                val fillByte = parseByte(dialogBinding.etFillByte.text.toString()) ?: 0
                buffer.insertFill(off, count, fillByte)
                onBufferEdited(off)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeleteBytesDialog() {
        if (isReadOnly) {
            showToast(R.string.hex_editor_readonly)
            return
        }
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.om_hex_bytes_to_delete)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("1")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hex_editor_delete_bytes)
            .setMessage(getString(R.string.om_hex_delete_from_offset_format, String.format("0x%08X", currentOffset)))
            .setView(input)
            .setPositiveButton(R.string.delete) { _, _ ->
                val count = (input.text.toString().toLongOrNull() ?: 1).coerceAtLeast(1)
                buffer.deleteBytes(currentOffset, count)
                onBufferEdited()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showFillRangeDialog() {
        if (isReadOnly) {
            showToast(R.string.hex_editor_readonly)
            return
        }
        val dialogBinding = DialogHexFillBinding.inflate(layoutInflater)
        dialogBinding.etStartOffset.setText(String.format("0x%X", currentOffset))
        val endDefault = if (buffer.size > 0) (currentOffset + 15).coerceAtMost(buffer.size - 1) else 0
        dialogBinding.etEndOffset.setText(String.format("0x%X", endDefault))

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hex_editor_fill_range)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val start = parseOffset(dialogBinding.etStartOffset.text.toString()) ?: currentOffset
                val end = parseOffset(dialogBinding.etEndOffset.text.toString()) ?: currentOffset
                val fillByte = parseByte(dialogBinding.etFillByte.text.toString()) ?: 0
                buffer.fillRange(start, end, fillByte)
                onBufferEdited(start)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showFileInfoDialog() {
        val target = buffer
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val digests = listOf("MD5", "SHA-1", "SHA-256").map { MessageDigest.getInstance(it) }
            withContext(Dispatchers.IO) {
                val chunk = ByteArray(HexBuffer.CHUNK)
                var position = 0L
                while (position < target.size && isActive) {
                    val n = target.read(position, chunk, 0, chunk.size)
                    if (n <= 0) break
                    digests.forEach { it.update(chunk, 0, n) }
                    position += n
                }
            }
            binding.progressBar.visibility = View.GONE
            val (md5, sha1, sha256) = digests.map { d -> d.digest().joinToString("") { String.format("%02x", it) } }

            val msg = StringBuilder()
                .append(getString(R.string.om_hex_info_file_format, path.fileName.toString())).append("\n")
                .append(getString(R.string.om_hex_info_size_format, target.size, formatFileSize(target.size))).append("\n\n")
                .append("MD5:\n").append(md5).append("\n\n")
                .append("SHA-1:\n").append(sha1).append("\n\n")
                .append("SHA-256:\n").append(sha256)
                .toString()

            showSafeDialog {
                setTitle(R.string.hex_editor_file_info)
                setMessage(msg)
                setPositiveButton(R.string.om_hex_copy_hashes) { _, _ ->
                    copyToClipboard(msg)
                }
                setNegativeButton(R.string.close, null)
            }
        }
    }

    private fun showEncodingDialog() {
        val encodings = arrayOf("UTF-8", "ASCII", "ISO-8859-1", "UTF-16LE", "UTF-16BE", "GBK")
        val currentIdx = encodings.indexOfFirst { it.equals(currentCharset.name(), ignoreCase = true) }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hex_editor_encoding)
            .setSingleChoiceItems(encodings, currentIdx) { dialog, which ->
                try {
                    currentCharset = Charset.forName(encodings[which])
                    showToast(getString(R.string.om_hex_encoding_set_format, encodings[which]))
                } catch (e: Exception) {
                    showToast(getString(R.string.om_hex_encoding_unsupported))
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- saving ------------------------------------------------------------------------------

    private fun saveFile(onComplete: ((Boolean) -> Unit)? = null) {
        if (!buffer.isModified && pendingRewrite == null) {
            showToast(R.string.hex_editor_saved_success)
            onComplete?.invoke(true)
            return
        }
        if (isSaving) return

        if (DeadManProtectionManager.isDfrootProtectionActive(this) &&
            DeadManProtectionManager.isProtectedFromModification(path)
        ) {
            DeadManProtectionManager.showDirtyFlagProtectionDialog(this)
            return
        }
        performSaveFile(onComplete)
    }

    private fun performSaveFile(onComplete: ((Boolean) -> Unit)? = null) {
        searchJob?.cancel()
        val target = buffer
        val start = SystemClock.elapsedRealtime()
        val finished: (Boolean, String) -> Unit = { successful, mode ->
            isSaving = false
            binding.progressBar.visibility = View.GONE
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Save mode=$mode ok=$successful size=${target.size} ms=${SystemClock.elapsedRealtime() - start}")
            }
            if (successful) {
                showToast(R.string.hex_editor_saved_success)
            } else {
                showToast(getString(R.string.hex_editor_save_failed, "I/O error"))
            }
            invalidateOptionsMenu()
            updateToolbarInfo()
            onComplete?.invoke(successful)
        }
        isSaving = true
        binding.progressBar.visibility = View.VISIBLE

        pendingRewrite?.let { copyRewriteOver(it, finished); return }

        when {
            // Small file read whole: write it from memory, as before.
            target.source is ByteArrayHexSource -> {
                val content = target.toByteArray()
                FileJobService.write(path, content, this) { successful ->
                    if (successful) target.markSaved()
                    finished(successful, "memory")
                }
            }
            // Same size and nothing moved: write only the edited runs into the file.
            target.canPatchInPlace -> lifecycleScope.launch {
                val written = withContext(Dispatchers.IO) {
                    try {
                        path.newByteChannel(StandardOpenOption.WRITE).use { channel ->
                            target.patchInPlace { position, bytes, length ->
                                val data = ByteBuffer.wrap(bytes, 0, length)
                                channel.position(position)
                                while (data.hasRemaining()) channel.write(data)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        -1L
                    }
                }
                if (written >= 0) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Patched $written bytes in place")
                    // Read the file again: the cached pages hold the old bytes.
                    loadFileContent(currentOffset) { finished(true, "patch") }
                } else {
                    finished(false, "patch")
                }
            }
            // Bytes inserted or deleted: build the new file in the cache, then copy it over.
            else -> lifecycleScope.launch {
                val temp = withContext(Dispatchers.IO) {
                    try {
                        val file = File(cacheDir, "hex-save-${System.nanoTime()}.bin")
                        target.openInputStream().use { input -> file.outputStream().use { input.copyTo(it, 256 * 1024) } }
                        file
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }
                }
                if (temp == null) {
                    finished(false, "rewrite")
                    return@launch
                }
                // From now on the editor reads the new copy, so the original can be overwritten.
                val old = buffer
                buffer = HexBuffer(ChannelHexSource.of(temp))
                runCatching { old.source.close() }
                pendingRewrite = temp
                onBufferEdited()
                copyRewriteOver(temp, finished)
            }
        }
    }

    private fun copyRewriteOver(temp: File, finished: (Boolean, String) -> Unit) {
        FileJobService.write(path, temp.length(), { temp.inputStream() }, this) { successful ->
            if (successful) {
                pendingRewrite = null
                loadFileContent(currentOffset) {
                    temp.delete()
                    finished(true, "rewrite")
                }
            } else {
                finished(false, "rewrite")
            }
        }
    }

    private fun showUnsavedChangesDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hex_editor_title)
            .setMessage(R.string.hex_editor_unsaved_changes)
            .setPositiveButton(R.string.save) { _, _ ->
                saveFile { successful ->
                    if (successful) {
                        finish()
                    }
                }
            }
            .setNegativeButton(R.string.discard) { _, _ ->
                pendingRewrite?.delete()
                finish()
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.hex_editor_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_undo)?.isEnabled = buffer.canUndo && !isReadOnly
        menu.findItem(R.id.action_redo)?.isEnabled = buffer.canRedo && !isReadOnly
        menu.findItem(R.id.action_save)?.isEnabled = (buffer.isModified || pendingRewrite != null) && !isReadOnly
        menu.findItem(R.id.action_readonly_toggle)?.isChecked = isReadOnly
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_undo -> {
                val restoredOffset = buffer.undo()
                onBufferEdited(if (restoredOffset != -1L) restoredOffset else currentOffset)
                true
            }
            R.id.action_redo -> {
                val restoredOffset = buffer.redo()
                onBufferEdited(if (restoredOffset != -1L) restoredOffset else currentOffset)
                true
            }
            R.id.action_search -> {
                binding.searchBarContainer.visibility = View.VISIBLE
                binding.etSearch.requestFocus()
                true
            }
            R.id.action_save -> {
                saveFile()
                true
            }
            R.id.action_goto -> {
                showGotoOffsetDialog()
                true
            }
            R.id.action_edit -> {
                if (isReadOnly) {
                    showToast(R.string.hex_editor_readonly)
                } else {
                    showEditByteDialog(currentOffset)
                }
                true
            }
            R.id.action_insert -> {
                showInsertBytesDialog()
                true
            }
            R.id.action_delete -> {
                showDeleteBytesDialog()
                true
            }
            R.id.action_fill -> {
                showFillRangeDialog()
                true
            }
            R.id.action_encoding -> {
                showEncodingDialog()
                true
            }
            R.id.action_file_info -> {
                showFileInfoDialog()
                true
            }
            R.id.action_readonly_toggle -> {
                isReadOnly = !isReadOnly
                item.isChecked = isReadOnly
                showToast(if (isReadOnly) R.string.hex_editor_readonly else R.string.hex_editor_edit_mode)
                invalidateOptionsMenu()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun parseHexPattern(hexStr: String): ByteArray? {
        val clean = hexStr.replace("\\s+".toRegex(), "").replace("0x", "", ignoreCase = true)
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        return try {
            val len = clean.length / 2
            val result = ByteArray(len)
            for (i in 0 until len) {
                result[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            result
        } catch (e: Exception) {
            null
        }
    }

    private fun parseOffset(str: String): Long? {
        val clean = str.trim()
        return try {
            if (clean.startsWith("0x", ignoreCase = true)) {
                clean.substring(2).toLong(16)
            } else {
                clean.toLong()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseByte(str: String): Byte? {
        val clean = str.trim().replace("0x", "", ignoreCase = true)
        return try {
            clean.toInt(16).toByte()
        } catch (e: Exception) {
            null
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService<ClipboardManager>()
        clipboard?.setPrimaryClip(ClipData.newPlainText("hex", text))
        showToast(R.string.hex_editor_copied_toast)
    }

    companion object {
        private const val TAG = "OMHex"

        /** Files up to this size are read whole (fast, and saving is one write). */
        private const val IN_MEMORY_THRESHOLD = 8L * 1024 * 1024

        /** Largest file read whole when its provider cannot seek. */
        private const val IN_MEMORY_MAX = 64L * 1024 * 1024

        /** Largest run inserted at once (stored as one repeated byte, not allocated). */
        private const val MAX_EDIT_RUN = 1L shl 31

        fun start(context: Context, path: Path) {
            val intent = Intent(context, HexEditorActivity::class.java).apply {
                extraPath = path
            }
            context.startActivity(intent)
        }
    }
}
