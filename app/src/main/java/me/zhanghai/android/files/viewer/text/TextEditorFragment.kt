/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SubMenu
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.TextEditorFragmentBinding
import me.zhanghai.android.files.provider.root.DeadManProtectionManager
import me.zhanghai.android.files.ui.ThemedFastScroller
import me.zhanghai.android.files.util.ActionState
import me.zhanghai.android.files.util.DataState
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.addOnBackPressedCallback
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.fadeInUnsafe
import me.zhanghai.android.files.util.fadeOutUnsafe
import me.zhanghai.android.files.util.isReady
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.viewModels
import java.nio.charset.Charset
import java.util.ArrayDeque
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TextEditorFragment : Fragment(), ConfirmReloadDialogFragment.Listener,
    ConfirmCloseDialogFragment.Listener, TextEditorPreferencesDialogFragment.Listener {
    private val args by args<Args>()
    private lateinit var argsFile: Path

    private lateinit var binding: TextEditorFragmentBinding
    private lateinit var menuBinding: MenuBinding

    private val viewModel by viewModels { { TextEditorViewModel(argsFile) } }

    private lateinit var onBackPressedCallback: OnBackPressedCallback

    private var isSettingText = false
    private lateinit var undoManager: TextEditorUndoManager

    private var currentLanguage = SyntaxLanguage.PLAIN_TEXT
    private var highlightJob: Job? = null

    // Search and replace state
    private val searchMatches = mutableListOf<IntRange>()
    private var currentMatchIndex = -1
    private var isSearchCaseSensitive = false
    private var isSearchRegex = false
    private var isSearchWholeWord = false

    // Display options
    private var isWordWrap = true
    private var showLineNumbers = true
    private var isReadOnly = false
    private var currentFontSizeSp = TextEditorSettings.DEFAULT_FONT_SIZE
    private var currentTheme: EditorTheme = EditorTheme.ONE_DARK

    private var currentCursorLine = 1
    private var currentCursorCol = 1

    // Cursor navigation history (MT Manager: Posición anterior / Siguiente posición)
    private val cursorBackStack = ArrayDeque<Int>()
    private val cursorForwardStack = ArrayDeque<Int>()
    private var lastRecordedCursorOffset: Int = 0

    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private val hideZoomIndicatorRunnable = Runnable {
        if (this::binding.isInitialized) {
            binding.zoomIndicator.animate()
                .alpha(0f)
                .setDuration(250)
                .withEndAction {
                    binding.zoomIndicator.visibility = View.GONE
                    binding.zoomIndicator.alpha = 1f
                }
                .start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)

        lifecycleScope.launchWhenStarted {
            onBackPressedCallback = object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() {
                    if (binding.searchPanel.visibility == View.VISIBLE) {
                        closeSearchPanel()
                        return
                    }
                    if (TextEditorSettings.isConfirmOnExit(requireContext()) && viewModel.isTextChanged.value) {
                        ConfirmCloseDialogFragment.show(this@TextEditorFragment)
                    } else {
                        finish()
                    }
                }
            }
            launch {
                viewModel.isTextChanged.collect {
                    onBackPressedCallback.isEnabled = viewModel.isTextChanged.value
                }
            }
            addOnBackPressedCallback(onBackPressedCallback)

            launch { viewModel.encoding.collect { onEncodingChanged(it) } }
            launch { viewModel.textState.collect { onTextStateChanged(it) } }
            launch { viewModel.isTextChanged.collect { onIsTextChangedChanged(it) } }
            launch { viewModel.writeFileState.collect { onWriteFileStateChanged(it) } }
            launch { viewModel.isCrlf.collect { updateSubtitle() } }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        TextEditorFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val argsFile = args.intent.extraPath
        if (argsFile == null) {
            finish()
            return
        }
        this.argsFile = argsFile

        // Auto-detect language or use forced syntax from intent
        val forced = requireActivity().intent.getStringExtra("FORCED_SYNTAX")
        currentLanguage = if (!forced.isNullOrEmpty()) {
            when (forced.uppercase()) {
                "XML" -> SyntaxLanguage.XML
                "HTML" -> SyntaxLanguage.HTML
                "PHP" -> SyntaxLanguage.PHP
                "SMALI" -> SyntaxLanguage.SMALI
                "JSON" -> SyntaxLanguage.JSON
                else -> SyntaxLanguage.detectFromFileName(argsFile.fileName.toString())
            }
        } else {
            SyntaxLanguage.detectFromFileName(argsFile.fileName.toString())
        }
        viewModel.syntaxLanguage.value = currentLanguage

        val activity = requireActivity() as AppCompatActivity
        activity.lifecycleScope.launchWhenCreated {
            activity.setSupportActionBar(binding.toolbar)
            activity.supportActionBar?.setDisplayHomeAsUpEnabled(true)
            updateSubtitle()
        }

        // Attach LineNumberView and Synchronized Scrolling
        binding.lineNumberView.attachEditor(binding.textEdit)
        binding.scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            binding.lineNumberView.scrollY = scrollY
            binding.lineNumberView.invalidate()
        }

        ThemedFastScroller.create(binding.scrollView)

        try {
            val monoTypeface = Typeface.createFromAsset(requireContext().assets, "Mono.ttf")
            binding.textEdit.typeface = monoTypeface
        } catch (e: Exception) {
            binding.textEdit.typeface = Typeface.MONOSPACE
        }

        // Cursor Position Listener for Subtitle, Status Strip and LineNumber Highlighting
        binding.textEdit.onCursorPositionChanged = { line, col, totalLines ->
            currentCursorLine = line
            currentCursorCol = col
            binding.lineNumberView.setCurrentLine(line - 1)
            updateSubtitle()
            checkRecordCursorJump(binding.textEdit.selectionStart)
        }

        binding.textEdit.onLineCountChanged = {
            binding.lineNumberView.syncLineCount()
        }

        // Setup Undo Manager
        undoManager = TextEditorUndoManager(binding.textEdit) { canUndo, canRedo ->
            updateUndoRedoMenuItems()
        }
        binding.textEdit.addTextChangedListener(undoManager)

        // Avoid TransactionTooLargeException
        binding.textEdit.isSaveEnabled = false
        val textEditSavedState = viewModel.removeEditTextSavedState()
        if (textEditSavedState != null) {
            binding.textEdit.onRestoreInstanceState(textEditSavedState)
        }

        binding.textEdit.doAfterTextChanged {
            if (isSettingText || viewModel.textState.value !is DataState.Success) {
                return@doAfterTextChanged
            }
            viewModel.isTextChanged.value = true
            binding.lineNumberView.syncLineCount()
            requestSyntaxHighlight()
            if (binding.searchPanel.visibility == View.VISIBLE) {
                performSearch(resetCurrent = false)
            }
            updateSubtitle()
        }

        setupStatusStrip()
        setupSearchPanel()
        setupSymbolBar()

        // Load saved preferences
        val ctx = requireContext()
        currentTheme = TextEditorSettings.getTheme(ctx)
        applyTheme(currentTheme)

        isWordWrap = TextEditorSettings.isWordWrap(ctx)
        setWordWrap(isWordWrap)

        showLineNumbers = TextEditorSettings.isShowLineNumbers(ctx)
        setLineNumbers(showLineNumbers)

        currentFontSizeSp = TextEditorSettings.getFontSize(ctx)
        setFontSize(currentFontSizeSp, showIndicator = false)

        isReadOnly = TextEditorSettings.isReadOnlyDefault(ctx)
        setReadOnly(isReadOnly)

        binding.lineNumberView.showWrapArrows = TextEditorSettings.isShowWrapArrows(ctx)
        binding.textEdit.isHighlightCurrentLine = TextEditorSettings.isHighlightCurrentLine(ctx)
        binding.textEdit.isAutoIndent = TextEditorSettings.isAutoIndent(ctx)
        binding.textEdit.isAutoClosePairs = TextEditorSettings.isAutoClosePairs(ctx)
        binding.symbolBar.visibility = if (TextEditorSettings.isShowSymbolBar(ctx) && !isReadOnly) View.VISIBLE else View.GONE

        setupPinchToZoom()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        highlightJob?.cancel()
        if (this::binding.isInitialized) {
            binding.zoomIndicator.removeCallbacks(hideZoomIndicatorRunnable)
        }
    }

    override fun onPreferencesChanged() {
        val ctx = context ?: return
        currentTheme = TextEditorSettings.getTheme(ctx)
        applyTheme(currentTheme)

        isWordWrap = TextEditorSettings.isWordWrap(ctx)
        setWordWrap(isWordWrap)

        showLineNumbers = TextEditorSettings.isShowLineNumbers(ctx)
        setLineNumbers(showLineNumbers)

        currentFontSizeSp = TextEditorSettings.getFontSize(ctx)
        setFontSize(currentFontSizeSp, showIndicator = false)

        binding.lineNumberView.showWrapArrows = TextEditorSettings.isShowWrapArrows(ctx)
        binding.textEdit.isHighlightCurrentLine = TextEditorSettings.isHighlightCurrentLine(ctx)
        binding.textEdit.isAutoIndent = TextEditorSettings.isAutoIndent(ctx)
        binding.textEdit.isAutoClosePairs = TextEditorSettings.isAutoClosePairs(ctx)
        binding.symbolBar.visibility = if (TextEditorSettings.isShowSymbolBar(ctx) && !isReadOnly) View.VISIBLE else View.GONE

        requestSyntaxHighlight()
    }

    private fun applyTheme(theme: EditorTheme) {
        currentTheme = theme
        FastSyntaxScanner.currentTheme = theme
        binding.editorContainer.setBackgroundColor(theme.backgroundColor)
        binding.textEdit.setEditorTheme(theme)
        binding.lineNumberView.setEditorTheme(theme)
        binding.editorStatusStrip.setBackgroundColor(theme.gutterColor)
        binding.symbolBar.setBackgroundColor(theme.gutterColor)
        binding.searchPanel.setBackgroundColor(theme.gutterColor)
    }

    private fun setupStatusStrip() {
        // Tapping Cursor Pos jumps to line
        binding.statusCursorPosText.setOnClickListener {
            showGotoLineDialog()
        }
        // Tapping Encoding opens encoding selector
        binding.statusEncodingText.setOnClickListener {
            showEncodingDialog()
        }
        // Tapping Syntax opens syntax selector
        binding.statusSyntaxText.setOnClickListener {
            showSyntaxLanguageDialog()
        }
    }

    private fun requestSyntaxHighlight() {
        highlightJob?.cancel()
        if (!TextEditorSettings.isSyntaxHighlighting(requireContext())) {
            FastSyntaxScanner.clearSpans(binding.textEdit.text)
            return
        }
        highlightJob = lifecycleScope.launch {
            delay(250) // Debounce typing
            val text = binding.textEdit.text
            if (text != null && text.isNotEmpty() && currentLanguage != SyntaxLanguage.PLAIN_TEXT) {
                val tokens = withContext(Dispatchers.Default) {
                    FastSyntaxScanner.scan(text, currentLanguage, currentTheme)
                }
                FastSyntaxScanner.applyTokens(text, tokens)
            }
        }
    }

    private fun updateSubtitle() {
        val activity = requireActivity() as? AppCompatActivity ?: return
        val charsetName = viewModel.encoding.value.displayName()
        val lineEnding = if (viewModel.isCrlf.value) "CRLF" else "LF"
        val syntax = currentLanguage.displayName
        val subtitle = getString(R.string.text_editor_cursor_pos, currentCursorLine, currentCursorCol) +
                " | $charsetName | $lineEnding | $syntax"
        activity.supportActionBar?.subtitle = subtitle

        // Update MT Manager Status Info Strip
        val fileName = viewModel.file.value.fileName.toString()
        val changed = viewModel.isTextChanged.value
        val fileSize = viewModel.fileSize.value
        val formattedSize = if (fileSize > 0) " (${Formatter.formatFileSize(requireContext(), fileSize)})" else ""
        binding.statusFileNameText.text = "${if (changed) "*" else ""}$fileName$formattedSize"
        binding.statusFileNameText.setTextColor(if (changed) Color.parseColor("#E5C07B") else Color.parseColor("#ABB2BF"))

        binding.statusCursorPosText.text = "$currentCursorLine:$currentCursorCol"
        binding.statusEncodingText.text = charsetName
        binding.statusSyntaxText.text = syntax

        // Update selection indicator
        val selStart = binding.textEdit.selectionStart
        val selEnd = binding.textEdit.selectionEnd
        if (selStart >= 0 && selEnd >= 0 && selStart != selEnd) {
            val selLen = abs(selEnd - selStart)
            binding.statusSelectionText.visibility = View.VISIBLE
            binding.statusSelectionText.text = "[Sel: $selLen]"
        } else {
            binding.statusSelectionText.visibility = View.GONE
        }
    }

    private fun setupSymbolBar() {
        val container = binding.symbolContainer
        container.removeAllViews()

        // 1. Navigation Arrow Keys (micro-navigation for touchscreens)
        addTextSymbolButton("◀") { binding.textEdit.moveCursor(CodeEditText.CursorDirection.LEFT) }
        addTextSymbolButton("▶") { binding.textEdit.moveCursor(CodeEditText.CursorDirection.RIGHT) }
        addTextSymbolButton("▲") { binding.textEdit.moveCursor(CodeEditText.CursorDirection.UP) }
        addTextSymbolButton("▼") { binding.textEdit.moveCursor(CodeEditText.CursorDirection.DOWN) }

        // 2. Indentation & Smart Newline
        addTextSymbolButton("⇥") { binding.textEdit.indentSelection() }
        addTextSymbolButton("⇤") { binding.textEdit.unindentSelection() }
        addTextSymbolButton("↵") { binding.textEdit.handleSmartEnter() }

        // 3. TAB
        addTextSymbolButton("TAB") { binding.textEdit.insertSymbol("    ") }

        // 4. Undo & Redo Quick Buttons
        addImageSymbolButton(R.drawable.ic_undo_24dp, R.string.text_editor_undo) {
            undoManager.undo()
        }
        addImageSymbolButton(R.drawable.ic_redo_24dp, R.string.text_editor_redo) {
            undoManager.redo()
        }

        // 5. Search Toggle
        addImageSymbolButton(R.drawable.search_icon_control_normal_24dp, R.string.text_editor_search) {
            toggleSearchPanel()
        }

        // 6. Programming Brackets & Quotes (with Auto-wrap)
        val brackets = listOf("{", "}", "(", ")", "[", "]", "<", ">", "\"", "'")
        for (sym in brackets) {
            addTextSymbolButton(sym) {
                binding.textEdit.insertSymbol(sym)
            }
        }

        // 7. Programming Operators & Delimiters
        val symbols = listOf(
            "=", ":", ";", "/", "\\", "+", "-", "*", "%",
            "!", "?", "&", "|", "@", "#", "$", "~", "^", ",", ".", "_"
        )
        for (sym in symbols) {
            addTextSymbolButton(sym) {
                binding.textEdit.insertSymbol(sym)
            }
        }

        // 8. Clipboard Actions (Paste & Select All)
        addTextSymbolButton("📋") {
            pasteClipboardText()
        }
        addTextSymbolButton("✂") {
            binding.textEdit.selectAll()
        }
    }

    private fun pasteClipboardText() {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val item = cm?.primaryClip?.getItemAt(0)
        val text = item?.text?.toString()
        if (!text.isNullOrEmpty()) {
            binding.textEdit.insertSymbol(text)
        }
    }

    private fun addTextSymbolButton(text: String, onClick: () -> Unit) {
        val btn = MaterialButton(
            requireContext(),
            null,
            androidx.appcompat.R.attr.borderlessButtonStyle
        ).apply {
            this.text = text
            minWidth = 0
            minimumWidth = 0
            setPadding(20, 0, 20, 0)
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#DCDFE4"))
            setOnClickListener { onClick() }
        }
        val params = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        binding.symbolContainer.addView(btn, params)
    }

    private fun addImageSymbolButton(resId: Int, contentDescriptionRes: Int, onClick: () -> Unit) {
        val btn = ImageButton(requireContext()).apply {
            setImageResource(resId)
            contentDescription = getString(contentDescriptionRes)
            setBackgroundResource(android.R.drawable.list_selector_background)
            setPadding(16, 0, 16, 0)
            setColorFilter(Color.parseColor("#ABB2BF"))
            setOnClickListener { onClick() }
        }
        val params = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        binding.symbolContainer.addView(btn, params)
    }

    private fun setupSearchPanel() {
        binding.searchEdit.doAfterTextChanged {
            performSearch(resetCurrent = true)
        }
        binding.searchClearBtn.setOnClickListener {
            binding.searchEdit.text.clear()
        }
        binding.replaceClearBtn.setOnClickListener {
            binding.replaceEdit.text.clear()
        }

        binding.searchPrevBtn.setOnClickListener {
            prevSearchMatch()
        }
        binding.searchNextBtn.setOnClickListener {
            nextSearchMatch()
        }

        // Case Sensitive Toggle
        binding.searchCaseBtn.setOnClickListener {
            isSearchCaseSensitive = !isSearchCaseSensitive
            updateSearchOptionButtons()
            performSearch(resetCurrent = true)
        }

        // Whole Word Toggle
        binding.searchWordBtn.setOnClickListener {
            isSearchWholeWord = !isSearchWholeWord
            updateSearchOptionButtons()
            performSearch(resetCurrent = true)
        }

        // Regex Toggle
        binding.searchRegexBtn.setOnClickListener {
            isSearchRegex = !isSearchRegex
            updateSearchOptionButtons()
            performSearch(resetCurrent = true)
        }

        updateSearchOptionButtons()

        binding.searchCloseBtn.setOnClickListener {
            closeSearchPanel()
        }

        binding.replaceBtn.setOnClickListener {
            replaceCurrentMatch()
        }
        binding.replaceAllBtn.setOnClickListener {
            replaceAllMatches()
        }
    }

    private fun updateSearchOptionButtons() {
        // Active pills get accent background with crisp white text
        val activeBg = Color.parseColor("#2196F3")
        val inactiveBg = Color.TRANSPARENT

        binding.searchCaseBtn.setBackgroundColor(if (isSearchCaseSensitive) activeBg else inactiveBg)
        binding.searchCaseBtn.setTextColor(if (isSearchCaseSensitive) Color.WHITE else Color.parseColor("#ABB2BF"))

        binding.searchWordBtn.setBackgroundColor(if (isSearchWholeWord) activeBg else inactiveBg)
        binding.searchWordBtn.setTextColor(if (isSearchWholeWord) Color.WHITE else Color.parseColor("#ABB2BF"))

        binding.searchRegexBtn.setBackgroundColor(if (isSearchRegex) activeBg else inactiveBg)
        binding.searchRegexBtn.setTextColor(if (isSearchRegex) Color.WHITE else Color.parseColor("#ABB2BF"))
    }

    private fun toggleSearchPanel() {
        if (binding.searchPanel.visibility == View.VISIBLE) {
            closeSearchPanel()
        } else {
            openSearchPanel()
        }
    }

    private fun openSearchPanel() {
        binding.searchPanel.visibility = View.VISIBLE
        binding.searchEdit.requestFocus()
        performSearch(resetCurrent = true)
    }

    private fun closeSearchPanel() {
        binding.searchPanel.visibility = View.GONE
        searchMatches.clear()
        currentMatchIndex = -1
        binding.textEdit.searchMatches = emptyList()
        binding.textEdit.currentMatchIndex = -1
        binding.searchCountText.setText(R.string.text_editor_no_matches)
    }

    private fun performSearch(resetCurrent: Boolean) {
        val query = binding.searchEdit.text.toString()
        searchMatches.clear()

        if (query.isEmpty()) {
            currentMatchIndex = -1
            binding.textEdit.searchMatches = emptyList()
            binding.textEdit.currentMatchIndex = -1
            binding.searchCountText.setText(R.string.text_editor_no_matches)
            return
        }

        val text = binding.textEdit.text.toString()

        try {
            if (isSearchRegex) {
                val flags = if (isSearchCaseSensitive) 0 else Pattern.CASE_INSENSITIVE
                val pattern = Pattern.compile(query, flags)
                val matcher = pattern.matcher(text)
                while (matcher.find()) {
                    if (matcher.start() < matcher.end()) {
                        searchMatches.add(matcher.start() until matcher.end())
                    }
                }
            } else if (isSearchWholeWord) {
                val escaped = Pattern.quote(query)
                val flags = if (isSearchCaseSensitive) 0 else Pattern.CASE_INSENSITIVE
                val pattern = Pattern.compile("\\b$escaped\\b", flags)
                val matcher = pattern.matcher(text)
                while (matcher.find()) {
                    searchMatches.add(matcher.start() until matcher.end())
                }
            } else {
                var index = 0
                while (index < text.length) {
                    val found = text.indexOf(query, index, ignoreCase = !isSearchCaseSensitive)
                    if (found == -1) break
                    searchMatches.add(found until (found + query.length))
                    index = found + query.length.coerceAtLeast(1)
                }
            }
        } catch (e: Exception) {
            // Malformed regex while user is typing
        }

        if (searchMatches.isEmpty()) {
            currentMatchIndex = -1
            binding.textEdit.searchMatches = emptyList()
            binding.textEdit.currentMatchIndex = -1
            binding.searchCountText.setText(R.string.text_editor_no_matches)
            return
        }

        if (resetCurrent || currentMatchIndex !in searchMatches.indices) {
            val cursor = binding.textEdit.selectionStart
            val nearest = searchMatches.indexOfFirst { it.first >= cursor }
            currentMatchIndex = if (nearest != -1) nearest else 0
        }

        binding.textEdit.searchMatches = searchMatches
        binding.textEdit.currentMatchIndex = currentMatchIndex

        binding.searchCountText.text = getString(
            R.string.text_editor_match_count,
            currentMatchIndex + 1,
            searchMatches.size
        )
        scrollToMatch(currentMatchIndex)
    }

    private fun nextSearchMatch() {
        if (searchMatches.isEmpty()) {
            showToast(getString(R.string.text_editor_no_text_found))
            return
        }
        currentMatchIndex = (currentMatchIndex + 1) % searchMatches.size
        binding.textEdit.currentMatchIndex = currentMatchIndex
        binding.searchCountText.text = getString(
            R.string.text_editor_match_count,
            currentMatchIndex + 1,
            searchMatches.size
        )
        scrollToMatch(currentMatchIndex)
    }

    private fun prevSearchMatch() {
        if (searchMatches.isEmpty()) {
            showToast(getString(R.string.text_editor_no_text_found))
            return
        }
        currentMatchIndex = if (currentMatchIndex - 1 < 0) searchMatches.size - 1 else currentMatchIndex - 1
        binding.textEdit.currentMatchIndex = currentMatchIndex
        binding.searchCountText.text = getString(
            R.string.text_editor_match_count,
            currentMatchIndex + 1,
            searchMatches.size
        )
        scrollToMatch(currentMatchIndex)
    }

    private fun scrollToMatch(index: Int) {
        if (index !in searchMatches.indices) return
        val range = searchMatches[index]
        binding.textEdit.setSelection(range.first, range.last + 1)

        val layout = binding.textEdit.layout ?: return
        val line = layout.getLineForOffset(range.first)
        val lineTop = layout.getLineTop(line)
        val scrollY = (lineTop - binding.scrollView.height / 3).coerceAtLeast(0)
        binding.scrollView.smoothScrollTo(0, scrollY)

        if (!isWordWrap) {
            val horizontalX = layout.getPrimaryHorizontal(range.first).toInt()
            val scrollX = (horizontalX - binding.horizontalScrollView.width / 3).coerceAtLeast(0)
            binding.horizontalScrollView.smoothScrollTo(scrollX, 0)
        }
    }

    private fun replaceCurrentMatch() {
        if (isReadOnly || currentMatchIndex !in searchMatches.indices) return
        val range = searchMatches[currentMatchIndex]
        val replacement = binding.replaceEdit.text.toString()

        binding.textEdit.text?.replace(range.first, range.last + 1, replacement)
        performSearch(resetCurrent = false)
    }

    private fun replaceAllMatches() {
        if (isReadOnly || searchMatches.isEmpty()) return
        val replacement = binding.replaceEdit.text.toString()
        val count = searchMatches.size

        undoManager.beginBatchEdit()
        try {
            for (i in searchMatches.indices.reversed()) {
                val range = searchMatches[i]
                binding.textEdit.text?.replace(range.first, range.last + 1, replacement)
            }
        } finally {
            undoManager.endBatchEdit()
        }

        showToast(getString(R.string.text_editor_replaced_count, count))
        performSearch(resetCurrent = true)
    }

    // Cursor Navigation History Stack (MT Manager: Posición anterior / Siguiente posición)
    private fun checkRecordCursorJump(newOffset: Int) {
        val diff = abs(newOffset - lastRecordedCursorOffset)
        if (diff > 120) {
            cursorBackStack.addLast(lastRecordedCursorOffset)
            if (cursorBackStack.size > 50) cursorBackStack.removeFirst()
            cursorForwardStack.clear()
            updateNavigationHistoryMenuItems()
        }
        lastRecordedCursorOffset = newOffset
    }

    private fun navigateToPreviousCursorPosition() {
        if (cursorBackStack.isEmpty()) return
        val current = binding.textEdit.selectionStart
        cursorForwardStack.addLast(current)
        val prev = cursorBackStack.removeLast()
        lastRecordedCursorOffset = prev
        val clamped = prev.coerceIn(0, binding.textEdit.text?.length ?: 0)
        binding.textEdit.setSelection(clamped)
        scrollToOffset(clamped)
        updateNavigationHistoryMenuItems()
        showToast(getString(R.string.text_editor_navigated_back))
    }

    private fun navigateToNextCursorPosition() {
        if (cursorForwardStack.isEmpty()) return
        val current = binding.textEdit.selectionStart
        cursorBackStack.addLast(current)
        val next = cursorForwardStack.removeLast()
        lastRecordedCursorOffset = next
        val clamped = next.coerceIn(0, binding.textEdit.text?.length ?: 0)
        binding.textEdit.setSelection(clamped)
        scrollToOffset(clamped)
        updateNavigationHistoryMenuItems()
        showToast(getString(R.string.text_editor_navigated_forward))
    }

    private fun scrollToOffset(offset: Int) {
        val layout = binding.textEdit.layout ?: return
        val line = layout.getLineForOffset(offset)
        val lineTop = layout.getLineTop(line)
        val scrollY = (lineTop - binding.scrollView.height / 3).coerceAtLeast(0)
        binding.scrollView.smoothScrollTo(0, scrollY)
    }

    private fun updateNavigationHistoryMenuItems() {
        if (!this::menuBinding.isInitialized) return
        menuBinding.navBackItem.isEnabled = cursorBackStack.isNotEmpty()
        menuBinding.navForwardItem.isEnabled = cursorForwardStack.isNotEmpty()
    }

    private fun showSyntaxLanguageDialog() {
        val languages = SyntaxLanguage.entries.toTypedArray()
        val names = languages.map { it.displayName }.toTypedArray()
        val selectedIndex = languages.indexOf(currentLanguage)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_editor_syntax_title)
            .setSingleChoiceItems(names, selectedIndex) { dialog, which ->
                val selected = languages[which]
                if (selected != currentLanguage) {
                    currentLanguage = selected
                    viewModel.syntaxLanguage.value = selected
                    FastSyntaxScanner.applyHighlightTo(binding.textEdit.text, currentLanguage, currentTheme)
                    updateSubtitle()
                    showToast(getString(R.string.text_editor_syntax_changed, selected.displayName))
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showThemeSelectorDialog() {
        val themes = EditorTheme.entries.toTypedArray()
        val names = themes.map { it.displayName }.toTypedArray()
        val selectedIndex = themes.indexOf(currentTheme)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_editor_theme_title)
            .setSingleChoiceItems(names, selectedIndex) { dialog, which ->
                val chosen = themes[which]
                TextEditorSettings.setTheme(requireContext(), chosen)
                applyTheme(chosen)
                showToast(getString(R.string.text_editor_theme_changed, chosen.displayName))
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showEncodingDialog() {
        val available = Charset.availableCharsets().values.toList()
        val names = available.map { it.displayName() }.toTypedArray()
        val current = viewModel.encoding.value
        val selectedIndex = available.indexOf(current).coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_editor_encoding)
            .setSingleChoiceItems(names, selectedIndex) { dialog, which ->
                val chosen = available[which]
                viewModel.encoding.value = chosen
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showGotoLineDialog() {
        val totalLines = binding.textEdit.lineCount.coerceAtLeast(1)
        val layout = binding.textEdit.layout
        val currentLine = if (layout != null) {
            layout.getLineForOffset(binding.textEdit.selectionStart) + 1
        } else 1

        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.text_editor_goto_line_hint, totalLines)
            setText(currentLine.toString())
            selectAll()
            setPadding(48, 32, 48, 16)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_editor_goto_line)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val targetLine = input.text.toString().toIntOrNull()
                if (targetLine != null && targetLine in 1..totalLines) {
                    val lineStart = layout?.getLineStart(targetLine - 1) ?: 0
                    checkRecordCursorJump(lineStart)
                    binding.textEdit.setSelection(lineStart)
                    val lineTop = layout?.getLineTop(targetLine - 1) ?: 0
                    val scrollY = (lineTop - binding.scrollView.height / 3).coerceAtLeast(0)
                    binding.scrollView.smoothScrollTo(0, scrollY)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showFileStatsDialog() {
        val fileName = viewModel.file.value.fileName.toString()
        val filePath = viewModel.file.value.toAbsolutePath().toString()
        val sizeBytes = viewModel.fileSize.value
        val formattedSize = "${Formatter.formatFileSize(requireContext(), sizeBytes)} ($sizeBytes bytes)"
        val encodingName = viewModel.encoding.value.displayName()
        val lineEnding = if (viewModel.isCrlf.value) "Windows (CRLF)" else "Unix (LF)"
        val syntaxName = currentLanguage.displayName
        val text = binding.textEdit.text.toString()
        val lineCount = binding.textEdit.lineCount
        val charCount = text.length
        val nonSpaceCharCount = text.count { !it.isWhitespace() }
        val wordCount = if (text.isBlank()) 0 else text.trim().split("\\s+".toRegex()).size

        val message = """
            ${getString(R.string.file_properties_basic_name)}: $fileName
            Ruta: $filePath
            ${getString(R.string.file_properties_basic_size)}: $formattedSize
            ${getString(R.string.text_editor_encoding)}: $encodingName
            ${getString(R.string.text_editor_line_endings)}: $lineEnding
            ${getString(R.string.text_editor_syntax)}: $syntaxName
            ${getString(R.string.text_editor_theme)}: ${currentTheme.displayName}
            ${getString(R.string.text_editor_word_wrap)}: ${if (isWordWrap) "Activado" else "Desactivado"}
            ${getString(R.string.text_editor_line_numbers)}: ${if (showLineNumbers) "Activado" else "Desactivado"}
            
            Líneas: $lineCount
            Palabras: $wordCount
            Caracteres: $charCount
            Caracteres (sin espacios): $nonSpaceCharCount
        """.trimIndent()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_editor_file_stats)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun setWordWrap(wrap: Boolean) {
        isWordWrap = wrap
        binding.textEdit.setHorizontallyScrolling(!wrap)
        val params = binding.textEdit.layoutParams
        params.width = if (wrap) {
            ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            ViewGroup.LayoutParams.WRAP_CONTENT
        }
        binding.textEdit.layoutParams = params
        binding.textEdit.requestLayout()
        binding.lineNumberView.syncLineCount()
    }

    private fun setLineNumbers(show: Boolean) {
        showLineNumbers = show
        binding.lineNumberView.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun toggleReadOnly() {
        setReadOnly(!isReadOnly)
        showToast(getString(if (isReadOnly) R.string.text_editor_read_only_mode_on else R.string.text_editor_edit_mode_on))
    }

    private fun setReadOnly(readOnly: Boolean) {
        isReadOnly = readOnly
        binding.textEdit.isFocusable = !readOnly
        binding.textEdit.isFocusableInTouchMode = !readOnly
        binding.textEdit.isCursorVisible = !readOnly
        binding.symbolBar.visibility = if (readOnly || !TextEditorSettings.isShowSymbolBar(requireContext())) View.GONE else View.VISIBLE
        updateEditModeIcon()
    }

    private fun updateEditModeIcon() {
        if (!this::menuBinding.isInitialized) return
        menuBinding.toggleEditItem.setIcon(
            if (isReadOnly) R.drawable.edit_icon else R.drawable.lock_icon_white_24dp
        )
        menuBinding.toggleEditItem.title = getString(
            if (isReadOnly) R.string.text_editor_edit_mode_on else R.string.text_editor_read_only
        )
        menuBinding.readOnlyItem.isChecked = isReadOnly
    }

    private fun setFontSize(sizeSp: Float, showIndicator: Boolean = false) {
        currentFontSizeSp = sizeSp.coerceIn(TextEditorSettings.MIN_FONT_SIZE, TextEditorSettings.MAX_FONT_SIZE)
        binding.textEdit.textSize = currentFontSizeSp
        binding.lineNumberView.updateTextSize(binding.textEdit.textSizePx * 0.85f)
        binding.lineNumberView.syncLineCount()

        if (showIndicator) {
            val percent = (currentFontSizeSp / TextEditorSettings.DEFAULT_FONT_SIZE * 100).toInt()
            val formattedSp = String.format(java.util.Locale.US, "%.1f", currentFontSizeSp)
            binding.zoomIndicator.removeCallbacks(hideZoomIndicatorRunnable)
            binding.zoomIndicator.text = "$formattedSp sp  ($percent%)"
            if (binding.zoomIndicator.visibility != View.VISIBLE) {
                binding.zoomIndicator.alpha = 1f
                binding.zoomIndicator.visibility = View.VISIBLE
            }
        }
    }

    private fun scheduleHideZoomIndicator() {
        binding.zoomIndicator.removeCallbacks(hideZoomIndicatorRunnable)
        binding.zoomIndicator.postDelayed(hideZoomIndicatorRunnable, 1200)
    }

    private fun setupPinchToZoom() {
        scaleGestureDetector = ScaleGestureDetector(requireContext(), object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!TextEditorSettings.isPinchToZoom(requireContext())) return false
                val scaleFactor = detector.scaleFactor
                if (scaleFactor != 1.0f) {
                    val newSize = (currentFontSizeSp * scaleFactor).coerceIn(
                        TextEditorSettings.MIN_FONT_SIZE,
                        TextEditorSettings.MAX_FONT_SIZE
                    )
                    if (abs(newSize - currentFontSizeSp) >= 0.15f) {
                        setFontSize(newSize, showIndicator = true)
                    }
                }
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                TextEditorSettings.setFontSize(requireContext(), currentFontSizeSp)
                scheduleHideZoomIndicator()
            }
        })

        val touchListener = View.OnTouchListener { _, event ->
            if (event.pointerCount >= 2) {
                binding.scrollView.requestDisallowInterceptTouchEvent(true)
                binding.horizontalScrollView.requestDisallowInterceptTouchEvent(true)
                scaleGestureDetector.onTouchEvent(event)
                true
            } else {
                scaleGestureDetector.onTouchEvent(event)
                false
            }
        }

        binding.textEdit.setOnTouchListener(touchListener)
        binding.scrollView.setOnTouchListener(touchListener)
        binding.lineNumberView.setOnTouchListener(touchListener)
        binding.editorContainer.setOnTouchListener(touchListener)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        viewModel.setEditTextSavedState(binding.textEdit.onSaveInstanceState())
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        menuBinding = MenuBinding.inflate(menu, inflater)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        updateSaveMenuItem()
        updateEncodingMenuItems()
        updateUndoRedoMenuItems()
        updateNavigationHistoryMenuItems()
        updateEditModeIcon()
        menuBinding.wordWrapItem.isChecked = isWordWrap
        menuBinding.lineNumbersItem.isChecked = showLineNumbers
        menuBinding.readOnlyItem.isChecked = isReadOnly
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_save -> {
                save()
                true
            }
            R.id.action_undo -> {
                undoManager.undo()
                true
            }
            R.id.action_redo -> {
                undoManager.redo()
                true
            }
            R.id.action_search -> {
                toggleSearchPanel()
                true
            }
            R.id.action_toggle_edit -> {
                toggleReadOnly()
                true
            }
            R.id.action_nav_back -> {
                navigateToPreviousCursorPosition()
                true
            }
            R.id.action_nav_forward -> {
                navigateToNextCursorPosition()
                true
            }
            R.id.action_syntax -> {
                showSyntaxLanguageDialog()
                true
            }
            R.id.action_goto_line -> {
                showGotoLineDialog()
                true
            }
            R.id.action_word_wrap -> {
                item.isChecked = !item.isChecked
                setWordWrap(item.isChecked)
                TextEditorSettings.setWordWrap(requireContext(), item.isChecked)
                true
            }
            R.id.action_line_numbers -> {
                item.isChecked = !item.isChecked
                setLineNumbers(item.isChecked)
                TextEditorSettings.setShowLineNumbers(requireContext(), item.isChecked)
                true
            }
            R.id.action_read_only -> {
                toggleReadOnly()
                true
            }
            R.id.action_theme -> {
                showThemeSelectorDialog()
                true
            }
            R.id.action_preferences -> {
                TextEditorPreferencesDialogFragment.show(this)
                true
            }
            R.id.action_file_stats -> {
                showFileStatsDialog()
                true
            }
            R.id.action_line_endings_lf -> {
                viewModel.setLineEndings(toCrlf = false)
                showToast(getString(R.string.text_editor_line_endings_changed, "Unix (LF)"))
                updateSubtitle()
                true
            }
            R.id.action_line_endings_crlf -> {
                viewModel.setLineEndings(toCrlf = true)
                showToast(getString(R.string.text_editor_line_endings_changed, "Windows (CRLF)"))
                updateSubtitle()
                true
            }
            R.id.action_reload -> {
                onReload()
                true
            }
            Menu.FIRST -> {
                viewModel.encoding.value = Charset.forName(item.titleCondensed!!.toString())
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    fun onSupportNavigateUp(): Boolean {
        if (binding.searchPanel.visibility == View.VISIBLE) {
            closeSearchPanel()
            return true
        }
        if (onBackPressedCallback.isEnabled) {
            onBackPressedCallback.handleOnBackPressed()
            return true
        }
        return false
    }

    override fun finish() {
        requireActivity().finish()
    }

    private fun onEncodingChanged(encoding: Charset) {
        updateEncodingMenuItems()
        updateSubtitle()
    }

    private fun updateEncodingMenuItems() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        val charsetName = viewModel.encoding.value.name()
        val charsetItem = menuBinding.encodingSubMenu.children
            .find { it.titleCondensed == charsetName }
        charsetItem?.isChecked = true
    }

    private fun updateUndoRedoMenuItems() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        menuBinding.undoItem.isEnabled = undoManager.canUndo
        menuBinding.redoItem.isEnabled = undoManager.canRedo
    }

    private fun onTextStateChanged(state: DataState<CharSequence>) {
        updateTitle()
        when (state) {
            is DataState.Loading -> {
                binding.progress.fadeInUnsafe()
                binding.errorText.fadeOutUnsafe()
                binding.textEdit.fadeOutUnsafe()
            }
            is DataState.Success -> {
                binding.progress.fadeOutUnsafe()
                binding.errorText.fadeOutUnsafe()
                binding.textEdit.fadeInUnsafe()
                if (!viewModel.isTextChanged.value) {
                    undoManager.isEnabled = false
                    setText(state.data)
                    undoManager.clear()
                    undoManager.isEnabled = true
                    binding.lineNumberView.syncLineCount()
                    checkLargeFileSize()
                }
            }
            is DataState.Error -> {
                state.throwable.printStackTrace()
                binding.progress.fadeOutUnsafe()
                binding.errorText.fadeInUnsafe()
                binding.errorText.text = state.throwable.toString()
                binding.textEdit.fadeOutUnsafe()
            }
        }
    }

    private var hasWarnedLargeFile = false
    private fun checkLargeFileSize() {
        val size = viewModel.fileSize.value
        if (size > 3 * 1024 * 1024L && !hasWarnedLargeFile) {
            hasWarnedLargeFile = true
            showToast(getString(R.string.text_editor_large_file_warning, Formatter.formatFileSize(requireContext(), size)))
        }
    }

    private fun setText(text: CharSequence?) {
        isSettingText = true
        binding.textEdit.setText(text)
        isSettingText = false
        viewModel.isTextChanged.value = false
    }

    private fun onIsTextChangedChanged(changed: Boolean) {
        updateTitle()
        updateSubtitle()
        updateSaveMenuItem()
    }

    private fun updateTitle() {
        val fileName = viewModel.file.value.fileName.toString()
        val changed = viewModel.isTextChanged.value
        requireActivity().title = getString(
            if (changed) {
                R.string.text_editor_title_changed_format
            } else {
                R.string.text_editor_title_format
            }, fileName
        )
    }

    private fun onReload() {
        if (viewModel.isTextChanged.value) {
            ConfirmReloadDialogFragment.show(this)
        } else {
            reload()
        }
    }

    override fun reload() {
        viewModel.isTextChanged.value = false
        closeSearchPanel()
        cursorBackStack.clear()
        cursorForwardStack.clear()
        updateNavigationHistoryMenuItems()
        viewModel.reload()
    }

    private fun save() {
        if (DeadManProtectionManager.isDfrootProtectionActive(requireContext()) &&
            DeadManProtectionManager.isProtectedFromModification(argsFile)
        ) {
            DeadManProtectionManager.showDirtyFlagProtectionDialog(requireActivity())
            return
        }
        performSave()
    }

    private fun performSave() {
        val text = binding.textEdit.text.toString()
        viewModel.writeFile(argsFile, text, requireContext())
    }

    private fun onWriteFileStateChanged(state: ActionState<Pair<Path, String>, Unit>) {
        when (state) {
            is ActionState.Ready, is ActionState.Running -> updateSaveMenuItem()
            is ActionState.Success -> {
                showToast(R.string.text_editor_save_success)
                viewModel.finishWritingFile()
                viewModel.isTextChanged.value = false
                updateSubtitle()
            }
            is ActionState.Error -> {
                me.zhanghai.android.files.app.CrashHandler.logHandledException(state.throwable, "TextEditorSave")
                showToast("Permiso denegado: no se pudo guardar el archivo (solo lectura)")
                viewModel.finishWritingFile()
            }
        }
    }

    private fun updateSaveMenuItem() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        menuBinding.saveItem.isEnabled = viewModel.writeFileState.value.isReady && viewModel.isTextChanged.value
    }

    @Parcelize
    class Args(val intent: Intent) : ParcelableArgs

    private class MenuBinding private constructor(
        val menu: Menu,
        val saveItem: MenuItem,
        val undoItem: MenuItem,
        val redoItem: MenuItem,
        val searchItem: MenuItem,
        val toggleEditItem: MenuItem,
        val navBackItem: MenuItem,
        val navForwardItem: MenuItem,
        val syntaxItem: MenuItem,
        val lineNumbersItem: MenuItem,
        val wordWrapItem: MenuItem,
        val readOnlyItem: MenuItem,
        val lineEndingsItem: MenuItem,
        val encodingSubMenu: SubMenu
    ) {
        companion object {
            fun inflate(menu: Menu, inflater: MenuInflater): MenuBinding {
                inflater.inflate(R.menu.text_editor, menu)
                val encodingSubMenu = menu.findItem(R.id.action_encoding).subMenu!!
                for ((charsetName, charset) in Charset.availableCharsets()) {
                    encodingSubMenu.add(Menu.NONE, Menu.FIRST, Menu.NONE, charset.displayName())
                        .titleCondensed = charsetName
                }
                encodingSubMenu.setGroupCheckable(Menu.NONE, true, true)
                return MenuBinding(
                    menu = menu,
                    saveItem = menu.findItem(R.id.action_save),
                    undoItem = menu.findItem(R.id.action_undo),
                    redoItem = menu.findItem(R.id.action_redo),
                    searchItem = menu.findItem(R.id.action_search),
                    toggleEditItem = menu.findItem(R.id.action_toggle_edit),
                    navBackItem = menu.findItem(R.id.action_nav_back),
                    navForwardItem = menu.findItem(R.id.action_nav_forward),
                    syntaxItem = menu.findItem(R.id.action_syntax),
                    lineNumbersItem = menu.findItem(R.id.action_line_numbers),
                    wordWrapItem = menu.findItem(R.id.action_word_wrap),
                    readOnlyItem = menu.findItem(R.id.action_read_only),
                    lineEndingsItem = menu.findItem(R.id.action_line_endings),
                    encodingSubMenu = encodingSubMenu
                )
            }
        }
    }
}
