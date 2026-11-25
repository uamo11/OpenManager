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
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.format.Formatter
import android.view.Gravity
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.event.TextSizeChangeEvent
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.SelectionMovement
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.TextEditorFragmentBinding
import me.zhanghai.android.files.provider.root.DeadManProtectionManager
import me.zhanghai.android.files.settings.PerformanceModeManager
import me.zhanghai.android.files.util.ActionState
import me.zhanghai.android.files.util.DataState
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.addOnBackPressedCallback
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.provider.linux.isLinuxPath
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
    ConfirmCloseDialogFragment.Listener, TextEditorPreferencesDialogFragment.Listener,
    ConfirmSyncDialogFragment.Listener {
    private val args by args<Args>()
    private lateinit var argsFile: Path

    private lateinit var binding: TextEditorFragmentBinding
    private lateinit var menuBinding: MenuBinding

    private val viewModel by viewModels { { TextEditorViewModel(argsFile) } }

    private lateinit var onBackPressedCallback: OnBackPressedCallback

    private var isSettingText = false

    /** The editor engine: draws only what is on screen, with its own undo, search and zoom. */
    private val editor: OmCodeEditor
        get() = binding.codeEditor

    private var currentLanguage = SyntaxLanguage.PLAIN_TEXT

    /** A new document opened from the side bar: saved with "Save as". */
    private val isDraft: Boolean
        get() = args.intent.getBooleanExtra(TextEditorActivity.EXTRA_DRAFT, false)

    // Search and replace options (the matches themselves live in the editor's searcher)
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

    private val hideZoomIndicatorRunnable = Runnable {
        if (this::binding.isInitialized) {
            if (PerformanceModeManager.isEnabled) {
                binding.zoomIndicator.animate().cancel()
                binding.zoomIndicator.visibility = View.GONE
                binding.zoomIndicator.alpha = 1f
            } else {
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val initialFile = args.intent.extraPath
        if (initialFile == null) {
            finish()
            return
        }
        argsFile = initialFile

        setHasOptionsMenu(true)

        lifecycleScope.launchWhenStarted {
            if (!::argsFile.isInitialized) return@launchWhenStarted
            onBackPressedCallback = object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() {
                    if (binding.searchPanel.visibility == View.VISIBLE) {
                        closeSearchPanel()
                        return
                    }
                    if ((isDraft || TextEditorSettings.isConfirmOnExit(requireContext())) &&
                        viewModel.isTextChanged.value
                    ) {
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

        setupEditor()

        setupStatusStrip()
        setupSearchPanel()
        setupSymbolBar()

        // Load saved preferences
        val ctx = requireContext()
        if (me.zhanghai.android.files.BuildConfig.DEBUG) {
            // E2E: open the editor in a given color theme.
            requireActivity().intent.getStringExtra("DEMO_EDITOR_THEME")?.let {
                TextEditorSettings.setTheme(ctx, EditorTheme.fromId(it))
            }
        }
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

        applyEditingPreferences(ctx)
        binding.symbolBar.visibility = if (TextEditorSettings.isShowSymbolBar(ctx) && !isReadOnly) View.VISIBLE else View.GONE

        setupWindowInsets()

        // Check for Demo Screen intents in Debug builds
        val demoSearch = requireActivity().intent.getBooleanExtra("DEMO_OPEN_SEARCH", false)
        val demoPrefs = requireActivity().intent.getBooleanExtra("DEMO_OPEN_PREFS", false)
        val demoSyntax = requireActivity().intent.getBooleanExtra("DEMO_OPEN_SYNTAX", false)
        if (demoSearch) {
            view.postDelayed({
                openSearchPanel()
                binding.searchEdit.setText("OpenManager")
                binding.replaceEdit.setText("OpenManagerPro")
            }, 300)
        } else if (demoPrefs) {
            view.postDelayed({
                TextEditorPreferencesDialogFragment.show(this)
            }, 300)
        } else if (demoSyntax) {
            view.postDelayed({
                showSyntaxLanguageDialog()
            }, 300)
        }
    }

    private fun setupEditor() {
        val ctx = requireContext()
        editor.typefaceText = editorTypeface()
        editor.typefaceLineNumber = editor.typefaceText
        editor.setLineSpacing(2f, 1.1f)
        editor.setHighlightBracketPair(true)
        editor.isCursorAnimationEnabled = !PerformanceModeManager.isEnabled
        editor.props.stickyScroll = false
        // Code: no spelling suggestions or autocorrect from the keyboard.
        editor.props.disallowSuggestions = true
        editor.setScaleTextSizes(
            spToPx(TextEditorSettings.MIN_FONT_SIZE), spToPx(TextEditorSettings.MAX_FONT_SIZE)
        )
        editor.isScalable = TextEditorSettings.isPinchToZoom(ctx)
        // Android decides about the on-screen keyboard (some phones wrongly report a hardware one).
        editor.setDisableSoftKbdIfHardKbdAvailable(false)
        // A jump made while a long text was still being laid out lands in the right place.
        editor.onLayoutReady = {
            if (this::binding.isInitialized && revealWhenLaidOut) {
                revealWhenLaidOut = false
                runCatching { editor.ensureSelectionVisible() }
            }
        }

        editor.subscribeAlways(ContentChangeEvent::class.java) { event ->
            if (isSettingText || event.action == ContentChangeEvent.ACTION_SET_NEW_TEXT) {
                return@subscribeAlways
            }
            if (viewModel.textState.value !is DataState.Success) return@subscribeAlways
            if (!viewModel.isTextChanged.value) viewModel.isTextChanged.value = true
            updateUndoRedoMenuItems()
            updateSubtitle()
        }
        editor.subscribeAlways(SelectionChangeEvent::class.java) { _ ->
            val cursor = editor.cursor
            currentCursorLine = cursor.leftLine + 1
            currentCursorCol = cursor.leftColumn + 1
            updateSubtitle()
            checkRecordCursorJump(cursor.left)
            if (me.zhanghai.android.files.BuildConfig.DEBUG) {
                android.util.Log.d("OMEditor", "cursor=$currentCursorLine:$currentCursorCol")
            }
        }
        editor.subscribeAlways(PublishSearchResultEvent::class.java) { _ -> updateSearchCount() }
        // Pinch to zoom is done by the editor; we keep the size and show it.
        editor.subscribeAlways(TextSizeChangeEvent::class.java) { event ->
            val sp = pxToSp(event.newTextSize)
            if (abs(sp - currentFontSizeSp) < 0.05f) return@subscribeAlways
            currentFontSizeSp = sp
            showZoomIndicator()
            binding.zoomIndicator.removeCallbacks(saveFontSizeRunnable)
            binding.zoomIndicator.postDelayed(saveFontSizeRunnable, 600)
        }
    }

    // Set when the cursor was moved while the editor was still laying out a long text.
    private var revealWhenLaidOut = false

    /** Puts the cursor at [line]:[column] and keeps it in view, also after a pending layout. */
    private fun selectAndReveal(line: Int, column: Int) {
        editor.setSelection(line, column, true)
        // The background layout of a long wrapped text may start just after this call.
        if (editor.isLayingOut || (editor.isWordwrap && editor.text.length > LONG_TEXT_CHARS)) {
            revealWhenLaidOut = true
            binding.zoomIndicator.removeCallbacks(forgetRevealRunnable)
            binding.zoomIndicator.postDelayed(forgetRevealRunnable, 15_000)
        }
    }

    private val forgetRevealRunnable = Runnable { revealWhenLaidOut = false }

    private val saveFontSizeRunnable = Runnable {
        context?.let { TextEditorSettings.setFontSize(it, currentFontSizeSp) }
        if (this::binding.isInitialized) scheduleHideZoomIndicator()
    }

    private fun editorTypeface(): Typeface =
        if (!PerformanceModeManager.currentBudget.editorUseCustomFont) {
            Typeface.MONOSPACE
        } else {
            try {
                Typeface.createFromAsset(requireContext().assets, "Mono.ttf")
            } catch (e: Exception) {
                Typeface.MONOSPACE
            }
        }

    private fun spToPx(sp: Float): Float = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics
    )

    private fun pxToSp(px: Float): Float = px / spToPx(1f)

    private fun applyEditingPreferences(ctx: Context) {
        editor.isHighlightCurrentLine = TextEditorSettings.isHighlightCurrentLine(ctx)
        editor.props.autoIndent = TextEditorSettings.isAutoIndent(ctx)
        editor.props.symbolPairAutoCompletion = TextEditorSettings.isAutoClosePairs(ctx)
        editor.isScalable = TextEditorSettings.isPinchToZoom(ctx)
        applyLanguage()
    }

    /** Coloring and indentation for [currentLanguage] in [currentTheme], done by the editor. */
    private fun applyLanguage() {
        val ctx = context ?: return
        if (!this::binding.isInitialized) return
        editor.setEditorLanguage(
            OmCodeLanguage(
                currentLanguage,
                currentTheme,
                TextEditorSettings.isSyntaxHighlighting(ctx),
                TextEditorSettings.isAutoClosePairs(ctx)
            )
        )
    }

    override fun onResume() {
        super.onResume()
        if (::argsFile.isInitialized) {
            checkAndPromptExternalChange()
            startFileChangeWatcher()
        }
    }

    override fun onPause() {
        super.onPause()
        stopFileChangeWatcher()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Leaving a new document without saving: its scratch file goes away.
        if (isDraft && ::argsFile.isInitialized && activity?.isFinishing == true) {
            TextEditorActivity.deleteDraft(argsFile)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopFileChangeWatcher()
        if (this::binding.isInitialized) {
            binding.zoomIndicator.removeCallbacks(hideZoomIndicatorRunnable)
            binding.zoomIndicator.removeCallbacks(saveFontSizeRunnable)
            binding.zoomIndicator.removeCallbacks(forgetRevealRunnable)
            // Rotation or theme change: the new view continues with this document and its undo
            // history. Leaving the editor: nothing to keep.
            if (viewModel.textState.value is DataState.Success && activity?.isFinishing != true) {
                val cursor = editor.cursor
                viewModel.retainedContent = editor.text
                viewModel.retainedSelection = intArrayOf(cursor.leftLine, cursor.leftColumn)
            } else {
                viewModel.retainedContent = null
            }
            editor.release()
        }
        if (PerformanceModeManager.isEnabled) {
            cursorBackStack.clear()
            cursorForwardStack.clear()
        }
    }

    private var isSyncDialogShowing = false
    private var fileChangeWatcherJob: Job? = null

    private fun checkAndPromptExternalChange() {
        if (!::argsFile.isInitialized) return
        if (isSyncDialogShowing) return
        if (!isResumed) return
        val file = argsFile
        lifecycleScope.launch(Dispatchers.IO) {
            val changed = viewModel.hasExternalChange(file)
            if (changed) {
                withContext(Dispatchers.Main) {
                    if (isResumed && !isSyncDialogShowing && !parentFragmentManager.isStateSaved) {
                        isSyncDialogShowing = true
                        ConfirmSyncDialogFragment.show(
                            hasUnsavedChanges = viewModel.isTextChanged.value,
                            fragment = this@TextEditorFragment
                        )
                    }
                }
            }
        }
    }

    private fun startFileChangeWatcher() {
        fileChangeWatcherJob?.cancel()
        // Remote files (SFTP, SMB...) are only checked when the editor comes back to the front:
        // asking the server every 2.5 s cost network and battery.
        if (!argsFile.isLinuxPath || isDraft) return
        fileChangeWatcherJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(2500L)
                if (!isSyncDialogShowing && isResumed && ::argsFile.isInitialized) {
                    val file = argsFile
                    val changed = withContext(Dispatchers.IO) {
                        viewModel.hasExternalChange(file)
                    }
                    if (changed && isActive && !isSyncDialogShowing && isResumed && !parentFragmentManager.isStateSaved) {
                        isSyncDialogShowing = true
                        ConfirmSyncDialogFragment.show(
                            hasUnsavedChanges = viewModel.isTextChanged.value,
                            fragment = this@TextEditorFragment
                        )
                    }
                }
            }
        }
    }

    private fun stopFileChangeWatcher() {
        fileChangeWatcherJob?.cancel()
        fileChangeWatcherJob = null
    }

    override fun onSyncConfirmed() {
        isSyncDialogShowing = false
        pendingRestore = intArrayOf(editor.cursor.leftLine, editor.cursor.leftColumn)
        reload()
        showToast(getString(R.string.text_editor_sync_success))
    }

    // Position to return to once a reloaded text is in the editor.
    private var pendingRestore: IntArray? = null

    override fun onSyncIgnored() {
        isSyncDialogShowing = false
        if (::argsFile.isInitialized) {
            viewModel.acknowledgeExternalChange(argsFile)
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

        applyEditingPreferences(ctx)
        binding.symbolBar.visibility = if (TextEditorSettings.isShowSymbolBar(ctx) && !isReadOnly) View.VISIBLE else View.GONE
        applyBottomBarInsets()
    }

    private fun applyTheme(theme: EditorTheme) {
        currentTheme = theme
        FastSyntaxScanner.currentTheme = theme
        binding.editorContainer.setBackgroundColor(theme.backgroundColor)
        editor.colorScheme = OmEditorColorScheme(theme)
        // Token colors depend on the theme.
        applyLanguage()
        binding.editorStatusStrip.setBackgroundColor(theme.gutterColor)
        binding.searchPanel.setBackgroundColor(theme.gutterColor)
        recolorChrome(binding.editorStatusStrip, theme)
        recolorChrome(binding.searchPanel, theme)
        if (view != null) updateSubtitle()
        try {
            requireActivity().window.navigationBarColor = theme.gutterColor
        } catch (ignored: Throwable) {}
        // After the line above: with the symbol bar shown, the navigation bar takes its color.
        applySymbolBarColors()
    }

    /**
     * The status strip, symbol bar and search panel are drawn with One Dark colors in XML. Each
     * view remembers its original color and gets the matching color of [theme].
     */
    private fun recolorChrome(root: View, theme: EditorTheme) {
        fun mapped(original: Int): Int = when (original) {
            0xFFDCDFE4.toInt(), 0xFFFFFFFF.toInt() -> theme.textColor
            0xFFABB2BF.toInt() -> theme.commentColor
            0xFF5C6370.toInt() -> theme.gutterTextColor
            0xFF61AFEF.toInt() -> theme.gutterCurrentLineColor
            0xFFE5C07B.toInt() -> theme.typeColor
            0xFF98C379.toInt() -> theme.stringColor
            0xFFC678DD.toInt() -> theme.keywordColor
            else -> original
        }
        fun walk(v: View) {
            when (v) {
                is android.widget.EditText -> {
                    val original = (v.getTag(R.id.om_editor_original_color) as? IntArray)
                        ?: intArrayOf(v.currentTextColor, v.currentHintTextColor)
                            .also { v.setTag(R.id.om_editor_original_color, it) }
                    v.setTextColor(mapped(original[0]))
                    v.setHintTextColor(mapped(original[1]))
                }
                is android.widget.TextView -> {
                    val original = (v.getTag(R.id.om_editor_original_color) as? IntArray)
                        ?: intArrayOf(v.currentTextColor).also { v.setTag(R.id.om_editor_original_color, it) }
                    v.setTextColor(mapped(original[0]))
                }
                is android.widget.ImageView -> {
                    v.setColorFilter(theme.textColor)
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
    }

    /** Language names are universal except plain text, which is translated. */
    private fun syntaxName(language: SyntaxLanguage): String =
        if (language == SyntaxLanguage.PLAIN_TEXT) getString(R.string.om_syntax_plain_text) else language.displayName

    /** "Untitled" for a new document, else the file name. */
    private fun displayName(): String =
        if (isDraft) getString(R.string.om_text_editor_untitled) else viewModel.file.value.fileName.toString()

    private fun setupStatusStrip() {
        // Tapping Cursor Pos jumps to line
        binding.statusCursorPosText.setOnClickListener {
            showGotoLineDialog()
        }
        // Tapping Encoding opens encoding selector
        binding.statusEncodingText.setOnClickListener {
            showEncodingDialog()
        }
        // Tapping Line Ending toggles CRLF / LF
        binding.statusLineEndingText.setOnClickListener {
            viewModel.isCrlf.value = !viewModel.isCrlf.value
            updateSubtitle()
            val newEnding = if (viewModel.isCrlf.value) "CRLF" else "LF"
            showToast(getString(R.string.om_line_ending_toast, newEnding))
        }
        // Tapping Syntax opens syntax selector
        binding.statusSyntaxText.setOnClickListener {
            showSyntaxLanguageDialog()
        }
    }

    private fun updateSubtitle() {
        val activity = requireActivity() as? AppCompatActivity ?: return
        val charsetName = viewModel.encoding.value.displayName()
        val lineEnding = if (viewModel.isCrlf.value) "CRLF" else "LF"
        val syntax = syntaxName(currentLanguage)
        activity.supportActionBar?.subtitle = null

        // Update MT Manager Status Info Strip
        val fileName = displayName()
        val changed = viewModel.isTextChanged.value
        val fileSize = viewModel.fileSize.value
        val formattedSize = when {
            viewModel.isPartial.value && fileSize > TextEditorViewModel.MAX_FILE_SIZE -> " (" + getString(
                R.string.om_text_editor_partial_status,
                Formatter.formatShortFileSize(requireContext(), TextEditorViewModel.MAX_FILE_SIZE),
                Formatter.formatFileSize(requireContext(), fileSize)
            ) + ")"
            fileSize > 0 -> " (${Formatter.formatFileSize(requireContext(), fileSize)})"
            else -> ""
        }
        binding.statusFileNameText.text = "${if (changed) "*" else ""}$fileName$formattedSize"
        binding.statusFileNameText.setTextColor(if (changed) currentTheme.typeColor else currentTheme.textColor)

        binding.statusCursorPosText.text = "$currentCursorLine:$currentCursorCol"
        binding.statusEncodingText.text = charsetName
        binding.statusLineEndingText.text = lineEnding
        binding.statusSyntaxText.text = syntax

        // Update selection indicator
        val cursor = editor.cursor
        if (cursor.isSelected) {
            val selLen = abs(cursor.right - cursor.left)
            binding.statusSelectionText.visibility = View.VISIBLE
            binding.statusSelectionText.text = getString(R.string.om_text_editor_selection, selLen)
        } else {
            binding.statusSelectionText.visibility = View.GONE
        }
    }

    /**
     * The symbol bar, styled like the explorer's bottom bar (same color: gray, AMOLED black or
     * light, flat keys with a ripple, real icons of one size) instead of boxed Unicode glyphs.
     */
    private fun setupSymbolBar() {
        val container = binding.symbolContainer
        container.removeAllViews()

        // Moving the cursor
        addIconKey(R.drawable.ic_key_left_24dp, R.string.om_editor_key_left) { editor.moveSelection(SelectionMovement.LEFT) }
        addIconKey(R.drawable.ic_key_right_24dp, R.string.om_editor_key_right) { editor.moveSelection(SelectionMovement.RIGHT) }
        addIconKey(R.drawable.ic_key_up_24dp, R.string.om_editor_key_up) { editor.moveSelection(SelectionMovement.UP) }
        addIconKey(R.drawable.ic_key_down_24dp, R.string.om_editor_key_down) { editor.moveSelection(SelectionMovement.DOWN) }
        addKeyDivider()
        // Editing
        addIconKey(R.drawable.ic_editor_goto_line_24dp, R.string.om_editor_key_tab) {
            insertIfEditable { editor.insertText(" ".repeat(OmCodeLanguage.INDENT), OmCodeLanguage.INDENT) }
        }
        addIconKey(R.drawable.ic_key_indent_24dp, R.string.om_editor_key_indent) { indentLines(unindent = false) }
        addIconKey(R.drawable.ic_key_outdent_24dp, R.string.om_editor_key_outdent) { indentLines(unindent = true) }
        addIconKey(R.drawable.ic_editor_line_endings_24dp, R.string.om_editor_key_enter) {
            insertIfEditable { editor.commitText("\n", true) }
        }
        addKeyDivider()
        addIconKey(R.drawable.ic_undo_24dp, R.string.text_editor_undo) { if (editor.canUndo()) editor.undo() }
        addIconKey(R.drawable.ic_redo_24dp, R.string.text_editor_redo) { if (editor.canRedo()) editor.redo() }
        addIconKey(R.drawable.search_icon_control_normal_24dp, R.string.text_editor_search) { toggleSearchPanel() }
        addKeyDivider()
        // Brackets, quotes and operators (an opening one wraps the selection)
        for (sym in listOf(
            "{", "}", "(", ")", "[", "]", "<", ">", "\"", "'", "=", ":", ";", "/", "\\", "+", "-",
            "*", "%", "!", "?", "&", "|", "@", "#", "$", "~", "^", ",", ".", "_"
        )) {
            addTextKey(sym) { insertSymbol(sym) }
        }
        addKeyDivider()
        addIconKey(R.drawable.ic_key_paste_24dp, R.string.om_editor_key_paste) { pasteClipboardText() }
        addIconKey(R.drawable.select_all_icon_white_24dp, R.string.om_editor_key_select_all) { editor.selectAll() }
        applySymbolBarColors()
    }

    /** Colors of the explorer's bottom bar, so the editor's bar matches the rest of the app. */
    private fun applySymbolBarColors() {
        if (!this::binding.isInitialized) return
        val palette = me.zhanghai.android.files.theme.custom.OpenManagerPalette.from(requireContext())
        binding.symbolBar.setBackgroundColor(palette.bottomBarBg)
        val tint = android.content.res.ColorStateList.valueOf(palette.iconTint)
        val container = binding.symbolContainer
        for (i in 0 until container.childCount) {
            when (val key = container.getChildAt(i)) {
                is ImageButton -> androidx.core.widget.ImageViewCompat.setImageTintList(key, tint)
                is TextView -> key.setTextColor(palette.iconTint)
                else -> key.setBackgroundColor(palette.dividerLineColor)
            }
        }
        if (binding.symbolBar.visibility == View.VISIBLE) {
            runCatching { requireActivity().window.navigationBarColor = palette.bottomBarBg }
        }
    }

    private fun pasteClipboardText() {
        insertIfEditable { editor.pasteText() }
    }

    private inline fun insertIfEditable(action: () -> Unit) {
        if (isReadOnly || !editor.isEditable) return
        action()
    }

    /**
     * A symbol from the bar. An opening bracket or quote also gets its closing one around the
     * selection or after the cursor, as typing does with auto-close on.
     */
    private fun insertSymbol(symbol: String) {
        insertIfEditable {
            val closing = when (symbol) {
                "{" -> "}"
                "(" -> ")"
                "[" -> "]"
                "\"" -> "\""
                "'" -> "'"
                else -> null
            }
            val cursor = editor.cursor
            if (closing != null && editor.props.symbolPairAutoCompletion) {
                if (cursor.isSelected) {
                    val content = editor.text
                    val leftLine = cursor.leftLine
                    val leftColumn = cursor.leftColumn
                    val rightLine = cursor.rightLine
                    val rightColumn = cursor.rightColumn
                    content.beginBatchEdit()
                    try {
                        content.insert(rightLine, rightColumn, closing)
                        content.insert(leftLine, leftColumn, symbol)
                    } finally {
                        content.endBatchEdit()
                    }
                } else {
                    editor.insertText(symbol + closing, symbol.length)
                }
            } else {
                editor.insertText(symbol, symbol.length)
            }
        }
    }

    /** Adds or removes one indentation level on every line of the selection (or the cursor's). */
    private fun indentLines(unindent: Boolean) {
        insertIfEditable {
            val content = editor.text
            val cursor = editor.cursor
            val first = cursor.leftLine
            val last = if (cursor.isSelected && cursor.rightColumn == 0 && cursor.rightLine > first) {
                cursor.rightLine - 1
            } else {
                cursor.rightLine
            }
            val indent = " ".repeat(OmCodeLanguage.INDENT)
            content.beginBatchEdit()
            try {
                for (line in first..last) {
                    if (unindent) {
                        val text = content.getLine(line)
                        var n = 0
                        while (n < OmCodeLanguage.INDENT && n < text.length && text[n] == ' ') n++
                        if (n == 0 && text.isNotEmpty() && text[0] == '\t') n = 1
                        if (n > 0) content.delete(line, 0, line, n)
                    } else {
                        content.insert(line, 0, indent)
                    }
                }
            } finally {
                content.endBatchEdit()
            }
        }
    }

    private var currentImeBottom: Int = 0
    private var currentNavBottom: Int = 0

    private fun setupWindowInsets() {
        // This fragment places every system bar inset itself (status bar above the toolbar, the
        // navigation bar or keyboard under the symbol bar). The app bar and the scrolling frame
        // also add them on their own, which left an empty band of the status bar's height above
        // the toolbar and one of the navigation bar's height under the symbol bar (user report).
        binding.appBarLayout.fitsSystemWindows = false
        ViewCompat.setOnApplyWindowInsetsListener(binding.editorScrollingFrame) { _, insets -> insets }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val cutouts = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val statusBarTop = maxOf(statusBars.top, cutouts.top)
            val insetLeft = maxOf(navBars.left, cutouts.left)
            val insetRight = maxOf(navBars.right, cutouts.right)

            binding.appBarLayout.updatePadding(left = insetLeft, top = statusBarTop, right = insetRight)
            binding.editorContentLayout.updatePadding(left = insetLeft, right = insetRight)
            updateInsetsFromWindow(insets)
            insets
        }

        ViewCompat.setWindowInsetsAnimationCallback(
            binding.root,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: List<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    updateInsetsFromWindow(insets)
                    return insets
                }
            }
        )

        binding.root.viewTreeObserver.addOnGlobalLayoutListener {
            if (!this::binding.isInitialized) return@addOnGlobalLayoutListener
            val insets = ViewCompat.getRootWindowInsets(binding.root)
            if (insets != null) {
                updateInsetsFromWindow(insets)
            } else {
                val r = Rect()
                binding.root.getWindowVisibleDisplayFrame(r)
                val screenHeight = binding.root.rootView.height
                val heightDiff = screenHeight - r.bottom
                val density = resources.displayMetrics.density
                val imeBottom = if (heightDiff > (120 * density).toInt()) heightDiff else 0
                if (currentImeBottom != imeBottom) {
                    currentImeBottom = imeBottom
                    applyBottomBarInsets()
                }
            }
        }
    }

    private fun updateInsetsFromWindow(insets: WindowInsetsCompat) {
        val imeInsetsBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        val navInsetsBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            .coerceAtLeast(insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
        val isImeVisible = insets.isVisible(WindowInsetsCompat.Type.ime()) || imeInsetsBottom > navInsetsBottom
        var imeBottom = if (isImeVisible && imeInsetsBottom > 0) imeInsetsBottom else 0

        // Fallback display frame check for OEM ROMs
        if (imeBottom == 0 && this::binding.isInitialized) {
            val r = Rect()
            binding.root.getWindowVisibleDisplayFrame(r)
            val screenHeight = binding.root.rootView.height
            val heightDiff = screenHeight - r.bottom
            val density = resources.displayMetrics.density
            if (heightDiff > (120 * density).toInt()) {
                imeBottom = heightDiff
            }
        }

        if (currentImeBottom != imeBottom || currentNavBottom != navInsetsBottom) {
            currentImeBottom = imeBottom
            currentNavBottom = navInsetsBottom
            applyBottomBarInsets()
        }
    }

    private fun applyBottomBarInsets() {
        if (!this::binding.isInitialized) return
        val imeBottom = currentImeBottom
        val navBottom = currentNavBottom
        val baseSearchBottomPadding = (8 * resources.displayMetrics.density).toInt()

        val lp = binding.editorContentLayout.layoutParams as? ViewGroup.MarginLayoutParams
        if (imeBottom > 0) {
            // Soft keyboard is OPEN: lift the entire content layout so symbol bar sits right on top of keyboard
            if (lp != null && lp.bottomMargin != imeBottom) {
                lp.bottomMargin = imeBottom
                binding.editorContentLayout.layoutParams = lp
            }
            binding.symbolBar.setPadding(0, 0, 0, 0)
            binding.searchPanel.setPadding(
                binding.searchPanel.paddingLeft,
                binding.searchPanel.paddingTop,
                binding.searchPanel.paddingRight,
                baseSearchBottomPadding
            )
            binding.editorContainer.setPadding(0, 0, 0, 0)

            editor.post {
                if (this::binding.isInitialized) runCatching { editor.ensureSelectionVisible() }
            }
        } else {
            // Soft keyboard is CLOSED: content layout rests at bottom, pad above navigation bar
            if (lp != null && lp.bottomMargin != 0) {
                lp.bottomMargin = 0
                binding.editorContentLayout.layoutParams = lp
            }
            if (binding.symbolBar.visibility == View.VISIBLE) {
                binding.symbolBar.setPadding(0, 0, 0, navBottom)
                binding.searchPanel.setPadding(
                    binding.searchPanel.paddingLeft,
                    binding.searchPanel.paddingTop,
                    binding.searchPanel.paddingRight,
                    baseSearchBottomPadding
                )
                binding.editorContainer.setPadding(0, 0, 0, 0)
            } else if (binding.searchPanel.visibility == View.VISIBLE) {
                binding.symbolBar.setPadding(0, 0, 0, 0)
                binding.searchPanel.setPadding(
                    binding.searchPanel.paddingLeft,
                    binding.searchPanel.paddingTop,
                    binding.searchPanel.paddingRight,
                    baseSearchBottomPadding + navBottom
                )
                binding.editorContainer.setPadding(0, 0, 0, 0)
            } else {
                binding.symbolBar.setPadding(0, 0, 0, 0)
                binding.searchPanel.setPadding(
                    binding.searchPanel.paddingLeft,
                    binding.searchPanel.paddingTop,
                    binding.searchPanel.paddingRight,
                    baseSearchBottomPadding
                )
                binding.editorContainer.setPadding(0, 0, 0, navBottom)
            }
        }
    }

    private fun keyBackground(): Int {
        val value = android.util.TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)
        return value.resourceId
    }

    private fun addTextKey(text: String, onClick: () -> Unit) {
        val density = resources.displayMetrics.density
        val key = TextView(requireContext()).apply {
            this.text = text
            gravity = Gravity.CENTER
            minWidth = (40 * density).toInt()
            setPadding((6 * density).toInt(), 0, (6 * density).toInt(), 0)
            textSize = 17f
            typeface = editorTypeface()
            setBackgroundResource(keyBackground())
            isClickable = true
            isFocusable = true
            contentDescription = text
            setOnClickListener { onClick() }
        }
        binding.symbolContainer.addView(
            key, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    private fun addIconKey(resId: Int, contentDescriptionRes: Int, onClick: () -> Unit) {
        val density = resources.displayMetrics.density
        val key = ImageButton(requireContext()).apply {
            setImageResource(resId)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = getString(contentDescriptionRes)
            setBackgroundResource(keyBackground())
            val pad = (10 * density).toInt()
            setPadding(pad, pad, pad, pad)
            setOnClickListener { onClick() }
            // The name of the key on a long press, like the toolbar's buttons.
            androidx.appcompat.widget.TooltipCompat.setTooltipText(this, contentDescription)
        }
        binding.symbolContainer.addView(
            key, LinearLayout.LayoutParams((44 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    /** A thin separator between groups of keys. */
    private fun addKeyDivider() {
        val density = resources.displayMetrics.density
        val divider = View(requireContext())
        binding.symbolContainer.addView(
            divider,
            LinearLayout.LayoutParams((1 * density).coerceAtLeast(1f).toInt(), (20 * density).toInt()).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = (4 * density).toInt()
                marginEnd = (4 * density).toInt()
            }
        )
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
        applyBottomBarInsets()
        binding.searchEdit.requestFocus()
        performSearch(resetCurrent = true)
    }

    private fun closeSearchPanel() {
        binding.searchPanel.visibility = View.GONE
        applyBottomBarInsets()
        runCatching { editor.searcher.stopSearch() }
        binding.searchCountText.setText(R.string.text_editor_no_matches)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun performSearch(resetCurrent: Boolean) {
        val query = binding.searchEdit.text.toString()
        val searcher = editor.searcher
        if (query.isEmpty()) {
            searcher.stopSearch()
            binding.searchCountText.setText(R.string.text_editor_no_matches)
            return
        }
        val type = when {
            isSearchRegex -> EditorSearcher.SearchOptions.TYPE_REGULAR_EXPRESSION
            isSearchWholeWord -> EditorSearcher.SearchOptions.TYPE_WHOLE_WORD
            else -> EditorSearcher.SearchOptions.TYPE_NORMAL
        }
        try {
            // Runs in the background; PublishSearchResultEvent brings the count.
            searcher.search(query, EditorSearcher.SearchOptions(type, !isSearchCaseSensitive))
        } catch (e: Exception) {
            // A regular expression that is still being typed.
            searcher.stopSearch()
            binding.searchCountText.setText(R.string.text_editor_no_matches)
        }
    }

    private fun updateSearchCount() {
        if (!this::binding.isInitialized || binding.searchPanel.visibility != View.VISIBLE) return
        val searcher = editor.searcher
        val count = if (searcher.hasQuery()) searcher.matchedPositionCount else 0
        if (count <= 0) {
            binding.searchCountText.setText(R.string.text_editor_no_matches)
            return
        }
        val current = searcher.currentMatchedPositionIndex
        binding.searchCountText.text =
            getString(R.string.text_editor_match_count, (current + 1).coerceAtLeast(0), count)
    }

    private fun nextSearchMatch() {
        val searcher = editor.searcher
        if (!searcher.hasQuery() || searcher.matchedPositionCount <= 0) {
            showToast(getString(R.string.text_editor_no_text_found))
            return
        }
        searcher.gotoNext()
        updateSearchCount()
    }

    private fun prevSearchMatch() {
        val searcher = editor.searcher
        if (!searcher.hasQuery() || searcher.matchedPositionCount <= 0) {
            showToast(getString(R.string.text_editor_no_text_found))
            return
        }
        searcher.gotoPrevious()
        updateSearchCount()
    }

    private fun replaceCurrentMatch() {
        if (isReadOnly) return
        val searcher = editor.searcher
        if (!searcher.hasQuery() || searcher.matchedPositionCount <= 0) return
        if (!searcher.isMatchedPositionSelected) {
            searcher.gotoNext()
            return
        }
        searcher.replaceCurrentMatch(binding.replaceEdit.text.toString())
    }

    private fun replaceAllMatches() {
        if (isReadOnly) return
        val searcher = editor.searcher
        if (!searcher.hasQuery()) return
        val count = searcher.matchedPositionCount
        if (count <= 0) return
        searcher.replaceAll(binding.replaceEdit.text.toString()) {
            if (isAdded) showToast(getString(R.string.text_editor_replaced_count, count))
        }
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
        cursorForwardStack.addLast(editor.cursor.left)
        val prev = cursorBackStack.removeLast()
        lastRecordedCursorOffset = prev
        moveCursorToOffset(prev)
        updateNavigationHistoryMenuItems()
        showToast(getString(R.string.text_editor_navigated_back))
    }

    private fun navigateToNextCursorPosition() {
        if (cursorForwardStack.isEmpty()) return
        cursorBackStack.addLast(editor.cursor.left)
        val next = cursorForwardStack.removeLast()
        lastRecordedCursorOffset = next
        moveCursorToOffset(next)
        updateNavigationHistoryMenuItems()
        showToast(getString(R.string.text_editor_navigated_forward))
    }

    private fun moveCursorToOffset(offset: Int) {
        val content = editor.text
        val position = content.indexer.getCharPosition(offset.coerceIn(0, content.length))
        selectAndReveal(position.line, position.column)
    }

    private fun jumpToLine(targetLine: Int) {
        val lineCount = editor.text.lineCount
        if (lineCount <= 0) return
        val line = (targetLine - 1).coerceIn(0, lineCount - 1)
        selectAndReveal(line, 0)
    }

    private fun updateNavigationHistoryMenuItems() {
        if (!this::menuBinding.isInitialized) return
        menuBinding.navBackItem.isEnabled = cursorBackStack.isNotEmpty()
        menuBinding.navForwardItem.isEnabled = cursorForwardStack.isNotEmpty()
    }

    private fun showSyntaxLanguageDialog() {
        val languages = SyntaxLanguage.entries.toTypedArray()
        val names = languages.map { syntaxName(it) }.toTypedArray()
        val selectedIndex = languages.indexOf(currentLanguage)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_editor_syntax_title)
            .setSingleChoiceItems(names, selectedIndex) { dialog, which ->
                val selected = languages[which]
                if (selected != currentLanguage) {
                    currentLanguage = selected
                    viewModel.syntaxLanguage.value = selected
                    applyLanguage()
                    updateSubtitle()
                    showToast(getString(R.string.text_editor_syntax_changed, syntaxName(selected)))
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
        val totalLines = editor.text.lineCount.coerceAtLeast(1)
        val currentLine = editor.cursor.leftLine + 1
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
                    checkRecordCursorJump(editor.text.getCharIndex(targetLine - 1, 0))
                    jumpToLine(targetLine)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showFileStatsDialog() {
        val fileName = displayName()
        val filePath = viewModel.file.value.toAbsolutePath().toString()
        val sizeBytes = viewModel.fileSize.value
        val formattedSize = "${Formatter.formatFileSize(requireContext(), sizeBytes)} ($sizeBytes B)"
        val encodingName = viewModel.encoding.value.displayName()
        val lineEnding = if (viewModel.isCrlf.value) "Windows (CRLF)" else "Unix (LF)"
        val languageName = syntaxName(currentLanguage)
        val content = editor.text
        val lineCount = content.lineCount
        val charCount = content.length
        var nonSpace = 0
        var words = 0
        var inWord = false
        for (line in 0 until lineCount) {
            val text = content.getLine(line)
            for (i in 0 until text.length) {
                val c = text[i]
                if (c.isWhitespace()) {
                    inWord = false
                } else {
                    nonSpace++
                    if (!inWord) words++
                    inWord = true
                }
            }
            inWord = false
        }
        val on = getString(R.string.om_text_editor_on)
        val off = getString(R.string.om_text_editor_off)
        val message = """
            ${getString(R.string.file_properties_basic_name)}: $fileName
            ${getString(R.string.om_text_editor_stats_path)}: $filePath
            ${getString(R.string.file_properties_basic_size)}: $formattedSize
            ${getString(R.string.text_editor_encoding)}: $encodingName
            ${getString(R.string.text_editor_line_endings)}: $lineEnding
            ${getString(R.string.text_editor_syntax)}: $languageName
            ${getString(R.string.text_editor_theme)}: ${currentTheme.displayName}
            ${getString(R.string.text_editor_word_wrap)}: ${if (isWordWrap) on else off}
            ${getString(R.string.text_editor_line_numbers)}: ${if (showLineNumbers) on else off}

            ${getString(R.string.om_text_editor_stats_lines)}: $lineCount
            ${getString(R.string.om_text_editor_stats_words)}: $words
            ${getString(R.string.om_text_editor_stats_chars)}: $charCount
            ${getString(R.string.om_text_editor_stats_chars_no_spaces)}: $nonSpace
        """.trimIndent()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_editor_file_stats)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun setWordWrap(wrap: Boolean) {
        isWordWrap = wrap
        if (editor.isWordwrap != wrap) editor.isWordwrap = wrap
    }

    private fun setLineNumbers(show: Boolean) {
        showLineNumbers = show
        editor.isLineNumberEnabled = show
    }

    private fun toggleReadOnly() {
        if (isReadOnly && refusePartialSave()) return
        setReadOnly(!isReadOnly)
        showToast(getString(if (isReadOnly) R.string.text_editor_read_only_mode_on else R.string.text_editor_edit_mode_on))
    }

    private fun setReadOnly(readOnly: Boolean) {
        isReadOnly = readOnly
        editor.isEditable = !readOnly
        binding.symbolBar.visibility = if (readOnly || !TextEditorSettings.isShowSymbolBar(requireContext())) View.GONE else View.VISIBLE
        applyBottomBarInsets()
        updateEditModeIcon()
    }

    private fun updateEditModeIcon() {
        if (!this::menuBinding.isInitialized) return
        // The icon shows the current mode (open lock: editing, closed lock: read-only) in the
        // toolbar's color; the old white lock vanished on the light theme (user report).
        menuBinding.toggleEditItem.setIcon(
            if (isReadOnly) R.drawable.ic_editor_lock_24dp else R.drawable.ic_editor_lock_open_24dp
        )
        menuBinding.toggleEditItem.title = getString(
            if (isReadOnly) R.string.text_editor_edit_mode_on else R.string.text_editor_read_only
        )
        menuBinding.readOnlyItem.isChecked = isReadOnly
    }

    private fun setFontSize(sizeSp: Float, showIndicator: Boolean = false) {
        currentFontSizeSp = sizeSp.coerceIn(TextEditorSettings.MIN_FONT_SIZE, TextEditorSettings.MAX_FONT_SIZE)
        if (abs(editor.textSizePx - spToPx(currentFontSizeSp)) > 0.5f) editor.setTextSize(currentFontSizeSp)
        if (showIndicator) showZoomIndicator()
    }

    private fun showZoomIndicator() {
        val percent = (currentFontSizeSp / TextEditorSettings.DEFAULT_FONT_SIZE * 100).toInt()
        val formattedSp = String.format(java.util.Locale.US, "%.1f", currentFontSizeSp)
        binding.zoomIndicator.removeCallbacks(hideZoomIndicatorRunnable)
        binding.zoomIndicator.text = "$formattedSp sp  ($percent%)"
        if (binding.zoomIndicator.visibility != View.VISIBLE) {
            binding.zoomIndicator.alpha = 1f
            binding.zoomIndicator.visibility = View.VISIBLE
        }
    }

    private fun scheduleHideZoomIndicator() {
        binding.zoomIndicator.removeCallbacks(hideZoomIndicatorRunnable)
        binding.zoomIndicator.postDelayed(hideZoomIndicatorRunnable, 1200)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        menuBinding = MenuBinding.inflate(menu, inflater)
        // The ⋮ menu shows its icons, in the menu's own text color (visible on light and dark).
        (menu as? androidx.appcompat.view.menu.MenuBuilder)?.setOptionalIconsVisible(true)
        val iconColor = com.google.android.material.color.MaterialColors.getColor(
            requireContext(), androidx.appcompat.R.attr.colorControlNormal, Color.GRAY
        )
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            if (item.itemId in TOOLBAR_ITEMS) continue
            val icon = item.icon ?: continue
            item.icon = androidx.core.graphics.drawable.DrawableCompat.wrap(icon.mutate()).also {
                androidx.core.graphics.drawable.DrawableCompat.setTint(it, iconColor)
            }
        }
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
            R.id.action_save_as -> {
                if (!refusePartialSave()) showSaveAsDialog()
                true
            }
            R.id.action_undo -> {
                if (editor.canUndo()) editor.undo()
                true
            }
            R.id.action_redo -> {
                if (editor.canRedo()) editor.redo()
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
            R.id.action_encoding -> {
                ensureEncodingSubMenuPopulated()
                // Not consumed, so the (now populated) sub-menu still opens.
                false
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
        if (isDraft && ::argsFile.isInitialized) TextEditorActivity.deleteDraft(argsFile)
        // A file sent by another app goes back to the explorer (to keep or copy it).
        val activity = requireActivity()
        (activity as? me.zhanghai.android.files.app.AppActivity)?.leaveToIncomingFile() ?: activity.finish()
    }

    private fun onEncodingChanged(encoding: Charset) {
        updateEncodingMenuItems()
        updateSubtitle()
    }

    private fun ensureEncodingSubMenuPopulated() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        val encodingSubMenu = menuBinding.encodingSubMenu
        if (encodingSubMenu.size() > 0) {
            return
        }
        for ((charsetName, charset) in Charset.availableCharsets()) {
            encodingSubMenu.add(Menu.NONE, Menu.FIRST, Menu.NONE, charset.displayName())
                .titleCondensed = charsetName
        }
        encodingSubMenu.setGroupCheckable(Menu.NONE, true, true)
        updateEncodingMenuItems()
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
        if (!this::binding.isInitialized) return
        menuBinding.undoItem.isEnabled = editor.canUndo()
        menuBinding.redoItem.isEnabled = editor.canRedo()
    }

    private fun onTextStateChanged(state: DataState<CharSequence>) {
        updateTitle()
        when (state) {
            is DataState.Loading -> {
                binding.progress.fadeInUnsafe()
                binding.errorPanel.fadeOutUnsafe()
                binding.editorContainer.visibility = View.VISIBLE
                binding.codeEditor.fadeOutUnsafe()
            }
            is DataState.Success -> {
                binding.progress.fadeOutUnsafe()
                binding.errorPanel.fadeOutUnsafe()
                binding.editorContainer.visibility = View.VISIBLE
                binding.codeEditor.fadeInUnsafe()
                if (viewModel.isPartial.value && !isReadOnly) setReadOnly(true)
                val retained = viewModel.retainedContent as? io.github.rosemoe.sora.text.Content
                if (retained != null) {
                    // Same document after a rotation: keep the edits and the undo history.
                    viewModel.retainedContent = null
                    isSettingText = true
                    editor.setText(retained, true, null)
                    isSettingText = false
                    viewModel.retainedSelection?.let { restoreSelection(it[0], it[1]) }
                    viewModel.retainedSelection = null
                } else if (!viewModel.isTextChanged.value) {
                    setText(state.data)
                    pendingRestore?.let { restoreSelection(it[0], it[1]) }
                    pendingRestore = null
                    val gotoLine = requireActivity().intent.getIntExtra("EXTRA_GOTO_LINE", -1)
                    if (gotoLine > 0) {
                        requireActivity().intent.removeExtra("EXTRA_GOTO_LINE")
                        editor.post { if (this::binding.isInitialized) jumpToLine(gotoLine) }
                    }
                }
                updateUndoRedoMenuItems()
                updateSubtitle()
                if (me.zhanghai.android.files.BuildConfig.DEBUG) {
                    // E2E: how the text is laid out (wrapped rows per line).
                    editor.postDelayed({
                        if (this::binding.isInitialized) {
                            android.util.Log.d(
                                "OMEditor",
                                "layout lines=${editor.text.lineCount} rows=${editor.layout.rowCount} " +
                                    "wrap=${editor.isWordwrap} width=${editor.width}"
                            )
                        }
                    }, 1500)
                }
            }
            is DataState.Error -> {
                state.throwable.printStackTrace()
                binding.progress.fadeOutUnsafe()
                binding.errorText.text = describeLoadError(state.throwable)
                showLoadErrorActions(state.throwable)
                binding.editorContainer.visibility = View.INVISIBLE
                binding.errorPanel.fadeInUnsafe()
                binding.codeEditor.fadeOutUnsafe()
            }
        }
    }

    private fun restoreSelection(line: Int, column: Int) {
        val content = editor.text
        if (content.lineCount == 0) return
        val l = line.coerceIn(0, content.lineCount - 1)
        val c = column.coerceIn(0, content.getColumnCount(l))
        editor.post { if (this::binding.isInitialized) selectAndReveal(l, c) }
    }

    /**
     * A file over the limit is not a dead end: open it in the HEX editor (any size, with text
     * search), or read its first part here without being able to save it.
     */
    private fun showLoadErrorActions(throwable: Throwable) {
        val tooLarge = throwable is TextEditorViewModel.FileTooLargeException && !isDraft
        binding.errorOpenHexButton.visibility = if (tooLarge) View.VISIBLE else View.GONE
        binding.errorOpenPartialButton.visibility = if (tooLarge) View.VISIBLE else View.GONE
        if (!tooLarge) return
        binding.errorOpenPartialButton.text = getString(
            R.string.om_text_editor_open_partial,
            Formatter.formatShortFileSize(requireContext(), TextEditorViewModel.MAX_FILE_SIZE)
        )
        binding.errorOpenHexButton.setOnClickListener {
            me.zhanghai.android.files.viewer.hex.HexEditorActivity.start(requireContext(), argsFile)
            requireActivity().finish()
        }
        binding.errorOpenPartialButton.setOnClickListener { viewModel.loadPartial() }
    }

    /** Saving a partial view would cut the file, so it is refused. */
    private fun refusePartialSave(): Boolean {
        if (!viewModel.isPartial.value) return false
        showToast(R.string.om_text_editor_partial_read_only)
        return true
    }

    private fun describeLoadError(throwable: Throwable): String {
        val ctx = requireContext()
        return if (throwable is TextEditorViewModel.FileTooLargeException) {
            getString(
                R.string.om_text_editor_too_large,
                Formatter.formatFileSize(ctx, throwable.size),
                Formatter.formatFileSize(ctx, throwable.maxSize)
            )
        } else {
            getString(
                R.string.om_text_editor_open_failed,
                throwable.localizedMessage?.takeIf { it.isNotBlank() } ?: throwable.javaClass.simpleName
            )
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
        editor.setText(text ?: "")
        isSettingText = false
        viewModel.isTextChanged.value = false
    }

    private fun onIsTextChangedChanged(changed: Boolean) {
        updateTitle()
        updateSubtitle()
        updateSaveMenuItem()
    }

    private fun updateTitle() {
        val fileName = displayName()
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
        if (refusePartialSave()) return
        if (isDraft) {
            showSaveAsDialog()
            return
        }
        if (DeadManProtectionManager.isDfrootProtectionActive(requireContext()) &&
            DeadManProtectionManager.isProtectedFromModification(argsFile)
        ) {
            DeadManProtectionManager.showDirtyFlagProtectionDialog(requireActivity())
            return
        }
        performSave()
    }

    private fun performSave() {
        val text = editor.text.toString()
        viewModel.writeFile(argsFile, text, requireContext())
    }

    /** Name and folder for a new document, or a copy of this file somewhere else. */
    private fun showSaveAsDialog() {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val currentParent: Path? = if (isDraft) null else argsFile.parent
        val startFolder = when {
            currentParent != null -> currentParent.toString()
            else -> args.intent.getStringExtra(TextEditorActivity.EXTRA_TARGET_DIR)
                ?: android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOCUMENTS
                ).absolutePath
        }
        val startName = if (isDraft) getString(R.string.om_text_editor_new_file_name) else argsFile.fileName.toString()
        fun field(hint: Int, value: String) = com.google.android.material.textfield.TextInputLayout(ctx).apply {
            this.hint = getString(hint)
            addView(com.google.android.material.textfield.TextInputEditText(context).apply {
                setText(value)
                isSingleLine = true
            })
        }
        val nameField = field(R.string.om_text_editor_save_as_name, startName)
        val folderField = field(R.string.om_text_editor_save_as_folder, startFolder)
        val content = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (22 * density).toInt()
            setPadding(pad, (8 * density).toInt(), pad, 0)
            addView(nameField)
            addView(folderField)
        }
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.om_text_editor_save_as)
            .setView(content)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            // Select the name without its extension, ready to type over.
            nameField.editText?.let { edit ->
                val dot = startName.lastIndexOf('.')
                edit.requestFocus()
                edit.setSelection(0, if (dot > 0) dot else startName.length)
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameField.editText?.text?.toString()?.trim().orEmpty()
                val folder = folderField.editText?.text?.toString()?.trim().orEmpty()
                nameField.error = null
                folderField.error = null
                if (name.isEmpty() || name.contains('/') || name == "." || name == "..") {
                    nameField.error = getString(R.string.om_text_editor_save_as_name_invalid)
                    return@setOnClickListener
                }
                val parent: Path = if (currentParent != null && folder == currentParent.toString()) {
                    currentParent
                } else {
                    val dir = java.io.File(folder)
                    if (!dir.isDirectory) {
                        folderField.error = getString(R.string.om_text_editor_save_as_folder_missing)
                        return@setOnClickListener
                    }
                    java8.nio.file.Paths.get(dir.absolutePath)
                }
                val target = parent.resolve(name)
                dialog.dismiss()
                if (target == argsFile) {
                    performSave()
                    return@setOnClickListener
                }
                val exists = target.isLinuxPath && target.toFile().exists()
                if (exists) {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                        .setMessage(getString(R.string.om_text_editor_save_as_exists, name))
                        .setPositiveButton(R.string.om_text_editor_replace) { _, _ -> saveTo(target) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else {
                    saveTo(target)
                }
            }
        }
        dialog.show()
    }

    private fun saveTo(target: Path) {
        if (DeadManProtectionManager.isDfrootProtectionActive(requireContext()) &&
            DeadManProtectionManager.isProtectedFromModification(target)
        ) {
            DeadManProtectionManager.showDirtyFlagProtectionDialog(requireActivity())
            return
        }
        viewModel.writeFile(target, editor.text.toString(), requireContext())
    }

    /** Saved under another name: continue in that file (and drop the draft). */
    private fun continueIn(target: Path) {
        val activity = requireActivity()
        startActivity(Intent(activity, TextEditorActivity::class.java).apply { extraPath = target })
        if (isDraft) TextEditorActivity.deleteDraft(argsFile)
        activity.finish()
    }

    private fun onWriteFileStateChanged(state: ActionState<Pair<Path, String>, Unit>) {
        when (state) {
            is ActionState.Ready, is ActionState.Running -> updateSaveMenuItem()
            is ActionState.Success -> {
                if (state.argument.first != argsFile) {
                    showToast(R.string.text_editor_save_success)
                    viewModel.finishWritingFile()
                    viewModel.isTextChanged.value = false
                    continueIn(state.argument.first)
                    return
                }
                showToast(R.string.text_editor_save_success)
                viewModel.finishWritingFile()
                viewModel.isTextChanged.value = false
                updateSubtitle()
            }
            is ActionState.Error -> {
                me.zhanghai.android.files.app.CrashHandler.logHandledException(state.throwable, "TextEditorSave")
                showToast(getString(R.string.om_text_editor_save_permission_denied))
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

    private companion object {
        // Above this size word wrap is laid out in the background.
        const val LONG_TEXT_CHARS = 100_000

        // Items shown on the toolbar itself keep the toolbar's icon color.
        val TOOLBAR_ITEMS = setOf(
            R.id.action_save, R.id.action_undo, R.id.action_redo, R.id.action_search,
            R.id.action_toggle_edit
        )
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
                // Filled on first open by ensureEncodingSubMenuPopulated():
                // Charset.availableCharsets() instantiates every ICU converter (~2.5 MiB native).
                val encodingSubMenu = menu.findItem(R.id.action_encoding).subMenu!!
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
