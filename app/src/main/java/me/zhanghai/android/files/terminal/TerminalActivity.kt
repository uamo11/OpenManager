/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.Menu
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.provider.root.DeadManProtectionManager
import me.zhanghai.android.files.settings.PerformanceModeManager
import me.zhanghai.android.files.terminal.remote.ProfileTransport
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.zhanghai.android.files.terminal.remote.RemoteFilesLauncher
import me.zhanghai.android.files.terminal.remote.RemoteProfile
import me.zhanghai.android.files.terminal.remote.RemoteProfileStore
import me.zhanghai.android.files.terminal.remote.ConnectionsActivity
import me.zhanghai.android.files.terminal.remote.RemotePrompter
import me.zhanghai.android.files.terminal.remote.RemoteProtocol
import me.zhanghai.android.files.terminal.remote.SshTransport
import me.zhanghai.android.files.theme.custom.OpenManagerPalette
import me.zhanghai.android.files.ui.isUiAlive
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class TerminalActivity : AppActivity() {

    private lateinit var rootTerminal: View
    private lateinit var appBarLayout: AppBarLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var terminalContainer: View
    private lateinit var terminalView: TerminalView
    private lateinit var extraKeysView: TerminalExtraKeysView

    private var layoutTerminalHud: View? = null
    private var viewHudBottomDivider: View? = null
    private var layoutSessionBadge: View? = null
    private var viewSessionDotHalo: View? = null
    private var viewSessionDot: View? = null
    private var tvSessionLabel: TextView? = null
    private var tvSessionChevron: TextView? = null
    private var layoutCwdChip: View? = null
    private var ivCwdFolder: ImageView? = null
    private var tvTerminalBreadcrumb: TextView? = null
    private var layoutZoomPill: View? = null
    private var viewZoomDivLeft: View? = null
    private var viewZoomDivRight: View? = null
    private var btnZoomOut: TextView? = null
    private var btnZoomIn: TextView? = null
    private var tvZoomSize: TextView? = null
    private var btnStopSession: ImageButton? = null
    private var btnCopyScreen: ImageButton? = null
    private var btnScriptAudit: ImageButton? = null
    private var btnDeadManSkull: ImageButton? = null
    private var btnOverflowMenu: ImageButton? = null
    private var layoutDeadManAlertBadge: View? = null
    private var remoteShortcutsScroll: View? = null
    private var btnShowExtraKeys: View? = null
    private var remoteShortcutsContainer: android.widget.LinearLayout? = null

    /** Title, subtitle and icon colors of the toolbar, set by [applyPreferences]. */
    private var lastChromeColors = intArrayOf(Color.WHITE, Color.LTGRAY, Color.WHITE)

    /** Profile of the attached SSH/Telnet session, null for local shells. */
    private var remoteProfile: RemoteProfile? = null

    private var lastDeadManTarget: String = ""
    private var lastDeadManCmd: String = ""
    private var currentCwd: String = "/storage/emulated/0"

    private var lastIsRunning: Boolean = false
    private var lastIsRoot: Boolean = false
    private var lastIsTermex: Boolean = false
    private var lastExitStatus: Int? = null
    private var lastIsLightChrome: Boolean = false

    private var terminalSession: TerminalSession? = null
    private var detachableClient: DetachableSessionClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentPid: Int = -1
    private lateinit var pinchScaleDetector: ScaleGestureDetector
    private var isPinching: Boolean = false

    private val runtimeHandler = Handler(Looper.getMainLooper())
    private val runtimeTickRunnable = object : Runnable {
        override fun run() {
            if (!isUiAlive) return
            if (terminalSession?.isRunning == true) {
                updateRunningSubtitle()
                runtimeHandler.postDelayed(this, 1000L)
            }
        }
    }

    companion object {
        const val EXTRA_PATH = "me.zhanghai.android.files.terminal.extra.PATH"
        const val EXTRA_CWD = "me.zhanghai.android.files.terminal.extra.CWD"
        const val EXTRA_COMMAND = "me.zhanghai.android.files.terminal.extra.COMMAND"
        const val EXTRA_FORCE_ROOT = "me.zhanghai.android.files.terminal.extra.FORCE_ROOT"
        const val EXTRA_FORCE_TERMEX = "me.zhanghai.android.files.terminal.extra.FORCE_TERMEX"
        const val EXTRA_ACTIVE_SCRIPT_PATH = "me.zhanghai.android.files.terminal.extra.ACTIVE_SCRIPT_PATH"
        const val EXTRA_REMOTE_PROFILE_ID = "me.zhanghai.android.files.terminal.extra.REMOTE_PROFILE_ID"
        private const val MENU_SESSION_BASE = 1000

        /** Remote sessions that ended while the terminal was not in front, not yet told. */
        private val remoteLostUnseen: MutableSet<TerminalSession> =
            java.util.Collections.newSetFromMap(java.util.WeakHashMap())

        /** Opens the terminal connected to a saved SSH/Telnet profile. */
        fun createRemoteIntent(context: Context, profileId: String): Intent =
            Intent(context, TerminalActivity::class.java).apply {
                putExtra(EXTRA_REMOTE_PROFILE_ID, profileId)
            }

        fun createIntent(
            context: Context,
            path: String? = null,
            command: String? = null,
            forceRoot: Boolean? = null,
            forceTermex: Boolean? = null,
            activeScriptPath: String? = null
        ): Intent {
            return Intent(context, TerminalActivity::class.java).apply {
                if (path != null) {
                    putExtra(EXTRA_PATH, path)
                    putExtra(EXTRA_CWD, path)
                }
                if (command != null) {
                    putExtra(EXTRA_COMMAND, command)
                }
                if (forceRoot != null) {
                    putExtra(EXTRA_FORCE_ROOT, forceRoot)
                }
                if (forceTermex != null) {
                    putExtra(EXTRA_FORCE_TERMEX, forceTermex)
                }
                if (activeScriptPath != null) {
                    putExtra(EXTRA_ACTIVE_SCRIPT_PATH, activeScriptPath)
                }
            }
        }
    }

    /**
     * Detachable [TerminalSessionClient] that coalesces rapid PTY output onto VSYNC display frames
     * and prevents leaking destroyed [TerminalActivity] instances across background reattachment.
     */
    private class DetachableSessionClient(
        private val appContext: Context,
        host: TerminalActivity
    ) : TerminalSessionClient {

        @Volatile
        private var hostActivity: TerminalActivity? = host
        private val frameScheduled = AtomicBoolean(false)
        private val mainHandler = Handler(Looper.getMainLooper())

        fun attach(activity: TerminalActivity) {
            hostActivity = activity
        }

        fun detach(activity: TerminalActivity) {
            if (hostActivity === activity) {
                hostActivity = null
            }
        }

        override fun onTextChanged(changedSession: TerminalSession) {
            if (frameScheduled.compareAndSet(false, true)) {
                val activity = hostActivity
                val targetView = activity?.takeIf { it.isUiAlive }?.terminalView
                if (targetView != null) {
                    targetView.postOnAnimation {
                        frameScheduled.set(false)
                        val live = hostActivity
                        if (live != null && live.isUiAlive) {
                            live.terminalView.onScreenUpdated()
                        }
                        ScriptExecutionManager.notifyOutputUpdated()
                    }
                } else {
                    mainHandler.post {
                        frameScheduled.set(false)
                        ScriptExecutionManager.notifyOutputUpdated()
                    }
                }
            }
        }

        // Output is no longer scanned for tracer tokens: the command guard talks to the app
        // through its own channel (CommandGuardServer) instead of printing into the terminal.

        override fun onTitleChanged(changedSession: TerminalSession) {
            val title = changedSession.title
            if (title.isNullOrEmpty()) return
            ScriptExecutionManager.notifyTitleChanged(title)
            val activity = hostActivity ?: return
            activity.runOnUiThread {
                if (!activity.isUiAlive) return@runOnUiThread
                if (changedSession.isRemote) {
                    // Remote shells usually report "user@host: ~/dir"; show it verbatim.
                    activity.tvTerminalBreadcrumb?.text = title
                    return@runOnUiThread
                }
                if (title.contains("/") || title.startsWith("~")) {
                    activity.updateCwdDisplay(title)
                }
            }
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            // An SSH/Telnet link that died while the user was elsewhere: on return, a clear choice
            // instead of a frozen "[Connection closed - press Enter]" screen.
            val host = hostActivity
            if (finishedSession.isRemote && (host == null || !host.isResumedNow)) {
                synchronized(remoteLostUnseen) { remoteLostUnseen.add(finishedSession) }
            }
            val activity = hostActivity
            if (activity != null) {
                activity.runOnUiThread {
                    if (!activity.isUiAlive) return@runOnUiThread
                    // Only the session on screen updates the HUD; others just end in the list.
                    if (activity.terminalSession !== finishedSession) return@runOnUiThread
                    activity.stopRuntimeTicker()
                    activity.toolbar.subtitle = activity.getString(
                        R.string.om_term_finished_code_format,
                        finishedSession.exitStatus
                    )
                    activity.updateHudState(
                        isRoot = ScriptExecutionManager.isRoot,
                        isTermex = ScriptExecutionManager.isTermex,
                        isRunning = false,
                        exitStatus = finishedSession.exitStatus
                    )
                }
            }
            ScriptExecutionManager.onSessionFinished(finishedSession, finishedSession.exitStatus, appContext)
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("terminal", text))
            val activity = hostActivity
            if (activity != null && activity.isUiAlive) {
                Toast.makeText(activity, R.string.terminal_copy, Toast.LENGTH_SHORT).show()
            }
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val activity = hostActivity
            if (activity != null && activity.isUiAlive) {
                activity.pasteFromClipboard()
            }
        }

        override fun onBell(session: TerminalSession) {}

        override fun onColorsChanged(session: TerminalSession) {
            val activity = hostActivity
            if (activity != null && activity.isUiAlive) {
                activity.terminalView.onScreenUpdated()
            }
        }

        override fun onTerminalCursorStateChange(state: Boolean) {}

        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
            ScriptExecutionManager.onSessionPid(session, pid)
            ScriptExecutionService.updateNotification(appContext)
            val activity = hostActivity ?: return
            activity.runOnUiThread {
                if (!activity.isUiAlive) return@runOnUiThread
                if (activity.terminalSession !== session) return@runOnUiThread
                activity.currentPid = pid
                activity.updateRunningSubtitle()
            }
        }

        override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK

        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_terminal)

        rootTerminal = findViewById(R.id.rootTerminal)
        appBarLayout = findViewById(R.id.appBarLayout)
        toolbar = findViewById(R.id.toolbar)
        terminalContainer = findViewById(R.id.terminalContainer)
        terminalView = findViewById(R.id.terminalView)
        extraKeysView = findViewById(R.id.extraKeysView)

        layoutTerminalHud = findViewById(R.id.layoutTerminalHud)
        viewHudBottomDivider = findViewById(R.id.viewHudBottomDivider)
        layoutSessionBadge = findViewById(R.id.layoutSessionBadge)
        viewSessionDotHalo = findViewById(R.id.viewSessionDotHalo)
        viewSessionDot = findViewById(R.id.viewSessionDot)
        tvSessionLabel = findViewById(R.id.tvSessionLabel)
        tvSessionChevron = findViewById(R.id.tvSessionChevron)
        layoutCwdChip = findViewById(R.id.layoutCwdChip)
        ivCwdFolder = findViewById(R.id.ivCwdFolder)
        tvTerminalBreadcrumb = findViewById(R.id.tvTerminalBreadcrumb)
        layoutZoomPill = findViewById(R.id.layoutZoomPill)
        viewZoomDivLeft = findViewById(R.id.viewZoomDivLeft)
        viewZoomDivRight = findViewById(R.id.viewZoomDivRight)
        btnZoomOut = findViewById(R.id.btnZoomOut)
        btnZoomIn = findViewById(R.id.btnZoomIn)
        tvZoomSize = findViewById(R.id.tvZoomSize)
        btnStopSession = findViewById(R.id.btnStopSession)
        btnCopyScreen = findViewById(R.id.btnCopyScreen)
        btnScriptAudit = findViewById(R.id.btnScriptAudit)
        btnDeadManSkull = findViewById(R.id.btnDeadManSkull)
        btnOverflowMenu = findViewById(R.id.btnOverflowMenu)
        layoutDeadManAlertBadge = findViewById(R.id.layoutDeadManAlertBadge)
        remoteShortcutsScroll = findViewById(R.id.remoteShortcutsScroll)
        btnShowExtraKeys = findViewById(R.id.btnShowExtraKeys)
        remoteShortcutsContainer = findViewById(R.id.remoteShortcutsContainer)
        RemotePrompter.attach(this)

        setupEdgeToEdgeInsets()
        setupControls()
        setupPinchZoomDetector()
        setupExtraKeys()
        applyPreferences()
        startTerminalSession()
    }

    /** Whether this screen is in front (read from the session thread's callbacks). */
    @Volatile
    var isResumedNow = false
        private set

    override fun onResume() {
        super.onResume()
        isResumedNow = true
        showRemoteLostIfNeeded()
        RemotePrompter.attach(this)
        me.zhanghai.android.files.terminal.tracer.GuardPrompter.attach(this)
        applyPreferences()
        if (terminalSession?.isRunning == true) {
            startRuntimeTicker()
        }
    }

    override fun onPause() {
        super.onPause()
        isResumedNow = false
        me.zhanghai.android.files.terminal.tracer.GuardPrompter.detach(this)
        if (me.zhanghai.android.files.BuildConfig.DEBUG && terminalSession?.isRemote == true) {
            android.util.Log.i(
                "OMEcho",
                "rtt=${terminalView.predictiveEchoRttMs}ms shownConfirmed=${terminalView.predictiveEchoShownCount}"
            )
        }
        stopRuntimeTicker()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra(EXTRA_REMOTE_PROFILE_ID)) {
            startTerminalSession()
            return
        }
        val cmd = intent.getStringExtra(EXTRA_COMMAND)
        if (cmd != null && cmd != ScriptExecutionManager.activeCommand) {
            startTerminalSession()
        }
    }

    private fun setupEdgeToEdgeInsets() {
        val topBarMask = WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
        ViewCompat.setOnApplyWindowInsetsListener(appBarLayout) { view, insets ->
            val bars = insets.getInsets(topBarMask)
            view.setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }

        val bottomBarMask = WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime()
        ViewCompat.setOnApplyWindowInsetsListener(rootTerminal) { view, insets ->
            val bars = insets.getInsets(bottomBarMask)
            view.setPadding(0, 0, 0, bars.bottom)
            insets
        }
    }

    private fun setupControls() {
        toolbar.setNavigationOnClickListener {
            finish()
        }

        layoutSessionBadge?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            showSessionQuickMenu(it)
        }

        layoutCwdChip?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val profile = remoteProfile
            if (profile != null && profile.protocol == RemoteProtocol.SSH) {
                // VPS: its files in the explorer, next to the phone's.
                openRemoteFiles(profile)
            } else {
                copyCwdToClipboard()
            }
        }
        layoutCwdChip?.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            copyCwdToClipboard()
            true
        }

        btnStopSession?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (remoteProfile != null) {
                // VPS: the button closes the connection and the screen, no signal menu.
                closeRemoteSession()
            } else {
                showStopSignalMenu(it)
            }
        }

        btnScriptAudit?.setOnClickListener {
            if (remoteProfile != null) showConnectionDetails() else showScriptAuditDialog()
        }

        btnCopyScreen?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            copyTerminalTranscript()
        }

        // The audit sheet shows what was blocked, why, and the protection switches.
        btnDeadManSkull?.setOnClickListener { showScriptAuditDialog() }
        layoutDeadManAlertBadge?.setOnClickListener { showScriptAuditDialog() }

        btnOverflowMenu?.setOnClickListener {
            showOverflowMenu(it)
        }

        btnZoomOut?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            changeFontSizeBy(-1)
        }

        btnZoomIn?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            changeFontSizeBy(+1)
        }

        tvZoomSize?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            TerminalSettings.setFontSize(this, TerminalSettings.DEFAULT_FONT_SIZE_SP)
            applyPreferences()
            Toast.makeText(
                this,
                getString(R.string.om_term_zoom_reset_format, TerminalSettings.DEFAULT_FONT_SIZE_SP),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun showScriptAuditDialog() {
        val activeScript = ScriptExecutionManager.activeScriptPath
            ?: intent.getStringExtra(EXTRA_ACTIVE_SCRIPT_PATH)
            ?: intent.getStringExtra(EXTRA_PATH)
        me.zhanghai.android.files.terminal.tracer.ScriptAuditDialogFragment.show(this, activeScript)
    }

    /**
     * A protection blocked something: show the skull badge (tap = details) without interrupting
     * the session with a modal dialog, which used to pop up again for every blocked command.
     */
    fun markDeadManBlocked(target: String, cmd: String) {
        lastDeadManTarget = target
        lastDeadManCmd = cmd
        if (!isUiAlive) return
        btnDeadManSkull?.visibility = View.VISIBLE
        layoutDeadManAlertBadge?.visibility = View.VISIBLE
    }

    fun showDeadManSkullAlert(target: String, cmd: String) {
        lastDeadManTarget = target
        lastDeadManCmd = cmd
        runOnUiThread {
            if (!isUiAlive) return@runOnUiThread
            btnDeadManSkull?.visibility = View.VISIBLE
            layoutDeadManAlertBadge?.visibility = View.VISIBLE
            DeadManProtectionManager.showDeadManProtectionDialog(this, target, cmd)
        }
    }

    private fun setupPinchZoomDetector() {
        pinchScaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                private var accumulatedFactor = 1.0f

                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    isPinching = true
                    accumulatedFactor = 1.0f
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    accumulatedFactor *= detector.scaleFactor
                    if (accumulatedFactor < 0.91f || accumulatedFactor > 1.09f) {
                        val delta = if (accumulatedFactor > 1.0f) 1 else -1
                        changeFontSizeBy(delta)
                        accumulatedFactor = 1.0f
                    }
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    isPinching = false
                    accumulatedFactor = 1.0f
                }
            }
        )
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.pointerCount >= 2 || isPinching) {
            pinchScaleDetector.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                isPinching = false
            }
            if (ev.pointerCount >= 2) {
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun changeFontSizeBy(delta: Int) {
        val currentSize = TerminalSettings.getFontSize(this)
        val newSize = (currentSize + delta).coerceIn(
            TerminalSettings.MIN_FONT_SIZE_SP,
            TerminalSettings.MAX_FONT_SIZE_SP
        )
        if (newSize != currentSize) {
            TerminalSettings.setFontSize(this, newSize)
            applyPreferences()
        }
    }

    private fun setupExtraKeys() {
        extraKeysView.onKeyString = { keyStr ->
            if (terminalSession?.isRunning == false) {
                if (keyStr == "\r" || keyStr == "\n") {
                    finish()
                }
            } else {
                terminalView.scrollToBottomForInput()
                terminalSession?.write(keyStr)
            }
        }
        extraKeysView.onToggleKeyboardClick = {
            toggleSoftKeyboard()
        }
        extraKeysView.onModifierLockedToast = { modName ->
            Toast.makeText(
                this,
                getString(R.string.om_term_modifier_locked_format, modName),
                Toast.LENGTH_SHORT
            ).show()
        }
        extraKeysView.deckMode = TerminalSettings.getExtraKeysMode(this)
        extraKeysView.onDeckModeSelected = { mode ->
            TerminalSettings.setExtraKeysMode(this, mode)
            updateExtraKeysHandle()
        }
        btnShowExtraKeys?.setOnClickListener { extraKeysView.showDeckMenu(it) }
        updateExtraKeysHandle()
        setupComposeBar()
    }

    /**
     * Local writing bar: the line is edited on the phone (instant, with the keyboard's suggestions)
     * and sent in one go, so a slow link never delays what you see while typing.
     */
    private fun setupComposeBar() {
        val bar = findViewById<View>(R.id.composeBar) ?: return
        val input = findViewById<android.widget.EditText>(R.id.composeInput)
        val send = findViewById<View>(R.id.composeSend)
        fun apply(enabled: Boolean, focus: Boolean) {
            bar.isVisible = enabled
            findViewById<View>(R.id.composeDivider)?.isVisible = enabled
            extraKeysView.composeBarEnabled = enabled
            if (enabled && focus) {
                input.requestFocus()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            } else if (!enabled) {
                terminalView.requestFocus()
            }
        }
        apply(TerminalSettings.isComposeBar(this), focus = false)
        extraKeysView.onComposeBarToggled = { enabled ->
            TerminalSettings.setComposeBar(this, enabled)
            apply(enabled, focus = true)
        }
        val sendLine = {
            val session = terminalSession
            if (session != null && session.isRunning) {
                val text = input.text?.toString().orEmpty()
                terminalView.scrollToBottomForInput()
                if (text.isNotEmpty()) {
                    // paste() honors bracketed paste, so multi-line text reaches Claude/vim as one block.
                    session.emulator?.paste(text) ?: session.write(text)
                }
                session.write("\r")
                input.text?.clear()
            }
        }
        send.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            sendLine()
        }
        input.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND || isEnter) {
                sendLine()
                true
            } else {
                false
            }
        }
    }

    /** With the key deck hidden, a small ⋮ over the terminal brings it back. */
    private fun updateExtraKeysHandle() {
        btnShowExtraKeys?.isVisible = extraKeysView.deckMode == TerminalExtraKeysView.DeckMode.HIDDEN
    }

    private fun toggleSoftKeyboard() {
        terminalView.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val imeVisible = ViewCompat.getRootWindowInsets(terminalView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (imeVisible) {
            imm.hideSoftInputFromWindow(terminalView.windowToken, 0)
        } else {
            imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun dpF(v: Float): Float = v * resources.displayMetrics.density

    private fun applyPreferences() {
        val fontSizeSp = TerminalSettings.getFontSize(this)
        val fontSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            fontSizeSp.toFloat(),
            resources.displayMetrics
        ).roundToInt().coerceAtLeast(10)
        terminalView.setTextSize(fontSizePx)
        terminalView.setTypeface(Typeface.MONOSPACE)
        tvZoomSize?.text = "${fontSizeSp}sp"

        val appPalette = OpenManagerPalette.from(this)
        val keepBlack = TerminalSettings.isKeepBlackTheme(this)
        val isLightChrome = !keepBlack && !appPalette.isDark
        // "Always black" under the light theme: the explorer's dark colors, not a glaring light bar.
        val darkUnderLightTheme = keepBlack && !appPalette.isDark
        lastIsLightChrome = isLightChrome

        // The explorer's toolbar colors for the toolbar and the session strip, one flat surface
        // (it was a GitHub-style navy with outlined capsules, a glowing dot and colored icons).
        val chromeBg = if (darkUnderLightTheme) 0xFF151515.toInt() else appPalette.toolbarBg
        val titleText = if (darkUnderLightTheme) 0xFFEDEDED.toInt() else appPalette.textPrimary
        val subtitleText = if (darkUnderLightTheme) 0xFF9E9E9E.toInt() else appPalette.textSecondary
        val iconTint = if (darkUnderLightTheme) 0xFFDCDCDC.toInt() else appPalette.iconTint
        val dividerColor = if (darkUnderLightTheme) 0xFF2E2E2E.toInt() else appPalette.dividerLineColor
        val terminalBg = if (keepBlack || appPalette.isAmoled || !isLightChrome) Color.BLACK else 0xFF0D1117.toInt()
        lastChromeColors = intArrayOf(titleText, subtitleText, iconTint)

        rootTerminal.setBackgroundColor(terminalBg)
        appBarLayout.setBackgroundColor(chromeBg)
        toolbar.setBackgroundColor(chromeBg)
        toolbar.setTitleTextColor(titleText)
        toolbar.setSubtitleTextColor(subtitleText)
        toolbar.navigationIcon?.setTint(iconTint)

        val tint = ColorStateList.valueOf(iconTint)
        btnCopyScreen?.imageTintList = tint
        btnOverflowMenu?.imageTintList = tint
        btnScriptAudit?.imageTintList = tint
        btnStopSession?.imageTintList = tint

        layoutTerminalHud?.setBackgroundColor(chromeBg)
        viewHudBottomDivider?.setBackgroundColor(dividerColor)

        // Working directory and font size: plain text with a ripple, no boxes.
        layoutCwdChip?.background = flatRipple(isLightChrome)
        ivCwdFolder?.imageTintList = ColorStateList.valueOf(subtitleText)
        tvTerminalBreadcrumb?.setTextColor(subtitleText)
        layoutZoomPill?.background = null
        viewZoomDivLeft?.setBackgroundColor(dividerColor)
        viewZoomDivRight?.setBackgroundColor(dividerColor)
        btnZoomOut?.setTextColor(iconTint)
        btnZoomIn?.setTextColor(iconTint)
        tvZoomSize?.setTextColor(subtitleText)

        // The terminal fills its area: no colored frame around it.
        terminalContainer.setBackgroundColor(terminalBg)
        terminalView.setBackgroundColor(terminalBg)

        // The key bar takes the explorer's bottom-bar colors (gray / black / light), like the
        // editor's symbol bar; the navigation bar under it continues it.
        extraKeysView.applyTheme(appPalette, keepBlack)
        applyBottomStripColors()
        @Suppress("DEPRECATION")
        window.statusBarColor = chromeBg
        @Suppress("DEPRECATION")
        window.navigationBarColor = extraKeysView.barColor
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = isLightChrome
            isAppearanceLightNavigationBars = extraKeysView.palette.isLightChrome
        }

        updatePredictiveEcho()
        renderSessionBadge()
    }

    private fun renderSessionBadge() {
        val spec = TerminalUiHelper.computeSessionBadge(
            isRunning = lastIsRunning,
            isRoot = lastIsRoot,
            isTermex = lastIsTermex,
            exitStatus = lastExitStatus,
            isLightChrome = lastIsLightChrome,
            remoteLabel = remoteProfile?.let { if (it.protocol == RemoteProtocol.SSH) "SSH" else "TELNET" }
        )
        // A small status dot and the session name, flat like the rest of the toolbar.
        viewSessionDotHalo?.background = null
        viewSessionDot?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(spec.dotColor)
        }
        val (titleText, subtitleText, _) = lastChromeColors.toList()
        tvSessionLabel?.text = spec.label
        tvSessionLabel?.setTextColor(titleText)
        tvSessionChevron?.setTextColor(subtitleText)
        layoutSessionBadge?.background = flatRipple(lastIsLightChrome)
    }

    /** A ripple on a transparent background: the flat touch feedback of the app's bars. */
    private fun flatRipple(light: Boolean): RippleDrawable {
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(6f)
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(if (light) 0x220F172A else 0x38FFFFFF), null, mask)
    }

    /**
     * The shortcuts strip and the writing bar sit on the key bar's color with flat keys and a
     * thin divider, like the editor's symbol bar (they were rounded outlined boxes).
     */
    private fun applyBottomStripColors() {
        val deck = extraKeysView.palette
        remoteShortcutsScroll?.setBackgroundColor(deck.deckBg)
        remoteShortcutsContainer?.let { container ->
            for (i in 0 until container.childCount) {
                when (val child = container.getChildAt(i)) {
                    is TextView -> {
                        child.setTextColor(deck.keyText)
                        child.background = flatRipple(deck.isLightChrome)
                    }
                    else -> child.setBackgroundColor(deck.divider)
                }
            }
        }
        findViewById<View>(R.id.composeBar)?.setBackgroundColor(deck.deckBg)
        findViewById<View>(R.id.composeDivider)?.setBackgroundColor(deck.divider)
        findViewById<android.widget.EditText>(R.id.composeInput)?.apply {
            setTextColor(deck.keyText)
            setHintTextColor(androidx.core.graphics.ColorUtils.setAlphaComponent(deck.keyText, 0x88))
        }
        (findViewById<android.widget.ImageButton>(R.id.composeSend))?.imageTintList =
            ColorStateList.valueOf(deck.accentColor)
    }

    private fun updateCwdDisplay(rawPath: String?) {
        remoteProfile?.let {
            tvTerminalBreadcrumb?.text = remoteBreadcrumb(it)
            return
        }
        if (!rawPath.isNullOrBlank()) {
            val normalized = rawPath.trim()
            val cleanCwd = if (File(normalized).isFile) {
                File(normalized).parent ?: normalized
            } else if (!normalized.startsWith("~") && normalized.contains('/')) {
                val leaf = normalized.substringAfterLast('/')
                if (leaf.contains('.') && !leaf.startsWith('.')) {
                    normalized.substringBeforeLast('/').ifEmpty { "/" }
                } else {
                    normalized
                }
            } else {
                normalized
            }
            currentCwd = cleanCwd
        }
        tvTerminalBreadcrumb?.text = TerminalUiHelper.formatBreadcrumbPath(currentCwd)
    }

    private fun updateRunningSubtitle() {
        if (terminalSession?.isRunning != true) return
        val startMs = ScriptExecutionManager.startTimeMillis
        val elapsedSec = if (startMs > 0L) ((System.currentTimeMillis() - startMs) / 1000L).coerceAtLeast(0L) else 0L
        val elapsedStr = TerminalUiHelper.formatElapsedSeconds(elapsedSec)
        toolbar.subtitle = if (currentPid > 0) {
            getString(R.string.om_term_running_pid_timer_format, currentPid, elapsedStr)
        } else {
            getString(R.string.om_term_running_timer_format, elapsedStr)
        }
    }

    private fun startRuntimeTicker() {
        runtimeHandler.removeCallbacks(runtimeTickRunnable)
        updateRunningSubtitle()
        runtimeHandler.postDelayed(runtimeTickRunnable, 1000L)
    }

    private fun stopRuntimeTicker() {
        runtimeHandler.removeCallbacks(runtimeTickRunnable)
    }

    private fun updateHudState(
        isRoot: Boolean,
        isTermex: Boolean,
        isRunning: Boolean,
        exitStatus: Int? = null,
        cwd: String? = null
    ) {
        val applyUi = {
            if (isUiAlive) {
                lastIsRoot = isRoot
                lastIsTermex = isTermex
                lastIsRunning = isRunning
                lastExitStatus = exitStatus
                btnStopSession?.isVisible = isRunning
                renderSessionBadge()
                if (cwd != null) {
                    updateCwdDisplay(cwd)
                }
                if (isRunning) {
                    startRuntimeTicker()
                } else {
                    stopRuntimeTicker()
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyUi()
        } else {
            runOnUiThread(applyUi)
        }
    }

    private fun startTerminalSession(
        forceRootOverride: Boolean? = null,
        forceTermexOverride: Boolean? = null,
        forceLocal: Boolean = false
    ) {
        val initialCommand = if (forceRootOverride == null && forceTermexOverride == null) {
            intent.getStringExtra(EXTRA_COMMAND)
        } else {
            null
        }

        val sessionClient = DetachableSessionClient(applicationContext, this)
        detachableClient?.detach(this)
        detachableClient = sessionClient

        val viewClient = object : TerminalViewClient {
            override fun onScale(scale: Float): Float {
                if (scale < 0.90f || scale > 1.10f) {
                    val delta = if (scale > 1.0f) 1 else -1
                    changeFontSizeBy(delta)
                    return 1.0f
                }
                return scale
            }

            override fun onSingleTapUp(e: MotionEvent?) {
                terminalView.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
            }

            override fun shouldBackButtonBeMappedToEscape(): Boolean = false
            // Remote shells echo every byte themselves: commit each key immediately instead of
            // letting the keyboard hold a composing word (the "typing lag" of IMEs like Samsung's).
            override fun shouldEnforceCharBasedInput(): Boolean =
                terminalSession?.isRemote == true || TerminalSettings.isInstantTyping(this@TerminalActivity)
            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
            override fun isTerminalViewSelected(): Boolean = true
            override fun copyModeChanged(copyMode: Boolean) {}

            override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
                if (session != null && !session.isRunning) {
                    if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                        finish()
                        return true
                    }
                }
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    changeFontSizeBy(+1)
                    return true
                }
                if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    changeFontSizeBy(-1)
                    return true
                }
                return false
            }

            override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean {
                return keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
            }

            override fun onLongPress(event: MotionEvent?): Boolean = false
            override fun readControlKey(): Boolean = extraKeysView.consumeCtrl()
            override fun readAltKey(): Boolean = extraKeysView.consumeAlt()
            override fun readShiftKey(): Boolean = false
            override fun readFnKey(): Boolean = false
            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
            override fun onEmulatorSet() {}
            override fun logError(tag: String?, message: String?) {}
            override fun logWarn(tag: String?, message: String?) {}
            override fun logInfo(tag: String?, message: String?) {}
            override fun logDebug(tag: String?, message: String?) {}
            override fun logVerbose(tag: String?, message: String?) {}
            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
            override fun logStackTrace(tag: String?, e: Exception?) {}
        }

        val remoteProfileId = if (forceRootOverride == null && forceTermexOverride == null &&
            !forceLocal
        ) {
            intent.getStringExtra(EXTRA_REMOTE_PROFILE_ID)
        } else {
            null
        }
        // 1. Reattach to a live session (several can run at once) unless a new one is requested.
        if (forceRootOverride == null && forceTermexOverride == null && initialCommand == null) {
            val existing = if (remoteProfileId != null) {
                ScriptExecutionManager.sessions.lastOrNull {
                    it.isRunning && (it.session.remoteTransport as? ProfileTransport)?.profile?.id == remoteProfileId
                }
            } else {
                ScriptExecutionManager.focusedEntry?.takeIf { it.isRunning }
                    ?: ScriptExecutionManager.sessions.lastOrNull { it.isRunning }
            }
            if (existing != null) {
                terminalView.setTerminalViewClient(viewClient)
                showEntry(existing, sessionClient)
                return
            }
        }

        // Switching environment (system / root / Termex) replaces an idle interactive shell;
        // scripts, commands and SSH sessions are never killed by starting another session.
        if (forceRootOverride != null || forceTermexOverride != null) {
            ScriptExecutionManager.focusedEntry
                ?.takeIf { it.command == null && !it.session.isRemote && it.session === terminalSession }
                ?.let { ScriptExecutionManager.terminate(this, it) }
        }

        if (remoteProfileId != null) {
            val profile = RemoteProfileStore.get(this, remoteProfileId)
            if (profile == null) {
                Toast.makeText(this, R.string.om_remote_profile_missing, Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            startRemoteSession(profile, sessionClient, viewClient)
            return
        }
        setRemoteProfile(null)
        // Switching to a local shell: a later recreation must not reopen the old connection.
        intent.removeExtra(EXTRA_REMOTE_PROFILE_ID)

        // Only a root shell can harm the system; without root Android already refuses.
        val rootRequested = forceRootOverride
            ?: (if (intent.hasExtra(EXTRA_FORCE_ROOT)) intent.getBooleanExtra(EXTRA_FORCE_ROOT, false) else null)
            ?: TerminalSettings.isRootSession(this)
        val willBeRoot = rootRequested && TerminalExpansionManager.isRootAvailable()
        if (!initialCommand.isNullOrEmpty() && willBeRoot &&
            DeadManProtectionManager.isDfrootProtectionActive(this) &&
            DeadManProtectionManager.containsDangerousSystemCommand(initialCommand)
        ) {
            DeadManProtectionManager.showDirtyFlagProtectionDialog(this) {
                finish()
            }
            return
        }

        val overridePath = intent.getStringExtra(EXTRA_PATH)
            ?: intent.getStringExtra(EXTRA_CWD)
            ?: intent.data?.path

        val forceRoot = forceRootOverride
            ?: if (intent.hasExtra(EXTRA_FORCE_ROOT)) intent.getBooleanExtra(EXTRA_FORCE_ROOT, false) else null
        val forceTermex = forceTermexOverride
            ?: if (intent.hasExtra(EXTRA_FORCE_TERMEX)) intent.getBooleanExtra(EXTRA_FORCE_TERMEX, false) else null

        val activeScript = intent.getStringExtra(EXTRA_ACTIVE_SCRIPT_PATH)
            ?: intent.getStringExtra(EXTRA_PATH)
            ?: intent.getStringExtra(EXTRA_CWD)

        val config = TerminalExpansionManager.prepareLaunchConfig(
            this,
            forceRoot = forceRoot,
            forceTermex = forceTermex,
            overrideCwd = overridePath,
            initialCommand = initialCommand,
            activeScriptPath = activeScript
        )

        val session = TerminalSession(
            config.executable,
            config.cwd,
            config.args,
            config.env,
            PerformanceModeManager.currentBudget.terminalTranscriptRows,
            sessionClient
        )
        terminalSession = session

        val scriptName = if (initialCommand != null) {
            val lastPathPart = initialCommand.split(" ", ";").lastOrNull { it.contains("/") } ?: initialCommand
            val fileName = File(lastPathPart.trim().trim('"', '\'', ')', '(')).name
            if (fileName.isNotEmpty()) fileName else initialCommand
        } else {
            val dirName = File(config.cwd).name.ifEmpty { "/" }
            "Terminal ($dirName)"
        }

        ScriptExecutionManager.startExecution(
            context = this,
            session = session,
            scriptName = scriptName,
            path = activeScript ?: overridePath,
            command = initialCommand,
            root = config.isRoot,
            termex = config.isTermex
        )
        currentPid = session.pid

        terminalView.setTerminalViewClient(viewClient)
        terminalView.attachSession(session)
        updatePredictiveEcho()

        toolbar.title = scriptName
        updateHudState(
            isRoot = config.isRoot,
            isTermex = config.isTermex,
            isRunning = true,
            cwd = config.cwd
        )
    }

    /**
     * Switches the profile from password to key login without leaving the app: generates an
     * Ed25519 key if the profile has none, types the authorized_keys command into this logged-in
     * session and stores the key in the profile (the saved password stays as a fallback).
     */
    private fun confirmInstallKeyOnServer() {
        val profile = remoteProfile?.let { RemoteProfileStore.get(this, it.id) ?: it } ?: return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.om_key_use_key_menu)
            .setMessage(getString(R.string.om_key_install_explain, profile.endpoint))
            .setPositiveButton(R.string.om_key_install_action) { _, _ -> installKeyOnServer(profile) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun installKeyOnServer(profile: RemoteProfile) {
        val session = terminalSession?.takeIf { it.isRunning && it.isRemote } ?: return
        val comment = me.zhanghai.android.files.terminal.remote.SshKeys.sanitizeComment(
            "openmanager@" + android.os.Build.MODEL.lowercase()
        )
        lifecycleScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                runCatching {
                    val existing = profile.privateKey?.takeIf { it.isNotBlank() }
                    if (existing != null) {
                        existing to me.zhanghai.android.files.terminal.remote.SshKeys.publicKeyLine(
                            existing, profile.keyPassphrase, comment
                        )
                    } else {
                        me.zhanghai.android.files.terminal.remote.SshKeys.generateEd25519(comment)
                            .let { it.privateKeyOpenSsh to it.publicKeyLine }
                    }
                }
            }
            if (!isUiAlive) return@launch
            val (privateKey, publicLine) = result.getOrElse {
                Toast.makeText(this@TerminalActivity, R.string.om_remote_error_key_format, Toast.LENGTH_LONG).show()
                return@launch
            }
            terminalView.scrollToBottomForInput()
            session.write(me.zhanghai.android.files.terminal.remote.SshKeys.installCommand(publicLine) + "\r")
            val updated = profile.copy(
                authType = me.zhanghai.android.files.terminal.remote.RemoteAuthType.PRIVATE_KEY,
                privateKey = privateKey
            )
            RemoteProfileStore.save(this@TerminalActivity, updated)
            remoteProfile = updated
            Toast.makeText(this@TerminalActivity, R.string.om_key_installed_toast, Toast.LENGTH_LONG).show()
        }
    }

    /** Ends the session on screen and shows another open one, or leaves the terminal. */
    private fun closeCurrentSession() {
        val entry = terminalSession?.let { ScriptExecutionManager.entryFor(it) }
        if (entry != null) {
            if (entry.isRunning) ScriptExecutionManager.terminate(this, entry) else ScriptExecutionManager.dismiss(entry)
        }
        val next = ScriptExecutionManager.sessions.lastOrNull { it.isRunning } ?: ScriptExecutionManager.sessions.lastOrNull()
        if (next != null) showEntry(next) else finish()
    }

    /** Puts an existing session on screen (switching sessions or reopening the terminal). */
    private fun showEntry(entry: ScriptExecutionManager.Entry, client: DetachableSessionClient? = null) {
        val sessionClient = client ?: DetachableSessionClient(applicationContext, this).also {
            detachableClient?.detach(this)
            detachableClient = it
        }
        ScriptExecutionManager.focus(entry)
        val session = entry.session
        setRemoteProfile((session.remoteTransport as? ProfileTransport)?.profile)
        session.updateTerminalSessionClient(sessionClient)
        terminalSession = session
        currentPid = entry.pid
        terminalView.attachSession(session)
        updatePredictiveEcho()
        terminalView.onScreenUpdated()
        toolbar.title = entry.name
        updateHudState(
            isRoot = entry.isRoot,
            isTermex = entry.isTermex,
            isRunning = session.isRunning,
            exitStatus = if (session.isRunning) null else session.exitStatus,
            cwd = entry.scriptPath ?: TerminalSettings.getStartPath(this)
        )
        if (!session.isRunning) {
            toolbar.subtitle = getString(R.string.om_term_finished_code_format, session.exitStatus)
        }
    }

    private fun startRemoteSession(
        profile: RemoteProfile,
        sessionClient: DetachableSessionClient,
        viewClient: TerminalViewClient
    ) {
        val transport = ProfileTransport.create(this, profile)
        val session = TerminalSession(
            transport,
            PerformanceModeManager.currentBudget.terminalTranscriptRows,
            sessionClient
        )
        terminalSession = session
        setRemoteProfile(profile)
        ScriptExecutionManager.startExecution(
            context = this,
            session = session,
            scriptName = profile.displayName,
            path = null,
            command = null,
            root = false,
            termex = false
        )
        currentPid = -1
        terminalView.setTerminalViewClient(viewClient)
        terminalView.attachSession(session)
        updatePredictiveEcho()
        terminalView.requestFocus()
        toolbar.title = profile.displayName
        tvTerminalBreadcrumb?.text = profile.endpoint
        updateHudState(isRoot = false, isTermex = false, isRunning = true)
    }

    private fun updatePredictiveEcho() {
        val enabled = terminalSession?.isRemote == true && TerminalSettings.isPredictiveEcho(this)
        terminalView.setPredictiveEchoEnabled(enabled)
        // The IME input type depends on the setting: rebuild the input connection.
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.restartInput(terminalView)
    }

    /** Shows the profile's shortcut chips and switches the HUD to remote mode. */
    private fun setRemoteProfile(profile: RemoteProfile?) {
        remoteProfile = profile
        applyToolbarMode()
        val container = remoteShortcutsContainer ?: return
        container.removeAllViews()
        val shortcuts = profile?.shortcuts.orEmpty()
        remoteShortcutsScroll?.isVisible = profile != null && shortcuts.isNotEmpty()
        if (profile == null) {
            renderSessionBadge()
            return
        }
        tvTerminalBreadcrumb?.text = remoteBreadcrumb(profile)
        for ((index, shortcut) in shortcuts.withIndex()) {
            if (index > 0) {
                container.addView(
                    View(this),
                    android.widget.LinearLayout.LayoutParams(dp(1), dp(18))
                )
            }
            val key = TextView(this).apply {
                text = shortcut.label
                textSize = 13f
                typeface = Typeface.MONOSPACE
                maxLines = 1
                gravity = android.view.Gravity.CENTER
                setPadding(dp(16), 0, dp(16), 0)
                contentDescription = shortcut.command
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    sendRemoteShortcut(shortcut.command)
                }
                setOnLongClickListener {
                    Toast.makeText(this@TerminalActivity, shortcut.command, Toast.LENGTH_SHORT).show()
                    true
                }
            }
            container.addView(
                key,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        applyBottomStripColors()
        renderSessionBadge()
    }

    /**
     * Local shells: stop-signal menu and script audit. VPS: close button, connection details.
     * Copy lives in the ⋮ menu for both, it is not needed one tap away.
     */
    private fun applyToolbarMode() {
        val remote = remoteProfile != null
        btnCopyScreen?.isVisible = false
        btnStopSession?.setImageResource(if (remote) R.drawable.close_icon_white_24dp else R.drawable.ic_terminal_stop_24dp)
        btnStopSession?.imageTintList = ColorStateList.valueOf(lastChromeColors[2])
        btnStopSession?.contentDescription = getString(if (remote) R.string.om_conn_disconnect else R.string.om_term_stop_signal)
        btnScriptAudit?.setImageResource(if (remote) R.drawable.lock_icon_white_24dp else R.drawable.shield_icon_white_24dp)
        btnScriptAudit?.contentDescription = getString(if (remote) R.string.om_conn_title else R.string.script_audit_title)
    }

    private fun closeRemoteSession() {
        // Close this connection; other sessions stay open and the next one is shown.
        closeCurrentSession()
    }

    /** Security and algorithms of the live SSH/Telnet link (replaces the script audit for VPS). */
    private fun showConnectionDetails() {
        val transport = terminalSession?.remoteTransport as? ProfileTransport ?: return
        val rows = transport.connectionDetails(this).toMutableList()
        val rtt = terminalView.predictiveEchoRttMs
        if (rtt > 0) rows += getString(R.string.om_conn_latency) to "$rtt ms"
        val status = getString(
            when {
                terminalSession?.isRunning != true -> R.string.om_conn_status_closed
                transport.isSecure -> R.string.om_conn_status_secure
                transport.profile.protocol == RemoteProtocol.TELNET -> R.string.om_conn_status_plaintext
                else -> R.string.om_conn_status_connecting
            }
        )
        val text = android.text.SpannableStringBuilder().apply {
            append(status).append("\n\n")
            for ((label, value) in rows) {
                val start = length
                append(label)
                setSpan(android.text.style.StyleSpan(Typeface.BOLD), start, length, 0)
                append("\n").append(value).append("\n\n")
            }
        }
        val fingerprint = rows.firstOrNull { it.first == getString(R.string.om_conn_fingerprint) }?.second
        val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setIcon(if (transport.isSecure) R.drawable.lock_icon_white_24dp else R.drawable.ic_conn_telnet)
            .setTitle(R.string.om_conn_title)
            .setMessage(text.trim())
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(R.string.om_conn_disconnect) { _, _ -> closeRemoteSession() }
        if (fingerprint != null) {
            builder.setNeutralButton(R.string.om_conn_copy_fingerprint) { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.setPrimaryClip(ClipData.newPlainText("SSH fingerprint", fingerprint))
                Toast.makeText(this, R.string.om_conn_fingerprint_copied, Toast.LENGTH_SHORT).show()
            }
        }
        builder.show()
    }

    /**
     * What the remote shell last reported as its title (usually "user@host: ~/dir"), so reopening
     * the screen from the notification shows the same location instead of the bare endpoint.
     */
    private fun remoteBreadcrumb(profile: RemoteProfile): String =
        terminalSession?.takeIf { it.isRemote }?.title?.takeIf { it.isNotBlank() } ?: profile.endpoint

    private fun sendRemoteShortcut(command: String) {
        val session = terminalSession ?: return
        if (!session.isRunning) return
        // Bracketed paste would insert it without running; type it like the user would.
        session.write(command + "\r")
        terminalView.requestFocus()
    }

    private fun showStopSignalMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.apply {
            add(Menu.NONE, 1, 1, R.string.om_term_send_sigint)
            add(Menu.NONE, 2, 2, R.string.om_term_force_kill)
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    terminalSession?.write("\u0003")
                    true
                }
                2 -> {
                    ScriptExecutionManager.terminateShell(this)
                    updateHudState(
                        isRoot = ScriptExecutionManager.isRoot,
                        isTermex = ScriptExecutionManager.isTermex,
                        isRunning = false,
                        exitStatus = 137
                    )
                    toolbar.subtitle = getString(R.string.om_term_finished_code_format, 137)
                    Toast.makeText(this, R.string.om_term_process_terminated, Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showSessionQuickMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.apply {
            if (terminalSession?.isRunning == true) {
                add(Menu.NONE, 1, 1, R.string.om_term_send_sigint)
                add(Menu.NONE, 2, 2, R.string.om_term_force_kill)
            }
            add(Menu.NONE, 3, 3, R.string.om_term_switch_system)
            if (TerminalExpansionManager.isRootAvailable()) {
                add(Menu.NONE, 4, 4, R.string.om_term_switch_root)
            }
            add(Menu.NONE, 5, 5, R.string.om_term_switch_termex)
            add(Menu.NONE, 6, 6, R.string.om_term_new_clean_session)
            remoteProfile?.let { add(Menu.NONE, 8, 7, getString(R.string.om_remote_reconnect, it.displayName)) }
            if (remoteProfile?.protocol == RemoteProtocol.SSH && terminalSession?.isRunning == true) {
                add(Menu.NONE, 10, 7, R.string.om_key_use_key_menu)
            }
            add(Menu.NONE, 7, 8, R.string.om_remote_menu_ssh_telnet)
            // Every open session (scripts, shells, SSH) with its state: tap to switch.
            val sessions = ScriptExecutionManager.sessions
            if (sessions.size > 1 || sessions.any { it.session !== terminalSession }) {
                sessions.forEachIndexed { index, entry ->
                    val state = if (entry.isRunning) "●" else "○ ${entry.session.exitStatus}"
                    val current = if (entry.session === terminalSession) "  ✓" else ""
                    add(Menu.NONE, MENU_SESSION_BASE + index, 20 + index, "$state  ${entry.name}$current")
                }
            }
            if (terminalSession != null) add(Menu.NONE, 9, 40, R.string.om_term_close_this_session)
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    terminalSession?.write("\u0003")
                    true
                }
                2 -> {
                    ScriptExecutionManager.terminateShell(this)
                    updateHudState(
                        isRoot = ScriptExecutionManager.isRoot,
                        isTermex = ScriptExecutionManager.isTermex,
                        isRunning = false,
                        exitStatus = 137
                    )
                    toolbar.subtitle = getString(R.string.om_term_finished_code_format, 137)
                    Toast.makeText(this, R.string.om_term_process_terminated, Toast.LENGTH_SHORT).show()
                    true
                }
                3 -> {
                    startTerminalSession(forceRootOverride = false, forceTermexOverride = false)
                    true
                }
                4 -> {
                    startTerminalSession(forceRootOverride = true, forceTermexOverride = false)
                    true
                }
                5 -> {
                    if (TerminalExpansionManager.isTermexInstalled(this)) {
                        startTerminalSession(forceRootOverride = false, forceTermexOverride = true)
                    } else {
                        TerminalExpansionManager.showExpansionInstallerDialog(this) {
                            if (TerminalExpansionManager.isTermexInstalled(this)) {
                                startTerminalSession(forceRootOverride = false, forceTermexOverride = true)
                            }
                        }
                    }
                    true
                }
                6 -> {
                    startTerminalSession(forceRootOverride = false, forceTermexOverride = null, forceLocal = true)
                    Toast.makeText(this, R.string.om_term_new_session_started, Toast.LENGTH_SHORT).show()
                    true
                }
                7 -> {
                    startActivity(ConnectionsActivity.createIntent(this))
                    true
                }
                9 -> {
                    closeCurrentSession()
                    true
                }
                10 -> {
                    confirmInstallKeyOnServer()
                    true
                }
                in MENU_SESSION_BASE until MENU_SESSION_BASE + 100 -> {
                    ScriptExecutionManager.sessions.getOrNull(item.itemId - MENU_SESSION_BASE)?.let { showEntry(it) }
                    true
                }
                8 -> {
                    remoteProfile?.let { profile ->
                        RemoteProfileStore.get(this, profile.id)?.let { ConnectionsActivity.connect(this, it) }
                    }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /** The SSH/Telnet session on screen died while the app was in the background. */
    private fun showRemoteLostIfNeeded() {
        val session = terminalSession ?: return
        if (!session.isRemote || session.isRunning) return
        val unseen = synchronized(remoteLostUnseen) { remoteLostUnseen.remove(session) }
        if (!unseen) return
        val profile = remoteProfile
        val name = profile?.displayName ?: session.title.orEmpty()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.om_ssh_connection_lost_title)
            .setMessage(getString(R.string.om_ssh_connection_lost_message, name))
            .setCancelable(false)
            .setPositiveButton(R.string.om_remote_files_reconnect) { _, _ ->
                ScriptExecutionManager.entryFor(session)?.let { ScriptExecutionManager.dismiss(it) }
                profile?.let { RemoteProfileStore.get(this, it.id) ?: it }
                    ?.let { me.zhanghai.android.files.terminal.remote.ConnectionsActivity.connect(this, it) }
            }
            .setNegativeButton(R.string.om_ssh_connection_lost_list) { _, _ ->
                // The dead session is dropped (it only held its screen text) and the server list opens.
                ScriptExecutionManager.entryFor(session)?.let { ScriptExecutionManager.dismiss(it) }
                startActivity(
                    me.zhanghai.android.files.terminal.remote.ConnectionsActivity.createIntent(this)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                finish()
            }
            .show()
    }

    private fun copyTerminalTranscript() {
        val transcript = try {
            terminalSession?.emulator?.screen?.transcriptText?.trim()
        } catch (_: Throwable) {
            null
        }
        if (!transcript.isNullOrEmpty()) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("Terminal Output", transcript))
            Toast.makeText(this, R.string.om_term_output_copied, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.om_term_no_text_copy, Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyCwdToClipboard() {
        val pathToCopy = remoteProfile?.let { remoteBreadcrumb(it) } ?: currentCwd.ifBlank { "/storage/emulated/0" }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("Terminal CWD", pathToCopy))
        Toast.makeText(
            this,
            getString(R.string.om_term_cwd_copied_format, pathToCopy),
            Toast.LENGTH_SHORT
        ).show()
    }

    private var openingRemoteFiles = false

    /** Opens the VPS over SFTP in the dual-panel explorer, starting where the shell is. */
    private fun openRemoteFiles(profile: RemoteProfile, typedPassword: String? = null) {
        // The files open as an SFTP channel on this session's own SSH connection when it is up:
        // no second login (which some servers only allow by keyboard-interactive or count against
        // fail2ban), no second host-key prompt.
        val shared = (terminalSession?.takeIf { it.isRemote }?.remoteTransport as? SshTransport)?.sharedClient()
        val auth = RemoteFilesLauncher.authentication(profile, typedPassword)
        if (auth == null && shared == null) {
            askPasswordForRemoteFiles(profile)
            return
        }
        if (openingRemoteFiles) return
        if (terminalSession?.takeIf { it.isRemote }?.isRunning != true) {
            // The files go over the same server: offer to reconnect instead of a confusing error.
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.om_remote_files_error_title)
                .setMessage(getString(R.string.om_remote_files_disconnected, profile.displayName))
                .setPositiveButton(R.string.om_remote_files_reconnect) { _, _ ->
                    RemoteProfileStore.get(this, profile.id)?.let { ConnectionsActivity.connect(this, it) }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        openingRemoteFiles = true
        Toast.makeText(this, getString(R.string.om_remote_files_opening, profile.displayName), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val title = terminalSession?.takeIf { it.isRemote }?.title
            val result = runCatching { RemoteFilesLauncher.resolveStartDirectory(profile, auth, title, shared) }
            openingRemoteFiles = false
            if (isFinishing || isDestroyed) return@launch
            result.onSuccess { dir ->
                startActivity(RemoteFilesLauncher.explorerIntent(dir))
            }.onFailure { e ->
                android.util.Log.w("OMRemoteFiles", "Opening the VPS files failed", e)
                val reason = RemoteFilesLauncher.describeError(this@TerminalActivity, profile, e)
                MaterialAlertDialogBuilder(this@TerminalActivity)
                    .setTitle(R.string.om_remote_files_error_title)
                    .setMessage(reason)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun askPasswordForRemoteFiles(profile: RemoteProfile) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.om_remote_password_hint)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.om_remote_password_title, profile.endpoint))
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val typed = input.text?.toString().orEmpty()
                if (typed.isNotEmpty()) openRemoteFiles(profile, typed)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.apply {
            val wakeTitle = if (wakeLock?.isHeld == true) {
                "${getString(R.string.terminal_wakelock)} (✓)"
            } else {
                getString(R.string.terminal_wakelock)
            }
            add(Menu.NONE, 1, 1, wakeTitle)
            if (remoteProfile == null) add(Menu.NONE, 12, 2, R.string.tracer_menu_item)
            if (remoteProfile?.protocol == RemoteProtocol.SSH) add(Menu.NONE, 13, 2, R.string.om_remote_files_menu)
            add(Menu.NONE, 10, 3, R.string.script_menu_keep_background)
            add(Menu.NONE, 11, 4, R.string.script_menu_terminate)
            add(Menu.NONE, 5, 5, R.string.terminal_paste)
            add(Menu.NONE, 6, 5, R.string.om_term_copy_all_screen)
            add(Menu.NONE, 7, 6, R.string.om_term_clear_screen)
            if (remoteProfile == null) add(Menu.NONE, 8, 7, R.string.om_term_switch_root)
            add(Menu.NONE, 9, 8, R.string.om_term_new_clean_session)
            add(Menu.NONE, 3, 9, R.string.terminal_reset)
            add(Menu.NONE, 4, 10, R.string.terminal_preferences)
            add(Menu.NONE, 2, 11, R.string.terminal_close)
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    toggleWakeLock()
                    true
                }
                13 -> {
                    remoteProfile?.let { openRemoteFiles(it) }
                    true
                }
                2 -> {
                    finish()
                    true
                }
                3 -> {
                    terminalSession?.reset()
                    terminalView.onScreenUpdated()
                    Toast.makeText(this, R.string.terminal_reset, Toast.LENGTH_SHORT).show()
                    true
                }
                4 -> {
                    val dialog = TerminalPreferencesDialog()
                    dialog.onSettingsChanged = {
                        applyPreferences()
                    }
                    dialog.show(supportFragmentManager, "TerminalPreferences")
                    true
                }
                5 -> {
                    pasteFromClipboard()
                    true
                }
                6 -> {
                    copyTerminalTranscript()
                    true
                }
                7 -> {
                    terminalSession?.write("clear\n")
                    true
                }
                8 -> {
                    terminalSession?.write("su\n")
                    true
                }
                9 -> {
                    startTerminalSession(forceRootOverride = false, forceTermexOverride = null)
                    Toast.makeText(this, R.string.om_term_new_session_started, Toast.LENGTH_SHORT).show()
                    true
                }
                10 -> {
                    ScriptExecutionManager.keepInBackground(this)
                    Toast.makeText(this, R.string.script_exec_kept_toast, Toast.LENGTH_SHORT).show()
                    moveTaskToBack(true)
                    true
                }
                11 -> {
                    ScriptExecutionManager.terminateShell(this)
                    Toast.makeText(this, R.string.om_term_process_terminated, Toast.LENGTH_SHORT).show()
                    finish()
                    true
                }
                12 -> {
                    showScriptAuditDialog()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun toggleWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenManager:TerminalWakeLock")
        }

        wakeLock?.let { wl ->
            if (wl.isHeld) {
                wl.release()
                Toast.makeText(this, R.string.terminal_wakelock_released, Toast.LENGTH_SHORT).show()
            } else {
                wl.acquire(60 * 60 * 1000L) // 1 hour max
                Toast.makeText(this, R.string.terminal_wakelock_acquired, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = cm.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(this)?.toString()
            if (!text.isNullOrEmpty()) {
                terminalSession?.write(text)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (terminalSession?.isRunning == false && event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                finish()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finish()
    }

    override fun onDestroy() {
        RemotePrompter.detach(this)
        stopRuntimeTicker()
        detachableClient?.detach(this)
        detachableClient = null
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        if (ScriptExecutionManager.runningCount > 0) {
            // Finished sessions are forgotten; running ones (any of them) keep working.
            ScriptExecutionManager.clearSession()
            Toast.makeText(applicationContext, R.string.script_exec_running_background_toast, Toast.LENGTH_SHORT).show()
        } else {
            ScriptExecutionManager.clearSession()
            ScriptExecutionService.stop(applicationContext)
        }
        super.onDestroy()
    }
}
